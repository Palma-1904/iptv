package de.iptv.firetv;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;

/**
 * Andere (zusätzlich installierte) Apps schließen, die im Hintergrund Arbeitsspeicher belegen – z. B. Netflix
 * (~90 MB am Wohnzimmer-Stick). Android erlaubt das nur für Apps im Hintergrund; Amazons eigene Dienste bleiben.
 */
final class AppsCleanup {

    private AppsCleanup() {
    }

    /** Liefert die frei gewordenen MB (Schätzung über den verfügbaren Speicher vorher/nachher). */
    static int closeOthers(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return 0;
        ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);
        int n = 0;
        for (ApplicationInfo a : c.getPackageManager().getInstalledApplications(0)) {
            if ((a.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || a.packageName.equals(c.getPackageName())) continue;
            try {
                am.killBackgroundProcesses(a.packageName);
                n++;
            } catch (Exception ignored) {
                // nicht erlaubt: weiter
            }
        }
        try {
            Thread.sleep(800);   // Speicher wird etwas verzögert frei
        } catch (InterruptedException ignored) {
            // egal
        }
        ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(after);
        int mb = (int) Math.max(0, (after.availMem - before.availMem) / (1024 * 1024));
        Zeit.log("Andere Apps geschlossen (" + n + "), " + mb + " MB frei geworden");
        return mb;
    }

    interface Done {
        void mb(int freed);
    }

    /** Im Hintergrund schließen; done (falls gesetzt) bekommt die frei gewordenen MB. */
    static void closeOthersAsync(Context c, Done done) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            int mb = closeOthers(app);
            if (done != null) done.mb(mb);
        }, "Apps schließen").start();
    }
}
