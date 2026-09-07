package com.limelight.usbip;

final class UsbIpConstants {
    private UsbIpConstants() {}

    static final int USBIP_VERSION = 0x0111;

    static final int OP_REQ_IMPORT = 0x8003;
    static final int OP_REP_IMPORT = 0x0003;
    static final int OP_REQ_DEVLIST = 0x8005;
    static final int OP_REP_DEVLIST = 0x0005;

    static final int ST_OK = 0x00;
    static final int ST_NA = 0x01;
    static final int ST_DEV_BUSY = 0x02;
    static final int ST_DEV_ERR = 0x03;
    static final int ST_NODEV = 0x04;
    static final int ST_ERROR = 0x05;

    static final int USBIP_CMD_SUBMIT = 0x00000001;
    static final int USBIP_CMD_UNLINK = 0x00000002;
    static final int USBIP_RET_SUBMIT = 0x00000003;
    static final int USBIP_RET_UNLINK = 0x00000004;

    static final int USBIP_DIR_OUT = 0;
    static final int USBIP_DIR_IN = 1;

    static final int USB_SPEED_UNKNOWN = 0;
    static final int USB_SPEED_LOW = 1;
    static final int USB_SPEED_FULL = 2;
    static final int USB_SPEED_HIGH = 3;
    static final int USB_SPEED_WIRELESS = 4;
    static final int USB_SPEED_SUPER = 5;
    static final int USB_SPEED_SUPER_PLUS = 6;

    static final int ERR_ECONNRESET = -104;
    static final int ERR_EPIPE = -32;
    static final int ERR_ENODEV = -19;
    static final int ERR_EINVAL = -22;
    static final int ERR_ETIMEDOUT = -110;
    static final int ERR_ENOSYS = -38;
}
