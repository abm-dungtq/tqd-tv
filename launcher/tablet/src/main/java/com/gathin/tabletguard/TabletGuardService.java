package com.gathin.tabletguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

public class TabletGuardService extends Service {
    private static final String CHANNEL_ID = "tqd_tablet_guard";
    private static final int NOTIF_ID = 1601;

    public static void start(Context ctx) {
        if (ctx == null) return;
        try {
            Intent i = new Intent(ctx, TabletGuardService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundSafely();
        TabletControlManager.init(this, null);
        TabletWebServer.ensureRunning(this);
        TabletCloudClient.ensureRunning(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundSafely();
        TabletControlManager.init(this, null);
        TabletWebServer.ensureRunning(this);
        TabletCloudClient.ensureRunning(this);
        return START_STICKY;
    }

    private void startForegroundSafely() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID,
                        "Quản lý Máy tính bảng TQD",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Giữ kết nối điều khiển & kiểm soát giờ xem cho bé");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Intent openIntent = new Intent(this, MainActivity.class);
            int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, piFlags);

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                    .setContentTitle("Quản lý Máy tính bảng TQD đang bật")
                    .setContentText("Kết nối từ xa qua tv.tqd.me")
                    .setContentIntent(pi)
                    .setOngoing(true);

            Notification notif = b.build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, notif);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
