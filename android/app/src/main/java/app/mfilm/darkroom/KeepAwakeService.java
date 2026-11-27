package app.mfilm.darkroom;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * A roll takes minutes. While it develops, this service keeps the app alive in
 * the background and the phone awake, and puts the frame being developed in the
 * notification shade -- so going to the gallery, or letting the screen time
 * out, doesn't stop the batch or lose it.
 */
public class KeepAwakeService extends Service {

    static final String ACTION_START = "app.mfilm.darkroom.KEEP_AWAKE";
    static final String ACTION_STOP = "app.mfilm.darkroom.KEEP_AWAKE_STOP";
    static final String EXTRA_TEXT = "text";

    private static final String CHANNEL = "developing";
    private static final int ID = 1;

    private PowerManager.WakeLock lock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String text = intent == null ? null : intent.getStringExtra(EXTRA_TEXT);
        if (text == null) text = getString(R.string.keep_awake);
        startForeground(ID, notification(text));

        if (lock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mfilm:develop");
            lock.setReferenceCounted(false);
        }
        if (!lock.isHeld()) lock.acquire(60 * 60 * 1000L);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (lock != null && lock.isHeld()) lock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    getString(R.string.keep_awake_channel), NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(open)
                .build();
    }
}
