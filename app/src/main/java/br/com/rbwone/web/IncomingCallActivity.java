package br.com.rbwone.web;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** A call-only lock-screen surface. It has no WebView, credentials, microphone or camera. */
public final class IncomingCallActivity extends Activity {
    private PhoneCallAlerts phoneCalls;
    private String owner, nonce;
    private final PhoneCallUnlockGate unlock = new PhoneCallUnlockGate();
    private TextView caller, explanation;
    private Button answer, reject;
    private final Runnable changed = () -> runOnUiThread(this::refresh);

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        phoneCalls = ((RbwApplication) getApplication()).phoneCalls();
        if (!readIntent(getIntent()) || current() == null) { finish(); return; }
        setCallScreenFlags(true);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER);
        root.setPadding(padding, padding, padding, padding); root.setBackgroundColor(Color.WHITE);
        TextView title = new TextView(this); title.setText(R.string.incoming_title); title.setTextSize(24); title.setTextColor(0xff004889);
        root.addView(title);
        caller = new TextView(this); caller.setTextSize(20); caller.setPadding(0, padding, 0, padding); root.addView(caller);
        explanation = new TextView(this); root.addView(explanation);
        answer = new Button(this); answer.setText(R.string.incoming_answer); answer.setOnClickListener(view -> requestAction("answer")); root.addView(answer);
        reject = new Button(this); reject.setText(R.string.incoming_reject); reject.setOnClickListener(view -> requestAction("reject")); root.addView(reject);
        setContentView(root);
        phoneCalls.addListener(changed);
        refresh();
    }
    private boolean readIntent(Intent intent) {
        if (intent == null || !PhoneCallAlerts.SHOW_INCOMING.equals(intent.getAction())) return false;
        owner = intent.getStringExtra("call_owner"); nonce = intent.getStringExtra("call_nonce");
        return owner != null && nonce != null;
    }
    private PhoneCallState.Call current() { return phoneCalls == null ? null : phoneCalls.peek(owner, nonce); }
    private boolean locked() {
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        return keyguard == null || keyguard.isKeyguardLocked();
    }
    private void refresh() {
        if (isFinishing() || isDestroyed()) return;
        PhoneCallState.Call call = current();
        if (call == null) { finish(); return; }
        if (caller == null) return;
        boolean locked = locked();
        caller.setText(locked ? getString(R.string.incoming_private) : call.label);
        explanation.setText(unlock.hasAttempt() ? R.string.incoming_unlock_wait : locked ? R.string.incoming_unlock : R.string.incoming_choose);
        answer.setEnabled(!unlock.hasAttempt()); reject.setEnabled(!unlock.hasAttempt());
    }
    private boolean liveAttempt(PhoneCallUnlockGate.Attempt attempt) {
        return !isFinishing() && !isDestroyed() && unlock.isCurrent(attempt, owner, nonce);
    }
    private void requestAction(String action) {
        if (current() == null) { finish(); return; }
        PhoneCallUnlockGate.Attempt attempt = unlock.begin(owner, nonce, action);
        if (attempt == null) return;
        if (!locked()) { launchAction(attempt, false); return; }
        refresh();
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (keyguard == null) { unlockCancelled(attempt); return; }
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                keyguard.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                    @Override public void onDismissSucceeded() { runOnUiThread(() -> launchAction(attempt, false)); }
                    @Override public void onDismissCancelled() { runOnUiThread(() -> unlockCancelled(attempt)); }
                    @Override public void onDismissError() { runOnUiThread(() -> unlockCancelled(attempt)); }
                });
            } catch (RuntimeException unavailable) { unlockCancelled(attempt); }
        } else {
            Intent confirm = keyguard.createConfirmDeviceCredentialIntent(getString(R.string.incoming_title), getString(R.string.incoming_unlock));
            if (confirm == null) {
                // A gesture-only keyguard has no credential intent. The normal MainActivity waits behind it.
                launchAction(attempt, true);
            } else {
                try { startActivityForResult(confirm, attempt.requestCode); }
                catch (ActivityNotFoundException unavailable) { unlockCancelled(attempt); }
            }
        }
    }
    private void unlockCancelled(PhoneCallUnlockGate.Attempt attempt) {
        if (!liveAttempt(attempt)) return;
        unlock.invalidate(); refresh();
    }
    private void launchAction(PhoneCallUnlockGate.Attempt attempt, boolean waitForManualUnlock) {
        if (!liveAttempt(attempt)) return;
        // Revalidate only the immutable ticket's original call, never the Activity's replacement fields.
        PhoneCallState.Call call = phoneCalls.peek(attempt.owner, attempt.nonce);
        if (call == null) { finish(); return; }
        if (locked() && !waitForManualUnlock) { unlockCancelled(attempt); return; }
        // Only an explicit button action reaches MainActivity, which rechecks lock and action freshness again.
        unlock.invalidate();
        Intent target = phoneCalls.actionIntent(call, attempt.action);
        if (target == null) { finish(); return; }
        try { startActivity(target); finish(); }
        catch (ActivityNotFoundException unavailable) { refresh(); Toast.makeText(this, R.string.incoming_open_failed, Toast.LENGTH_SHORT).show(); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        PhoneCallUnlockGate.Attempt attempt = unlock.forRequestCode(request, owner, nonce);
        if (attempt == null) return;
        if (result == RESULT_OK) launchAction(attempt, false); else unlockCancelled(attempt);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        unlock.invalidate();
        if (!readIntent(intent)) { finish(); return; }
        refresh();
    }
    @Override protected void onResume() { super.onResume(); refresh(); }
    private void hideCaller() { if (caller != null) caller.setText(R.string.incoming_private); }
    @Override protected void onPause() { hideCaller(); super.onPause(); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) refresh(); else hideCaller(); }
    private void setCallScreenFlags(boolean enabled) {
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(enabled); setTurnScreenOn(enabled); }
        else {
            int flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
            if (enabled) getWindow().addFlags(flags); else getWindow().clearFlags(flags);
        }
    }
    @Override protected void onDestroy() {
        unlock.invalidate();
        if (phoneCalls != null) phoneCalls.removeListener(changed);
        setCallScreenFlags(false); super.onDestroy();
    }
}
