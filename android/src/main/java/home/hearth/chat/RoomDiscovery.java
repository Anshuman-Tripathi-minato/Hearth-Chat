package home.hearth.chat;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Discovers one shared Hearth Chat room on the local Wi-Fi network. */
final class RoomDiscovery {
    private static final String TAG = "HearthDiscovery";
    private static final String SERVICE_TYPE = "_hearthchat._tcp.";
    private static final String SERVICE_PREFIX = "HearthChat-";
    private static final long STARTUP_GRACE_MS = 4_000;
    private static final long ELECTION_DELAY_MS = 700;
    private static final long HOST_RETRY_MS = 5_000;

    interface Listener {
        void onRoomChanged(String url, boolean isHost, String status);
    }

    private static final class Candidate {
        final String nodeId;
        final String url;
        final boolean hasHistory;

        Candidate(String nodeId, String url, boolean hasHistory) {
            this.nodeId = nodeId;
            this.url = url;
            this.hasHistory = hasHistory;
        }
    }

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Candidate> candidates = new HashMap<>();
    private final Map<String, Boolean> resolving = new HashMap<>();
    private final NsdManager nsdManager;
    private final String nodeId;
    private final String instanceName;
    private final boolean hasSavedHistory;
    private WifiManager.MulticastLock multicastLock;
    private NsdManager.DiscoveryListener discoveryListener;
    private NsdManager.RegistrationListener registrationListener;
    private LanServer server;
    private String roomUrl = "";
    private String registeredName;
    private boolean discoveryRunning;
    private boolean hosting;
    private boolean destroyed;

    private final Runnable election = this::electRoom;
    private final Runnable hostFallback = () -> {
        if (!destroyed && !hosting && candidates.isEmpty()) becomeHost();
    };

    RoomDiscovery(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        SharedPreferences preferences = this.context.getSharedPreferences(LanService.PREFS_NAME, Context.MODE_PRIVATE);
        String savedId = preferences.getString(LanService.KEY_NODE_ID, null);
        if (savedId == null || savedId.isEmpty()) {
            savedId = UUID.randomUUID().toString();
            preferences.edit().putString(LanService.KEY_NODE_ID, savedId).apply();
        }
        nodeId = savedId;
        instanceName = SERVICE_PREFIX + nodeId.substring(0, Math.min(8, nodeId.length()));
        File historyFile = new File(this.context.getFilesDir(), "messages.json");
        hasSavedHistory = historyFile.isFile() && historyFile.length() > 2;
        nsdManager = (NsdManager) this.context.getSystemService(Context.NSD_SERVICE);
    }

    void start() {
        if (nsdManager == null) {
            publish("", false, "Wi-Fi discovery is unavailable");
            handler.postDelayed(hostFallback, STARTUP_GRACE_MS);
            return;
        }
        acquireMulticastLock();
        startDiscovery();
        publish("", false, "Finding a Hearth Chat room on Wi-Fi…");
        handler.postDelayed(hostFallback, STARTUP_GRACE_MS);
    }

    void stop() {
        destroyed = true;
        handler.removeCallbacks(election);
        handler.removeCallbacks(hostFallback);
        stopDiscovery();
        unregisterHost();
        stopServer();
        releaseMulticastLock();
    }

    private void startDiscovery() {
        if (destroyed || discoveryRunning || nsdManager == null) return;
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) {
                discoveryRunning = true;
            }

            @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (!SERVICE_TYPE.equals(serviceInfo.getServiceType())) return;
                String name = serviceInfo.getServiceName();
                if (name == null || name.equals(registeredName) || resolving.containsKey(name)) return;
                resolving.put(name, true);
                try {
                    nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo failedInfo, int errorCode) {
                            handler.post(() -> {
                                resolving.remove(name);
                                Log.w(TAG, "Room resolve failed: " + errorCode);
                            });
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

            @Override public void onServiceLost(NsdServiceInfo serviceInfo) {
                handler.post(() -> {
                    String name = serviceInfo.getServiceName();
                    candidates.remove(name);
                    resolving.remove(name);
                    scheduleElection();
                });
            }

            @Override public void onDiscoveryStopped(String serviceType) {
                discoveryRunning = false;
            }

            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                discoveryRunning = false;
                Log.e(TAG, "Room discovery failed to start: " + errorCode);
                try { nsdManager.stopServiceDiscovery(this); } catch (Exception ignored) { }
                handler.postDelayed(() -> {
                    if (!destroyed) startDiscovery();
                }, HOST_RETRY_MS);
            }

            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Room discovery failed to stop: " + errorCode);
                try { nsdManager.stopServiceDiscovery(this); } catch (Exception ignored) { }
            }
        };
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (IllegalArgumentException | IllegalStateException e) {
            discoveryListener = null;
            Log.e(TAG, "Could not start room discovery", e);
            handler.postDelayed(() -> {
                if (!destroyed) startDiscovery();
            }, HOST_RETRY_MS);
        }
    }

    private void stopDiscovery() {
        if (!discoveryRunning || discoveryListener == null || nsdManager == null) return;
        try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (Exception ignored) { }
        discoveryRunning = false;
        discoveryListener = null;
    }

    private void acceptResolvedService(NsdServiceInfo serviceInfo) {
        String name = serviceInfo.getServiceName();
        resolving.remove(name);
        Map<String, byte[]> attributes = serviceInfo.getAttributes();
        byte[] nodeValue = attributes == null ? null : attributes.get("node");
        byte[] historyValue = attributes == null ? null : attributes.get("history");
        InetAddress address = serviceInfo.getHost();
        if (nodeValue == null || address == null || !(address instanceof Inet4Address)) return;
        String remoteNode = new String(nodeValue, StandardCharsets.UTF_8);
        if (remoteNode.isEmpty() || remoteNode.equals(nodeId)) return;
        String url = "http://" + address.getHostAddress() + ":" + serviceInfo.getPort();
        boolean remoteHasHistory = historyValue != null && "1".equals(new String(historyValue, StandardCharsets.UTF_8));
        candidates.put(name, new Candidate(remoteNode, url, remoteHasHistory));
        scheduleElection();
    }

    private void scheduleElection() {
        handler.removeCallbacks(hostFallback);
        handler.removeCallbacks(election);
        handler.postDelayed(election, ELECTION_DELAY_MS);
    }

    private void electRoom() {
        if (destroyed) return;
        Candidate winner = null;
        String winningNode = nodeId;
        boolean winningHasHistory = hasSavedHistory;
        Iterator<Map.Entry<String, Candidate>> iterator = candidates.entrySet().iterator();
        while (iterator.hasNext()) {
            Candidate candidate = iterator.next().getValue();
            if (candidate.url == null || candidate.nodeId == null) continue;
            if ((candidate.hasHistory && !winningHasHistory)
                    || (candidate.hasHistory == winningHasHistory && candidate.nodeId.compareTo(winningNode) < 0)) {
                winner = candidate;
                winningNode = candidate.nodeId;
                winningHasHistory = candidate.hasHistory;
            }
        }

        if (winningNode.equals(nodeId)) {
            if (!hosting) becomeHost();
            else publish(roomUrl, true, "This phone is hosting the shared room");
            return;
        }

        if (hosting) {
            unregisterHost();
            stopServer();
            hosting = false;
        }
        if (winner != null) {
            roomUrl = winner.url;
            publish(roomUrl, false, "Joined the shared room on Wi-Fi");
        }
    }

    private void becomeHost() {
        if (destroyed || hosting) return;
        server = new LanServer(context);
        if (!server.start()) {
            server = null;
            publish("", false, "Could not start the room service");
            handler.postDelayed(hostFallback, HOST_RETRY_MS);
            return;
        }
        hosting = true;
        String address = LanServer.getWifiAddress(context);
        roomUrl = "0.0.0.0".equals(address) ? "http://127.0.0.1:" + LanServer.PORT
                : "http://" + address + ":" + LanServer.PORT;
        publish(roomUrl, true, "This phone is hosting the shared room");
        registerHost();
    }

    private void registerHost() {
        if (nsdManager == null || !hosting || registrationListener != null) return;
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(instanceName);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(LanServer.PORT);
        serviceInfo.setAttribute("node", nodeId);
        serviceInfo.setAttribute("history", hasSavedHistory ? "1" : "0");
        registrationListener = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo registered) {
                handler.post(() -> registeredName = registered.getServiceName());
            }

            @Override public void onRegistrationFailed(NsdServiceInfo service, int errorCode) {
                handler.post(() -> {
                    Log.e(TAG, "Room registration failed: " + errorCode);
                    registrationListener = null;
                    handler.postDelayed(() -> {
                        if (!destroyed && hosting) registerHost();
                    }, HOST_RETRY_MS);
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

    private void unregisterHost() {
        if (nsdManager != null && registrationListener != null) {
            try { nsdManager.unregisterService(registrationListener); } catch (Exception ignored) { }
        }
        registrationListener = null;
        registeredName = null;
    }

    private void stopServer() {
        if (server != null) server.close();
        server = null;
    }

    private void publish(String url, boolean isHost, String status) {
        roomUrl = url == null ? "" : url;
        context.getSharedPreferences(LanService.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(LanService.KEY_ROOM_URL, roomUrl)
                .putBoolean(LanService.KEY_IS_HOST, isHost)
                .apply();
        listener.onRoomChanged(roomUrl, isHost, status);
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
        } catch (Exception ignored) { }
        multicastLock = null;
    }
}
