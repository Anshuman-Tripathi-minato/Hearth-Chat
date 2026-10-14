package home.hearth.chat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/** Runtime permissions needed for the Bluetooth mesh and for hotspot rooms, per Android version. */
final class MeshPermissions {
    private MeshPermissions() {
    }

    static String[] bluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT};
        }
        // Android 11 and older only allow Bluetooth scanning with location permission.
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    static String[] hotspot() {
        if (Build.VERSION.SDK_INT >= 33) return new String[]{Manifest.permission.NEARBY_WIFI_DEVICES};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    static boolean granted(Context context) {
        return missing(context, bluetooth()).length == 0;
    }

    static String[] missing(Context context, String[] wanted) {
        List<String> out = new ArrayList<>();
        for (String permission : wanted) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) out.add(permission);
        }
        return out.toArray(new String[0]);
    }

    /** Android 11 and older also need location services switched on to find Bluetooth devices or start a hotspot. */
    static boolean locationNeededAndOff(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false;
        LocationManager location = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (location == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return !location.isLocationEnabled();
        return !location.isProviderEnabled(LocationManager.GPS_PROVIDER) && !location.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }
}
