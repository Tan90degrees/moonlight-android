package com.limelight.usbip;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.util.concurrent.atomic.AtomicBoolean;

final class UsbIpTransferEngine implements AutoCloseable {
    static final class Result {
        final int status;
        final byte[] data;
        final int actualLength;

        Result(int status, byte[] data, int actualLength) {
            this.status = status;
            this.data = data;
            this.actualLength = actualLength;
        }
    }

    private final UsbDevice device;
    private final UsbDeviceConnection connection;
    private final AtomicBoolean closed = new AtomicBoolean();

    UsbIpTransferEngine(UsbManager manager, UsbDevice device) {
        this.device = device;
        this.connection = manager.openDevice(device);
        if (connection == null) {
            throw new IllegalStateException("Unable to open USB device " + device.getDeviceName());
        }

        // The device is explicitly selected for USB/IP, so force-claim all interfaces.
        // Android will detach a kernel driver where the platform supports doing so.
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            connection.claimInterface(device.getInterface(i), true);
        }
    }

    Result submit(int direction, int endpointNumber, int transferFlags,
                  int requestedLength, byte[] setup, byte[] outData, int timeoutMs) {
        if (closed.get()) {
            return new Result(UsbIpConstants.ERR_ENODEV, new byte[0], 0);
        }

        try {
            if (endpointNumber == 0) {
                return controlTransfer(direction, requestedLength, setup, outData, timeoutMs);
            }

            UsbEndpoint endpoint = findEndpoint(endpointNumber, direction);
            if (endpoint == null) {
                return new Result(UsbIpConstants.ERR_EINVAL, new byte[0], 0);
            }

            if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
                return new Result(UsbIpConstants.ERR_ENOSYS, new byte[0], 0);
            }

            if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_INT) {
                return new Result(UsbIpConstants.ERR_EINVAL, new byte[0], 0);
            }

            int length = Math.max(0, requestedLength);
            byte[] buffer;
            if (direction == UsbIpConstants.USBIP_DIR_IN) {
                buffer = new byte[length];
            }
            else {
                buffer = outData == null ? new byte[0] : outData;
                length = Math.min(length, buffer.length);
            }

            // UsbDeviceConnection.bulkTransfer() uses USBDEVFS_BULK internally and
            // works with bulk endpoints on all supported Android versions. Several
            // Android USB host implementations also accept interrupt endpoints here;
            // this is our compatibility-first MVP path. A future JNI backend can use
            // USBDEVFS_SUBMITURB for fully asynchronous interrupt/isochronous I/O.
            int rc = connection.bulkTransfer(endpoint, buffer, length, timeoutMs);
            if (rc < 0) {
                return new Result(UsbIpConstants.ERR_EPIPE, new byte[0], 0);
            }

            if (direction == UsbIpConstants.USBIP_DIR_IN) {
                byte[] data = new byte[rc];
                System.arraycopy(buffer, 0, data, 0, rc);
                return new Result(0, data, rc);
            }
            return new Result(0, new byte[0], rc);
        }
        catch (RuntimeException e) {
            return new Result(UsbIpConstants.ERR_EPIPE, new byte[0], 0);
        }
    }

    private Result controlTransfer(int direction, int requestedLength, byte[] setup,
                                   byte[] outData, int timeoutMs) {
        if (setup == null || setup.length != 8) {
            return new Result(UsbIpConstants.ERR_EINVAL, new byte[0], 0);
        }

        int requestType = setup[0] & 0xff;
        int request = setup[1] & 0xff;
        int value = littleEndian16(setup, 2);
        int index = littleEndian16(setup, 4);
        int setupLength = littleEndian16(setup, 6);
        int length = Math.min(Math.max(0, requestedLength), setupLength);

        byte[] buffer;
        if (direction == UsbIpConstants.USBIP_DIR_IN) {
            buffer = new byte[length];
        }
        else {
            buffer = outData == null ? new byte[0] : outData;
            length = Math.min(length, buffer.length);
        }

        int rc = connection.controlTransfer(requestType, request, value, index,
                buffer, length, timeoutMs);
        if (rc < 0) {
            return new Result(UsbIpConstants.ERR_EPIPE, new byte[0], 0);
        }

        if (direction == UsbIpConstants.USBIP_DIR_IN) {
            byte[] data = new byte[rc];
            System.arraycopy(buffer, 0, data, 0, rc);
            return new Result(0, data, rc);
        }
        return new Result(0, new byte[0], rc);
    }

    private UsbEndpoint findEndpoint(int endpointNumber, int direction) {
        int wantedDirection = direction == UsbIpConstants.USBIP_DIR_IN
                ? UsbConstants.USB_DIR_IN : UsbConstants.USB_DIR_OUT;

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint endpoint = intf.getEndpoint(e);
                if (endpoint.getEndpointNumber() == endpointNumber &&
                        endpoint.getDirection() == wantedDirection) {
                    return endpoint;
                }
            }
        }
        return null;
    }

    private static int littleEndian16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            try {
                connection.releaseInterface(device.getInterface(i));
            }
            catch (RuntimeException ignored) {
            }
        }
        connection.close();
    }
}
