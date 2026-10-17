package ai.mengluo.dsh.android;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.*;
import android.widget.TextView;

/** Read-only captions, removed before observing or operating the underlying app. */
final class PhoneProgressOverlay {
    private final Context context;
    private final WindowManager manager;
    private TextView view;
    PhoneProgressOverlay(Context context) {
        this.context = new androidx.appcompat.view.ContextThemeWrapper(context, R.style.AppTheme);
        manager = this.context.getSystemService(WindowManager.class);
    }
    void show(String text) {
        if (view != null) { if (!view.getText().toString().equals(text)) view.setText(text); return; }
        Ui ui = new Ui(context);
        TextView caption = ui.text(text, 13, false);
        caption.setTextColor(android.graphics.Color.WHITE);
        caption.setBackground(ui.rounded(android.graphics.Color.rgb(24, 28, 39), 16));
        caption.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
        caption.setMaxLines(4); caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        WindowManager.LayoutParams position = new WindowManager.LayoutParams(
            Math.min(ui.dp(300), manager.getCurrentWindowMetrics().getBounds().width() - ui.dp(48)), -2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        // Android 12+ checks WINDOW opacity, not View alpha, for untrusted touch-through.
        position.alpha = android.os.Build.VERSION.SDK_INT >= 31 ? Math.min(.65f,
            context.getSystemService(android.hardware.input.InputManager.class).getMaximumObscuringOpacityForTouch()) : .65f;
        position.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; position.y = ui.dp(12);
        position.setTitle("MengLuo phone progress");
        try { manager.addView(caption, position); view = caption; }
        catch (RuntimeException ignored) { /* Optional captions must never remove the mandatory Stop control. */ }
    }
    boolean hide() {
        TextView old = view; view = null;
        if (old != null) try { manager.removeViewImmediate(old); } catch (RuntimeException ignored) { }
        if (old != null && old.isAttachedToWindow()) { view = old; return false; }
        return true;
    }
    boolean visible() { return view != null && view.isAttachedToWindow(); }
}
