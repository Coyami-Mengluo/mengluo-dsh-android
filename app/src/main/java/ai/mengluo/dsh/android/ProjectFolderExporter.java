package ai.mengluo.dsh.android;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Folder/ZIP export through user-selected destinations; no all-files permission or runtime relocation. */
final class ProjectFolderExporter {
    static final int TREE = 33, ZIP = 34;
    private final Activity activity;
    private final ProjectFiles files;
    private final File cache;
    private final Ui ui;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<AlertDialog> dialogs = new HashSet<>();
    private ProjectFiles.Project pendingProject;
    private String pendingRelative;
    private int pendingRequest;
    private boolean closed;
    private AtomicBoolean running;
    ProjectFolderExporter(Activity activity, ProjectFiles files, File cache, Bundle saved) {
        this.activity = activity; this.files = files; this.cache = cache; ui = new Ui(activity);
        if (saved != null && saved.getString("folder.project") != null) {
            pendingProject = new ProjectFiles.Project(saved.getString("folder.title", "项目"), saved.getString("folder.project"));
            pendingRelative = saved.getString("folder.relative", ""); pendingRequest = saved.getInt("folder.request", 0);
        }
        if (saved != null && saved.getBoolean("folder.interrupted", false)) toast("上次导出已停止，目标位置可能保留未完成副本；原项目未修改");
    }
    void save(Bundle state) {
        if (pendingProject != null) {
            state.putString("folder.project", pendingProject.path); state.putString("folder.title", pendingProject.title);
            state.putString("folder.relative", pendingRelative); state.putInt("folder.request", pendingRequest);
        }
        state.putBoolean("folder.interrupted", running != null);
    }
    void close() {
        closed = true; if (running != null) running.set(true);
        for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss(); worker.shutdown();
    }
    private void toast(String text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }
    private AlertDialog show(MaterialAlertDialogBuilder builder) {
        AlertDialog dialog = builder.create(); dialogs.add(dialog); dialog.setOnDismissListener(ignored -> dialogs.remove(dialog)); dialog.show(); return dialog;
    }
    void choose(ProjectFiles.Project project, String relative) {
        if (running != null || pendingProject != null) { toast("请先完成当前导出"); return; }
        show(new MaterialAlertDialogBuilder(activity).setTitle(relative.isEmpty() ? "导出整个项目" : "导出当前文件夹")
            .setItems(new String[]{"保存为手机文件夹…", "打包为 ZIP…"}, (dialog, index) ->
                show(new MaterialAlertDialogBuilder(activity).setTitle(index == 0 ? "导出文件夹" : "导出 ZIP")
                    .setMessage("保留子目录、空文件夹和隐藏文件；符号链接、运行环境及特殊文件会跳过并列出。\n\n建议暂停项目写入后导出，可保存到 Documents/MengLuo。不会移动原项目或覆盖已有文件夹；分享前请检查隐私内容。")
                    .setNegativeButton("取消", null).setPositiveButton("选择保存位置", (d, which) -> pick(project, relative, index == 0 ? TREE : ZIP))))
            .setNegativeButton("取消", null));
    }
    static Intent intent(int request, String name) {
        if (request == TREE) return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (request == ZIP) return new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip").putExtra(Intent.EXTRA_TITLE, ProjectExport.safeName(name) + ".zip");
        throw new IllegalArgumentException("Unknown export mode");
    }
    private void pick(ProjectFiles.Project project, String relative, int request) {
        try {
            String name = files.directory(project, relative).getName();
            pendingProject = project; pendingRelative = relative; pendingRequest = request;
            activity.startActivityForResult(intent(request, name), request);
        } catch (Exception error) { clear(); toast("无法选择保存位置：" + error.getMessage()); }
    }
    private void clear() { pendingProject = null; pendingRelative = null; pendingRequest = 0; }
    boolean result(int request, int result, Intent data) {
        if (request != TREE && request != ZIP) return false;
        ProjectFiles.Project project = pendingProject; String relative = pendingRelative; int expected = pendingRequest; clear();
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        if (project == null || expected != request) { toast("导出项目记录已失效，请重新选择"); return true; }
        if (!"content".equals(data.getData().getScheme())) { toast("请选择系统提供的保存位置"); return true; }
        if (running != null) { toast("已有导出正在进行"); return true; }
        export(project, relative, request, data.getData()); return true;
    }
    private void export(ProjectFiles.Project project, String relative, int request, Uri destination) {
        AtomicBoolean cancelled = new AtomicBoolean(); running = cancelled;
        LinearLayout content = ui.column(24); TextView label = ui.caption("正在扫描文件夹…");
        label.setTag("folder-export-status"); label.setMaxLines(4);
        LinearProgressIndicator bar = new LinearProgressIndicator(activity); bar.setIndeterminate(true);
        content.addView(label); ui.gap(content, 12); content.addView(bar, new LinearLayout.LayoutParams(-1, ui.dp(4)));
        AlertDialog dialog = show(new MaterialAlertDialogBuilder(activity).setTitle("正在导出文件夹").setView(content)
            .setCancelable(false).setNegativeButton("取消", null));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            cancelled.set(true); dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false); label.setText("正在取消，等待当前读写结束…");
        });
        ContentResolver resolver = activity.getApplicationContext().getContentResolver();
        worker.execute(() -> {
            String resultText; boolean complete = false; File temporary = null;
            long[] last = {0};
            ProjectExport.Progress update = (name, done, total) -> {
                long now = System.nanoTime(); if (now - last[0] < 150_000_000L) return; last[0] = now;
                int percent = total <= 0 ? 0 : (int)Math.min(99, done * 100 / total);
                activity.runOnUiThread(() -> {
                    if (closed || cancelled.get()) return;
                    bar.setIndeterminate(total <= 0); if (total > 0) bar.setProgressCompat(percent, true);
                    label.setText((total > 0 ? percent + "% · " : "") + name);
                });
            };
            try {
                ProjectExport.Plan plan = ProjectExport.scan(files, project, relative, cancelled::get, update);
                ProjectExport.check(cancelled::get);
                String location;
                if (request == TREE) {
                    DocumentTreeExport target = new DocumentTreeExport(resolver, destination, plan.name);
                    ProjectExport.copy(plan, target, cancelled::get, update); ProjectExport.check(cancelled::get); target.finish(); location = target.name;
                } else {
                    temporary = File.createTempFile("folder-export-", ".zip", cache);
                    try (OutputStream output = new FileOutputStream(temporary)) { ProjectExport.zip(plan, output, cancelled::get, update); }
                    ProjectExport.check(cancelled::get);
                    try (InputStream input = new FileInputStream(temporary); OutputStream output = resolver.openOutputStream(destination, "wt")) {
                        if (output == null) throw new IOException("无法写入 ZIP 保存位置");
                        byte[] buffer = new byte[64 * 1024]; int count; long done = 0, total = temporary.length();
                        while ((count = input.read(buffer)) != -1) {
                            ProjectExport.check(cancelled::get); output.write(buffer, 0, count); done += count; update.update("保存 ZIP", done, total);
                        }
                    }
                    location = ProjectExport.safeName(plan.name) + ".zip";
                }
                complete = true; resultText = "已保存：" + location + "\n\n" + plan.summary();
            } catch (Exception error) {
                resultText = RuntimePolicy.redact(String.valueOf(error.getMessage()))
                    + "\n\n原项目未修改。所选位置可能保留未完成的副本；文件夹中的 .incomplete 标记表示导出未完成。";
            } finally { if (temporary != null) try { Files.deleteIfExists(temporary.toPath()); } catch (IOException ignored) { } }
            String result = resultText; boolean success = complete;
            activity.runOnUiThread(() -> {
                running = null; if (closed || activity.isDestroyed()) return; dialog.dismiss();
                show(new MaterialAlertDialogBuilder(activity).setTitle(success ? "导出完成" : cancelled.get() ? "导出已取消" : "导出未完成")
                    .setMessage(result).setPositiveButton("知道了", null));
            });
        });
    }
}
