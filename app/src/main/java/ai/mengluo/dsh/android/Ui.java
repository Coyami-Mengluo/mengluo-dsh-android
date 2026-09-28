package ai.mengluo.dsh.android;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;

/** Small shared visual vocabulary; no runtime or package-manager behavior. */
final class Ui {
    private final Context context;
    Ui(Context context) { this.context = context; }
    int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    int color(int id) { return context.getColor(id); }
    GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }
    TextView text(String text, int size, boolean bold) {
        TextView view = new TextView(context); view.setText(text); view.setTextSize(size); view.setTextColor(color(R.color.ink));
        view.setFontFeatureSettings("kern"); view.setLineSpacing(dp(3), 1f);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setPadding(0, dp(4), 0, dp(4)); return view;
    }
    TextView caption(String text) {
        TextView view = text(text, 13, false); view.setTextColor(color(R.color.muted)); return view;
    }
    LinearLayout column(int padding) {
        LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(padding), dp(padding), dp(padding), dp(padding)); return view;
    }
    LinearLayout card() {
        LinearLayout view = column(20); GradientDrawable shape = rounded(color(R.color.surface), 24);
        shape.setStroke(dp(1), color(R.color.outline)); view.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(14); view.setLayoutParams(params); return view;
    }
    void gap(LinearLayout parent, int size) { parent.addView(new View(context), new LinearLayout.LayoutParams(1, dp(size))); }
    MaterialButton button(String text, int icon, boolean primary, Runnable action) {
        MaterialButton view = new MaterialButton(context); view.setText(text); view.setAllCaps(false); view.setTextSize(14);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); view.setCornerRadius(dp(16));
        view.setInsetTop(dp(3)); view.setInsetBottom(dp(3)); view.setMinHeight(dp(54)); view.setMinimumHeight(dp(54));
        view.setPadding(dp(18), 0, dp(18), 0); view.setElevation(0); view.setStateListAnimator(null);
        int background = color(primary ? R.color.accent : R.color.tonal);
        int foreground = color(primary ? R.color.on_accent : R.color.accent);
        view.setBackgroundTintList(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}}, new int[]{color(R.color.page),background}));
        ColorStateList tint = new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}}, new int[]{color(R.color.muted),foreground});
        view.setTextColor(tint); view.setIconTint(tint); view.setIconSize(dp(20)); view.setIconPadding(dp(10));
        view.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START); if (icon != 0) view.setIconResource(icon);
        view.setOnClickListener(ignored -> action.run()); return view;
    }
    LinearLayout pair(View first, View second) {
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1); left.rightMargin = dp(5);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1); right.leftMargin = dp(5);
        row.addView(first, left); row.addView(second, right); return row;
    }
    View input(EditText edit) {
        LinearLayout container = column(24); container.setPadding(dp(24), dp(8), dp(24), 0);
        TextInputLayout box = new TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        box.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE); box.setBoxCornerRadii(dp(14), dp(14), dp(14), dp(14));
        box.setHint(edit.getHint()); edit.setHint(null); edit.setTextColor(color(R.color.ink)); edit.setTextSize(14);
        box.addView(edit, new LinearLayout.LayoutParams(-1, -2)); container.addView(box); return container;
    }
}
