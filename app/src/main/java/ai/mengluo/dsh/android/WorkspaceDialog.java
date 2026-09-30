package ai.mengluo.dsh.android;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;

/** Permission entry only. Project selection stays in the official Harness UI. */
final class WorkspaceDialog {
    private final Activity activity;
    private final Engine engine;
    private final Ui ui;
    private final Runnable changed;
    private final Set<AlertDialog> dialogs = new HashSet<>();
    private boolean closed, waitingForPermission;

    WorkspaceDialog(Activity activity, Engine engine, Bundle saved, Runnable changed) {
        this.activity = activity; this.engine = engine; this.changed = changed; ui = new Ui(activity);
        waitingForPermission = saved != null && saved.getBoolean("workspace.permission", false);
    }
    void save(Bundle state) { state.putBoolean("workspace.permission", waitingForPermission); }
    void close() { closed = true; dismiss(); }
    private void dismiss() { for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss(); }
    private void show(AlertDialog dialog) { if (closed) return; dialogs.add(dialog); dialog.setOnDismissListener(d -> dialogs.remove(dialog)); dialog.show(); }
    private void toast(String message) { if (!closed) Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }
    void show() {
        boolean allowed = engine.workspaces.hasAccess();
        LinearLayout content = ui.column(24); content.setTag("storage-settings");
        content.addView(ui.text(allowed ? "已允许所有文件访问" : "尚未授权手机文件访问", 18, true));
        ui.gap(content, 10);
        content.addView(ui.caption("项目位置由 Harness 选择，客户端不再限定单个文件夹。授权后可在 Harness 中浏览、创建和切换手机公共目录中的项目。"));
        TextView path = ui.caption("手机内部存储：" + engine.workspaces.sharedRoot.getPath());
        path.setTextIsSelectable(true); content.addView(path); ui.gap(content, 12);
        Button permission = ui.button(allowed ? "管理文件权限" : "授权文件访问", R.drawable.ic_workspace, true, this::permissions);
        permission.setTag("storage-permission"); content.addView(permission, new LinearLayout.LayoutParams(-1, -2));
        content.addView(ui.button("复制手机存储路径", 0, false, () -> {
            activity.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("手机存储路径", engine.workspaces.sharedRoot.getPath()));
            toast("路径已复制，可在 Harness 的项目选择器中打开");
        }), new LinearLayout.LayoutParams(-1, -2));
        ui.gap(content, 12);
        content.addView(ui.caption("原项目、聊天与运行环境不搬动；/workspace 仍指向应用内工作区。不授权也可继续使用应用内项目和导入 / 导出。"));
        content.addView(ui.caption("授权会开放系统允许的公共文件，代码和插件也可访问这些文件；其他 App 私有数据仍受系统保护。不会自动开启 Harness 的「完全访问」模式。"));
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        show(new MaterialAlertDialogBuilder(activity).setTitle("手机文件访问").setView(scroll).setPositiveButton("关闭", null).create());
    }
    private void permissions() {
        if (engine.busy || engine.checkingSource) { toast("请等待当前操作完成后调整文件权限"); return; }
        show(new MaterialAlertDialogBuilder(activity).setTitle(engine.workspaces.hasAccess() ? "管理手机文件权限" : "允许访问手机公共文件？")
            .setMessage("你将在系统设置中管理「所有文件访问」。授权后，Harness、代码和插件可读写系统允许的公共文件，不限于某个项目；请只运行可信代码。\n\n修改权限时系统可能结束 App，请先保存文件并结束正在运行的任务。不会获得 Root，也不会自动修改 Harness 的运行权限。")
            .setNegativeButton("取消", null).setPositiveButton("去系统设置", (d, which) -> {
                try {
                    waitingForPermission = true; dismiss();
                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName())));
                } catch (ActivityNotFoundException error) {
                    waitingForPermission = false; toast("请在系统设置的特殊应用权限中管理所有文件访问");
                }
            }).create());
    }
    void onResume() {
        if (!waitingForPermission || closed) return;
        waitingForPermission = false; changed.run(); show();
    }
}
