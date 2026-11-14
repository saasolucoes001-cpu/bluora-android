package br.com.rbwone.web;

import android.Manifest;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.content.ContextCompat;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONObject;

/** Alerts for a ringing event already received by the active WebView, not a push/SIP client. */
public final class PhoneCallAlerts {
    public static final String OPEN_CALL = "br.com.rbwone.web.OPEN_PHONE_CALL";
    public static final String SHOW_INCOMING = "br.com.rbwone.web.SHOW_INCOMING_CALL";
    public static final String CHANNEL = "rbw-live-calls-v1";
    private static final String TAG = "rbw-live-call";
    private final Context context;
    private final PhoneCallState state = new PhoneCallState();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Runnable> listeners = new LinkedHashSet<>();
    private boolean foreground, disposed;
    private Runnable expiry;

    public PhoneCallAlerts(Context context) {
        this.context = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Chamadas no Bluora", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Chamadas recebidas enquanto o telefone do aplicativo permanece conectado.");
            // The existing WebView owns ringing audio. Avoid a second simultaneous ringtone.
            channel.setSound(null, null); channel.enableVibration(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            NotificationManager manager = this.context.getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
    private long now() { return SystemClock.elapsedRealtime(); }
    public boolean channelEnabled() {
        if (disposed) return false;
        if (Build.VERSION.SDK_INT < 26) return true;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager == null ? null : manager.getNotificationChannel(CHANNEL);
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
    public boolean priorityChannelEnabled() {
        if (disposed) return false;
        if (Build.VERSION.SDK_INT < 26) return true;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager == null ? null : manager.getNotificationChannel(CHANNEL);
        return channel != null && channel.getImportance() >= NotificationManager.IMPORTANCE_HIGH;
    }
    private boolean permissionGranted() {
        return (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                && NotificationManagerCompat.from(context).areNotificationsEnabled() && channelEnabled();
    }
    public boolean fullScreenAllowed() {
        if (disposed) return false;
        if (Build.VERSION.SDK_INT < 34) return true;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager != null && manager.canUseFullScreenIntent();
    }
    public JSONObject status(String permission) throws Exception {
        if (disposed) return new JSONObject().put("supported", false).put("permission", "denied")
                .put("fullScreenPermission", "denied").put("highPriorityChannel", false).put("active", false);
        boolean granted = permissionGranted();
        String effectivePermission = !channelEnabled() || ("granted".equals(permission) && !granted) ? "denied" : permission;
        return new JSONObject().put("supported", true).put("permission", effectivePermission)
                .put("fullScreenPermission", fullScreenAllowed() ? "granted" : "denied")
                .put("highPriorityChannel", priorityChannelEnabled())
                .put("active", !foreground && state.current(now()) != null && granted);
    }
    public void addListener(Runnable listener) { if (!disposed) listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }
    private void changed() { for (Runnable listener : new LinkedHashSet<>(listeners)) listener.run(); }
    public PhoneCallState.Call peek(String owner, String nonce) { return disposed || foreground ? null : state.peek(owner, nonce, now()); }
    public void sync(PhoneCallRequest request) { if (disposed) return; state.sync(request, now()); render(); }
    public void clear(String owner) { if (disposed) return; state.clear(owner); render(); }
    public void reset() { if (disposed) return; state.reset(); cancel(); changed(); }
    public void setForeground(boolean value) { if (disposed) return; foreground = value; render(); }
    public boolean isOwner(String owner) { return !disposed && state.isOwner(owner); }
    public boolean isActionCurrent(PhoneCallState.Action action) { return !disposed && state.pendingActionCurrent(action, now()); }
    public void completeAction(PhoneCallState.Action action) { if (!disposed) state.completeAction(action); }
    Intent actionIntent(PhoneCallState.Call call, String action) {
        if (disposed) return null;
        return new Intent(context, MainActivity.class).setAction(OPEN_CALL)
                .setData(Uri.parse("rbwone://live-call/" + call.nonce + "/" + action))
                .putExtra("call_nonce", call.nonce).putExtra("call_action", action)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    private PendingIntent callPending(Intent intent, int requestCode) {
        Bundle options = null;
        if (Build.VERSION.SDK_INT >= 35) {
            // Scoped to these explicit, immutable, short-lived call intents; no global background-launch grant.
            options = ActivityOptions.makeBasic().setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
        }
        Intent notificationIntent = new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, requestCode, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE, options);
    }
    private PendingIntent pending(PhoneCallState.Call call, String action) { return callPending(actionIntent(call, action), action.hashCode()); }
    private PendingIntent presentation(PhoneCallState.Call call) {
        Intent intent = new Intent(context, IncomingCallActivity.class).setAction(SHOW_INCOMING)
                .setData(Uri.parse("rbwone://incoming-call/" + call.nonce))
                .putExtra("call_owner", call.ownerKey).putExtra("call_nonce", call.nonce)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return callPending(intent, SHOW_INCOMING.hashCode());
    }
    public PhoneCallState.Action takeIntent(Intent intent) {
        if (disposed) return null;
        if (intent == null || !OPEN_CALL.equals(intent.getAction())) return null;
        String nonce = intent.getStringExtra("call_nonce"), action = intent.getStringExtra("call_action");
        intent.setAction(Intent.ACTION_MAIN);
        if (nonce == null || action == null) return null;
        PhoneCallState.Action result = state.respond(nonce, action, now());
        render(); return result;
    }
    private void cancel() {
        if (expiry != null) { handler.removeCallbacks(expiry); expiry = null; }
        NotificationManagerCompat.from(context).cancel(TAG, 0);
    }
    private void render() {
        if (disposed) return;
        try {
            PhoneCallState.Call call = state.current(now());
            // Claim before foreground/permission checks: an existing call must not re-open after minimizing or granting permission.
            boolean firstPresentation = state.claimPresentation(now());
            if (foreground || call == null || !permissionGranted()) { cancel(); return; }
            PendingIntent answer = pending(call, "answer"), reject = pending(call, "reject");
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification).setContentTitle("Chamada no Bluora")
                    .setContentText(call.label).setContentIntent(pending(call, "open")).setCategory(NotificationCompat.CATEGORY_CALL)
                    .setPriority(NotificationCompat.PRIORITY_HIGH).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(new NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                            .setContentTitle("Chamada no Bluora").build())
                    .setOnlyAlertOnce(true).setAutoCancel(false).setTimeoutAfter(Math.max(1, call.expiresAt - now()));
            if (call.acceptLabel.equals("Atender")) {
                builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(
                        new Person.Builder().setName(call.label).setImportant(true).build(), reject, answer));
                if (firstPresentation && priorityChannelEnabled() && fullScreenAllowed()) builder.setFullScreenIntent(presentation(call), true);
            } else {
                // Callback origination remains an explicit Ligar action, with no automatic full-screen presentation.
                builder.addAction(R.drawable.ic_notification, "Ligar", answer)
                        .addAction(R.drawable.ic_notification, "Recusar", reject);
            }
            try { NotificationManagerCompat.from(context).notify(TAG, 0, builder.build()); }
            catch (SecurityException denied) { cancel(); return; }
            if (expiry != null) handler.removeCallbacks(expiry);
            String nonce = call.nonce;
            expiry = () -> { PhoneCallState.Call current = state.current(now()); if (current == null || current.nonce.equals(nonce)) render(); };
            handler.postDelayed(expiry, Math.max(1, call.expiresAt - now()));
        } finally { changed(); }
    }
    public void close() {
        if (disposed) return;
        disposed = true; state.reset(); cancel(); changed(); listeners.clear();
    }
}
