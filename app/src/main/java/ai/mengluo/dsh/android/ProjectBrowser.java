package ai.mengluo.dsh.android;

import android.app.Activity;
import android.content.*;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Native project picker and SAF transfers. It does not grant filesystem access to WebView. */
final class ProjectBrowser {
    static final int IMPORT = 30, EXPORT = 31;
    private static final int PAGE_SIZE = 100;
    private final Activity activity;
    private final Ui ui;
    private final File profile, cache;
    private final ProjectFiles files;
    private final ProjectFolderExporter folders;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<AlertDialog> dialogs = new HashSet<>();
    private boolean closed;
    private ProjectFiles.Project pendingProject;
    private String pendingRelative;
    private int pendingRequest;
    ProjectBrowser(Activity activity, File workspace, File rootfs, File profile, Bundle saved) {
        this.activity = activity; ui = new Ui(activity); this.profile = profile; cache = activity.getCacheDir();
        files = new ProjectFiles(workspace, rootfs);
        folders = new ProjectFolderExporter(activity, files, cache, saved);
        if (saved != null && saved.getString("files.project") != null) {
            pendingProject = new ProjectFiles.Project(saved.getString("files.title", "项目"), saved.getString("files.project"));
            pendingRelative = saved.getString("files.relative", ""); pendingRequest = saved.getInt("files.request", 0);
        }
    }
    void save(Bundle state) {
        folders.save(state);
        if (pendingProject == null) return;
        state.putString("files.project", pendingProject.path); state.putString("files.title", pendingProject.title);
        state.putString("files.relative", pendingRelative); state.putInt("files.request", pendingRequest);
    }
    void close() { closed = true; folders.close(); for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss(); worker.shutdown(); }
    private AlertDialog show(MaterialAlertDialogBuilder builder) { AlertDialog dialog = builder.create(); show(dialog); return dialog; }
    private void show(AlertDialog dialog) { dialogs.add(dialog); dialog.setOnDismissListener(ignored -> dialogs.remove(dialog)); dialog.show(); }
    private void toast(String value) { Toast.makeText(activity, value, Toast.LENGTH_LONG).show(); }
    void showProjects() {
        ProjectCatalog catalog = ProjectCatalog.read(profile);
        String[] labels = catalog.projects.stream().map(p -> p.title + "\n" + p.path).toArray(String[]::new);
        show(new MaterialAlertDialogBuilder(activity).setTitle("代码文件 · 选择项目")
            .setItems(labels, (dialog, index) -> browse(catalog.projects.get(index), "", 0))
            .setNegativeButton("关闭", null).setNeutralButton("刷新列表", (dialog, which) -> showProjects()));
        if (catalog.warning != null) toast(catalog.warning);
    }
    void browse(ProjectFiles.Project project, String relative, int requestedPage) {
        try {
            List<File> entries = files.list(project, relative);
            int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE), page = Math.max(0, Math.min(pages - 1, requestedPage));
            ArrayList<String> labels = new ArrayList<>(); ArrayList<Runnable> actions = new ArrayList<>();
            if (!relative.isEmpty()) {
                labels.add("↑ 上级目录"); actions.add(() -> browse(project, parent(relative), 0));
            }
            for (int i = page * PAGE_SIZE; i < Math.min(entries.size(), (page + 1) * PAGE_SIZE); i++) {
                File file = entries.get(i); String next = ProjectFiles.child(relative, file.getName());
                boolean link = Files.isSymbolicLink(file.toPath());
                labels.add((link ? "↗ " : file.isDirectory() ? "▸ " : "") + file.getName());
                actions.add(() -> {
                    try {
                        File safe = files.resolve(project, next);
                        if (safe.isDirectory()) browse(project, next, 0); else fileActions(project, next);
                    } catch (Exception error) { toast(error.getMessage()); }
                });
            }
            if (page > 0) { labels.add("← 上一页"); actions.add(() -> browse(project, relative, page - 1)); }
            if (page + 1 < pages) { labels.add("下一页 →"); actions.add(() -> browse(project, relative, page + 1)); }
            String title = project.title + (relative.isEmpty() ? "" : " / " + relative);
            if (pages > 1) title += "（" + (page + 1) + "/" + pages + "）";
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity).setTitle(title)
                .setNegativeButton("项目列表", (dialog, which) -> showProjects())
                .setPositiveButton("新建文件", (dialog, which) -> newFile(project, relative))
                .setNeutralButton("导入 / 导出", (dialog, which) -> transfers(project, relative));
            if (labels.isEmpty()) builder.setMessage("此目录还没有文件。\n" + project.path + (relative.isEmpty() ? "" : "/" + relative));
            else builder.setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run());
            show(builder);
        } catch (Exception error) {
            show(new MaterialAlertDialogBuilder(activity).setTitle("无法打开项目").setMessage(project.path + "\n\n" + error.getMessage())
                .setNegativeButton("关闭", null).setPositiveButton("项目列表", (dialog, which) -> showProjects()));
        }
    }
    private static String parent(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }
    private void transfers(ProjectFiles.Project project, String relative) {
        ArrayList<String> labels = new ArrayList<>(List.of(relative.isEmpty() ? "导出整个项目…" : "导出当前文件夹…"));
        if (!relative.isEmpty()) labels.add("导出整个项目…");
        labels.add("导入文件到此目录…");
        show(new MaterialAlertDialogBuilder(activity).setTitle("导入 / 导出")
            .setItems(labels.toArray(new String[0]), (dialog, index) -> {
                if (index == labels.size() - 1) pick(project, relative, IMPORT);
                else folders.choose(project, index == 0 ? relative : "");
            }).setNegativeButton("返回目录", (dialog, which) -> browse(project, relative, 0)));
    }
    private void newFile(ProjectFiles.Project project, String directory) {
        EditText name = new TextInputEditText(activity); name.setHint("文件名，例如 hello.js"); name.setSingleLine(true);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle("新建文件 · " + project.title).setView(ui.input(name))
            .setNegativeButton("取消", (d, which) -> browse(project, directory, 0)).setPositiveButton("创建", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                String value = name.getText().toString().trim(); files.create(project, directory, value);
                dialog.dismiss(); edit(project, ProjectFiles.child(directory, value));
            } catch (Exception error) { name.setError(error.getMessage()); }
        })); show(dialog);
    }
    private void fileActions(ProjectFiles.Project project, String relative) {
        show(new MaterialAlertDialogBuilder(activity).setTitle(relative.substring(relative.lastIndexOf('/') + 1))
            .setItems(new String[]{"查看 / 编辑文本", "导出到手机…"}, (dialog, which) -> {
                if (which == 0) edit(project, relative); else pick(project, relative, EXPORT);
            }).setNegativeButton("返回目录", (dialog, which) -> browse(project, parent(relative), 0)));
    }
    private void edit(ProjectFiles.Project project, String relative) {
        try {
            String original = files.readText(project, relative);
            EditText text = new TextInputEditText(activity); text.setHint("代码内容"); text.setTypeface(Typeface.MONOSPACE);
            text.setGravity(Gravity.TOP); text.setMinLines(8); text.setMaxLines(14); text.setText(original);
            AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle(relative.substring(relative.lastIndexOf('/') + 1)).setView(ui.input(text))
                .setNegativeButton("返回目录", (d, which) -> browse(project, parent(relative), 0))
                .setPositiveButton("保存", null).setNeutralButton("导出已保存文件", (d, which) -> pick(project, relative, EXPORT)).create();
            dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                try { files.saveText(project, relative, original, text.getText().toString()); dialog.dismiss(); toast("已保存到 " + project.title); browse(project, parent(relative), 0); }
                catch (Exception error) { toast(error.getMessage()); }
            })); show(dialog);
        } catch (Exception error) {
            show(new MaterialAlertDialogBuilder(activity).setTitle("无法用文本编辑器打开").setMessage(error.getMessage())
                .setNegativeButton("返回目录", (d, which) -> browse(project, parent(relative), 0))
                .setPositiveButton("导出到手机…", (d, which) -> pick(project, relative, EXPORT)));
        }
    }
    private void pick(ProjectFiles.Project project, String relative, int request) {
        try {
            files.resolve(project, relative);
            pendingProject = project; pendingRelative = relative; pendingRequest = request;
            Intent intent = new Intent(request == IMPORT ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream");
            if (request == IMPORT) intent.setType("*/*");
            else intent.putExtra(Intent.EXTRA_TITLE, relative.substring(relative.lastIndexOf('/') + 1));
            activity.startActivityForResult(intent, request);
        } catch (Exception error) { clearPending(); toast("无法打开系统文件选择器：" + error.getMessage()); }
    }
    private void clearPending() { pendingProject = null; pendingRelative = null; pendingRequest = 0; }
    boolean onActivityResult(int request, int result, Intent data) {
        if (folders.result(request, result, data)) return true;
        if (request != IMPORT && request != EXPORT) return false;
        ProjectFiles.Project project = pendingProject; String relative = pendingRelative; int expected = pendingRequest; clearPending();
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        if (project == null || expected != request) { toast("目标项目记录已失效，请重新选择文件"); return true; }
        AlertDialog progress = show(new MaterialAlertDialogBuilder(activity).setTitle(request == IMPORT ? "正在导入文件…" : "正在导出文件…")
            .setMessage(project.title).setCancelable(false));
        worker.execute(() -> {
            String errorMessage = null;
            try {
                if (request == IMPORT) {
                    String name = "import-" + System.currentTimeMillis();
                    try (android.database.Cursor cursor = activity.getContentResolver().query(data.getData(), new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                    }
                    try (InputStream input = activity.getContentResolver().openInputStream(data.getData())) {
                        if (input == null) throw new IOException("无法读取所选文件");
                        files.importFile(project, relative, name, input, cache);
                    }
                } else {
                    // Check the source before opening a destination that a document provider may truncate.
                    if (!files.resolve(project, relative).isFile()) throw new IOException("原文件已不存在，未导出");
                    try (OutputStream output = activity.getContentResolver().openOutputStream(data.getData(), "wt")) {
                        if (output == null) throw new IOException("无法写入所选位置");
                        files.exportFile(project, relative, output);
                    }
                }
            } catch (Exception error) { errorMessage = error.getMessage(); }
            String failure = errorMessage;
            activity.runOnUiThread(() -> {
                if (closed || activity.isDestroyed()) return;
                progress.dismiss(); toast(failure != null ? failure : request == IMPORT ? "已导入到 " + project.title : "已导出，项目内原文件保留");
                browse(project, request == IMPORT ? relative : parent(relative), 0);
            });
        });
        return true;
    }
}
