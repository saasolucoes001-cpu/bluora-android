package br.com.rbwone.web;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Checks on each foreground opening; installation always goes through Android confirmation. */
public final class AppUpdater {
    private final Activity activity;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled;
    private boolean busy, destroyed, awaitingPermission, skipNextCheck, foreground;
    private AlertDialog dialog;
    private File ready;
    private UpdateRelease available;
    private boolean checked;
    private String updateError;
    private final String sessionId = java.util.UUID.randomUUID().toString();
    private final java.util.List<Runnable> checks = new java.util.ArrayList<>();
    public JSONObject state() {
        JSONObject value = new JSONObject();
        try { value.put("supported", true).put("installedVersion", BuildConfig.VERSION_NAME)
            .put("availableVersion", available == null ? JSONObject.NULL : available.versionName)
            .put("error", updateError == null ? JSONObject.NULL : updateError).put("sessionId", sessionId).put("busy", busy || ready != null).put("checked", checked).put("platform", "android"); } catch (Exception ignored) { }
        return value;
    }
    public void check(Runnable complete) {
        if (destroyed || busy || dialog != null || ready != null) { complete.run(); return; }
        checks.add(complete);
        if (!busy) check();
    }
    public void cancel() {
        cancelled = true;
        if (ready != null) { ready.delete(); ready = null; }
    }
    public void requestInstall() {
        if (!visible() || busy || available == null || dialog != null) throw new IllegalStateException("Confira novamente a atualização disponível.");
        download(available);
    }
    public AppUpdater(Activity activity) { this.activity = activity; }
    private boolean visible() { return foreground && !destroyed && !activity.isFinishing() && !activity.isDestroyed(); }
    private static HttpURLConnection connect(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000); connection.setReadTimeout(15_000); connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
        return connection;
    }
    public void check() {
        foreground = true;
        if (skipNextCheck) skipNextCheck = false;
        if (destroyed || busy || dialog != null || ready != null) return;
        busy = true;
        worker.execute(() -> {
            UpdateRelease release = null;
            try {
                HttpURLConnection connection = connect(UpdateRelease.MANIFEST + "?t=" + System.currentTimeMillis());
                try {
                    if (connection.getResponseCode() != 200) throw new IOException();
                    try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[4096]; int count;
                        while ((count = input.read(buffer)) != -1) { bytes.write(buffer, 0, count); if (bytes.size() > 16_384) throw new IOException(); }
                        release = new UpdateRelease(new JSONObject(bytes.toString(StandardCharsets.UTF_8.name())));
                    }
                } finally { connection.disconnect(); }
            } catch (Exception ignored) { /* Offline checking never blocks opening the app. */ }
            UpdateRelease found = release;
            activity.runOnUiThread(() -> {
                busy = false;
                checked = found != null;
                available = found != null && found.isNewer(BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT) ? found : null;
                java.util.List<Runnable> callbacks = new java.util.ArrayList<>(checks); checks.clear();
                for (Runnable callback : callbacks) callback.run();
            });
        });
    }
    private void download(UpdateRelease release) {
        busy = true; cancelled = false; updateError = null;
        worker.execute(() -> {
            File directory = new File(activity.getCacheDir(), "updates");
            File file = new File(directory, "rbw-" + java.util.UUID.randomUUID() + ".apk"); boolean valid = false;
            try {
                if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) throw new IOException();
                HttpURLConnection connection = connect(release.apkUrl);
                try {
                    if (connection.getResponseCode() != 200) throw new IOException();
                    try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(file)) { release.copyVerified(input, output, () -> cancelled); }
                } finally { connection.disconnect(); }
                validatePackage(file, release); valid = !cancelled;
            } catch (Exception ignored) { /* Do not expose provider internals or credentials. */ }
            if (!valid) file.delete();
            boolean success = valid;
            activity.runOnUiThread(() -> {
                busy = false;
                if (destroyed) { file.delete(); return; }
                if (success) { ready = file; if (visible()) install(); }
                else if (!cancelled) updateError = "Não foi possível baixar ou validar o instalador. Confira a conexão e tente novamente.";
            });
        });
    }
    @SuppressWarnings("deprecation")
    private void validatePackage(File file, UpdateRelease release) throws Exception {
        PackageManager pm = activity.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        PackageInfo installed = pm.getPackageInfo(activity.getPackageName(), flags);
        if (archive == null || !activity.getPackageName().equals(archive.packageName) || archive.versionCode != release.versionCode
                || !release.versionName.equals(archive.versionName) || archive.versionCode <= installed.versionCode) throw new IOException();
        Signature[] incoming = Build.VERSION.SDK_INT >= 28 && archive.signingInfo != null ? archive.signingInfo.getApkContentsSigners() : archive.signatures;
        Signature[] current = Build.VERSION.SDK_INT >= 28 && installed.signingInfo != null ? installed.signingInfo.getApkContentsSigners() : installed.signatures;
        if (incoming == null || current == null || incoming.length == 0 || !new HashSet<>(Arrays.asList(incoming)).equals(new HashSet<>(Arrays.asList(current)))) throw new IOException();
    }
    private void install() {
        if (!visible() || ready == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                dialog = new AlertDialog.Builder(activity).setTitle("Permitir atualização")
                        .setMessage("Autorize o Bluora a instalar esta atualização na próxima tela. O Android ainda pedirá sua confirmação.")
                        .setNegativeButton("Depois", (d,w) -> ready = null)
                        .setPositiveButton("Abrir configurações", (d,w) -> {
                            try { awaitingPermission = true; activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()))); }
                            catch (Exception ignored) { awaitingPermission = false; ready = null; toast("Não foi possível abrir as configurações de instalação."); }
                        }).setOnCancelListener(d -> ready = null).create();
                AlertDialog permission = dialog;
                permission.setOnDismissListener(d -> { if (dialog == permission) dialog = null; }); permission.show(); return;
            }
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", ready);
            skipNextCheck = true;
            activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            ready = null;
        } catch (Exception ignored) { ready = null; toast("Não foi possível abrir o instalador do Android."); }
    }
    public void resume() {
        if (!awaitingPermission) { if (ready != null && dialog == null && !busy) install(); return; }
        awaitingPermission = false;
        if (Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls()) install();
        else { ready = null; toast("Atualização adiada. A instalação não foi autorizada."); }
    }
    private void toast(String text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }
    public void pause() { foreground = false; }
    public void close() { destroyed = true; cancelled = true; if (dialog != null) dialog.dismiss(); worker.shutdownNow(); }
}
