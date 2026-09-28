package ai.mengluo.dsh.android;

import java.io.*;
import java.net.*;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Download pinned base archives once; never replace an existing rootfs during a Harness update. */
final class RuntimeAssets {
    static File obtain(File cache, JSONObject asset, DownloadSource source, Consumer<String> progress) throws Exception {
        String name = asset.getString("name"), official = asset.getString("url"), hash = asset.getString("sha256");
        if (!(name.equals("ubuntu-base.tgz") || name.equals("node-linux.tgz")) || !hash.matches("[a-f0-9]{64}") || !allowed(official)) throw new IOException("基础环境清单无效");
        File archive = new File(cache, BuildConfig.RUNTIME_ABI + "-" + name);
        if (archive.isFile()) {
            try { ArchiveInstaller.verify(archive, hash); progress.accept("复用已校验的 " + name); return archive; }
            catch (IOException invalid) { if (!archive.delete()) throw invalid; }
        }
        String mirror = official;
        if (source == DownloadSource.MIRROR) mirror = official.replace("https://nodejs.org/dist/", "https://npmmirror.com/mirrors/node/")
            .replace("https://cdimage.ubuntu.com/", "https://mirrors.ustc.edu.cn/ubuntu-cdimage/");
        try { download(mirror, archive, hash, name, progress); }
        catch (Exception error) {
            if (mirror.equals(official)) throw error;
            progress.accept("基础环境镜像不可用，改用官方地址下载同一校验版本 · " + name);
            download(official, archive, hash, name, progress);
        }
        return archive;
    }
    private static boolean allowed(String value) {
        try {
            URI u = new URI(value);
            return UpdatePolicy.cleanHttps(u) && java.util.Set.of("nodejs.org", "cdimage.ubuntu.com", "mirrors.ustc.edu.cn",
                "npmmirror.com", "registry.npmmirror.com", "cdn.npmmirror.com", "cnpmjs.org").contains(u.getHost());
        } catch (Exception invalid) { return false; }
    }
    private static void download(String url, File target, String sha256, String name, Consumer<String> progress) throws Exception {
        HttpURLConnection c = UpdateHttp.open(url, RuntimeAssets::allowed);
        File partial = new File(target.getPath() + ".partial");
        long expected = c.getContentLengthLong(), total = 0, last = 0, deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(30);
        try {
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(partial)) {
                byte[] b = new byte[64 * 1024]; int n;
                while ((n = in.read(b)) != -1) {
                    total += n;
                    if (total > 256L * 1024 * 1024 || System.nanoTime() > deadline) throw new IOException("基础环境下载过大或超时");
                    out.write(b, 0, n);
                    if (System.currentTimeMillis() - last > 250) {
                        last = System.currentTimeMillis();
                        progress.accept(String.format(java.util.Locale.ROOT, "下载 %s · %.1f MB%s", name, total / 1048576d, expected > 0 ? " / " + (expected / 1048576) + " MB" : ""));
                    }
                }
                out.getFD().sync();
            }
            if (expected > 0 && total != expected) throw new IOException("基础环境下载不完整");
            ArchiveInstaller.verify(partial, sha256);
            if (!partial.renameTo(target)) throw new IOException("无法保存基础环境");
        } finally { c.disconnect(); partial.delete(); }
    }
}
