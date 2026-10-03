package ai.mengluo.dsh.android;

import android.content.*;
import android.net.Uri;
import android.provider.Settings;
import android.widget.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** System grants remain user-operated. There is deliberately no persistent automation enable switch. */
final class PhonePermissions {
    private static java.lang.ref.WeakReference<TextView> guideState = new java.lang.ref.WeakReference<>(null);
    private PhonePermissions() { }
    static void refresh(MainActivity activity) { TextView view = guideState.get(); if (view != null && view.getContext() == activity) view.setText(PhoneControl.get(activity).permissionSummary()); }
    static androidx.appcompat.app.AlertDialog show(MainActivity activity) {
        PhoneControl control = PhoneControl.get(activity); Ui ui = new Ui(activity);
        LinearLayout content = ui.column(24);
        TextView state = ui.text(control.permissionSummary(), 15, false); guideState = new java.lang.ref.WeakReference<>(state); content.addView(state);
        content.addView(ui.caption("和 Harness 说你要操作手机即可。开启系统权限后，任务可直接操作普通应用；普通点击、导航、输入和截图自动执行。截图包含整屏可见内容（包括通知、键盘等），仅遮住本应用悬浮窗。文字与截图会进入对话，并可能发送给你配置的模型服务。"));
        content.addView(ui.caption("部分设备的无障碍截图可能全黑，此时会请求 Android 屏幕共享授权作为备用通道。需你亲自确认；每次任务最多请求一次，拒绝后不反复弹出。不录制音视频，只在请求截图时读取一帧；停止任务即结束屏幕共享。"));
        content.addView(ui.button("授权无障碍服务", 0, true, () -> {
            try { activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (ActivityNotFoundException error) { Toast.makeText(activity, "请在系统设置的无障碍服务中选择 MengLuo · 手机操作", Toast.LENGTH_LONG).show(); }
        }));
        content.addView(ui.button("授权跨应用悬浮窗", 0, false, () -> {
            try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + activity.getPackageName()))); }
            catch (ActivityNotFoundException error) { Toast.makeText(activity, "请在系统设置的特殊应用权限中允许悬浮窗", Toast.LENGTH_LONG).show(); }
        }));
        content.addView(ui.caption("支付、发送、删除等敏感动作，以及模型需要询问时，会在悬浮药丸中等你确认。自动识别不能保证涵盖所有风险，请留意操作。授权后返回会检查连接，不恢复已取消的任务。随时可点「停止」；已完成的操作不会撤销。系统保护界面、锁屏和密码控件不操作。"));
        content.addView(ui.button("重新检查状态", 0, false, () -> { control.permissionsChanged(); state.setText(control.permissionSummary()); }));
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        return new MaterialAlertDialogBuilder(activity).setTitle("手机操作权限").setView(scroll).setPositiveButton("完成", null).show();
    }
}
