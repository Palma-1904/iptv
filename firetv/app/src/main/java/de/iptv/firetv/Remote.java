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
 * Der Stick meldet seinen Status an "<kanal>-status" und empfängt Befehle von "<kanal>-cmd" über eine
 * dauerhaft offene Verbindung (kommen sofort an; wenige Anfragen – ntfy.sh bremst sonst bei mehreren
 * Sticks am selben Anschluss).
 * Übertragen werden nur Gerätename, Liste, Ansicht, laufender Titel und App-Version.
 * ntfy.sh erlaubt ohne Konto nur 250 Nachrichten am Tag je Internet-Anschluss: darum meldet sich der Stick
 * von selbst nur alle 3 Stunden; Änderungen (Senderwechsel usw.) nur, solange der Editor zuschaut
 * (Befehl „watch“ beim Öffnen der Fernwartung), Antworten auf Befehle immer.
 */
final class Remote {

    interface Target {
        /** Befehl ausführen (im Bild-Thread). */
        void remote(String action, JSONObject arg);
    }

    private static final String NTFY = "https://ntfy.sh/";
    private static final long HEARTBEAT_MS = 3 * 3600 * 1000L;
    private static final long MIN_GAP_MS = 10000;           // Änderungen zusammenfassen (schnelles Umschalten)
    private static volatile long watchUntil;                 // so lange schaut der Editor zu
    private static volatile boolean dirty;                   // etwas Neues zu melden
    private static final long MAX_AGE_S = 10 * 60;   // ältere Befehle nicht mehr ausführen

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;
    private static String deviceId, deviceName;
    private static volatile String topic;
    private static volatile String fb;   // Firebase-Datenbank (aus fernwartung.json), sonst ntfy.sh
    private static volatile String playlistUrl = "", list = "", view = "";
    private static volatile String state = "start", title = "", type = "";
    private static volatile long since = System.currentTimeMillis() / 1000;
    private static String lastCmdId;
    private static final Set<String> done = new HashSet<>();
    private static volatile long lastSent;
    private static boolean started;
    // Handy-Fernbedienung (fernbedienung.html): eigener geheimer Schlüssel je Gerät (QR-Code in der Einrichtung),
    // gleiche Ablage wie die Fernwartung: <schlüssel>/status/<gerät>, /inbox/<gerät>, /sender/<gerät>
    private static volatile String handyKey;
    private static volatile JSONArray nowEpg = new JSONArray();   // laufende und nächste Sendung
    private static volatile String nowNorm = "";
    private static String lastChannels = "";
    private static long channelsSent;
    /** Vom Handy erlaubt (Schlüssel am Fernseher sichtbar): nur Bedienen, keine Einstellungen. */
    private static final Set<String> PHONE_ACTIONS = new HashSet<>(java.util.Arrays.asList(
            "play", "zap", "toggle", "stop", "message", "reload", "watch", "remind"));
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
        handyKey = p.getString("handyKey", null);
        if (handyKey == null) newHandyKey();
        Thread t = new Thread(Remote::loop, "Fernwartung");
        t.setDaemon(true);
        t.start();
        Thread l = new Thread(Remote::listen, "Fernwartung-Befehle");
        l.setDaemon(true);
        l.start();
        Thread h = new Thread(Remote::listenPhone, "Handy-Fernbedienung");
        h.setDaemon(true);
        h.start();
    }

    /** Neuer Schlüssel für die Handy-Fernbedienung (alte QR-Codes gelten dann nicht mehr). */
    static void newHandyKey() {
        String old = handyKey;
        String k = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "").substring(0, 32);
        handyKey = k;
        app.getSharedPreferences("iptv", Context.MODE_PRIVATE).edit().putString("handyKey", k).apply();
        lastChannels = "";
        dirty = true;
        String f = fb;
        if (old != null && f != null) {
            new Thread(() -> {
                try {
                    delete(f + old + ".json");   // alten Stand wegräumen
                } catch (Exception ignored) {
                    // egal
                }
            }).start();
        }
    }

    /** Adresse der Handy-Fernbedienung für dieses Gerät (null = Fernwartung über Firebase noch nicht bereit). */
    static String phoneLink() {
        String f = fb, k = handyKey;
        if (f == null || k == null) return null;
        String host = f.replaceAll("^https://", "").replaceAll("/+$", "");
        return BuildConfig.START_URL + "fernbedienung.html#k=" + k + "&db=" + host;
    }

    /** Vom Player: was läuft (Adresse ohne Zugangsdaten) und Programm jetzt/danach. */
    static void playing(String norm, JSONArray epg) {
        nowNorm = norm == null ? "" : norm;
        nowEpg = epg == null ? new JSONArray() : epg;
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
            lastSent = 0;   // gleich mit der neuen Liste melden
        }
    }

    /** Name der eingerichteten Liste (z. B. „asw“) – leer, solange die Webapp nichts gemeldet hat. */
    static String currentList() {
        return list;
    }

    static String deviceName() {
        return deviceName == null ? Build.MODEL : deviceName;
    }

    /** Was läuft gerade? state: playing, paused, stopped, error, sleep, overview */
    static void status(String s, String t, String ty) {
        state = s;
        title = t == null ? "" : t;
        type = ty == null ? "" : ty;
        // ntfy.sh: nur melden, wenn der Editor zuschaut (Tageskontingent); Firebase: immer (kein Kontingent)
        if (fb != null || System.currentTimeMillis() < watchUntil) dirty = true;
    }

    private static void loop() {
        while (true) {
            try {
                Thread.sleep(2000);
                if (topic == null) loadTopic();
                if (topic == null) continue;
                long now = System.currentTimeMillis();
                long beat = fb != null ? 5 * 60 * 1000L : HEARTBEAT_MS;
                long gap = fb != null ? 2000 : MIN_GAP_MS;
                if (now - lastSent > beat || (dirty && now - lastSent > gap)) send();
                if (fb != null && now - channelsSent > 60000) sendChannels();
            } catch (InterruptedException e) {
                return;
            } catch (Exception ignored) {
                // offline o. ä.: später erneut
            }
        }
    }

    /** Befehle über eine offen gehaltene Verbindung empfangen; ntfy schickt alle 45 s ein Lebenszeichen. */
    private static void listen() {
        long wait = 5000;
        while (true) {
            try {
                String tp = topic;
                if (tp == null) {
                    Thread.sleep(3000);
                    continue;
                }
                if (fb != null) {                     // Firebase: eigener Briefkasten je Gerät, Live-Verbindung
                    listenFirebase(tp);
                    wait = 5000;
                    Thread.sleep(2000);
                    continue;
                }
                String s = lastCmdId != null ? lastCmdId : String.valueOf(since);
                HttpURLConnection c = (HttpURLConnection) new URL(NTFY + tp + "-cmd/json?since=" + s).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(120000);
                c.setUseCaches(false);
                try {
                    if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
                    wait = 5000;
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (!tp.equals(topic)) break;   // andere Liste eingerichtet: neu verbinden
                            handle(line);
                        }
                    }
                } finally {
                    c.disconnect();
                }
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // offline, ntfy bremst (429) o. ä.: mit wachsender Pause neu verbinden
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    return;
                }
                wait = Math.min(wait * 2, 5 * 60 * 1000L);
            }
        }
    }

    /**
     * Firebase: Befehle liegen unter <kanal>/inbox/<gerät>/<id>; Live-Verbindung (Server-Sent Events) meldet neue
     * sofort. Nach dem Ausführen wird der Befehl gelöscht, der Briefkasten bleibt leer.
     */
    private static void listenFirebase(String tp) throws Exception {
        listenInbox(tp, false);
    }

    /** Handy-Fernbedienung: eigener Briefkasten unter dem Geräteschlüssel (nur Bedien-Befehle). */
    private static void listenPhone() {
        long wait = 5000;
        while (true) {
            try {
                if (fb == null || handyKey == null) {
                    Thread.sleep(5000);
                    continue;
                }
                listenInbox(handyKey, true);
                wait = 5000;
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    return;
                }
                wait = Math.min(wait * 2, 5 * 60 * 1000L);
            }
        }
    }

    private static boolean stillValid(String root, boolean phone) {
        return fb != null && root.equals(phone ? handyKey : topic);
    }

    private static void listenInbox(String tp, boolean phone) throws Exception {
        String base = fb + tp + "/inbox/" + deviceId;
        HttpURLConnection c = (HttpURLConnection) new URL(base + ".json").openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);              // Firebase schickt regelmäßig „keep-alive“
        c.setUseCaches(false);
        c.setRequestProperty("Accept", "text/event-stream");
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line, event = "";
                while ((line = r.readLine()) != null) {
                    if (!stillValid(tp, phone)) break;
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                        if (event.equals("cancel") || event.equals("auth_revoked")) break;
                    } else if (line.startsWith("data:") && (event.equals("put") || event.equals("patch"))) {
                        JSONObject d = new JSONObject(line.substring(5).trim());
                        String path = d.optString("path", "/");
                        Object data = d.opt("data");
                        if ("/".equals(path) && data instanceof JSONObject) {
                            JSONObject all = (JSONObject) data;
                            for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
                                String id = it.next();
                                JSONObject cmd = all.optJSONObject(id);
                                if (cmd != null) fbCommand(base, id, cmd, phone);
                            }
                        } else if (data instanceof JSONObject && path.matches("/[^/]+")) {
                            fbCommand(base, path.substring(1), (JSONObject) data, phone);
                        }
                    }
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private static void fbCommand(String base, String id, JSONObject c, boolean phone) {
        new Thread(() -> {                     // gleich aus dem Briefkasten löschen
            try {
                HttpURLConnection d = (HttpURLConnection) new URL(base + "/" + id + ".json").openConnection();
                d.setRequestMethod("DELETE");
                d.setConnectTimeout(10000);
                d.getResponseCode();
                d.disconnect();
            } catch (Exception ignored) {
                // nächstes Mal
            }
        }).start();
        if (done.contains(id)) return;
        done.add(id);
        if (System.currentTimeMillis() / 1000 - c.optLong("t") > MAX_AGE_S) return;   // zu alt
        final String action = c.optString("action");
        final JSONObject arg = c.optJSONObject("arg") != null ? c.optJSONObject("arg") : new JSONObject();
        if (phone && !PHONE_ACTIONS.contains(action)) return;
        if ("watch".equals(action)) {
            dirty = true;
            channelsSent = 0;   // Handy öffnet die Seite: Senderliste gleich frisch
            return;
        }
        main.post(() -> dispatch(action, arg));
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
        JSONObject cfg = new JSONObject(text);
        String f = cfg.optString("firebase", "");
        fb = f.matches("https://[a-z0-9-]+\\.([a-z0-9-]+\\.)?(firebasedatabase\\.app|firebaseio\\.com)/?")
                ? (f.endsWith("/") ? f : f + "/") : null;
        String t = cfg.optString("topic", "");
        if (t.matches("[A-Za-z0-9_-]{16,64}")) topic = t;
    }

    private static void send() throws Exception {
        lastSent = System.currentTimeMillis();
        dirty = false;
        JSONObject o = new JSONObject()
                .put("id", deviceId).put("name", deviceName).put("list", list).put("view", view)
                .put("state", state).put("title", title).put("type", type)
                .put("ver", BuildConfig.VERSION_CODE).put("t", System.currentTimeMillis() / 1000)
                .put("note", note)
                .put("cfg", new JSONObject()
                        .put("autostart", AutostartService.enabled(app))
                        .put("home", AutostartService.homeReturns(app))
                        .put("overlay", Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(app))
                        .put("install", Updater.installAllowed(app))
                        .put("waechter", Waechter.running())
                        .put("waechterSwitch", Waechter.canSwitch(app) && Waechter.supported())
                        .put("waechterOld", !Waechter.supported())
                        .put("pause", (Waechter.pausedFor(app) + 59999) / 60000)
                        .put("normvol", app.getSharedPreferences("iptv", Context.MODE_PRIVATE).getBoolean("normvol", true)))
                .put("diag", Diag.json(app))
                .put("norm", nowNorm).put("epg", nowEpg).put("rem", Reminders.upcoming(app));
        if (fb != null) {
            o.put("noteT", noteT);
            put(fb + topic + "/status/" + deviceId + ".json", o.toString());   // überschreibt: immer der neueste Stand
            String k = handyKey;
            if (k != null) {
                // Handy sieht nur Bedien-Daten (keine Einstellungen/Diagnose)
                JSONObject h = new JSONObject()
                        .put("id", deviceId).put("name", deviceName).put("list", list).put("view", view)
                        .put("state", state).put("title", title).put("type", type).put("t", o.get("t"))
                        .put("note", note).put("noteT", noteT).put("norm", nowNorm).put("epg", nowEpg)
                        .put("rem", Reminders.upcoming(app))
                        .put("ver", BuildConfig.VERSION_CODE);
                put(fb + k + "/status/" + deviceId + ".json", h.toString());
            }
        } else {
            note = "";
            post(NTFY + topic + "-status", o.toString());
        }
    }

    /**
     * Senderliste fürs Handy: „★ Meine Sender“ (sonst die Gruppe des laufenden Senders, sonst die erste Gruppe)
     * mit laufender Sendung; nur bei Änderung, höchstens jede Minute.
     */
    private static void sendChannels() throws Exception {
        channelsSent = System.currentTimeMillis();
        PlayerActivity.Node root = PlayerActivity.treeRoot;
        if (root == null) return;
        PlayerActivity.Node group = findGroup(root, "Meine Sender", 0);
        if (group == null && !nowNorm.isEmpty()) group = groupOf(root, nowNorm);
        if (group == null) group = firstLiveGroup(root, 0);
        if (group == null) return;
        JSONArray a = new JSONArray();
        for (PlayerActivity.Node n : group.children) {
            if (n.item == null || !n.item.live()) continue;
            // Programm der nächsten Stunden (für Erinnerungen vom Handy)
            JSONArray pr = new JSONArray();
            long nowS = System.currentTimeMillis() / 1000;
            for (int i = 0; i < n.item.start.length && pr.length() < 8; i++) {
                if (n.item.end[i] > nowS) pr.put(new JSONArray().put(n.item.start[i]).put(n.item.end[i]).put(n.item.title[i]));
            }
            a.put(new JSONObject().put("n", n.item.name).put("k", Memory.norm(n.item.url))
                    .put("now", n.item.nowTitle()).put("l", n.item.logo == null ? "" : n.item.logo).put("p", pr));
            if (a.length() >= 80) break;
        }
        String body = new JSONObject().put("group", group.name).put("items", a).toString();
        if (body.equals(lastChannels)) return;
        lastChannels = body;
        put(fb + topic + "/sender/" + deviceId + ".json", body);
        String k = handyKey;
        if (k != null) put(fb + k + "/sender/" + deviceId + ".json", body);
    }

    private static PlayerActivity.Node findGroup(PlayerActivity.Node n, String name, int depth) {
        if (n.item != null || depth > 3) return null;
        if (depth > 0 && n.name.contains(name) && !n.children.isEmpty() && n.children.get(0).item != null) return n;
        for (PlayerActivity.Node c : n.children) {
            PlayerActivity.Node g = findGroup(c, name, depth + 1);
            if (g != null) return g;
        }
        return null;
    }

    private static PlayerActivity.Node groupOf(PlayerActivity.Node n, String norm) {
        for (PlayerActivity.Node c : n.children) {
            if (c.item != null) {
                if (c.item.live() && Memory.norm(c.item.url).equals(norm)) return n;
            } else {
                PlayerActivity.Node g = groupOf(c, norm);
                if (g != null) return g;
            }
        }
        return null;
    }

    private static PlayerActivity.Node firstLiveGroup(PlayerActivity.Node n, int depth) {
        if (depth > 3) return null;
        for (PlayerActivity.Node c : n.children) {
            if (c.item != null && c.item.live()) return n;
            if (c.item == null && !c.search) {
                PlayerActivity.Node g = firstLiveGroup(c, depth + 1);
                if (g != null) return g;
            }
        }
        return null;
    }

    private static void delete(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("DELETE");
        c.setConnectTimeout(10000);
        c.getResponseCode();
        c.disconnect();
    }

    /** Eine Zeile von ntfy (message, keepalive, open) auswerten. */
    private static void handle(String line) {
        if (line.trim().isEmpty()) return;
        try {
            JSONObject m = new JSONObject(line);
            if (!"message".equals(m.optString("event"))) return;
            lastCmdId = m.optString("id", lastCmdId);
            JSONObject c = new JSONObject(m.optString("message"));
            String to = c.optString("to", "");
            boolean forMe = to.equals(deviceId) || to.equals("all") || to.equals("list:" + list);
            String cid = c.optString("id", m.optString("id"));
            if (!forMe || done.contains(cid)) return;
            if (System.currentTimeMillis() / 1000 - m.optLong("time") > MAX_AGE_S) return;
            done.add(cid);
            final String action = c.optString("action");
            final JSONObject arg = c.optJSONObject("arg") != null ? c.optJSONObject("arg") : new JSONObject();
            if ("watch".equals(action)) {            // Editor schaut zu: gleich und bei Änderungen melden
                watchUntil = System.currentTimeMillis() + Math.min(arg.optLong("sec", 600), 1800) * 1000L;
                dirty = true;
                return;
            }
            main.post(() -> dispatch(action, arg));
        } catch (Exception ignored) {
            // keine gültige Nachricht
        }
    }

    /** Spielt gerade der Player, bekommt er den Befehl, sonst die Übersicht (MainActivity). */
    private static void dispatch(String action, JSONObject arg) {
        if ("remind".equals(action)) {          // Handy: Erinnerung an eine Sendung an/aus
            String norm = arg.optString("norm");
            long t = arg.optLong("t");
            if (norm.isEmpty() || t <= 0) return;
            report(Reminders.set(app, norm, arg.optString("ch"), t, arg.optString("title"), arg.optBoolean("on", true)));
            return;
        }
        Target p = player_target, m = main_target;
        if (p != null && ("stop".equals(action) || "play".equals(action) || "message".equals(action)
                || "zap".equals(action) || "toggle".equals(action))) {
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

    /** Etwas Neues zu melden (z. B. Erinnerungen geändert). */
    static void changed() {
        dirty = true;
    }

    /** Rückmeldung zu einem Befehl (z. B. „Sender nicht gefunden“). */
    private static volatile String note = "";

    private static volatile long noteT;

    static void report(String text) {
        note = text;
        noteT = System.currentTimeMillis() / 1000;
        dirty = true;
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

    private static void put(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setDoOutput(true);
        c.setRequestMethod("PUT");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream o = c.getOutputStream()) {
            o.write(body.getBytes(StandardCharsets.UTF_8));
        }
        c.getResponseCode();
        c.disconnect();
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
