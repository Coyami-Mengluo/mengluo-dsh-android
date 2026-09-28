package ai.mengluo.dsh.android;

import java.io.File;
import java.io.IOException;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.net.URI;
import java.util.regex.Pattern;

/** Pure validation shared by the Android runtime and JVM regression tests. */
public final class RuntimePolicy {
    private RuntimePolicy() {}
    public static final String HARNESS_VERSION = "0.1.7-rc.2";
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?");
    public static String exactVersion(String value) {
        if (!VERSION.matcher(value).matches()) throw new IllegalArgumentException("请输入完整版本号");
        return value;
    }
    public static File inside(File root, String relative) throws IOException {
        if (relative == null || relative.indexOf('\0') >= 0 || relative.indexOf('\\') >= 0 || relative.startsWith("/"))
            throw new IOException("拒绝无效路径");
        File canonicalRoot = root.getCanonicalFile();
        File file = new File(root, relative).getCanonicalFile();
        if (!file.toPath().startsWith(canonicalRoot.toPath())) throw new IOException("拒绝工作区外路径");
        return file;
    }
    public static boolean trustedPage(String candidate, String ready) {
        try {
            URI url = new URI(candidate), base = new URI(ready);
            return "http".equals(url.getScheme()) && "127.0.0.1".equals(url.getHost())
                && url.getPort() > 0 && url.getPort() == base.getPort() && url.getUserInfo() == null;
        } catch (Exception ignored) { return false; }
    }
    public static String redact(String text) {
        return text.replaceAll("(?i)([?&#]token=)[^\\s&#\"'<>]+", "$1[REDACTED]")
            .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*", "$1[REDACTED]")
            .replaceAll("sk-[A-Za-z0-9_-]{12,}", "[REDACTED]");
    }
    public static String quote(String text) { return "'" + text.replace("'", "'\"'\"'") + "'"; }
    static void requireElf64(File file, String abi) throws IOException {
        int machine;
        if ("arm64-v8a".equals(abi)) machine = 183;
        else if ("x86_64".equals(abi)) machine = 62;
        else throw new IOException("不支持的运行架构：" + abi);
        byte[] header = new byte[20];
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) { input.readFully(header); }
        int actual = (header[18] & 255) | ((header[19] & 255) << 8);
        if (header[0] != 127 || header[1] != 'E' || header[2] != 'L' || header[3] != 'F'
            || header[4] != 2 || header[5] != 1 || actual != machine)
            throw new IOException("运行环境架构不匹配（需要 " + abi + "）：" + file.getName());
    }
}
