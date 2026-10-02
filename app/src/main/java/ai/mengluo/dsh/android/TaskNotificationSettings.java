package ai.mengluo.dsh.android;

import android.app.Activity;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

final class TaskNotificationSettings implements AutoCloseable {
    private final Activity activity;
    private final TaskNotifications notifications;
    private AlertDialog dialog;
    private TextView status;
    private Button test;
    TaskNotificationSettings(Activity activity) { this.activity = activity; notifications = TaskNotifications.get(activity); }
    void show() {
        close(); Ui ui = new Ui(activity);
        LinearLayout content = ui.column(24);
        MaterialSwitch enabled = new MaterialSwitch(activity); enabled.setText("任务结束 / 需要确认时提醒"); enabled.setChecked(notifications.enabled());
        enabled.setTextColor(ui.color(R.color.ink)); enabled.setTag("task-notifications-toggle"); content.addView(enabled);
        content.addView(ui.caption("点击通知返回 Harness，再由你查看结果或确认操作。通知不显示对话内容、文件路径或密钥。"));
        ui.gap(content, 12); status = ui.caption(""); status.setTag("task-notifications-status"); content.addView(status);
        content.addView(ui.button("系统通知设置", R.drawable.ic_task_notification, false, () -> notifications.openSettings(activity)));
        test = ui.button("发送测试通知", 0, false, notifications::test); test.setTag("task-notifications-test"); content.addView(test);
        content.addView(ui.caption("运行期间通过 Harness 页面的实时连接接收提醒。强行停止应用、关闭页面连接或系统暂停后台运行时，无法保证送达；勿扰模式也可能静音。"));
        enabled.setOnCheckedChangeListener((button, checked) -> { notifications.enabled(checked); if (checked) notifications.requestOnce(activity); refresh(); });
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        dialog = new MaterialAlertDialogBuilder(activity).setTitle("任务通知").setView(scroll).setPositiveButton("关闭", null).show();
        refresh();
    }
    void refresh() {
        if (dialog == null || !dialog.isShowing()) return;
        status.setText(!notifications.enabled() ? "已在应用内关闭任务提醒" : notifications.allowed() ? "系统通知已开启" : "系统通知未开启，请在系统通知设置中允许提醒");
        test.setEnabled(notifications.enabled() && notifications.allowed());
    }
    @Override public void close() { if (dialog != null) dialog.dismiss(); dialog = null; status = null; test = null; }
}
