package ai.mengluo.dsh.android;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Rounded, theme-colored speech bubble with a movable pointer to the menu icon. */
final class HintBubble extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius, pointer, stroke;
    private final int fill, border;
    private boolean right;
    private float anchor;
    private int alpha = 255;
    HintBubble(Ui ui) {
        radius = ui.dp(18); pointer = ui.dp(9); stroke = ui.dp(1);
        fill = ui.color(R.color.surface); border = ui.color(R.color.outline);
    }
    void anchor(boolean right, float y) { this.right = right; anchor = y; invalidateSelf(); }
    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds(); float inset = stroke / 2;
        RectF body = new RectF(bounds.left + inset + (right ? 0 : pointer), bounds.top + inset,
            bounds.right - inset - (right ? pointer : 0), bounds.bottom - inset);
        float y = Math.max(body.top + radius + pointer, Math.min(body.bottom - radius - pointer, anchor));
        Path shape = new Path(); shape.addRoundRect(body, radius, radius, Path.Direction.CW);
        Path tail = new Path(); float edge = right ? body.right : body.left;
        tail.moveTo(edge + (right ? -1 : 1), y - pointer);
        tail.lineTo(right ? bounds.right - inset : bounds.left + inset, y);
        tail.lineTo(edge + (right ? -1 : 1), y + pointer); tail.close();
        shape.op(tail, Path.Op.UNION);
        paint.setStyle(Paint.Style.FILL); paint.setColor(fill); paint.setAlpha(alpha); canvas.drawPath(shape, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); paint.setColor(border); paint.setAlpha(alpha); canvas.drawPath(shape, paint);
    }
    @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
