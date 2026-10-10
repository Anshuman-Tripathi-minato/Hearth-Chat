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
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class LanServer {
    private static final String TAG = "HearthLan";
    private static final int PORT = 8787;
    private static final int MAX_TEXT_BYTES = 1024 * 1024;
    private static final long MAX_MEDIA_BYTES = 120L * 1024 * 1024;
    private final Context context;
    private final File mediaDirectory;
    private final File messagesFile;
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final Object dataLock = new Object();
    private JSONArray messages = new JSONArray();
    private long nextSequence = 1;
    private volatile boolean running;
    private ServerSocket listener;

    LanServer(Context context) {
        this.context = context.getApplicationContext();
        File store = this.context.getFilesDir();
        mediaDirectory = new File(store, "media");
        messagesFile = new File(store, "messages.json");
        mediaDirectory.mkdirs();
        loadMessages();
    }

    void start() {
        if (running) return;
        running = true;
        Thread acceptThread = new Thread(this::acceptConnections, "hearth-http");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    void close() {
        running = false;
        try {
            if (listener != null) listener.close();
        } catch (IOException ignored) {
        }
        clients.shutdownNow();
    }

    private void acceptConnections() {
        try (ServerSocket server = new ServerSocket(PORT)) {
            server.setReuseAddress(true);
            listener = server;
            while (running) {
                Socket socket = server.accept();
                socket.setSoTimeout(30_000);
                clients.execute(() -> handleClient(socket));
            }
        } catch (IOException e) {
            if (running) Log.e(TAG, "Could not listen on port " + PORT, e);
        } finally {
            listener = null;
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
            if ("GET".equals(method) || "HEAD".equals(method)) {
                handleGet(path, uri.getRawQuery(), headers, output, "HEAD".equals(method));
                return;
            }
            if (!"POST".equals(method)) {
                respond(output, 405, "Method Not Allowed", "text/plain", "Method not allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/api/messages".equals(path)) {
                if (bodyLength < 0 || bodyLength > MAX_TEXT_BYTES) {
                    respond(output, 413, "Payload Too Large", "application/json", jsonError("Message is too large"));
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
                info.put("room", "Home room");
            } catch (JSONException ignored) {
            }
            respond(output, 200, "OK", "application/json; charset=utf-8", info.toString().getBytes(StandardCharsets.UTF_8), headOnly);
            return;
        }
        if ("/api/messages".equals(path)) {
            long after = parseLong(query(rawQuery).get("after"), 0);
            JSONObject page = messagesAfter(after);
            respond(output, 200, "OK", "application/json; charset=utf-8", page.toString().getBytes(StandardCharsets.UTF_8), headOnly);
            return;
        }
        if (path.startsWith("/media/")) {
            serveMedia(path, headers.get("range"), output, headOnly);
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
            String text = clean(value.optString("text", ""), 5000);
            long createdAt = value.optLong("createdAt", System.currentTimeMillis());
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
                    saved.put("kind", "text");
                    saved.put("seq", nextSequence++);
                    messages.put(saved);
                    persistMessages();
                }
            }
            respond(output, 201, "Created", "application/json; charset=utf-8", saved.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            respond(output, 400, "Bad Request", "application/json", jsonError("Invalid message"));
        }
    }

    private void handlePostMedia(InputStream input, OutputStream output, String rawQuery,
                                 Map<String, String> headers, long length) throws IOException {
        Map<String, String> params = query(rawQuery);
        String contentType = headers.getOrDefault("content-type", "application/octet-stream").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!(contentType.startsWith("image/") || contentType.startsWith("video/"))) {
            respond(output, 415, "Unsupported Media Type", "application/json", jsonError("Choose an image or video"));
            return;
        }
        if (length <= 0 || length > MAX_MEDIA_BYTES) {
            respond(output, 413, "Payload Too Large", "application/json", jsonError("Media must be smaller than 120 MB"));
            return;
        }
        String id = UUID.randomUUID().toString();
        String extension = extensionFor(contentType);
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
        String originalName = clean(new File(params.getOrDefault("name", "Media")).getName(), 160);
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
                saved.put("kind", contentType.startsWith("video/") ? "video" : "image");
                saved.put("mediaUrl", "/media/" + id + extension);
                saved.put("mediaName", originalName);
                saved.put("mediaType", contentType);
                saved.put("mediaBytes", length);
                saved.put("seq", nextSequence++);
                messages.put(saved);
                persistMessages();
            }
            respond(output, 201, "Created", "application/json; charset=utf-8", saved.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            target.delete();
            respond(output, 500, "Internal Server Error", "application/json", jsonError("Could not save media"));
        }
    }

    private void serveMedia(String path, String range, OutputStream output, boolean headOnly) throws IOException {
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
                writeHeaders(output, 416, "Range Not Satisfiable", mimeFor(file), 0,
                        "Content-Range: bytes */" + file.length() + "\r\n", false);
                return;
            }
        }
        long size = end - start + 1;
        writeHeaders(output, partial ? 206 : 200, partial ? "Partial Content" : "OK", mimeFor(file), size,
                "Accept-Ranges: bytes\r\n" + (partial ? "Content-Range: bytes " + start + "-" + end + "/" + file.length() + "\r\n" : ""), headOnly);
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
        JSONArray batch = new JSONArray();
        long last = after;
        boolean more = false;
        synchronized (dataLock) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message == null) continue;
                long seq = message.optLong("seq", 0);
                if (seq > after) {
                    if (batch.length() >= 1000) {
                        more = true;
                        break;
                    }
                    batch.put(message);
                    last = Math.max(last, seq);
                }
            }
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

    private static String clean(String value, int maxLength) {
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
                "Access-Control-Allow-Headers: Content-Type, Range\r\n" +
                "Cache-Control: no-store\r\n" + extra + "\r\n";
        output.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
    }

    private static String extensionFor(String type) {
        switch (type) {
            case "image/jpeg": return ".jpg";
            case "image/png": return ".png";
            case "image/gif": return ".gif";
            case "image/webp": return ".webp";
            case "image/heic": return ".heic";
            case "video/mp4": return ".mp4";
            case "video/webm": return ".webm";
            case "video/quicktime": return ".mov";
            default: return ".bin";
        }
    }

    private static String mimeFor(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".heic")) return "image/heic";
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".mov")) return "video/quicktime";
        return "application/octet-stream";
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
