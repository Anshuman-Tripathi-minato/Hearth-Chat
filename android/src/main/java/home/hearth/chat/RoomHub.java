package home.hearth.chat;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live connections for a room: pushes messages, presence and call signals over WebSocket, and relays group calls.
 *
 * <p>Group calls are relayed through the host phone so 30+ people can join: every device sends one encoded audio
 * stream (and, when asked, one small video stream). The hub forwards audio only from the few people speaking right
 * now, and video only from a handful of visible tiles, which keeps the host phone's Wi-Fi traffic bounded.
 */
final class RoomHub {
    private static final String TAG = "HearthHub";
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;
    private static final long AUDIO_DROP_BYTES = 1024 * 1024;
    private static final long VIDEO_DROP_BYTES = 384 * 1024;
    private static final long HARD_LIMIT_BYTES = 24L * 1024 * 1024;
    static final int MAX_CALL_PARTICIPANTS = 60;
    private static final int MAX_SPEAKERS = 4;
    private static final int SPEAK_LEVEL = 10;
    private static final long SPEAKER_HOLD_MS = 1200;
    private static final long KEY_REQUEST_GAP_MS = 700;

    interface Host {
        void touchPeer(String device, String name, boolean typing, boolean busy);

        void keepAlive(String device);

        void dropPeer(String device);

        String peersFingerprint();

        JSONArray onlinePeers();

        void relaySignal(JSONObject body);

        void postCallLog(JSONObject call, String text);
    }

    private final Host host;
    private final ExecutorService workers;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
    private final Set<Conn> conns = ConcurrentHashMap.newKeySet();
    private final Object callLock = new Object();
    private final Map<String, Participant> participants = new HashMap<>();
    private final List<Participant> speakers = new ArrayList<>();
    private final Set<String> everJoined = new HashSet<>();
    private long callStartedAt;
    private int nextSlot = 1;
    private volatile boolean rosterDirty;
    private long lastVisibleUpdate;
    private String lastPeers = "";
    private int tick;

    private static final class Conn {
        final Socket socket;
        final OutputStream out;
        final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        final AtomicLong queued = new AtomicLong();
        final Set<Integer> awaitingKey = ConcurrentHashMap.newKeySet();
        volatile boolean open = true;
        volatile String device = "";
        volatile String name = "Someone";

        Conn(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
        }
    }

    private static final class Participant {
        final Conn conn;
        final int slot;
        final String device;
        final long joinedAt = System.currentTimeMillis();
        String name;
        boolean camera;
        boolean muted;
        boolean screen;
        boolean visible;
        volatile long lastLoud;
        volatile long lastSpoke;
        long lastKeyRequest;

        Participant(Conn conn, int slot, String device, String name) {
            this.conn = conn;
            this.slot = slot;
            this.device = device;
            this.name = name;
        }
    }

    RoomHub(Host host, ExecutorService workers) {
        this.host = host;
        this.workers = workers;
        ticker.scheduleAtFixedRate(this::onTick, 250, 250, TimeUnit.MILLISECONDS);
    }

    void close() {
        ticker.shutdownNow();
        for (Conn conn : conns) closeConn(conn);
    }

    static boolean isUpgrade(Map<String, String> headers) {
        String upgrade = headers.get("upgrade");
        return upgrade != null && upgrade.toLowerCase(java.util.Locale.ROOT).contains("websocket")
                && headers.containsKey("sec-websocket-key");
    }

    /** Runs a WebSocket connection on the calling thread until it closes. */
    void serve(Socket socket, InputStream input, OutputStream rawOutput, Map<String, String> headers) throws IOException {
        String accept;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((headers.get("sec-websocket-key").trim() + WS_GUID).getBytes(StandardCharsets.ISO_8859_1));
            accept = Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IOException("WebSocket handshake failed", e);
        }
        String response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        rawOutput.write(response.getBytes(StandardCharsets.ISO_8859_1));
        rawOutput.flush();
        socket.setSoTimeout(70_000);
        socket.setTcpNoDelay(true);
        Conn conn = new Conn(socket, new BufferedOutputStream(rawOutput, 64 * 1024));
        conns.add(conn);
        workers.execute(() -> writeLoop(conn));
        try {
            readLoop(conn, input);
        } finally {
            closeConn(conn);
        }
    }

    private void readLoop(Conn conn, InputStream in) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        int messageOpcode = 0;
        while (conn.open) {
            int b0 = in.read();
            if (b0 < 0) return;
            int b1 = in.read();
            if (b1 < 0) return;
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0f;
            boolean masked = (b1 & 0x80) != 0;
            long length = b1 & 0x7f;
            if (length == 126) length = (readByte(in) << 8) | readByte(in);
            else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) length = (length << 8) | readByte(in);
            }
            if (length < 0 || length > MAX_FRAME_BYTES) return;
            byte[] mask = new byte[4];
            if (masked) readFully(in, mask);
            byte[] payload = new byte[(int) length];
            readFully(in, payload);
            if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

            if (opcode == 0x8) {
                enqueue(conn, frame(0x8, new byte[0]), Long.MAX_VALUE);
                return;
            } else if (opcode == 0x9) {
                enqueue(conn, frame(0xA, payload), Long.MAX_VALUE);
                continue;
            } else if (opcode == 0xA) {
                continue;
            }
            if (opcode != 0) {
                messageOpcode = opcode;
                message.reset();
            }
            message.write(payload, 0, payload.length);
            if (message.size() > MAX_FRAME_BYTES) return;
            if (!fin) continue;
            byte[] data = message.toByteArray();
            message.reset();
            if (messageOpcode == 0x1) onText(conn, new String(data, StandardCharsets.UTF_8));
            else if (messageOpcode == 0x2) onBinary(conn, data);
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) throw new IOException("Connection closed");
        return value;
    }

    private static void readFully(InputStream in, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int count = in.read(target, offset, target.length - offset);
            if (count < 0) throw new IOException("Connection closed");
            offset += count;
        }
    }

    private void writeLoop(Conn conn) {
        try {
            while (conn.open) {
                byte[] next = conn.queue.poll(20, TimeUnit.SECONDS);
                if (next == null) continue;
                conn.out.write(next);
                conn.queued.addAndGet(-next.length);
                if (conn.queue.isEmpty()) conn.out.flush();
            }
        } catch (Exception e) {
            // The reader notices the closed socket and cleans up.
        } finally {
            closeConn(conn);
        }
    }

    private static boolean enqueue(Conn conn, byte[] frame, long dropAbove) {
        if (!conn.open) return false;
        long pending = conn.queued.get();
        if (pending > HARD_LIMIT_BYTES) {
            closeSocket(conn);
            return false;
        }
        if (pending > dropAbove) return false;
        conn.queued.addAndGet(frame.length);
        conn.queue.offer(frame);
        return true;
    }

    private static void closeSocket(Conn conn) {
        conn.open = false;
        try {
            conn.socket.close();
        } catch (IOException ignored) {
        }
    }

    private void closeConn(Conn conn) {
        boolean wasOpen = conns.remove(conn);
        closeSocket(conn);
        if (!wasOpen) return;
        leaveCall(conn);
        String device = conn.device;
        if (!device.isEmpty()) {
            boolean stillConnected = false;
            for (Conn other : conns) if (device.equals(other.device)) stillConnected = true;
            if (!stillConnected) host.dropPeer(device);
        }
    }

    static byte[] frame(int opcode, byte[] payload) {
        int length = payload.length;
        int headerLength = length < 126 ? 2 : length < 65536 ? 4 : 10;
        byte[] out = new byte[headerLength + length];
        out[0] = (byte) (0x80 | opcode);
        if (length < 126) {
            out[1] = (byte) length;
        } else if (length < 65536) {
            out[1] = 126;
            out[2] = (byte) (length >> 8);
            out[3] = (byte) length;
        } else {
            out[1] = 127;
            for (int i = 0; i < 8; i++) out[2 + i] = (byte) ((long) length >> (56 - 8 * i));
        }
        System.arraycopy(payload, 0, out, headerLength, length);
        return out;
    }

    private static byte[] textFrame(JSONObject value) {
        return frame(0x1, value.toString().getBytes(StandardCharsets.UTF_8));
    }

    /* ---------- Room-wide pushes ---------- */

    void broadcast(JSONObject value) {
        byte[] frame = textFrame(value);
        for (Conn conn : conns) enqueue(conn, frame, Long.MAX_VALUE);
    }

    /** Sends to every connection of one device; returns false when that device has no live connection. */
    boolean sendTo(String device, JSONObject value) {
        byte[] frame = textFrame(value);
        boolean sent = false;
        for (Conn conn : conns) {
            if (device.equals(conn.device)) sent |= enqueue(conn, frame, Long.MAX_VALUE);
        }
        return sent;
    }

    int connectionCount() {
        return conns.size();
    }

    private void onTick() {
        try {
            tick++;
            if (tick % 4 == 0) {
                for (Conn conn : conns) {
                    if (!conn.device.isEmpty()) host.keepAlive(conn.device);
                }
                String fingerprint = host.peersFingerprint();
                if (!fingerprint.equals(lastPeers)) {
                    lastPeers = fingerprint;
                    JSONObject push = new JSONObject();
                    push.put("t", "peers");
                    push.put("peers", host.onlinePeers());
                    broadcast(push);
                }
            }
            if (tick % 80 == 0) {
                byte[] ping = frame(0x9, new byte[0]);
                for (Conn conn : conns) enqueue(conn, ping, Long.MAX_VALUE);
            }
            synchronized (callLock) {
                if (!participants.isEmpty()) {
                    long now = System.currentTimeMillis();
                    if (speakers.removeIf(p -> now - p.lastLoud > SPEAKER_HOLD_MS)) rosterDirty = true;
                    if (now - lastVisibleUpdate > 2000) updateVisible(now);
                }
                if (rosterDirty) {
                    rosterDirty = false;
                    broadcast(roster());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Room tick failed", e);
        }
    }

    /* ---------- Client messages ---------- */

    private void onText(Conn conn, String text) {
        JSONObject message;
        try {
            message = new JSONObject(text);
        } catch (JSONException e) {
            return;
        }
        String type = message.optString("t");
        switch (type) {
            case "hello":
            case "presence": {
                conn.device = LanServer.clean(message.optString("device", conn.device), 80);
                conn.name = LanServer.clean(message.optString("name", conn.name), 48);
                if (conn.name.isEmpty()) conn.name = "Someone";
                if (!conn.device.isEmpty()) {
                    host.touchPeer(conn.device, conn.name, message.optBoolean("typing"), message.optBoolean("busy"));
                }
                if ("hello".equals(type)) {
                    try {
                        JSONObject welcome = new JSONObject();
                        welcome.put("t", "welcome");
                        welcome.put("peers", host.onlinePeers());
                        enqueue(conn, textFrame(welcome), Long.MAX_VALUE);
                        synchronized (callLock) {
                            enqueue(conn, textFrame(roster()), Long.MAX_VALUE);
                        }
                    } catch (JSONException ignored) {
                    }
                }
                break;
            }
            case "signal":
                try {
                    message.put("from", conn.device);
                    message.put("fromName", conn.name);
                    host.relaySignal(message);
                } catch (JSONException ignored) {
                }
                break;
            case "gc-join":
                joinCall(conn, message);
                break;
            case "gc-leave":
                leaveCall(conn);
                break;
            case "gc-state":
                synchronized (callLock) {
                    Participant self = participants.get(conn.device);
                    if (self != null && self.conn == conn) {
                        self.muted = message.optBoolean("muted", self.muted);
                        self.camera = message.optBoolean("camera", self.camera);
                        boolean screen = message.optBoolean("screen", self.screen);
                        if (screen != self.screen) lastVisibleUpdate = 0;
                        if (!self.camera && !screen) self.visible = false;
                        self.screen = screen;
                        if (self.muted) speakers.remove(self);
                        rosterDirty = true;
                        if (!self.visible && (self.camera || self.screen)) lastVisibleUpdate = 0;
                    }
                }
                break;
            case "gc-pli":
                synchronized (callLock) {
                    int slot = message.optInt("slot");
                    for (Participant p : participants.values()) {
                        if (p.slot == slot) requestKey(p, System.currentTimeMillis());
                    }
                }
                break;
            default:
                break;
        }
    }

    private void joinCall(Conn conn, JSONObject message) {
        if (conn.device.isEmpty()) return;
        boolean started = false;
        synchronized (callLock) {
            Participant existing = participants.get(conn.device);
            if (existing != null && existing.conn != conn) {
                participants.remove(conn.device);
                speakers.remove(existing);
            }
            if (!participants.containsKey(conn.device)) {
                if (participants.size() >= MAX_CALL_PARTICIPANTS) {
                    try {
                        JSONObject full = new JSONObject();
                        full.put("t", "gc-full");
                        full.put("max", MAX_CALL_PARTICIPANTS);
                        enqueue(conn, textFrame(full), Long.MAX_VALUE);
                    } catch (JSONException ignored) {
                    }
                    return;
                }
                if (participants.isEmpty()) {
                    callStartedAt = System.currentTimeMillis();
                    everJoined.clear();
                    nextSlot = 1;
                    started = true;
                }
                Participant self = new Participant(conn, nextSlot++, conn.device, conn.name);
                participants.put(conn.device, self);
                everJoined.add(conn.device);
            }
            Participant self = participants.get(conn.device);
            self.camera = message.optBoolean("camera");
            self.muted = message.optBoolean("muted");
            self.name = conn.name;
            // Everyone already in the call needs a keyframe before they can show any visible tile to the newcomer.
            for (Participant other : participants.values()) {
                if (other != self && other.visible) {
                    conn.awaitingKey.add(other.slot);
                    requestKey(other, System.currentTimeMillis());
                }
            }
            lastVisibleUpdate = 0;
            rosterDirty = true;
        }
        if (started) {
            try {
                JSONObject call = new JSONObject();
                call.put("group", true);
                call.put("status", "started");
                call.put("with", conn.name);
                host.postCallLog(call, conn.name + " started a group call");
            } catch (JSONException ignored) {
            }
        }
    }

    private void leaveCall(Conn conn) {
        JSONObject ended = null;
        synchronized (callLock) {
            Participant self = participants.get(conn.device);
            if (self == null || self.conn != conn) return;
            participants.remove(conn.device);
            speakers.remove(self);
            lastVisibleUpdate = 0;
            rosterDirty = true;
            if (participants.isEmpty()) {
                try {
                    ended = new JSONObject();
                    ended.put("group", true);
                    ended.put("status", "completed");
                    ended.put("duration", Math.max(0, (System.currentTimeMillis() - callStartedAt) / 1000));
                    ended.put("people", everJoined.size());
                } catch (JSONException ignored) {
                }
                callStartedAt = 0;
            }
        }
        if (ended != null) host.postCallLog(ended, "Group call ended");
    }

    /** Binary media: [kind, flags, level, channels, rate/10 (2), reserved (2), timestamp ms (4), payload]. */
    private void onBinary(Conn conn, byte[] data) {
        if (data.length < 12) return;
        int kind = data[0];
        boolean key = (data[1] & 1) != 0;
        int level = data[2] & 0x7f;
        long now = System.currentTimeMillis();
        Participant sender;
        List<Participant> targets;
        boolean forward;
        synchronized (callLock) {
            sender = participants.get(conn.device);
            if (sender == null || sender.conn != conn) return;
            if (kind == 1) {
                if (sender.muted) return;
                if (level >= SPEAK_LEVEL) {
                    sender.lastLoud = now;
                    sender.lastSpoke = now;
                }
                if (!speakers.contains(sender) && now - sender.lastLoud < 300) admitSpeaker(sender, now);
                forward = speakers.contains(sender);
            } else if (kind == 2) {
                forward = sender.visible;
            } else {
                return;
            }
            if (!forward) return;
            targets = new ArrayList<>(participants.values());
        }
        byte[] payload = new byte[data.length + 2];
        payload[0] = (byte) (sender.slot >> 8);
        payload[1] = (byte) sender.slot;
        System.arraycopy(data, 0, payload, 2, data.length);
        byte[] out = frame(0x2, payload);
        for (Participant target : targets) {
            if (target == sender) continue;
            Conn receiver = target.conn;
            if (kind == 1) {
                enqueue(receiver, out, AUDIO_DROP_BYTES);
            } else {
                if (receiver.awaitingKey.contains(sender.slot)) {
                    if (!key) continue;
                    receiver.awaitingKey.remove(sender.slot);
                }
                if (!enqueue(receiver, out, VIDEO_DROP_BYTES)) {
                    // A slow receiver missed a frame: wait for the next keyframe so its decoder never sees a gap.
                    receiver.awaitingKey.add(sender.slot);
                    synchronized (callLock) {
                        requestKey(sender, now);
                    }
                }
            }
        }
    }

    private void admitSpeaker(Participant candidate, long now) {
        if (speakers.size() < MAX_SPEAKERS) {
            speakers.add(candidate);
            rosterDirty = true;
            return;
        }
        Participant quietest = null;
        for (Participant p : speakers) {
            if (now - p.lastLoud > 400 && (quietest == null || p.lastLoud < quietest.lastLoud)) quietest = p;
        }
        if (quietest != null) {
            speakers.remove(quietest);
            speakers.add(candidate);
            rosterDirty = true;
        }
    }

    private void requestKey(Participant target, long now) {
        if (now - target.lastKeyRequest < KEY_REQUEST_GAP_MS) return;
        target.lastKeyRequest = now;
        try {
            JSONObject key = new JSONObject();
            key.put("t", "gc-key");
            enqueue(target.conn, textFrame(key), Long.MAX_VALUE);
        } catch (JSONException ignored) {
        }
    }

    /** Picks which video tiles are forwarded: screen shares first, then whoever spoke most recently. */
    private void updateVisible(long now) {
        lastVisibleUpdate = now;
        List<Participant> candidates = new ArrayList<>();
        for (Participant p : participants.values()) if (p.camera || p.screen) candidates.add(p);
        Collections.sort(candidates, (a, b) -> {
            if (a.screen != b.screen) return a.screen ? -1 : 1;
            if (a.lastSpoke != b.lastSpoke) return Long.compare(b.lastSpoke, a.lastSpoke);
            return Long.compare(a.joinedAt, b.joinedAt);
        });
        int tiles = participants.size() <= 10 ? 6 : 4;
        Set<Participant> next = new HashSet<>(candidates.subList(0, Math.min(tiles, candidates.size())));
        for (Participant p : participants.values()) {
            boolean visible = next.contains(p);
            if (visible == p.visible) continue;
            p.visible = visible;
            rosterDirty = true;
            if (visible) {
                for (Participant receiver : participants.values()) if (receiver != p) receiver.conn.awaitingKey.add(p.slot);
                p.lastKeyRequest = 0;
                requestKey(p, now);
            }
        }
    }

    private JSONObject roster() {
        JSONObject value = new JSONObject();
        try {
            value.put("t", "gc");
            value.put("active", !participants.isEmpty());
            value.put("startedAt", callStartedAt);
            JSONArray list = new JSONArray();
            List<Participant> ordered = new ArrayList<>(participants.values());
            Collections.sort(ordered, (a, b) -> Long.compare(a.joinedAt, b.joinedAt));
            for (Participant p : ordered) {
                JSONObject item = new JSONObject();
                item.put("slot", p.slot);
                item.put("device", p.device);
                item.put("name", p.name);
                item.put("camera", p.camera);
                item.put("muted", p.muted);
                item.put("screen", p.screen);
                item.put("visible", p.visible);
                item.put("speaking", speakers.contains(p));
                list.put(item);
            }
            value.put("participants", list);
        } catch (JSONException ignored) {
        }
        return value;
    }

    JSONObject callSummary() {
        synchronized (callLock) {
            JSONObject summary = new JSONObject();
            try {
                summary.put("active", !participants.isEmpty());
                summary.put("people", participants.size());
                summary.put("startedAt", callStartedAt);
            } catch (JSONException ignored) {
            }
            return summary;
        }
    }
}
