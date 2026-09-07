package com.limelight.usbip;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class UsbIpDevice {
    private static final int PATH_SIZE = 256;
    private static final int BUS_ID_SIZE = 32;

    final UsbDevice device;
    final String busId;

    UsbIpDevice(UsbDevice device) {
        this.device = device;
        this.busId = String.format(Locale.US, "android-%d", device.getDeviceId());
    }

    void writeDevice(DataOutputStream out) throws IOException {
        writeFixedString(out, device.getDeviceName(), PATH_SIZE);
        writeFixedString(out, busId, BUS_ID_SIZE);

        int[] busDev = parseBusAndDeviceNumber(device.getDeviceName());
        out.writeInt(busDev[0]);
        out.writeInt(busDev[1]);
        out.writeInt(guessSpeed(device));

        out.writeShort(device.getVendorId() & 0xffff);
        out.writeShort(device.getProductId() & 0xffff);
        // Android's public UsbDevice API doesn't expose bcdDevice. 0x0100 is a
        // safe placeholder and is not used to select the host-side USB driver.
        out.writeShort(0x0100);

        out.writeByte(device.getDeviceClass() & 0xff);
        out.writeByte(device.getDeviceSubclass() & 0xff);
        out.writeByte(device.getDeviceProtocol() & 0xff);
        out.writeByte(1); // bConfigurationValue; Android exposes active config indirectly
        out.writeByte(Math.max(1, device.getConfigurationCount()) & 0xff);
        out.writeByte(device.getInterfaceCount() & 0xff);
    }

    void writeInterfaces(DataOutputStream out) throws IOException {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            out.writeByte(intf.getInterfaceClass() & 0xff);
            out.writeByte(intf.getInterfaceSubclass() & 0xff);
            out.writeByte(intf.getInterfaceProtocol() & 0xff);
            out.writeByte(0);
        }
    }

    private static int[] parseBusAndDeviceNumber(String name) {
        String[] parts = name.split("/");
        try {
            if (parts.length >= 2) {
                return new int[] {
                        Integer.parseInt(parts[parts.length - 2]),
                        Integer.parseInt(parts[parts.length - 1])
                };
            }
        }
        catch (NumberFormatException ignored) {
        }
        return new int[] {1, 1};
    }

    private static int guessSpeed(UsbDevice device) {
        // There is no public negotiated-speed API on the minSdk used by Moonlight.
        // High speed is the least surprising default for classic USB 2.x devices;
        // this value is advisory metadata for vhci_hcd and does not change the
        // actual transfer path on Android.
        return UsbIpConstants.USB_SPEED_HIGH;
    }

    private static void writeFixedString(DataOutputStream out, String value, int size) throws IOException {
        byte[] encoded = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        int write = Math.min(encoded.length, size - 1);
        out.write(encoded, 0, write);
        for (int i = write; i < size; i++) {
            out.writeByte(0);
        }
    }
}
