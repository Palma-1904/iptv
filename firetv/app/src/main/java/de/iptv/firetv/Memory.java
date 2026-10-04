package de.iptv.firetv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Was sich der Stick merkt (nur auf diesem Gerät):
 * Weiterschauen-Stellen von Filmen/Folgen, „Zuletzt gesehen“ und Lieblingssender (Live-TV).
 */
final class Memory {

    private static final int MAX_RESUME = 400;
    private static final int MAX_RECENT = 12;

    private static Memory instance;

    private final SharedPreferences prefs;
    private final JSONObject resume;               // url -> [Stelle ms, Länge ms, Zeitpunkt ms]
    private final List<String> recent = new ArrayList<>();
    private final LinkedHashSet<String> favLive = new LinkedHashSet<>();

    /** Adresse ohne Server und Zugangsdaten: ".../movie/USER/PASS/42.mkv" -> "movie/42.mkv".
     *  So bleibt alles erhalten, wenn eine Playlist auf einen anderen Zugang umgestellt wird. */
    static String norm(String url) {
        if (url == null) return "";
        return url.replaceFirst("^https?://[^/]+/(live|movie|series)/[^/]+/[^/]+/", "$1/");
    }

    static synchronized Memory get(Context c) {
        if (instance == null) instance = new Memory(c.getApplicationContext());
        return instance;
    }

    private Memory(Context c) {
        prefs = c.getSharedPreferences("iptv", Context.MODE_PRIVATE);
        JSONObject r = new JSONObject();
        try {
            JSONObject raw = new JSONObject(prefs.getString("resume", "{}"));
            for (Iterator<String> it = raw.keys(); it.hasNext(); ) {
                String k = it.next();
                r.put(norm(k), raw.get(k));
            }
        } catch (Exception ignored) {
            // leer anfangen
        }
        resume = r;
        List<String> rec = new ArrayList<>();
        readList("recent", rec);
        for (String u : rec) if (!recent.contains(norm(u))) recent.add(norm(u));
        List<String> f = new ArrayList<>();
        readList("favLive", f);
        for (String u : f) favLive.add(norm(u));
    }

    private void readList(String key, List<String> into) {
        try {
            JSONArray a = new JSONArray(prefs.getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) into.add(a.getString(i));
        } catch (Exception ignored) {
            // leer anfangen
        }
    }

    private void writeList(String key, Iterable<String> list) {
        JSONArray a = new JSONArray();
        for (String s : list) a.put(s);
        prefs.edit().putString(key, a.toString()).apply();
    }

    // ---------- Weiterschauen ----------

    /** [Stelle, Länge, Zeitpunkt] oder null. */
    long[] resume(String url) {
        JSONArray a = resume.optJSONArray(norm(url));
        if (a == null) return null;
        return new long[]{a.optLong(0), a.optLong(1), a.optLong(2)};
    }

    /** Ab 95 % gilt ein Film/eine Folge als gesehen. */
    static boolean finished(long[] r) {
        return r != null && r[1] > 0 && r[0] >= r[1] * 0.95;
    }

    void putResume(String url, long pos, long len) {
        if (url == null || len <= 0 || pos < 0) return;
        if (pos < 60000 && !finished(new long[]{pos, len, 0})) return;   // unter 1 Minute: nicht merken
        try {
            resume.put(norm(url), new JSONArray().put(Math.min(pos, len)).put(len).put(System.currentTimeMillis()));
            prune();
            prefs.edit().putString("resume", resume.toString()).apply();
        } catch (Exception ignored) {
            // nicht wichtig
        }
    }

    private void prune() {
        if (resume.length() <= MAX_RESUME) return;
        String oldest = null;
        long t = Long.MAX_VALUE;
        for (Iterator<String> it = resume.keys(); it.hasNext(); ) {
            String k = it.next();
            long ts = resume.optJSONArray(k).optLong(2);
            if (ts < t) {
                t = ts;
                oldest = k;
            }
        }
        if (oldest != null) resume.remove(oldest);
    }

    // ---------- Zuletzt gesehen ----------

    List<String> recent() {
        return recent;
    }

    void addRecent(String url) {
        url = norm(url);
        recent.remove(url);
        recent.add(0, url);
        while (recent.size() > MAX_RECENT) recent.remove(recent.size() - 1);
        writeList("recent", recent);
    }

    // ---------- Lieblingssender ----------

    boolean isFavLive(String url) {
        return favLive.contains(norm(url));
    }

    Iterable<String> favLive() {
        return favLive;
    }

    /** Umschalten; liefert den neuen Zustand. */
    boolean toggleFavLive(String url) {
        url = norm(url);
        boolean on = !favLive.remove(url);
        if (on) favLive.add(url);
        writeList("favLive", favLive);
        return on;
    }
}
