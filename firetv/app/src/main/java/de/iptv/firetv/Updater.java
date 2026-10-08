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
 * Prüft bei jedem Start der App, im laufenden Betrieb höchstens alle 6 Stunden;
 * „Später“ fragt einen Tag lang nicht mehr.
 * Per Fernwartung: still im Hintergrund laden, dann direkt das „Installieren“-Fenster von Fire OS
 * (das schreibt Fire OS vor); danach startet die App von selbst wieder (UpdateReceiver).
 * Mit Wächter: keine Frage, der Wächter drückt „Installieren“ selbst – aber nicht, während jemand schaut
 * (dann beim nächsten Öffnen der App oder wenn der Schlaf-Timer die Wiedergabe angehalten hat).
 */
final class Updater {

    private static final long CHECK_EVERY = 6 * 3600 * 1000L;
    private static final long SNOOZE = 24 * 3600 * 1000L;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean running;
    private static int retries;   // Update hängen geblieben: so oft schon neu versucht

    interface Progress {
        void percent(int p);
    }

    private Updater() {
    }

    /** Darf die App neue Versionen installieren? (Ab Android 8 einmalig in den Einstellungen erlauben.) */
    static boolean installAllowed(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    /** Einstellung „Unbekannte Apps installieren“ für diese App öffnen. */
    static void openInstallPermission(Activity a) {
        AutostartService.suppress(5 * 60 * 1000L);
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(a, "Einstellung nicht gefunden: Einstellungen → Mein Fire TV → Entwickleroptionen.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    /** force: auf jeden Fall prüfen (beim Öffnen der App), sonst höchstens alle 6 Stunden. */
    static void check(Activity a, boolean force) {
        if (running || a.isFinishing()) return;
        SharedPreferences prefs = a.getSharedPreferences("iptv", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!force && now - prefs.getLong("updateChecked", 0) < CHECK_EVERY) return;
        if (now < prefs.getLong("updateSnooze", 0)) return;
        running = true;
        prefs.edit().putLong("updateChecked", now).apply();
        new Thread(() -> {
            int v = latestVersion();
            main.post(() -> {
                running = false;
                if (v <= BuildConfig.VERSION_CODE || a.isFinishing()) return;
                if (Waechter.running() && installAllowed(a)) {
                    // Wächter bestätigt selbst – nur nicht mitten in eine laufende Sendung hinein
                    if (a instanceof PlayerActivity && ((PlayerActivity) a).watching()) {
                        prefs.edit().remove("updateChecked").apply();
                        return;
                    }
                    fetchAndInstall(a, false);
                } else {
                    ask(a, prefs);
                }
            });
        }).start();
    }

    /** Fernwartung: ohne Rückfragen laden, dann „Installieren“ (drückt der Wächter, sonst jemand vor Ort). */
    static void remoteUpdate(Activity a) {
        fetchAndInstall(a, true);
    }

    private static void fetchAndInstall(Activity a, boolean report) {
        if (running) {
            if (report) Remote.report("Update läuft bereits");
            return;
        }
        running = true;
        new Thread(() -> {
            int v = latestVersion();
            if (v == 0 || v <= BuildConfig.VERSION_CODE) {
                main.post(() -> running = false);
                if (report) Remote.report(v == 0 ? "Versionsprüfung fehlgeschlagen (Internet?)"
                        : "Schon aktuell (Version " + BuildConfig.VERSION_CODE + ")");
                return;
            }
            if (!installAllowed(a)) {
                main.post(() -> running = false);
                if (report) Remote.report("Installieren noch nicht erlaubt – einmalig vor Ort: ☰ 3 s halten → „Updates erlauben“");
                return;
            }
            Remote.report("Version " + v + " wird im Hintergrund geladen …");
            File apk = downloadApk(a, null);
            main.post(() -> {
                running = false;
                if (apk == null) {
                    Remote.report("Neue Version noch nicht abrufbar (GitHub-Zwischenspeicher) – in 10 Minuten erneut versuchen");
                    return;
                }
                Remote.report("Version " + v + " geladen – " + (Waechter.running()
                        ? "der Wächter installiert sie jetzt" : "wartet am Gerät auf „Installieren“"));
                install(a, apk);
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

    /** Mit Fortschrittsanzeige laden (Update beim Start der App). */
    private static void download(Activity a) {
        if (!installAllowed(a)) {
            Toast.makeText(a, "Bitte „Fernsehen“ erlauben, Apps zu installieren – danach erneut „Installieren“.",
                    Toast.LENGTH_LONG).show();
            a.getSharedPreferences("iptv", Context.MODE_PRIVATE).edit().remove("updateChecked").apply();
            openInstallPermission(a);
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
        new Thread(() -> {
            File apk = downloadApk(a, p -> main.post(() -> bar.setProgress(p)));
            main.post(() -> {
                dlg.dismiss();
                if (apk == null) {
                    Toast.makeText(a, "Die neue Version ist noch nicht abrufbar – die App fragt später erneut.",
                            Toast.LENGTH_LONG).show();
                    a.getSharedPreferences("iptv", Context.MODE_PRIVATE).edit()
                            .putLong("updateSnooze", System.currentTimeMillis() + 15 * 60 * 1000L).apply();
                    return;
                }
                install(a, apk);
            });
        }).start();
    }

    /** Build-Nummer der veröffentlichten App (0 = unbekannt). */
    private static int latestVersion() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(BuildConfig.START_URL + "app-version.txt?t="
                    + System.currentTimeMillis()).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setUseCaches(false);
            int v = 0;
            if (c.getResponseCode() == 200) {
                try (InputStream in = c.getInputStream()) {
                    byte[] b = new byte[32];
                    int n = in.read(b);
                    v = Integer.parseInt(new String(b, 0, Math.max(0, n)).trim());
                }
            }
            c.disconnect();
            return v;
        } catch (Exception e) {
            return 0;
        }
    }

    /** app.apk in den Cache laden; null bei Fehler. Läuft im Hintergrund-Thread. */
    private static File downloadApk(Context a, Progress progress) {
        File dir = new File(a.getCacheDir(), "update");
        File apk = new File(dir, "app.apk");
        try {
            if (!dir.exists() && !dir.mkdirs()) return null;
            HttpURLConnection c = (HttpURLConnection) new URL(BuildConfig.START_URL + "app.apk?t="
                    + System.currentTimeMillis()).openConnection();
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
                    if (pct != last && progress != null) {
                        last = pct;
                        progress.percent(pct);
                    }
                }
            }
            c.disconnect();
            if (apk.length() < 1024 * 1024) return null;
            // GitHub liefert kurz nach einer neuen Version teils noch die alte Datei aus:
            // nur installieren, wenn die geladene Datei wirklich neuer ist als die installierte
            return archiveVersion(a, apk) > BuildConfig.VERSION_CODE ? apk : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Versionsnummer der geladenen APK-Datei (0 = unlesbar). */
    @SuppressWarnings("deprecation")
    private static long archiveVersion(Context c, File apk) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
            if (pi == null) return 0;
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Fire-OS-Installer öffnen; nach dem Update startet die App von selbst wieder (UpdateReceiver). */
    private static void install(Activity a, File apk) {
        a.getSharedPreferences("iptv", Context.MODE_PRIVATE).edit().putBoolean("restartAfterUpdate", true).apply();
        AutostartService.suppress(10 * 60 * 1000L);   // Installieren-Fenster nicht verdrängen
        Waechter.expectInstall(a);                     // Wächter drückt „Installieren“
        // Nach einem erfolgreichen Update läuft dieser Prozess nicht mehr. Läuft er nach 2½ Minuten noch,
        // ist der Installer hängen geblieben (z. B. von anderen Befehlen gestört): noch einmal versuchen.
        if (Waechter.running()) {
            main.postDelayed(() -> {
                if (a.isFinishing() || retries >= 2) return;
                retries++;
                Remote.report("Update nicht abgeschlossen – neuer Versuch (" + retries + ")");
                fetchAndInstall(a, true);
            }, 150 * 1000L);
        }
        Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".files", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        // CLEAR_TASK: alte Installer-Bildschirme („App installiert – Fertig / Öffnen“ vom letzten Update) wegräumen –
        // sonst holt Fire OS den alten Bildschirm wieder hervor und installiert gar nichts
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            a.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(a, "Installation konnte nicht gestartet werden.", Toast.LENGTH_LONG).show();
            Remote.report("Installer konnte nicht geöffnet werden");
        }
    }
}
