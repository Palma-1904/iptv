package de.iptv.firetv;

import android.os.SystemClock;
import android.util.Log;

/** Zeitmessung fürs Protokoll (adb logcat -s IPTVZEIT): wo der Stick Zeit verliert. */
final class Zeit {
    static final long START = SystemClock.elapsedRealtime();

    private Zeit() {
    }

    static long now() {
        return SystemClock.elapsedRealtime();
    }

    static void log(String what) {
        Runtime r = Runtime.getRuntime();
        long mb = (r.totalMemory() - r.freeMemory()) / (1024 * 1024);
        Log.i("IPTVZEIT", "+" + (now() - START) + "ms " + what + "  [Speicher " + mb + "/" + (r.maxMemory() / (1024 * 1024)) + " MB]");
    }

    /** Nur melden, wenn es länger als ms gedauert hat. */
    static void slow(String what, long since, long ms) {
        long d = now() - since;
        if (d >= ms) log(what + " " + d + "ms");
    }
}
