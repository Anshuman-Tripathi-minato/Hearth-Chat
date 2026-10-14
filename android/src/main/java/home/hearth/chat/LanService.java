package home.hearth.chat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public final class LanService extends Service {
    static final String PREFS_NAME = "hearth-room";
    static final String KEY_ROOM_URL = "room_url";
    static final String KEY_NODE_ID = "node_id";
    static final String KEY_IS_HOST = "is_host";
    static final String KEY_LAST_ROOM_ID = "last_room_id";
    static final String KEY_HOSTING = "hosting";
    static final String KEY_ROOM_NAME = "room_name";
    static final String KEY_USER_NAME = "user_name";
    static final String EXTRA_JOIN_ROOM = "join_room_id";
    static final String EXTRA_SHOW_ROOMS = "show_rooms";

    private static final String CHANNEL_ID = "hearth_local_room";
    private static final String ROOMS_CHANNEL_ID = "hearth_new_rooms";
    private static final int NOTIFICATION_ID = 8787;
    private RoomDiscovery discovery;
    private BleMesh mesh;
    private static volatile MeshCall meshCall;
    static final String EXTRA_SHOW_MESH = "show_mesh";
    private static final String MESH_CHANNEL_ID = "hearth_mesh";

    static MeshCall meshCall() {
        return meshCall;
    }
    private String status = "Finding Hearth Chat rooms on Wi-Fi…";

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannels();
        startForeground(NOTIFICATION_ID, makeNotification());
        discovery = new RoomDiscovery(getApplicationContext(), new RoomDiscovery.Listener() {
            @Override public void onRoomChanged(String url, boolean isHost, String nextStatus) {
                status = nextStatus;
                updateNotification();
            }

            @Override public void onNewRoom(RoomDiscovery.Room room) {
                notifyNewRoom(room);
            }
        });
        discovery.start();
        mesh = new BleMesh(getApplicationContext());
        meshCall = new MeshCall(getApplicationContext(), mesh);
        mesh.setBackgroundListener(this::onMeshInBackground);
        meshCall.setBackgroundListener(this::onMeshInBackground);
        mesh.start();
    }

    /** Bluetooth events while the Bluetooth chat screen is closed: notify about private messages and incoming calls. */
    private void onMeshInBackground(org.json.JSONObject event) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        String title = null;
        String text = null;
        int id = 9500;
        if ("call".equals(event.optString("type")) && "incoming".equals(event.optString("state"))) {
            title = "Incoming Bluetooth call";
            text = event.optString("name", "Someone") + " is calling over Bluetooth";
            id = 9501;
        } else if ("call".equals(event.optString("type")) && "ended".equals(event.optString("state"))) {
            manager.cancel(9501);
            return;
        } else if ("message".equals(event.optString("type"))) {
            org.json.JSONObject message = event.optJSONObject("message");
            if (message == null || !message.optBoolean("private")) return;
            title = message.optString("name", "Someone") + " · Bluetooth";
            String kind = message.optString("kind");
            text = "text".equals(kind) ? message.optString("text") : "voice".equals(kind) ? "Voice message" : "Sent a file";
            id = 9502;
        }
        if (title == null) return;
        Intent open = new Intent(this, MainActivity.class).putExtra(EXTRA_SHOW_MESH, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pending = PendingIntent.getActivity(this, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, MESH_CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_home)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(id == 9501 ? Notification.CATEGORY_CALL : Notification.CATEGORY_MESSAGE);
        if (id == 9501) builder.setFullScreenIntent(pending, true);
        manager.notify(id, builder.build());
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
        if (meshCall != null) meshCall.hangup();
        if (mesh != null) mesh.stop();
        super.onDestroy();
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) return;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Home chat room", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps your Wi-Fi chat rooms available to your devices.");
            manager.createNotificationChannel(channel);
            NotificationChannel rooms = new NotificationChannel(
                    ROOMS_CHANNEL_ID, "New rooms nearby", NotificationManager.IMPORTANCE_HIGH);
            rooms.setDescription("Tells you when someone opens a new Hearth room on your Wi-Fi.");
            manager.createNotificationChannel(rooms);
            NotificationChannel meshChannel = new NotificationChannel(
                    MESH_CHANNEL_ID, "Bluetooth messages and calls", NotificationManager.IMPORTANCE_HIGH);
            meshChannel.setDescription("Private messages and calls that arrive over the Bluetooth mesh.");
            manager.createNotificationChannel(meshChannel);
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
        String url = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_ROOM_URL, "");
        boolean host = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_IS_HOST, false);
        String line = host && !url.isEmpty() ? status + " · laptops: " + url : status;
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

    /** A new room appeared on the Wi-Fi: offer to join it, or to see all rooms. Nothing joins automatically. */
    private void notifyNewRoom(RoomDiscovery.Room room) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        int id = 9000 + (room.id.hashCode() & 0xfff);
        Intent show = new Intent(this, MainActivity.class).putExtra(EXTRA_SHOW_ROOMS, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent join = new Intent(this, MainActivity.class).putExtra(EXTRA_JOIN_ROOM, room.id)
                .putExtra("notification_id", id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent showPending = PendingIntent.getActivity(this, id, show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent joinPending = PendingIntent.getActivity(this, id + 1, join,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, ROOMS_CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder.setSmallIcon(R.drawable.ic_home)
                .setContentTitle("New room on your Wi-Fi")
                .setContentText("“" + room.name + "” by " + room.hostName + " · Join or see all rooms")
                .setContentIntent(showPending)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_SOCIAL)
                .addAction(new Notification.Action.Builder(null, "Join", joinPending).build())
                .addAction(new Notification.Action.Builder(null, "See rooms", showPending).build())
                .build();
        manager.notify(id, notification);
    }
}
