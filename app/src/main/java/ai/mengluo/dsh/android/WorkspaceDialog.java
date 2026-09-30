package ai.mengluo.dsh.android;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** An explicit, direct-path folder picker, not a SAF URI masquerading as a filesystem path. */
final class WorkspaceDialog {
    private static final int PAGE_SIZE = 80;
    private final Activity activity;
    private final Engine engine;
    private final Ui ui;
    private final Runnable changed;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<AlertDialog> dialogs = new HashSet<>();
    private boolean closed, waitingForPermission;

    WorkspaceDialog(Activity activity, Engine engine, Bundle saved, Runnable changed) {
        this.activity = activity; this.engine = engine; this.changed = changed; ui = new Ui(activity);
        waitingForPermission = saved != null && saved.getBoolean("workspace.permission", false);
    }
    void save(Bundle state) { state.putBoolean("workspace.permission", waitingForPermission); }
    void close() { closed = true; for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss(); worker.shutdown(); }
    private void show(AlertDialog dialog) { if (closed) return; dialogs.add(dialog); dialog.setOnDismissListener(d -> dialogs.remove(dialog)); dialog.show(); }
    private void toast(String message) { if (!closed) Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }
    void show() {
        LinearLayout content = ui.column(24); content.setTag("workspace-settings");
        content.addView(ui.text("当前工作目录", 16, true));
        TextView path = ui.caption(engine.workspaces.guestPath()); path.setTextIsSelectable(true); content.addView(path);
        content.addView(ui.caption(engine.workspaces.selected() == null ? "应用内工作目录 · 不需要额外文件权限"
            : engine.workspaces.hasAccess() ? "手机公共目录 · 直接读写原文件" : "所有文件访问未授权，启动与终端将暂停使用此目录"));
        ui.gap(content, 12);
        content.addView(ui.caption("Harness 与终端从所选目录启动。官方界面中的已有会话仍使用原项目；新项目请选择下方复制的路径。切换不会搬移、覆盖或删除原文件。"));
        ui.gap(content, 12);
        Button choose = ui.button("选择手机文件夹", R.drawable.ic_workspace, true, this::choose);
        choose.setTag("workspace-choose"); content.addView(choose, new LinearLayout.LayoutParams(-1, -2));
        content.addView(ui.button("使用应用内工作目录", R.drawable.ic_home, false, () -> confirm(null)), new LinearLayout.LayoutParams(-1, -2));
        content.addView(ui.button("复制 Harness 路径", 0, false, () -> {
            activity.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Harness 工作目录", engine.workspaces.guestPath()));
            toast("路径已复制，可在 Harness 工作区选择器中使用");
        }), new LinearLayout.LayoutParams(-1, -2));
        ui.gap(content, 12);
        content.addView(ui.caption("公共目录可运行 Node、Python、Bash 脚本，但不保证支持软链接、原生依赖或所有构建工具。Ubuntu、Node 和配置仍保存在应用内部。"));
        content.addView(ui.caption("所有文件访问是较广的系统权限，不是仅授权所选目录。Harness、插件和命令可能访问其他公共文件；只运行可信代码。它与 Harness 的「完全访问」模式是两项独立设置。"));
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        show(new MaterialAlertDialogBuilder(activity).setTitle("工作目录").setView(scroll).setPositiveButton("关闭", null).create());
    }
    private void choose() {
        if (engine.busy || engine.checkingSource) { toast("请等待当前操作完成"); return; }
        if (engine.workspaces.hasAccess()) { browse(initialDirectory(), 0); return; }
        show(new MaterialAlertDialogBuilder(activity).setTitle("允许直接访问手机文件？")
            .setMessage("为让 Harness 和终端直接在公共目录运行，需要你在系统设置中开启「所有文件访问」。\n\n这会授予较广的公共文件读写权限，不限于选中的文件夹；代码或插件可能读取、修改其他公共文件。不会获得 Root 或其他 App 的私有数据权限。\n\n不授权仍可使用应用内工作目录和导入 / 导出。")
            .setNegativeButton("暂不授权", null).setPositiveButton("去系统授权", (d, which) -> {
                try {
                    waitingForPermission = true;
                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName())));
                } catch (ActivityNotFoundException error) {
                    waitingForPermission = false; toast("此系统没有提供权限页面，请在系统设置的特殊应用权限中手动开启所有文件访问");
                }
            }).create());
    }
    void onResume() {
        if (!waitingForPermission) return;
        waitingForPermission = false;
        if (engine.workspaces.hasAccess()) browse(initialDirectory(), 0);
        else toast("未授权，工作目录没有改变");
    }
    private File initialDirectory() {
        File selected = engine.workspaces.selected();
        return selected != null && selected.isDirectory() ? selected : engine.workspaces.sharedRoot;
    }
    void browse(File directory, int requestedPage) {
        AlertDialog loading = new MaterialAlertDialogBuilder(activity).setTitle("读取文件夹…").setMessage(directory.getPath()).create();
        show(loading);
        worker.execute(() -> {
            File checked = null; ArrayList<File> folders = new ArrayList<>(); String failure = null;
            try {
                checked = engine.workspaces.validate(directory, true);
                File[] entries = checked.listFiles(); if (entries == null) throw new IOException("无法读取此目录，请检查权限");
                for (File child : entries) {
                    if (!child.isDirectory() || Files.isSymbolicLink(child.toPath())) continue;
                    try { folders.add(engine.workspaces.validate(child, false)); } catch (IOException ignored) { /* Not a selectable project directory. */ }
                }
                folders.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER).thenComparing(File::getName));
            } catch (Exception error) { failure = error.getMessage(); }
            File target = checked; String error = failure;
            activity.runOnUiThread(() -> {
                if (closed || !loading.isShowing()) return;
                loading.dismiss();
                if (error != null) { toast(error); return; }
                directoryPage(target, folders, requestedPage);
            });
        });
    }
    private void directoryPage(File directory, List<File> folders, int requestedPage) {
        int pages = Math.max(1, (folders.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(pages - 1, requestedPage));
        ArrayList<String> labels = new ArrayList<>(); ArrayList<Runnable> actions = new ArrayList<>();
        boolean root = directory.equals(engine.workspaces.sharedRoot.getAbsoluteFile());
        if (!root) { labels.add("↑ 上级目录"); actions.add(() -> browse(directory.getParentFile(), 0)); }
        for (int i = page * PAGE_SIZE; i < Math.min(folders.size(), (page + 1) * PAGE_SIZE); i++) {
            File folder = folders.get(i); labels.add("▸ " + folder.getName()); actions.add(() -> browse(folder, 0));
        }
        if (page > 0) { labels.add("← 上一页"); actions.add(() -> browse(directory, page - 1)); }
        if (page + 1 < pages) { labels.add("下一页 →"); actions.add(() -> browse(directory, page + 1)); }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity).setTitle(directory.getPath())
            .setNegativeButton("取消", null).setNeutralButton("新建文件夹", (d, which) -> newFolder(directory))
            .setPositiveButton("使用此文件夹", (d, which) -> confirm(directory));
        if (labels.isEmpty()) builder.setMessage("空文件夹，可以直接作为工作目录。");
        else builder.setItems(labels.toArray(new String[0]), (d, index) -> actions.get(index).run());
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!root));
        show(dialog);
    }
    private void newFolder(File parent) {
        EditText name = new TextInputEditText(activity); name.setHint("文件夹名称"); name.setSingleLine(true);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle("新建项目文件夹").setView(ui.input(name))
            .setNegativeButton("取消", (d, which) -> browse(parent, 0)).setPositiveButton("创建", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String text = name.getText().toString().trim(); ProjectFiles.child("", text);
                if (text.indexOf(':') >= 0 || text.equalsIgnoreCase("Android")) throw new IOException("请使用其他文件夹名称");
                File checked = engine.workspaces.validate(parent, true), child = new File(checked, text);
                if (!child.mkdir()) throw new IOException("创建失败，可能已有同名文件夹或没有写入权限");
                dialog.dismiss(); browse(child, 0);
            } catch (Exception error) { name.setError(error.getMessage()); }
        })); show(dialog);
    }
    private void confirm(File directory) {
        if (engine.busy || engine.checkingSource) { toast("请等待当前操作完成"); return; }
        boolean running = engine.running();
        show(new MaterialAlertDialogBuilder(activity).setTitle("切换工作目录？")
            .setMessage((directory == null ? "/workspace（应用内）" : directory.getPath())
                + "\n\n" + (running ? "将停止正在运行的 Harness，当前任务会中断。切换后请重新启动。\n" : "")
                + "仅改变启动与终端的工作目录；旧会话的项目路径不变，原文件不搬移或删除。")
            .setNegativeButton("取消", null).setPositiveButton(running ? "停止并切换" : "确认切换", (d, which) -> {
                for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
                engine.changeWorkspace(directory, running, failure -> {
                    if (closed) return;
                    if (failure != null) { toast(failure); show(); }
                    else { changed.run(); toast("工作目录已保存，可启动 Harness 或打开终端"); }
                });
            }).create());
    }
}
