package home.hearth.chat;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebChromeClient.FileChooserParams;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 43;
    private static final int DOWNLOAD_PERMISSION_REQUEST = 44;
    private static final int MEDIA_PERMISSION_REQUEST = 45;
    private static final int SECURE_ATTEMPTS = 6;
    private PermissionRequest pendingPermission;
    private int secureFailures;
    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int loadAttempts;
    private static final String LOBBY_URL = "file:///android_asset/lobby.html";
    private static final String MESH_URL = "file:///android_asset/mesh.html";
    private static final int FEATURE_PERMISSION_REQUEST = 46;
    private Runnable afterPermission;
    private String permissionDeniedMessage = "";
    private android.media.MediaRecorder noteRecorder;
    private java.io.File noteFile;
    private long noteStartedAt;
    private String loadedRoomUrl = null;
    private volatile boolean onLobby;
    private ValueCallback<Uri[]> uploadCallback;
    private PendingDownload pendingDownload;
    private final Runnable roomUrlWatcher = new Runnable() {
        @Override public void run() {
            if (webView == null || isFinishing()) return;
            // Wait for the room service so a room from the previous session is not opened by mistake.
            if (RoomDiscovery.current() != null) {
                String roomUrl = getRoomUrl();
                if (!roomUrl.equals(loadedRoomUrl)) {
                    loadedRoomUrl = roomUrl;
                    loadAttempts = 0;
                    secureFailures = 0;
                    if (roomUrl.isEmpty()) showLobby();
                    else webView.loadUrl(pageUrl(roomUrl));
                }
            }
            handler.postDelayed(this, 500);
        }
    };

    private static final class PendingDownload {
        final String url;
        final String userAgent;
        final String contentDisposition;
        final String mimeType;
        final String fileName;

        PendingDownload(String url, String userAgent, String contentDisposition, String mimeType) {
            this(url, userAgent, contentDisposition, mimeType, null);
        }

        PendingDownload(String url, String userAgent, String contentDisposition, String mimeType, String fileName) {
            this.url = url;
            this.userAgent = userAgent;
            this.contentDisposition = contentDisposition;
            this.mimeType = mimeType;
            this.fileName = fileName;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startLanService();
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
        }

        webView = new WebView(this);
        boolean darkMode = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        webView.setBackgroundColor(darkMode ? 0xFF0F1A17 : 0xFFF4F7F4);
        webView.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                onLobby = url != null && url.startsWith("file:///android_asset/");
                attachMeshEvents(url != null && url.startsWith(MESH_URL));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                loadAttempts = 0;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                String activeUrl = getRoomUrl();
                if (!request.isForMainFrame() || activeUrl.isEmpty() || onLobby) return;
                String failed = request.getUrl().toString();
                if (failed.startsWith(secureUrl(activeUrl)) && ++secureFailures >= SECURE_ATTEMPTS) {
                    // The host may run an older Hearth without HTTPS: chat still works, calls will not.
                    handler.postDelayed(() -> view.loadUrl(pageUrl(getRoomUrl())), 500);
                } else if (failed.startsWith(pageUrl(activeUrl)) && loadAttempts < 20) {
                    loadAttempts++;
                    handler.postDelayed(() -> view.loadUrl(pageUrl(getRoomUrl())), 500);
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler sslHandler, SslError error) {
                // The room phone uses its own self-signed certificate; trust it only for the room's address.
                Uri failing = Uri.parse(error.getUrl());
                Uri room = Uri.parse(getRoomUrl());
                if (failing.getHost() != null && failing.getHost().equals(room.getHost())
                        && failing.getPort() == LanServer.SECURE_PORT) {
                    sslHandler.proceed();
                } else {
                    sslHandler.cancel();
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> handleMediaPermission(request));
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingPermission == request) pendingPermission = null;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams chooserParams) {
                if (uploadCallback != null) uploadCallback.onReceiveValue(null);
                uploadCallback = callback;
                try {
                    startActivityForResult(chooserParams.createIntent(), FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    uploadCallback.onReceiveValue(null);
                    uploadCallback = null;
                    Toast.makeText(MainActivity.this, "Could not open the file picker.", Toast.LENGTH_LONG).show();
                    return true;
                }
            }
        });
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                beginDownload(new PendingDownload(url, userAgent, contentDisposition, mimeType)));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        // Group calls fall back to the room's plain WebSocket when the secure one is refused.
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        webView.addJavascriptInterface(new CallBridge(), "HearthAndroid");
        setContentView(wrapWithSystemBarInsets(webView));
        handler.post(roomUrlWatcher);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** Notification taps: join the announced room, or show the room list. */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String joinId = intent.getStringExtra(LanService.EXTRA_JOIN_ROOM);
        int notificationId = intent.getIntExtra("notification_id", 0);
        if (notificationId != 0) {
            android.app.NotificationManager manager = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.cancel(notificationId);
        }
        if (joinId != null && !joinId.isEmpty()) {
            intent.removeExtra(LanService.EXTRA_JOIN_ROOM);
            joinWhenReady(joinId, 0);
        } else if (intent.getBooleanExtra(LanService.EXTRA_SHOW_ROOMS, false)) {
            intent.removeExtra(LanService.EXTRA_SHOW_ROOMS);
            handler.postDelayed(this::showLobby, 300);
        } else if (intent.getBooleanExtra(LanService.EXTRA_SHOW_MESH, false)) {
            intent.removeExtra(LanService.EXTRA_SHOW_MESH);
            handler.postDelayed(this::showMesh, 300);
        }
    }

    private void showMesh() {
        if (webView == null) return;
        onLobby = true;
        webView.loadUrl(MESH_URL);
    }

    /** Streams Bluetooth mesh and call events into the Bluetooth chat page while it is open. */
    private void attachMeshEvents(boolean attach) {
        BleMesh mesh = BleMesh.current();
        MeshCall call = LanService.meshCall();
        if (mesh != null) mesh.setListener(attach ? this::pushMeshEvent : null);
        if (call != null) call.setListener(attach ? this::pushMeshEvent : null);
    }

    private void pushMeshEvent(org.json.JSONObject event) {
        String script = "window.meshEvent && window.meshEvent(" + event + ")";
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(script, null);
        });
    }

    /** Asks for the given runtime permissions, then runs the action if they were granted. */
    private void withPermissions(String[] wanted, String deniedMessage, Runnable action) {
        String[] missing = MeshPermissions.missing(this, wanted);
        if (missing.length == 0) {
            action.run();
            return;
        }
        afterPermission = action;
        permissionDeniedMessage = deniedMessage;
        requestPermissions(missing, FEATURE_PERMISSION_REQUEST);
    }

    private void joinWhenReady(String id, int attempt) {
        RoomDiscovery directory = RoomDiscovery.current();
        if (directory != null) {
            directory.join(id);
        } else if (attempt < 20) {
            handler.postDelayed(() -> joinWhenReady(id, attempt + 1), 250);
        }
    }

    private void showLobbyForced() {
        if (webView == null) return;
        onLobby = true;
        webView.loadUrl(LOBBY_URL);
    }

    private void showLobby() {
        if (webView != null && !onLobby) {
            onLobby = true;
            webView.loadUrl(LOBBY_URL);
        }
    }

    /**
     * Keeps the page clear of the status bar, navigation bar, camera cutout and keyboard. Android 15 draws apps
     * edge to edge, so without this the room header sits underneath the clock and battery icons.
     */
    private android.view.View wrapWithSystemBarInsets(WebView page) {
        boolean dark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int barColor = dark ? 0xFF121E1A : 0xFFFFFFFF;
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(barColor);
        root.addView(page, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        android.view.Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                android.graphics.Insets keyboard = insets.getInsets(android.view.WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
                return android.view.WindowInsets.CONSUMED;
            });
            // Android 11 crashes in getInsetsController() until the decor view exists; getDecorView() creates it.
            window.getDecorView();
            android.view.WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark ? 0 : light, light);
            }
        } else {
            // Older Android keeps the page below the bars by itself; just match the bar colours and resize for the keyboard.
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.setStatusBarColor(barColor);
            window.setNavigationBarColor(barColor);
            if (!dark) {
                int flags = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                window.getDecorView().setSystemUiVisibility(flags);
            }
        }
        if (Build.VERSION.SDK_INT < 35) {
            window.setStatusBarColor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? android.graphics.Color.TRANSPARENT : barColor);
            window.setNavigationBarColor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? android.graphics.Color.TRANSPARENT : barColor);
        }
        return root;
    }

    private boolean stopNote(boolean discard) {
        boolean ok = true;
        if (noteRecorder != null) {
            try {
                noteRecorder.stop();
            } catch (Exception e) {
                ok = false;
            }
            noteRecorder.release();
            noteRecorder = null;
        }
        if (discard && noteFile != null) noteFile.delete();
        return ok;
    }

    private static String[] concat(String[] first, String[] second) {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(java.util.Arrays.asList(first));
        all.addAll(java.util.Arrays.asList(second));
        return all.toArray(new String[0]);
    }

    private String currentRoomId() {
        RoomDiscovery directory = RoomDiscovery.current();
        if (directory == null) return "";
        try {
            return directory.stateJson().optString("joinedId", "");
        } catch (Exception e) {
            return "";
        }
    }

    private String getRoomUrl() {
        return getSharedPreferences(LanService.PREFS_NAME, MODE_PRIVATE)
                .getString(LanService.KEY_ROOM_URL, "");
    }

    /** Prefers the secure room (needed for the microphone and camera), falling back to plain HTTP. */
    private String pageUrl(String roomUrl) {
        if (roomUrl == null || roomUrl.isEmpty()) return "";
        if (secureFailures < SECURE_ATTEMPTS) return secureUrl(roomUrl);
        return roomUrl.endsWith("/") ? roomUrl : roomUrl + "/";
    }

    private static String secureUrl(String roomUrl) {
        Uri room = Uri.parse(roomUrl);
        return "https://" + room.getHost() + ":" + LanServer.SECURE_PORT + "/";
    }

    private void handleMediaPermission(PermissionRequest request) {
        List<String> needed = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) needed.add(Manifest.permission.RECORD_AUDIO);
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) needed.add(Manifest.permission.CAMERA);
        }
        List<String> missing = new ArrayList<>();
        for (String permission : needed) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        }
        if (missing.isEmpty()) {
            request.grant(request.getResources());
            return;
        }
        if (pendingPermission != null) pendingPermission.deny();
        pendingPermission = request;
        requestPermissions(missing.toArray(new String[0]), MEDIA_PERMISSION_REQUEST);
    }

    /** Lets the pages pick rooms, route call audio, keep the screen awake during calls, and save downloads. */
    private final class CallBridge {
        @JavascriptInterface
        public String rooms() {
            RoomDiscovery directory = RoomDiscovery.current();
            return directory == null ? "[]" : directory.roomsJson(true).toString();
        }

        @JavascriptInterface
        public String lobbyState() {
            RoomDiscovery directory = RoomDiscovery.current();
            return directory == null ? "{}" : directory.stateJson().toString();
        }

        @JavascriptInterface
        public void joinRoom(String id) {
            runOnUiThread(() -> {
                RoomDiscovery directory = RoomDiscovery.current();
                if (directory == null || id == null) return;
                if (id.equals(currentRoomId()) && !getRoomUrl().isEmpty()) {
                    loadedRoomUrl = null;
                    return;
                }
                directory.join(id);
            });
        }

        @JavascriptInterface
        public void createRoom(String name) {
            createRoom(name, false);
        }

        @JavascriptInterface
        public void createRoom(String name, boolean useHotspot) {
            if (!onLobby) return;
            runOnUiThread(() -> {
                Runnable create = () -> {
                    RoomDiscovery directory = RoomDiscovery.current();
                    if (directory != null) directory.createRoom(name, useHotspot);
                    loadedRoomUrl = null;
                };
                if (useHotspot) {
                    String[] wanted = concat(MeshPermissions.hotspot(), MeshPermissions.bluetooth());
                    withPermissions(wanted, "Hotspot rooms need the Nearby devices / Location permission.", create);
                } else {
                    create.run();
                }
            });
        }

        @JavascriptInterface
        public String takeError() {
            RoomDiscovery directory = RoomDiscovery.current();
            return directory == null ? "" : directory.takeError();
        }

        @JavascriptInterface
        public void openMesh() {
            runOnUiThread(MainActivity.this::showMesh);
        }

        /* ---------- Bluetooth mesh (only the app's own pages may use these) ---------- */

        @JavascriptInterface
        public String meshState() {
            BleMesh mesh = BleMesh.current();
            if (!onLobby || mesh == null) return "{}";
            org.json.JSONObject state = mesh.state();
            try {
                MeshCall call = LanService.meshCall();
                if (call != null) state.put("call", call.snapshot());
                state.put("locationOff", MeshPermissions.locationNeededAndOff(MainActivity.this));
            } catch (org.json.JSONException ignored) {
            }
            return state.toString();
        }

        @JavascriptInterface
        public String meshMessages() {
            BleMesh mesh = BleMesh.current();
            return !onLobby || mesh == null ? "[]" : mesh.messages().toString();
        }

        @JavascriptInterface
        public String meshSend(String json) {
            BleMesh mesh = BleMesh.current();
            if (!onLobby || mesh == null) return "{\"error\":\"Bluetooth is not ready\"}";
            try {
                return mesh.send(new org.json.JSONObject(json)).toString();
            } catch (org.json.JSONException e) {
                return "{\"error\":\"Invalid message\"}";
            }
        }

        @JavascriptInterface
        public void meshEnable() {
            if (!onLobby) return;
            runOnUiThread(() -> withPermissions(MeshPermissions.bluetooth(),
                    "Bluetooth chat needs the Nearby devices permission.", () -> {
                        BleMesh mesh = BleMesh.current();
                        if (mesh != null) mesh.restart();
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            android.bluetooth.BluetoothManager manager = (android.bluetooth.BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
                            if (manager != null && manager.getAdapter() != null && !manager.getAdapter().isEnabled()) {
                                try {
                                    startActivity(new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE));
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }));
        }

        @JavascriptInterface
        public void openLocationSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public String meshCall(String peerId, String name) {
            MeshCall call = LanService.meshCall();
            if (!onLobby || call == null) return "Bluetooth is not ready.";
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> withPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                        "Calls need microphone access.", () -> call.call(peerId, name)));
                return "";
            }
            return call.call(peerId, name);
        }

        @JavascriptInterface
        public void meshAnswer(boolean accept) {
            MeshCall call = LanService.meshCall();
            if (!onLobby || call == null) return;
            if (accept && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> withPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                        "Calls need microphone access.", () -> call.answer(true)));
                return;
            }
            call.answer(accept);
        }

        @JavascriptInterface
        public void meshHangup() {
            MeshCall call = LanService.meshCall();
            if (onLobby && call != null) call.hangup();
        }

        @JavascriptInterface
        public void meshMute(boolean muted) {
            MeshCall call = LanService.meshCall();
            if (onLobby && call != null) call.setMuted(muted);
        }

        @JavascriptInterface
        public void meshSpeaker(boolean on) {
            MeshCall call = LanService.meshCall();
            if (onLobby && call != null) call.setSpeaker(on);
        }

        /** Saves a file received over Bluetooth into the phone's Downloads folder. */
        @JavascriptInterface
        public String meshSaveFile(String name, String type, String data) {
            if (!onLobby) return "Not available here.";
            try {
                byte[] bytes = android.util.Base64.decode(data, android.util.Base64.NO_WRAP);
                String safe = (name == null || name.trim().isEmpty() ? "hearth-file" : name).replaceAll("[\\\\/:*?\"<>|]", "_");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safe);
                    values.put(android.provider.MediaStore.Downloads.MIME_TYPE, type == null || type.isEmpty() ? "application/octet-stream" : type);
                    values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) return "Could not save the file.";
                    try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) return "Could not save the file.";
                        out.write(bytes);
                    }
                } else {
                    if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        runOnUiThread(() -> withPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                "Saving files needs storage permission.", () -> { }));
                        return "Allow storage access, then tap Save again.";
                    }
                    java.io.File folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    folder.mkdirs();
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(folder, safe))) {
                        out.write(bytes);
                    }
                }
                return "";
            } catch (Exception e) {
                return "Could not save the file.";
            }
        }

        /** Voice notes for the Bluetooth chat: small AAC recordings (about 3 KB per second). */
        @JavascriptInterface
        public String noteStart() {
            if (!onLobby) return "Not available here.";
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> withPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                        "Voice messages need microphone access.", () -> { }));
                return "permission";
            }
            synchronized (MainActivity.this) {
                stopNote(true);
                try {
                    noteFile = new java.io.File(getCacheDir(), "voice-note.m4a");
                    noteRecorder = new android.media.MediaRecorder();
                    noteRecorder.setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION);
                    noteRecorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
                    noteRecorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
                    noteRecorder.setAudioChannels(1);
                    noteRecorder.setAudioSamplingRate(16000);
                    noteRecorder.setAudioEncodingBitRate(24000);
                    noteRecorder.setMaxDuration(60_000);
                    noteRecorder.setOutputFile(noteFile.getAbsolutePath());
                    noteRecorder.prepare();
                    noteRecorder.start();
                    noteStartedAt = System.currentTimeMillis();
                    return "";
                } catch (Exception e) {
                    stopNote(true);
                    return "Could not start recording.";
                }
            }
        }

        @JavascriptInterface
        public String noteStop(boolean discard) {
            synchronized (MainActivity.this) {
                long duration = (System.currentTimeMillis() - noteStartedAt) / 1000;
                boolean ok = stopNote(false);
                org.json.JSONObject result = new org.json.JSONObject();
                try {
                    if (discard || !ok || noteFile == null || !noteFile.isFile()) {
                        result.put("error", discard ? "" : "Recording failed.");
                        return result.toString();
                    }
                    byte[] data = java.nio.file.Files.readAllBytes(noteFile.toPath());
                    noteFile.delete();
                    result.put("data", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP));
                    result.put("type", "audio/mp4");
                    result.put("duration", Math.max(1, Math.min(60, duration)));
                } catch (Exception e) {
                    try {
                        result.put("error", "Recording failed.");
                    } catch (org.json.JSONException ignored) {
                    }
                }
                return result.toString();
            }
        }


        @JavascriptInterface
        public void stopHosting() {
            if (!onLobby) return;
            runOnUiThread(() -> {
                RoomDiscovery directory = RoomDiscovery.current();
                if (directory != null) directory.stopHosting();
            });
        }

        @JavascriptInterface
        public void leaveRoom() {
            runOnUiThread(() -> {
                RoomDiscovery directory = RoomDiscovery.current();
                if (directory != null) directory.leave();
            });
        }

        @JavascriptInterface
        public void cancelAutoJoin() {
            runOnUiThread(() -> {
                RoomDiscovery directory = RoomDiscovery.current();
                if (directory != null) directory.cancelAutoJoin();
            });
        }

        @JavascriptInterface
        public void openRooms() {
            runOnUiThread(MainActivity.this::showLobby);
        }

        @JavascriptInterface
        public void backToRoom() {
            runOnUiThread(() -> loadedRoomUrl = null);
        }

        @JavascriptInterface
        public String getName() {
            String name = getSharedPreferences(LanService.PREFS_NAME, MODE_PRIVATE).getString(LanService.KEY_USER_NAME, "");
            return name.isEmpty() ? RoomDiscovery.deviceName(MainActivity.this) : name;
        }

        @JavascriptInterface
        public void setName(String name) {
            String cleaned = name == null ? "" : name.trim();
            if (cleaned.length() > 48) cleaned = cleaned.substring(0, 48);
            getSharedPreferences(LanService.PREFS_NAME, MODE_PRIVATE).edit().putString(LanService.KEY_USER_NAME, cleaned).apply();
        }

        /** The page hands downloads here directly; WebView does not reliably start downloads from the secure room. */
        @JavascriptInterface
        public void download(String url, String fileName, String mimeType) {
            runOnUiThread(() -> {
                Uri target = Uri.parse(url == null ? "" : url);
                Uri room = Uri.parse(getRoomUrl());
                if (target.getHost() == null || !target.getHost().equals(room.getHost())) {
                    Toast.makeText(MainActivity.this, "Reconnect to the room to download this file.", Toast.LENGTH_LONG).show();
                    return;
                }
                beginDownload(new PendingDownload(url, null, null, mimeType, fileName));
            });
        }

        @JavascriptInterface
        public void setInCall(boolean active, boolean speaker) {
            runOnUiThread(() -> {
                AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (audio != null) {
                    audio.setMode(active ? AudioManager.MODE_IN_COMMUNICATION : AudioManager.MODE_NORMAL);
                    audio.setSpeakerphoneOn(active && speaker);
                }
                if (active) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void setSpeaker(boolean speaker) {
            runOnUiThread(() -> {
                AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (audio != null) audio.setSpeakerphoneOn(speaker);
            });
        }

        @JavascriptInterface
        public void ring(boolean on) {
            runOnUiThread(() -> {
                Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator == null) return;
                if (on) vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 600, 900}, 0));
                else vibrator.cancel();
            });
        }
    }

    private void beginDownload(PendingDownload download) {
        if (Build.VERSION.SDK_INT < 29 &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = download;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, DOWNLOAD_PERMISSION_REQUEST);
            return;
        }
        enqueueDownload(download);
    }

    /**
     * Android's download manager does not trust the room phone's self-signed certificate, so files from the
     * secure room address are fetched from the same phone's plain address instead.
     */
    private static Uri downloadUri(String url) {
        Uri uri = Uri.parse(url);
        if ("https".equals(uri.getScheme()) && uri.getPort() == LanServer.SECURE_PORT) {
            return uri.buildUpon().scheme("http").encodedAuthority(uri.getHost() + ":" + LanServer.PORT).build();
        }
        return uri;
    }

    private void enqueueDownload(PendingDownload download) {
        if (download.url == null || !download.url.startsWith("http")) {
            Toast.makeText(this, "Reconnect to the room to download this file.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            String filename = download.fileName != null && !download.fileName.trim().isEmpty()
                    ? download.fileName
                    : URLUtil.guessFileName(download.url, download.contentDisposition, download.mimeType);
            filename = filename.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (filename.isEmpty()) filename = "hearth-shared-file";
            if (filename.length() > 120) filename = filename.substring(0, 120);

            DownloadManager.Request request = new DownloadManager.Request(downloadUri(download.url));
            request.setTitle(filename);
            request.setDescription("Shared from Hearth Chat");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverRoaming(false);
            if (download.mimeType != null && !download.mimeType.isEmpty()) request.setMimeType(download.mimeType);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("Download manager unavailable");
            manager.enqueue(request);
            Toast.makeText(this, "Downloading to Downloads", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not start the download.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                ClipData clips = data.getClipData();
                if (clips != null && clips.getItemCount() > 0) {
                    result = new Uri[clips.getItemCount()];
                    for (int i = 0; i < clips.getItemCount(); i++) result[i] = clips.getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            if (uploadCallback != null) uploadCallback.onReceiveValue(result);
            uploadCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == FEATURE_PERMISSION_REQUEST) {
            Runnable action = afterPermission;
            afterPermission = null;
            boolean allGranted = grantResults.length > 0;
            for (int result : grantResults) allGranted &= result == PackageManager.PERMISSION_GRANTED;
            if (allGranted && action != null) action.run();
            else if (!allGranted) Toast.makeText(this, permissionDeniedMessage, Toast.LENGTH_LONG).show();
            return;
        }
        if (requestCode == MEDIA_PERMISSION_REQUEST && pendingPermission != null) {
            PermissionRequest request = pendingPermission;
            pendingPermission = null;
            boolean allGranted = grantResults.length > 0;
            for (int result : grantResults) allGranted &= result == PackageManager.PERMISSION_GRANTED;
            if (allGranted) {
                request.grant(request.getResources());
            } else {
                request.deny();
                Toast.makeText(this, "Allow microphone and camera access for voice messages and calls.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode == DOWNLOAD_PERMISSION_REQUEST && pendingDownload != null) {
            PendingDownload download = pendingDownload;
            pendingDownload = null;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enqueueDownload(download);
            } else {
                Toast.makeText(this, "Storage permission is needed to save downloads.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(roomUrlWatcher);
        new CallBridge().setInCall(false, false);
        if (uploadCallback != null) uploadCallback.onReceiveValue(null);
        uploadCallback = null;
        super.onDestroy();
    }

    private void startLanService() {
        Intent intent = new Intent(this, LanService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.getUrl() != null && webView.getUrl().startsWith(MESH_URL)) {
            // Back from the Bluetooth chat: let it close its own screens first, then return to the rooms list.
            webView.evaluateJavascript("window.hearthBack ? String(window.hearthBack()) : 'false'", result -> {
                if (!"\"true\"".equals(result)) showLobbyForced();
            });
        } else if (onLobby && !getRoomUrl().isEmpty()) {
            // Back from the room list returns to the room you are in.
            loadedRoomUrl = null;
        } else if (!onLobby) {
            // The room page closes menus or returns from a chat to the chat list first;
            // otherwise keep the room (and any call) running instead of closing it.
            webView.evaluateJavascript("window.hearthBack ? String(window.hearthBack()) : 'false'", result -> {
                if (!"\"true\"".equals(result)) moveTaskToBack(true);
            });
        } else {
            super.onBackPressed();
        }
    }
}
