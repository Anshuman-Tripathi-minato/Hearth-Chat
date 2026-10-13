package home.hearth.chat;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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

public final class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 43;
    private static final int DOWNLOAD_PERMISSION_REQUEST = 44;
    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int loadAttempts;
    private String loadedRoomUrl = "";
    private ValueCallback<Uri[]> uploadCallback;
    private PendingDownload pendingDownload;
    private final Runnable roomUrlWatcher = new Runnable() {
        @Override public void run() {
            if (webView == null || isFinishing()) return;
            String roomUrl = getRoomUrl();
            if (!roomUrl.isEmpty() && !roomUrl.equals(loadedRoomUrl)) {
                loadedRoomUrl = roomUrl;
                loadAttempts = 0;
                webView.loadUrl(pageUrl(roomUrl));
            }
            handler.postDelayed(this, 750);
        }
    };

    private static final class PendingDownload {
        final String url;
        final String userAgent;
        final String contentDisposition;
        final String mimeType;

        PendingDownload(String url, String userAgent, String contentDisposition, String mimeType) {
            this.url = url;
            this.userAgent = userAgent;
            this.contentDisposition = contentDisposition;
            this.mimeType = mimeType;
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
            public void onPageFinished(WebView view, String url) {
                loadAttempts = 0;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                String activeUrl = getRoomUrl();
                if (request.isForMainFrame() && !activeUrl.isEmpty()
                        && request.getUrl().toString().startsWith(pageUrl(activeUrl)) && loadAttempts < 20) {
                    loadAttempts++;
                    handler.postDelayed(() -> view.loadUrl(pageUrl(getRoomUrl())), 500);
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
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
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        setContentView(webView);
        handler.post(roomUrlWatcher);
    }

    private String getRoomUrl() {
        return getSharedPreferences(LanService.PREFS_NAME, MODE_PRIVATE)
                .getString(LanService.KEY_ROOM_URL, "");
    }

    private static String pageUrl(String roomUrl) {
        if (roomUrl == null || roomUrl.isEmpty()) return "";
        return roomUrl.endsWith("/") ? roomUrl : roomUrl + "/";
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

    private void enqueueDownload(PendingDownload download) {
        try {
            String filename = URLUtil.guessFileName(download.url, download.contentDisposition, download.mimeType);
            filename = filename.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (filename.isEmpty()) filename = "hearth-shared-file";
            if (filename.length() > 120) filename = filename.substring(0, 120);

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(download.url));
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
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
