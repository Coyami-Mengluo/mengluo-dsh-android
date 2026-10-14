package ai.mengluo.dsh.android;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;

/** Draws inside the existing stop window, so its touch area and screenshot mask stay unchanged. */
@SuppressLint("ViewConstructor") // This task-state view is created only by PhoneOverlay, never from XML.
final class PhoneOverlayFrame extends LinearLayout {
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF perimeter = new RectF();
    private final Matrix rotation = new Matrix();
    private final float density;
    private final boolean awaitingConfirmation;
    private final int[] colors;
    private final ValueAnimator animator;
    private SweepGradient gradient;
    private float angle, radius;

    PhoneOverlayFrame(Context context, boolean awaitingConfirmation, boolean rainbow) {
        super(context);
        this.awaitingConfirmation = awaitingConfirmation;
        density = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        int padding = Math.round(10 * density);
        setPadding(padding, padding, padding, padding);
        border.setStyle(Paint.Style.STROKE);
        int blue = context.getColor(R.color.phone_ring_blue);
        int violet = context.getColor(R.color.phone_ring_violet);
        int cyan = context.getColor(R.color.phone_ring_cyan);
        int red = context.getColor(R.color.phone_ring_red);
        colors = rainbow ? new int[]{red, context.getColor(R.color.phone_ring_orange),
            context.getColor(R.color.phone_ring_yellow), context.getColor(R.color.phone_ring_green),
            cyan, blue, violet, red} : new int[]{blue, violet, cyan, blue};
        animator = ValueAnimator.ofFloat(0, 360);
        animator.setDuration(2400);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> {
            angle = (float)value.getAnimatedValue();
            rotateGradient();
            invalidate();
        });
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        float inset = 3 * density;
        perimeter.set(inset, inset, width - inset, height - inset);
        radius = Math.max(0, Math.min(24 * density - inset, Math.min(perimeter.width(), perimeter.height()) / 2));
        gradient = new SweepGradient(width / 2f, height / 2f, colors, null);
        border.setShader(gradient);
        rotateGradient();
    }

    private void rotateGradient() {
        if (gradient == null) return;
        rotation.setRotate(angle, getWidth() / 2f, getHeight() / 2f);
        gradient.setLocalMatrix(rotation);
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (perimeter.isEmpty()) return;
        // Paint after the children: the compact artwork background must not cover the ring.
        border.setStrokeWidth(6 * density);
        border.setAlpha(45);
        canvas.drawRoundRect(perimeter, radius, radius, border);
        border.setStrokeWidth(2.5f * density);
        border.setAlpha(255);
        canvas.drawRoundRect(perimeter, radius, radius, border);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        syncAnimation();
    }

    @Override protected void onDetachedFromWindow() {
        animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        syncAnimation();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        syncAnimation();
    }

    private void syncAnimation() {
        // View can dispatch a visibility change before this constructor finishes.
        if (animator == null) return;
        boolean moving = !awaitingConfirmation && isAttachedToWindow() && isShown()
            && getWindowVisibility() == VISIBLE && ValueAnimator.areAnimatorsEnabled();
        if (moving) {
            if (!animator.isStarted()) animator.start();
        } else animator.cancel();
    }
}
