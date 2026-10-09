package de.iptv.firetv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Erinnerungen an Sendungen: in der Senderliste ⏯ auf einem Sender = an seine nächste Sendung erinnern.
 * Eine Minute vor Beginn erscheint „Gleich beginnt …“ mit „Umschalten“.
 */
final class Reminders {

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean running;
    static volatile Activity front;   // gerade sichtbarer Bildschirm der App (für den Hinweis)

    private Reminders() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("iptv", Context.MODE_PRIVATE);
    }

    private static JSONArray all(Context c) {
        try {
            return new JSONArray(prefs(c).getString("reminders", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** Erinnerung an- bzw. ausschalten; liefert den Text für die Rückmeldung. */
    static String toggle(Context c, String url, String channel, long start, String title) {
        JSONArray a = all(c);
        JSONArray out = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r == null) continue;
            if (r.optLong("t") == start && Memory.norm(url).equals(r.optString("norm"))) {
                removed = true;
                continue;
            }
            if (r.optLong("t") * 1000 > System.currentTimeMillis() - 10 * 60 * 1000L) out.put(r);   // alte weg
        }
        String when = new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date(start * 1000));
        if (!removed) {
            try {
                out.put(new JSONObject().put("t", start).put("norm", Memory.norm(url))
                        .put("ch", channel).put("title", title));
            } catch (Exception ignored) {
                // egal
            }
        }
        prefs(c).edit().putString("reminders", out.toString()).apply();
        return removed ? "Erinnerung gelöscht: " + when + " " + title
                : "🔔 Erinnerung: " + when + " „" + title + "“ (" + channel + ")";
    }

    static boolean isSet(Context c, String url, long start) {
        JSONArray a = all(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r != null && r.optLong("t") == start && Memory.norm(url).equals(r.optString("norm"))) return true;
        }
        return false;
    }

    /** Läuft im Hintergrund, solange die App lebt: alle 20 s nachsehen. */
    static void start(Context c) {
        if (running) return;
        running = true;
        final Context app = c.getApplicationContext();
        main.post(new Runnable() {
            @Override
            public void run() {
                main.postDelayed(this, 20000);
                check(app);
            }
        });
    }

    private static void check(Context c) {
        JSONArray a = all(c);
        long now = System.currentTimeMillis() / 1000;
        JSONArray keep = new JSONArray();
        JSONObject due = null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r == null) continue;
            long t = r.optLong("t");
            if (due == null && t - 70 <= now && now < t + 5 * 60) {
                due = r;                          // jetzt fällig (1 Minute vorher bis 5 Minuten danach)
            } else if (t + 5 * 60 > now) {
                keep.put(r);
            }
        }
        if (due == null) return;
        prefs(c).edit().putString("reminders", keep.toString()).apply();
        show(due);
    }

    private static void show(JSONObject r) {
        Activity a = front;
        if (a == null || a.isFinishing()) return;
        String when = new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date(r.optLong("t") * 1000));
        final String norm = r.optString("norm");
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("🔔 Gleich beginnt")
                .setMessage(when + "  " + r.optString("title") + "\n" + r.optString("ch"))
                .setPositiveButton("Umschalten", (dd, w) -> {
                    try {
                        JSONObject arg = new JSONObject().put("norm", norm);
                        Remote.Target t = Remote.player_target != null ? Remote.player_target : Remote.main_target;
                        if (t != null) t.remote("play", arg);
                    } catch (Exception ignored) {
                        // egal
                    }
                })
                .setNegativeButton("Schließen", null)
                .show();
        main.postDelayed(() -> { if (d.isShowing()) d.dismiss(); }, 5 * 60 * 1000L);
    }
}
