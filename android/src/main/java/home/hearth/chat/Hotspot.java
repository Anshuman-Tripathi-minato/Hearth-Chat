package home.hearth.chat;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * Hotspot rooms: when there is no Wi-Fi router, one phone starts its own local-only hotspot and hosts the room on it.
 * Other phones learn the hotspot's name and password over the Bluetooth mesh and join it with one tap (Android 10+),
 * then the room works exactly like a normal Wi-Fi room: chat, files, voice and video calls, group calls.
 */
@SuppressLint("MissingPermission")
final class Hotspot {
    private static final String TAG = "HearthHotspot";

    interface HostCallback {
        void onStarted(String ssid, String passphrase, int security, String address);

        void onFailed(String reason);
    }

    interface JoinCallback {
        void onConnected(String address);

        void onFailed(String reason);

        void onLost();
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private ConnectivityManager.NetworkCallback joinCallback;

    Hotspot(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean hosting() {
        return reservation != null;
    }

    /* ---------- Hosting ---------- */

    void startHost(HostCallback callback) {
        if (reservation != null) {
            callback.onFailed("Hotspot already running");
            return;
        }
        String[] missing = MeshPermissions.missing(context, MeshPermissions.hotspot());
        if (missing.length > 0) {
            callback.onFailed("permission");
            return;
        }
        if (Build.VERSION.SDK_INT < 33 && !locationOn()) {
            callback.onFailed("Turn on Location (Android needs it to start a hotspot), then try again.");
            return;
        }
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            callback.onFailed("This phone has no Wi-Fi.");
            return;
        }
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation started) {
                    reservation = started;
                    String ssid = "";
                    String passphrase = "";
                    int security = 1;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        SoftApConfiguration config = started.getSoftApConfiguration();
                        ssid = config.getSsid() == null ? "" : config.getSsid();
                        passphrase = config.getPassphrase() == null ? "" : config.getPassphrase();
                        security = config.getSecurityType();
                    } else {
                        WifiConfiguration config = started.getWifiConfiguration();
                        if (config != null) {
                            ssid = config.SSID == null ? "" : config.SSID;
                            passphrase = config.preSharedKey == null ? "" : config.preSharedKey;
                        }
                    }
                    final String name = ssid;
                    final String key = passphrase;
                    final int type = security;
                    // The hotspot interface needs a moment to get its address.
                    waitForAddress(0, address -> callback.onStarted(name, key, type, address));
                }

                @Override public void onStopped() {
                    reservation = null;
                }

                @Override public void onFailed(int reason) {
                    reservation = null;
                    callback.onFailed(reason == ERROR_INCOMPATIBLE_MODE
                            ? "This phone can't run a hotspot while connected to Wi-Fi or tethering. Turn those off and try again."
                            : reason == ERROR_TETHERING_DISALLOWED ? "Hotspots are turned off on this phone."
                            : "Could not start the hotspot (error " + reason + ").");
                }
            }, main);
        } catch (Exception e) {
            callback.onFailed("Could not start the hotspot: " + e.getMessage());
        }
    }

    interface AddressCallback {
        void onAddress(String address);
    }

    private void waitForAddress(int attempt, AddressCallback callback) {
        String address = hotspotAddress();
        if (!address.isEmpty() || attempt > 20) {
            callback.onAddress(address.isEmpty() ? "192.168.43.1" : address);
            return;
        }
        main.postDelayed(() -> waitForAddress(attempt + 1, callback), 250);
    }

    void stopHost() {
        if (reservation != null) {
            try {
                reservation.close();
            } catch (Exception ignored) {
            }
        }
        reservation = null;
    }

    /** The IPv4 address of this phone on its own hotspot (the soft-AP interface, not the Wi-Fi client). */
    static String hotspotAddress() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                String name = network.getName();
                boolean softAp = name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap")
                        || name.equals("wlan1") || name.equals("wlan2");
                if (!softAp) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) return address.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /* ---------- Joining (Android 10+) ---------- */

    void join(String ssid, String passphrase, int security, String hostAddress, JoinCallback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            callback.onFailed("On this Android version, join the Wi-Fi “" + ssid + "” (password " + passphrase + ") in Settings, then open the room.");
            return;
        }
        leave();
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        WifiNetworkSpecifier.Builder specifier = new WifiNetworkSpecifier.Builder().setSsid(ssid);
        if (security == 3) specifier.setWpa3Passphrase(passphrase);
        else if (!passphrase.isEmpty()) specifier.setWpa2Passphrase(passphrase);
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier.build())
                .build();
        joinCallback = new ConnectivityManager.NetworkCallback() {
            private boolean connected;

            @Override public void onAvailable(Network network) {
                connected = true;
                // Route this app's traffic (including the chat page) through the hotspot.
                connectivity.bindProcessToNetwork(network);
                String address = hostAddress;
                LinkProperties link = connectivity.getLinkProperties(network);
                if ((address == null || address.isEmpty()) && link != null) address = gatewayOf(link);
                final String target = address == null ? "" : address;
                main.post(() -> callback.onConnected(target));
            }

            @Override public void onUnavailable() {
                main.post(() -> callback.onFailed("Couldn't connect to the hotspot. Move closer to the host phone and try again."));
            }

            @Override public void onLost(Network network) {
                connectivity.bindProcessToNetwork(null);
                if (connected) main.post(callback::onLost);
            }
        };
        try {
            connectivity.requestNetwork(request, joinCallback, 30_000);
        } catch (Exception e) {
            joinCallback = null;
            callback.onFailed("Couldn't connect to the hotspot: " + e.getMessage());
        }
    }

    void leave() {
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (joinCallback != null) {
            try {
                connectivity.unregisterNetworkCallback(joinCallback);
            } catch (Exception ignored) {
            }
            joinCallback = null;
            connectivity.bindProcessToNetwork(null);
        }
    }

    private static String gatewayOf(LinkProperties link) {
        for (RouteInfo route : link.getRoutes()) {
            InetAddress gateway = route.getGateway();
            if (gateway instanceof Inet4Address && !gateway.isAnyLocalAddress()) return gateway.getHostAddress();
        }
        for (LinkAddress address : link.getLinkAddresses()) {
            if (address.getAddress() instanceof Inet4Address) {
                byte[] raw = address.getAddress().getAddress();
                raw[3] = 1;
                try {
                    return InetAddress.getByAddress(raw).getHostAddress();
                } catch (Exception ignored) {
                }
            }
        }
        return "";
    }

    private boolean locationOn() {
        android.location.LocationManager location = (android.location.LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (location == null) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return location.isLocationEnabled();
        return location.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                || location.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
    }

    static void log(String message) {
        Log.i(TAG, message);
    }
}
