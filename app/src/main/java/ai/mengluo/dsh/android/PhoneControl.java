package ai.mengluo.dsh.android;

import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.accessibilityservice.AccessibilityServiceInfo;
import org.json.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** App-owned phone-control gate. All decisions and Android operations are serialized on the main looper. */
final class PhoneControl {
    static final String OPEN = "ai.mengluo.dsh.android.PHONE_CONTROL", MENU = "ai.mengluo.dsh.android.PHONE_MENU", STOP = "ai.mengluo.dsh.android.PHONE_STOP";
    private static PhoneControl instance;
    static synchronized PhoneControl get(Context context) { if (instance == null) instance = new PhoneControl(context.getApplicationContext()); return instance; }
    final Context context;
    final PhoneControlState state = new PhoneControlState();
    final Handler main = new Handler(Looper.getMainLooper());
    private final PhoneOverlay overlay;
    private final Set<String> apps = new HashSet<>();
    private final Set<String> calls = new HashSet<>();
    private WeakReference<MainActivity> activity = new WeakReference<>(null);
    private PhoneHttp server;
    private String secret;
    private boolean runtime, guideNotified;
    private CompletableFuture<JSONObject> pending;
    private Runnable confirmAction;
    private String confirmation = "";
    private record CaptureWait(String owner, String lease, String pkg, CompletableFuture<JSONObject> response) { }
    private CaptureWait captureWait;
    private boolean capturePrompted;
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!runtime) return;
            String expiry = state.expired(SystemClock.elapsedRealtime());
            if (!expiry.isEmpty()) stopNow(expiry);
            else if (state.live() && (!enabled() || PhoneAccess.connected == null || !Settings.canDrawOverlays(context))) stopNow("permission_lost");
            syncUi(); main.postDelayed(this, 1000);
        }
    };
    private PhoneControl(Context context) { this.context = context; overlay = new PhoneOverlay(context, this); }
    static final class Reply extends JSONObject {
        @Override public Reply put(String name, Object value) { try { super.put(name, value); return this; } catch (JSONException e) { throw new IllegalArgumentException(e); } }
        @Override public Reply put(String name, int value) { return put(name, Integer.valueOf(value)); }
        @Override public Reply put(String name, boolean value) { return put(name, Boolean.valueOf(value)); }
    }
    static Reply result(String status) {
        Reply value = new Reply().put("status", status);
        if ("screen_changed".equals(status) || "stale_screenshot".equals(status)) value.put("actionDispatched", false)
            .put("instruction", "The action was NOT executed or dispatched. Take a new screenshot and locate the target again; do not claim a click occurred.");
        return value;
    }
    static boolean supported(String version) {
        // The first verified extension API. Older runtimes still start without the optional phone plugin.
        return version.matches("0\\.2\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?");
    }
    static List<String> webCommand(String cli, int port, boolean includePhone) {
        ArrayList<String> command = new ArrayList<>(List.of("/opt/node/bin/node", "--expose-internals", cli, "web"));
        // Launcher options precede app options; otherwise the web app receives --patch and rejects it.
        if (includePhone) command.addAll(List.of("--patch", "/opt/mengluo-phone/patch.yml"));
        command.addAll(List.of("--host", "127.0.0.1", "--port", Integer.toString(port), "--no-open"));
        return command;
    }
    synchronized List<String> prepare(File rootfs) throws IOException {
        if (server != null) throw new IOException("Phone bridge already running");
        File base = new File(rootfs.getCanonicalFile(), "opt/mengluo-phone");
        if (!base.getCanonicalPath().equals(base.getAbsolutePath())) throw new IOException("Unsafe phone extension directory");
        for (String path : List.of("plugin.mjs", "transport.mjs", "patch.yml", "skills/android-phone/SKILL.md")) {
            File target = new File(base, path);
            if (!target.getCanonicalPath().equals(target.getAbsolutePath())) throw new IOException("Unsafe phone extension asset");
            if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new IOException("Cannot create phone extension directory");
            try (InputStream input = context.getAssets().open("phone/" + path)) { IO.atomicText(target, new String(IO.bytes(input), StandardCharsets.UTF_8)); }
        }
        byte[] random = new byte[32]; new java.security.SecureRandom().nextBytes(random);
        StringBuilder hex = new StringBuilder(); for (byte value : random) hex.append(String.format(Locale.ROOT, "%02x", value & 255)); secret = hex.toString();
        server = new PhoneHttp(secret, this::request);
        int port = server.port();
        main.post(() -> { runtime = true; guideNotified = false; main.removeCallbacks(watchdog); watchdog.run(); });
        return List.of("ML_PHONE_URL=http://127.0.0.1:" + port + "/v1", "ML_PHONE_TOKEN=" + secret);
    }
    void runtimeStopped() {
        synchronized (this) { if (server != null) server.close(); server = null; secret = null; }
        main.post(() -> { runtime = false; stopNow("runtime_stopped"); main.removeCallbacks(watchdog); overlay.hide(); calls.clear(); });
    }
    boolean enabled() {
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        ComponentName expected = new ComponentName(context, PhoneAccess.class);
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            android.content.pm.ServiceInfo service = info.getResolveInfo().serviceInfo;
            if (expected.equals(new ComponentName(service.packageName, service.name))) return true;
        }
        return false;
    }
    String permissionSummary() {
        return "无障碍：" + (enabled() ? PhoneAccess.connected != null ? "已连接" : "已开启，等待系统连接" : "未授权")
            + "\n跨应用悬浮窗：" + (Settings.canDrawOverlays(context) ? "已授权" : "未授权")
            + "\n兼容截图：" + (PhoneCaptureService.available() ? "本次屏幕共享已授权" : "仅在无障碍截图全黑时请求系统授权")
            + "\n运行环境：" + (runtime ? "手机控制桥接已启动" : "启动受支持的 Harness 0.2 系列后接入");
    }
    void foreground(MainActivity owner) { activity = new WeakReference<>(owner); permissionsChanged(); }
    void background(MainActivity owner) { if (activity.get() == owner) activity.clear(); syncUi(); }
    void permissionsChanged() { main.post(() -> { if (state.live() && (!enabled() || PhoneAccess.connected == null || !Settings.canDrawOverlays(context))) stopNow("permission_lost"); syncUi(); }); }
    void stop(String reason) { if (Looper.myLooper() == Looper.getMainLooper()) stopNow(reason); else main.post(() -> stopNow(reason)); }
    private void stopNow(String reason) {
        String closed = state.close(reason);
        confirmAction = null; confirmation = ""; captureWait = null; apps.clear();
        PhoneCaptureService.stopCapture();
        if (PhoneAccess.connected != null) PhoneAccess.connected.clear();
        if (pending != null) { pending.complete(result(reason).put("instruction", "Stop phone actions. Do not retry or resume without a new explicit user request. Already executed actions are not undone.")); pending = null; }
        if (!closed.isEmpty()) android.util.Log.i("MengLuoPhone", "Phone control ended: " + reason);
        syncUi();
    }
    private String request(String body) {
        try {
            JSONObject input = new JSONObject(body);
            CompletableFuture<JSONObject> response = new CompletableFuture<>();
            main.post(() -> handle(input, response));
            return response.get(110, TimeUnit.SECONDS).toString();
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); return result("bridge_closed").toString(); }
        catch (Exception error) { stop("bridge_error"); return result("bridge_error").toString(); }
    }
    private void handle(JSONObject input, CompletableFuture<JSONObject> response) {
        String op = input.optString("op"), owner = input.optString("owner"), lease = input.optString("lease");
        if (op.equals("screenshot") || op.equals("tap") || op.equals("gesture")) response.thenAccept(value -> {
            // Diagnostic values only: no screen text, image, app names, credentials or task contents.
            String status = value.optString("status"), reason = value.optString("reason");
            String line = "[phone] " + op + " status=" + (status.matches("[a-z_]{1,64}") ? status : "unknown");
            if (reason.matches("[a-z_]{1,64}")) line += " reason=" + reason;
            for (String key : List.of("x", "y", "endX", "endY")) if (input.opt(key) instanceof Number n) line += " " + key + "=" + n.intValue();
            for (String key : List.of("width", "height", "displayWidth", "displayHeight")) if (value.opt(key) instanceof Number n) line += " " + key + "=" + n.intValue();
            if (value.has("actionDispatched")) line += " dispatched=" + value.optBoolean("actionDispatched");
            Engine.get(context).note(line);
        });
        if (!runtime) { response.complete(result("runtime_stopped")); return; }
        String expired = state.expired(SystemClock.elapsedRealtime()); if (!expired.isEmpty()) stopNow(expired);
        if (op.equals("status")) {
            state.touch(owner, SystemClock.elapsedRealtime());
            response.complete(result(state.phase(owner)).put("accessibilityEnabled", enabled()).put("accessibilityConnected", PhoneAccess.connected != null)
                .put("overlayPermission", Settings.canDrawOverlays(context)).put("stopVisible", overlay.attached())
                .put("screenshotMode", "full_display").put("maskedOwnOverlay", true).put("imageCoordinateTaps", true)
                .put("gestures", new JSONArray(List.of("long_press", "swipe")))
                .put("screenSharingActive", PhoneCaptureService.available())
                .put("allowedApps", new JSONArray(owner.equals(state.owner()) ? apps : Set.of()))); return;
        }
        if (op.equals("cancel")) { if (owner.equals(state.owner())) stopNow("model_cancelled"); response.complete(result(state.phase(owner))); return; }
        String requestId = input.optString("requestId");
        if (!requestId.matches("[a-f0-9-]{36}")) { response.complete(result("invalid_request")); return; }
        String callKey = owner + ":" + requestId;
        if (calls.contains(callKey)) { response.complete(result("duplicate_request").put("instruction", "This request was already accepted. Do not repeat consequential actions.")); return; }
        if (calls.size() >= 2000) { response.complete(result("session_limit")); return; }
        calls.add(callKey);
        if (op.equals("begin")) {
            String phase = state.begin(owner, SystemClock.elapsedRealtime());
            if (!phase.equals("preparing")) { response.complete(result(phase)); return; }
            if (pending != null) { response.complete(result("busy")); return; }
            String purpose = input.optString("purpose", "");
            if (purpose.isBlank() || purpose.length() > 300) { stopNow("invalid_purpose"); response.complete(result("invalid_purpose")); return; }
            if (!enabled() || PhoneAccess.connected == null || !Settings.canDrawOverlays(context)) {
                stopNow("permission_required"); notifyGuide(); response.complete(result("permission_required").put("instruction", "Ask the user to open the phone-control permission page. After granting, wait for a NEW user request; never auto-resume.")); return;
            }
            pending = response; activateTask(owner, state.lease()); return;
        }
        if (!state.allowed(owner, lease)) { response.complete(result(state.phase(owner).equals("active") ? "invalid_lease" : state.phase(owner))); return; }
        if (!ready()) { stopNow("permission_lost"); response.complete(result("permission_lost")); return; }
        if (pending != null) { response.complete(result("busy")); return; }
        try {
            if (op.equals("finish")) { stopNow("completed"); response.complete(result("completed")); }
            else if (op.equals("observe")) response.complete(PhoneAccess.connected.observe(apps));
            else if (op.equals("ask")) {
                String question = input.optString("question");
                if (question.isBlank() || question.length() > 500) { response.complete(result("invalid_question")); return; }
                pending = response; confirmation = "需要你确认：\n" + question;
                confirmAction = () -> {
                    if (!state.allowed(owner, lease) || !ready()) { stopNow("permission_lost"); return; }
                    confirmAction = null; confirmation = ""; pending = null;
                    response.complete(result("confirmed")); syncUi();
                };
                armDeadline(owner, lease, response); syncUi();
            }
            else if (op.equals("action") || op.equals("screenshot") || op.equals("tap") || op.equals("gesture")) {
                PhoneAccess.Action action = op.equals("action") ? PhoneAccess.connected.plan(input, apps) : null;
                PhoneAccess.Touch touch = op.equals("tap") || op.equals("gesture") ? PhoneAccess.connected.planTouch(input, op.equals("gesture"), apps, overlay::screenBounds) : null;
                pending = response;
                boolean needsConfirmation = (action != null || touch != null)
                    && (input.optBoolean("ask") || (action != null ? action.needsConfirmation() : touch.needsConfirmation()));
                Runnable execute = () -> {
                    if (!state.allowed(owner, lease) || !ready()) { stopNow("permission_lost"); return; }
                    confirmAction = null; confirmation = "";
                    try {
                        if (action != null) {
                            boolean done = action.execute().getAsBoolean();
                            response.complete(result(done ? "action_dispatched" : "stale_or_failed").put("instruction", "Observe the screen to verify the outcome. Do not repeat a consequential action blindly."));
                            pending = null; syncUi();
                        } else if (touch != null) {
                            // A confirmation expands/replaces the pill. Restore its ordinary bounds before dispatching a touch.
                            syncUi();
                            Runnable timeout = () -> {
                                if (pending == response && state.allowed(owner, lease)) {
                                    response.complete(result("action_unknown").put("instruction", "Check the screen before any further action; do not blindly retry."));
                                    pending = null; if (PhoneAccess.connected != null) PhoneAccess.connected.clear(); syncUi();
                                }
                            };
                            main.postDelayed(timeout, 5000); response.whenComplete((value, error) -> main.removeCallbacks(timeout));
                            overlay.whenAttached(() -> pending == response, () -> {
                                if (pending != response) return;
                                if (!state.allowed(owner, lease) || !ready()) { stopNow("permission_lost"); return; }
                                PhoneAccess.connected.touch(touch, Set.copyOf(apps), overlay::screenBounds, needsConfirmation, value -> {
                                    if (state.allowed(owner, lease) && ready()) response.complete(value);
                                    else response.complete(result(state.phase(owner)));
                                    if (pending == response) pending = null; syncUi();
                                });
                            });
                        } else {
                            // The image and its coordinate plan must see the same compact overlay.
                            // Let a user-expanded pill finish its two-second timeout before capture.
                            Runnable timeout = () -> {
                                if (pending != response) return;
                                response.complete(result("screen_changed").put("instruction", "Wait for the phone controls to settle, then request a new screenshot."));
                                pending = null; syncUi();
                            };
                            main.postDelayed(timeout, 6000);
                            response.whenComplete((value, error) -> main.removeCallbacks(timeout));
                            overlay.whenCompact(() -> pending == response, () -> {
                                if (pending != response) return;
                                if (!state.allowed(owner, lease) || !ready()) { stopNow("permission_lost"); return; }
                                main.removeCallbacks(timeout);
                                PhoneAccess.connected.screenshot(Set.copyOf(apps), overlay::screenBounds, value -> {
                                    if (state.allowed(owner, lease) && ready() && value.optString("status").equals("screen_capture_empty") && !PhoneCaptureService.available()) {
                                        requestCapture(owner, lease, response); return;
                                    }
                                    if (state.allowed(owner, lease) && ready()) response.complete(value);
                                    else response.complete(result(state.phase(owner)));
                                    if (pending == response) pending = null; syncUi();
                                });
                            });
                        }
                    } catch (Exception error) { response.complete(result("action_failed")); pending = null; syncUi(); }
                };
                if (needsConfirmation) {
                    confirmation = action != null ? action.description() : touch.description(); confirmAction = execute;
                    armDeadline(owner, lease, response); syncUi();
                } else execute.run();
            } else response.complete(result("unsupported_operation"));
        } catch (IllegalArgumentException error) { response.complete(PhoneAccess.rejected(error)); }
        catch (Exception error) { response.complete(result("action_failed")); }
    }
    private boolean ready() { return runtime && state.expired(SystemClock.elapsedRealtime()).isEmpty() && enabled() && PhoneAccess.connected != null && PhoneAccess.connected.unlocked() && Settings.canDrawOverlays(context) && overlay.attached(); }
    private void armDeadline(String owner, String lease, CompletableFuture<JSONObject> value) {
        Runnable timeout = () -> { if (pending == value && state.matches(owner, lease)) stopNow("confirmation_timeout"); };
        main.postDelayed(timeout, 90_000);
        value.whenComplete((result, error) -> main.removeCallbacks(timeout));
    }
    private void activateTask(String owner, String lease) {
        if (!state.matches(owner, lease)) return;
        if (!enabled() || PhoneAccess.connected == null || !Settings.canDrawOverlays(context)) { stopNow("permission_lost"); return; }
        PhoneAccess.connected.clear();
        capturePrompted = false;
        apps.clear();
        for (android.content.pm.ResolveInfo info : context.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            String pkg = info.activityInfo.packageName;
            if (!PhoneAccess.protectedPackage(pkg, context.getPackageName())) apps.add(pkg);
        }
        if (!state.approve(owner, lease)) return;
        syncUi();
        if (!state.allowed(owner, lease) || pending == null) return;
        CompletableFuture<JSONObject> response = pending;
        Runnable timeout = () -> { if (pending == response && state.matches(owner, lease)) stopNow("stop_ui_unavailable"); };
        main.postDelayed(timeout, 6000);
        response.whenComplete((value, error) -> main.removeCallbacks(timeout));
        // Show the initial pill for two seconds, then start with a settled edge handle.
        // Do not hand the model a usable lease until the real stop affordance is attached.
        overlay.whenCompact(() -> pending == response, () -> {
            if (pending != response || !state.allowed(owner, lease)) return;
            if (!ready()) { stopNow("permission_lost"); return; }
            response.complete(result("active").put("lease", lease).put("allowedApps", new JSONArray(apps))); pending = null;
            context.getSystemService(NotificationManager.class).cancel(321);
        });
    }
    void approveAction() { Runnable action = confirmAction; if (action != null) action.run(); }
    private void requestCapture(String owner, String lease, CompletableFuture<JSONObject> response) {
        if (capturePrompted) {
            response.complete(result("screen_capture_unavailable").put("instruction", "No usable screenshot is available. Do not guess coordinates or repeat permission prompts. Use phone_observe or ask the user."));
            if (pending == response) pending = null; return;
        }
        capturePrompted = true; captureWait = new CaptureWait(owner, lease, PhoneAccess.connected.foreground(), response);
        armDeadline(owner, lease, response);
        try { context.startActivity(new Intent(context, PhoneCaptureActivity.class).putExtra("owner", owner).putExtra("lease", lease).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (RuntimeException error) { captureStarted(owner, lease, false); }
    }
    boolean awaitingCapture(String owner, String lease) {
        return captureWait != null && Objects.equals(owner, captureWait.owner) && Objects.equals(lease, captureWait.lease)
            && pending == captureWait.response && state.allowed(owner, lease) && ready();
    }
    void captureConsent(String owner, String lease, int code, Intent data) {
        if (!awaitingCapture(owner, lease)) return;
        if (code != Activity.RESULT_OK || data == null) { captureStarted(owner, lease, false); return; }
        try { context.startForegroundService(new Intent(context, PhoneCaptureService.class).putExtra("owner", owner).putExtra("lease", lease).putExtra("grant", data)); }
        catch (RuntimeException error) { captureStarted(owner, lease, false); }
    }
    void captureStarted(String owner, String lease, boolean success) {
        if (!awaitingCapture(owner, lease)) { if (success) PhoneCaptureService.stopCapture(); return; }
        CaptureWait wait = captureWait; captureWait = null; pending = null;
        if (PhoneAccess.connected != null) PhoneAccess.connected.clear();
        // Return to the task app, never replay the failed screenshot or a pending gesture automatically.
        if (apps.contains(wait.pkg)) {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(wait.pkg);
            if (launch != null) { try { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (RuntimeException ignored) { } }
        }
        wait.response.complete(result(success ? "screen_capture_ready" : "screen_capture_permission_denied")
            .put("instruction", success ? "The user granted this task's screen sharing. Wait for the target app, then request a NEW phone_screenshot; no action was replayed."
                : "Screen sharing was not granted. Do not repeat the prompt or guess coordinates. Use phone_observe or ask the user."));
        syncUi();
    }
    void syncUi() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::syncUi); return; }
        MainActivity foreground = activity.get();
        if (foreground != null) PhonePermissions.refresh(foreground);
        if (!runtime || !Settings.canDrawOverlays(context)) overlay.hide();
        else if (state.live()) {
            if (!overlay.show(true, confirmation)) { stopNow("stop_ui_unavailable"); return; }
        } else if (foreground == null) overlay.show(false, ""); else overlay.hide();
        if (foreground != null) foreground.phoneOverlayChanged(state.live() && overlay.attached());
    }
    private void notifyGuide() {
        MainActivity foreground = activity.get();
        if (foreground != null) { if (!guideNotified) { guideNotified = true; foreground.showPhonePermissions(); } return; }
        if (guideNotified) return; guideNotified = true;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("phone-control", "手机操作授权", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(context, 321, new Intent(context, MainActivity.class).setAction(OPEN).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try { manager.notify(321, new Notification.Builder(context, "phone-control").setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("手机操作需要系统权限").setContentText("返回 MengLuo 查看并亲自授权；不会自动授权或恢复已取消任务。")
            .setContentIntent(open).setAutoCancel(true).build()); } catch (SecurityException ignored) { }
    }
}
