package ai.mengluo.dsh.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.widget.ImageView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class IconDisplayTest {
    @Test public void densitySpecificBitmapsKeepTransparencyAndAvoidOversizedDecoding() {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var resources = context.getResources();
        for (int density : new int[]{160, 240, 320, 480, 640}) {
            for (int[] spec : new int[][]{{R.drawable.app_icon_bitmap, 68}, {R.drawable.menu_icon_bitmap, 34}, {R.mipmap.ic_launcher_bitmap, 48}}) {
                Bitmap bitmap = ((BitmapDrawable) resources.getDrawableForDensity(spec[0], density, context.getTheme())).getBitmap();
                assertEquals(Math.round(spec[1] * density / 160f), bitmap.getWidth());
                assertEquals(bitmap.getWidth(), bitmap.getHeight());
                assertTrue(bitmap.hasAlpha());
                assertEquals("Transparent artwork background must stay transparent", 0, bitmap.getPixel(0, 0) >>> 24);
            }
        }
        assertEquals(R.mipmap.ic_launcher, context.getApplicationInfo().icon);
        BitmapDrawable launcher = (BitmapDrawable) context.getPackageManager().getApplicationIcon(context.getApplicationInfo());
        assertTrue(launcher.getPaint().isFilterBitmap());
        assertTrue(launcher.hasMipMap());
    }

    @Test public void floatingArtworkFitsWithinCircleAndUsesFilteredDisplaySizedBitmap() throws Exception {
        AtomicReference<Rect> screenBounds = new AtomicReference<>();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                ImageView ball = activity.findViewById(android.R.id.content).findViewWithTag("menu-ball");
                assertTrue(ball.getClipToOutline());
                float density = activity.getResources().getDisplayMetrics().density;
                assertEquals(Math.round(9 * density), ball.getPaddingLeft());
                assertEquals(ball.getPaddingLeft(), ball.getPaddingBottom());
                BitmapDrawable drawable = (BitmapDrawable) ball.getDrawable();
                assertTrue(drawable.getPaint().isFilterBitmap());
                assertTrue(drawable.hasMipMap());
                assertTrue("No multi-thousand-pixel texture for a small floating button", drawable.getBitmap().getWidth() <= Math.ceil(36 * density));
                RectF bounds = new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
                ball.getImageMatrix().mapRect(bounds); bounds.offset(ball.getPaddingLeft(), ball.getPaddingTop());
                float center = ball.getWidth() / 2f;
                for (float x : new float[]{bounds.left, bounds.right}) for (float y : new float[]{bounds.top, bounds.bottom})
                    assertTrue("Even the table corners must fit inside the circular outline", Math.hypot(x - center, y - center) < center);
                try {
                    var expand = MainActivity.class.getDeclaredMethod("expandBall"); expand.setAccessible(true); expand.invoke(activity);
                    int[] position = new int[2]; ball.getLocationOnScreen(position);
                    screenBounds.set(new Rect(position[0], position[1], position[0] + ball.getWidth(), position[1] + ball.getHeight()));
                } catch (Exception error) { throw new AssertionError(error); }
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(200); // Let the render thread present the expanded button before capturing it.
            Bitmap screen = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull(screen);
            Rect bounds = screenBounds.get();
            Bitmap preview = Bitmap.createBitmap(bounds.width() * 2, bounds.height(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(preview); canvas.drawColor(0xfff5f6fa);
            canvas.drawBitmap(screen, bounds, new Rect(0, 0, bounds.width(), bounds.height()), null); screen.recycle();
            int inkPixels = 0;
            for (int y = 0; y < bounds.height(); y++) for (int x = 0; x < bounds.width(); x++) {
                int pixel = preview.getPixel(x, y);
                if (android.graphics.Color.blue(pixel) - android.graphics.Color.red(pixel) > 25) inkPixels++;
            }
            assertTrue("The actual floating circle must contain the blue character, not just an empty background", inkPixels > bounds.width());
            var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            BitmapDrawable launcher = (BitmapDrawable) context.getPackageManager().getApplicationIcon(context.getApplicationInfo());
            int size = Math.round(48 * context.getResources().getDisplayMetrics().density);
            int gap = (bounds.height() - size) / 2;
            launcher.setBounds(bounds.width() + gap, gap, bounds.width() + gap + size, gap + size); launcher.draw(canvas);
            File dir = new File(context.getExternalFilesDir(null), "verification"); dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(dir, "icon-render.png"))) { preview.compress(Bitmap.CompressFormat.PNG, 100, out); }
            preview.recycle();
        }
    }
}
