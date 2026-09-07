package com.limelight.usbip;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.limelight.LimeLog;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small userspace implementation of the Linux USB/IP 1.1 server protocol.
 *
 * The Android app owns the physical USB device through UsbManager. The remote
 * host still uses the normal Linux vhci_hcd + usbip client.
 */
public final class UsbIpServer implements AutoCloseable {
    private static final int IO_TIMEOUT_MS = 5000;
    private static final int MAX_TRANSFER_SIZE = 16 * 1024 * 1024;

    private final Context context;
    private final UsbManager usbManager;
    private final int port;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService clientExecutor = Executors.newCachedThreadPool();
    private final ExecutorService transferExecutor = Executors.newCachedThreadPool();
    private final Set<Socket> clientSockets = ConcurrentHashMap.newKeySet();
    private final Set<String> importedBusIds = ConcurrentHashMap.newKeySet();

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;

    public UsbIpServer(Context context, int port) {
        this.context = context.getApplicationContext();
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.port = port;
    }

    public synchronized void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        try {
            serverSocket = new ServerSocket(port);
            acceptThread = new Thread(this::acceptLoop, "UsbIpAccept");
            acceptThread.start();
            LimeLog.info("USB/IP server listening on TCP " + port);
        }
        catch (IOException e) {
            running.set(false);
            throw e;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                clientExecutor.execute(() -> handleClient(socket));
            }
            catch (SocketException e) {
                if (running.get()) {
                    LimeLog.warning("USB/IP accept failed: " + e.getMessage());
                }
            }
            catch (IOException e) {
                if (running.get()) {
                    LimeLog.warning("USB/IP accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        clientSockets.add(socket);
        try (Socket client = socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(client.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(client.getOutputStream()))) {

            int version = in.readUnsignedShort();
            int opCode = in.readUnsignedShort();
            in.readInt(); // request status is reserved/zero

            if (version != UsbIpConstants.USBIP_VERSION) {
                LimeLog.warning(String.format("Unsupported USB/IP version 0x%04x", version));
                writeOpCommon(out, opCode & 0x7fff, UsbIpConstants.ST_ERROR);
                out.flush();
                return;
            }

            if (opCode == UsbIpConstants.OP_REQ_DEVLIST) {
                handleDeviceList(out);
            }
            else if (opCode == UsbIpConstants.OP_REQ_IMPORT) {
                handleImport(in, out);
            }
            else {
                LimeLog.warning(String.format("Unsupported USB/IP op 0x%04x", opCode));
                writeOpCommon(out, opCode & 0x7fff, UsbIpConstants.ST_ERROR);
                out.flush();
            }
        }
        catch (EOFException ignored) {
        }
        catch (IOException | RuntimeException e) {
            if (running.get()) {
                LimeLog.warning("USB/IP client error: " + e.getMessage());
            }
        }
        finally {
            clientSockets.remove(socket);
        }
    }

    private void handleDeviceList(DataOutputStream out) throws IOException {
        List<UsbIpDevice> exported = getExportedDevices();

        writeOpCommon(out, UsbIpConstants.OP_REP_DEVLIST, UsbIpConstants.ST_OK);
        out.writeInt(exported.size());
        for (UsbIpDevice device : exported) {
            device.writeDevice(out);
            device.writeInterfaces(out);
        }
        out.flush();
    }

    private void handleImport(DataInputStream in, DataOutputStream out) throws IOException {
        String requestedBusId = readFixedString(in, 32);
        UsbIpDevice selected = null;
        for (UsbIpDevice candidate : getExportedDevices()) {
            if (candidate.busId.equals(requestedBusId)) {
                selected = candidate;
                break;
            }
        }

        if (selected == null) {
            writeOpCommon(out, UsbIpConstants.OP_REP_IMPORT, UsbIpConstants.ST_NODEV);
            out.flush();
            return;
        }

        if (!importedBusIds.add(selected.busId)) {
            writeOpCommon(out, UsbIpConstants.OP_REP_IMPORT, UsbIpConstants.ST_DEV_BUSY);
            out.flush();
            return;
        }

        try {
            final UsbIpTransferEngine transferEngine;
            try {
                transferEngine = new UsbIpTransferEngine(usbManager, selected.device);
            }
            catch (RuntimeException e) {
                writeOpCommon(out, UsbIpConstants.OP_REP_IMPORT, UsbIpConstants.ST_DEV_BUSY);
                out.flush();
                return;
            }

            writeOpCommon(out, UsbIpConstants.OP_REP_IMPORT, UsbIpConstants.ST_OK);
            selected.writeDevice(out);
            out.flush();

            LimeLog.info("USB/IP imported device " + selected.busId + " (" +
                    String.format("%04x:%04x", selected.device.getVendorId(), selected.device.getProductId()) + ")");

            try (UsbIpTransferEngine ignored = transferEngine) {
                runUrbSession(in, out, transferEngine);
            }
        }
        finally {
            importedBusIds.remove(selected.busId);
        }
    }

    private void runUrbSession(DataInputStream in, DataOutputStream out,
                               UsbIpTransferEngine transferEngine) throws IOException {
        Map<Integer, PendingTransfer> pending = new ConcurrentHashMap<>();
        Object writeLock = new Object();

        while (running.get()) {
            int command;
            try {
                command = in.readInt();
            }
            catch (EOFException e) {
                break;
            }

            int seqnum = in.readInt();
            int devid = in.readInt();
            int direction = in.readInt();
            int endpoint = in.readInt();

            if (command == UsbIpConstants.USBIP_CMD_SUBMIT) {
                int transferFlags = in.readInt();
                int transferBufferLength = in.readInt();
                int startFrame = in.readInt();
                int numberOfPackets = in.readInt();
                in.readInt(); // interval
                byte[] setup = new byte[8];
                in.readFully(setup);

                if (transferBufferLength < 0 || transferBufferLength > MAX_TRANSFER_SIZE) {
                    throw new IOException("Invalid USB/IP transfer length " + transferBufferLength);
                }

                // ISO URBs carry packet descriptors after the transfer payload. Close the
                // import session before touching that variable-sized payload so the parser
                // can never become desynchronized.
                if (numberOfPackets > 0) {
                    throw new IOException("Isochronous USB/IP URBs are not supported yet");
                }

                byte[] outPayload = new byte[0];
                if (direction == UsbIpConstants.USBIP_DIR_OUT && transferBufferLength > 0) {
                    outPayload = new byte[transferBufferLength];
                    in.readFully(outPayload);
                }

                final byte[] submittedPayload = outPayload;
                PendingTransfer pendingTransfer = new PendingTransfer();
                pending.put(seqnum, pendingTransfer);

                Future<?> future = transferExecutor.submit(() -> {
                    UsbIpTransferEngine.Result result = transferEngine.submit(
                            direction, endpoint, transferFlags, transferBufferLength,
                            setup, submittedPayload, IO_TIMEOUT_MS);

                    PendingTransfer state = pending.remove(seqnum);
                    if (state == null || state.cancelled.get()) {
                        return;
                    }

                    try {
                        sendRetSubmit(out, writeLock, seqnum, devid, direction, endpoint,
                                result.status, result.data, result.actualLength,
                                startFrame, 0);
                    }
                    catch (IOException e) {
                        if (running.get()) {
                            LimeLog.warning("USB/IP response failed: " + e.getMessage());
                        }
                    }
                });
                pendingTransfer.future = future;
            }
            else if (command == UsbIpConstants.USBIP_CMD_UNLINK) {
                int unlinkSeqnum = in.readInt();
                // The type-specific union is 28 bytes on the wire. CMD_UNLINK uses
                // the first 4 bytes for seqnum and leaves the remaining 24 reserved.
                byte[] padding = new byte[24];
                in.readFully(padding);

                PendingTransfer target = pending.remove(unlinkSeqnum);
                int status;
                if (target != null) {
                    target.cancelled.set(true);
                    Future<?> future = target.future;
                    if (future != null) {
                        future.cancel(true);
                    }
                    status = UsbIpConstants.ERR_ECONNRESET;
                }
                else {
                    // The target raced to completion before this unlink arrived.
                    status = 0;
                }
                sendRetUnlink(out, writeLock, seqnum, devid, direction, endpoint, status);
            }
            else {
                throw new IOException(String.format("Unknown USB/IP command 0x%08x", command));
            }
        }

        for (PendingTransfer transfer : pending.values()) {
            transfer.cancelled.set(true);
            if (transfer.future != null) {
                transfer.future.cancel(true);
            }
        }
        pending.clear();
    }

    private List<UsbIpDevice> getExportedDevices() {
        Set<String> selected = UsbIpPreferences.getSelectedDeviceNames(context);
        List<UsbIpDevice> result = new ArrayList<>();
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (selected.contains(device.getDeviceName()) && usbManager.hasPermission(device)) {
                result.add(new UsbIpDevice(device));
            }
        }
        return result;
    }

    private static void writeOpCommon(DataOutputStream out, int code, int status) throws IOException {
        out.writeShort(UsbIpConstants.USBIP_VERSION);
        out.writeShort(code);
        out.writeInt(status);
    }

    private static void sendRetSubmit(DataOutputStream out, Object lock,
                                      int seqnum, int devid, int direction, int endpoint,
                                      int status, byte[] data, int actualLength,
                                      int startFrame, int numberOfPackets) throws IOException {
        synchronized (lock) {
            out.writeInt(UsbIpConstants.USBIP_RET_SUBMIT);
            out.writeInt(seqnum);
            out.writeInt(devid);
            out.writeInt(direction);
            out.writeInt(endpoint);
            out.writeInt(status);
            out.writeInt(actualLength);
            out.writeInt(startFrame);
            out.writeInt(numberOfPackets);
            out.writeInt(0); // error_count
            out.writeLong(0); // union padding to the 28-byte type-specific header
            if (direction == UsbIpConstants.USBIP_DIR_IN && status == 0 && data != null) {
                out.write(data, 0, Math.min(actualLength, data.length));
            }
            out.flush();
        }
    }

    private static void sendRetUnlink(DataOutputStream out, Object lock,
                                      int seqnum, int devid, int direction, int endpoint,
                                      int status) throws IOException {
        synchronized (lock) {
            out.writeInt(UsbIpConstants.USBIP_RET_UNLINK);
            out.writeInt(seqnum);
            out.writeInt(devid);
            out.writeInt(direction);
            out.writeInt(endpoint);
            out.writeInt(status);
            for (int i = 0; i < 6; i++) {
                out.writeInt(0);
            }
            out.flush();
        }
    }

    private static String readFixedString(DataInputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        in.readFully(buffer);
        int end = 0;
        while (end < buffer.length && buffer[end] != 0) {
            end++;
        }
        return new String(buffer, 0, end, StandardCharsets.UTF_8);
    }

    @Override
    public synchronized void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            }
            catch (IOException ignored) {
            }
        }

        // Closing active client sockets releases imported USB handles promptly and
        // unblocks threads waiting in DataInputStream.read*().
        for (Socket client : new ArrayList<>(clientSockets)) {
            try {
                client.close();
            }
            catch (IOException ignored) {
            }
        }
        clientSockets.clear();
        importedBusIds.clear();

        clientExecutor.shutdownNow();
        transferExecutor.shutdownNow();
        LimeLog.info("USB/IP server stopped");
    }

    private static final class PendingTransfer {
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile Future<?> future;
    }
}
