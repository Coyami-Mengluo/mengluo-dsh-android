package ai.mengluo.dsh.android;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Check the exact versions, not just a registry homepage responding with HTTP 200. */
final class RegistryClient {
    static final String PNPM_VERSION = "11.7.0";
    static final String NAME = "@deepseek-ai/dsh";
    static final class Release {
        final String version, integrity;
        Release(String version, String integrity) { this.version = UpdatePolicy.version(version); this.integrity = integrity; }
    }
    static List<Release> catalog() throws Exception {
        JSONObject root = new JSONObject(UpdateHttp.text("https://registry.npmjs.org/@deepseek-ai%2fdsh",
            url -> url.startsWith("https://registry.npmjs.org/"), 16 * 1024 * 1024));
        if (!NAME.equals(root.getString("name"))) throw new IOException("官方版本目录不匹配");
        JSONObject versions = root.getJSONObject("versions"); ArrayList<Release> result = new ArrayList<>();
        for (Iterator<String> i = versions.keys(); i.hasNext();) {
            String version = i.next(); JSONObject pkg = versions.getJSONObject(version);
            if (!pkg.optString("deprecated").isEmpty()) continue;
            try { result.add(release(pkg)); } catch (Exception invalid) { /* Do not offer incomplete historical releases. */ }
        }
        result.sort((a, b) -> UpdatePolicy.compare(b.version, a.version));
        if (result.isEmpty()) throw new IOException("官方暂未提供可安装版本");
        return Collections.unmodifiableList(result);
    }
    static Release official(String version) throws Exception {
        return release(new JSONObject(UpdateHttp.text(DownloadSource.OFFICIAL.metadataUrl(NAME, version),
            url -> url.startsWith("https://registry.npmjs.org/"), 1024 * 1024)));
    }
    static Release release(JSONObject pkg) throws Exception {
        String version = UpdatePolicy.version(pkg.getString("version"));
        JSONObject dist = pkg.getJSONObject("dist"); String integrity = dist.getString("integrity");
        if (!NAME.equals(pkg.getString("name")) || !integrity.matches("sha512-[A-Za-z0-9+/]{86}==")
            || !("https://registry.npmjs.org/@deepseek-ai/dsh/-/dsh-" + version + ".tgz").equals(dist.getString("tarball")))
            throw new IOException("官方包校验信息不完整");
        return new Release(version, integrity);
    }
    static void check(DownloadSource source) throws IOException {
        checkVersion(source, "@deepseek-ai/dsh", RuntimePolicy.HARNESS_VERSION);
        checkVersion(source, "pnpm", PNPM_VERSION);
    }
    static void checkVersion(DownloadSource source, String name, String version) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(source.metadataUrl(name, version)).openConnection();
        connection.setConnectTimeout(12_000); connection.setReadTimeout(12_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int code = connection.getResponseCode();
            if (code == 404) throw new IOException(source == DownloadSource.MIRROR
                ? "镜像尚未同步 " + name + " " + version + "，请稍后重试或切换官方源"
                : "官方源未找到 " + name + " " + version);
            if (code != 200) throw new IOException(source.title + "返回 HTTP " + code + "，请重试或切换下载源");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 1024 * 1024) throw new IOException("版本信息过大，已停止读取");
                    bytes.write(buffer, 0, count);
                }
                JSONObject metadata = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
                JSONObject dist = metadata.getJSONObject("dist");
                URI tarball = new URI(dist.getString("tarball"));
                if (!name.equals(metadata.optString("name")) || !version.equals(metadata.optString("version"))
                    || !"https".equals(tarball.getScheme()) || tarball.getHost() == null || tarball.getUserInfo() != null
                    || dist.optString("integrity").isBlank()) throw new IOException("下载源的版本或校验信息不完整，已停止安装");
            }
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("无法解析下载源版本信息", error); }
        finally { connection.disconnect(); }
    }
}
