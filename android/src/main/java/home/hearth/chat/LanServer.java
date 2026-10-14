package home.hearth.chat;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

final class LanServer {
    private static final String TAG = "HearthLan";
    static final int PORT = 8787;
    static final int SECURE_PORT = 8788;
    private static final int MAX_SIGNAL_BYTES = 256 * 1024;
    private static final long PEER_TIMEOUT_MS = 12_000;
    private static final long SIGNAL_TTL_MS = 60_000;
    private static final int MAX_TEXT_BYTES = 10 * 1024 * 1024;
    private static final long MAX_MEDIA_BYTES = 1024L * 1024 * 1024;
    private final Context context;
    private final File mediaDirectory;
    private final File messagesFile;
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final Object dataLock = new Object();
    private JSONArray messages = new JSONArray();
    private long nextSequence = 1;
    private volatile boolean running;
    private ServerSocket listener;
    private ServerSocket secureListener;
    private final Object peerLock = new Object();
    private final Map<String, JSONObject> peers = new HashMap<>();
    private final List<JSONObject> signals = new ArrayList<>();
    private long nextSignal = 1;
    private final RoomInfo roomInfo;
    private final RoomHub hub;

    /** Who this room is, and which other rooms the host phone can see on the Wi-Fi. */
    interface RoomInfo {
        String id();

        String name();

        JSONArray otherRooms();
    }

    LanServer(Context context, RoomInfo roomInfo) {
        this.context = context.getApplicationContext();
        this.roomInfo = roomInfo;
        File store = this.context.getFilesDir();
        mediaDirectory = new File(store, "media");
        messagesFile = new File(store, "messages.json");
        mediaDirectory.mkdirs();
        loadMessages();
        hub = new RoomHub(new HubHost(), clients);
    }

    private final class HubHost implements RoomHub.Host {
        @Override public void touchPeer(String device, String name, boolean typing, boolean busy) {
            updatePeer(device, name, typing, busy);
        }

        @Override public void keepAlive(String device) {
            synchronized (peerLock) {
                JSONObject peer = peers.get(device);
                if (peer != null) {
                    try {
                        peer.put("lastSeen", System.currentTimeMillis());
                    } catch (JSONException ignored) {
                    }
                }
            }
        }

        @Override public void dropPeer(String device) {
            synchronized (peerLock) {
                peers.remove(device);
            }
        }

        @Override public String peersFingerprint() {
            StringBuilder out = new StringBuilder();
            JSONArray online = onlinePeers();
            List<String> rows = new ArrayList<>();
            for (int i = 0; i < online.length(); i++) {
                JSONObject peer = online.optJSONObject(i);
                rows.add(peer.optString("device") + "|" + peer.optString("name") + "|" + peer.optBoolean("typing") + "|" + peer.optBoolean("busy"));
            }
            Collections.sort(rows);
            for (String row : rows) out.append(row).append('\n');
            return out.toString();
        }

        @Override public JSONArray onlinePeers() {
            return LanServer.this.onlinePeers();
        }

        @Override public void relaySignal(JSONObject body) {
            try {
                queueSignal(body);
            } catch (JSONException ignored) {
            }
        }

        @Override public void postCallLog(JSONObject call, String text) {
            postSystemCall(call, text);
        }
    }

    private void updatePeer(String device, String name, boolean typing, boolean busy) {
        if (device == null || device.isEmpty()) return;
        synchronized (peerLock) {
            try {
                JSONObject peer = new JSONObject();
                peer.put("device", device);
                peer.put("name", name == null || name.isEmpty() ? "Someone" : name);
                peer.put("typing", typing);
                peer.put("busy", busy);
                peer.put("lastSeen", System.currentTimeMillis());
                peers.put(device, peer);
            } catch (JSONException ignored) {
            }
        }
    }

    /** Copies of the people online (expired heartbeats removed), without their timestamps. */
    private JSONArray onlinePeers() {
        JSONArray online = new JSONArray();
        long now = System.currentTimeMillis();
        synchronized (peerLock) {
            Iterator<Map.Entry<String, JSONObject>> iterator = peers.entrySet().iterator();
            while (iterator.hasNext()) {
                JSONObject peer = iterator.next().getValue();
                if (now - peer.optLong("lastSeen") > PEER_TIMEOUT_MS) {
                    iterator.remove();
                    continue;
                }
                try {
                    JSONObject copy = new JSONObject();
                    copy.put("device", peer.optString("device"));
                    copy.put("name", peer.optString("name"));
                    copy.put("typing", peer.optBoolean("typing"));
                    copy.put("busy", peer.optBoolean("busy"));
                    online.put(copy);
                } catch (JSONException ignored) {
                }
            }
        }
        return online;
    }

    /** Stores a call signal for HTTP polling and pushes it straight to the target's live connection. */
    private JSONObject queueSignal(JSONObject body) throws JSONException {
        String from = clean(body.optString("from", ""), 80);
        String to = clean(body.optString("to", ""), 80);
        String type = clean(body.optString("type", ""), 24);
        if (from.isEmpty() || to.isEmpty() || type.isEmpty()) return null;
        JSONObject signal = new JSONObject();
        synchronized (peerLock) {
            signal.put("id", nextSignal++);
            signal.put("from", from);
            signal.put("fromName", clean(body.optString("fromName", "Someone"), 48));
            signal.put("to", to);
            signal.put("type", type);
            signal.put("callId", clean(body.optString("callId", ""), 80));
            Object data = body.opt("data");
            if (data != null && data != JSONObject.NULL) signal.put("data", data);
            signal.put("at", System.currentTimeMillis());
            signals.add(signal);
            if (signals.size() > 2000) signals.remove(0);
        }
        JSONObject push = new JSONObject();
        push.put("t", "signal");
        push.put("signal", signal);
        push.put("now", System.currentTimeMillis());
        hub.sendTo(to, push);
        return signal;
    }

    /** Room-authored call log entries (group calls started and ended). */
    private void postSystemCall(JSONObject call, String text) {
        try {
            JSONObject saved = new JSONObject();
            synchronized (dataLock) {
                saved.put("id", UUID.randomUUID().toString());
                saved.put("sender", "room");
                saved.put("senderName", roomName());
                saved.put("text", text);
                saved.put("createdAt", System.currentTimeMillis());
                saved.put("kind", "call");
                saved.put("call", call);
                saved.put("seq", nextSequence++);
                messages.put(saved);
                persistMessages();
            }
            notifyMessage(saved);
        } catch (JSONException ignored) {
        }
    }

    /** Pushes a new or changed message to every live connection. Very large messages are fetched instead. */
    private void notifyMessage(JSONObject message) {
        try {
            String json;
            synchronized (dataLock) {
                json = message.toString();
            }
            JSONObject push = new JSONObject();
            if (json.length() > 64 * 1024) {
                push.put("t", "sync");
            } else {
                push.put("t", "msg");
                push.put("message", new JSONObject(json));
            }
            hub.broadcast(push);
        } catch (JSONException ignored) {
        }
    }

    String roomName() {
        String name = roomInfo == null ? null : roomInfo.name();
        return name == null || name.isEmpty() ? "Home room" : name;
    }

    int liveConnections() {
        return hub.connectionCount();
    }

    boolean start() {
        if (running) return true;
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PORT));
            listener = server;
            running = true;
            Thread acceptThread = new Thread(() -> acceptConnections(server), "hearth-http");
            acceptThread.setDaemon(true);
            acceptThread.start();
            startSecure();
            return true;
        } catch (IOException e) {
            running = false;
            Log.e(TAG, "Could not listen on port " + PORT, e);
            return false;
        }
    }

    /** HTTPS lets browsers use the microphone and camera for voice messages and calls. */
    private void startSecure() {
        try {
            SSLContext tls = SelfSignedCert.sslContext(context.getFilesDir());
            SSLServerSocket server = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(SECURE_PORT));
            secureListener = server;
            Thread acceptThread = new Thread(() -> acceptConnections(server), "hearth-https");
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (Exception e) {
            Log.e(TAG, "Could not start the secure room on port " + SECURE_PORT, e);
        }
    }

    void close() {
        running = false;
        try {
            if (listener != null) listener.close();
        } catch (IOException ignored) {
        }
        try {
            if (secureListener != null) secureListener.close();
        } catch (IOException ignored) {
        }
        hub.close();
        clients.shutdownNow();
    }

    private void acceptConnections(ServerSocket server) {
        try {
            while (running) {
                Socket socket = server.accept();
                socket.setSoTimeout(30_000);
                clients.execute(() -> handleClient(socket));
            }
        } catch (IOException e) {
            if (running) Log.e(TAG, "Could not listen on port " + server.getLocalPort(), e);
        }
    }

    private void handleClient(Socket socket) {
        try (Socket client = socket) {
            BufferedInputStream input = new BufferedInputStream(client.getInputStream());
            OutputStream output = client.getOutputStream();
            String requestLine = readLine(input);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] pieces = requestLine.split(" ", 3);
            if (pieces.length < 2) {
                respond(output, 400, "Bad Request", "text/plain", "Bad request".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String method = pieces[0].toUpperCase(Locale.ROOT);
            URI uri = URI.create("http://localhost" + pieces[1]);
            String path = uri.getPath();
            Map<String, String> headers = readHeaders(input);
            long bodyLength = parseLong(headers.get("content-length"), 0);

            if ("OPTIONS".equals(method)) {
                respond(output, 204, "No Content", "text/plain", new byte[0]);
                return;
            }
            if ("GET".equals(method) && "/ws".equals(path) && RoomHub.isUpgrade(headers)) {
                hub.serve(client, input, output, headers);
                return;
            }
            if ("GET".equals(method) || "HEAD".equals(method)) {
                handleGet(path, uri.getRawQuery(), headers, output, "HEAD".equals(method));
                return;
            }
            if (!"POST".equals(method)) {
                respond(output, 405, "Method Not Allowed", "text/plain", "Method not allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/api/signal".equals(path) || "/api/react".equals(path) || "/api/delete".equals(path)) {
                if (bodyLength <= 0 || bodyLength > MAX_SIGNAL_BYTES) {
                    respond(output, 413, "Payload Too Large", "application/json", jsonError("Request too large"));
                    return;
                }
                JSONObject body;
                try {
                    body = new JSONObject(new String(readExactly(input, (int) bodyLength), StandardCharsets.UTF_8));
                } catch (JSONException e) {
                    respond(output, 400, "Bad Request", "application/json", jsonError("Invalid request"));
                    return;
                }
                if ("/api/signal".equals(path)) handleSignal(body, output);
                else if ("/api/react".equals(path)) handleReact(body, output);
                else handleDelete(body, output);
            } else if ("/api/messages".equals(path)) {
                if (bodyLength < 0 || bodyLength > MAX_TEXT_BYTES) {
                    respond(output, 413, "Payload Too Large", "application/json", jsonError("Messages can be up to 10 MB"));
                    return;
                }
                handlePostMessage(input, output, (int) bodyLength);
            } else if ("/api/media".equals(path)) {
                handlePostMedia(input, output, uri.getRawQuery(), headers, bodyLength);
            } else {
                respond(output, 404, "Not Found", "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "Request failed", e);
        }
    }

    private void handleGet(String path, String rawQuery, Map<String, String> headers,
                           OutputStream output, boolean headOnly) throws IOException {
        if ("/".equals(path) || "/index.html".equals(path)) {
            try (InputStream page = context.getAssets().open("index.html")) {
                respond(output, 200, "OK", "text/html; charset=utf-8", readAll(page), headOnly);
            } catch (IOException e) {
                respond(output, 500, "Internal Server Error", "text/plain",
                        "The chat page is missing".getBytes(StandardCharsets.UTF_8), headOnly);
            }
            return;
        }
        if ("/api/info".equals(path)) {
            String ip = getWifiAddress(context);
            JSONObject info = new JSONObject();
            try {
                info.put("ip", ip);
                info.put("port", PORT);
                info.put("url", "0.0.0.0".equals(ip) ? "" : "http://" + ip + ":" + PORT);
                info.put("secureUrl", "0.0.0.0".equals(ip) || secureListener == null ? "" : "https://" + ip + ":" + SECURE_PORT);
                info.put("room", roomName());
                info.put("roomId", roomInfo == null ? "" : roomInfo.id());
                info.put("online", onlinePeers().length());
                info.put("call", hub.callSummary());
                info.put("version", 3);
            } catch (JSONException ignored) {
            }
            respond(output, 200, "OK", "application/json; charset=utf-8", info.toString().getBytes(StandardCharsets.UTF_8), headOnly);
            return;
        }
        if ("/api/messages".equals(path)) {
            long after = parseLong(query(rawQuery).get("after"), 0);
            byte[] page;
            synchronized (dataLock) {
                page = messagesAfter(after).toString().getBytes(StandardCharsets.UTF_8);
            }
            respond(output, 200, "OK", "application/json; charset=utf-8", page, headOnly);
            return;
        }
        if ("/api/rooms".equals(path)) {
            JSONObject result = new JSONObject();
            try {
                result.put("rooms", roomInfo == null ? new JSONArray() : roomInfo.otherRooms());
            } catch (JSONException ignored) {
            }
            respond(output, 200, "OK", "application/json; charset=utf-8", result.toString().getBytes(StandardCharsets.UTF_8), headOnly);
            return;
        }
        if ("/api/presence".equals(path)) {
            byte[] body = presence(query(rawQuery)).toString().getBytes(StandardCharsets.UTF_8);
            respond(output, 200, "OK", "application/json; charset=utf-8", body, headOnly);
            return;
        }
        if (path.startsWith("/media/")) {
            serveMedia(path, rawQuery, headers.get("range"), output, headOnly);
            return;
        }
        respond(output, 404, "Not Found", "text/plain", "Not found".getBytes(StandardCharsets.UTF_8), headOnly);
    }

    private void handlePostMessage(InputStream input, OutputStream output, int length) throws IOException {
        byte[] body = readExactly(input, length);
        try {
            JSONObject value = new JSONObject(new String(body, StandardCharsets.UTF_8));
            String id = clean(value.optString("id", ""), 80);
            String sender = clean(value.optString("sender", ""), 80);
            String senderName = clean(value.optString("senderName", "Someone"), 48);
            String text = clean(value.optString("text", ""), Integer.MAX_VALUE);
            long createdAt = value.optLong("createdAt", System.currentTimeMillis());
            boolean isCall = "call".equals(value.optString("kind"));
            if (id.isEmpty() || sender.isEmpty() || text.trim().isEmpty()) {
                respond(output, 400, "Bad Request", "application/json", jsonError("Add a message first"));
                return;
            }
            JSONObject saved = new JSONObject();
            synchronized (dataLock) {
                JSONObject previous = findMessage(id);
                if (previous != null) saved = previous;
                else {
                    saved.put("id", id);
                    saved.put("sender", sender);
                    saved.put("senderName", senderName.isEmpty() ? "Someone" : senderName);
                    saved.put("text", text);
                    saved.put("createdAt", createdAt);
                    saved.put("kind", isCall ? "call" : "text");
                    if (isCall) saved.put("call", cleanCall(value.optJSONObject("call")));
                    JSONObject reply = cleanReply(value.optJSONObject("replyTo"));
                    if (reply != null) saved.put("replyTo", reply);
                    saved.put("seq", nextSequence++);
                    messages.put(saved);
                    persistMessages();
                }
            }
            notifyMessage(saved);
            respond(output, 201, "Created", "application/json; charset=utf-8", snapshot(saved));
        } catch (JSONException e) {
            respond(output, 400, "Bad Request", "application/json", jsonError("Invalid message"));
        }
    }

    private void handlePostMedia(InputStream input, OutputStream output, String rawQuery,
                                 Map<String, String> headers, long length) throws IOException {
        Map<String, String> params = query(rawQuery);
        String contentType = headers.getOrDefault("content-type", "application/octet-stream").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        String originalName = clean(new File(params.getOrDefault("name", "File")).getName(), 160);
        if (length <= 0 || length > MAX_MEDIA_BYTES) {
            respond(output, 413, "Payload Too Large", "application/json", jsonError("Files must be smaller than 1 GB"));
            return;
        }
        String id = UUID.randomUUID().toString();
        String extension = extensionFor(originalName, contentType);
        contentType = MIME_TYPES.getOrDefault(extension, "application/octet-stream");
        File target = new File(mediaDirectory, id + extension);
        try (FileOutputStream file = new FileOutputStream(target)) {
            copyExactly(input, file, length);
        } catch (IOException e) {
            target.delete();
            throw e;
        }
        String sender = clean(params.getOrDefault("sender", ""), 80);
        String senderName = clean(params.getOrDefault("senderName", "Someone"), 48);
        String caption = clean(params.getOrDefault("caption", ""), 1000);
        if (originalName.isEmpty()) originalName = "File" + extension;
        if (sender.isEmpty()) {
            target.delete();
            respond(output, 400, "Bad Request", "application/json", jsonError("Device name is missing"));
            return;
        }
        try {
            JSONObject saved = new JSONObject();
            synchronized (dataLock) {
                saved.put("id", id);
                saved.put("sender", sender);
                saved.put("senderName", senderName.isEmpty() ? "Someone" : senderName);
                saved.put("text", caption);
                saved.put("createdAt", System.currentTimeMillis());
                saved.put("kind", kindFor(contentType));
                saved.put("mediaUrl", "/media/" + id + extension);
                saved.put("mediaName", originalName);
                saved.put("mediaType", contentType);
                saved.put("mediaBytes", length);
                if ("1".equals(params.get("voice")) && contentType.startsWith("audio/")) {
                    saved.put("voice", true);
                    saved.put("duration", Math.max(0, Math.min(3600, parseLong(params.get("duration"), 0))));
                }
                if (params.containsKey("replyId")) {
                    JSONObject reply = new JSONObject();
                    reply.put("id", params.get("replyId"));
                    reply.put("name", params.getOrDefault("replyName", ""));
                    reply.put("snippet", params.getOrDefault("replySnippet", ""));
                    JSONObject cleaned = cleanReply(reply);
                    if (cleaned != null) saved.put("replyTo", cleaned);
                }
                saved.put("seq", nextSequence++);
                messages.put(saved);
                persistMessages();
            }
            notifyMessage(saved);
            respond(output, 201, "Created", "application/json; charset=utf-8", snapshot(saved));
        } catch (JSONException e) {
            target.delete();
            respond(output, 500, "Internal Server Error", "application/json", jsonError("Could not save media"));
        }
    }

    private byte[] snapshot(JSONObject message) {
        synchronized (dataLock) {
            return message.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    private static JSONObject cleanReply(JSONObject reply) throws JSONException {
        if (reply == null) return null;
        String id = clean(reply.optString("id", ""), 80);
        if (id.isEmpty()) return null;
        JSONObject result = new JSONObject();
        result.put("id", id);
        result.put("name", clean(reply.optString("name", ""), 48));
        result.put("snippet", clean(reply.optString("snippet", ""), 160));
        return result;
    }

    private static JSONObject cleanCall(JSONObject call) throws JSONException {
        JSONObject result = new JSONObject();
        if (call == null) return result;
        result.put("video", call.optBoolean("video", false));
        result.put("status", clean(call.optString("status", "ended"), 20));
        result.put("duration", Math.max(0, Math.min(86_400, call.optLong("duration", 0))));
        result.put("with", clean(call.optString("with", ""), 48));
        return result;
    }

    /** Heartbeat from a device: records it as online and hands back the people online plus any call signals for it. */
    private JSONObject presence(Map<String, String> params) {
        String device = clean(params.getOrDefault("device", ""), 80);
        long after = parseLong(params.get("after"), 0);
        long now = System.currentTimeMillis();
        JSONObject result = new JSONObject();
        JSONArray online = new JSONArray();
        JSONArray inbox = new JSONArray();
        long last = after;
        updatePeer(device, clean(params.getOrDefault("name", "Someone"), 48),
                "1".equals(params.get("typing")), "1".equals(params.get("busy")));
        online = onlinePeers();
        synchronized (peerLock) {
            try {
                Iterator<JSONObject> signalIterator = signals.iterator();
                while (signalIterator.hasNext()) {
                    JSONObject signal = signalIterator.next();
                    boolean mine = device.equals(signal.optString("to"));
                    if (now - signal.optLong("at") > SIGNAL_TTL_MS || (mine && signal.optLong("id") <= after)) {
                        signalIterator.remove();
                    } else if (mine) {
                        inbox.put(signal);
                        last = Math.max(last, signal.optLong("id"));
                    }
                }
                result.put("peers", online);
                result.put("signals", inbox);
                result.put("next", last);
                result.put("now", now);
            } catch (JSONException ignored) {
            }
        }
        return result;
    }

    private void handleSignal(JSONObject body, OutputStream output) throws IOException {
        try {
            if (queueSignal(body) == null) {
                respond(output, 400, "Bad Request", "application/json", jsonError("Missing call details"));
                return;
            }
            respond(output, 201, "Created", "application/json; charset=utf-8", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            respond(output, 400, "Bad Request", "application/json", jsonError("Invalid signal"));
        }
    }

    /** Toggles one device's emoji reaction; the message gets a new sequence number so every client picks up the change. */
    private void handleReact(JSONObject body, OutputStream output) throws IOException {
        String id = clean(body.optString("id", ""), 80);
        String device = clean(body.optString("device", ""), 80);
        String emoji = clean(body.optString("emoji", ""), 16);
        if (id.isEmpty() || device.isEmpty() || emoji.isEmpty()) {
            respond(output, 400, "Bad Request", "application/json", jsonError("Missing reaction"));
            return;
        }
        try {
            JSONObject saved;
            synchronized (dataLock) {
                saved = findMessage(id);
                if (saved == null || saved.optBoolean("deleted")) {
                    respond(output, 404, "Not Found", "application/json", jsonError("Message not found"));
                    return;
                }
                JSONObject reactions = saved.optJSONObject("reactions");
                if (reactions == null) reactions = new JSONObject();
                JSONArray who = reactions.optJSONArray(emoji);
                JSONArray next = new JSONArray();
                boolean removed = false;
                if (who != null) {
                    for (int i = 0; i < who.length(); i++) {
                        if (device.equals(who.optString(i))) removed = true;
                        else next.put(who.optString(i));
                    }
                }
                if (!removed) {
                    if (reactions.length() >= 24 && who == null) {
                        respond(output, 400, "Bad Request", "application/json", jsonError("Too many reactions"));
                        return;
                    }
                    next.put(device);
                }
                if (next.length() == 0) reactions.remove(emoji);
                else reactions.put(emoji, next);
                saved.put("reactions", reactions);
                saved.put("seq", nextSequence++);
                persistMessages();
            }
            notifyMessage(saved);
            respond(output, 200, "OK", "application/json; charset=utf-8", snapshot(saved));
        } catch (JSONException e) {
            respond(output, 400, "Bad Request", "application/json", jsonError("Invalid reaction"));
        }
    }

    /** Deletes a message for everyone. Only the device that sent it may do this; its shared file is removed too. */
    private void handleDelete(JSONObject body, OutputStream output) throws IOException {
        String id = clean(body.optString("id", ""), 80);
        String device = clean(body.optString("device", ""), 80);
        try {
            JSONObject saved;
            String mediaUrl;
            synchronized (dataLock) {
                saved = findMessage(id);
                if (saved == null) {
                    respond(output, 404, "Not Found", "application/json", jsonError("Message not found"));
                    return;
                }
                if (device.isEmpty() || !device.equals(saved.optString("sender"))) {
                    respond(output, 403, "Forbidden", "application/json", jsonError("You can only delete your own messages"));
                    return;
                }
                mediaUrl = saved.optString("mediaUrl", "");
                String[] cleared = {"text", "mediaUrl", "mediaName", "mediaType", "mediaBytes", "voice", "duration", "reactions", "replyTo", "call"};
                for (String key : cleared) saved.remove(key);
                saved.put("text", "");
                saved.put("deleted", true);
                saved.put("seq", nextSequence++);
                persistMessages();
            }
            if (mediaUrl.startsWith("/media/")) {
                String name = mediaUrl.substring("/media/".length());
                if (!name.contains("/") && !name.contains("..")) new File(mediaDirectory, name).delete();
            }
            notifyMessage(saved);
            respond(output, 200, "OK", "application/json; charset=utf-8", snapshot(saved));
        } catch (JSONException e) {
            respond(output, 400, "Bad Request", "application/json", jsonError("Invalid request"));
        }
    }

    private void serveMedia(String path, String rawQuery, String range, OutputStream output, boolean headOnly) throws IOException {
        String name = path.substring("/media/".length());
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            respond(output, 400, "Bad Request", "text/plain", new byte[0], headOnly);
            return;
        }
        File file = new File(mediaDirectory, name);
        if (!file.isFile()) {
            respond(output, 404, "Not Found", "text/plain", "Media not found".getBytes(StandardCharsets.UTF_8), headOnly);
            return;
        }
        Map<String, String> params = query(rawQuery);
        String type = mimeFor(file);
        boolean inline = isInlineSafe(type) && !params.containsKey("download");
        String downloadName = clean(new File(params.getOrDefault("name", name)).getName(), 160);
        if (downloadName.isEmpty()) downloadName = name;
        String disposition = "Content-Disposition: " + (inline ? "inline" : "attachment") +
                "; filename=\"" + downloadName.replaceAll("[^\\x20-\\x7e]|[\"\\\\]", "_") + "\"" +
                "; filename*=UTF-8''" + encodeRfc5987(downloadName) + "\r\n" +
                "X-Content-Type-Options: nosniff\r\n";
        long start = 0;
        long end = file.length() - 1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            try {
                String[] parts = range.substring(6).split("-", 2);
                start = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
                if (start < 0 || end < start || end >= file.length()) throw new NumberFormatException();
                partial = true;
            } catch (NumberFormatException e) {
                writeHeaders(output, 416, "Range Not Satisfiable", type, 0,
                        "Content-Range: bytes */" + file.length() + "\r\n", false);
                return;
            }
        }
        long size = end - start + 1;
        writeHeaders(output, partial ? 206 : 200, partial ? "Partial Content" : "OK", type, size,
                disposition + "Accept-Ranges: bytes\r\n" + (partial ? "Content-Range: bytes " + start + "-" + end + "/" + file.length() + "\r\n" : ""), headOnly);
        if (headOnly) return;
        try (FileInputStream in = new FileInputStream(file)) {
            in.skip(start);
            byte[] buffer = new byte[16 * 1024];
            long left = size;
            while (left > 0) {
                int count = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (count < 0) break;
                output.write(buffer, 0, count);
                left -= count;
            }
        }
    }

    private JSONObject messagesAfter(long after) {
        List<JSONObject> newer = new ArrayList<>();
        synchronized (dataLock) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message != null && message.optLong("seq", 0) > after) newer.add(message);
            }
        }
        // Edited messages get fresh sequence numbers, so page in sequence order to keep the client cursor correct.
        Collections.sort(newer, (a, b) -> Long.compare(a.optLong("seq", 0), b.optLong("seq", 0)));
        boolean more = newer.size() > 1000;
        if (more) newer = newer.subList(0, 1000);
        JSONArray batch = new JSONArray();
        long last = after;
        for (JSONObject message : newer) {
            batch.put(message);
            last = Math.max(last, message.optLong("seq", 0));
        }
        JSONObject result = new JSONObject();
        try {
            result.put("messages", batch);
            result.put("next", last);
            result.put("more", more);
        } catch (JSONException ignored) {
        }
        return result;
    }

    private JSONObject findMessage(String id) {
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message != null && id.equals(message.optString("id"))) return message;
        }
        return null;
    }

    private void loadMessages() {
        if (!messagesFile.isFile()) return;
        try (FileInputStream in = new FileInputStream(messagesFile)) {
            JSONArray loaded = new JSONArray(new String(readAll(in), StandardCharsets.UTF_8));
            messages = loaded;
            for (int i = 0; i < messages.length(); i++) {
                nextSequence = Math.max(nextSequence, messages.optJSONObject(i).optLong("seq", 0) + 1);
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not read saved messages", e);
        }
    }

    private void persistMessages() {
        File pending = new File(messagesFile.getParentFile(), "messages.tmp");
        try (FileOutputStream out = new FileOutputStream(pending)) {
            out.write(messages.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            Log.e(TAG, "Could not write messages", e);
            return;
        }
        if (!pending.renameTo(messagesFile)) {
            try (FileInputStream in = new FileInputStream(pending);
                 FileOutputStream out = new FileOutputStream(messagesFile)) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            } catch (IOException e) {
                Log.e(TAG, "Could not finalize saved messages", e);
            } finally {
                pending.delete();
            }
        }
    }

    private static Map<String, String> readHeaders(InputStream input) throws IOException {
        Map<String, String> result = new HashMap<>();
        String line;
        while ((line = readLine(input)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) result.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        return result;
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') line.write(value);
            if (line.size() > 16 * 1024) throw new IOException("Header line too long");
        }
        if (value == -1 && line.size() == 0) return null;
        return line.toString(StandardCharsets.ISO_8859_1.name());
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(length, 64 * 1024));
        copyExactly(input, out, length);
        return out.toByteArray();
    }

    private static void copyExactly(InputStream input, OutputStream output, long length) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long left = length;
        while (left > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (count == -1) throw new IOException("Request body ended early");
            output.write(buffer, 0, count);
            left -= count;
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toByteArray();
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return result;
        for (String part : rawQuery.split("&")) {
            int equals = part.indexOf('=');
            String key = equals < 0 ? part : part.substring(0, equals);
            String value = equals < 0 ? "" : part.substring(equals + 1);
            try {
                result.put(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(value, "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    static String clean(String value, int maxLength) {
        if (value == null) return "";
        String cleaned = value.replace("\u0000", "").trim();
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static byte[] jsonError(String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("error", message);
        } catch (JSONException ignored) {
        }
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void respond(OutputStream output, int code, String reason, String type, byte[] body) throws IOException {
        respond(output, code, reason, type, body, false);
    }

    private static void respond(OutputStream output, int code, String reason, String type, byte[] body, boolean headOnly) throws IOException {
        writeHeaders(output, code, reason, type, body.length, "", headOnly);
        if (!headOnly && body.length > 0) output.write(body);
    }

    private static void writeHeaders(OutputStream output, int code, String reason, String type,
                                     long length, String extra, boolean headOnly) throws IOException {
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n" +
                "Content-Type: " + type + "\r\n" +
                "Content-Length: " + length + "\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS, HEAD\r\n" +
                "Access-Control-Allow-Private-Network: true\r\n" +
                "Access-Control-Allow-Headers: Content-Type, Range\r\n" +
                "Cache-Control: no-store\r\n" + extra + "\r\n";
        output.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
    }

    private static final Map<String, String> MIME_TYPES = new HashMap<>();
    static {
        String[][] known = {
                {".jpg", "image/jpeg"}, {".jpeg", "image/jpeg"}, {".png", "image/png"}, {".gif", "image/gif"},
                {".webp", "image/webp"}, {".heic", "image/heic"}, {".heif", "image/heif"}, {".bmp", "image/bmp"},
                {".avif", "image/avif"}, {".svg", "image/svg+xml"},
                {".mp4", "video/mp4"}, {".m4v", "video/mp4"}, {".webm", "video/webm"}, {".mov", "video/quicktime"},
                {".mkv", "video/x-matroska"}, {".3gp", "video/3gpp"}, {".avi", "video/x-msvideo"},
                {".mp3", "audio/mpeg"}, {".weba", "audio/webm"}, {".m4a", "audio/mp4"}, {".aac", "audio/aac"}, {".wav", "audio/wav"},
                {".ogg", "audio/ogg"}, {".oga", "audio/ogg"}, {".opus", "audio/opus"}, {".flac", "audio/flac"},
                {".pdf", "application/pdf"}, {".txt", "text/plain"}, {".csv", "text/csv"}, {".md", "text/markdown"},
                {".json", "application/json"}, {".rtf", "application/rtf"},
                {".doc", "application/msword"},
                {".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
                {".xls", "application/vnd.ms-excel"},
                {".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"},
                {".ppt", "application/vnd.ms-powerpoint"},
                {".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"},
                {".odt", "application/vnd.oasis.opendocument.text"},
                {".ods", "application/vnd.oasis.opendocument.spreadsheet"},
                {".odp", "application/vnd.oasis.opendocument.presentation"},
                {".epub", "application/epub+zip"},
                {".zip", "application/zip"}, {".rar", "application/vnd.rar"}, {".7z", "application/x-7z-compressed"},
                {".tar", "application/x-tar"}, {".gz", "application/gzip"}, {".tgz", "application/gzip"},
                {".apk", "application/vnd.android.package-archive"},
        };
        for (String[] pair : known) MIME_TYPES.put(pair[0], pair[1]);
    }

    /** Keeps the sender's file extension (sanitized) so downloads open in the right app. */
    private static String extensionFor(String name, String type) {
        if (type.equals("audio/webm")) return ".weba";
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String extension = name.substring(dot).toLowerCase(Locale.ROOT);
            if (extension.matches("\\.[a-z0-9]{1,10}")) return extension;
        }
        for (Map.Entry<String, String> entry : MIME_TYPES.entrySet()) {
            if (entry.getValue().equals(type)) return entry.getKey();
        }
        return ".bin";
    }

    private static String mimeFor(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String known = dot >= 0 ? MIME_TYPES.get(name.substring(dot)) : null;
        return known == null ? "application/octet-stream" : known;
    }

    private static String kindFor(String type) {
        if (type.startsWith("image/") && !type.equals("image/svg+xml")) return "image";
        if (type.startsWith("video/")) return "video";
        if (type.startsWith("audio/")) return "audio";
        return "file";
    }

    /** Only previews that cannot run script in the room page are shown inline; everything else downloads. */
    private static boolean isInlineSafe(String type) {
        return (type.startsWith("image/") && !type.equals("image/svg+xml")) || type.startsWith("video/")
                || type.startsWith("audio/") || type.equals("application/pdf") || type.equals("text/plain");
    }

    private static String encodeRfc5987(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "-._~".indexOf(c) >= 0) {
                out.append((char) c);
            } else {
                out.append('%').append(String.format(Locale.ROOT, "%02X", c));
            }
        }
        return out.toString();
    }

    static String getWifiAddress(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                int address = wifi.getConnectionInfo().getIpAddress();
                if (address != 0) return Formatter.formatIpAddress(address);
            }
        } catch (Exception ignored) {
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface network : Collections.list(interfaces)) {
                for (java.net.InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "0.0.0.0";
    }
}
