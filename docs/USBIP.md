# USB/IP device sharing

This branch adds a non-root userspace USB/IP server to Moonlight Android.

## What it does

- Enumerates USB devices attached to the Android device in USB host/OTG mode.
- Lets the user explicitly select which devices are exported.
- Uses the Android `UsbManager` permission dialog. Root access and kernel USB/IP modules are not required on Android.
- Exposes selected and authorized devices using the standard USB/IP TCP protocol on port `3240`.
- Keeps Moonlight's existing userspace Xbox USB driver from claiming devices selected for USB/IP.

The remote computer is expected to provide the USB/IP client implementation (for example Linux `vhci_hcd`).

## Android usage

1. Connect a USB device to the Android phone/tablet using USB OTG or a powered USB-C hub.
2. Open **Settings > USB/IP device sharing** in Moonlight.
3. Check the USB device that should be shared.
4. Accept the Android USB permission dialog.
5. Enable **USB/IP server**.
6. Keep the phone and remote computer on a trusted network (or use a VPN).

The USB/IP server runs as a foreground `connectedDevice` service while enabled.

## Linux client

Load the virtual USB host controller:

```bash
sudo modprobe vhci-hcd
```

List devices exported by the Android phone:

```bash
usbip list -r PHONE_IP
```

Attach one of the returned bus IDs:

```bash
sudo usbip attach -r PHONE_IP -b android-DEVICE_ID
```

Inspect the attached device:

```bash
usbip port
lsusb
```

Detach it later with:

```bash
sudo usbip detach -p PORT
```

## Protocol implementation

The Android implementation is under `app/src/main/java/com/limelight/usbip/` and implements the USB/IP 1.1 wire protocol directly:

- `OP_REQ_DEVLIST` / `OP_REP_DEVLIST`
- `OP_REQ_IMPORT` / `OP_REP_IMPORT`
- `USBIP_CMD_SUBMIT` / `USBIP_RET_SUBMIT`
- `USBIP_CMD_UNLINK` / `USBIP_RET_UNLINK`

USB transfers are mapped to Android's public `UsbDeviceConnection` API, so the implementation remains non-root.

## Current transfer support

- Control: supported
- Bulk: supported
- Interrupt: best-effort through the Android userspace USB API and device/vendor dependent
- Isochronous: not supported yet

Isochronous URBs are rejected and the import session is closed deliberately so their variable packet descriptor payload cannot desynchronize the USB/IP TCP stream.

## Current limitations

### USB speed metadata

Android's public API does not expose the negotiated USB link speed. The server derives a conservative value from endpoint maximum packet sizes. A future JNI backend can obtain the exact speed from the authorized USB file descriptor.

### Active imports

Changing the selection controls new device-list and import requests. An already active remote import remains alive until the remote client disconnects or the USB/IP service stops.

### USB/IP security

Classic USB/IP has no authentication or encryption. Do not expose TCP port `3240` directly to the public Internet. Use a trusted LAN or a VPN such as WireGuard.

## Recommended initial test devices

Start validation with simple bulk/control devices such as USB serial adapters (CP210x, FTDI, CH34x) before testing more complex devices.

USB cameras and USB audio devices generally use isochronous transfers and are outside the scope of this first implementation.
