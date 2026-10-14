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
        webView.setBackgroundColor(0xFFF4F7F4);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                onLobby = url != null && url.startsWith("file:///android_asset/");
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
        setContentView(webView);
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
        }
    }

    private void joinWhenReady(String id, int attempt) {
        RoomDiscovery directory = RoomDiscovery.current();
        if (directory != null) {
            directory.join(id);
        } else if (attempt < 20) {
            handler.postDelayed(() -> joinWhenReady(id, attempt + 1), 250);
        }
    }

    private void showLobby() {
        if (webView != null && !onLobby) {
            onLobby = true;
            webView.loadUrl(LOBBY_URL);
        }
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
            if (!onLobby) return;
            runOnUiThread(() -> {
                RoomDiscovery directory = RoomDiscovery.current();
                if (directory != null) directory.createRoom(name);
                loadedRoomUrl = null;
            });
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
            return getSharedPreferences(LanService.PREFS_NAME, MODE_PRIVATE).getString(LanService.KEY_USER_NAME, "");
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
        if (onLobby && !getRoomUrl().isEmpty()) {
            // Back from the room list returns to the room you are in.
            loadedRoomUrl = null;
        } else if (!onLobby) {
            // Keep the room (and any call) running instead of closing it.
            moveTaskToBack(true);
        } else {
            super.onBackPressed();
        }
    }
}
