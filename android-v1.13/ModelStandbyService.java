package com.example.livechinesea16;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Opt-in, user-visible standby. Holds no wakelock, microphone, or decoder loop.
 * Foreground status makes process less likely to be reclaimed, not immortal.
 * Its ongoing persistent notification exposes an explicit "Stop standby" action.
 */
public final class ModelStandbyService extends Service {
    public static final String ACTION_START = "com.example.livechinesea16.STANDBY_START";
    public static final String ACTION_STOP = "com.example.livechinesea16.STANDBY_STOP";
    private static final String CHANNEL = "live_chinese_model_standby";
    private static final int NOTIFICATION_ID = 218;
    private static final String LOG_TAG = "LiveChineseA16";

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel c = new NotificationChannel(CHANNEL,
                "Model ready in background", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("Optional standby with microphone off; keeps offline Mandarin model available");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            getSharedPreferences(CaptionService.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(CaptionService.KEY_KEEP_READY, false).apply();
            Log.i(LOG_TAG, "QA_STANDBY_STOPPED");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!getSharedPreferences(CaptionService.PREFS, MODE_PRIVATE)
                .getBoolean(CaptionService.KEY_KEEP_READY, false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Intent activity = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 3, activity,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, ModelStandbyService.class).setAction(ACTION_STOP);
        PendingIntent quit = PendingIntent.getService(this, 4, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("Live Chinese — model standby")
                .setContentText("Keeping model ready • Microphone not used by standby")
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "Stop standby", quit).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        Log.i(LOG_TAG, "QA_STANDBY_STARTED, no microphone or wakelock");
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
