package ai.mengluo.dsh.android;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import org.json.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Application-scoped checks/downloads; activities only display state and request explicit actions. */
final class AndroidUpdates {
    private static AndroidUpdates instance;
    static synchronized AndroidUpdates get(Context context) {
        if (instance == null) instance = new AndroidUpdates(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Runnable> listeners = new ArrayList<>();
    volatile boolean checkingHarness, checkingApk, downloading;
    volatile String harnessStatus = "点击检查官方版本", apkStatus = "点击检查客户端版本";
    volatile List<RegistryClient.Release> releases = List.of();
    volatile ApkRelease apk;
    volatile int progress;
    private AndroidUpdates(Context context) {
        this.context = context; prefs = context.getSharedPreferences("updates", 0);
        try {
            JSONObject cached = new JSONObject(prefs.getString("apk", "")); apk = ApkRelease.parse(cached);
            apkStatus = apk.code > BuildConfig.VERSION_CODE ? "发现客户端 " + apk.version : "当前客户端 " + BuildConfig.VERSION_NAME;
        } catch (Exception ignored) { }
        try {
            JSONArray values = new JSONArray(prefs.getString("harness", "[]")); ArrayList<RegistryClient.Release> cached = new ArrayList<>();
            for (int i = 0; i < values.length(); i++) {
                JSONObject item = values.getJSONObject(i); String integrity = item.getString("integrity");
                if (!integrity.matches("sha512-[A-Za-z0-9+/]{86}==")) continue;
                cached.add(new RegistryClient.Release(item.getString("version"), integrity));
            }
            releases = List.copyOf(cached); if (!releases.isEmpty()) harnessStatus = "上次检查：" + releases.get(0).version;
        } catch (Exception ignored) { }
    }
    synchronized void listen(Runnable r) { listeners.add(r); r.run(); }
    synchronized void unlisten(Runnable r) { listeners.remove(r); }
    private void changed() { main.post(() -> { synchronized (this) { for (Runnable r : new ArrayList<>(listeners)) r.run(); } }); }
    boolean automatic() { return prefs.getBoolean("automatic", true); }
    void automatic(boolean enabled) { prefs.edit().putBoolean("automatic", enabled).apply(); }
    void automaticCheck() {
        if (!automatic()) return;
        if (UpdatePolicy.due(prefs.getLong("harness-check", 0), System.currentTimeMillis(), UpdatePolicy.DAY)) checkHarness(false);
        if (UpdatePolicy.due(prefs.getLong("apk-check", 0), System.currentTimeMillis(), UpdatePolicy.DAY)) checkApk(false);
    }
    private boolean begin(String key, boolean manual) {
        long now = System.currentTimeMillis();
        if (!UpdatePolicy.due(prefs.getLong(key, 0), now, manual ? 30_000 : UpdatePolicy.DAY)) return false;
        return prefs.edit().putLong(key, now).commit(); // Attempts are throttled too; a network failure never loops.
    }
    synchronized void checkHarness(boolean manual) {
        if (checkingHarness) return;
        if (!begin("harness-check", manual)) { harnessStatus = "请间隔 30 秒再检查；已有版本列表仍可使用"; changed(); return; }
        checkingHarness = true; harnessStatus = "正在获取官方版本目录…"; changed();
        worker.execute(() -> {
            try {
                releases = RegistryClient.catalog(); JSONArray values = new JSONArray();
                for (RegistryClient.Release r : releases) values.put(new JSONObject().put("version", r.version).put("integrity", r.integrity));
                prefs.edit().putString("harness", values.toString()).commit();
                harnessStatus = "官方最新版本 " + releases.get(0).version + " · 共 " + releases.size() + " 个版本";
            } catch (Exception e) { harnessStatus = "检查失败：" + RuntimePolicy.redact(String.valueOf(e.getMessage())) + "；保留已有结果"; }
            finally { checkingHarness = false; changed(); }
        });
    }
    synchronized void checkApk(boolean manual) {
        if (checkingApk || downloading) return;
        if (!begin("apk-check", manual)) { apkStatus = "请间隔 30 秒再检查"; changed(); return; }
        checkingApk = true; apkStatus = "正在检查 GitHub 发布版本…"; changed();
        worker.execute(() -> {
            try {
                JSONObject value = new JSONObject(GitHubReleaseSource.feed());
                ApkRelease target = ApkRelease.parse(value);
                prefs.edit().putString("apk", value.toString()).commit(); apk = target;
                apkStatus = target.code > BuildConfig.VERSION_CODE ? "发现客户端 " + target.version : "客户端已是最新版本";
            } catch (Exception e) { apkStatus = "检查失败：" + RuntimePolicy.redact(String.valueOf(e.getMessage())); }
            finally { checkingApk = false; changed(); }
        });
    }
    boolean available() {
        String current = Engine.get(context).currentVersion();
        boolean harness = releases.stream().anyMatch(r -> UpdatePolicy.compare(r.version, current) > 0 && (current.contains("-") || !r.version.contains("-")));
        return harness || apk != null && apk.code > BuildConfig.VERSION_CODE;
    }
    String takeReminder() {
        String current = Engine.get(context).currentVersion();
        String newer = releases.stream().filter(r -> UpdatePolicy.compare(r.version, current) > 0 && (current.contains("-") || !r.version.contains("-")))
            .map(r -> "Harness " + r.version).findFirst().orElse("");
        if (apk != null && apk.code > BuildConfig.VERSION_CODE) newer += (newer.isEmpty() ? "" : "、") + "客户端 " + apk.version;
        if (newer.isEmpty() || newer.equals(prefs.getString("notified", ""))) return null;
        prefs.edit().putString("notified", newer).apply(); return "发现更新：" + newer;
    }
    static final class ApkRelease {
        final String version, url, sha256, notes;
        final int code, minSdk;
        final long size;
        ApkRelease(String version, int code, int minSdk, String url, String sha256, long size, String notes) {
            this.version = UpdatePolicy.version(version); this.code = code; this.minSdk = minSdk; this.url = url; this.sha256 = sha256; this.size = size; this.notes = notes;
        }
        static ApkRelease parse(JSONObject value) throws Exception {
            if (value.getInt("schema") != 1 || !BuildConfig.APPLICATION_ID.equals(value.getString("applicationId"))) throw new IOException("客户端更新信息不匹配");
            JSONObject file = value.getJSONObject("files").getJSONObject(BuildConfig.RUNTIME_ABI);
            ApkRelease result = new ApkRelease(value.getString("version"), value.getInt("versionCode"), value.getInt("minSdk"),
                file.getString("url"), file.getString("sha256"), file.getLong("size"), value.optString("notes", ""));
            if (result.code <= 0 || result.minSdk < 30 || result.minSdk > Build.VERSION.SDK_INT || !UpdatePolicy.releaseUrl(result.url)
                || !result.sha256.matches("[a-f0-9]{64}") || result.size < 100_000 || result.size > 512L * 1024 * 1024 || result.notes.length() > 8000)
                throw new IOException("客户端更新文件无效或不支持当前系统");
            return result;
        }
    }
    private File apkFile(ApkRelease release) { File dir = new File(context.getFilesDir(), "updates"); dir.mkdirs(); return new File(dir, "client-" + release.sha256 + ".apk"); }
    synchronized void download() {
        ApkRelease target = apk;
        if (downloading || checkingApk || target == null || target.code <= BuildConfig.VERSION_CODE) return;
        downloading = true; progress = 0; apkStatus = "正在下载客户端 " + target.version; changed();
        worker.execute(() -> {
            File destination = apkFile(target), partial = new File(destination.getParentFile(), destination.getName() + ".partial");
            try {
                if (destination.isFile()) { verify(context, destination, target); apkStatus = "已校验，可点击系统确认安装"; progress = 100; return; }
                HttpURLConnection connection = GitHubReleaseSource.apk(target.url);
                long total = 0, lastEvent = 0, deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(30);
                try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[64 * 1024]; int count;
                    while ((count = in.read(buffer)) != -1) {
                        total += count; if (total > target.size || System.nanoTime() > deadline) throw new IOException("下载大小异常或超过 30 分钟");
                        out.write(buffer, 0, count);
                        if (SystemClock.elapsedRealtime() - lastEvent >= 200) {
                            lastEvent = SystemClock.elapsedRealtime(); progress = (int)(total * 100 / target.size);
                            apkStatus = String.format(java.util.Locale.ROOT, "下载客户端 · %.1f / %.1f MB", total / 1048576d, target.size / 1048576d); changed();
                        }
                    }
                    out.getFD().sync();
                } finally { connection.disconnect(); }
                verify(context, partial, target);
                if (!partial.renameTo(destination)) throw new IOException("无法保存已校验的安装包");
                progress = 100; apkStatus = "下载与校验完成，点击系统确认安装";
            } catch (Exception error) { apkStatus = "下载失败：" + RuntimePolicy.redact(String.valueOf(error.getMessage())); partial.delete(); }
            finally { downloading = false; changed(); }
        });
    }
    boolean downloaded() { return apk != null && apkFile(apk).isFile(); }
    void install(Activity activity) throws Exception {
        ApkRelease target = apk;
        if (target == null) throw new IOException("请先检查更新");
        File file = apkFile(target); verify(context, file, target);
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + BuildConfig.APPLICATION_ID)));
            apkStatus = "允许本应用安装更新后，返回并再次点击系统确认安装"; changed(); return;
        }
        Uri uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".updates", file);
        activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }
    static void verify(Context context, File file, ApkRelease release) throws Exception {
        if (file.length() != release.size) throw new IOException("安装包大小校验失败");
        ArchiveInstaller.verify(file, release.sha256);
        PackageManager pm = context.getPackageManager();
        PackageInfo own = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo candidate = pm.getPackageArchiveInfo(file.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (candidate == null || !own.packageName.equals(candidate.packageName) || candidate.getLongVersionCode() != release.code
            || candidate.getLongVersionCode() <= own.getLongVersionCode() || !release.version.equals(candidate.versionName)
            || candidate.applicationInfo == null || candidate.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT
            || (candidate.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            throw new IOException("安装包身份、版本或系统要求不匹配");
        if (candidate.signingInfo == null || own.signingInfo == null || candidate.signingInfo.hasMultipleSigners() || own.signingInfo.hasMultipleSigners()
            || !Arrays.equals(own.signingInfo.getApkContentsSigners()[0].toByteArray(), candidate.signingInfo.getApkContentsSigners()[0].toByteArray()))
            throw new IOException("安装包签名与当前客户端不同，已拒绝安装");
        try (ZipFile zip = new ZipFile(file)) {
            if (zip.getEntry("lib/" + BuildConfig.RUNTIME_ABI + "/libproot.so") == null) throw new IOException("安装包架构不匹配");
        }
    }
}
