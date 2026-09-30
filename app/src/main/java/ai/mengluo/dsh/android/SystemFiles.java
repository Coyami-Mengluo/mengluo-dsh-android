package ai.mengluo.dsh.android;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Shares only a user-selected, read-only snapshot, never a runtime directory or WebView file URL. */
final class SystemFiles implements AutoCloseable {
    static final long MAX_BYTES = 128L * 1024 * 1024;
    private final Activity activity;
    private final ProjectFiles files;
    private final File shareRoot;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AlertDialog dialog;
    private volatile boolean closed;
    private boolean busy;

    SystemFiles(Activity activity, ProjectFiles files) {
        this(activity, files, new File(activity.getCacheDir(), "open-files"));
    }
    SystemFiles(Activity activity, ProjectFiles files, File shareRoot) {
        this.activity = activity; this.files = files; this.shareRoot = shareRoot;
    }
    static String absolutePath(String value) throws IOException {
        String path = ProjectFiles.guestPath(value);
        if (path.equals("/") || ProjectFiles.runtimePath(path)) throw new IOException("请选择项目文件，不支持运行环境或配置文件");
        return path;
    }
    private File resolve(String path) throws IOException {
        int slash = path.lastIndexOf('/');
        if (slash <= 0) throw new IOException("请选择具体项目中的文件");
        return files.resolve(new ProjectFiles.Project("文件", path.substring(0, slash)), path.substring(slash + 1));
    }
    void open(String raw, boolean confirm) {
        if (closed || busy || (dialog != null && dialog.isShowing())) return;
        final String path;
        try { path = absolutePath(raw); }
        catch (IOException error) { toast(error.getMessage()); return; }
        if (!confirm) { prepare(path); return; }
        dialog = new MaterialAlertDialogBuilder(activity).setTitle("使用系统应用打开？")
            .setMessage(path + "\n\n仅向你选择的应用提供此文件的只读副本，不授予整个项目的访问权。")
            .setNegativeButton("取消", null).setPositiveButton("选择应用", (d, w) -> prepare(path)).create();
        dialog.setOnDismissListener(d -> { if (!busy) dialog = null; }); dialog.show();
    }
    private void prepare(String path) {
        if (closed || busy) return;
        busy = true;
        dialog = new MaterialAlertDialogBuilder(activity).setTitle("正在准备文件…").setMessage("原文件不会被修改。")
            .setCancelable(false).create(); dialog.show();
        worker.execute(() -> {
            File copy = null; String failure = null;
            try { copy = snapshot(path); }
            catch (Exception error) { failure = error.getMessage(); }
            File ready = copy; String error = failure;
            activity.runOnUiThread(() -> {
                busy = false;
                if (dialog != null) { dialog.dismiss(); dialog = null; }
                if (closed || activity.isDestroyed() || activity.isFinishing()) return;
                if (error != null) { toast("无法打开文件：" + error); return; }
                try { activity.startActivity(Intent.createChooser(viewIntent(activity, ready), "打开方式")); }
                catch (ActivityNotFoundException missing) { toast("没有可打开此文件的应用，可从“代码文件”导出后打开"); }
                catch (Exception denied) { toast("系统无法打开此文件：" + denied.getMessage()); }
            });
        });
    }
    File snapshot(String path) throws IOException {
        File source = resolve(absolutePath(path));
        if (!Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("文件不存在或不是普通文件");
        if (source.length() > MAX_BYTES) throw new IOException("文件超过 128 MiB，请从代码文件导出后打开");
        Files.createDirectories(shareRoot.toPath());
        cleanup();
        File directory = Files.createTempDirectory(shareRoot.toPath(), "open-").toFile();
        File copy = new File(directory, source.getName());
        boolean complete = false;
        try {
            // Re-resolve after preparing the destination; reject links and path changes again.
            source = resolve(path);
            try (InputStream input = Files.newInputStream(source.toPath(), LinkOption.NOFOLLOW_LINKS);
                 OutputStream output = Files.newOutputStream(copy.toPath(), StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[65536]; long total = 0; int n;
                while ((n = input.read(buffer)) != -1) {
                    if (closed || Thread.currentThread().isInterrupted()) throw new IOException("已取消");
                    total += n; if (total > MAX_BYTES) throw new IOException("文件超过 128 MiB");
                    output.write(buffer, 0, n);
                }
            }
            complete = true; return copy;
        } finally { if (!complete) { Files.deleteIfExists(copy.toPath()); Files.deleteIfExists(directory.toPath()); } }
    }
    private void cleanup() throws IOException {
        File[] directories = shareRoot.listFiles(); if (directories == null) return;
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
        for (File directory : directories) {
            if (!directory.getName().startsWith("open-") || !Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)
                || directory.lastModified() >= cutoff) continue;
            File[] copies = directory.listFiles(); if (copies == null) continue;
            for (File copy : copies) if (Files.isRegularFile(copy.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(copy.toPath());
            if (directory.list() != null && directory.list().length == 0) Files.deleteIfExists(directory.toPath());
        }
    }
    static String mimeType(String name) {
        int dot = name.lastIndexOf('.'); String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        if (type != null) return type;
        return switch (extension) {
            case "md", "log", "py", "js", "ts", "java", "kt", "json", "yaml", "yml", "sh", "css" -> "text/plain";
            default -> "application/octet-stream";
        };
    }
    static Intent viewIntent(Context context, File copy) {
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".files", copy);
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType(copy.getName()))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("项目文件", uri)); return intent;
    }
    private void toast(String message) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }
    @Override public void close() { closed = true; worker.shutdownNow(); if (dialog != null) dialog.dismiss(); dialog = null; }
}
