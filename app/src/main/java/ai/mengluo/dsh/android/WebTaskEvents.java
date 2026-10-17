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
import org.json.JSONArray;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.List;

/** Exact-runtime-origin event bridge; question replies require an explicit native form submission. */
final class WebTaskEvents implements AutoCloseable {
    private static final String NAME = "MengLuoTaskEvents";
    private static final Set<String> ROUTES = Set.of("/api/remote.mux", "/api/events.host", "/api/events.mux");
    private final WebView web;
    private final Supplier<String> ready;
    private final TaskNotifications notifications;
    private final PhoneControl phone;
    private final Map<String, String> channels = new HashMap<>();
    private final Set<String> connectedChannels = new java.util.HashSet<>();
    private ScriptHandler script;
    private boolean registered;

    WebTaskEvents(WebView web, Supplier<String> ready, TaskNotifications notifications) {
        this.web = web; this.ready = ready; this.notifications = notifications; phone = PhoneControl.get(web.getContext());
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
            String value = message.getData(); if (value == null || value.length() > 65536) return;
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
            String previous = channels.put(route, channel); connectedChannels.remove(previous);
            if (previous != null) phone.webQuestionsDisconnected(previous);
            notifications.state.disconnected(); return;
        }
        if (!channels.containsValue(channel)) return;
        if ("disconnected".equals(kind)) { channels.values().removeIf(channel::equals); connectedChannels.remove(channel); phone.webQuestionsDisconnected(channel); notifications.state.disconnected(); return; }
        if ("connected".equals(kind)) { connectedChannels.add(channel); return; }
        String session = message.optString("session"), request = message.optString("request");
        if ("resolved".equals(kind)) {
            if (validId(request)) {
                phone.webQuestionResolved(channel, request);
                String affected = notifications.state.resolved(request);
                if (affected != null && !notifications.state.waitingFor(affected)) notifications.cancel(affected);
            }
            return;
        }
        if ("question-failed".equals(kind)) { if (validId(request)) phone.webQuestionFailed(channel, request); return; }
        if (!validId(session)) return;
        switch (kind) {
            case "status" -> {
                if (!(message.opt("running") instanceof Boolean)) return;
                boolean running = message.optBoolean("running");
                if (running) notifications.cancel(session);
                notifications.show(session, notifications.state.status(session, running));
            }
            case "summary" -> notifications.state.summary(session, message.optBoolean("subagent", false));
            case "error" -> { notifications.state.error(session); notifications.cancel(session); phone.webSessionEnded(session); }
            case "removed" -> { notifications.state.removed(session); notifications.cancel(session); phone.webSessionEnded(session); }
            case "waiting" -> { if (validId(request)) notifications.show(session, notifications.state.waiting(session, request)); }
            case "question" -> {
                if (!validId(request) || !connectedChannels.contains(channel)) return;
                try {
                    PhoneQuestion form = parseQuestion(request, message.getJSONArray("questions"));
                    phone.offerWebQuestion(channel, session, form, answers -> submitQuestion(channel, form, answers));
                } catch (JSONException | IllegalArgumentException malformed) { /* Keep unsupported requests in the official UI. */ }
            }
            default -> { }
        }
    }
    private static PhoneQuestion parseQuestion(String id, JSONArray questions) throws JSONException {
        if (questions.length() < 1 || questions.length() > 4) throw new IllegalArgumentException("invalid_question");
        List<PhoneQuestion.Item> items = new ArrayList<>();
        for (int i = 0; i < questions.length(); i++) {
            JSONObject q = questions.getJSONObject(i); List<PhoneQuestion.Option> options = new ArrayList<>();
            JSONArray choices = q.has("options") ? q.getJSONArray("options") : new JSONArray();
            for (int j = 0; j < choices.length(); j++) {
                JSONObject option = choices.getJSONObject(j);
                options.add(new PhoneQuestion.Option(requiredText(option, "label"), optionalText(option, "description")));
            }
            if (q.has("multiSelect") && !(q.opt("multiSelect") instanceof Boolean)) throw new IllegalArgumentException("invalid_question");
            items.add(new PhoneQuestion.Item(requiredText(q, "id"), optionalText(q, "header"), requiredText(q, "question"), options,
                q.optBoolean("multiSelect"), optionalText(q, "detail")));
        }
        return new PhoneQuestion(id, items);
    }
    private static String requiredText(JSONObject value, String key) {
        if (!(value.opt(key) instanceof String text)) throw new IllegalArgumentException("invalid_question");
        return text;
    }
    private static String optionalText(JSONObject value, String key) { return value.has(key) ? requiredText(value, key) : ""; }
    private void submitQuestion(String channel, PhoneQuestion form, Map<String, PhoneQuestion.Answer> answers) {
        String active = ready.get();
        if (!channels.containsValue(channel) || !connectedChannels.contains(channel) || active == null || !RuntimePolicy.trustedPage(web.getUrl(), active)) {
            phone.webQuestionResolved(channel, form.id); return;
        }
        JSONArray encoded = new JSONArray();
        try {
            for (PhoneQuestion.Item item : form.items) {
                PhoneQuestion.Answer answer = answers.get(item.id());
                encoded.put(new JSONObject().put("id", item.id()).put("selected", new JSONArray(answer.selected())).put("custom", answer.text()));
            }
            JSONObject message = new JSONObject().put("channel", channel).put("request", form.id).put("answers", encoded);
            web.evaluateJavascript("!!(window.__mengluoReplyQuestion && window.__mengluoReplyQuestion(" + message + "))", result -> {
                if (!"true".equals(result)) phone.webQuestionResolved(channel, form.id);
            });
        } catch (JSONException | RuntimeException error) { phone.webQuestionFailed(channel, form.id); }
    }
    private static boolean validId(String value) { return !value.isEmpty() && value.length() <= 256 && value.chars().noneMatch(Character::isISOControl); }
    @Override public void close() {
        for (String channel : channels.values()) phone.webQuestionsDisconnected(channel);
        if (script != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) script.remove();
        script = null;
        if (registered && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(web, NAME);
        registered = false;
        channels.clear(); connectedChannels.clear(); notifications.state.disconnected();
    }
}
