package ai.mengluo.dsh.android;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

/** Non-exported, task-scoped system consent. Never clicks or pre-approves the platform prompt. */
public final class PhoneCaptureActivity extends Activity {
    private String owner, lease;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); owner = getIntent().getStringExtra("owner"); lease = getIntent().getStringExtra("lease");
        PhoneControl control = PhoneControl.get(this);
        if (!control.awaitingCapture(owner, lease)) { finish(); return; }
        if (state == null) {
            try {
                MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
                Intent consent = Build.VERSION.SDK_INT >= 34 ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) : manager.createScreenCaptureIntent();
                startActivityForResult(consent, 731);
            } catch (RuntimeException error) { control.captureConsent(owner, lease, RESULT_CANCELED, null); finish(); }
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 731) { PhoneControl.get(this).captureConsent(owner, lease, result, data); finish(); }
    }
}
