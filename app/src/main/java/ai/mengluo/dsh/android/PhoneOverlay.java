package ai.mengluo.dsh.android;

import android.content.*;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** The cross-app affordance is a real WindowManager window, not a WebView drawing. */
final class PhoneOverlay {
    private final Context context;
    private final PhoneControl control;
    private final WindowManager manager;
    private final Ui ui;
    private final Handler main = new Handler(Looper.getMainLooper());
    private View view;
    private WindowManager.LayoutParams position;
    private String rendering = "";
    private boolean active, collapsed, awaitingConfirmation;
    private ImageButton icon;
    private TextView statusText;
    private Button stopButton;
    private int expandedWidth, ordinaryY, appliedX, appliedY, appliedWidth;
    private final List<LayoutWait> layoutWaits = new ArrayList<>();
    private final Runnable collapse = this::collapseNow;
    private void collapseNow() {
        if (view == null || awaitingConfirmation) return;
        setCollapsed(true);
    }
    private void scheduleCollapse() {
        main.removeCallbacks(collapse);
        if (view != null && !awaitingConfirmation) main.postDelayed(collapse, 2000);
    }
    private void setCollapsed(boolean value) {
        if (view == null) return;
        collapsed = value;
        int padding = active && !value ? ui.dp(10) : 0;
        view.setPadding(padding, padding, padding, padding);
        view.setAlpha(value ? .65f : 1f);
        if (statusText != null) statusText.setVisibility(value ? View.GONE : View.VISIBLE);
        if (stopButton != null) stopButton.setVisibility(value ? View.GONE : View.VISIBLE);
        icon.setContentDescription(active && value ? "手机操作中，展开停止按钮" : "返回 MengLuo");
        position.width = value ? ui.dp(25) : expandedWidth;
        position.x = safeBounds().right - position.width - (value ? 0 : ui.dp(6));
        view.requestLayout(); update();
    }
    PhoneOverlay(Context context, PhoneControl control) {
        // A service/application context does not inherit the Activity's Material theme.
        this.context = new androidx.appcompat.view.ContextThemeWrapper(context, R.style.AppTheme);
        this.control = control; manager = this.context.getSystemService(WindowManager.class); ui = new Ui(this.context);
    }
    boolean attached() { return view != null && view.isAttachedToWindow(); }
    PhoneScreenPolicy.Box screenBounds() {
        if (!attached() || view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        int[] location = new int[2]; view.getLocationOnScreen(location);
        // Physical display coordinates, not LayoutParams coordinates relative to the inset frame.
        int shadow = ui.dp(8);
        return new PhoneScreenPolicy.Box(location[0] - shadow, location[1] - shadow,
            location[0] + view.getWidth() + shadow, location[1] + view.getHeight() + shadow);
    }
    void whenAttached(Runnable action) {
        whenAttached(() -> true, action);
    }
    void whenAttached(BooleanSupplier needed, Runnable action) {
        whenSettled(false, needed, action);
    }
    void whenCompact(Runnable action) {
        whenCompact(() -> true, action);
    }
    void whenCompact(BooleanSupplier needed, Runnable action) {
        whenSettled(true, needed, action);
    }
    private void whenSettled(boolean requireCompact, BooleanSupplier needed, Runnable action) {
        if (view == null) return;
        LayoutWait wait = new LayoutWait(view, requireCompact, needed, action);
        layoutWaits.add(wait); view.post(wait);
    }
    private final class LayoutWait implements Runnable {
        private final View expected;
        private final boolean requireCompact;
        private final BooleanSupplier needed;
        private final Runnable action;
        private final long deadline = SystemClock.elapsedRealtime() + 6000;
        LayoutWait(View expected, boolean requireCompact, BooleanSupplier needed, Runnable action) {
            this.expected = expected; this.requireCompact = requireCompact; this.needed = needed; this.action = action;
        }
        void cancel() { expected.removeCallbacks(this); layoutWaits.remove(this); }
        @Override public void run() {
            if (view != expected || !needed.getAsBoolean() || SystemClock.elapsedRealtime() >= deadline) { cancel(); return; }
            if (attached() && !expected.isLayoutRequested() && expected.getWidth() == position.width
                && (!requireCompact || collapsed)) { cancel(); action.run(); }
            else expected.postOnAnimation(this);
        }
    }
    boolean show(boolean controlling, String confirmation) {
        Rect bounds = safeBounds();
        String next = controlling + ":" + confirmation + ":" + context.getResources().getConfiguration().uiMode + ":" + bounds.width() + "x" + bounds.height();
        // addView attaches on the next UI traversal; do not tear it down while that is pending.
        if (next.equals(rendering) && view != null) return true;
        int oldY = position == null ? ui.dp(180) : position.y;
        if (view != null && active && !awaitingConfirmation && !confirmation.isEmpty()) ordinaryY = oldY;
        if (view != null && active && awaitingConfirmation && confirmation.isEmpty()) oldY = ordinaryY;
        // Ordinary watchdog/action updates must not re-open the pill or restart its timer.
        // Dismissing confirmation immediately restores the edge handle before a pending touch.
        boolean resumeCollapsed = view != null && controlling && active && (collapsed || awaitingConfirmation) && confirmation.isEmpty();
        hide(); active = controlling; rendering = next; collapsed = false; awaitingConfirmation = !confirmation.isEmpty();
        LinearLayout body = ui.column(controlling ? 10 : 0); body.setElevation(ui.dp(8));
        body.setBackground(ui.rounded(ui.color(R.color.surface), controlling ? 24 : 28)); body.setClipToOutline(true);
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL);
        icon = new ImageButton(context); icon.setImageResource(R.drawable.menu_icon); icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setBackground(ui.rounded(ui.color(R.color.surface), 28)); icon.setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9));
        icon.setContentDescription("返回 MengLuo"); row.addView(icon, new LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)));
        icon.setOnClickListener(v -> {
            if (collapsed) {
                setCollapsed(false); scheduleCollapse();
                // The edge handle is the way back to Stop, not a forced app switch.
                if (active) return;
            }
            scheduleCollapse();
            context.startActivity(new Intent(context, MainActivity.class).setAction(PhoneControl.MENU).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        });
        if (controlling) {
            statusText = ui.text(confirmation.isEmpty() ? "手机操作中" : "等待你确认", 14, true);
            row.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1));
            stopButton = ui.button("停止", 0, false, () -> control.stop("user_cancelled")); stopButton.setFilterTouchesWhenObscured(true);
            stopButton.setTextColor(ui.color(R.color.danger)); row.addView(stopButton, new LinearLayout.LayoutParams(-2, -2));
        }
        body.addView(row);
        if (!confirmation.isEmpty()) {
            ScrollView scroll = new ScrollView(context); LinearLayout details = ui.column(0);
            details.addView(ui.text(confirmation, 13, false));
            boolean question = confirmation.startsWith("需要你确认：");
            details.addView(ui.caption(question ? "同意后继续当前任务；不同意或要结束，请点停止。" : "请核对这一次敏感或不确定的操作。确认只对当前动作有效。"));
            Button approve = ui.button(question ? "同意并继续" : "确认这次操作", 0, true, control::approveAction); approve.setFilterTouchesWhenObscured(true); details.addView(approve);
            scroll.addView(details);
            body.addView(scroll, new LinearLayout.LayoutParams(-1, Math.max(ui.dp(80), Math.min(ui.dp(240), safeBounds().height() - ui.dp(100)))));
        }
        expandedWidth = controlling ? Math.min(ui.dp(330), safeBounds().width() - ui.dp(16)) : ui.dp(52);
        position = new WindowManager.LayoutParams(expandedWidth, -2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        position.gravity = Gravity.TOP | Gravity.LEFT;
        position.x = Math.max(safeBounds().left, safeBounds().right - position.width - ui.dp(6)); position.y = oldY;
        position.setTitle("MengLuo phone stop");
        // Drag by the artwork; never put a drag listener on the stop/approve buttons.
        icon.setOnTouchListener(new View.OnTouchListener() {
            float x, y; int startX, startY; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { main.removeCallbacks(collapse); x = event.getRawX(); y = event.getRawY(); startX = position.x; startY = position.y; moved = false; return true; }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    if (Math.abs(event.getRawX()-x) + Math.abs(event.getRawY()-y) > ui.dp(8)) moved = true;
                    if (moved) { if (collapsed) setCollapsed(false); position.x = startX + (int)(event.getRawX()-x); position.y = startY + (int)(event.getRawY()-y); update(); } return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    if (!moved && event.getActionMasked() == MotionEvent.ACTION_UP) v.performClick();
                    scheduleCollapse(); return true;
                }
                return false;
            }
        });
        view = body;
        if (resumeCollapsed) setCollapsed(true);
        try {
            manager.addView(view, position);
            appliedX = position.x; appliedY = position.y; appliedWidth = position.width;
            view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (view != v) return;
                view.setSystemGestureExclusionRects(List.of(new Rect(0, 0, view.getWidth(), Math.min(view.getHeight(), ui.dp(180))))); update();
            });
            view.post(this::update); if (!collapsed) scheduleCollapse(); return true;
        } catch (RuntimeException error) { hide(); return false; }
    }
    private Rect safeBounds() {
        WindowMetrics metrics = manager.getCurrentWindowMetrics(); Rect area = new Rect(metrics.getBounds());
        android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        // WindowManager coordinates are relative to the usable display frame with this window's flags.
        area.offsetTo(0, 0); area.right -= insets.left + insets.right; area.bottom -= insets.top + insets.bottom;
        return area;
    }
    private void update() {
        if (!attached()) return;
        Rect safe = safeBounds();
        position.x = Math.max(safe.left, Math.min(safe.right - (collapsed ? ui.dp(25) : position.width), position.x));
        position.y = Math.max(ui.dp(6), Math.min(safe.bottom - view.getHeight() - ui.dp(8), position.y));
        if (position.x == appliedX && position.y == appliedY && position.width == appliedWidth) return;
        try { manager.updateViewLayout(view, position); appliedX = position.x; appliedY = position.y; appliedWidth = position.width; } catch (RuntimeException error) { hide(); control.stop("stop_ui_unavailable"); }
    }
    void hide() {
        for (LayoutWait wait : new ArrayList<>(layoutWaits)) wait.cancel();
        main.removeCallbacks(collapse); View old = view; view = null; rendering = "";
        active = false; collapsed = false; awaitingConfirmation = false;
        icon = null; statusText = null; stopButton = null;
        if (old != null) { try { manager.removeViewImmediate(old); } catch (RuntimeException ignored) { } }
    }
}
