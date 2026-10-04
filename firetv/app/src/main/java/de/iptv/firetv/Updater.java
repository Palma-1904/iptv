package de.iptv.firetv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Automatische Updates: Neben app.apk liegt app-version.txt (Build-Nummer aus GitHub).
 * Ist sie höher als die eigene, wird gefragt, geladen und der Android-Installer geöffnet.
 * Höchstens alle 6 Stunden prüfen; „Später“ fragt einen Tag lang nicht mehr.
 */
final class Updater {

    private static final long CHECK_EVERY = 6 * 3600 * 1000L;
    private static final long SNOOZE = 24 * 3600 * 1000L;
    private static boolean running;

    private Updater() {
    }

    static void check(Activity a) {
        if (running || a.isFinishing()) return;
        SharedPreferences prefs = a.getSharedPreferences("iptv", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("updateChecked", 0) < CHECK_EVERY) return;
        if (now < prefs.getLong("updateSnooze", 0)) return;
        running = true;
        prefs.edit().putLong("updateChecked", now).apply();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            int latest = 0;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(BuildConfig.START_URL + "app-version.txt").openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setUseCaches(false);
                if (c.getResponseCode() == 200) {
                    try (InputStream in = c.getInputStream()) {
                        byte[] b = new byte[32];
                        int n = in.read(b);
                        latest = Integer.parseInt(new String(b, 0, Math.max(0, n)).trim());
                    }
                }
                c.disconnect();
            } catch (Exception ignored) {
                // offline o. ä.: nächstes Mal
            }
            final int v = latest;
            main.post(() -> {
                running = false;
                if (v > BuildConfig.VERSION_CODE && !a.isFinishing()) ask(a, prefs);
            });
        }).start();
    }

    private static void ask(Activity a, SharedPreferences prefs) {
        new AlertDialog.Builder(a)
                .setTitle("Neue Version der App")
                .setMessage("Es gibt eine neue Version. Jetzt herunterladen und installieren?\n"
                        + "(Dauert etwa eine Minute; die Einrichtung bleibt erhalten.)")
                .setPositiveButton("Installieren", (d, w) -> download(a))
                .setNegativeButton("Später", (d, w) ->
                        prefs.edit().putLong("updateSnooze", System.currentTimeMillis() + SNOOZE).apply())
                .show();
    }

    private static void download(Activity a) {
        // Ab Android 8 muss die App einmal „unbekannte Apps installieren“ dürfen
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, "Bitte „Fernsehen“ erlauben, Apps zu installieren – danach erneut „Installieren“.",
                    Toast.LENGTH_LONG).show();
            a.getSharedPreferences("iptv", Context.MODE_PRIVATE).edit().remove("updateChecked").apply();
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName())));
            } catch (Exception ignored) {
                // Einstellung nicht vorhanden
            }
            return;
        }
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setPadding(48, 24, 48, 24);
        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("Neue Version wird geladen …")
                .setView(bar)
                .setCancelable(false)
                .show();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            File dir = new File(a.getCacheDir(), "update");
            File apk = new File(dir, "app.apk");
            boolean ok = false;
            try {
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("Ordner");
                HttpURLConnection c = (HttpURLConnection) new URL(BuildConfig.START_URL + "app.apk").openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setUseCaches(false);
                int total = c.getContentLength();
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0;
                    int n, last = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        int pct = total > 0 ? (int) (100 * done / total) : 0;
                        if (pct != last) {
                            last = pct;
                            main.post(() -> bar.setProgress(pct));
                        }
                    }
                }
                c.disconnect();
                ok = apk.length() > 1024 * 1024;
            } catch (Exception ignored) {
                ok = false;
            }
            final boolean success = ok;
            main.post(() -> {
                dlg.dismiss();
                if (!success) {
                    Toast.makeText(a, "Die neue Version konnte nicht geladen werden. Später erneut versuchen.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".files", apk);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    a.startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(a, "Installation konnte nicht gestartet werden.", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }
}
