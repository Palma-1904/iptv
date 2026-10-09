package de.iptv.firetv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Zustandsbericht für die Fernwartung: WLAN, Hänger/Fehler heute, letzter Fehler. */
final class Diag {

    private static String day = "";
    private static int stalls, errors, altSwitches;
    private static String lastError = "";
    private static long lastErrorAt;
    private static final long started = System.currentTimeMillis();

    private Diag() {
    }

    private static void newDay() {
        String d = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date());
        if (!d.equals(day)) {
            day = d;
            stalls = errors = altSwitches = 0;
        }
    }

    /** Bild hing und wurde neu gestartet. */
    static synchronized void stall() {
        newDay();
        stalls++;
    }

    /** Sender ging nicht (nach allen Versuchen). */
    static synchronized void error(String what) {
        newDay();
        errors++;
        lastError = what == null ? "" : what;
        lastErrorAt = System.currentTimeMillis();
    }

    /** Auf eine Ersatz-Fassung umgeschaltet. */
    static synchronized void altSwitch() {
        newDay();
        altSwitches++;
    }

    static synchronized JSONObject json(Context c) {
        newDay();
        JSONObject o = new JSONObject();
        try {
            o.put("stalls", stalls).put("errors", errors).put("alt", altSwitches)
                    .put("uptimeMin", (System.currentTimeMillis() - started) / 60000);
            if (lastErrorAt > 0) {
                o.put("lastError", lastError).put("lastErrorAt", lastErrorAt / 1000);
            }
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) {
                o.put("net", "offline");
            } else if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
                WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                o.put("net", "wlan");
                if (wm != null) o.put("rssi", wm.getConnectionInfo().getRssi());
            } else {
                o.put("net", "lan");
            }
        } catch (Exception ignored) {
            // nicht wichtig
        }
        return o;
    }
}
