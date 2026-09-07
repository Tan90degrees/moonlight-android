package com.limelight.usbip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.limelight.LimeLog;
import com.limelight.R;

import java.io.IOException;

public final class UsbIpServerService extends Service {
    public static final String ACTION_START = "com.limelight.usbip.action.START";
    public static final String ACTION_STOP = "com.limelight.usbip.action.STOP";

    private static final String CHANNEL_ID = "usbip_server";
    private static final int NOTIFICATION_ID = 3240;

    private UsbIpServer server;

    public static void start(Context context) {
        Intent intent = new Intent(context, UsbIpServerService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        }
        else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, UsbIpServerService.class).setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            UsbIpPreferences.setEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        if (server == null || !server.isRunning()) {
            try {
                server = new UsbIpServer(this, UsbIpPreferences.getPort(this));
                server.start();
                UsbIpPreferences.setEnabled(this, true);
            }
            catch (IOException e) {
                LimeLog.severe("Unable to start USB/IP server: " + e.getMessage());
                UsbIpPreferences.setEnabled(this, false);
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.close();
            server = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.usbip_notification_channel),
                    NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent activityIntent = new Intent(this, UsbIpSettings.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, activityIntent, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(getString(R.string.usbip_notification_title))
                .setContentText(getString(R.string.usbip_notification_text, UsbIpPreferences.getPort(this)))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
    }
}
