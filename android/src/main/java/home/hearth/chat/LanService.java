package home.hearth.chat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

public final class LanService extends Service {
    static final String PREFS_NAME = "hearth-room";
    static final String KEY_ROOM_URL = "room_url";
    static final String KEY_NODE_ID = "node_id";
    static final String KEY_IS_HOST = "is_host";

    private static final String CHANNEL_ID = "hearth_local_room";
    private static final int NOTIFICATION_ID = 8787;
    private RoomDiscovery discovery;
    private String status = "Finding a Hearth Chat room on Wi-Fi…";

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        startForeground(NOTIFICATION_ID, makeNotification());
        discovery = new RoomDiscovery(getApplicationContext(), (url, isHost, nextStatus) -> {
            status = nextStatus;
            updateNotification();
        });
        discovery.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (discovery != null) discovery.stop();
        super.onDestroy();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Home chat room", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps your home Wi-Fi chat room available to your devices.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, makeNotification());
    }

    private Notification makeNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String url = preferences.getString(KEY_ROOM_URL, "");
        String line = url.isEmpty() ? status : status + " · " + url;
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_home)
                .setContentTitle("Hearth Chat")
                .setContentText(line)
                .setStyle(new Notification.BigTextStyle().bigText(line))
                .setContentIntent(pending)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }
}
