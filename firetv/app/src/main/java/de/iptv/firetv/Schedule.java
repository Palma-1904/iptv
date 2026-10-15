package de.iptv.firetv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.util.Calendar;

/**
 * Zeitplan je Stick (Admin-Fernbedienung → ⚙️): täglich zu festen Zeiten ausschalten (Player stoppen, mit Wächter
 * Standby) und einschalten (aufwecken, App öffnen – die Seniorenansicht startet den letzten Sender).
 * Dazu der Schlaf-Timer (Stunden ohne Taste, 0 = aus). Gespeichert in den Prefs „iptv“: schedOff/schedOn („HH:MM“
 * oder leer), sleepH. Wecker per AlarmManager (RTC_WAKEUP holt den Stick auch aus dem Standby), nach dem Hochfahren,
 * beim App-Start und nach jedem Auslösen neu gestellt.
 */
public class Schedule extends BroadcastReceiver {
    private static final String OFF = "de.iptv.firetv.SCHED_OFF", ON = "de.iptv.firetv.SCHED_ON";

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("iptv", Context.MODE_PRIVATE);
    }

    /** Schlaf-Timer in Millisekunden (0 = aus), Standard 3 Stunden. */
    static long sleepMs(Context c) {
        return prefs(c).getInt("sleepH", 3) * 3600 * 1000L;
    }

    /** Für den Status (cfg.sched). */
    static JSONObject json(Context c) {
        SharedPreferences p = prefs(c);
        try {
            return new JSONObject().put("off", p.getString("schedOff", "")).put("on", p.getString("schedOn", ""))
                    .put("sleepH", p.getInt("sleepH", 3));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Fernwartung „schedule“ {off, on, sleepH}: speichern und Wecker neu stellen. */
    static String set(Context c, JSONObject arg) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (arg.has("off")) e.putString("schedOff", valid(arg.optString("off")));
        if (arg.has("on")) e.putString("schedOn", valid(arg.optString("on")));
        if (arg.has("sleepH")) e.putInt("sleepH", Math.max(0, Math.min(12, arg.optInt("sleepH", 3))));
        e.apply();
        apply(c);
        SharedPreferences p = prefs(c);
        String off = p.getString("schedOff", ""), on = p.getString("schedOn", "");
        int h = p.getInt("sleepH", 3);
        return "Zeitplan: " + (off.isEmpty() ? "kein Aus" : "aus um " + off) + ", "
                + (on.isEmpty() ? "kein An" : "an um " + on) + " · Schlaf-Timer " + (h == 0 ? "aus" : h + " Std.");
    }

    private static String valid(String t) {
        return t != null && t.matches("([01]\\d|2[0-3]):[0-5]\\d") ? t : "";
    }

    /** Beide Wecker (neu) stellen bzw. löschen. */
    static void apply(Context c) {
        SharedPreferences p = prefs(c);
        arm(c, OFF, p.getString("schedOff", ""), 1);
        arm(c, ON, p.getString("schedOn", ""), 2);
    }

    private static void arm(Context c, String action, String hhmm, int code) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(c, Schedule.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(c, code, i, flags);
        if (am == null) return;
        am.cancel(pi);
        if (hhmm == null || hhmm.isEmpty()) return;
        Calendar at = Calendar.getInstance();
        at.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hhmm.substring(0, 2)));
        at.set(Calendar.MINUTE, Integer.parseInt(hhmm.substring(3, 5)));
        at.set(Calendar.SECOND, 0);
        at.set(Calendar.MILLISECOND, 0);
        if (at.getTimeInMillis() <= System.currentTimeMillis() + 30000) at.add(Calendar.DAY_OF_MONTH, 1);
        try {
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.getTimeInMillis(), pi);
            else am.setExact(AlarmManager.RTC_WAKEUP, at.getTimeInMillis(), pi);
        } catch (SecurityException e) {   // neuere Android-Versionen ohne Recht für genaue Wecker: ungefähr reicht
            am.set(AlarmManager.RTC_WAKEUP, at.getTimeInMillis(), pi);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Context c = context.getApplicationContext();
        Remote.init(c);
        if (OFF.equals(intent.getAction())) {
            Remote.scheduledOff();
        } else if (ON.equals(intent.getAction())) {
            Remote.wake();
        }
        apply(c);   // nächster Tag
    }
}
