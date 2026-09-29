package ai.mengluo.dsh.android;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;

/** SAF export: the user chooses one destination; no broad storage permission is required. */
final class LogExporter {
    static final int EXPORT = 32;
    private final Activity activity;
    private final Engine engine;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean pending, closed;
    LogExporter(Activity activity, Engine engine, Bundle saved) {
        this.activity = activity; this.engine = engine;
        pending = saved != null && saved.getBoolean("logs.export-pending", false);
    }
    static Intent intent() {
        return new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
            .putExtra(Intent.EXTRA_TITLE, "MengLuo-DSH-Android-" + BuildConfig.VERSION_NAME + "-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".log");
    }
    void start() {
        if (pending) return;
        try { pending = true; activity.startActivityForResult(intent(), EXPORT); }
        catch (ActivityNotFoundException missing) { pending = false; toast("未找到系统文件保存界面"); }
    }
    void save(Bundle state) { state.putBoolean("logs.export-pending", pending); }
    boolean result(int request, int result, Intent data) {
        if (request != EXPORT) return false;
        if (!pending) return true;
        pending = false;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri uri = data.getData();
        if (!"content".equals(uri.getScheme())) { toast("请选择系统文件保存位置"); return true; }
        ContentResolver resolver = activity.getApplicationContext().getContentResolver();
        worker.execute(() -> {
            String message;
            try {
                byte[] snapshot = engine.exportLogs();
                try (OutputStream out = resolver.openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException("无法打开保存位置");
                    out.write(snapshot);
                }
                message = "日志已导出；分享前请检查是否含私人内容";
            } catch (Exception error) { message = "日志导出失败：" + RuntimePolicy.redact(String.valueOf(error.getMessage())); }
            String completed = message;
            activity.runOnUiThread(() -> { if (!closed && !activity.isDestroyed()) toast(completed); });
        });
        return true;
    }
    private void toast(String message) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }
    void close() { closed = true; worker.shutdown(); }
}
