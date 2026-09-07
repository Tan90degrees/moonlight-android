package com.limelight.usbip;

import android.content.Context;
import android.content.Intent;
import android.preference.Preference;
import android.util.AttributeSet;

/** Preference entry that keeps StreamSettings decoupled from the USB/IP feature. */
public final class UsbIpSettingsPreference extends Preference {
    public UsbIpSettingsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public UsbIpSettingsPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onClick() {
        super.onClick();
        getContext().startActivity(new Intent(getContext(), UsbIpSettings.class));
    }
}
