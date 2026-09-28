package ai.mengluo.dsh.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Token exchange is a same-origin cookie redirect on current official Harness. */
final class HarnessPage {
    static void check(String launchUrl) throws IOException {
        URL target = new URL(launchUrl);
        Map<String, String> cookies = new LinkedHashMap<>();
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!RuntimePolicy.trustedPage(target.toString(), launchUrl)) throw new IOException("Harness 登录跳转超出本地服务范围");
            HttpURLConnection connection = (HttpURLConnection) target.openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(5_000); connection.setReadTimeout(5_000); connection.setInstanceFollowRedirects(false);
            if (!cookies.isEmpty()) connection.setRequestProperty("Cookie", String.join("; ", cookies.values()));
            try {
                int code = connection.getResponseCode();
                if (code == 200) {
                    try (InputStream input = connection.getInputStream(); ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192]; int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (body.size() + count > 2 * 1024 * 1024) throw new IOException("Harness 页面过大");
                            body.write(buffer, 0, count);
                        }
                        if (!body.toString(StandardCharsets.UTF_8.name()).contains("<title>DeepSeek Harness</title>")) throw new IOException("本地服务未返回 Harness 页面");
                    }
                    return;
                }
                if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) throw new IOException("Harness 页面返回 HTTP " + code);
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IOException("Harness 登录跳转缺少目标");
                URL next = new URL(target, location);
                if (!RuntimePolicy.trustedPage(next.toString(), launchUrl)) throw new IOException("Harness 登录跳转超出本地服务范围");
                for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                    if (!"Set-Cookie".equalsIgnoreCase(header.getKey())) continue;
                    for (String value : header.getValue()) {
                        String pair = value.split(";", 2)[0]; int separator = pair.indexOf('=');
                        if (separator > 0 && pair.indexOf('\r') < 0 && pair.indexOf('\n') < 0) cookies.put(pair.substring(0, separator), pair);
                    }
                }
                target = next;
            } finally { connection.disconnect(); }
        }
        throw new IOException("Harness 登录跳转次数过多");
    }
}
