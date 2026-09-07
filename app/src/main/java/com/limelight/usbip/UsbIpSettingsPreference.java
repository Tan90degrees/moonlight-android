package com.limelight.usbip;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.preference.Preference;
import android.util.AttributeSet;

/** Preference entry that keeps StreamSettings decoupled from the USB/IP feature. */
public final class UsbIpSettingsPreference extends Preference {
    public UsbIpSettingsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        updateAvailability(context);
    }

    public UsbIpSettingsPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        updateAvailability(context);
    }

    private void updateAvailability(Context context) {
        setEnabled(context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST));
    }

    @Override
    protected void onClick() {
        super.onClick();
        if (isEnabled()) {
            getContext().startActivity(new Intent(getContext(), UsbIpSettings.class));
        }
    }
}
