package com.limelight.usbip;

import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
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
        // harmless placeholder; host-side driver matching uses VID/PID/class.
        out.writeShort(0x0100);

        out.writeByte(device.getDeviceClass() & 0xff);
        out.writeByte(device.getDeviceSubclass() & 0xff);
        out.writeByte(device.getDeviceProtocol() & 0xff);

        int configurationValue = 1;
        if (device.getConfigurationCount() > 0) {
            UsbConfiguration configuration = device.getConfiguration(0);
            configurationValue = configuration.getId();
        }
        out.writeByte(configurationValue & 0xff);
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
        // Android doesn't expose negotiated USB speed through the public API.
        // Endpoint max-packet size gives us a useful lower bound without root/JNI:
        // >512 strongly implies SuperSpeed, >64 implies HighSpeed. Devices whose
        // endpoints fit in 64 bytes are conservatively advertised as FullSpeed.
        int maxPacketSize = 0;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint endpoint = intf.getEndpoint(e);
                maxPacketSize = Math.max(maxPacketSize, endpoint.getMaxPacketSize());
            }
        }

        if (maxPacketSize > 512) {
            return UsbIpConstants.USB_SPEED_SUPER;
        }
        if (maxPacketSize > 64) {
            return UsbIpConstants.USB_SPEED_HIGH;
        }
        return UsbIpConstants.USB_SPEED_FULL;
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
