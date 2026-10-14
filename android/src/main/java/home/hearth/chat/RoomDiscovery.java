package home.hearth.chat;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lists every Hearth room on the Wi-Fi and lets this phone join one or host its own.
 *
 * <p>Nothing is joined automatically except the room this phone was last in (when it is still on the Wi-Fi), and a
 * phone only hosts a room after its owner creates one.
 */
final class RoomDiscovery {
    private static final String TAG = "HearthDiscovery";
    private static final String SERVICE_TYPE = "_hearthchat._tcp.";
    private static final String SERVICE_PREFIX = "HearthChat-";
    private static final long RETRY_MS = 5_000;
    private static final long QUIET_STARTUP_MS = 8_000;

    interface Listener {
        void onRoomChanged(String url, boolean isHost, String status);

        void onNewRoom(Room room);
    }

    static final class Room {
        final String id;
        final String name;
        final String hostName;
        final String ip;
        final int port;
        final int version;

        Room(String id, String name, String hostName, String ip, int port, int version) {
            this.id = id;
            this.name = name;
            this.hostName = hostName;
            this.ip = ip;
            this.port = port;
            this.version = version;
        }

        String url() {
            return "http://" + ip + ":" + port;
        }
    }

    private static volatile RoomDiscovery current;

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object roomsLock = new Object();
    private final Map<String, Room> rooms = new HashMap<>();
    private final Map<String, String> serviceToRoom = new HashMap<>();
    private final Set<String> resolving = new HashSet<>();
    private final Set<String> announced = new HashSet<>();
    private final NsdManager nsdManager;
    private final SharedPreferences preferences;
    private final String nodeId;
    private final long startedAt = System.currentTimeMillis();
    private WifiManager.MulticastLock multicastLock;
    private NsdManager.DiscoveryListener discoveryListener;
    private NsdManager.RegistrationListener registrationListener;
    private volatile LanServer server;
    private volatile String joinedId = "";
    private String registeredName;
    private boolean discoveryRunning;
    private boolean destroyed;
    private volatile boolean autoJoinPending;
    private final Hotspot hotspot;
    private volatile JSONObject hotspotInfo;
    private volatile boolean joinedHotspot;
    private volatile boolean joiningHotspot;
    private volatile String lastError = "";

    RoomDiscovery(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        preferences = this.context.getSharedPreferences(LanService.PREFS_NAME, Context.MODE_PRIVATE);
        String savedId = preferences.getString(LanService.KEY_NODE_ID, null);
        if (savedId == null || savedId.isEmpty()) {
            savedId = UUID.randomUUID().toString();
            preferences.edit().putString(LanService.KEY_NODE_ID, savedId).apply();
        }
        nodeId = savedId;
        nsdManager = (NsdManager) this.context.getSystemService(Context.NSD_SERVICE);
        hotspot = new Hotspot(this.context);
    }

    static RoomDiscovery current() {
        return current;
    }

    void start() {
        current = this;
        acquireMulticastLock();
        startDiscovery();
        String lastRoom = preferences.getString(LanService.KEY_LAST_ROOM_ID, "");
        if (preferences.getBoolean(LanService.KEY_HOSTING, false) && preferences.getBoolean(KEY_HOTSPOT, false)) {
            publish("", false, "Restarting your hotspot room…");
            startHotspotRoom(nodeId.equals(lastRoom));
        } else if (preferences.getBoolean(LanService.KEY_HOSTING, false)) {
            startHosting();
            if (nodeId.equals(lastRoom)) join(nodeId);
            else publish("", false, "Hosting " + quoted(hostedName()));
        } else {
            autoJoinPending = !lastRoom.isEmpty();
            publish("", false, autoJoinPending ? "Looking for your last room…" : "Choose a room to join");
            // Hotspot rooms are never joined automatically (Android asks before switching Wi-Fi).
            handler.postDelayed(() -> {
                if (autoJoinPending) cancelAutoJoin();
            }, 12_000);
        }
    }

    void stop() {
        destroyed = true;
        if (current == this) current = null;
        stopDiscovery();
        unregisterHost();
        stopServer();
        releaseMulticastLock();
    }

    /* ---------- Actions from the app (main thread) ---------- */

    static final String KEY_HOTSPOT = "hosting_hotspot";

    /** Creates this phone's room, either on the current Wi-Fi or on its own hotspot (no router needed). */
    void createRoom(String name, boolean useHotspot) {
        String cleaned = name == null ? "" : name.trim();
        if (cleaned.isEmpty()) cleaned = ownerName() + "'s room";
        if (cleaned.length() > 40) cleaned = cleaned.substring(0, 40);
        boolean renamed = !cleaned.equals(preferences.getString(LanService.KEY_ROOM_NAME, ""));
        lastError = "";
        if (joinedHotspot) leave();
        if (useHotspot) {
            if (hotspot.hosting() && server != null) {
                preferences.edit().putString(LanService.KEY_ROOM_NAME, cleaned).apply();
                join(nodeId);
                return;
            }
            if (server != null && !hotspot.hosting()) {
                unregisterHost();
                stopServer();
            }
            preferences.edit().putString(LanService.KEY_ROOM_NAME, cleaned).apply();
            startHotspotRoom(true);
            return;
        }
        if (hotspot.hosting()) stopHotspot();
        preferences.edit().putBoolean(LanService.KEY_HOSTING, true).putBoolean(KEY_HOTSPOT, false)
                .putString(LanService.KEY_ROOM_NAME, cleaned).apply();
        if (server != null && renamed) {
            unregisterHost();
            registerHost();
        }
        startHosting();
        join(nodeId);
    }

    private void startHotspotRoom(boolean openIt) {
        joiningHotspot = true;
        hotspot.startHost(new Hotspot.HostCallback() {
            @Override public void onStarted(String ssid, String passphrase, int security, String address) {
                joiningHotspot = false;
                preferences.edit().putBoolean(LanService.KEY_HOSTING, true).putBoolean(KEY_HOTSPOT, true).apply();
                try {
                    JSONObject info = new JSONObject();
                    info.put("id", nodeId);
                    info.put("name", hostedName());
                    info.put("ssid", ssid);
                    info.put("pass", passphrase);
                    info.put("security", security);
                    info.put("ip", address);
                    info.put("port", LanServer.PORT);
                    hotspotInfo = info;
                    BleMesh mesh = BleMesh.current();
                    if (mesh != null) mesh.setHostedRoom(info);
                } catch (JSONException ignored) {
                }
                startHosting();
                if (openIt) join(nodeId);
                else publish("", false, "Hosting " + quoted(hostedName()) + " on this phone's hotspot");
            }

            @Override public void onFailed(String reason) {
                joiningHotspot = false;
                lastError = "permission".equals(reason) ? "permission-hotspot" : reason;
                preferences.edit().putBoolean(KEY_HOTSPOT, false).apply();
                publish("", false, "Hotspot room could not start");
            }
        });
    }

    private void stopHotspot() {
        hotspot.stopHost();
        hotspotInfo = null;
        BleMesh mesh = BleMesh.current();
        if (mesh != null) mesh.setHostedRoom(null);
    }

    void stopHosting() {
        preferences.edit().putBoolean(LanService.KEY_HOSTING, false).putBoolean(KEY_HOTSPOT, false).apply();
        boolean wasJoined = nodeId.equals(joinedId);
        unregisterHost();
        stopServer();
        stopHotspot();
        if (wasJoined) leave();
    }

    String takeError() {
        String error = lastError;
        lastError = "";
        return error;
    }

    void join(String id) {
        autoJoinPending = false;
        if (nodeId.equals(id)) {
            if (server == null) return;
            joinedId = nodeId;
            preferences.edit().putString(LanService.KEY_LAST_ROOM_ID, nodeId).apply();
            String url = "http://" + ownAddress() + ":" + LanServer.PORT;
            publish(url, true, "Hosting " + quoted(hostedName()));
            return;
        }
        Room room;
        synchronized (roomsLock) {
            room = rooms.get(id);
        }
        if (room == null) {
            JSONObject hotspotRoom = findHotspotRoom(id);
            if (hotspotRoom != null) joinHotspotRoom(hotspotRoom);
            return;
        }
        if (joinedHotspot) leave();
        joinedId = id;
        preferences.edit().putString(LanService.KEY_LAST_ROOM_ID, id).apply();
        publish(room.url(), false, "In " + quoted(room.name));
    }

    private JSONObject findHotspotRoom(String id) {
        BleMesh mesh = BleMesh.current();
        if (mesh == null) return null;
        JSONArray found = mesh.hotspotRooms();
        for (int i = 0; i < found.length(); i++) {
            JSONObject room = found.optJSONObject(i);
            if (room != null && id.equals(room.optString("id"))) return room;
        }
        return null;
    }

    private void joinHotspotRoom(JSONObject room) {
        String id = room.optString("id");
        joiningHotspot = true;
        lastError = "";
        publish("", false, "Connecting to " + quoted(room.optString("name")) + "…");
        hotspot.join(room.optString("ssid"), room.optString("pass"), room.optInt("security", 1), room.optString("ip"),
                new Hotspot.JoinCallback() {
                    @Override public void onConnected(String address) {
                        joiningHotspot = false;
                        joinedHotspot = true;
                        joinedId = id;
                        preferences.edit().putString(LanService.KEY_LAST_ROOM_ID, id).apply();
                        String target = address.isEmpty() ? room.optString("ip") : address;
                        publish("http://" + target + ":" + room.optInt("port", LanServer.PORT), false, "In " + quoted(room.optString("name")) + " (hotspot)");
                    }

                    @Override public void onFailed(String reason) {
                        joiningHotspot = false;
                        lastError = reason;
                        publish("", false, "Choose a room to join");
                    }

                    @Override public void onLost() {
                        joinedHotspot = false;
                        if (id.equals(joinedId)) {
                            joinedId = "";
                            lastError = "Lost the hotspot room. Move closer to the host phone and join again.";
                            publish("", false, "Choose a room to join");
                        }
                    }
                });
    }

    private String ownAddress() {
        JSONObject info = hotspotInfo;
        if (info != null && !info.optString("ip").isEmpty()) return info.optString("ip");
        String address = LanServer.getWifiAddress(context);
        return "0.0.0.0".equals(address) ? "127.0.0.1" : address;
    }

    void leave() {
        if (joinedHotspot || joiningHotspot) {
            hotspot.leave();
            joinedHotspot = false;
            joiningHotspot = false;
        }
        autoJoinPending = false;
        joinedId = "";
        preferences.edit().remove(LanService.KEY_LAST_ROOM_ID).apply();
        publish("", false, server != null ? "Hosting " + quoted(hostedName()) : "Choose a room to join");
    }

    void cancelAutoJoin() {
        if (!autoJoinPending) return;
        autoJoinPending = false;
        publish("", false, "Choose a room to join");
    }

    /** Snapshot for the room list screen; safe to call from any thread. */
    JSONObject stateJson() {
        JSONObject state = new JSONObject();
        try {
            state.put("rooms", roomsJson(true));
            state.put("joinedId", joinedId);
            state.put("hosting", server != null);
            state.put("hostedName", hostedName());
            state.put("myId", nodeId);
            state.put("autoJoining", autoJoinPending);
            state.put("lastRoomId", preferences.getString(LanService.KEY_LAST_ROOM_ID, ""));
            state.put("owner", ownerName());
            state.put("wifi", !"0.0.0.0".equals(LanServer.getWifiAddress(context)) || hotspotInfo != null);
            state.put("joining", joiningHotspot);
            state.put("error", lastError);
            JSONObject info = hotspotInfo;
            if (info != null) state.put("hotspot", info);
            BleMesh mesh = BleMesh.current();
            if (mesh != null) {
                JSONObject meshState = mesh.state();
                JSONObject summary = new JSONObject();
                summary.put("status", meshState.optString("status"));
                summary.put("running", meshState.optBoolean("running"));
                summary.put("permission", meshState.optBoolean("permission"));
                JSONArray meshPeers = meshState.optJSONArray("peers");
                summary.put("peers", meshPeers == null ? 0 : meshPeers.length());
                summary.put("longRange", meshState.optBoolean("longRange"));
                state.put("mesh", summary);
            }
        } catch (JSONException ignored) {
        }
        return state;
    }

    /** Rooms on the Wi-Fi; when includeOwn is set, this phone's hosted room is listed first. */
    JSONArray roomsJson(boolean includeOwn) {
        JSONArray list = new JSONArray();
        try {
            if (includeOwn && server != null) {
                JSONObject own = roomJson(new Room(nodeId, hostedName(), ownerName(), ownAddress(), LanServer.PORT, 3), true);
                own.put("type", hotspotInfo != null ? "hotspot" : "wifi");
                list.put(own);
            }
            List<Room> found;
            synchronized (roomsLock) {
                found = new ArrayList<>(rooms.values());
            }
            Set<String> listed = new HashSet<>();
            listed.add(nodeId);
            for (Room room : found) {
                JSONObject item = roomJson(room, false);
                item.put("type", "wifi");
                list.put(item);
                listed.add(room.id);
            }
            // Hotspot rooms heard about over Bluetooth that are not already reachable on this Wi-Fi.
            BleMesh mesh = BleMesh.current();
            JSONArray hotspots = mesh == null ? new JSONArray() : mesh.hotspotRooms();
            for (int i = 0; i < hotspots.length(); i++) {
                JSONObject room = hotspots.optJSONObject(i);
                if (room == null || listed.contains(room.optString("id"))) continue;
                JSONObject item = new JSONObject();
                item.put("id", room.optString("id"));
                item.put("name", room.optString("name", "Hotspot room"));
                item.put("host", room.optString("host"));
                item.put("url", "http://" + room.optString("ip") + ":" + room.optInt("port", LanServer.PORT));
                item.put("secureUrl", "https://" + room.optString("ip") + ":" + LanServer.SECURE_PORT);
                item.put("type", "hotspot");
                item.put("hops", room.optInt("hops", 1));
                item.put("mine", false);
                item.put("joined", room.optString("id").equals(joinedId));
                list.put(item);
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    private JSONObject roomJson(Room room, boolean mine) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("id", room.id);
        item.put("name", room.name);
        item.put("host", room.hostName);
        item.put("url", room.url());
        item.put("secureUrl", "https://" + room.ip + ":" + LanServer.SECURE_PORT);
        item.put("version", room.version);
        item.put("mine", mine);
        item.put("joined", room.id.equals(joinedId));
        return item;
    }

    String hostedName() {
        String name = preferences.getString(LanService.KEY_ROOM_NAME, "");
        return name.isEmpty() ? ownerName() + "'s room" : name;
    }

    String ownerName() {
        String name = preferences.getString(LanService.KEY_USER_NAME, "");
        return name.isEmpty() ? deviceName(context) : name;
    }

    /** The phone's friendly name from Settings (for example "Galaxy S24"), falling back to its model. */
    static String deviceName(Context context) {
        try {
            String name = android.provider.Settings.Global.getString(context.getContentResolver(), "device_name");
            if (name != null && !name.trim().isEmpty()) return name.trim();
        } catch (Exception ignored) {
        }
        String model = Build.MODEL == null ? "Phone" : Build.MODEL;
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        if (!maker.isEmpty() && !model.toLowerCase(java.util.Locale.ROOT).startsWith(maker.toLowerCase(java.util.Locale.ROOT))) {
            model = Character.toUpperCase(maker.charAt(0)) + maker.substring(1) + " " + model;
        }
        return model;
    }

    private static String quoted(String name) {
        return "“" + name + "”";
    }

    /* ---------- Hosting ---------- */

    private void startHosting() {
        if (destroyed || server != null) return;
        LanServer created = new LanServer(context, new LanServer.RoomInfo() {
            @Override public String id() {
                return nodeId;
            }

            @Override public String name() {
                return hostedName();
            }

            @Override public JSONArray otherRooms() {
                return roomsJson(false);
            }

            @Override public String address() {
                return ownAddress();
            }
        });
        if (!created.start()) {
            created.close();
            publish("", false, "Could not start your room. Is another app using port " + LanServer.PORT + "?");
            return;
        }
        server = created;
        registerHost();
    }

    private void registerHost() {
        if (nsdManager == null || server == null || registrationListener != null) return;
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(SERVICE_PREFIX + nodeId.substring(0, Math.min(8, nodeId.length())));
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(LanServer.PORT);
        serviceInfo.setAttribute("node", nodeId);
        serviceInfo.setAttribute("history", "1");
        serviceInfo.setAttribute("name", trimUtf8(hostedName(), 120));
        serviceInfo.setAttribute("owner", trimUtf8(ownerName(), 60));
        serviceInfo.setAttribute("v", "3");
        registrationListener = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo registered) {
                handler.post(() -> registeredName = registered.getServiceName());
            }

            @Override public void onRegistrationFailed(NsdServiceInfo service, int errorCode) {
                handler.post(() -> {
                    Log.e(TAG, "Room registration failed: " + errorCode);
                    registrationListener = null;
                    handler.postDelayed(() -> {
                        if (!destroyed && server != null) registerHost();
                    }, RETRY_MS);
                });
            }

            @Override public void onServiceUnregistered(NsdServiceInfo service) {
                handler.post(() -> registeredName = null);
            }

            @Override public void onUnregistrationFailed(NsdServiceInfo service, int errorCode) {
                Log.w(TAG, "Room unregistration failed: " + errorCode);
            }
        };
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        } catch (IllegalArgumentException | IllegalStateException e) {
            registrationListener = null;
            Log.e(TAG, "Could not register room", e);
        }
    }

    private static String trimUtf8(String value, int maxBytes) {
        String result = value;
        while (result.getBytes(StandardCharsets.UTF_8).length > maxBytes) result = result.substring(0, result.length() - 1);
        return result;
    }

    private void unregisterHost() {
        if (nsdManager != null && registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener);
            } catch (Exception ignored) {
            }
        }
        registrationListener = null;
        registeredName = null;
    }

    private void stopServer() {
        if (server != null) server.close();
        server = null;
    }

    /* ---------- Discovery ---------- */

    private void startDiscovery() {
        if (destroyed || discoveryRunning || nsdManager == null) return;
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) {
                discoveryRunning = true;
            }

            @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                handler.post(() -> resolve(serviceInfo));
            }

            @Override public void onServiceLost(NsdServiceInfo serviceInfo) {
                handler.post(() -> {
                    String name = serviceInfo.getServiceName();
                    resolving.remove(name);
                    String id = serviceToRoom.remove(name);
                    if (id == null) return;
                    synchronized (roomsLock) {
                        rooms.remove(id);
                    }
                });
            }

            @Override public void onDiscoveryStopped(String serviceType) {
                discoveryRunning = false;
            }

            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                discoveryRunning = false;
                Log.e(TAG, "Room discovery failed to start: " + errorCode);
                handler.postDelayed(() -> {
                    if (!destroyed) startDiscovery();
                }, RETRY_MS);
            }

            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Room discovery failed to stop: " + errorCode);
            }
        };
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (IllegalArgumentException | IllegalStateException e) {
            discoveryListener = null;
            Log.e(TAG, "Could not start room discovery", e);
            handler.postDelayed(() -> {
                if (!destroyed) startDiscovery();
            }, RETRY_MS);
        }
    }

    private void resolve(NsdServiceInfo serviceInfo) {
        String name = serviceInfo.getServiceName();
        if (destroyed || name == null || name.equals(registeredName) || resolving.contains(name)) return;
        resolving.add(name);
        try {
            nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo failedInfo, int errorCode) {
                    handler.post(() -> resolving.remove(name));
                    // Resolution fails when several resolves overlap; try again shortly.
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                        handler.postDelayed(() -> resolve(serviceInfo), 600);
                    }
                }

                @Override public void onServiceResolved(NsdServiceInfo resolvedInfo) {
                    handler.post(() -> acceptResolvedService(resolvedInfo));
                }
            });
        } catch (IllegalArgumentException | IllegalStateException e) {
            resolving.remove(name);
            Log.w(TAG, "Could not resolve room service", e);
        }
    }

    private void acceptResolvedService(NsdServiceInfo serviceInfo) {
        String name = serviceInfo.getServiceName();
        resolving.remove(name);
        Map<String, byte[]> attributes = serviceInfo.getAttributes();
        String id = attribute(attributes, "node");
        InetAddress address = serviceInfo.getHost();
        if (id.isEmpty() || id.equals(nodeId) || !(address instanceof Inet4Address)) return;
        String roomName = attribute(attributes, "name");
        String owner = attribute(attributes, "owner");
        int version = (int) Math.max(1, parseInt(attribute(attributes, "v")));
        if (roomName.isEmpty()) roomName = "Hearth room";
        Room room = new Room(id, roomName, owner.isEmpty() ? "Older Hearth app" : owner,
                address.getHostAddress(), serviceInfo.getPort(), version);
        Room previous;
        synchronized (roomsLock) {
            previous = rooms.put(id, room);
        }
        serviceToRoom.put(name, id);

        if (id.equals(joinedId) && (previous == null || !previous.ip.equals(room.ip))) {
            publish(room.url(), false, "In " + quoted(room.name));
        } else if (autoJoinPending && id.equals(preferences.getString(LanService.KEY_LAST_ROOM_ID, ""))) {
            join(id);
        }
        if (!announced.contains(id)) {
            announced.add(id);
            if (System.currentTimeMillis() - startedAt > QUIET_STARTUP_MS && !id.equals(joinedId)) listener.onNewRoom(room);
        }
    }

    private static String attribute(Map<String, byte[]> attributes, String key) {
        byte[] value = attributes == null ? null : attributes.get(key);
        return value == null ? "" : new String(value, StandardCharsets.UTF_8);
    }

    private static long parseInt(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private void stopDiscovery() {
        if (!discoveryRunning || discoveryListener == null || nsdManager == null) return;
        try {
            nsdManager.stopServiceDiscovery(discoveryListener);
        } catch (Exception ignored) {
        }
        discoveryRunning = false;
        discoveryListener = null;
    }

    private void publish(String url, boolean isHost, String status) {
        preferences.edit()
                .putString(LanService.KEY_ROOM_URL, url == null ? "" : url)
                .putBoolean(LanService.KEY_IS_HOST, isHost)
                .apply();
        listener.onRoomChanged(url == null ? "" : url, isHost, status);
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return;
            multicastLock = wifi.createMulticastLock("hearth-chat-discovery");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "Could not acquire Wi-Fi multicast lock", e);
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Exception ignored) {
        }
        multicastLock = null;
    }
}
