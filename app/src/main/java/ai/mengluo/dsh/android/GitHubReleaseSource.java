package ai.mengluo.dsh.android;
import java.io.IOException;
import java.net.*;
import org.json.*;

/** Fallback uses the same repository's official asset API, never a third-party GitHub proxy. */
final class GitHubReleaseSource {
    private static final String API = "https://api.github.com/repos/" + UpdatePolicy.REPOSITORY + "/releases/";
    static String feed() throws Exception {
        try { return UpdateHttp.text(UpdatePolicy.FEED, UpdatePolicy::githubTransport, 128 * 1024); }
        catch (IOException directFailed) {
            JSONObject release = metadata("latest");
            return UpdateHttp.text(asset(release, "android-update.json"), UpdatePolicy::githubTransport, 128 * 1024);
        }
    }
    static HttpURLConnection apk(String url) throws Exception {
        if (!UpdatePolicy.releaseUrl(url)) throw new IOException("安装包地址不匹配");
        try { return UpdateHttp.open(url, UpdatePolicy::githubTransport); }
        catch (IOException directFailed) {
            String[] pieces = new URI(url).getPath().split("/");
            String tag = pieces[pieces.length - 2], name = pieces[pieces.length - 1];
            JSONObject release = metadata("tags/" + tag);
            if (!tag.equals(release.getString("tag_name"))) throw new IOException("GitHub 版本标签不匹配");
            return UpdateHttp.open(asset(release, name), UpdatePolicy::githubTransport);
        }
    }
    private static JSONObject metadata(String suffix) throws Exception {
        JSONObject value = new JSONObject(UpdateHttp.text(API + suffix, UpdatePolicy::githubTransport, 1024 * 1024));
        if (value.optBoolean("draft") || value.optBoolean("prerelease")) throw new IOException("不是公开稳定发布");
        return value;
    }
    private static String asset(JSONObject release, String name) throws Exception {
        JSONArray files = release.getJSONArray("assets");
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.getJSONObject(i);
            if (name.equals(item.getString("name"))) {
                String url = item.getString("url");
                if (!url.matches(java.util.regex.Pattern.quote(API) + "assets/[0-9]+")) throw new IOException("GitHub 附件来源异常");
                return url;
            }
        }
        throw new IOException("发布版本暂缺更新附件，请稍后重试");
    }
}
