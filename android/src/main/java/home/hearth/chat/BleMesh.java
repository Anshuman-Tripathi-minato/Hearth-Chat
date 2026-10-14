package home.hearth.chat;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelUuid;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Bluetooth Low Energy mesh: phones relay messages for each other, so a message can reach people far beyond one
 * phone's Bluetooth range (like BitChat or Bridgefy). Every phone advertises and scans; the phone with the lower id
 * opens the connection, so each pair of phones shares exactly one link.
 *
 * <p>On every link the GATT characteristic carries framed packets: a central writes to it and the peripheral
 * notifies on it. Mesh packets are flooded with a hop limit and de-duplicated by id. When two phones meet they
 * exchange inventories of recent messages, so history spreads even when people move between groups.
 *
 * <p>Private messages are end-to-end encrypted (ECDH P-256 + AES-GCM); relaying phones only see who a packet is for.
 * Phones that support Bluetooth LE Coded PHY (long range) also advertise and connect over it automatically.
 */
@SuppressLint("MissingPermission")
final class BleMesh {
    private static final String TAG = "HearthMesh";
    static final UUID SERVICE = UUID.fromString("7d2ea001-5f3c-4c8e-9b61-2a4f1e0c8d90");
    static final UUID DATA = UUID.fromString("7d2ea002-5f3c-4c8e-9b61-2a4f1e0c8d90");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int MANUFACTURER_ID = 0xFFFF;
    private static final int MAX_LINKS = 7;
    private static final int DEFAULT_TTL = 7;
    private static final int MAX_PACKET = 1536 * 1024;
    static final int MAX_ATTACHMENT = 700 * 1024;
    private static final long ANNOUNCE_MS = 25_000;
    private static final long PEER_EXPIRY_MS = 95_000;
    /** A link that has heard nothing for this long is dead (neighbours announce every 25 s); close it so we reconnect. */
    private static final long LINK_SILENCE_MS = 75_000;
    private static final long SYNC_WINDOW_MS = 24L * 3600 * 1000;

    static final byte T_MSG = 1;
    static final byte T_ACK = 2;
    static final byte T_INV = 3;
    static final byte T_REQ = 4;
    static final byte T_HELLO = 5;
    static final byte T_CALL = 6;
    static final byte T_ANNOUNCE = 8;
    static final byte AUDIO_MARK = (byte) 0xA1;
    private static final int HEADER = 1 + 1 + 1 + 16 + 8 + 8;
    private static final byte[] BROADCAST = new byte[8];

    interface Listener {
        void onMeshEvent(JSONObject event);
    }

    /** Receives call audio and call signals from direct neighbours. */
    interface CallSink {
        void onCallSignal(String peer, JSONObject signal);

        void onAudio(String peer, byte[] packet);
    }

    private static volatile BleMesh current;

    private final Context context;
    private final SharedPreferences preferences;
    private final HandlerThread thread = new HandlerThread("hearth-mesh");
    private final Handler handler;
    private final SecureRandom random = new SecureRandom();
    private final String nodeId;
    private final byte[] nodeBytes;
    private final KeyPair keys;
    private final Map<String, Link> links = new ConcurrentHashMap<>();
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    /** Private messages that arrived before we learned the sender's key; retried when the key shows up. */
    private final List<byte[]> waitingForKey = new ArrayList<>();
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<String, Long>(4096, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 20_000;
        }
    };
    private final LinkedHashMap<String, Stored> store = new LinkedHashMap<String, Stored>(512, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Stored> eldest) {
            return size() > 600;
        }
    };
    private final List<JSONObject> log = new ArrayList<>();
    private final File logFile;
    private volatile Listener listener;
    private volatile Listener backgroundListener;
    private volatile CallSink callSink;
    private volatile JSONObject hostedRoom;
    private volatile boolean running;
    private volatile String status = "Off";
    private BluetoothManager manager;
    private BluetoothAdapter adapter;
    private BluetoothGattServer server;
    private BluetoothGattCharacteristic serverCharacteristic;
    private BluetoothLeAdvertiser advertiser;
    private final List<AdvertisingSetCallback> advertising = new ArrayList<>();
    private AdvertiseCallback plainAdvertising;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private ScanCallback codedScanCallback;
    /* Diagnostics shown on the Bluetooth screen, so a phone that finds nobody can say why. */
    private final Map<String, Long> sightings = new ConcurrentHashMap<>();
    private final Map<String, Long> firstSeen = new ConcurrentHashMap<>();
    private final Map<String, Long> retryAt = new ConcurrentHashMap<>();
    private final Map<String, Integer> connectFailures = new ConcurrentHashMap<>();
    private volatile String advertiseState = "starting";
    private volatile String scanState = "starting";
    private volatile String lastConnectError = "";
    private boolean longRange;
    private boolean persistQueued;

    private static final class Stored {
        final byte[] packet;
        final long time;

        Stored(byte[] packet, long time) {
            this.packet = packet;
            this.time = time;
        }
    }

    private static final class Peer {
        String id;
        String name = "Someone";
        PublicKey key;
        String keyText = "";
        long lastSeen;
        int hops = 99;
        JSONObject room;
    }

    /** One Bluetooth connection to a neighbouring phone. */
    private final class Link {
        final String address;
        final boolean central;
        BluetoothGatt gatt;
        BluetoothGattCharacteristic remote;
        BluetoothDevice device;
        String shortId = "";
        String nodeId = "";
        int mtu = 23;
        boolean ready;
        boolean busy;
        boolean coded;
        int failures;
        final ArrayDeque<byte[]> urgent = new ArrayDeque<>();
        final ArrayDeque<byte[]> normal = new ArrayDeque<>();
        final ByteArrayOutputStream[] rx = {new ByteArrayOutputStream(), new ByteArrayOutputStream()};
        final int[] rxExpected = {-1, -1};
        long openedAt = System.currentTimeMillis();
        long lastActivity = System.currentTimeMillis();
        long lastReceived = System.currentTimeMillis();

        Link(String address, boolean central) {
            this.address = address;
            this.central = central;
        }

        int chunkSize() {
            // Android refuses any write or notification above 512 bytes (the largest attribute value), whatever the MTU.
            return Math.max(20, Math.min(mtu - 3, 512));
        }
    }

    BleMesh(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(LanService.PREFS_NAME, Context.MODE_PRIVATE);
        thread.start();
        handler = new Handler(thread.getLooper());
        String saved = preferences.getString("mesh_id", "");
        if (saved.length() != 16) {
            byte[] fresh = new byte[8];
            random.nextBytes(fresh);
            saved = hex(fresh);
            preferences.edit().putString("mesh_id", saved).apply();
        }
        nodeId = saved;
        nodeBytes = unhex(nodeId);
        keys = loadOrCreateKeys();
        logFile = new File(this.context.getFilesDir(), "mesh_messages.json");
        handler.post(this::loadLog);
    }

    static BleMesh current() {
        return current;
    }

    String nodeId() {
        return nodeId;
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setBackgroundListener(Listener listener) {
        this.backgroundListener = listener;
    }

    void setCallSink(CallSink sink) {
        this.callSink = sink;
    }

    /* ---------- Lifecycle ---------- */

    void start() {
        current = this;
        handler.post(() -> {
            if (running) return;
            manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null) {
                setStatus("This phone has no Bluetooth");
                return;
            }
            if (!MeshPermissions.granted(context)) {
                setStatus("Needs Bluetooth permission");
                return;
            }
            if (!adapter.isEnabled()) {
                setStatus("Bluetooth is off");
                handler.postDelayed(this::retryStart, 4000);
                return;
            }
            try {
                running = true;
                longRange = adapter.isLeCodedPhySupported() && adapter.isLeExtendedAdvertisingSupported();
                openServer();
                startAdvertising();
                startScanning();
                setStatus(longRange ? "On · long range" : "On");
                handler.postDelayed(announceTask, 1500);
                handler.postDelayed(maintenanceTask, 5000);
            } catch (Exception e) {
                Log.e(TAG, "Could not start the mesh", e);
                running = false;
                setStatus("Bluetooth could not start");
                handler.postDelayed(this::retryStart, 6000);
            }
        });
    }

    private void retryStart() {
        if (!running && current == this) start();
    }

    void stop() {
        handler.post(() -> {
            running = false;
            handler.removeCallbacks(announceTask);
            handler.removeCallbacks(maintenanceTask);
            try {
                if (scanner != null && scanCallback != null) scanner.stopScan(scanCallback);
                if (scanner != null && codedScanCallback != null) scanner.stopScan(codedScanCallback);
            } catch (Exception ignored) {
            }
            try {
                if (advertiser != null) for (AdvertisingSetCallback callback : advertising) advertiser.stopAdvertisingSet(callback);
                if (advertiser != null && plainAdvertising != null) advertiser.stopAdvertising(plainAdvertising);
            } catch (Exception ignored) {
            }
            advertising.clear();
            plainAdvertising = null;
            for (Link link : new ArrayList<>(links.values())) closeLink(link);
            try {
                if (server != null) server.close();
            } catch (Exception ignored) {
            }
            server = null;
            setStatus("Off");
        });
    }

    void restart() {
        stop();
        handler.postDelayed(this::start, 800);
    }

    private void setStatus(String next) {
        status = next;
        emitState();
    }

    private final Runnable announceTask = new Runnable() {
        @Override public void run() {
            if (!running) return;
            announce();
            handler.postDelayed(this, ANNOUNCE_MS);
        }
    };

    private final Runnable maintenanceTask = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            for (Link link : new ArrayList<>(links.values())) {
                // A link only counts once the other phone's hello arrives: a link can look ready on one side while the
                // other side has already dropped it, and then nothing sent over it ever arrives.
                if (link.nodeId.isEmpty() && now - link.openedAt > 20_000) closeLink(link);
                else if (now - link.lastReceived > LINK_SILENCE_MS) closeLink(link);
            }
            boolean changed = false;
            Iterator<Map.Entry<String, Peer>> iterator = peers.entrySet().iterator();
            while (iterator.hasNext()) {
                Peer peer = iterator.next().getValue();
                if (now - peer.lastSeen > PEER_EXPIRY_MS && linkFor(peer.id) == null) {
                    iterator.remove();
                    changed = true;
                }
            }
            if (changed) emitState();
            handler.postDelayed(this, 5000);
        }
    };

    /* ---------- GATT server (peripheral side) ---------- */

    private void openServer() {
        server = manager.openGattServer(context, serverCallback);
        if (server == null) throw new IllegalStateException("No GATT server");
        BluetoothGattService service = new BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        serverCharacteristic = new BluetoothGattCharacteristic(DATA,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        serverCharacteristic.addDescriptor(descriptor);
        service.addCharacteristic(serverCharacteristic);
        server.addService(service);
    }

    private final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {
        @Override public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            handler.post(() -> {
                String key = "p:" + device.getAddress();
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // Our own outgoing connection is reported to our server too. Tracking it as a second link would
                    // later close it with cancelConnection(), which tears down the real link underneath.
                    if (links.containsKey("c:" + device.getAddress())) return;
                    if (!links.containsKey(key)) {
                        Link link = new Link(key, false);
                        link.device = device;
                        links.put(key, link);
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Link link = links.get(key);
                    if (link != null) dropLink(link);
                }
            });
        }

        @Override public void onMtuChanged(BluetoothDevice device, int mtu) {
            handler.post(() -> {
                Link link = links.get("p:" + device.getAddress());
                if (link != null) link.mtu = Math.min(mtu, 517);
            });
        }

        @Override public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor,
                                                       boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            if (responseNeeded) server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            handler.post(() -> {
                if (links.containsKey("c:" + device.getAddress())) return;
                Link link = links.get("p:" + device.getAddress());
                if (link == null) {
                    link = new Link("p:" + device.getAddress(), false);
                    link.device = device;
                    links.put(link.address, link);
                }
                if (!link.ready) {
                    link.ready = true;
                    sendHello(link);
                }
            });
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic,
                                                           boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            if (responseNeeded) server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            byte[] copy = value == null ? new byte[0] : value.clone();
            handler.post(() -> {
                Link link = links.get("p:" + device.getAddress());
                if (link != null) onChunk(link, copy);
            });
        }

        @Override public void onNotificationSent(BluetoothDevice device, int status) {
            handler.post(() -> {
                Link link = links.get("p:" + device.getAddress());
                if (link != null) {
                    link.busy = false;
                    pump(link);
                }
            });
        }
    };

    /* ---------- Advertising and scanning ---------- */

    private void startAdvertising() {
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            advertiseState = "not supported";
            Log.w(TAG, "This phone cannot advertise; it can still connect to others");
            return;
        }
        byte[] shortId = new byte[6];
        System.arraycopy(nodeBytes, 0, shortId, 0, 6);
        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(SERVICE))
                .addManufacturerData(MANUFACTURER_ID, shortId)
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build();
        // The normal advert uses the original advertising call: every Android version supports it, while the newer
        // advertising-set call never completes on some Android 11 Bluetooth stacks, leaving the phone invisible.
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        plainAdvertising = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                advertiseState = "ok";
                emitState();
            }

            @Override public void onStartFailure(int errorCode) {
                Log.w(TAG, "Advertising failed: " + errorCode);
                advertiseState = "failed (" + errorCode + ")";
                emitState();
            }
        };
        try {
            advertiser.startAdvertising(settings, data, plainAdvertising);
        } catch (Exception e) {
            Log.w(TAG, "Could not advertise", e);
            advertiseState = "failed (" + e.getClass().getSimpleName() + ")";
        }
        if (longRange) {
            // Long range: coded PHY trades speed for distance (often several hundred metres in the open).
            AdvertisingSetParameters coded = new AdvertisingSetParameters.Builder()
                    .setLegacyMode(false)
                    .setConnectable(true)
                    .setScannable(false)
                    .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
                    .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
                    .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
                    .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                    .build();
            startSet(coded, data);
        }
    }

    private void startSet(AdvertisingSetParameters parameters, AdvertiseData data) {
        AdvertisingSetCallback callback = new AdvertisingSetCallback() {
            @Override public void onAdvertisingSetStarted(AdvertisingSet set, int txPower, int status) {
                if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) Log.w(TAG, "Long-range advertising failed: " + status);
            }
        };
        try {
            advertiser.startAdvertisingSet(parameters, data, null, null, null, callback);
            advertising.add(callback);
        } catch (Exception e) {
            Log.w(TAG, "Could not advertise", e);
        }
    }

    /**
     * Scans with plain (legacy) settings, which every phone handles; some chipsets miss ordinary adverts in extended
     * scan mode. Phones with long-range support run a second scan just for long-range (coded PHY) adverts.
     */
    private void startScanning() {
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            scanState = "not supported";
            return;
        }
        List<ScanFilter> filters = Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build());
        ScanSettings plain = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanCallback = newScanCallback(filters, plain, true);
        scanner.startScan(filters, plain, scanCallback);
        scanState = "ok";
        if (longRange) {
            try {
                ScanSettings coded = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                        .setLegacy(false).setPhy(BluetoothDevice.PHY_LE_CODED).build();
                codedScanCallback = newScanCallback(filters, coded, false);
                scanner.startScan(filters, coded, codedScanCallback);
            } catch (Exception e) {
                Log.w(TAG, "Long-range scan not available", e);
                codedScanCallback = null;
            }
        }
    }

    private ScanCallback newScanCallback(List<ScanFilter> filters, ScanSettings settings, boolean main) {
        return new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                handler.post(() -> onFound(result));
            }

            @Override public void onBatchScanResults(List<ScanResult> results) {
                handler.post(() -> {
                    for (ScanResult result : results) onFound(result);
                });
            }

            @Override public void onScanFailed(int errorCode) {
                Log.w(TAG, "Scan failed: " + errorCode);
                if (main) {
                    scanState = "failed (" + errorCode + ")";
                    emitState();
                }
                ScanCallback self = this;
                handler.postDelayed(() -> {
                    if (!running) return;
                    try {
                        scanner.startScan(filters, settings, self);
                        if (main) scanState = "ok";
                    } catch (Exception ignored) {
                    }
                }, 5000);
            }
        };
    }

    private void onFound(ScanResult result) {
        if (!running) return;
        ScanRecord record = result.getScanRecord();
        byte[] data = record == null ? null : record.getManufacturerSpecificData(MANUFACTURER_ID);
        if (data == null || data.length < 6) return;
        String shortId = hex(data).substring(0, 12);
        String mine = nodeId.substring(0, 12);
        if (shortId.equals(mine)) return;
        long now = System.currentTimeMillis();
        sightings.put(shortId, now);
        Long first = firstSeen.get(shortId);
        if (first == null || now - first > 120_000 && !hasLinkTo(shortId)) {
            first = now;
            firstSeen.put(shortId, now);
        }
        if (hasLinkTo(shortId)) return;
        // Normally only the phone with the lower id connects, so every pair of phones has one link. If that phone
        // has not managed it after a while (it may be unable to see us), this phone connects instead.
        if (shortId.compareTo(mine) < 0 && now - first < 12_000) return;
        Long wait = retryAt.get(shortId);
        if (wait != null && now < wait) return;
        if (links.size() >= MAX_LINKS) return;
        String key = "c:" + result.getDevice().getAddress();
        if (links.containsKey(key)) return;
        Link link = new Link(key, true);
        link.shortId = shortId;
        link.device = result.getDevice();
        link.coded = result.getPrimaryPhy() == BluetoothDevice.PHY_LE_CODED;
        links.put(key, link);
        // Offer the normal 1M PHY too: some phones advertise long range but cannot hold a long-range connection.
        int phy = BluetoothDevice.PHY_LE_1M_MASK | (link.coded ? BluetoothDevice.PHY_LE_CODED_MASK : 0);
        try {
            link.gatt = result.getDevice().connectGatt(context, false, new CentralCallback(link), BluetoothDevice.TRANSPORT_LE, phy, handler);
        } catch (Exception e) {
            links.remove(key);
            connectFailed(shortId, e.getClass().getSimpleName());
        }
    }

    private boolean hasLinkTo(String shortId) {
        for (Link link : links.values()) {
            if (shortId.equals(link.shortId) || (!link.nodeId.isEmpty() && link.nodeId.startsWith(shortId))) return true;
        }
        return false;
    }

    /** Backs off 2 s, 4 s, 8 s… (up to 30 s) after each failed attempt, instead of retrying on every advert. */
    private void connectFailed(String shortId, String reason) {
        if (shortId == null || shortId.isEmpty()) return;
        int failures = connectFailures.containsKey(shortId) ? connectFailures.get(shortId) + 1 : 1;
        connectFailures.put(shortId, failures);
        retryAt.put(shortId, System.currentTimeMillis() + Math.min(30_000L, 1000L << Math.min(failures, 5)));
        lastConnectError = reason;
        emitState();
    }

    /* ---------- GATT client (central side) ---------- */

    private final class CentralCallback extends BluetoothGattCallback {
        private final Link link;

        CentralCallback(Link link) {
            this.link = link;
        }

        @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            handler.post(() -> {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    link.gatt = gatt;
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    if (!gatt.requestMtu(517)) gatt.discoverServices();
                } else {
                    if (link.nodeId.isEmpty()) connectFailed(link.shortId, "error " + status);
                    dropLink(link);
                    try {
                        gatt.close();
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        @Override public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            handler.post(() -> {
                if (status == BluetoothGatt.GATT_SUCCESS) link.mtu = Math.min(mtu, 517);
                if (!link.coded) gatt.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK | BluetoothDevice.PHY_LE_1M_MASK,
                        BluetoothDevice.PHY_LE_2M_MASK | BluetoothDevice.PHY_LE_1M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED);
                gatt.discoverServices();
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            handler.post(() -> {
                BluetoothGattService service = gatt.getService(SERVICE);
                BluetoothGattCharacteristic characteristic = service == null ? null : service.getCharacteristic(DATA);
                if (characteristic == null) {
                    closeLink(link);
                    return;
                }
                link.remote = characteristic;
                gatt.setCharacteristicNotification(characteristic, true);
                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
                if (descriptor == null) {
                    closeLink(link);
                    return;
                }
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (!gatt.writeDescriptor(descriptor)) closeLink(link);
            });
        }

        @Override public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            handler.post(() -> {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    closeLink(link);
                    return;
                }
                link.ready = true;
                sendHello(link);
            });
        }

        @Override public void onServiceChanged(BluetoothGatt gatt) {
            // The other phone restarted its server, so the handles we hold are stale: discover and subscribe again.
            handler.post(() -> {
                link.ready = false;
                link.busy = false;
                link.remote = null;
                if (!gatt.discoverServices()) closeLink(link);
            });
        }

        @Override public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            handler.post(() -> {
                link.busy = false;
                pump(link);
            });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            byte[] copy = value == null ? new byte[0] : value.clone();
            handler.post(() -> onChunk(link, copy));
        }
    }

    /* ---------- Links, framing and flow control ---------- */

    private Link linkFor(String peerId) {
        for (Link link : links.values()) if (link.ready && peerId.equals(link.nodeId)) return link;
        return null;
    }

    private void closeLink(Link link) {
        try {
            if (link.central && link.gatt != null) {
                link.gatt.disconnect();
                link.gatt.close();
            } else if (!link.central && server != null && link.device != null) {
                server.cancelConnection(link.device);
            }
        } catch (Exception ignored) {
        }
        dropLink(link);
    }

    private void dropLink(Link link) {
        if (links.remove(link.address) == null) return;
        if (!link.nodeId.isEmpty()) {
            Peer peer = peers.get(link.nodeId);
            if (peer != null && peer.hops == 1) peer.hops = 2;
            CallSink sink = callSink;
            if (sink != null) {
                try {
                    JSONObject lost = new JSONObject();
                    lost.put("action", "lost");
                    sink.onCallSignal(link.nodeId, lost);
                } catch (JSONException ignored) {
                }
            }
        }
        emitState();
    }

    /**
     * Queues one packet on a link. Stream 1 (urgent) carries call audio and signals and is sent ahead of stream 0;
     * the two streams are framed separately, so a large photo never delays a voice call.
     */
    private void enqueue(Link link, byte[] packet, boolean urgent) {
        if (!link.ready) return;
        int stream = urgent ? 1 : 0;
        int chunk = link.chunkSize();
        ArrayDeque<byte[]> queue = urgent ? link.urgent : link.normal;
        if (urgent && queue.size() > 40) queue.clear();
        if (!urgent && queue.size() > 6000) return;
        int offset = 0;
        boolean first = true;
        while (offset < packet.length || first) {
            int headerLength = first ? 5 : 1;
            int take = Math.min(chunk - headerLength, packet.length - offset);
            byte[] piece = new byte[headerLength + take];
            piece[0] = (byte) ((first ? 0x80 : 0) | stream);
            if (first) ByteBuffer.wrap(piece, 1, 4).putInt(packet.length);
            System.arraycopy(packet, offset, piece, headerLength, take);
            queue.add(piece);
            offset += take;
            first = false;
        }
        pump(link);
    }

    private void pump(Link link) {
        if (link.busy || !link.ready) return;
        boolean urgent = !link.urgent.isEmpty();
        byte[] piece = urgent ? link.urgent.peek() : link.normal.peek();
        if (piece == null) return;
        boolean sent;
        try {
            if (link.central) {
                link.remote.setWriteType(urgent ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                link.remote.setValue(piece);
                sent = link.gatt.writeCharacteristic(link.remote);
            } else {
                serverCharacteristic.setValue(piece);
                sent = server.notifyCharacteristicChanged(link.device, serverCharacteristic, false);
            }
        } catch (Exception e) {
            Log.w(TAG, "Bluetooth write failed", e);
            sent = false;
        }
        if (sent) {
            link.failures = 0;
            link.busy = true;
            if (urgent) link.urgent.poll();
            else link.normal.poll();
            link.lastActivity = System.currentTimeMillis();
            final Link target = link;
            // Safety net: if the stack never reports completion, keep the queue moving.
            handler.postDelayed(() -> {
                if (target.busy && System.currentTimeMillis() - target.lastActivity > 3000) {
                    target.busy = false;
                    pump(target);
                }
            }, 3200);
        } else if (++link.failures >= 50) {
            // A piece the stack keeps refusing would block this link forever; drop it. The receiver resyncs on the
            // next packet's start chunk, and the mesh inventory sync fetches any message lost this way.
            Log.w(TAG, "Dropping a piece the Bluetooth stack keeps refusing (" + piece.length + " bytes)");
            link.failures = 0;
            if (urgent) link.urgent.poll();
            else link.normal.poll();
            pump(link);
        } else {
            handler.postDelayed(() -> pump(link), 40);
        }
    }

    private void onChunk(Link link, byte[] chunk) {
        if (chunk.length < 1) return;
        link.lastActivity = System.currentTimeMillis();
        link.lastReceived = link.lastActivity;
        int stream = chunk[0] & 0x01;
        boolean start = (chunk[0] & 0x80) != 0;
        ByteArrayOutputStream buffer = link.rx[stream];
        if (start) {
            if (chunk.length < 5) return;
            int length = ByteBuffer.wrap(chunk, 1, 4).getInt();
            if (length < 0 || length > MAX_PACKET) {
                link.rxExpected[stream] = -1;
                return;
            }
            buffer.reset();
            link.rxExpected[stream] = length;
            buffer.write(chunk, 5, chunk.length - 5);
        } else {
            if (link.rxExpected[stream] < 0) return;
            buffer.write(chunk, 1, chunk.length - 1);
        }
        if (link.rxExpected[stream] >= 0 && buffer.size() >= link.rxExpected[stream]) {
            byte[] packet = buffer.toByteArray();
            buffer.reset();
            link.rxExpected[stream] = -1;
            onPacket(link, packet);
        }
    }

    /* ---------- Mesh packets ---------- */

    private byte[] packet(byte type, int ttl, byte[] id, byte[] from, byte[] to, byte[] payload) {
        byte[] out = new byte[HEADER + payload.length];
        out[0] = 1;
        out[1] = type;
        out[2] = (byte) ttl;
        System.arraycopy(id, 0, out, 3, 16);
        System.arraycopy(from, 0, out, 19, 8);
        System.arraycopy(to, 0, out, 27, 8);
        System.arraycopy(payload, 0, out, HEADER, payload.length);
        return out;
    }

    private byte[] newId() {
        byte[] id = new byte[16];
        random.nextBytes(id);
        return id;
    }

    private void onPacket(Link link, byte[] data) {
        if (data.length == 0) return;
        if (data[0] == AUDIO_MARK) {
            CallSink sink = callSink;
            if (sink != null && !link.nodeId.isEmpty()) sink.onAudio(link.nodeId, data);
            return;
        }
        if (data[0] != 1 || data.length < HEADER) return;
        byte type = data[1];
        int ttl = data[2] & 0xff;
        String id = hex(data, 3, 16);
        String from = hex(data, 19, 8);
        String to = hex(data, 27, 8);
        byte[] payload = new byte[data.length - HEADER];
        System.arraycopy(data, HEADER, payload, 0, payload.length);
        try {
            switch (type) {
                case T_HELLO:
                    onHello(link, new JSONObject(new String(payload, StandardCharsets.UTF_8)));
                    return;
                case T_INV:
                    onInventory(link, payload);
                    return;
                case T_REQ:
                    onRequest(link, payload);
                    return;
                case T_CALL: {
                    CallSink sink = callSink;
                    if (sink != null && nodeId.equals(to)) sink.onCallSignal(from, new JSONObject(new String(payload, StandardCharsets.UTF_8)));
                    return;
                }
                default:
                    break;
            }
        } catch (JSONException e) {
            return;
        }
        if (from.equals(nodeId) || seen.containsKey(id)) return;
        seen.put(id, System.currentTimeMillis());
        boolean forMe = isBroadcast(to) || nodeId.equals(to);
        try {
            if (type == T_ANNOUNCE) {
                onAnnounce(from, DEFAULT_TTL - ttl + 1, new JSONObject(new String(payload, StandardCharsets.UTF_8)));
            } else if (type == T_MSG) {
                store.put(id, new Stored(data, System.currentTimeMillis()));
                if (forMe) deliver(id, from, to, payload);
            } else if (type == T_ACK && nodeId.equals(to)) {
                JSONObject ack = new JSONObject(new String(payload, StandardCharsets.UTF_8));
                markDelivered(ack.optString("ref"), from);
            }
        } catch (JSONException ignored) {
        }
        // Relay onwards to everyone except the phone it came from.
        if (ttl > 1 && !nodeId.equals(to)) {
            byte[] relay = data.clone();
            relay[2] = (byte) (ttl - 1);
            for (Link other : links.values()) if (other != link && other.ready) enqueue(other, relay, false);
        }
    }

    private static boolean isBroadcast(String to) {
        return "0000000000000000".equals(to);
    }

    private void sendHello(Link link) {
        try {
            JSONObject hello = new JSONObject();
            hello.put("id", nodeId);
            hello.put("name", myName());
            hello.put("key", Base64.encodeToString(keys.getPublic().getEncoded(), Base64.NO_WRAP));
            if (hostedRoom != null) hello.put("room", hostedRoom);
            enqueue(link, packet(T_HELLO, 1, newId(), nodeBytes, BROADCAST, hello.toString().getBytes(StandardCharsets.UTF_8)), true);
        } catch (JSONException ignored) {
        }
    }

    private void onHello(Link link, JSONObject hello) {
        String id = hello.optString("id");
        if (id.length() != 16 || id.equals(nodeId)) {
            closeLink(link);
            return;
        }
        for (Link other : links.values()) {
            if (other != link && id.equals(other.nodeId)) {
                // Duplicate link to the same phone: keep the older one.
                closeLink(link);
                return;
            }
        }
        link.nodeId = id;
        link.shortId = id.substring(0, 12);
        connectFailures.remove(link.shortId);
        retryAt.remove(link.shortId);
        onAnnounce(id, 1, hello);
        sendInventory(link);
    }

    private void onAnnounce(String id, int hops, JSONObject info) {
        Peer peer = peers.get(id);
        if (peer == null) {
            peer = new Peer();
            peer.id = id;
            peers.put(id, peer);
        }
        peer.name = LanServer.clean(info.optString("name", peer.name), 48);
        peer.lastSeen = System.currentTimeMillis();
        peer.hops = linkFor(id) != null ? 1 : Math.max(1, hops);
        String keyText = info.optString("key", "");
        if (!keyText.isEmpty() && !keyText.equals(peer.keyText)) {
            try {
                peer.key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.decode(keyText, Base64.NO_WRAP)));
                peer.keyText = keyText;
            } catch (Exception ignored) {
            }
        }
        peer.room = info.optJSONObject("room");
        if (peer.key != null && !waitingForKey.isEmpty()) retryWaiting(id);
        emitState();
    }

    private void retryWaiting(String from) {
        List<byte[]> ready = new ArrayList<>();
        Iterator<byte[]> iterator = waitingForKey.iterator();
        while (iterator.hasNext()) {
            byte[] data = iterator.next();
            if (from.equals(hex(data, 19, 8))) {
                ready.add(data);
                iterator.remove();
            }
        }
        for (byte[] data : ready) {
            byte[] payload = new byte[data.length - HEADER];
            System.arraycopy(data, HEADER, payload, 0, payload.length);
            deliver(hex(data, 3, 16), from, hex(data, 27, 8), payload);
        }
    }

    private void announce() {
        try {
            JSONObject info = new JSONObject();
            info.put("name", myName());
            info.put("key", Base64.encodeToString(keys.getPublic().getEncoded(), Base64.NO_WRAP));
            if (hostedRoom != null) info.put("room", hostedRoom);
            byte[] id = newId();
            seen.put(hex(id), System.currentTimeMillis());
            byte[] data = packet(T_ANNOUNCE, DEFAULT_TTL, id, nodeBytes, BROADCAST, info.toString().getBytes(StandardCharsets.UTF_8));
            for (Link link : links.values()) if (link.ready) enqueue(link, data, false);
        } catch (JSONException ignored) {
        }
    }

    /** Tell a new neighbour which recent messages we hold, so each side can ask for what it missed. */
    private void sendInventory(Link link) {
        long cutoff = System.currentTimeMillis() - SYNC_WINDOW_MS;
        ByteArrayOutputStream ids = new ByteArrayOutputStream();
        List<String> recent = new ArrayList<>();
        for (Map.Entry<String, Stored> entry : store.entrySet()) if (entry.getValue().time > cutoff) recent.add(entry.getKey());
        int from = Math.max(0, recent.size() - 300);
        for (int i = from; i < recent.size(); i++) {
            byte[] id = unhex(recent.get(i));
            ids.write(id, 0, id.length);
        }
        enqueue(link, packet(T_INV, 1, newId(), nodeBytes, BROADCAST, ids.toByteArray()), false);
    }

    private void onInventory(Link link, byte[] payload) {
        ByteArrayOutputStream wanted = new ByteArrayOutputStream();
        for (int offset = 0; offset + 16 <= payload.length; offset += 16) {
            String id = hex(payload, offset, 16);
            if (!seen.containsKey(id)) wanted.write(payload, offset, 16);
        }
        if (wanted.size() > 0) enqueue(link, packet(T_REQ, 1, newId(), nodeBytes, BROADCAST, wanted.toByteArray()), false);
    }

    private void onRequest(Link link, byte[] payload) {
        for (int offset = 0; offset + 16 <= payload.length; offset += 16) {
            Stored stored = store.get(hex(payload, offset, 16));
            if (stored == null) continue;
            byte[] copy = stored.packet.clone();
            copy[2] = (byte) Math.max(copy[2] & 0xff, 3);
            enqueue(link, copy, false);
        }
    }

    /* ---------- Sending from the app ---------- */

    /**
     * Sends a chat message: {to: peer id or "", kind, text, file{name,type,data}, duration}. Private messages are
     * encrypted for the recipient. Returns the stored message (with its id), or an object with an "error".
     */
    JSONObject send(JSONObject request) {
        JSONObject result = new JSONObject();
        try {
            String to = request.optString("to", "");
            JSONObject body = new JSONObject();
            body.put("kind", request.optString("kind", "text"));
            body.put("text", LanServer.clean(request.optString("text", ""), 20_000));
            body.put("name", myName());
            body.put("createdAt", System.currentTimeMillis());
            if (request.has("file")) body.put("file", request.getJSONObject("file"));
            if (request.has("duration")) body.put("duration", request.optInt("duration"));
            byte[] plain = body.toString().getBytes(StandardCharsets.UTF_8);
            if (plain.length > MAX_ATTACHMENT * 4 / 3 + 4096) {
                result.put("error", "Too large for Bluetooth. Keep files under 700 KB.");
                return result;
            }
            byte[] id = newId();
            byte[] payload;
            byte[] target = BROADCAST;
            if (!to.isEmpty()) {
                Peer peer = peers.get(to);
                if (peer == null || peer.key == null) {
                    result.put("error", "Waiting for their secure key — they need to be on the mesh.");
                    return result;
                }
                target = unhex(to);
                JSONObject sealed = new JSONObject();
                sealed.put("enc", Base64.encodeToString(encrypt(peer.key, to, id, plain), Base64.NO_WRAP));
                payload = sealed.toString().getBytes(StandardCharsets.UTF_8);
            } else {
                payload = plain;
            }
            byte[] data = packet(T_MSG, DEFAULT_TTL, id, nodeBytes, target, payload);
            String idText = hex(id);
            handler.post(() -> {
                seen.put(idText, System.currentTimeMillis());
                store.put(idText, new Stored(data, System.currentTimeMillis()));
                for (Link link : links.values()) if (link.ready) enqueue(link, data, false);
            });
            JSONObject message = new JSONObject(body.toString());
            message.put("id", idText);
            message.put("from", nodeId);
            message.put("to", to);
            message.put("mine", true);
            message.put("status", links.isEmpty() ? "waiting" : "sent");
            addToLog(message);
            return message;
        } catch (Exception e) {
            try {
                result.put("error", "Could not send: " + e.getMessage());
            } catch (JSONException ignored) {
            }
            return result;
        }
    }

    private void deliver(String id, String from, String to, byte[] payload) {
        try {
            JSONObject body = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            boolean isPrivate = !isBroadcast(to);
            if (isPrivate) {
                Peer peer = peers.get(from);
                if (!body.has("enc")) return;
                if (peer == null || peer.key == null) {
                    if (waitingForKey.size() < 100) waitingForKey.add(packet(T_MSG, 0, unhex(id), unhex(from), unhex(to), payload));
                    return;
                }
                byte[] plain = decrypt(peer.key, from, unhex(id), Base64.decode(body.getString("enc"), Base64.NO_WRAP));
                body = new JSONObject(new String(plain, StandardCharsets.UTF_8));
                JSONObject ack = new JSONObject();
                ack.put("ref", id);
                byte[] ackId = newId();
                seen.put(hex(ackId), System.currentTimeMillis());
                byte[] ackPacket = packet(T_ACK, DEFAULT_TTL, ackId, nodeBytes, unhex(from), ack.toString().getBytes(StandardCharsets.UTF_8));
                for (Link link : links.values()) if (link.ready) enqueue(link, ackPacket, false);
            }
            body.put("id", id);
            body.put("from", from);
            body.put("to", isPrivate ? to : "");
            body.put("mine", false);
            body.put("receivedAt", System.currentTimeMillis());
            if (isPrivate) body.put("private", true);
            Peer peer = peers.get(from);
            if (peer != null && body.optString("name").isEmpty()) body.put("name", peer.name);
            addToLog(body);
            JSONObject event = new JSONObject();
            event.put("type", "message");
            event.put("message", body);
            emit(event);
        } catch (Exception e) {
            Log.w(TAG, "Dropped a message that could not be read", e);
        }
    }

    private void markDelivered(String ref, String by) {
        synchronized (log) {
            for (JSONObject message : log) {
                if (ref.equals(message.optString("id"))) {
                    try {
                        message.put("status", "delivered");
                    } catch (JSONException ignored) {
                    }
                }
            }
        }
        schedulePersist();
        try {
            JSONObject event = new JSONObject();
            event.put("type", "delivered");
            event.put("ref", ref);
            emit(event);
        } catch (JSONException ignored) {
        }
    }

    /* ---------- Calls between direct neighbours ---------- */

    boolean isDirect(String peerId) {
        return linkFor(peerId) != null;
    }

    boolean sendCallSignal(String peerId, JSONObject signal) {
        Link link = linkFor(peerId);
        if (link == null) return false;
        byte[] data = packet(T_CALL, 1, newId(), nodeBytes, unhex(peerId), signal.toString().getBytes(StandardCharsets.UTF_8));
        handler.post(() -> enqueue(link, data, true));
        return true;
    }

    void sendAudio(String peerId, byte[] packet) {
        handler.post(() -> {
            Link link = linkFor(peerId);
            if (link != null) enqueue(link, packet, true);
        });
    }

    /* ---------- Hotspot rooms announced over Bluetooth ---------- */

    void setHostedRoom(JSONObject room) {
        hostedRoom = room;
        handler.post(this::announce);
    }

    JSONArray hotspotRooms() {
        JSONArray rooms = new JSONArray();
        for (Peer peer : new ArrayList<>(peers.values())) {
            JSONObject hosted = peer.room;
            if (hosted == null) continue;
            try {
                JSONObject room = new JSONObject(hosted.toString());
                room.put("hops", peer.hops);
                room.put("host", peer.name);
                rooms.put(room);
            } catch (JSONException ignored) {
            }
        }
        return rooms;
    }

    /* ---------- State for the app ---------- */

    JSONObject state() {
        JSONObject state = new JSONObject();
        try {
            state.put("me", nodeId);
            state.put("name", myName());
            state.put("status", status);
            state.put("running", running);
            state.put("longRange", longRange);
            state.put("permission", MeshPermissions.granted(context));
            state.put("bluetooth", adapter != null && adapter.isEnabled());
            JSONArray list = new JSONArray();
            for (Peer peer : new ArrayList<>(peers.values())) {
                JSONObject item = new JSONObject();
                item.put("id", peer.id);
                item.put("name", peer.name);
                item.put("hops", peer.hops);
                item.put("direct", linkFor(peer.id) != null);
                item.put("secure", peer.key != null);
                item.put("lastSeen", peer.lastSeen);
                if (peer.room != null) item.put("room", peer.room);
                list.put(item);
            }
            state.put("peers", list);
            int direct = 0;
            for (Link link : links.values()) if (link.ready && !link.nodeId.isEmpty()) direct++;
            state.put("links", direct);
            long now = System.currentTimeMillis();
            int seen = 0;
            for (Map.Entry<String, Long> sighting : sightings.entrySet()) {
                if (now - sighting.getValue() < 30_000 && !hasLinkTo(sighting.getKey())) seen++;
            }
            state.put("seen", seen);
            state.put("advertising", advertiseState);
            state.put("scanning", scanState);
            state.put("lastError", lastConnectError);
        } catch (JSONException ignored) {
        }
        return state;
    }

    JSONArray messages() {
        JSONArray list = new JSONArray();
        synchronized (log) {
            for (JSONObject message : log) list.put(message);
        }
        return list;
    }

    private void emitState() {
        try {
            JSONObject event = new JSONObject();
            event.put("type", "state");
            event.put("state", state());
            emit(event);
        } catch (JSONException ignored) {
        }
    }

    private void emit(JSONObject event) {
        Listener target = listener != null ? listener : backgroundListener;
        if (target != null) target.onMeshEvent(event);
    }

    private String myName() {
        String name = preferences.getString(LanService.KEY_USER_NAME, "");
        return name.isEmpty() ? RoomDiscovery.deviceName(context) : name;
    }

    /* ---------- Local history ---------- */

    private void addToLog(JSONObject message) {
        synchronized (log) {
            log.add(message);
            while (log.size() > 2000) log.remove(0);
        }
        schedulePersist();
    }

    private void schedulePersist() {
        if (persistQueued) return;
        persistQueued = true;
        handler.postDelayed(() -> {
            persistQueued = false;
            JSONArray list = messages();
            File pending = new File(logFile.getParentFile(), "mesh_messages.tmp");
            try (FileOutputStream out = new FileOutputStream(pending)) {
                out.write(list.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                return;
            }
            if (!pending.renameTo(logFile)) pending.delete();
        }, 800);
    }

    private void loadLog() {
        if (!logFile.isFile()) return;
        try (FileInputStream in = new FileInputStream(logFile)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
            JSONArray saved = new JSONArray(bytes.toString(StandardCharsets.UTF_8.name()));
            synchronized (log) {
                for (int i = 0; i < saved.length(); i++) {
                    JSONObject message = saved.optJSONObject(i);
                    if (message == null) continue;
                    log.add(message);
                    seen.put(message.optString("id"), message.optLong("createdAt"));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read mesh history", e);
        }
    }

    /* ---------- Encryption for private messages ---------- */

    private KeyPair loadOrCreateKeys() {
        try {
            String privateText = preferences.getString("mesh_private", "");
            String publicText = preferences.getString("mesh_public", "");
            KeyFactory factory = KeyFactory.getInstance("EC");
            if (!privateText.isEmpty() && !publicText.isEmpty()) {
                PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(privateText, Base64.NO_WRAP)));
                PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(Base64.decode(publicText, Base64.NO_WRAP)));
                return new KeyPair(publicKey, privateKey);
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), random);
            KeyPair pair = generator.generateKeyPair();
            preferences.edit()
                    .putString("mesh_private", Base64.encodeToString(pair.getPrivate().getEncoded(), Base64.NO_WRAP))
                    .putString("mesh_public", Base64.encodeToString(pair.getPublic().getEncoded(), Base64.NO_WRAP))
                    .apply();
            return pair;
        } catch (Exception e) {
            throw new IllegalStateException("Could not create mesh keys", e);
        }
    }

    private byte[] sharedKey(PublicKey theirs, String theirId) throws Exception {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(keys.getPrivate());
        agreement.doPhase(theirs, true);
        byte[] secret = agreement.generateSecret();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(secret);
        digest.update("hearth-mesh-v1".getBytes(StandardCharsets.UTF_8));
        String low = nodeId.compareTo(theirId) < 0 ? nodeId : theirId;
        String high = nodeId.compareTo(theirId) < 0 ? theirId : nodeId;
        digest.update((low + high).getBytes(StandardCharsets.UTF_8));
        return digest.digest();
    }

    private byte[] encrypt(PublicKey theirs, String theirId, byte[] packetId, byte[] plain) throws Exception {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sharedKey(theirs, theirId), "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(packetId);
        byte[] sealed = cipher.doFinal(plain);
        byte[] out = new byte[12 + sealed.length];
        System.arraycopy(nonce, 0, out, 0, 12);
        System.arraycopy(sealed, 0, out, 12, sealed.length);
        return out;
    }

    private byte[] decrypt(PublicKey theirs, String theirId, byte[] packetId, byte[] sealed) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sharedKey(theirs, theirId), "AES"), new GCMParameterSpec(128, sealed, 0, 12));
        cipher.updateAAD(packetId);
        return cipher.doFinal(sealed, 12, sealed.length - 12);
    }

    /* ---------- Hex helpers ---------- */

    static String hex(byte[] bytes) {
        return hex(bytes, 0, bytes.length);
    }

    static String hex(byte[] bytes, int offset, int length) {
        StringBuilder out = new StringBuilder(length * 2);
        for (int i = offset; i < offset + length; i++) out.append(String.format(Locale.ROOT, "%02x", bytes[i] & 0xff));
        return out.toString();
    }

    static byte[] unhex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
