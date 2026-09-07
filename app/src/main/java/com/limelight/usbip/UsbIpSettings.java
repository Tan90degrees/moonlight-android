package com.limelight.usbip;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.utils.UiHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class UsbIpSettings extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.limelight.usbip.USB_PERMISSION";

    private UsbManager usbManager;
    private LinearLayout deviceContainer;
    private TextView serverStatus;
    private Switch serverSwitch;
    private boolean updatingSwitch;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbDevice device = getUsbDevice(intent);

            if (ACTION_USB_PERMISSION.equals(action)) {
                if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    UsbIpPreferences.setSelected(UsbIpSettings.this, device, true);
                }
                else {
                    Toast.makeText(UsbIpSettings.this,
                            R.string.usbip_permission_denied, Toast.LENGTH_SHORT).show();
                }
                refreshDeviceList();
            }
            else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                refreshDeviceList();
            }
            else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (device != null) {
                    UsbIpPreferences.forgetDevice(UsbIpSettings.this, device);
                }
                refreshDeviceList();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiHelper.setLocale(this);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        setTitle(R.string.usbip_settings_title);
        setContentView(buildContentView());
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerUsbReceiver();
        refreshDeviceList();
        refreshServerState();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(usbReceiver);
        super.onStop();
    }

    private View buildContentView() {
        int padding = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);

        serverSwitch = new Switch(this);
        serverSwitch.setText(R.string.usbip_server_enable);
        serverSwitch.setTextSize(18);
        serverSwitch.setOnCheckedChangeListener(this::onServerSwitchChanged);
        root.addView(serverSwitch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        serverStatus = new TextView(this);
        serverStatus.setPadding(0, dp(4), 0, dp(12));
        root.addView(serverStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView warning = new TextView(this);
        warning.setText(R.string.usbip_security_note);
        warning.setPadding(0, 0, 0, dp(12));
        root.addView(warning, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button refresh = new Button(this);
        refresh.setText(R.string.usbip_refresh_devices);
        refresh.setOnClickListener(v -> refreshDeviceList());
        root.addView(refresh, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView heading = new TextView(this);
        heading.setText(R.string.usbip_settings_title);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setTextSize(16);
        heading.setPadding(0, dp(16), 0, dp(8));
        root.addView(heading);

        deviceContainer = new LinearLayout(this);
        deviceContainer.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(deviceContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    private void onServerSwitchChanged(CompoundButton button, boolean checked) {
        if (updatingSwitch) {
            return;
        }

        if (checked) {
            UsbIpServerService.start(this);
        }
        else {
            UsbIpServerService.stop(this);
        }
        UsbIpPreferences.setEnabled(this, checked);
        refreshServerState();
    }

    private void refreshServerState() {
        boolean enabled = UsbIpPreferences.isEnabled(this);
        updatingSwitch = true;
        serverSwitch.setChecked(enabled);
        updatingSwitch = false;

        if (enabled) {
            serverStatus.setText(getString(R.string.usbip_server_running,
                    UsbIpPreferences.getPort(this)));
        }
        else {
            serverStatus.setText(R.string.usbip_server_stopped);
        }
    }

    private void refreshDeviceList() {
        deviceContainer.removeAllViews();

        List<UsbDevice> devices = new ArrayList<>(usbManager.getDeviceList().values());
        devices.sort(Comparator.comparing(UsbDevice::getDeviceName));

        if (devices.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.usbip_no_devices);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.setPadding(0, dp(24), 0, 0);
            deviceContainer.addView(empty);
            return;
        }

        for (UsbDevice device : devices) {
            deviceContainer.addView(buildDeviceRow(device));
        }
    }

    private View buildDeviceRow(UsbDevice device) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(buildDeviceTitle(device));
        checkBox.setTag(device);
        checkBox.setChecked(UsbIpPreferences.isSelected(this, device) && usbManager.hasPermission(device));
        checkBox.setOnCheckedChangeListener((button, checked) -> {
            UsbDevice selected = (UsbDevice) button.getTag();
            if (checked) {
                if (usbManager.hasPermission(selected)) {
                    UsbIpPreferences.setSelected(this, selected, true);
                }
                else {
                    button.setChecked(false);
                    requestUsbPermission(selected);
                }
            }
            else {
                UsbIpPreferences.setSelected(this, selected, false);
            }
        });
        row.addView(checkBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView summary = new TextView(this);
        summary.setPadding(dp(48), 0, 0, 0);
        boolean selected = UsbIpPreferences.isSelected(this, device) && usbManager.hasPermission(device);
        summary.setText(selected ? R.string.usbip_device_shared : R.string.usbip_device_needs_permission);
        row.addView(summary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return row;
    }

    private String buildDeviceTitle(UsbDevice device) {
        String name = device.getProductName();
        if (name == null || name.trim().isEmpty()) {
            name = "USB device";
        }
        return String.format(Locale.US, "%s  [%04x:%04x]  %s",
                name, device.getVendorId(), device.getProductId(), device.getDeviceName());
    }

    private void requestUsbPermission(UsbDevice device) {
        int flags = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }

        Intent intent = new Intent(ACTION_USB_PERMISSION);
        intent.setPackage(getPackageName());
        PendingIntent permissionIntent = PendingIntent.getBroadcast(
                this, device.getDeviceId(), intent, flags);
        try {
            usbManager.requestPermission(device, permissionIntent);
        }
        catch (SecurityException e) {
            Toast.makeText(this, R.string.error_usb_prohibited, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED);
        }
        else {
            registerReceiver(usbReceiver, filter);
        }
    }

    @SuppressWarnings("deprecation")
    private static UsbDevice getUsbDevice(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
