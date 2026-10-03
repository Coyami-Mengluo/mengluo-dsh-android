package ai.mengluo.dsh.android;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.*;
import org.json.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Only the native controller can call this service. No exported command receiver or web bridge. */
public final class PhoneAccess extends AccessibilityService {
    static volatile PhoneAccess connected;
    private final Map<Integer, AccessibilityNodeInfo> nodes = new HashMap<>();
    private String snapshot = "", visiblePackage = "";
    private int windowId = -1;
    private boolean passwordVisible;
    private long captureEpoch;
    private CapturedScreen lastScreen;

    @Override protected void onServiceConnected() { connected = this; PhoneControl.get(this).permissionsChanged(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Dynamic text, animation and scrolling do not revoke screenshot coordinates.
        // Read the actual foreground/window/geometry and action risk immediately before dispatch instead.
        // Never read or log the accessibility event's text.
    }
    @Override public void onInterrupt() { PhoneControl.get(this).stop("service_disconnected"); clear(); }
    @Override public boolean onUnbind(Intent intent) { disconnect(); return super.onUnbind(intent); }
    @Override public void onDestroy() { disconnect(); super.onDestroy(); }
    private void disconnect() { if (connected == this) connected = null; PhoneControl.get(this).stop("service_disconnected"); clear(); }
    void clear() { clearNodes(); lastScreen = null; captureEpoch++; }
    @SuppressWarnings("deprecation") private void clearNodes() { for (AccessibilityNodeInfo node : nodes.values()) node.recycle(); nodes.clear(); snapshot = ""; }
    boolean unlocked() { return !getSystemService(KeyguardManager.class).isKeyguardLocked(); }

    static boolean protectedPackage(String value, String own) {
        return value == null || value.isEmpty() || value.equals(own) || value.equals("android")
            || value.equals("com.android.systemui") || value.equals("com.android.settings")
            || value.contains("permissioncontroller") || value.contains("packageinstaller")
            || value.contains("securitycenter");
    }
    @SuppressWarnings("deprecation") String foreground() {
        AccessibilityNodeInfo root = getRootInActiveWindow(); if (root == null) return "";
        try { return Objects.toString(root.getPackageName(), ""); } finally { root.recycle(); }
    }
    JSONObject observe(Set<String> allowed) throws JSONException {
        clearNodes(); passwordVisible = false;
        if (!unlocked()) return PhoneControl.result("device_locked");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return PhoneControl.result("screen_unavailable");
        try {
            visiblePackage = Objects.toString(root.getPackageName(), ""); windowId = root.getWindowId();
            if (protectedPackage(visiblePackage, getPackageName()) || !allowed.contains(visiblePackage)) return PhoneControl.result("outside_allowed_apps");
            snapshot = UUID.randomUUID().toString(); JSONArray output = new JSONArray();
            visit(root, output, 0, new int[]{0});
            return PhoneControl.result("ok").put("package", visiblePackage).put("snapshot", snapshot)
                .put("nodes", output).put("truncated", nodes.size() >= 250).put("passwordFieldsRedacted", passwordVisible);
        } finally { recycle(root); }
    }
    private void visit(AccessibilityNodeInfo node, JSONArray output, int depth, int[] visited) throws JSONException {
        if (depth > 35 || ++visited[0] > 500 || nodes.size() >= 250) return;
        // Do not descend into password controls, including labels supplied by custom views.
        if (node.isPassword()) { passwordVisible = true; return; }
        if (node.isVisibleToUser()) {
            int id = nodes.size() + 1; nodes.put(id, copy(node)); Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            output.put(new JSONObject().put("id", id).put("depth", depth).put("text", limited(node.getText())).put("label", limited(node.getContentDescription()))
                .put("class", limited(node.getClassName())).put("clickable", node.isClickable()).put("editable", node.isEditable())
                .put("scrollable", node.isScrollable()).put("bounds", new JSONArray(List.of(bounds.left, bounds.top, bounds.right, bounds.bottom))));
        }
        for (int i = 0; i < node.getChildCount() && nodes.size() < 250 && visited[0] < 500; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) { try { visit(child, output, depth + 1, visited); } finally { recycle(child); } }
        }
    }
    @SuppressWarnings("deprecation") private static AccessibilityNodeInfo copy(AccessibilityNodeInfo node) { return AccessibilityNodeInfo.obtain(node); }
    @SuppressWarnings("deprecation") private static void recycle(AccessibilityNodeInfo node) { node.recycle(); }
    private static String limited(CharSequence text) { String value = Objects.toString(text, ""); return value.substring(0, Math.min(value.length(), 400)); }

    /** Capture a fixed action from an observed node; verify again immediately before executing it. */
    Action plan(JSONObject args, Set<String> allowed) {
        String kind = args.optString("action"), token = args.optString("snapshot");
        if (!Set.of("click", "input", "scroll_forward", "scroll_backward", "back", "launch").contains(kind)) throw new IllegalArgumentException("unsupported_action");
        if (!unlocked()) throw new IllegalArgumentException("device_locked");
        if (kind.equals("launch")) {
            String target = args.optString("package");
            if (!allowed.contains(target) || protectedPackage(target, getPackageName())) throw new IllegalArgumentException("outside_allowed_apps");
            Intent launch = getPackageManager().getLaunchIntentForPackage(target);
            if (launch == null) throw new IllegalArgumentException("app_unavailable");
            return new Action("打开应用 " + target, false, () -> { clear(); startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; });
        }
        String front = foreground();
        if (!allowed.contains(front) || protectedPackage(front, getPackageName())) throw new IllegalArgumentException("outside_allowed_apps");
        if (!token.equals(snapshot) || snapshot.isEmpty() || !front.equals(visiblePackage)) throw new IllegalArgumentException("stale_snapshot");
        if (kind.equals("back")) return new Action("在 " + front + " 返回上一页", false, () -> { boolean same = foreground().equals(front); clear(); return same && performGlobalAction(GLOBAL_ACTION_BACK); });
        AccessibilityNodeInfo node = nodes.get(args.optInt("node", -1));
        if (node == null || node.isPassword() || !node.isEnabled()) throw new IllegalArgumentException("invalid_node");
        String text = args.optString("text", "");
        if (text.length() > 2000) throw new IllegalArgumentException("text_too_long");
        if (kind.equals("input") && !node.isEditable()) throw new IllegalArgumentException("not_editable");
        String label = limited(node.getText()) + " " + limited(node.getContentDescription());
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds); int oldWindow = windowId;
        String description = (kind.equals("input") ? "输入：" + text : kind.equals("click") ? "点击：" + label : "滚动：" + label) + "\n应用：" + front;
        return new Action(description, PhoneActionPolicy.needsConfirmation(kind, label), () -> {
            if (!foreground().equals(front) || !snapshot.equals(token) || !node.refresh() || node.isPassword() || !node.isVisibleToUser()
                || !node.isEnabled() || node.getWindowId() != oldWindow || !label.equals(limited(node.getText()) + " " + limited(node.getContentDescription()))) return false;
            Rect current = new Rect(); node.getBoundsInScreen(current); if (!bounds.equals(current)) return false;
            boolean done;
            if (kind.equals("input")) { Bundle values = new Bundle(); values.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text); done = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, values); }
            else done = node.performAction(kind.equals("click") ? AccessibilityNodeInfo.ACTION_CLICK : kind.equals("scroll_forward") ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            clear(); return done;
        });
    }
    record Action(String description, boolean needsConfirmation, java.util.function.BooleanSupplier execute) { }

    private record ScreenPlan(String pkg, int window, int width, int height, int rotation, PhoneScreenPolicy.Box overlay) { }
    private record CapturedScreen(String id, ScreenPlan plan, int imageWidth, int imageHeight, long capturedAt) { }
    private record Hit(String label, PhoneScreenPolicy.Box bounds, PhoneScreenPolicy.Box scrollArea) { }
    record Touch(String description, boolean needsConfirmation, CapturedScreen screen, PhoneScreenPolicy.Pixel start, PhoneScreenPolicy.Pixel end,
                 int duration, Hit startHit, Hit endHit) { }
    private static PhoneScreenPolicy.Box box(Rect value) { return new PhoneScreenPolicy.Box(value.left, value.top, value.right, value.bottom); }
    private boolean sensitive(AccessibilityNodeInfo node, int depth, int[] count) {
        // The UI tree shown to the model is truncated; screenshot sensitivity checks must not be.
        if (node.isPassword() || depth > 80 || ++count[0] > 5000) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) { try { if (sensitive(child, depth + 1, count)) return true; } finally { recycle(child); } }
        }
        return false;
    }
    @SuppressWarnings("deprecation") private ScreenPlan screenPlan(Set<String> allowed, Supplier<PhoneScreenPolicy.Box> overlay) throws JSONException {
        JSONObject tree = observe(allowed);
        if (!tree.optString("status").equals("ok")) throw new IllegalArgumentException(tree.optString("status"));
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) throw new IllegalArgumentException("screen_unavailable");
        try {
            if (root.getWindowId() != windowId || !visiblePackage.equals(Objects.toString(root.getPackageName(), ""))) throw new IllegalArgumentException("screen_changed");
            if (sensitive(root, 0, new int[]{0})) throw new IllegalArgumentException("sensitive_screen");
        } finally { recycle(root); }
        Display display = getSystemService(WindowManager.class).getDefaultDisplay(); Point size = new Point(); display.getRealSize(size);
        return new ScreenPlan(visiblePackage, windowId, size.x, size.y, display.getRotation(),
            PhoneScreenPolicy.ownMask(overlay.get(), size.x, size.y));
    }

    private ScreenPlan captureStart(Set<String> allowed, Supplier<PhoneScreenPolicy.Box> overlay) throws JSONException {
        return screenPlan(allowed, overlay);
    }

    void screenshot(Set<String> allowed, Supplier<PhoneScreenPolicy.Box> overlay, Consumer<JSONObject> callback) {
        lastScreen = null; long epoch = ++captureEpoch;
        try {
            ScreenPlan expected = captureStart(allowed, overlay); long startedAt = SystemClock.elapsedRealtime();
            if (PhoneCaptureService.available()) {
                PhoneCaptureService.active.capture(expected.width, expected.height, (bitmap, status) -> {
                    if (bitmap == null) callback.accept(PhoneControl.result(status));
                    else deliverScreenshot(expected, epoch, startedAt, allowed, overlay, bitmap, "screen_share", callback);
                });
                return;
            }
            TakeScreenshotCallback receiver = new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    HardwareBuffer buffer = result.getHardwareBuffer(); Bitmap hardware = null, software = null;
                    try {
                        if (epoch != captureEpoch || !expected.equals(screenPlan(allowed, overlay))) { callback.accept(PhoneControl.result("screen_changed")); return; }
                        if ((long)buffer.getWidth() * buffer.getHeight() > 20_000_000) { callback.accept(PhoneControl.result("screenshot_too_large")); return; }
                        if (buffer.getWidth() != expected.width || buffer.getHeight() != expected.height) { callback.accept(PhoneControl.result("screen_changed")); return; }
                        hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (hardware != null) software = hardware.copy(Bitmap.Config.ARGB_8888, true);
                        if (software == null) { callback.accept(PhoneControl.result("screenshot_unavailable")); return; }
                        Bitmap owned = software; software = null;
                        deliverScreenshot(expected, epoch, startedAt, allowed, overlay, owned, "accessibility", callback);
                    } catch (IllegalArgumentException error) { callback.accept(PhoneControl.result(error.getMessage())); }
                    catch (Exception error) { callback.accept(PhoneControl.result("screenshot_unavailable")); }
                    finally { if (software != null) software.recycle(); if (hardware != null) hardware.recycle(); buffer.close(); }
                }
                @Override public void onFailure(int code) { callback.accept(PhoneControl.result("screenshot_denied").put("androidCode", code)); }
            };
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), receiver);
        } catch (IllegalArgumentException error) { callback.accept(PhoneControl.result(error.getMessage())); }
        catch (Exception error) { callback.accept(PhoneControl.result("screenshot_unavailable")); }
    }
    private void deliverScreenshot(ScreenPlan expected, long epoch, long startedAt, Set<String> allowed, Supplier<PhoneScreenPolicy.Box> overlay,
                                   Bitmap software, String source, Consumer<JSONObject> callback) {
        Bitmap scaled = null;
        try {
            if (epoch != captureEpoch || !expected.equals(screenPlan(allowed, overlay)) || software.getWidth() != expected.width || software.getHeight() != expected.height) {
                callback.accept(PhoneControl.result("screen_changed")); return;
            }
            PhoneScreenPolicy.Box mask = expected.overlay;
            if (emptyScreen(software, mask)) {
                callback.accept(PhoneControl.result("screen_capture_empty").put("instruction", "The screenshot is blank or protected. Do not claim you can see it or guess coordinates.")); return;
            }
            // Only our actual overlay bounds are masked, never arbitrary accessibility windows.
            Canvas canvas = new Canvas(software); Paint paint = new Paint(); paint.setColor(Color.rgb(32, 33, 36));
            canvas.drawRect(mask.left(), mask.top(), mask.right(), mask.bottom(), paint);
            float scale = Math.min(1f, 1280f / Math.max(software.getWidth(), software.getHeight()));
            scaled = Bitmap.createScaledBitmap(software, Math.max(1, (int)(software.getWidth()*scale)), Math.max(1, (int)(software.getHeight()*scale)), true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.JPEG, 70, bytes);
            if (bytes.size() > 2_000_000) { callback.accept(PhoneControl.result("screenshot_too_large")); return; }
            lastScreen = new CapturedScreen(UUID.randomUUID().toString(), expected, scaled.getWidth(), scaled.getHeight(), startedAt);
            callback.accept(PhoneControl.result("ok").put("mimeType", "image/jpeg").put("image", android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP))
                .put("screenshot", lastScreen.id).put("captureMode", "full_display").put("captureSource", source).put("maskedOwnOverlay", true)
                .put("width", scaled.getWidth()).put("height", scaled.getHeight()).put("displayWidth", expected.width).put("displayHeight", expected.height)
                .put("coordinateSpace", "image_pixels").put("validForMs", PhoneScreenPolicy.CAPTURE_TTL_MS));
        } catch (IllegalArgumentException error) { callback.accept(PhoneControl.result(error.getMessage())); }
        catch (Exception error) { callback.accept(PhoneControl.result("screenshot_unavailable")); }
        finally { if (scaled != null && scaled != software) scaled.recycle(); software.recycle(); }
    }
    static boolean emptyScreen(Bitmap bitmap, PhoneScreenPolicy.Box ownOverlay) {
        int stepX = Math.max(1, bitmap.getWidth() / 128), stepY = Math.max(1, bitmap.getHeight() / 128);
        for (int y = 0; y < bitmap.getHeight(); y += stepY) for (int x = 0; x < bitmap.getWidth(); x += stepX) {
            if (ownOverlay.contains(new PhoneScreenPolicy.Pixel(x, y))) continue;
            int color = bitmap.getPixel(x, y);
            if (Color.alpha(color) > 8 && (Color.red(color) > 8 || Color.green(color) > 8 || Color.blue(color) > 8)) return false;
        }
        return true;
    }

    Touch planTouch(JSONObject args, boolean gesture, Set<String> allowed, Supplier<PhoneScreenPolicy.Box> overlay) throws JSONException {
        CapturedScreen screen = lastScreen;
        if (screen == null || !screen.id.equals(args.optString("screenshot"))) throw new IllegalArgumentException("stale_screenshot");
        int x = coordinate(args, "x"), y = coordinate(args, "y");
        String kind = gesture ? args.optString("gesture") : "tap";
        int duration = gesture ? PhoneScreenPolicy.duration(kind, args.has("durationMs") ? coordinate(args, "durationMs") : kind.equals("long_press") ? 800 : 400) : 60;
        PhoneScreenPolicy.Pixel start = PhoneScreenPolicy.toDisplay(x, y, screen.imageWidth, screen.imageHeight, screen.plan.width, screen.plan.height);
        PhoneScreenPolicy.Pixel end = kind.equals("swipe") ? PhoneScreenPolicy.toDisplay(coordinate(args, "endX"), coordinate(args, "endY"),
            screen.imageWidth, screen.imageHeight, screen.plan.width, screen.plan.height) : start;
        if (kind.equals("swipe") && start.equals(end)) throw new IllegalArgumentException("invalid_coordinates");
        List<Hit> hits = validateTouch(screen, start, end, allowed, overlay); Hit first = hits.get(0), last = hits.get(1);
        String target = args.optString("target").strip();
        if (target.isEmpty() || target.length() > 160) throw new IllegalArgumentException("invalid_target");
        boolean confirm = PhoneActionPolicy.needsTouchConfirmation(first.label, last.label, routineScroll(start, end, first));
        String description = (kind.equals("swipe") ? "滑动" : kind.equals("long_press") ? "长按" : "点击") + "图片坐标 (" + x + ", " + y + ")"
            + (kind.equals("swipe") ? " → (" + args.getInt("endX") + ", " + args.getInt("endY") + ")" : "")
            + "：" + target + "\n界面标签：" + (first.label.isBlank() ? "无法识别" : first.label) + "\n应用：" + screen.plan.pkg;
        return new Touch(description, confirm, screen, start, end, duration, first, last);
    }
    private static int coordinate(JSONObject args, String key) {
        Object value = args.opt(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() != number.intValue()) throw new IllegalArgumentException("invalid_coordinates");
        return number.intValue();
    }
    private static boolean routineScroll(PhoneScreenPolicy.Pixel start, PhoneScreenPolicy.Pixel end, Hit first) {
        return Math.abs(end.y() - start.y()) > 2L * Math.abs(end.x() - start.x())
            && first.scrollArea != null && first.scrollArea.contains(end);
    }
    private List<Hit> validateTouch(CapturedScreen screen, PhoneScreenPolicy.Pixel start, PhoneScreenPolicy.Pixel end, Set<String> allowed, Supplier<PhoneScreenPolicy.Box> overlay) throws JSONException {
        if (lastScreen != screen) throw new IllegalArgumentException("stale_screenshot");
        PhoneScreenPolicy.requireFresh(screen.capturedAt, SystemClock.elapsedRealtime());
        if (!screen.plan.equals(screenPlan(allowed, overlay))) throw new IllegalArgumentException("screen_changed");
        if (PhoneScreenPolicy.crosses(screen.plan.overlay, start, end)) throw new IllegalArgumentException("own_overlay_blocked");
        // Gesture coordinates have different semantics under touch exploration/magnification; never guess.
        if (getSystemService(AccessibilityManager.class).isTouchExplorationEnabled() || getMagnificationController().getScale() != 1f)
            throw new IllegalArgumentException("coordinate_mapping_unavailable");
        requireForegroundPath(screen.plan, start, end);
        // This is an image-coordinate action, not a node-snapshot action. Do not reject
        // animation, text changes, recycled views or scrolling inside this same app/window.
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) throw new IllegalArgumentException("screen_unavailable");
        try {
            Rect bounds = new Rect(); root.getBoundsInScreen(bounds);
            if (root.getWindowId() != screen.plan.window || !screen.plan.pkg.equals(Objects.toString(root.getPackageName(), ""))
                || !bounds.contains(start.x(), start.y()) || !bounds.contains(end.x(), end.y()))
                throw new IllegalArgumentException("outside_foreground_app");
            Hit first = hitAt(root, start, 0, new int[]{0}, null), last = start.equals(end) ? first : hitAt(root, end, 0, new int[]{0}, null);
            return List.of(first == null ? new Hit("", box(bounds), null) : first, last == null ? new Hit("", box(bounds), null) : last);
        } finally { recycle(root); }
    }
    @SuppressWarnings("deprecation") private void requireForegroundPath(ScreenPlan plan, PhoneScreenPolicy.Pixel start, PhoneScreenPolicy.Pixel end) {
        List<AccessibilityWindowInfo> windows = getWindows(); AccessibilityWindowInfo target = null;
        try {
            for (AccessibilityWindowInfo window : windows) if (window.getId() == plan.window && window.getDisplayId() == Display.DEFAULT_DISPLAY) target = window;
            if (target == null || target.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) throw new IllegalArgumentException("outside_foreground_app");
            for (AccessibilityWindowInfo window : windows) {
                if (window.getId() == target.getId() || window.getDisplayId() != Display.DEFAULT_DISPLAY || window.getLayer() < target.getLayer()) continue;
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                if (!bounds.isEmpty() && PhoneScreenPolicy.crosses(box(bounds), start, end)) throw new IllegalArgumentException("outside_foreground_app");
            }
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
    }
    private Hit hitAt(AccessibilityNodeInfo node, PhoneScreenPolicy.Pixel point, int depth, int[] visited, PhoneScreenPolicy.Box scrollArea) {
        if (depth > 80 || ++visited[0] > 5000) throw new IllegalArgumentException("screen_unavailable");
        if (!node.isVisibleToUser()) return null;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        if (!bounds.contains(point.x(), point.y())) return null;
        if (node.isPassword()) throw new IllegalArgumentException("sensitive_screen");
        if (node.isScrollable()) scrollArea = box(bounds);
        Hit selected = node.isEnabled() && (node.isClickable() || node.isEditable() || node.isLongClickable() || node.isScrollable())
            ? new Hit(node.isEditable() ? "输入框" : (limited(node.getText()) + " " + limited(node.getContentDescription())).strip(), box(bounds), scrollArea) : null;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try { Hit found = hitAt(child, point, depth + 1, visited, scrollArea); if (found != null) selected = found; }
                finally { recycle(child); }
            }
        }
        return selected;
    }
    void touch(Touch plan, Set<String> allowed, Supplier<PhoneScreenPolicy.Box> overlay, boolean confirmed, Consumer<JSONObject> callback) {
        touch(plan, allowed, overlay, confirmed, false, callback);
    }
    void touch(Touch plan, Set<String> allowed, Supplier<PhoneScreenPolicy.Box> overlay, boolean confirmed,
            boolean skipActionConfirmation, Consumer<JSONObject> callback) {
        try {
            List<Hit> current = validateTouch(plan.screen, plan.start, plan.end, allowed, overlay);
            boolean sameConfirmedTargets = List.of(plan.startHit, plan.endHit).equals(current);
            if (PhoneActionPolicy.needsConfirmationBeforeDispatch(current.get(0).label, current.get(1).label,
                routineScroll(plan.start, plan.end, current.get(0)), confirmed, sameConfirmedTargets, skipActionConfirmation)) {
                callback.accept(PhoneControl.result("confirmation_required").put("actionDispatched", false)
                    .put("reason", confirmed ? "confirmed_target_changed" : "action_risk_changed")
                    .put("instruction", "The action was NOT executed. The current action needs a new confirmation. Take a fresh screenshot and request the intended action with ask=true; do not bypass confirmation."));
                return;
            }
            // A screenshot authorizes one touch sequence. Consume it even if Android cancels/rejects it.
            clear(); Path path = new Path(); path.moveTo(plan.start.x(), plan.start.y());
            if (!plan.start.equals(plan.end)) path.lineTo(plan.end.x(), plan.end.y());
            GestureDescription gesture = new GestureDescription.Builder().setDisplayId(Display.DEFAULT_DISPLAY)
                .addStroke(new GestureDescription.StrokeDescription(path, 0, plan.duration)).build();
            boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription value) { callback.accept(PhoneControl.result("action_dispatched").put("actionDispatched", true).put("instruction", "Take a new screenshot to verify the result. Never blindly repeat a consequential action.")); }
                @Override public void onCancelled(GestureDescription value) { callback.accept(PhoneControl.result("gesture_cancelled").put("instruction", "The gesture may be partially executed. Check the screen; do not blindly retry.")); }
            }, null);
            if (!dispatched) callback.accept(PhoneControl.result("gesture_unavailable"));
        } catch (IllegalArgumentException error) { callback.accept(rejected(error)); }
        catch (Exception error) { callback.accept(PhoneControl.result("action_failed")); }
    }
    static PhoneControl.Reply rejected(IllegalArgumentException error) {
        PhoneControl.Reply result = PhoneControl.result(error.getMessage()).put("actionDispatched", false);
        if ("screen_changed".equals(error.getMessage()) || "stale_screenshot".equals(error.getMessage()))
            result.put("instruction", "The action was NOT executed or dispatched. Take a new screenshot and locate the target again; do not claim a click occurred.");
        return result;
    }
}
