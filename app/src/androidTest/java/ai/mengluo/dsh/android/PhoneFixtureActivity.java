package ai.mengluo.dsh.android;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;

/** This separate APK contains no accounts, network clients or user files. */
public class PhoneFixtureActivity extends Activity {
    private android.content.BroadcastReceiver revisionCommands;
    private final android.os.Handler revisionHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private TextView revisionTicker, revisionCount;
    private EditText revisionInput;
    private Button revisionTarget;
    private ScrollView revisionScroll, revisionOtherScroll;
    private android.app.AlertDialog revisionDialog;
    private int tickerStep;
    private boolean ticking;
    private int hintStep;
    private boolean hintTicking;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!ticking || revisionTicker == null) return;
            revisionTicker.setText((++tickerStep % 2 == 0) ? "Rotating hint: apples" : "Rotating hint: lemons");
            revisionHandler.postDelayed(this, 200);
        }
    };
    private final Runnable hintTick = new Runnable() {
        @Override public void run() {
            if (!hintTicking || revisionInput == null) return;
            revisionInput.setHint(switch (++hintStep % 3) { case 0 -> ""; case 1 -> "Search rotating apples"; default -> "Search rotating lemons"; });
            revisionHandler.postDelayed(this, 200);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(80, 100, 80, 40);
        page.setBackgroundColor(android.graphics.Color.rgb(241, 248, 252));
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        TextView title = new TextView(this); title.setText("MengLuo isolated phone-control fixture"); title.setTextSize(24); page.addView(title);
        TextView count = new TextView(this); count.setText("Count: 0"); count.setTextSize(28); page.addView(count);
        Button plus = new Button(this); plus.setText("Increment test counter"); int[] value = {0}; plus.setOnClickListener(v -> count.setText("Count: " + (++value[0]))); page.addView(plus);
        EditText input = new EditText(this); input.setHint("Non-sensitive test input"); page.addView(input);
        EditText password = new EditText(this); password.setInputType(129); password.setText("private-fixture-value"); password.setContentDescription("private-fixture-description"); page.addView(password);
        Button hide = new Button(this); hide.setText("Hide sensitive fixture"); hide.setOnClickListener(v -> page.removeView(password)); page.addView(hide);
        TextView holds = new TextView(this); holds.setText("Long presses: 0"); page.addView(holds);
        int[] longPresses = {0}; plus.setOnLongClickListener(v -> { holds.setText("Long presses: " + (++longPresses[0])); return true; });
        TextView swipes = new TextView(this); swipes.setText("Swipes: 0"); page.addView(swipes);
        TextView pad = new TextView(this); pad.setText("Gesture pad"); pad.setContentDescription("Gesture pad"); pad.setClickable(true);
        pad.setGravity(android.view.Gravity.CENTER); pad.setBackgroundColor(android.graphics.Color.rgb(190, 222, 247));
        float[] start = new float[2]; int[] swipeCount = {0};
        pad.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) { start[0] = event.getX(); start[1] = event.getY(); }
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                if (Math.abs(event.getX() - start[0]) + Math.abs(event.getY() - start[1]) > 40) swipes.setText("Swipes: " + (++swipeCount[0]));
                else v.performClick();
            }
            return true;
        });
        page.addView(pad, new LinearLayout.LayoutParams(-1, 140));
        Button send = new Button(this); send.setText("Send test message"); send.setOnClickListener(v -> count.setText("CONSEQUENTIAL ACTION EXECUTED")); page.addView(send);
        if (getIntent().getBooleanExtra("revisionFixture", false)) {
            if (!getPackageName().equals("ai.mengluo.dsh.android.phoneprobe.test")) throw new IllegalStateException("Isolated fixture only");
            page.removeView(password);
            revisionCount = count; revisionTarget = plus; revisionTarget.setId(android.R.id.button1); revisionInput = input;
            revisionTicker = new TextView(this); revisionTicker.setText("Rotating hint: apples"); revisionTicker.setTextSize(18);
            // Fixed sibling geometry: changing this text must not move or rename the target.
            page.addView(revisionTicker, 1, new LinearLayout.LayoutParams(-1, 60));
            revisionOtherScroll = new ScrollView(this);
            TextView unrelated = new TextView(this); unrelated.setText("Unrelated scroll region\nFixture line two\nFixture line three\nFixture line four");
            revisionOtherScroll.addView(unrelated, new ScrollView.LayoutParams(-1, 240));
            page.addView(revisionOtherScroll, 4, new LinearLayout.LayoutParams(-1, 60));
            TextView padding = new TextView(this); padding.setText("Non-interactive fixture padding");
            page.addView(padding, new LinearLayout.LayoutParams(-1, 1800));
            revisionScroll = new ScrollView(this); revisionScroll.setFillViewport(true); revisionScroll.addView(page);
            setContentView(revisionScroll); registerRevisionCommands();
        } else setContentView(page);
    }
    private void registerRevisionCommands() {
        revisionCommands = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context ignored, android.content.Intent intent) {
                android.os.ResultReceiver reply = intent.getParcelableExtra("reply");
                if (reply == null) return;
                Bundle result = new Bundle();
                try {
                    switch (intent.getStringExtra("operation")) {
                        case "paint" -> paintOwnView(result);
                        case "startTicker" -> { ticking = true; revisionHandler.removeCallbacks(tick); tick.run(); }
                        case "prepareHint" -> {
                            revisionInput.setShowSoftInputOnFocus(false); revisionInput.setText(""); revisionInput.setHint("Search rotating apples");
                            revisionInput.setId(android.R.id.edit);
                            revisionScroll.setFocusableInTouchMode(true); revisionScroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);
                            revisionScroll.requestFocus(); hintStep = 0;
                        }
                        case "startHintTicker" -> { hintTicking = true; revisionHandler.removeCallbacks(hintTick); hintTick.run(); }
                        case "moveTarget" -> revisionTarget.setTranslationX(80);
                        case "ordinaryLabel" -> revisionTarget.setText("Increment fixture counter");
                        case "dangerousLabel" -> revisionTarget.setText("Delete all test files");
                        case "scroll" -> revisionScroll.scrollTo(0, 40);
                        case "scrollUnrelated" -> revisionOtherScroll.scrollTo(0, 40);
                        case "newWindow" -> revisionDialog = new android.app.AlertDialog.Builder(PhoneFixtureActivity.this)
                            .setTitle("Isolated replacement window").setMessage("No phone data is used.").create();
                        case "reset" -> {
                            ticking = false; revisionHandler.removeCallbacks(tick);
                            hintTicking = false; revisionHandler.removeCallbacks(hintTick);
                            revisionTarget.setTranslationX(0); revisionTarget.setText("Increment test counter"); revisionScroll.scrollTo(0, 0);
                            revisionOtherScroll.scrollTo(0, 0);
                            if (revisionDialog != null) { revisionDialog.dismiss(); revisionDialog = null; }
                        }
                        case "count" -> { }
                        case "finish" -> finish();
                        default -> throw new IllegalArgumentException("Unknown fixture command");
                    }
                    if ("newWindow".equals(intent.getStringExtra("operation"))) revisionDialog.show();
                    result.putString("count", revisionCount.getText().toString());
                    result.putInt("ticks", tickerStep);
                    result.putInt("hintTicks", hintStep); result.putBoolean("inputFocused", revisionInput.isFocused()); result.putInt("inputLength", revisionInput.getText().length());
                    reply.send(0, result);
                } catch (Exception error) { result.putString("error", error.getClass().getSimpleName() + ": " + error.getMessage()); reply.send(1, result); }
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter(getPackageName() + ".REVISION_FIXTURE");
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(revisionCommands, filter, android.content.Context.RECEIVER_EXPORTED);
        else registerReceiver(revisionCommands, filter);
    }
    private void paintOwnView(Bundle result) throws java.io.IOException {
        android.graphics.Point display = new android.graphics.Point(); getWindowManager().getDefaultDisplay().getRealSize(display);
        if (display.x <= 0 || display.y <= 0 || (long)display.x * display.y > 20_000_000) throw new IllegalStateException("Unexpected fixture display size");
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(display.x, display.y, android.graphics.Bitmap.Config.ARGB_8888);
        try (java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap); canvas.drawColor(android.graphics.Color.WHITE);
            android.view.View decor = getWindow().getDecorView(); int[] origin = new int[2]; decor.getLocationOnScreen(origin);
            canvas.translate(origin[0], origin[1]); decor.draw(canvas); // Only this harmless test activity, never screen capture.
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes) || bytes.size() > 700_000) throw new IllegalStateException("Unexpected fixture image size");
            result.putByteArray("image", bytes.toByteArray()); result.putInt("width", display.x); result.putInt("height", display.y);
            int[] target = new int[2]; revisionTarget.getLocationOnScreen(target);
            result.putInt("targetX", target[0] + revisionTarget.getWidth() / 5); result.putInt("targetY", target[1] + revisionTarget.getHeight() / 2);
            revisionInput.getLocationOnScreen(target);
            result.putInt("inputX", target[0] + revisionInput.getWidth() / 5); result.putInt("inputY", target[1] + revisionInput.getHeight() / 2);
        } finally { bitmap.recycle(); }
    }
    @Override protected void onDestroy() {
        ticking = false; revisionHandler.removeCallbacks(tick);
        hintTicking = false; revisionHandler.removeCallbacks(hintTick);
        if (revisionCommands != null) { unregisterReceiver(revisionCommands); revisionCommands = null; }
        if (revisionDialog != null) { revisionDialog.dismiss(); revisionDialog = null; }
        super.onDestroy();
    }
}
