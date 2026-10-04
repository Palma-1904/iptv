package de.iptv.firetv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Fernwartung über ntfy.sh (ohne Schlüssel auf dem Gerät):
 * Den geheimen Kanal legt der Editor als fernwartung.json in den Gist neben die Playlist.
 * Der Stick meldet seinen Status an "<kanal>-status" und holt Befehle von "<kanal>-cmd".
 * Übertragen werden nur Gerätename, Liste, Ansicht, laufender Titel und App-Version.
 */
final class Remote {

    interface Target {
        /** Befehl ausführen (im Bild-Thread). */
        void remote(String action, JSONObject arg);
    }

    private static final String NTFY = "https://ntfy.sh/";
    private static final long POLL_MS = 15000;
    private static final long HEARTBEAT_MS = 5 * 60 * 1000L;
    private static final long MAX_AGE_S = 10 * 60;   // ältere Befehle nicht mehr ausführen

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;
    private static String deviceId, deviceName;
    private static volatile String topic;
    private static volatile String playlistUrl = "", list = "", view = "";
    private static volatile String state = "start", title = "", type = "";
    private static volatile long since = System.currentTimeMillis() / 1000;
    private static String lastCmdId;
    private static final Set<String> done = new HashSet<>();
    private static volatile long lastSent;
    private static boolean started;
    static volatile Target main_target;    // MainActivity
    static volatile Target player_target;  // PlayerActivity (wenn offen)

    private Remote() {
    }

    static synchronized void init(Context c) {
        if (started) return;
        started = true;
        app = c.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences("iptv", Context.MODE_PRIVATE);
        deviceId = p.getString("deviceId", null);
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString().substring(0, 8);
            p.edit().putString("deviceId", deviceId).apply();
        }
        String n = null;
        try {
            n = Settings.Global.getString(app.getContentResolver(), "device_name");
        } catch (Exception ignored) {
            // nicht vorhanden
        }
        deviceName = n != null && !n.isEmpty() ? n : Build.MODEL;
        Thread t = new Thread(Remote::loop, "Fernwartung");
        t.setDaemon(true);
        t.start();
    }

    /** Von der Webapp: welche Playlist dieses Gerät nutzt (daraus folgt der Gist mit fernwartung.json). */
    static void setPlaylist(String url, String v) {
        if (url == null) return;
        boolean changed = !url.equals(playlistUrl) || !v.equals(view);
        playlistUrl = url;
        view = v;
        String file = url.replaceAll("[?#].*$", "");
        file = file.substring(file.lastIndexOf('/') + 1);
        list = file.replaceAll("\\.m3u8?$", "");
        if (changed) {
            topic = null;   // neu laden
            lastSent = 0;
        }
    }

    /** Was läuft gerade? state: playing, paused, stopped, error, sleep, overview */
    static void status(String s, String t, String ty) {
        state = s;
        title = t == null ? "" : t;
        type = ty == null ? "" : ty;
        lastSent = 0;   // gleich melden
    }

    private static void loop() {
        long lastPoll = 0;
        while (true) {
            try {
                Thread.sleep(2000);
                if (topic == null) loadTopic();
                if (topic == null) continue;
                long now = System.currentTimeMillis();
                if (now - lastSent > HEARTBEAT_MS) send();
                if (now - lastPoll > POLL_MS) {
                    lastPoll = now;
                    poll();
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception ignored) {
                // offline o. ä.: später erneut
            }
        }
    }

    /** fernwartung.json aus dem Gist (gleicher Ordner wie die Playlist). */
    private static void loadTopic() throws Exception {
        String url = playlistUrl;
        if (!url.startsWith("https://gist.githubusercontent.com/")) {
            Thread.sleep(30000);
            return;
        }
        String base = url.replaceAll("[?#].*$", "");
        base = base.substring(0, base.lastIndexOf('/') + 1);
        String text = get(base + "fernwartung.json?t=" + System.currentTimeMillis());
        if (text == null) {
            Thread.sleep(10 * 60 * 1000L);   // Fernwartung (noch) nicht eingeschaltet
            return;
        }
        String t = new JSONObject(text).optString("topic", "");
        if (t.matches("[A-Za-z0-9_-]{16,64}")) topic = t;
    }

    private static void send() throws Exception {
        lastSent = System.currentTimeMillis();
        JSONObject o = new JSONObject()
                .put("id", deviceId).put("name", deviceName).put("list", list).put("view", view)
                .put("state", state).put("title", title).put("type", type)
                .put("ver", BuildConfig.VERSION_CODE).put("t", System.currentTimeMillis() / 1000)
                .put("note", note);
        note = "";
        post(NTFY + topic + "-status", o.toString());
    }

    private static void poll() throws Exception {
        String s = lastCmdId != null ? lastCmdId : String.valueOf(since);
        String text = get(NTFY + topic + "-cmd/json?poll=1&since=" + s);
        if (text == null) return;
        for (String line : text.split("\n")) {
            if (line.trim().isEmpty()) continue;
            JSONObject m = new JSONObject(line);
            if (!"message".equals(m.optString("event"))) continue;
            lastCmdId = m.optString("id", lastCmdId);
            JSONObject c;
            try {
                c = new JSONObject(m.optString("message"));
            } catch (Exception e) {
                continue;
            }
            String to = c.optString("to", "");
            boolean forMe = to.equals(deviceId) || to.equals("all") || to.equals("list:" + list);
            String cid = c.optString("id", m.optString("id"));
            if (!forMe || done.contains(cid)) continue;
            if (System.currentTimeMillis() / 1000 - m.optLong("time") > MAX_AGE_S) continue;
            done.add(cid);
            final String action = c.optString("action");
            final JSONObject arg = c.optJSONObject("arg") != null ? c.optJSONObject("arg") : new JSONObject();
            main.post(() -> dispatch(action, arg));
        }
    }

    /** Spielt gerade der Player, bekommt er den Befehl, sonst die Übersicht (MainActivity). */
    private static void dispatch(String action, JSONObject arg) {
        Target p = player_target, m = main_target;
        if (p != null && ("stop".equals(action) || "play".equals(action) || "message".equals(action))) {
            p.remote(action, arg);
        } else if (m != null) {
            m.remote(action, arg);
        }
    }

    /** Nachricht aus der Ferne groß auf dem Fernseher (schließt sich nach 3 Minuten). */
    static void showMessage(android.app.Activity a, String text) {
        if (a == null || a.isFinishing() || text == null || text.isEmpty()) return;
        android.widget.TextView tv = new android.widget.TextView(a);
        tv.setText(text);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 28);
        tv.setPadding(60, 40, 60, 20);
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(a)
                .setTitle("Nachricht")
                .setView(tv)
                .setPositiveButton("OK", null)
                .show();
        main.postDelayed(() -> {
            if (d.isShowing()) d.dismiss();
        }, 3 * 60 * 1000L);
        report("Nachricht angezeigt");
    }

    /** Rückmeldung zu einem Befehl (z. B. „Sender nicht gefunden“). */
    private static volatile String note = "";

    static void report(String text) {
        note = text;
        lastSent = 0;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private static void post(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream o = c.getOutputStream()) {
            o.write(body.getBytes(StandardCharsets.UTF_8));
        }
        c.getResponseCode();
        c.disconnect();
    }

    /** Pfad (Indizes) zu einem Eintrag im Baum, Adresse ohne Zugangsdaten verglichen. */
    static JSONArray pathTo(PlayerActivity.Node root, String norm) {
        if (root == null) return null;
        java.util.ArrayList<Integer> p = new java.util.ArrayList<>();
        if (!find(root, norm, p)) return null;
        JSONArray a = new JSONArray();
        for (int i : p) a.put(i);
        return a;
    }

    private static boolean find(PlayerActivity.Node n, String norm, java.util.List<Integer> p) {
        if (n.item != null) return Memory.norm(n.item.url).equals(norm);
        for (int i = 0; i < n.children.size(); i++) {
            p.add(i);
            if (find(n.children.get(i), norm, p)) return true;
            p.remove(p.size() - 1);
        }
        return false;
    }
}
