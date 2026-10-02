package ai.mengluo.dsh.android;

import android.net.Uri;
import android.webkit.WebView;
import androidx.webkit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Main-frame, exact-runtime-origin bridge. No task content or arbitrary notification text is accepted. */
final class WebTaskEvents implements AutoCloseable {
    private static final String NAME = "MengLuoTaskEvents";
    private static final Set<String> ROUTES = Set.of("/api/remote.mux", "/api/events.host", "/api/events.mux");
    private final WebView web;
    private final Supplier<String> ready;
    private final TaskNotifications notifications;
    private final Map<String, String> channels = new HashMap<>();
    private final Set<String> connectedChannels = new java.util.HashSet<>();
    private ScriptHandler script;
    private boolean registered;

    WebTaskEvents(WebView web, Supplier<String> ready, TaskNotifications notifications) {
        this.web = web; this.ready = ready; this.notifications = notifications;
    }
    boolean connected() { return !connectedChannels.isEmpty(); }
    boolean install(String launchUrl) throws IOException {
        close(); notifications.runtime(launchUrl);
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
            || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false;
        String source;
        try (InputStream input = web.getContext().getAssets().open("task-events.js")) { source = new String(IO.bytes(input), StandardCharsets.UTF_8); }
        String origin = "http://127.0.0.1:" + Uri.parse(launchUrl).getPort();
        WebViewCompat.addWebMessageListener(web, NAME, Set.of(origin), (view, message, sourceOrigin, mainFrame, reply) -> {
            String active = ready.get();
            if (!mainFrame || active == null || !RuntimePolicy.trustedPage(sourceOrigin.toString(), active)
                || !RuntimePolicy.trustedPage(view.getUrl(), active) || message.getType() != WebMessageCompat.TYPE_STRING) return;
            String value = message.getData(); if (value == null || value.length() > 2048) return;
            try { receive(new JSONObject(value)); }
            catch (org.json.JSONException malformed) { /* Unknown data has no native effect. */ }
        });
        registered = true;
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            script = WebViewCompat.addDocumentStartJavaScript(web, source, Set.of(origin));
        return true;
    }
    private void receive(JSONObject message) {
        String kind = message.optString("kind"), channel = message.optString("channel");
        if (!validId(channel)) return;
        if ("reset".equals(kind)) {
            String route = message.optString("route"); if (!ROUTES.contains(route)) return;
            String previous = channels.put(route, channel); connectedChannels.remove(previous); notifications.state.disconnected(); return;
        }
        if (!channels.containsValue(channel)) return;
        if ("disconnected".equals(kind)) { channels.values().removeIf(channel::equals); connectedChannels.remove(channel); notifications.state.disconnected(); return; }
        if ("connected".equals(kind)) { connectedChannels.add(channel); return; }
        String session = message.optString("session"), request = message.optString("request");
        if ("resolved".equals(kind)) {
            if (validId(request)) {
                String affected = notifications.state.resolved(request);
                if (affected != null && !notifications.state.waitingFor(affected)) notifications.cancel(affected);
            }
            return;
        }
        if (!validId(session)) return;
        switch (kind) {
            case "status" -> {
                if (!(message.opt("running") instanceof Boolean)) return;
                boolean running = message.optBoolean("running");
                if (running) notifications.cancel(session);
                notifications.show(session, notifications.state.status(session, running));
            }
            case "summary" -> notifications.state.summary(session, message.optBoolean("subagent", false));
            case "error" -> { notifications.state.error(session); notifications.cancel(session); }
            case "removed" -> { notifications.state.removed(session); notifications.cancel(session); }
            case "waiting" -> { if (validId(request)) notifications.show(session, notifications.state.waiting(session, request)); }
            default -> { }
        }
    }
    private static boolean validId(String value) { return !value.isEmpty() && value.length() <= 256 && value.chars().noneMatch(Character::isISOControl); }
    @Override public void close() {
        if (script != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) script.remove();
        script = null;
        if (registered && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(web, NAME);
        registered = false;
        channels.clear(); connectedChannels.clear(); notifications.state.disconnected();
    }
}
