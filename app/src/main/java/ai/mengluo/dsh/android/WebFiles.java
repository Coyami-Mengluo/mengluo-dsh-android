package ai.mengluo.dsh.android;

import android.net.Uri;
import android.webkit.WebView;
import androidx.webkit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Origin-bound requests only. A page can request a native confirmation, never read or receive a file. */
final class WebFiles implements AutoCloseable {
    private static final String NAME = "MengLuoFiles";
    private final WebView web;
    private final Supplier<String> ready;
    private final SystemFiles files;
    private final Runnable browse;
    private ScriptHandler script;
    private String source;
    private boolean registered;

    WebFiles(WebView web, Supplier<String> ready, SystemFiles files, Runnable browse) {
        this.web = web; this.ready = ready; this.files = files; this.browse = browse;
    }
    void install(String launchUrl) throws IOException {
        close();
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
        String origin = "http://127.0.0.1:" + Uri.parse(launchUrl).getPort();
        try (InputStream input = web.getContext().getAssets().open("file-open.js")) { source = new String(IO.bytes(input), StandardCharsets.UTF_8); }
        WebViewCompat.addWebMessageListener(web, NAME, Set.of(origin), (view, message, sourceOrigin, mainFrame, reply) -> {
            String active = ready.get();
            if (!mainFrame || active == null || !RuntimePolicy.trustedPage(sourceOrigin.toString(), active)
                || !RuntimePolicy.trustedPage(view.getUrl(), active) || message.getType() != WebMessageCompat.TYPE_STRING) return;
            String value = message.getData(); if (value == null || value.length() > 8192) return;
            try {
                JSONObject data = new JSONObject(value);
                if ("open".equals(data.optString("action"))) files.open(data.getString("path"), true);
                else if ("browse".equals(data.optString("action"))) browse.run();
            } catch (org.json.JSONException invalid) { /* Malformed page requests have no native effect. */ }
        });
        registered = true;
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            script = WebViewCompat.addDocumentStartJavaScript(web, source, Set.of(origin));
    }
    void finished(String url) {
        String active = ready.get();
        if (registered && source != null && active != null && RuntimePolicy.trustedPage(url, active)) web.evaluateJavascript(source, null);
    }
    @Override public void close() {
        if (script != null) { script.remove(); script = null; }
        if (registered) { WebViewCompat.removeWebMessageListener(web, NAME); registered = false; }
        source = null;
    }
}
