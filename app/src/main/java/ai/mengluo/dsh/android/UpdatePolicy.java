package ai.mengluo.dsh.android;

import java.net.URI;
import java.math.BigInteger;
import java.util.regex.Pattern;

/** Pure policy, shared by both update lines. No credentials or WebView input. */
final class UpdatePolicy {
    static final String REPOSITORY = "Coyami-Mengluo/mengluo-dsh-android";
    static final String FEED = "https://github.com/" + REPOSITORY + "/releases/latest/download/android-update.json";
    static final long DAY = 24L * 60 * 60 * 1000;
    private static final Pattern VERSION = Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-((?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][A-Za-z0-9-]*)(?:\\.(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][A-Za-z0-9-]*))*))?");
    static String version(String value) {
        if (value == null || value.length() > 100 || !VERSION.matcher(value).matches()) throw new IllegalArgumentException("无效版本号");
        return value;
    }
    static int compare(String left, String right) {
        String[] a = version(left).split("-", 2), b = version(right).split("-", 2);
        String[] an = a[0].split("\\."), bn = b[0].split("\\.");
        for (int i = 0; i < 3; i++) { int c = new BigInteger(an[i]).compareTo(new BigInteger(bn[i])); if (c != 0) return c; }
        if (a.length != b.length) return a.length == 1 ? 1 : -1;
        if (a.length == 1) return 0;
        an = a[1].split("\\."); bn = b[1].split("\\.");
        for (int i = 0; i < Math.min(an.length, bn.length); i++) {
            boolean x = an[i].matches("[0-9]+"), y = bn[i].matches("[0-9]+");
            int c = x && y ? new BigInteger(an[i]).compareTo(new BigInteger(bn[i])) : x != y ? (x ? -1 : 1) : an[i].compareTo(bn[i]);
            if (c != 0) return c;
        }
        return Integer.compare(an.length, bn.length);
    }
    static boolean due(long checked, long now, long interval) { return checked <= 0 || checked > now || now - checked >= interval; }
    static boolean releaseUrl(String value) {
        try {
            URI u = new URI(value);
            return cleanHttps(u) && "github.com".equals(u.getHost())
                && u.getRawPath().matches("/" + REPOSITORY + "/releases/download/v[0-9A-Za-z.-]+/[A-Za-z0-9._-]+\\.apk")
                && u.getQuery() == null;
        } catch (Exception invalid) { return false; }
    }
    static boolean cleanHttps(URI u) {
        return "https".equals(u.getScheme()) && u.getHost() != null && u.getUserInfo() == null
            && (u.getPort() == -1 || u.getPort() == 443) && u.getFragment() == null && u.normalize().equals(u);
    }
    static boolean githubTransport(String value) {
        try {
            URI u = new URI(value);
            return cleanHttps(u) && ("github.com".equals(u.getHost()) && u.getRawPath().startsWith("/" + REPOSITORY + "/releases/")
                || "api.github.com".equals(u.getHost()) && u.getRawPath().matches("/repos/" + REPOSITORY + "/releases/(?:latest|tags/v[0-9A-Za-z.-]+|assets/[0-9]+)") && u.getQuery() == null
                || "release-assets.githubusercontent.com".equals(u.getHost()) || "objects.githubusercontent.com".equals(u.getHost()));
        } catch (Exception invalid) { return false; }
    }
}
