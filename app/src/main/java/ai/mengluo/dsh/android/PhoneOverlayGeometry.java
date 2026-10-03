package ai.mengluo.dsh.android;

/** Pixel dimensions shared by the edge window and its complete, scaled artwork. */
final class PhoneOverlayGeometry {
    private PhoneOverlayGeometry() { }

    record IconBox(int width, int height, int horizontalPadding, int verticalPadding) { }

    static IconBox icon(boolean compact, float density) {
        if (!Float.isFinite(density) || density <= 0) throw new IllegalArgumentException("Invalid display density");
        // The compact 40x48dp surface remains wholly on-screen and touchable;
        // its 32x32dp artwork is fitted inside it rather than cut in half.
        return compact
            ? new IconBox(dp(40, density), dp(48, density), dp(4, density), dp(8, density))
            : new IconBox(dp(52, density), dp(52, density), dp(9, density), dp(9, density));
    }

    private static int dp(int value, float density) { return Math.round(value * density); }
}
