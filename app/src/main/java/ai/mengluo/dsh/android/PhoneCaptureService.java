package ai.mengluo.dsh.android;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import java.nio.ByteBuffer;
import java.util.function.BiConsumer;

/** User-consented compatibility capture. No audio/video recording, saved tokens or background frames. */
public final class PhoneCaptureService extends Service {
    static PhoneCaptureService active;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private BiConsumer<Bitmap, String> pending;
    private boolean closing;
    private int width, height, contentWidth, contentHeight;
    private final Runnable timeout = () -> deliver(null, "screenshot_timeout");
    private final MediaProjection.Callback projectionEvents = new MediaProjection.Callback() {
        @Override public void onStop() {
            if (!closing) { closeCapture(); PhoneControl.get(PhoneCaptureService.this).stop("screen_capture_stopped"); }
        }
        @Override public void onCapturedContentResize(int w, int h) { contentWidth = w; contentHeight = h; }
    };
    static boolean available() { return active != null && active.projection != null && !active.closing; }
    static void stopCapture() { if (active != null) active.closeCapture(); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        PhoneControl control = PhoneControl.get(this);
        if (intent == null || PhoneControl.STOP.equals(intent.getAction())) { control.stop("user_cancelled"); closeCapture(); return START_NOT_STICKY; }
        String owner = intent.getStringExtra("owner"), lease = intent.getStringExtra("lease");
        if (!control.awaitingCapture(owner, lease) || active != null) { stopSelf(); return START_NOT_STICKY; }
        try {
            NotificationManager notifications = getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel("phone-capture", "手机截图", NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop = PendingIntent.getService(this, 732, new Intent(this, PhoneCaptureService.class).setAction(PhoneControl.STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification = new Notification.Builder(this, "phone-capture").setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("MengLuo · 手机截图已授权").setContentText("仅在模型请求时读取截图；停止操作会结束屏幕共享。")
                .setOngoing(true).addAction(new Notification.Action.Builder(null, "停止手机操作", stop).build()).build();
            startForeground(732, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            Intent data = intent.getParcelableExtra("grant");
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK, data);
            projection.registerCallback(projectionEvents, main);
            Rect screen = getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
            width = screen.width(); height = screen.height();
            // Exactly one virtual display per consent token, including on Android 14+.
            // It has no surface until a tool requests one screenshot.
            display = projection.createVirtualDisplay("MengLuo phone screenshots", width, height, getResources().getConfiguration().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, main);
            if (display == null) throw new IllegalStateException("No capture display");
            active = this; control.captureStarted(owner, lease, true);
        } catch (RuntimeException error) { closeCapture(); control.captureStarted(owner, lease, false); }
        return START_NOT_STICKY;
    }
    void capture(int w, int h, BiConsumer<Bitmap, String> callback) {
        if (!available() || pending != null) { callback.accept(null, "screen_capture_unavailable"); return; }
        if (w <= 0 || h <= 0 || (long)w * h > 20_000_000) { callback.accept(null, "screenshot_too_large"); return; }
        pending = callback;
        try {
            if (reader == null || width != w || height != h) {
                display.setSurface(null); if (reader != null) reader.close();
                width = w; height = h; display.resize(w, h, getResources().getConfiguration().densityDpi);
                reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
                reader.setOnImageAvailableListener(this::frame, main);
            }
            try (Image old = reader.acquireLatestImage()) { /* Discard any frame left by the previous request. */ }
            main.postDelayed(timeout, 5000); display.setSurface(reader.getSurface());
        } catch (RuntimeException error) { deliver(null, "screenshot_unavailable"); }
    }
    private void frame(ImageReader source) {
        Bitmap image = null;
        try (Image frame = source.acquireLatestImage()) {
            if (frame == null || pending == null || source != reader || closing) return;
            if (frame.getWidth() != width || frame.getHeight() != height
                || (contentWidth > 0 && (contentWidth != width || contentHeight != height))) { deliver(null, "screen_capture_size_mismatch"); return; }
            Image.Plane plane = frame.getPlanes()[0]; ByteBuffer bytes = plane.getBuffer();
            int pixelStride = plane.getPixelStride(), rowStride = plane.getRowStride();
            if (pixelStride < 4 || rowStride < width * (long)pixelStride || bytes.remaining() < (long)(height - 1) * rowStride + (long)width * pixelStride)
                throw new IllegalStateException("Unsupported image layout");
            int[] colors = new int[width * height]; byte[] row = new byte[width * pixelStride]; int initial = bytes.position();
            for (int y = 0; y < height; y++) {
                bytes.position(initial + y * rowStride); bytes.get(row);
                for (int x = 0; x < width; x++) { int at = x * pixelStride; colors[y * width + x] = (row[at + 3] & 255) << 24 | (row[at] & 255) << 16 | (row[at + 1] & 255) << 8 | (row[at + 2] & 255); }
            }
            image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); image.setPixels(colors, 0, width, 0, 0, width, height);
        } catch (RuntimeException error) { deliver(null, "screenshot_unavailable"); return; }
        if (image != null) deliver(image, "ok");
    }
    private void deliver(Bitmap image, String status) {
        main.removeCallbacks(timeout); BiConsumer<Bitmap, String> callback = pending; pending = null;
        if (display != null) { try { display.setSurface(null); } catch (RuntimeException ignored) { } }
        if (callback != null) callback.accept(image, status); else if (image != null) image.recycle();
    }
    private void closeCapture() {
        if (closing) return; closing = true;
        if (active == this) active = null;
        deliver(null, "screen_capture_stopped");
        if (display != null) { display.release(); display = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (projection != null) { projection.unregisterCallback(projectionEvents); projection.stop(); projection = null; }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public void onDestroy() { closeCapture(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
