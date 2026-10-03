package ai.mengluo.dsh.android;

/** Full-display image geometry. Only our own overlay supplies a mask; other window bounds do not. */
final class PhoneScreenPolicy {
    static final long CAPTURE_TTL_MS = 60_000;
    record Box(int left, int top, int right, int bottom) {
        int width() { return right - left; }
        int height() { return bottom - top; }
        boolean contains(Pixel point) { return point.x >= left && point.y >= top && point.x < right && point.y < bottom; }
    }
    record Pixel(int x, int y) { }
    private PhoneScreenPolicy() { }
    static Box clip(Box value, int width, int height) {
        if (width <= 0 || height <= 0 || (long)width * height > 20_000_000) throw new IllegalArgumentException("screenshot_too_large");
        if (value == null) throw new IllegalArgumentException("stop_ui_unavailable");
        int left = Math.max(0, value.left), top = Math.max(0, value.top);
        int right = Math.min(width, value.right), bottom = Math.min(height, value.bottom);
        if (right <= left || bottom <= top) throw new IllegalArgumentException("screen_unavailable");
        return new Box(left, top, right, bottom);
    }
    static Box ownMask(Box overlay, int width, int height) {
        Box mask = clip(overlay, width, height);
        // Fail explicitly instead of silently handing the model an entirely painted-over display.
        if ((long)mask.width() * mask.height() >= (long)width * height) throw new IllegalArgumentException("invalid_overlay_bounds");
        return mask;
    }
    static Pixel toDisplay(int x, int y, int imageWidth, int imageHeight, int width, int height) {
        if (imageWidth <= 0 || imageHeight <= 0 || width <= 0 || height <= 0
            || x < 0 || y < 0 || x >= imageWidth || y >= imageHeight) throw new IllegalArgumentException("invalid_coordinates");
        // Pixel-centre mapping, using each axis's actual encoded size (including integer rounding).
        return new Pixel((int)((2L * x + 1) * width / (2L * imageWidth)), (int)((2L * y + 1) * height / (2L * imageHeight)));
    }
    static void requireFresh(long capturedAt, long now) {
        if (now < capturedAt || now - capturedAt > CAPTURE_TTL_MS) throw new IllegalArgumentException("stale_screenshot");
    }
    static int duration(String gesture, int requested) {
        int minimum = gesture.equals("long_press") ? 500 : 100;
        if (!gesture.equals("long_press") && !gesture.equals("swipe")) throw new IllegalArgumentException("unsupported_gesture");
        if (requested < minimum || requested > 2000) throw new IllegalArgumentException("invalid_duration");
        return requested;
    }
    static boolean crosses(Box box, Pixel start, Pixel end) {
        // Segment/rectangle clipping: check the whole swipe, not just its endpoints.
        double enter = 0, leave = 1;
        int[] origin = {start.x, start.y}, delta = {end.x - start.x, end.y - start.y};
        int[] low = {box.left, box.top}, high = {box.right, box.bottom};
        for (int axis = 0; axis < 2; axis++) {
            if (delta[axis] == 0) { if (origin[axis] < low[axis] || origin[axis] >= high[axis]) return false; }
            else {
                double first = (low[axis] - origin[axis]) / (double)delta[axis], last = (high[axis] - origin[axis]) / (double)delta[axis];
                enter = Math.max(enter, Math.min(first, last)); leave = Math.min(leave, Math.max(first, last));
                if (enter > leave) return false;
            }
        }
        return true;
    }
}
