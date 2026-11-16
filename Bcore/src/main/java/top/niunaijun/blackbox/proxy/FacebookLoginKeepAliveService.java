package top.niunaijun.blackbox.proxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;

/**
 * Temporary foreground service used only while an external Facebook/Chrome login is active.
 * It prevents aggressive Android/OEM background management from killing the :black process
 * and invalidating ActivityManager/PackageManager binder services during the callback round-trip.
 */
public class FacebookLoginKeepAliveService extends Service {
    public static final String ACTION_STOP = "top.niunaijun.blackbox.action.STOP_FACEBOOK_LOGIN_KEEPALIVE";
    private static final String CHANNEL_ID = "bbox_login_bridge";
    private static final int NOTIFICATION_ID = 0x4242;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::stopSelf;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        handler.removeCallbacks(timeout);
        // Safety timeout in case the user cancels the browser login.
        handler.postDelayed(timeout, 120_000L);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(timeout);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "Login bridge", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Keeps the virtual session active during external sign-in");
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Login in progress")
                .setContentText("Keeping the virtual session active")
                .setOngoing(true)
                .build();
    }
}
