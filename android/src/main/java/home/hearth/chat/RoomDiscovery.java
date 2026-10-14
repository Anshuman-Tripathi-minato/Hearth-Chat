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
    }

    static RoomDiscovery current() {
        return current;
    }

    void start() {
        current = this;
        acquireMulticastLock();
        startDiscovery();
        String lastRoom = preferences.getString(LanService.KEY_LAST_ROOM_ID, "");
        if (preferences.getBoolean(LanService.KEY_HOSTING, false)) {
            startHosting();
            if (nodeId.equals(lastRoom)) join(nodeId);
            else publish("", false, "Hosting " + quoted(hostedName()));
        } else {
            autoJoinPending = !lastRoom.isEmpty();
            publish("", false, autoJoinPending ? "Looking for your last room…" : "Choose a room to join");
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

    void createRoom(String name) {
        String cleaned = name == null ? "" : name.trim();
        if (cleaned.isEmpty()) cleaned = ownerName() + "'s room";
        if (cleaned.length() > 40) cleaned = cleaned.substring(0, 40);
        boolean renamed = !cleaned.equals(preferences.getString(LanService.KEY_ROOM_NAME, ""));
        preferences.edit().putBoolean(LanService.KEY_HOSTING, true).putString(LanService.KEY_ROOM_NAME, cleaned).apply();
        if (server != null && renamed) {
            unregisterHost();
            registerHost();
        }
        startHosting();
        join(nodeId);
    }

    void stopHosting() {
        preferences.edit().putBoolean(LanService.KEY_HOSTING, false).apply();
        boolean wasJoined = nodeId.equals(joinedId);
        unregisterHost();
        stopServer();
        if (wasJoined) leave();
    }

    void join(String id) {
        autoJoinPending = false;
        if (nodeId.equals(id)) {
            if (server == null) return;
            joinedId = nodeId;
            preferences.edit().putString(LanService.KEY_LAST_ROOM_ID, nodeId).apply();
            String address = LanServer.getWifiAddress(context);
            String url = "0.0.0.0".equals(address) ? "http://127.0.0.1:" + LanServer.PORT : "http://" + address + ":" + LanServer.PORT;
            publish(url, true, "Hosting " + quoted(hostedName()));
            return;
        }
        Room room;
        synchronized (roomsLock) {
            room = rooms.get(id);
        }
        if (room == null) return;
        joinedId = id;
        preferences.edit().putString(LanService.KEY_LAST_ROOM_ID, id).apply();
        publish(room.url(), false, "In " + quoted(room.name));
    }

    void leave() {
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
            state.put("wifi", !"0.0.0.0".equals(LanServer.getWifiAddress(context)));
        } catch (JSONException ignored) {
        }
        return state;
    }

    /** Rooms on the Wi-Fi; when includeOwn is set, this phone's hosted room is listed first. */
    JSONArray roomsJson(boolean includeOwn) {
        JSONArray list = new JSONArray();
        try {
            if (includeOwn && server != null) {
                String address = LanServer.getWifiAddress(context);
                list.put(roomJson(new Room(nodeId, hostedName(), ownerName(), "0.0.0.0".equals(address) ? "127.0.0.1" : address,
                        LanServer.PORT, 3), true));
            }
            List<Room> found;
            synchronized (roomsLock) {
                found = new ArrayList<>(rooms.values());
            }
            for (Room room : found) list.put(roomJson(room, false));
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
        return name.isEmpty() ? Build.MODEL : name;
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
