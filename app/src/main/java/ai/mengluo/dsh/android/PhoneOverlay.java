package ai.mengluo.dsh.android;

import android.content.*;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityWindowInfo;
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
    private boolean active, collapsed, awaitingConfirmation, reviewing;
    private ImageButton icon;
    private TextView statusText;
    private Button stopButton, reviewButton;
    private TextView conversationText, stepsText;
    private PhoneQuestion renderedQuestion;
    private PhoneQuestionForm questionForm;
    private int expandedWidth, ordinaryY, appliedX, appliedY, appliedWidth, appliedHeight;
    private final List<LayoutWait> layoutWaits = new ArrayList<>();
    private final Runnable questionLayout = new Runnable() {
        @Override public void run() {
            if (renderedQuestion == null || view == null) return;
            if (attached()) resizeQuestion(view.getRootWindowInsets());
            // Some OEMs never dispatch IME insets/layout changes to application overlays.
            // This exists only while the human is answering, and never inspects keyboard text.
            main.postDelayed(this, 100);
        }
    };
    private final Runnable collapse = this::collapseNow;
    private void collapseNow() {
        if (view == null || awaitingConfirmation || reviewing) return;
        setCollapsed(true);
    }
    private void scheduleCollapse() {
        main.removeCallbacks(collapse);
        if (view != null && !awaitingConfirmation && !reviewing) main.postDelayed(collapse, 2000);
    }
    private void setCollapsed(boolean value) {
        if (view == null) return;
        collapsed = value;
        int padding = active && !value ? ui.dp(10) : 0;
        view.setPadding(padding, padding, padding, padding);
        view.setAlpha(value ? .85f : 1f);
        if (statusText != null) statusText.setVisibility(value ? View.GONE : View.VISIBLE);
        if (stopButton != null) stopButton.setVisibility(value ? View.GONE : View.VISIBLE);
        if (reviewButton != null) reviewButton.setVisibility(value ? View.GONE : View.VISIBLE);
        PhoneOverlayGeometry.IconBox iconBox = applyIconGeometry(value);
        icon.setContentDescription(active ? value ? "手机操作中，展开对话和停止按钮" : "查看对话并暂停手机操作" : "返回 MengLuo");
        // Resize the child with the window: a 52dp child inside a narrow window
        // is clipped, not scaled, even when its ImageView uses FIT_CENTER.
        position.width = value ? iconBox.width() : expandedWidth;
        position.x = safeBounds().right - position.width - (value ? 0 : ui.dp(6));
        view.requestLayout(); update();
        if (attached()) control.syncUi();
    }
    private PhoneOverlayGeometry.IconBox applyIconGeometry(boolean compact) {
        PhoneOverlayGeometry.IconBox box = PhoneOverlayGeometry.icon(compact, context.getResources().getDisplayMetrics().density);
        icon.setLayoutParams(new LinearLayout.LayoutParams(box.width(), box.height()));
        icon.setPadding(box.horizontalPadding(), box.verticalPadding(), box.horizontalPadding(), box.verticalPadding());
        return box;
    }
    PhoneOverlay(Context context, PhoneControl control) {
        // A service/application context does not inherit the Activity's Material theme.
        this.context = new androidx.appcompat.view.ContextThemeWrapper(context, R.style.AppTheme);
        this.control = control; manager = this.context.getSystemService(WindowManager.class); ui = new Ui(this.context);
    }
    boolean attached() { return view != null && view.isAttachedToWindow(); }
    boolean compact() { return attached() && collapsed; }
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
        boolean rainbow = control.skipActionConfirmation();
        PhoneQuestion question = controlling ? control.question() : null;
        boolean asking = question != null;
        boolean review = controlling && control.reviewing() && !asking;
        String next = controlling + ":" + confirmation + ":" + rainbow + ":" + review + ":" + (asking ? question.id : "") + ":" + context.getResources().getConfiguration().uiMode + ":" + bounds.width() + "x" + bounds.height();
        // addView attaches on the next UI traversal; do not tear it down while that is pending.
        // In particular, watchdog/streaming refreshes must preserve typed replies, focus and selection.
        if (next.equals(rendering) && question == renderedQuestion && view != null) { updateConversation(); return true; }
        int oldY = position == null ? ui.dp(180) : position.y;
        if (view != null && active && !awaitingConfirmation && !reviewing && (!confirmation.isEmpty() || review || asking)) ordinaryY = oldY;
        if (view != null && active && (awaitingConfirmation || reviewing) && confirmation.isEmpty() && !review && !asking) oldY = ordinaryY;
        // Ordinary watchdog/action updates must not re-open the pill or restart its timer.
        // Dismissing confirmation immediately restores the edge handle before a pending touch.
        boolean resumeCollapsed = view != null && controlling && active && (collapsed || awaitingConfirmation || reviewing) && confirmation.isEmpty() && !review && !asking;
        hide(); active = controlling; rendering = next; renderedQuestion = question; collapsed = false; awaitingConfirmation = asking || !confirmation.isEmpty(); reviewing = review;
        LinearLayout body = controlling ? new PhoneOverlayFrame(context, awaitingConfirmation || reviewing, rainbow) : ui.column(0);
        body.setElevation(ui.dp(8));
        body.setBackground(ui.rounded(ui.color(R.color.surface), controlling ? 24 : 28)); body.setClipToOutline(true);
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL);
        icon = new ImageButton(context); icon.setImageResource(R.drawable.menu_icon); icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setMinimumWidth(0); icon.setMinimumHeight(0);
        icon.setBackground(ui.rounded(ui.color(R.color.surface), 28)); applyIconGeometry(false);
        icon.setContentDescription("返回 MengLuo"); row.addView(icon);
        icon.setOnClickListener(v -> {
            if (renderedQuestion != null) return;
            if (collapsed) {
                setCollapsed(false); scheduleCollapse();
                // The edge handle is the way back to Stop, not a forced app switch.
                if (active) return;
            }
            if (active) { control.review(); return; }
            scheduleCollapse();
            context.startActivity(new Intent(context, MainActivity.class).setAction(PhoneControl.MENU).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        });
        if (controlling) {
            statusText = ui.text(asking ? "AI 等待你的回答" : reviewing ? "已暂停 · 查看对话" : confirmation.isEmpty() ? "手机操作中" : "等待你确认", 14, true);
            row.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1));
            stopButton = ui.button("停止", 0, false, () -> control.stop("user_cancelled")); stopButton.setFilterTouchesWhenObscured(true);
            stopButton.setTextColor(ui.color(R.color.danger)); row.addView(stopButton, new LinearLayout.LayoutParams(-2, -2));
        }
        body.addView(row);
        if (asking) {
            icon.setContentDescription("AI 等待你的回答");
            questionForm = new PhoneQuestionForm(context, question, answers -> control.answerQuestion(question, answers));
            questionForm.refresh(control.questionSubmitting(), control.questionError());
            body.addView(questionForm, new LinearLayout.LayoutParams(-1, 0, 1));
            // Claim window focus without focusing an editor or opening the keyboard automatically.
            body.setFocusableInTouchMode(true); body.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        } else if (reviewing) {
            ScrollView scroll = new ScrollView(context); LinearLayout details = ui.column(0);
            details.addView(ui.caption("本次任务的近期对话与操作记录；长消息显示摘要。"));
            conversationText = ui.text(control.progress.conversation(), 14, false); details.addView(conversationText);
            details.addView(ui.caption("操作记录"));
            stepsText = ui.text(control.progress.steps(), 12, false); details.addView(stepsText);
            scroll.addView(details);
            body.addView(scroll, new LinearLayout.LayoutParams(-1, Math.max(ui.dp(100), Math.min(ui.dp(360), safeBounds().height() - ui.dp(180)))));
            Button resume = ui.button("收起并继续", 0, true, control::resumeFromReview);
            resume.setFilterTouchesWhenObscured(true); body.addView(resume);
        } else if (!confirmation.isEmpty()) {
            ScrollView scroll = new ScrollView(context); LinearLayout details = ui.column(0);
            details.addView(ui.text(confirmation, 13, false));
            boolean informationalAsk = confirmation.startsWith("需要你确认：");
            details.addView(ui.caption(informationalAsk ? "同意后继续当前任务；不同意或要结束，请点停止。" : "请核对这一次敏感或不确定的操作。确认只对当前动作有效。"));
            Button approve = ui.button(informationalAsk ? "同意并继续" : "确认这次操作", 0, true, control::approveAction); approve.setFilterTouchesWhenObscured(true); details.addView(approve);
            scroll.addView(details);
            body.addView(scroll, new LinearLayout.LayoutParams(-1, Math.max(ui.dp(80), Math.min(ui.dp(240), safeBounds().height() - ui.dp(100)))));
        } else if (controlling) {
            reviewButton = ui.button("查看对话 · 暂停操作", 0, false, control::review);
            reviewButton.setFilterTouchesWhenObscured(true); body.addView(reviewButton);
        }
        expandedWidth = controlling ? Math.min(ui.dp(asking ? 360 : 330), safeBounds().width() - ui.dp(16)) : ui.dp(52);
        position = new WindowManager.LayoutParams(expandedWidth, asking ? questionHeight(null) : -2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | (asking ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
            PixelFormat.TRANSLUCENT);
        if (asking) position.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        position.gravity = Gravity.TOP | Gravity.LEFT;
        position.x = Math.max(safeBounds().left, safeBounds().right - position.width - ui.dp(6)); position.y = asking ? ui.dp(6) : oldY;
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
        if (asking) body.setOnApplyWindowInsetsListener((v, insets) -> {
            if (view == v && renderedQuestion != null) {
                // Keep Stop outside the scrolling questions and Submit above the visible IME.
                resizeQuestion(insets);
            }
            return insets;
        });
        if (controlling) body.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        if (resumeCollapsed) setCollapsed(true);
        try {
            manager.addView(view, position);
            appliedX = position.x; appliedY = position.y; appliedWidth = position.width; appliedHeight = position.height;
            view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (view != v) return;
                view.setSystemGestureExclusionRects(List.of(new Rect(0, 0, view.getWidth(), Math.min(view.getHeight(), ui.dp(180))))); update();
            });
            view.post(() -> { if (view == body) { if (asking) { body.requestFocus(); body.requestApplyInsets(); main.post(questionLayout); } update(); } });
            if (!collapsed) scheduleCollapse(); return true;
        } catch (RuntimeException error) { hide(); return false; }
    }
    private void updateConversation() {
        if (questionForm != null) questionForm.refresh(control.questionSubmitting(), control.questionError());
        if (conversationText != null && !conversationText.getText().toString().equals(control.progress.conversation())) conversationText.setText(control.progress.conversation());
        if (stepsText != null && !stepsText.getText().toString().equals(control.progress.steps())) stepsText.setText(control.progress.steps());
    }
    private Rect safeBounds() {
        WindowMetrics metrics = manager.getCurrentWindowMetrics(); Rect area = new Rect(metrics.getBounds());
        android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        // WindowManager coordinates are relative to the usable display frame with this window's flags.
        area.offsetTo(0, 0); area.right -= insets.left + insets.right; area.bottom -= insets.top + insets.bottom;
        return area;
    }
    private int questionHeight(WindowInsets insets) {
        WindowMetrics metrics = manager.getCurrentWindowMetrics();
        Rect display = metrics.getBounds();
        android.graphics.Insets system = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        int top = display.top + system.top + ui.dp(6), bottom = display.bottom - system.bottom;
        if (attached()) { int[] location = new int[2]; view.getLocationOnScreen(location); top = location[1]; }
        int keyboardTop = Integer.MAX_VALUE;
        if (insets != null && insets.isVisible(WindowInsets.Type.ime()) && insets.getInsets(WindowInsets.Type.ime()).bottom > system.bottom) {
            keyboardTop = display.bottom - insets.getInsets(WindowInsets.Type.ime()).bottom;
        }
        // OriginOS reports no IME WindowInsets for TYPE_APPLICATION_OVERLAY. Phone
        // tasks already require this service with retrieveInteractiveWindows, so
        // its public window bounds provide a fallback without another permission.
        PhoneAccess access = PhoneAccess.connected;
        if (access != null) {
            try {
                List<AccessibilityWindowInfo> windows = access.getWindows();
                try {
                    for (AccessibilityWindowInfo window : windows) {
                        if (window.getDisplayId() != Display.DEFAULT_DISPLAY || window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                        Rect keyboard = new Rect(); window.getBoundsInScreen(keyboard);
                        if (!keyboard.isEmpty() && keyboard.top > display.top && keyboard.bottom > top)
                            keyboardTop = Math.min(keyboardTop, keyboard.top);
                    }
                } finally {
                    for (AccessibilityWindowInfo window : windows) window.recycle();
                }
            } catch (RuntimeException ignored) { /* A concurrent service disconnect is handled by the task watchdog. */ }
        }
        if (keyboardTop == Integer.MAX_VALUE && attached()) {
            Rect visible = new Rect(); view.getWindowVisibleDisplayFrame(visible);
            // Treat only a substantial display occlusion as an IME fallback;
            // never feed this overlay's own resized bounds back into its height.
            int ownBottom = top + view.getHeight();
            if (visible.bottom > top && display.bottom - visible.bottom > ui.dp(120)
                && Math.abs(visible.bottom - ownBottom) > ui.dp(4)) keyboardTop = visible.bottom;
        }
        bottom = Math.min(bottom, keyboardTop);
        return Math.max(ui.dp(180), Math.min(ui.dp(600), bottom - top - ui.dp(8)));
    }
    private void resizeQuestion(WindowInsets insets) {
        if (!attached() || renderedQuestion == null) return;
        int height = questionHeight(insets);
        if (position.height == height) return;
        position.height = height; update();
        PhoneQuestionForm expected = questionForm;
        if (expected != null) expected.post(() -> { if (questionForm == expected) expected.revealFocusedInput(); });
    }
    private void update() {
        if (!attached()) return;
        Rect safe = safeBounds();
        position.x = Math.max(safe.left, Math.min(safe.right - position.width, position.x));
        position.y = Math.max(ui.dp(6), Math.min(safe.bottom - view.getHeight() - ui.dp(8), position.y));
        if (renderedQuestion != null) position.y = ui.dp(6); // Keep Stop and Submit above the IME, including after a drag.
        if (position.x == appliedX && position.y == appliedY && position.width == appliedWidth && position.height == appliedHeight) return;
        try { manager.updateViewLayout(view, position); appliedX = position.x; appliedY = position.y; appliedWidth = position.width; appliedHeight = position.height; } catch (RuntimeException error) { hide(); control.stop("stop_ui_unavailable"); }
    }
    void hide() {
        for (LayoutWait wait : new ArrayList<>(layoutWaits)) wait.cancel();
        main.removeCallbacks(collapse); main.removeCallbacks(questionLayout); View old = view;
        if (questionForm != null) questionForm.hideKeyboard();
        view = null; rendering = ""; renderedQuestion = null; questionForm = null;
        active = false; collapsed = false; awaitingConfirmation = false; reviewing = false;
        icon = null; statusText = null; stopButton = null; reviewButton = null; conversationText = null; stepsText = null;
        if (old != null) { try { manager.removeViewImmediate(old); } catch (RuntimeException ignored) { } }
    }
}
