package com.limelight.usbip;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;

import java.util.HashSet;
import java.util.Set;

/**
 * Small preference store dedicated to the userspace USB/IP server.
 *
 * USB permissions are owned by the application UID and remain valid only while
 * the Android USB permission is valid. We persist the device node name only so
 * the UI can remember the user's choice while that device stays attached.
 */
public final class UsbIpPreferences {
    private static final String PREFS_NAME = "usbip_server";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PORT = "port";
    private static final String KEY_SELECTED_DEVICES = "selected_devices";

    public static final int DEFAULT_PORT = 3240;

    private UsbIpPreferences() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static int getPort(Context context) {
        return prefs(context).getInt(KEY_PORT, DEFAULT_PORT);
    }

    public static Set<String> getSelectedDeviceNames(Context context) {
        Set<String> stored = prefs(context).getStringSet(KEY_SELECTED_DEVICES, null);
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }

    public static boolean isSelected(Context context, UsbDevice device) {
        return getSelectedDeviceNames(context).contains(device.getDeviceName());
    }

    public static void setSelected(Context context, UsbDevice device, boolean selected) {
        Set<String> devices = getSelectedDeviceNames(context);
        if (selected) {
            devices.add(device.getDeviceName());
        }
        else {
            devices.remove(device.getDeviceName());
        }
        prefs(context).edit().putStringSet(KEY_SELECTED_DEVICES, devices).apply();
    }

    public static void forgetDevice(Context context, UsbDevice device) {
        setSelected(context, device, false);
    }
}
