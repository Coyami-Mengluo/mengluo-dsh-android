package ai.mengluo.dsh.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhoneOverlayGeometryTest {
    @Test public void compactArtworkFitsInsideTheExactEdgeWindow() {
        PhoneOverlayGeometry.IconBox compact = PhoneOverlayGeometry.icon(true, 1f);
        assertEquals(40, compact.width()); assertEquals(48, compact.height());
        assertEquals(32, compact.width() - 2 * compact.horizontalPadding());
        assertEquals(32, compact.height() - 2 * compact.verticalPadding());
        assertTrue("The entire child fits in the compact window", compact.width() < PhoneOverlayGeometry.icon(false, 1f).width());
    }

    @Test public void expandingRestoresTheOriginalArtworkGeometry() {
        PhoneOverlayGeometry.IconBox expanded = PhoneOverlayGeometry.icon(false, 1f);
        assertEquals(52, expanded.width()); assertEquals(52, expanded.height());
        assertEquals(9, expanded.horizontalPadding()); assertEquals(9, expanded.verticalPadding());
        assertEquals(34, expanded.width() - 2 * expanded.horizontalPadding());
        assertEquals(expanded, PhoneOverlayGeometry.icon(false, 1f));
    }

    @Test public void systemDensitiesKeepTheWholeImageBoxInsideTheVisibleSurface() {
        for (float density : new float[] { .5f, .75f, 1f, 1.25f, 1.3333334f, 1.5f, 2f, 2.625f, 3f, 3.5f, 4f }) {
            PhoneOverlayGeometry.IconBox compact = PhoneOverlayGeometry.icon(true, density);
            PhoneOverlayGeometry.IconBox expanded = PhoneOverlayGeometry.icon(false, density);
            assertEquals(Math.round(40 * density), compact.width());
            assertEquals(Math.round(48 * density), compact.height());
            assertTrue(compact.width() < expanded.width());
            for (PhoneOverlayGeometry.IconBox box : new PhoneOverlayGeometry.IconBox[] { compact, expanded }) {
                assertTrue(box.horizontalPadding() >= 0); assertTrue(box.verticalPadding() >= 0);
                assertTrue(box.width() > 2 * box.horizontalPadding());
                assertTrue(box.height() > 2 * box.verticalPadding());
                assertTrue("FIT_CENTER receives an almost-square complete image box",
                    Math.abs((box.width() - 2 * box.horizontalPadding()) - (box.height() - 2 * box.verticalPadding())) <= 1);
            }
        }
    }

    @Test public void invalidDensitiesAreRejected() {
        for (float density : new float[] { 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY }) {
            assertThrows(IllegalArgumentException.class, () -> PhoneOverlayGeometry.icon(true, density));
        }
    }
}
