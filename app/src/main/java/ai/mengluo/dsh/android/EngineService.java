package ai.mengluo.dsh.android;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.util.function.Consumer;

/** User-started foreground runtime, never silently restarted after a device reboot. */
public final class EngineService extends Service {
    static final String CHANNEL = "local-runtime";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Consumer<Engine> notificationRuntime = engine -> TaskNotifications.get(this).runtime(engine.readyUrl);
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, "本地 Harness", NotificationManager.IMPORTANCE_LOW));
        Engine.get(this).listen(notificationRuntime);
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        Engine engine = Engine.get(this);
        if (intent != null && "stop".equals(intent.getAction())) { engine.stop(); stopSelf(); return START_NOT_STICKY; }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, EngineService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
            .setContentTitle("MengLuo · 本地代码工作区")
            .setContentText("点击返回工作区，或停止当前运行")
            .setSmallIcon(android.R.drawable.ic_menu_edit).setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "停止", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, notification);
        if (intent != null && "apk-download".equals(intent.getAction())) AndroidUpdates.get(this).download();
        else if (intent != null && "update-harness".equals(intent.getAction())) engine.install(intent.getStringExtra("version"));
        else if (intent != null && "rollback".equals(intent.getAction())) engine.rollback();
        else if (intent != null && "install".equals(intent.getAction())) engine.install();
        else engine.start();
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { Engine.get(this).unlisten(notificationRuntime); TaskNotifications.get(this).runtime(null); Engine.get(this).stop(); handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
