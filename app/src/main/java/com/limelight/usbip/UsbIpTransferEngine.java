package com.limelight.usbip;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.Build;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

final class UsbIpTransferEngine implements AutoCloseable {
    private static final long REQUEST_WAIT_SLICE_MS = 250;

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
    private final List<UsbInterface> claimedInterfaces = new ArrayList<>();
    private final Set<AsyncTransfer> activeTransfers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean ioFailed = new AtomicBoolean();
    private final Object controlLock = new Object();
    private final Thread completionThread;

    UsbIpTransferEngine(UsbManager manager, UsbDevice device) {
        this.device = device;
        this.connection = manager.openDevice(device);
        if (connection == null) {
            throw new IllegalStateException("Unable to open USB device " + device.getDeviceName());
        }

        try {
            // The device is explicitly selected for USB/IP, so force-claim all interfaces.
            // Fail the import instead of running with a partially claimed composite device.
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface intf = device.getInterface(i);
                if (!connection.claimInterface(intf, true)) {
                    throw new IllegalStateException("Unable to claim USB interface " +
                            intf.getId() + ":" + intf.getAlternateSetting());
                }
                claimedInterfaces.add(intf);
            }
        }
        catch (RuntimeException e) {
            releaseClaimedInterfaces();
            connection.close();
            throw e;
        }

        completionThread = new Thread(this::completionLoop, "UsbIpUsbRequest");
        completionThread.start();
    }

    Result submit(int direction, int endpointNumber, int transferFlags,
                  int requestedLength, byte[] setup, byte[] outData, int timeoutMs) {
        if (closed.get()) {
            return new Result(UsbIpConstants.ERR_ENODEV, new byte[0], 0);
        }
        if (Thread.currentThread().isInterrupted()) {
            return new Result(UsbIpConstants.ERR_ECONNRESET, new byte[0], 0);
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

            // Android only supports interrupt endpoints through asynchronous UsbRequest.
            // Using bulkTransfer() for HID interrupt traffic can produce spurious failures
            // and also gives us no way to cancel the underlying request on USB/IP UNLINK.
            return asyncTransfer(direction, endpoint, requestedLength, outData);
        }
        catch (RuntimeException e) {
            return new Result(UsbIpConstants.ERR_EPIPE, new byte[0], 0);
        }
    }

    private Result asyncTransfer(int direction, UsbEndpoint endpoint,
                                 int requestedLength, byte[] outData) {
        if (ioFailed.get()) {
            return new Result(UsbIpConstants.ERR_EPIPE, new byte[0], 0);
        }

        int length = Math.max(0, requestedLength);
        ByteBuffer buffer;
        if (direction == UsbIpConstants.USBIP_DIR_IN) {
            buffer = ByteBuffer.allocate(length);
        }
        else {
            byte[] payload = outData == null ? new byte[0] : outData;
            length = Math.min(length, payload.length);
            buffer = ByteBuffer.allocate(length);
            if (length > 0) {
                buffer.put(payload, 0, length);
                buffer.flip();
            }
        }

        UsbRequest request = new UsbRequest();
        if (!request.initialize(connection, endpoint)) {
            request.close();
            return new Result(UsbIpConstants.ERR_EPIPE, new byte[0], 0);
        }

        AsyncTransfer transfer = new AsyncTransfer(direction, buffer, request);
        request.setClientData(transfer);
        activeTransfers.add(transfer);

        if (closed.get() || Thread.currentThread().isInterrupted()) {
            activeTransfers.remove(transfer);
            request.close();
            return new Result(closed.get() ? UsbIpConstants.ERR_ENODEV : UsbIpConstants.ERR_ECONNRESET,
                    new byte[0], 0);
        }

        final boolean queued;
        try {
            queued = queueRequest(request, buffer, length);
        }
        catch (RuntimeException e) {
            activeTransfers.remove(transfer);
            request.close();
            return new Result(UsbIpConstants.ERR_EPIPE, new byte[0], 0);
        }

        if (!queued) {
            activeTransfers.remove(transfer);
            request.close();
            return new Result(UsbIpConstants.ERR_EPIPE, new byte[0], 0);
        }
        transfer.queued.set(true);

        // Future.cancel(true), used by USB/IP UNLINK, interrupts this worker. await()
        // turns that Java interruption into UsbRequest.cancel(), so cancellation reaches
        // the actual usbfs request instead of only suppressing RET_SUBMIT.
        return transfer.await();
    }

    @SuppressWarnings("deprecation")
    private static boolean queueRequest(UsbRequest request, ByteBuffer buffer, int length) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return request.queue(buffer);
        }
        return request.queue(buffer, length);
    }

    private void completionLoop() {
        while (!closed.get()) {
            try {
                UsbRequest request;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        request = connection.requestWait(REQUEST_WAIT_SLICE_MS);
                    }
                    catch (TimeoutException e) {
                        continue;
                    }
                }
                else {
                    request = connection.requestWait();
                }

                if (request == null) {
                    if (!closed.get()) {
                        ioFailed.set(true);
                        failAsyncTransfers(UsbIpConstants.ERR_EPIPE);
                    }
                    continue;
                }

                Object clientData = request.getClientData();
                if (!(clientData instanceof AsyncTransfer)) {
                    request.close();
                    continue;
                }

                AsyncTransfer transfer = (AsyncTransfer) clientData;
                activeTransfers.remove(transfer);
                transfer.completeFromRequest();
                request.close();
            }
            catch (RuntimeException e) {
                if (!closed.get()) {
                    ioFailed.set(true);
                    failAsyncTransfers(UsbIpConstants.ERR_EPIPE);
                }
            }
        }
    }

    private void failAsyncTransfers(int status) {
        for (AsyncTransfer transfer : activeTransfers) {
            transfer.cancel(status);
        }
    }

    private Result controlTransfer(int direction, int requestedLength, byte[] setup,
                                   byte[] outData, int timeoutMs) {
        if (setup == null || setup.length != 8) {
            return new Result(UsbIpConstants.ERR_EINVAL, new byte[0], 0);
        }
        if (Thread.currentThread().isInterrupted()) {
            return new Result(UsbIpConstants.ERR_ECONNRESET, new byte[0], 0);
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

        final int rc;
        synchronized (controlLock) {
            if (closed.get() || Thread.currentThread().isInterrupted()) {
                return new Result(closed.get() ? UsbIpConstants.ERR_ENODEV : UsbIpConstants.ERR_ECONNRESET,
                        new byte[0], 0);
            }
            rc = connection.controlTransfer(requestType, request, value, index,
                    buffer, length, timeoutMs);
        }
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

    private void releaseClaimedInterfaces() {
        for (UsbInterface intf : claimedInterfaces) {
            try {
                connection.releaseInterface(intf);
            }
            catch (RuntimeException ignored) {
            }
        }
        claimedInterfaces.clear();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        failAsyncTransfers(UsbIpConstants.ERR_ENODEV);
        completionThread.interrupt();
        releaseClaimedInterfaces();
        connection.close();

        try {
            completionThread.join(1000);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        for (AsyncTransfer transfer : activeTransfers) {
            try {
                transfer.request.close();
            }
            catch (RuntimeException ignored) {
            }
        }
        activeTransfers.clear();
    }

    private static final class AsyncTransfer {
        final int direction;
        final ByteBuffer buffer;
        final UsbRequest request;
        final AtomicBoolean queued = new AtomicBoolean();
        final AtomicBoolean finished = new AtomicBoolean();
        final CountDownLatch done = new CountDownLatch(1);
        volatile Result result;

        AsyncTransfer(int direction, ByteBuffer buffer, UsbRequest request) {
            this.direction = direction;
            this.buffer = buffer;
            this.request = request;
        }

        void cancel(int status) {
            if (queued.get()) {
                try {
                    request.cancel();
                }
                catch (RuntimeException ignored) {
                }
            }
            finish(new Result(status, new byte[0], 0));
        }

        void completeFromRequest() {
            int actualLength = Math.max(0, Math.min(buffer.position(), buffer.capacity()));
            if (direction == UsbIpConstants.USBIP_DIR_IN) {
                byte[] data = new byte[actualLength];
                buffer.flip();
                buffer.get(data, 0, actualLength);
                finish(new Result(0, data, actualLength));
            }
            else {
                finish(new Result(0, new byte[0], actualLength));
            }
        }

        Result await() {
            try {
                done.await();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancel(UsbIpConstants.ERR_ECONNRESET);
            }

            Result r = result;
            return r != null ? r : new Result(UsbIpConstants.ERR_ECONNRESET, new byte[0], 0);
        }

        private void finish(Result result) {
            if (finished.compareAndSet(false, true)) {
                this.result = result;
                done.countDown();
            }
        }
    }
}
