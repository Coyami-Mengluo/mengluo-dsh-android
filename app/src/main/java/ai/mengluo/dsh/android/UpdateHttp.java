package ai.mengluo.dsh.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/** Bounded HTTPS requests. Uses Android's default proxy selector; no embedded proxy. */
final class UpdateHttp {
    static HttpURLConnection open(String url, Predicate<String> allowed) throws IOException {
        for (int i = 0; i <= 5; i++) {
            if (!allowed.test(url)) throw new IOException("更新地址不在可信发布源中");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15_000); c.setReadTimeout(30_000); c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "MengLuo-DSH-Android/" + BuildConfig.VERSION_NAME);
            c.setRequestProperty("Accept", url.startsWith("https://api.github.com/") && url.contains("/releases/assets/")
                ? "application/octet-stream" : "application/json,application/octet-stream;q=0.9,*/*;q=0.8");
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            int status;
            try { status = c.getResponseCode(); } catch (IOException e) { c.disconnect(); throw e; }
            if (status == 200) return c;
            String location = c.getHeaderField("Location"); c.disconnect();
            if ((status == 301 || status == 302 || status == 303 || status == 307 || status == 308) && location != null) {
                url = URI.create(url).resolve(location).toString(); continue;
            }
            if (status == 404) throw new IOException("发布源暂未提供该版本（HTTP 404），请稍后再试");
            if (status == 403 || status == 429) throw new IOException("发布源暂时限制请求，请稍后重试；不会自动反复请求");
            throw new IOException("更新请求失败：HTTP " + status);
        }
        throw new IOException("更新请求重定向过多");
    }
    static String text(String url, Predicate<String> allowed, int limit) throws IOException {
        HttpURLConnection c = open(url, allowed);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) != -1) { if (out.size() + n > limit) throw new IOException("更新信息过大"); out.write(b, 0, n); }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally { c.disconnect(); }
    }
}
