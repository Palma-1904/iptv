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
    private static final java.util.regex.Pattern NORM =
            java.util.regex.Pattern.compile("^https?://[^/]+/(live|movie|series)/[^/]+/[^/]+/");

    static String norm(String url) {
        if (url == null) return "";
        // vorübersetztes Muster: wird für jeden Eintrag der Liste gebraucht (String.replaceFirst übersetzt jedes Mal neu)
        return NORM.matcher(url).replaceFirst("$1/");
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

    boolean hasResume() {
        return resume.length() > 0;
    }

    void clearResume() {
        for (java.util.Iterator<String> it = resume.keys(); it.hasNext(); ) {
            it.next();
            it.remove();
        }
        prefs.edit().putString("resume", "{}").apply();
    }

    // ---------- Große Listen: zu welcher Serie gehört eine Folge (für „Zuletzt gesehen“ ohne geladene Folgen) ----------

    private JSONObject seriesOf;

    String seriesOf(String url) {
        if (seriesOf == null) {
            try {
                seriesOf = new JSONObject(prefs.getString("seriesOf", "{}"));
            } catch (Exception e) {
                seriesOf = new JSONObject();
            }
        }
        String s = seriesOf.optString(norm(url), "");
        return s.isEmpty() ? null : s;
    }

    void setSeriesOf(String url, String ids) {
        seriesOf(url);
        try {
            seriesOf.put(norm(url), ids);
            if (seriesOf.length() > 200) {   // klein halten: nur Folgen, die noch in „Zuletzt gesehen“/Weiterschauen sind
                JSONObject keep = new JSONObject();
                for (String r : recent) if (seriesOf.has(r)) keep.put(r, seriesOf.get(r));
                seriesOf = keep;
            }
            prefs.edit().putString("seriesOf", seriesOf.toString()).apply();
        } catch (Exception ignored) {
            // egal
        }
    }

    // ---------- Funktionierende Fassung je Live-Sender (Ersatz-Fassungen x-alt) ----------
    private JSONObject goodAlt;

    /** Zuletzt funktionierende Ersatz-Fassung eines Senders (norm-Adresse) oder null = Hauptadresse. */
    String goodAlt(String url) {
        if (goodAlt == null) {
            try {
                goodAlt = new JSONObject(prefs.getString("goodAlt", "{}"));
            } catch (Exception e) {
                goodAlt = new JSONObject();
            }
        }
        String s = goodAlt.optString(norm(url), "");
        return s.isEmpty() ? null : s;
    }

    void setGoodAlt(String url, String alt) {
        String now = goodAlt(url), want = alt == null ? null : norm(alt);
        if (now == null ? want == null : now.equals(want)) return;   // nur bei Änderung schreiben
        try {
            if (want == null) goodAlt.remove(norm(url));
            else goodAlt.put(norm(url), want);
            if (goodAlt.length() > 300) goodAlt = new JSONObject();   // klein halten (wird von selbst neu gelernt)
            prefs.edit().putString("goodAlt", goodAlt.toString()).apply();
        } catch (Exception ignored) {
            // egal
        }
    }

    // ---------- Zuletzt gesehen ----------

    List<String> recent() {
        return recent;
    }

    void clearRecent() {
        recent.clear();
        writeList("recent", recent);
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

    void clearFavLive() {
        favLive.clear();
        writeList("favLive", favLive);
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
