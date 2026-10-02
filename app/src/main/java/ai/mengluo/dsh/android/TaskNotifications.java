package ai.mengluo.dsh.android;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import java.util.LinkedHashSet;

/** Only generic task state reaches the notification drawer; no prompt, path, token or approval action. */
final class TaskNotifications {
    static final String CHANNEL = "harness-tasks";
    static final String OPEN = "ai.mengluo.dsh.android.OPEN_TASK";
    static final int PERMISSION_REQUEST = 20;
    @android.annotation.SuppressLint("StaticFieldLeak") // get() retains only the application context, never an Activity.
    private static TaskNotifications instance;
    static synchronized TaskNotifications get(Context context) {
        if (instance == null) instance = new TaskNotifications(context.getApplicationContext());
        return instance;
    }
    final TaskNoticeState state = new TaskNoticeState();
    private final Context context;
    private final NotificationManager manager;
    private final SharedPreferences preferences;
    private final LinkedHashSet<String> shown = new LinkedHashSet<>();
    private String runtime;
    private long lastSound = -10_000;
    TaskNotifications(Context context) {
        this.context = context;
        manager = context.getSystemService(NotificationManager.class);
        preferences = context.getSharedPreferences("task-notifications", 0);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Harness 任务提醒", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("任务结束或需要确认时提醒；点击返回 Harness，不会自动确认操作。");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);
    }
    boolean enabled() { return preferences.getBoolean("enabled", true); }
    void enabled(boolean value) { preferences.edit().putBoolean("enabled", value).apply(); if (!value) cancelAll(); }
    boolean allowed() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled() && channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
    void requestOnce(Activity activity) {
        if (!enabled() || Build.VERSION.SDK_INT < 33 || allowed() || preferences.getBoolean("permission-asked", false)) return;
        preferences.edit().putBoolean("permission-asked", true).apply();
        activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST);
    }
    void openSettings(Activity activity) {
        if (enabled() && Build.VERSION.SDK_INT >= 33 && !preferences.getBoolean("permission-asked", false)
            && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) { requestOnce(activity); return; }
        activity.startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL));
    }
    void runtime(String value) {
        if (java.util.Objects.equals(runtime, value)) return;
        runtime = value; state.clear(); cancelAll();
    }
    void show(String session, TaskNoticeState.Notice notice) {
        if (notice == TaskNoticeState.Notice.NONE || !enabled() || !allowed()) return;
        String title = notice == TaskNoticeState.Notice.WAITING ? "Harness 需要你确认" : "Harness 本轮任务已结束";
        String body = notice == TaskNoticeState.Notice.WAITING ? "点击返回 Harness，查看并处理确认或提问。" : "点击返回 Harness 查看结果。";
        post(session, title, body);
    }
    void test() { if (enabled() && allowed()) post("test", "Harness 任务提醒已开启", "这是一条测试通知，点击返回应用。"); }
    private void post(String session, String title, String body) {
        Intent intent = new Intent(context, MainActivity.class).setAction(OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(context, 22, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification publicVersion = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_task_notification)
            .setContentTitle("MengLuo DSH Android").setContentText("有新的任务提醒").build();
        long now = SystemClock.elapsedRealtime();
        Notification notice = new NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_task_notification)
            .setContentTitle(title).setContentText(body).setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
            .setGroup("harness-tasks").setTimeoutAfter(24 * 60 * 60 * 1000L)
            .setSilent(now - lastSound < 3_000).build();
        try {
            manager.notify("task:" + session, 2, notice); lastSound = now;
            shown.remove(session); shown.add(session);
            while (shown.size() > 24) cancel(shown.iterator().next());
        } catch (SecurityException revoked) { /* Permission can be revoked between the check and notify. */ }
    }
    void cancel(String session) { manager.cancel("task:" + session, 2); shown.remove(session); }
    private void cancelAll() { for (String session : new java.util.ArrayList<>(shown)) cancel(session); }
}
