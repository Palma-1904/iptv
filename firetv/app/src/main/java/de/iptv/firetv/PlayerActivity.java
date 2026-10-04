package de.iptv.firetv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Eingebauter Player (libVLC – gleiche Technik wie VLC, spielt alle Tonformate) für die Fire-TV-App.
 *
 * Live:   ▲/▼ Sender davor/danach (innerhalb der Gruppe), ◀/▶ oder ☰ Senderliste, OK Info.
 *         Senderliste: links neben dem verkleinerten Bild, darunter das Programm des markierten
 *         Senders. ◀ in der Liste = Gruppen, OK = umschalten (Liste bleibt offen), ▶/Zurück = schließen.
 * Filme/Serien: ◀/▶ spulen, OK Pause, ▲/▼ Folge davor/danach (Serien).
 * Zurück: Einblendung schließen bzw. zurück zur Übersicht.
 *
 * Der Anbieter erlaubt nur EINE Verbindung: Beim Umschalten wird der alte Sender sofort gestoppt
 * und der neue erst verbunden, wenn kurz keine Taste mehr gedrückt wurde.
 */
public class PlayerActivity extends Activity implements Remote.Target {

    /** Daten von der Webapp (für Intent-Extras evtl. zu groß). */
    static String pending;
    /** Neuere Webapp: ganze Playlist als Baum (bleibt gespeichert, solange die App läuft) und Pfad zum Eintrag. */
    static volatile Node treeRoot;
    static volatile String treeVersion;
    static String pendingPath;
    /** Im Player geänderte Favoriten (Art -> Schlüssel -> an/aus); MainActivity gibt sie an die Webapp. */
    /** ☰ 3 Sekunden gehalten: MainActivity soll die Einrichtung öffnen. */
    static boolean openSetup;
    static final java.util.Map<String, java.util.Map<String, Boolean>> favChanges = new java.util.HashMap<>();
    /** Zuletzt gesehener Eintrag – die Webapp setzt dort den Fokus. */
    static String lastUrl;

    private static final String VLC = "org.videolan.vlc";
    private static final String USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20";
    private static final int ROYAL = 0xFF1D4ED8;
    private static final int ROYAL_LIGHT = 0xFF8FB0FF;
    private static final int PANEL = 0xF20B1220;
    private static final int MUTED = 0xFFB8C2D6;
    private static final long TUNE_DELAY = 700;   // ms Ruhe nach dem Umschalten, dann verbinden
    private static final long INFO_MS = 6000;
    private static final long LIST_MS = 30000;
    private static final long REFRESH_MS = 2 * 3600 * 1000L;   // Liste und Programm alle 2 Stunden auffrischen
    private static final long ADOPT_MS = 30 * 1000L;            // neuen Baum der Webapp übernehmen (prüfen)

    static class Item {
        String name, url, logo, tvgId, type, group;
        String heading;   // Titel in der Info (z. B. „Film (Deutsch 4K)“), sonst name
        long[] start = new long[0], end = new long[0];
        String[] title = new String[0];

        boolean live() {
            return "live".equals(type);
        }

        /** Index der laufenden Sendung oder -1. */
        int now() {
            long t = System.currentTimeMillis() / 1000;
            for (int i = 0; i < start.length; i++) if (start[i] <= t && end[i] > t) return i;
            return -1;
        }

        String nowTitle() {
            int i = now();
            return i < 0 ? "" : title[i];
        }
    }

    /** Eintrag im Baum: Bereich, Gruppe, Film mit Sprachen … oder ein abspielbarer Eintrag (item). */
    static class Node {
        final String name;
        Node parent;
        final List<Node> children = new ArrayList<>();
        Item item;
        boolean search;
        boolean variants;   // Film mit mehreren Sprachfassungen
        String favKey, favType;  // Favoriten-Kennung (Film = Werk, Serie = Titel)
        boolean fav;

        Node(String name) {
            this.name = name;
        }

        Node add(Node c) {
            c.parent = this;
            children.add(c);
            return c;
        }

        /** Anzahl der abspielbaren Einträge darunter (für „97 Sender“). */
        int count() {
            if (item != null) return 1;
            int n = 0;
            for (Node c : children) n += c.count();
            return n;
        }
    }

    private static void readFav(Node n, JSONObject j) {
        if (!j.has("fk")) return;
        n.favKey = j.optString("fk");
        n.favType = j.optString("ft");
        n.fav = j.optInt("fv") == 1;
    }

    static Node parseTree(JSONObject j) throws Exception {
        Node n = new Node(j.optString("n"));
        if (j.has("u")) {
            Item it = new Item();
            it.name = n.name;
            it.heading = j.optString("ti", n.name);
            it.url = j.optString("u");
            it.logo = j.optString("l");
            it.tvgId = j.optString("id");
            it.type = j.optString("t", "live");
            JSONArray epg = j.optJSONArray("e");
            int k = epg == null ? 0 : epg.length();
            it.start = new long[k];
            it.end = new long[k];
            it.title = new String[k];
            for (int i = 0; i < k; i++) {
                JSONArray p = epg.getJSONArray(i);
                it.start[i] = p.optLong(0);
                it.end[i] = p.optLong(1);
                it.title[i] = p.optString(2);
            }
            n.item = it;
            readFav(n, j);
            return n;
        }
        n.search = j.optInt("s") == 1;
        n.variants = j.optInt("v") == 1;
        readFav(n, j);
        JSONArray c = j.optJSONArray("c");
        if (c != null) for (int i = 0; i < c.length(); i++) n.add(parseTree(c.getJSONObject(i)));
        return n;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", Locale.GERMANY);
    private Node rootNode;          // Übersicht (Live TV, Filme, Serien, Suche)
    private Node ctx;               // Gruppe des laufenden Eintrags (▲/▼ schaltet darin)
    private final List<Item> ctxItems = new ArrayList<>();
    private int index;              // laufender Eintrag in ctxItems
    private int pendingIndex = -1;  // Ziel beim Umschalten mit ▲/▼ (gleiche Gruppe)
    private int retries;
    private boolean failed;
    private float textScale = 1f;
    private boolean senior;         // Seniorenansicht: nur Player, Zurück verlässt ihn nicht
    private boolean menuHeld;       // ☰ wurde lange gehalten (Einrichtung) – kurzer Druck entfällt
    private final Runnable menuHint = () -> Toast.makeText(this,
            "Für die Einrichtung ☰ weiter gedrückt halten …", Toast.LENGTH_SHORT).show();
    private final Runnable menuSetup = () -> {
        menuHeld = true;
        openSetup = true;
        finish();
    };

    private LibVLC vlc;
    private MediaPlayer player;
    private FrameLayout root;
    private VLCVideoLayout surface;
    private long position, length;   // Filme/Serien: Stand und Länge in ms
    private ProgressBar spinner;
    private TextView status;
    private LinearLayout info;
    private ImageView infoLogo;
    private TextView infoNum, infoName, infoNow, infoNext, infoClock, infoHint;
    private ProgressBar infoProgress;

    // Senderliste
    private LinearLayout listPanel;
    private TextView listTitle, listHint;
    private ListView list;
    private ListAdapter adapter;
    private Node browse;            // Ebene, die die Liste gerade zeigt
    private Node displayRoot;       // Übersicht mit „Zuletzt gesehen“ und „Lieblingssender“ davor
    private Memory mem;
    private long pendingSeek = -1;  // Weiterschauen: nach dem Start an diese Stelle springen
    private long lastSave;
    private String digits = "";     // Zifferntasten: eingegebene Sendernummer
    private TextView digitBox;
    private long lastInput = System.currentTimeMillis();
    private boolean sleeping, sleepWarned;
    private static final long SLEEP_MS = 3 * 3600 * 1000L;   // Schlaf-Timer: 3 Stunden ohne Taste
    private static final long SLEEP_GRACE = 60 * 1000L;      // dann 1 Minute Vorwarnung
    private Node browseStart;       // Start ohne laufenden Eintrag: Liste an dieser Stelle öffnen
    private String treeSeen;        // Fassung des Baums, mit der der Player arbeitet
    private LinearLayout preview;
    private TextView previewName, previewEpg;
    private ImageLoader images;

    private final Runnable hideInfo = () -> info.setVisibility(View.GONE);
    private final Runnable hideList = this::closeList;
    private final Runnable tune = () -> {
        if (pendingIndex < 0) return;
        index = pendingIndex;
        pendingIndex = -1;
        retries = 0;
        play();
    };
    /** Webapp (im Hintergrund) bitten, Playlist und Programm neu zu laden; sie liefert einen neuen Baum. */
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            MainActivity.requestRefresh();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    /** Neuen Baum übernehmen, ohne die Wiedergabe zu unterbrechen. */
    private final Runnable adopt = new Runnable() {
        @Override
        public void run() {
            handler.postDelayed(this, ADOPT_MS);
            Node fresh = treeRoot;
            String v = treeVersion;
            if (fresh == null || v == null || v.equals(treeSeen) || treeSeen == null) return;
            treeSeen = v;
            Item playing = current();
            Node leaf = playing == null ? null : findLeaf(fresh, playing.url);
            if (playing != null && leaf == null) return;   // laufender Eintrag nicht mehr in der Liste: alten Baum behalten
            rootNode = fresh;
            if (leaf != null && pendingIndex < 0) {
                ctx = leaf.parent;
                ctxItems.clear();
                for (Node c : ctx.children) if (c.item != null) ctxItems.add(c.item);
                index = Math.max(0, ctxItems.indexOf(leaf.item));
            }
            if (idle()) browseStart = rootNode;
            if (listOpen()) {
                if (idle()) showNode(rootNode, null);
                else openList();
            }
        }
    };

    /** Eintrag zu einer Adresse (Zugangsdaten werden ignoriert, siehe Memory.norm). */
    private static Node findLeaf(Node n, String url) {
        if (n.item != null) return Memory.norm(url).equals(Memory.norm(n.item.url)) ? n : null;
        for (Node c : n.children) {
            Node f = findLeaf(c, url);
            if (f != null) return f;
        }
        return null;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (info.getVisibility() == View.VISIBLE) updateInfo();
            handler.postDelayed(this, 1000);
        }
    };

    // ---------- Start ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        boolean ok = pendingPath != null ? readPath(pendingPath) : readData(pending);
        pending = null;
        pendingPath = null;
        if (!ok) {
            finish();
            return;
        }
        images = new ImageLoader();
        mem = Memory.get(this);
        Remote.player_target = this;
        buildViews();

        vlc = new LibVLC(this, new ArrayList<>(Arrays.asList(
                "--http-reconnect",
                "--audio-language=de,deu,ger",
                "--audio-time-stretch",
                // Zeitsteuerung bei Live-Sendern nicht ständig nachregeln (verursacht kleine Ruckler)
                "--clock-jitter=0",
                "--clock-synchro=0")));
        player = new MediaPlayer(vlc);
        player.attachViews(surface, null, false, false);
        player.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Buffering:
                    spinner.setVisibility(event.getBuffering() < 100f ? View.VISIBLE : View.GONE);
                    break;
                case MediaPlayer.Event.Playing:
                    retries = 0;
                    failed = false;
                    reportPlaying();
                    spinner.setVisibility(View.GONE);
                    status.setVisibility(View.GONE);
                    if (pendingSeek > 0) {            // Weiterschauen
                        player.setTime(pendingSeek);
                        position = pendingSeek;
                        pendingSeek = -1;
                    }
                    handler.removeCallbacks(matchRate);
                    handler.postDelayed(matchRate, 1500);   // Bildrate erst nach dem Start bekannt
                    break;
                case MediaPlayer.Event.TimeChanged:
                    position = event.getTimeChanged();
                    if (System.currentTimeMillis() - lastSave > 15000) saveResume();
                    break;
                case MediaPlayer.Event.LengthChanged:
                    length = event.getLengthChanged();
                    break;
                case MediaPlayer.Event.EndReached:
                    onEnded();
                    break;
                case MediaPlayer.Event.EncounteredError:
                    onError();
                    break;
                default:
                    break;
            }
        });

        if (idle()) {
            spinner.setVisibility(View.GONE);
            root.post(this::openList);
        } else {
            play();
        }
        handler.post(tick);
        handler.postDelayed(refresh, REFRESH_MS);
        handler.postDelayed(adopt, ADOPT_MS);
        handler.postDelayed(sleepCheck, 60000);
        handler.postDelayed(() -> Updater.check(this, false), 20000);
    }

    private static Item readItem(JSONObject j) throws Exception {
        Item it = new Item();
        it.name = j.optString("name");
        it.url = j.optString("url");
        it.logo = j.optString("logo");
        it.tvgId = j.optString("tvgId");
        it.type = j.optString("type", "live");
        it.group = j.optString("group");
        JSONArray epg = j.optJSONArray("epg");
        int n = epg == null ? 0 : epg.length();
        it.start = new long[n];
        it.end = new long[n];
        it.title = new String[n];
        for (int k = 0; k < n; k++) {
            JSONArray p = epg.getJSONArray(k);
            it.start[k] = p.optLong(0);
            it.end[k] = p.optLong(1);
            it.title[k] = p.optString(2);
        }
        return it;
    }

    /** Neue Webapp: Baum (treeRoot) + Pfad aus Indizes bis zum Eintrag. */
    private boolean readPath(String json) {
        try {
            JSONObject o = new JSONObject(json);
            Node n = treeRoot;
            JSONArray path = o.getJSONArray("path");
            for (int i = 0; n != null && i < path.length(); i++) {
                int k = path.getInt(i);
                n = k >= 0 && k < n.children.size() ? n.children.get(k) : null;
            }
            if (n == null) return false;
            rootNode = treeRoot;
            treeSeen = treeVersion;
            if ("senioren".equals(o.optString("view"))) senior = true;   // gleiche Schriftgröße wie Komplett
            if (n.item == null) {
                browseStart = n;   // z. B. Liste ohne Live-Sender: nur Auswahl, noch nichts abspielen
                return true;
            }
            setContext(n);
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "Wiedergabe nicht möglich: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /** Ältere Webapp: "groups" + "group" + "index" bzw. nur "items" + "index" -> als Baum „Live TV“. */
    private boolean readData(String json) {
        if (json == null) return false;
        try {
            JSONObject o = new JSONObject(json);
            rootNode = new Node("Übersicht");
            Node area = rootNode.add(new Node("Live TV"));
            JSONArray gs = o.optJSONArray("groups");
            int g = 0;
            if (gs == null || gs.length() == 0) {
                gs = new JSONArray().put(new JSONObject().put("name", "").put("items", o.getJSONArray("items")));
            } else {
                g = o.optInt("group");
            }
            for (int i = 0; i < gs.length(); i++) {
                JSONObject jg = gs.getJSONObject(i);
                Node grp = area.add(new Node(jg.optString("name")));
                JSONArray arr = jg.getJSONArray("items");
                for (int k = 0; k < arr.length(); k++) {
                    Item it = readItem(arr.getJSONObject(k));
                    it.heading = it.name;
                    Node leaf = grp.add(new Node(it.name));
                    leaf.item = it;
                }
            }
            if (g < 0 || g >= area.children.size()) g = 0;
            Node grp = area.children.get(g);
            if (grp.children.isEmpty()) return false;
            int i = Math.max(0, Math.min(grp.children.size() - 1, o.optInt("index")));
            if ("senioren".equals(o.optString("view"))) senior = true;   // gleiche Schriftgröße wie Komplett
            setContext(grp.children.get(i));
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "Wiedergabe nicht möglich: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /** Laufender Eintrag = leaf; ▲/▼ schaltet zwischen den abspielbaren Geschwistern. */
    private void setContext(Node leaf) {
        ctx = leaf.parent;
        ctxItems.clear();
        for (Node c : ctx.children) if (c.item != null) ctxItems.add(c.item);
        index = Math.max(0, ctxItems.indexOf(leaf.item));
    }

    // ---------- Wiedergabe ----------

    private List<Item> items() {
        return ctxItems;
    }

    /** Läuft gerade etwas (oder wird gleich umgeschaltet)? Ohne: nur Auswahl-Liste. */
    private boolean idle() {
        return ctxItems.isEmpty();
    }

    private Item current() {
        return idle() ? null : items().get(pendingIndex >= 0 ? pendingIndex : index);
    }

    private void play() {
        Item it = items().get(index);
        lastUrl = it.url;
        if (senior) getSharedPreferences("iptv", MODE_PRIVATE).edit().putString("lastSeniorUrl", it.url).apply();
        failed = false;
        status.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE);
        position = 0;
        length = 0;
        pendingSeek = -1;
        sleeping = false;
        mem.addRecent(it.url);
        long[] r = it.live() ? null : mem.resume(it.url);
        if (r != null && r[0] > 60000 && !Memory.finished(r)) askResume(it, r[0]);
        else start(it);
        if (!listOpen()) showInfo();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    // ---------- Bildwiederholrate an die Sendung anpassen ----------
    // Deutsches Fernsehen: 25/50 Bilder/s. Der Stick gibt sonst 60 Hz aus -> Bilder werden ungleichmäßig
    // wiederholt, das Bild „hakt“ regelmäßig. Daher Fernseher auf 50 Hz (bzw. passende Rate) schalten.
    private float appliedFps;

    private final Runnable matchRate = this::matchFrameRate;

    private void matchFrameRate() {
        if (Build.VERSION.SDK_INT < 23 || player == null) return;
        float fps = 0;
        try {
            org.videolan.libvlc.Media.VideoTrack vt = player.getCurrentVideoTrack();
            if (vt != null && vt.frameRateDen > 0) fps = (float) vt.frameRateNum / vt.frameRateDen;
        } catch (Exception ignored) {
            // keine Angabe
        }
        if (fps < 10 || fps > 120 || Math.abs(fps - appliedFps) < 0.01f) return;
        android.view.Display d = getWindowManager().getDefaultDisplay();
        android.view.Display.Mode cur = d.getMode();
        android.view.Display.Mode best = null;
        float bestScore = Float.MAX_VALUE;
        for (android.view.Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
            float r = m.getRefreshRate();
            float ratio = r / fps;
            float off = Math.abs(ratio - Math.round(ratio));   // ganzes Vielfaches der Bildrate?
            if (Math.round(ratio) < 1 || off > 0.01f) continue;
            float score = off * 1000 + Math.abs(r - 50);         // bevorzugt 50/60 statt 25/24
            if (score < bestScore) {
                bestScore = score;
                best = m;
            }
        }
        appliedFps = fps;
        if (best == null || best.getModeId() == cur.getModeId()) return;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.preferredDisplayModeId = best.getModeId();
        getWindow().setAttributes(lp);
    }

    private void reportPlaying() {
        Item it = current();
        if (it != null) Remote.status("playing", it.heading != null ? it.heading : it.name, it.type);
    }

    // ---------- Fernwartung (Befehle aus dem Editor) ----------

    @Override
    public void remote(String action, JSONObject arg) {
        switch (action) {
            case "close":
                finish();
                break;
            case "message":
                Remote.showMessage(this, arg.optString("text"));
                break;
            case "stop":
                saveResume();
                player.stop();
                sleeping = true;      // jede Taste schaut weiter
                closeList();
                info.setVisibility(View.GONE);
                showStatus("Die Wiedergabe wurde aus der Ferne beendet.\n\nZum Weiterschauen eine beliebige Taste drücken.");
                Remote.status("stopped", "", "");
                break;
            case "play": {
                Node leaf = rootNode == null ? null : findLeaf(rootNode, arg.optString("norm"));
                if (leaf == null) {
                    Remote.report("Eintrag nicht gefunden");
                    return;
                }
                sleeping = false;
                status.setVisibility(View.GONE);
                if (listOpen()) closeList();
                jumpTo(leaf);
                break;
            }
            default:
                break;
        }
    }

    /** Film/Folge schon angefangen: fortsetzen oder von vorne? */
    private void askResume(Item it, long pos) {
        spinner.setVisibility(View.GONE);
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("Weiterschauen?")
                .setMessage("„" + (it.heading != null ? it.heading : it.name) + "“\nzuletzt bei " + duration(pos))
                .setPositiveButton("Fortsetzen", (d, w) -> {
                    pendingSeek = pos;
                    spinner.setVisibility(View.VISIBLE);
                    start(it);
                })
                .setNegativeButton("Von vorne", (d, w) -> {
                    spinner.setVisibility(View.VISIBLE);
                    start(it);
                })
                .setOnCancelListener(d -> {
                    spinner.setVisibility(View.VISIBLE);
                    start(it);
                })
                .create();
        dlg.show();
        android.widget.Button b = dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
        if (b != null) b.requestFocus();
    }

    /** Stelle des laufenden Films/der laufenden Folge merken. */
    private void saveResume() {
        lastSave = System.currentTimeMillis();
        if (idle() || pendingIndex >= 0) return;
        Item it = items().get(index);
        if (!it.live() && length > 0) mem.putResume(it.url, position, length);
    }

    private void start(Item it) {
        Media media = new Media(vlc, Uri.parse(it.url));
        media.setHWDecoderEnabled(true, false);
        media.addOption(":http-user-agent=" + USER_AGENT);
        // Vorrat gegen Ruckeln bei schwankendem WLAN/Anbieter (Live 5 s, Filme 8 s; Umschalten etwas langsamer)
        media.addOption(":network-caching=" + (it.live() ? 5000 : 8000));
        media.addOption(":live-caching=5000");
        if (it.url.split("\\?")[0].toLowerCase(Locale.ROOT).endsWith(".m3u8")) {
            // HLS: Anbieter liefert ~10-s-Stücke und ist zeitweise langsamer als Echtzeit (gemessen
            // bei RTL Crime: 12 s für 10 s Film) -> 30 s hinter live (wie Samsung), bis 60 s Vorrat; Stabilität vor Aktualität
            media.addOption(":adaptive-livedelay=30000");
            media.addOption(":adaptive-maxbuffer=60000");
        }
        player.setMedia(media);
        media.release();
        player.play();
    }

    /** Sender/Folge wechseln: alten Stream sofort beenden, neuen erst nach kurzer Ruhe laden. */
    private void step(int delta) {
        int size = items().size();
        if (size < 2) {
            showInfo();
            return;
        }
        int from = pendingIndex >= 0 ? pendingIndex : index;
        int to = from + delta;
        if (current().live()) {
            to = (to + size) % size;
        } else if (to < 0 || to >= size) {
            showInfo();
            return;
        }
        saveResume();
        pendingIndex = to;
        player.stop();
        spinner.setVisibility(View.VISIBLE);
        status.setVisibility(View.GONE);
        showInfo();
        handler.removeCallbacks(tune);
        handler.postDelayed(tune, TUNE_DELAY);
    }

    /** Direkt umschalten (aus der Liste), auch in eine andere Gruppe oder zu einem Film. */
    private void jumpTo(Node leaf) {
        if (!idle() && leaf.parent == ctx && leaf.item == items().get(index) && pendingIndex < 0 && !failed) return;
        handler.removeCallbacks(tune);
        saveResume();
        player.stop();
        pendingIndex = -1;
        retries = 0;
        setContext(leaf);
        play();
    }

    private void onEnded() {
        Item it = items().get(index);
        if (it.live()) {
            onError();
        } else if (index < items().size() - 1) {
            if (length > 0) mem.putResume(it.url, length, length);   // gesehen
            step(1);                       // nächste Folge
        } else {
            finish();
        }
    }

    private void onError() {
        Item it = items().get(index);
        if (retries < 2) {
            retries++;
            showStatus("Verbindung wird erneut aufgebaut …");
            spinner.setVisibility(View.VISIBLE);
            final Node atCtx = ctx;
            final int at = index;
            handler.postDelayed(() -> {
                if (atCtx == ctx && at == index && pendingIndex < 0) {
                    player.stop();
                    start(it);
                }
            }, 2500);
            return;
        }
        failed = true;
        spinner.setVisibility(View.GONE);
        Remote.status("error", it.name, it.type);
        String keys = it.live() || items().size() > 1
                ? "\n\n▲ ▼ anderen Sender wählen  ·  OK = in VLC öffnen" : "\n\nOK = in VLC öffnen";
        showStatus("„" + it.name + "“ kann gerade nicht abgespielt werden.\n"
                + "Läuft auf einem anderen Gerät schon Fernsehen? Der Anbieter erlaubt nur eines." + keys);
    }

    private void togglePause() {
        if (player.isPlaying()) {
            player.pause();
            Item it = current();
            if (it != null) Remote.status("paused", it.heading != null ? it.heading : it.name, it.type);
        } else {
            player.play();
        }
        handler.postDelayed(this::showInfo, 150);
    }

    private void seek(long ms) {
        long pos = Math.max(0, position + ms);
        if (length > 0) pos = Math.min(pos, Math.max(0, length - 1000));
        position = pos;
        player.setTime(pos);
        showInfo();
    }

    private void openInVlc() {
        Item it = items().get(index);
        player.stop();
        AutostartService.suppress(6 * 3600 * 1000L);   // in VLC weiterschauen
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse(it.url), "video/*");
        intent.setPackage(VLC);
        try {
            startActivity(intent);
            finish();
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "VLC ist nicht installiert.", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- Fernbedienung ----------

    /** Manche Fernbedienungen (z. B. Fire-TV-App auf dem Handy) senden Escape statt Zurück. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        lastInput = System.currentTimeMillis();
        if (sleepWarned && !sleeping) {            // Vorwarnung: jede Taste = „ich schaue noch“
            sleepWarned = false;
            status.setVisibility(View.GONE);
        }
        if (sleeping) {                            // angehalten: jede Taste startet wieder
            if (event.getAction() == KeyEvent.ACTION_UP) {
                sleeping = false;
                status.setVisibility(View.GONE);
                if (!idle()) play();
            }
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP) onBackPressed();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.getRepeatCount() == 0) {
                menuHeld = false;
                handler.postDelayed(menuHint, 1200);
                handler.postDelayed(menuSetup, 3000);
            }
            return true;
        }
        if (listOpen()) {
            handler.removeCallbacks(hideList);
            handler.postDelayed(hideList, LIST_MS);
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (browse.parent != null) showNode(browse.parent, browse);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    int pos = list.getSelectedItemPosition();
                    Node sel = pos >= 0 && pos < browse.children.size() ? browse.children.get(pos) : null;
                    if (sel != null && sel.item == null) open(sel);
                    else closeList();
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return true;           // Listenende: nicht umschalten
                default:
                    return super.onKeyDown(keyCode, event);
            }
        }
        int digit = digitOf(keyCode);
        if (digit >= 0 && current() != null && current().live()) {
            enterDigit(digit);
            return true;
        }
        if (idle()) {             // noch nichts gewählt: jede Taste öffnet die Auswahl
            openList();
            return true;
        }
        boolean live = current().live();
        boolean fast = event.getRepeatCount() > 2;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                step(1);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                step(1);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (live) openList(); else seek(fast ? -60000 : -10000);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (live) openList(); else seek(fast ? 60000 : 30000);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() > 0) return true;
                if (failed) openInVlc();
                else if (live) toggleInfo();
                else togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (!live) togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (!live) seek(60000);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (!live) seek(-60000);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private static int digitOf(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) return keyCode - KeyEvent.KEYCODE_0;
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) return keyCode - KeyEvent.KEYCODE_NUMPAD_0;
        return -1;
    }

    /** Zifferntasten: Sendernummer in der aktuellen Gruppe; nach 1,5 s Pause wird umgeschaltet. */
    private final Runnable digitGo = () -> {
        int n;
        try {
            n = Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            n = 0;
        }
        digits = "";
        digitBox.setVisibility(View.GONE);
        if (n < 1 || n > items().size()) {
            Toast.makeText(this, "Keinen Sender mit dieser Nummer", Toast.LENGTH_SHORT).show();
            return;
        }
        if (n - 1 == index && pendingIndex < 0) return;
        Node leaf = null;
        for (Node c : ctx.children) if (c.item == items().get(n - 1)) leaf = c;
        if (leaf != null) jumpTo(leaf);
    };

    private void enterDigit(int d) {
        if (digits.length() >= 4) digits = "";
        digits += d;
        digitBox.setText(digits);
        digitBox.setVisibility(View.VISIBLE);
        handler.removeCallbacks(digitGo);
        handler.postDelayed(digitGo, 1500);
    }

    /** Schlaf-Timer: 3 Stunden ohne Taste -> Vorwarnung, nach 1 Minute Wiedergabe anhalten (Verbindung frei). */
    private final Runnable sleepCheck = new Runnable() {
        @Override
        public void run() {
            handler.postDelayed(this, 30000);
            if (sleeping || idle() || !player.isPlaying()) return;
            long quiet = System.currentTimeMillis() - lastInput;
            if (quiet >= SLEEP_MS + SLEEP_GRACE) {
                saveResume();
                player.stop();
                sleeping = true;
                Remote.status("sleep", "", "");
                if (Waechter.running()) Updater.check(PlayerActivity.this, true);   // ruhiger Moment für ein Update
                sleepWarned = false;
                closeList();
                info.setVisibility(View.GONE);
                showStatus("Wiedergabe angehalten – seit 3 Stunden wurde keine Taste gedrückt.\n\n"
                        + "Zum Weiterschauen eine beliebige Taste drücken.");
            } else if (quiet >= SLEEP_MS && !sleepWarned) {
                sleepWarned = true;
                showStatus("Läuft noch jemand?\n\nBitte eine Taste drücken –\nsonst endet die Wiedergabe in einer Minute.");
            }
        }
    };

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            handler.removeCallbacks(menuHint);
            handler.removeCallbacks(menuSetup);
            if (!menuHeld) {
                if (listOpen()) closeList();
                else openList();
            }
            menuHeld = false;
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (listOpen()) {
            closeList();
        } else if (info.getVisibility() == View.VISIBLE && !failed) {
            info.setVisibility(View.GONE);
        } else if (senior) {
            showInfo();                   // Seniorenansicht: nicht versehentlich hinausfallen
        } else {
            finish();
        }
    }

    /** Schaut gerade jemand? (Automatische Updates warten dann.) */
    boolean watching() {
        return player != null && player.isPlaying() && !sleeping;
    }

    @Override
    protected void onResume() {
        super.onResume();
        AutostartService.shown();
    }

    @Override
    protected void onPause() {
        AutostartService.hidden();
        super.onPause();
    }

    /** Home-Taste: Player schließt (Verbindung frei), die App kommt auf Wunsch gleich zurück. */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        AutostartService.bounceBack(this);
    }

    // ---------- Lebenszyklus: Verbindung immer freigeben ----------

    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing()) finish();     // z. B. Home-Taste: Stream beenden, nicht im Hintergrund weiterlaufen
    }

    @Override
    protected void onDestroy() {
        if (Remote.player_target == this) Remote.player_target = null;
        if (mem != null && player != null) saveResume();
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.stop();
            player.detachViews();
            player.release();
            player = null;
        }
        if (vlc != null) {
            vlc.release();
            vlc = null;
        }
        if (images != null) images.shutdown();
        super.onDestroy();
    }

    // ---------- Oberfläche ----------

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * textScale);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** Mehrzeilig, am Ende mit „…“ gekürzt. */
    private static TextView lines(TextView t, int max) {
        t.setSingleLine(false);
        t.setMaxLines(max);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private void buildViews() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        surface = new VLCVideoLayout(this);
        root.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutVideo());

        spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        root.addView(spinner, new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER));

        status = text(24, Color.WHITE, true);
        status.setSingleLine(false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(32), dp(24), dp(32), dp(24));
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(PANEL);
        sb.setCornerRadius(dp(16));
        status.setBackground(sb);
        status.setVisibility(View.GONE);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        slp.setMargins(dp(80), dp(40), dp(80), dp(40));
        root.addView(status, slp);

        buildInfo();
        buildList();

        digitBox = text(44, Color.WHITE, true);
        digitBox.setPadding(dp(24), dp(8), dp(24), dp(8));
        GradientDrawable db = new GradientDrawable();
        db.setColor(PANEL);
        db.setCornerRadius(dp(12));
        digitBox.setBackground(db);
        digitBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        dlp.setMargins(0, dp(32), dp(40), 0);
        root.addView(digitBox, dlp);
    }

    /**
     * Bildbereich: ganzer Bildschirm, bei offener Senderliste verkleinert rechts neben der Liste
     * (darunter die Programmvorschau). VLC passt das Seitenverhältnis darin selbst an.
     */
    private void layoutVideo() {
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0) return;
        FrameLayout.LayoutParams lp;
        if (listOpen()) {
            int pw = listPanel.getLayoutParams().width;
            int bx = pw + dp(32), by = dp(40);
            int bw = Math.max(dp(160), w - bx - dp(40));
            int bh = Math.round(bw * 9f / 16f);
            lp = new FrameLayout.LayoutParams(bw, bh, Gravity.TOP | Gravity.START);
            lp.leftMargin = bx;
            lp.topMargin = by;
            FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(bw, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START);
            pl.leftMargin = bx;
            pl.topMargin = by + bh + dp(20);
            preview.setLayoutParams(pl);
        } else {
            lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.START);
        }
        FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (old.width != lp.width || old.height != lp.height || old.leftMargin != lp.leftMargin
                || old.topMargin != lp.topMargin) {
            surface.setLayoutParams(lp);
        }
    }

    private void buildInfo() {
        info = new LinearLayout(this);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(48), dp(56), dp(48), dp(28));
        info.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF2000000, 0xCC000000, 0x00000000}));

        infoLogo = new ImageView(this);
        infoLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(140 * textScale), dp(90 * textScale));
        llp.rightMargin = dp(28);
        info.addView(infoLogo, llp);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.BOTTOM);
        infoNum = text(20, MUTED, true);
        infoName = lines(text(28, Color.WHITE, true), 2);   // lange Film-/Serientitel: 2 Zeilen
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        nlp.leftMargin = dp(12);
        top.addView(infoNum);
        top.addView(infoName, nlp);
        infoClock = text(26, Color.WHITE, true);
        top.addView(infoClock);
        mid.addView(top);

        infoNow = text(22, Color.WHITE, false);
        mid.addView(infoNow);
        infoProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        infoProgress.setMax(1000);
        infoProgress.setProgressTintList(ColorStateList.valueOf(0xFF4D7CFF));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        plp.topMargin = dp(8);
        plp.bottomMargin = dp(8);
        mid.addView(infoProgress, plp);
        infoNext = text(18, MUTED, false);
        mid.addView(infoNext);
        infoHint = text(15, MUTED, false);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(10);
        mid.addView(infoHint, hlp);
        info.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        info.setVisibility(View.GONE);
        root.addView(info, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
    }

    private void showInfo() {
        if (listOpen() || idle()) return;
        updateInfo();
        info.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideInfo);
        handler.postDelayed(hideInfo, INFO_MS);
    }

    private void toggleInfo() {
        if (info.getVisibility() == View.VISIBLE) info.setVisibility(View.GONE);
        else showInfo();
    }

    private void updateInfo() {
        Item it = current();
        int i = pendingIndex >= 0 ? pendingIndex : index;
        int size = items().size();
        infoNum.setText(size > 1 ? (i + 1) + "/" + size : "");
        infoName.setText(it.heading != null ? it.heading : it.name);
        infoClock.setText(hhmm.format(new Date()));
        images.load(it.logo, infoLogo);
        if (it.live()) {
            int n = it.now();
            if (n >= 0) {
                infoNow.setText(time(it.start[n]) + " – " + time(it.end[n]) + "   " + it.title[n]);
                long t = System.currentTimeMillis() / 1000;
                infoProgress.setProgress((int) (1000 * (t - it.start[n]) / Math.max(1, it.end[n] - it.start[n])));
                infoProgress.setVisibility(View.VISIBLE);
                infoNext.setText(n + 1 < it.start.length ? "Danach  " + time(it.start[n + 1]) + "   " + it.title[n + 1] : "");
            } else {
                infoNow.setText(ctx.name == null ? "" : ctx.name);
                infoProgress.setVisibility(View.GONE);
                infoNext.setText("");
            }
            infoHint.setText(senior
                    ? "▲ ▼  Sender wechseln     ◀ ▶  Senderliste     OK  Info"
                    : size > 1
                    ? "▲ ▼  Sender wechseln     ◀ ▶  Senderliste     OK  Info     ↩  Übersicht"
                    : "◀ ▶  Liste     OK  Info     ↩  Übersicht");
        } else {
            long pos = position, dur = length;
            boolean known = dur > 0;
            infoNow.setText((player.isPlaying() ? "▶  " : "❚❚  Pause   ")
                    + duration(pos) + (known ? "  /  " + duration(dur) : ""));
            infoProgress.setVisibility(known ? View.VISIBLE : View.GONE);
            if (known) infoProgress.setProgress((int) (1000 * pos / dur));
            infoNext.setText(size > 1 && i + 1 < size ? "Nächste Folge:  " + items().get(i + 1).name : "");
            infoHint.setText("◀ ▶  Spulen (gedrückt halten = schneller)     OK  Pause"
                    + (size > 1 ? "     ▲ ▼  Folge" : "") + "     ☰  Liste     ↩  Übersicht");
        }
    }

    private String time(long sec) {
        return hhmm.format(new Date(sec * 1000));
    }

    private static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s / 60) % 60;
        return h > 0 ? String.format(Locale.GERMANY, "%d:%02d:%02d", h, m, s % 60)
                : String.format(Locale.GERMANY, "%d:%02d", m, s % 60);
    }

    private void showStatus(String msg) {
        status.setText(msg);
        status.setVisibility(View.VISIBLE);
    }

    // ---------- Senderliste (links neben dem Bild) ----------

    private boolean listOpen() {
        return listPanel != null && listPanel.getVisibility() == View.VISIBLE;
    }

    private void buildList() {
        listPanel = new LinearLayout(this);
        listPanel.setOrientation(LinearLayout.VERTICAL);
        listPanel.setBackgroundColor(PANEL);
        listPanel.setPadding(dp(16), dp(24), dp(16), dp(16));

        listTitle = text(22, Color.WHITE, true);
        listTitle.setPadding(dp(8), 0, dp(8), dp(12));
        listPanel.addView(listTitle);

        list = new ListView(this);
        list.setDivider(new ColorDrawable(0x22FFFFFF));
        list.setDividerHeight(1);
        list.setSelector(new ColorDrawable(ROYAL));
        list.setDrawSelectorOnTop(false);
        list.setItemsCanFocus(false);
        adapter = new ListAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) -> {
            handler.removeCallbacks(hideList);
            handler.postDelayed(hideList, LIST_MS);
            Node n = browse.children.get(position);
            if (n.item != null) jumpTo(n);
            else open(n);
        });
        // OK lange drücken: Film bzw. Serie als Favorit markieren oder entfernen
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            toggleFav(browse.children.get(position));
            return true;
        });
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updatePreview(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        listPanel.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        listHint = text(14, MUTED, false);
        listHint.setSingleLine(false);
        listHint.setPadding(dp(8), dp(10), dp(8), 0);
        listPanel.addView(listHint);

        listPanel.setVisibility(View.GONE);
        root.addView(listPanel, new FrameLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START));

        preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        previewName = lines(text(22, Color.WHITE, true), 3);
        previewEpg = text(18, MUTED, false);
        previewEpg.setSingleLine(false);
        previewEpg.setMaxLines(5);
        previewEpg.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(8);
        preview.addView(previewName);
        preview.addView(previewEpg, elp);
        preview.setVisibility(View.GONE);
        root.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void openList() {
        info.setVisibility(View.GONE);
        int w = root.getWidth();
        int pw = Math.max(dp(380 * Math.min(textScale, 1.15f)), Math.round(w * 0.42f));
        listPanel.getLayoutParams().width = pw;
        listPanel.setVisibility(View.VISIBLE);
        preview.setVisibility(View.VISIBLE);
        listPanel.requestLayout();
        // Gruppe des laufenden Eintrags zeigen, Auswahl auf ihm (ohne laufenden Eintrag: Startebene)
        if (idle()) {
            showNode(browseStart != null ? browseStart : rootNode, null);
        } else {
            Item playing = current();
            Node sel = null;
            for (Node c : ctx.children) if (c.item == playing) sel = c;
            showNode(ctx, sel);
        }
        list.requestFocus();
        layoutVideo();
    }

    private void closeList() {
        if (idle()) {              // ohne laufenden Eintrag bleibt die Auswahl offen
            handler.removeCallbacks(hideList);
            if (!senior) finish();
            return;
        }
        handler.removeCallbacks(hideList);
        listPanel.setVisibility(View.GONE);
        preview.setVisibility(View.GONE);
        root.requestFocus();
        layoutVideo();
    }

    /** Ebene öffnen: Suche fragt nach dem Suchbegriff, sonst Inhalt anzeigen. */
    private void open(Node n) {
        if (n.search) askSearch();
        else showNode(n, null);
    }

    /** Liste zeigt die Einträge von n; Auswahl auf select (oder dem ersten). */
    /** Übersicht: davor „Zuletzt gesehen“ und „Lieblingssender“ (aus dem Gerätespeicher). */
    private Node buildDisplayRoot() {
        Node d = new Node(rootNode.name);
        Node fav = new Node("★ Lieblingssender");
        for (String url : mem.favLive()) {
            Node leaf = findLeaf(rootNode, url);
            if (leaf != null) fav.add(copyLeaf(leaf));
        }
        Node recent = new Node("🕘 Zuletzt gesehen");
        for (String url : mem.recent()) {
            Node leaf = findLeaf(rootNode, url);
            if (leaf != null) recent.add(copyLeaf(leaf));
        }
        if (!fav.children.isEmpty()) d.add(fav);
        if (!recent.children.isEmpty()) d.add(recent);
        d.children.addAll(rootNode.children);   // Eltern der Bereiche bleiben rootNode
        displayRoot = d;
        return d;
    }

    private static Node copyLeaf(Node leaf) {
        Node c = new Node(leaf.item.heading != null ? leaf.item.heading : leaf.name);
        c.item = leaf.item;
        c.favKey = leaf.favKey != null ? leaf.favKey : leaf.parent != null ? leaf.parent.favKey : null;
        c.favType = leaf.favType != null ? leaf.favType : leaf.parent != null ? leaf.parent.favType : null;
        return c;
    }

    /** Nächste Folge vorschlagen: nach der zuletzt gesehenen, sonst die angefangene. */
    private Node suggestEpisode(Node n) {
        Node best = null;
        long bestTs = 0;
        int bestIdx = -1;
        for (int i = 0; i < n.children.size(); i++) {
            Node c = n.children.get(i);
            if (c.item == null || !"series".equals(c.item.type)) return null;
            long[] r = mem.resume(c.item.url);
            if (r != null && r[2] > bestTs) {
                bestTs = r[2];
                best = c;
                bestIdx = i;
            }
        }
        if (best == null) return null;
        if (Memory.finished(mem.resume(best.item.url)) && bestIdx + 1 < n.children.size()) {
            return n.children.get(bestIdx + 1);
        }
        return best;
    }

    private void showNode(Node n, Node select) {
        if (n == rootNode || n == displayRoot) n = buildDisplayRoot();
        if (select == null) select = suggestEpisode(n);
        browse = n;
        listTitle.setText(n.name);
        boolean leaves = !n.children.isEmpty() && n.children.get(0).item != null;
        String up = n.parent != null ? "   ◀ zurück" : "";
        boolean favs = hasFav(n);
        listHint.setText((leaves
                ? "▲ ▼ auswählen   OK abspielen" + up + "   ↩ schließen"
                : "▲ ▼ auswählen   OK / ▶ öffnen" + up + "   ↩ schließen")
                + (favs ? "\nOK lange drücken = Favorit ★ an/aus" : ""));
        adapter.notifyDataSetChanged();
        int sel = select == null ? 0 : Math.max(0, n.children.indexOf(select));
        list.setSelectionFromTop(sel, dp(120));
        list.setSelection(sel);
        updatePreview(sel);
        handler.removeCallbacks(hideList);
        handler.postDelayed(hideList, LIST_MS);
    }

    /** Suche über alle Einträge (Name und Gruppe); Ergebnisse als eigene Ebene unter der Übersicht. */
    private void askSearch() {
        handler.removeCallbacks(hideList);
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint("Sender, Film oder Serie");
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("Suche")
                .setView(input)
                .setPositiveButton("Suchen", (d, w) -> runSearch(input.getText().toString()))
                .setNegativeButton("Abbrechen", (d, w) -> handler.postDelayed(hideList, LIST_MS))
                .create();
        input.setOnEditorActionListener((v, actionId, ev) -> {
            dlg.dismiss();
            runSearch(input.getText().toString());
            return true;
        });
        dlg.show();
    }

    private void runSearch(String q) {
        String[] terms = q.trim().toLowerCase(Locale.GERMANY).split("\\s+");
        if (q.trim().isEmpty()) return;
        Node res = new Node("Suche: " + q.trim());
        res.parent = rootNode;
        collect(rootNode, "", terms, res);
        if (res.children.isEmpty()) {
            Toast.makeText(this, "Keine Treffer für „" + q.trim() + "“", Toast.LENGTH_LONG).show();
            handler.postDelayed(hideList, LIST_MS);
            return;
        }
        showNode(res, null);
        list.requestFocus();
    }

    private void collect(Node n, String path, String[] terms, Node res) {
        if (res.children.size() >= 300) return;
        if (n.item != null) {
            String hay = (n.item.heading + " " + path).toLowerCase(Locale.GERMANY);
            for (String t : terms) if (!hay.contains(t)) return;
            Node copy = new Node(n.item.heading);
            copy.item = n.item;
            copy.parent = res;
            res.children.add(copy);
            return;
        }
        String p = n.parent == null ? "" : path + " " + n.name;
        for (Node c : n.children) collect(c, p, terms, res);
    }

    /** Unter dem kleinen Bild: Programm des markierten Senders bzw. Inhalt der markierten Ebene. */
    private void updatePreview(int position) {
        if (browse == null || position < 0 || position >= browse.children.size()) return;
        Node n = browse.children.get(position);
        if (n.item == null) {
            previewName.setText(n.name);
            if (n.search) {
                previewEpg.setText("Alle Sender, Filme und Serien durchsuchen");
                return;
            }
            StringBuilder sb = new StringBuilder(countText(n));
            for (int k = 0; k < Math.min(4, n.children.size()); k++) sb.append("\n").append(n.children.get(k).name);
            previewEpg.setText(sb);
            return;
        }
        Item it = n.item;
        previewName.setText(it.heading != null ? it.heading : it.name);
        if (!it.live()) {
            previewEpg.setText("OK = abspielen");
            return;
        }
        int now = it.now();
        if (now < 0) {
            previewEpg.setText(it.start.length == 0 ? "Keine Programmdaten" : "");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int k = now; k < Math.min(it.start.length, now + 5); k++) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(k == now ? "Jetzt  " : time(it.start[k]) + "   ").append(it.title[k]);
        }
        previewEpg.setText(sb);
    }

    /** Favorit des Eintrags (oder des Films/der Serie darüber) umschalten, überall im Baum anzeigen. */
    private void toggleFav(Node n) {
        if (n.item != null && n.item.live()) {
            boolean on = mem.toggleFavLive(n.item.url);
            adapter.notifyDataSetChanged();
            Toast.makeText(this, on ? "★ Lieblingssender – steht jetzt oben in der Übersicht"
                    : "☆ Kein Lieblingssender mehr", Toast.LENGTH_SHORT).show();
            return;
        }
        Node f = n;
        while (f != null && f.favKey == null) f = f.parent;
        if (f == null) {
            Toast.makeText(this, "Favoriten gibt es für Sender, Filme und Serien.", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean on = !f.fav;
        markFav(rootNode, f.favType, f.favKey, on);
        f.fav = on;
        java.util.Map<String, Boolean> m = favChanges.get(f.favType);
        if (m == null) favChanges.put(f.favType, m = new java.util.HashMap<>());
        m.put(f.favKey, on);
        adapter.notifyDataSetChanged();
        Toast.makeText(this, on ? "★ Zu den Favoriten hinzugefügt" : "☆ Aus den Favoriten entfernt", Toast.LENGTH_SHORT).show();
    }

    private static void markFav(Node n, String type, String key, boolean on) {
        if (n == null) return;
        if (key.equals(n.favKey) && type.equals(n.favType)) n.fav = on;
        for (Node c : n.children) markFav(c, type, key, on);
    }

    /** „97 Sender“, „12 Filme“, „3 Fassungen“ … */
    private static String countText(Node n) {
        if (n.search) return "";
        if (n.variants) return n.children.size() + " Fassungen";
        int c = n.count();
        Node first = n;
        while (first.item == null && !first.children.isEmpty()) first = first.children.get(0);
        String type = first.item == null ? "" : first.item.type;
        return c + ("live".equals(type) ? " Sender" : "movie".equals(type) ? (c == 1 ? " Film" : " Filme")
                : "series".equals(type) ? (c == 1 ? " Folge" : " Folgen") : " Einträge");
    }

    private class ListAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return browse == null ? 0 : browse.children.size();
        }

        @Override
        public Object getItem(int position) {
            return browse.children.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row = convertView != null && convertView.getTag() instanceof Row
                    ? (Row) convertView.getTag() : new Row();
            Node n = browse.children.get(position);
            Item playing = idle() ? null : items().get(index);
            if (n.item == null) {
                boolean inside = contains(n, playing);
                row.num.setText(inside ? "▶" : n.search ? "" : "›");
                row.num.setTextColor(inside ? ROYAL_LIGHT : MUTED);
                row.logo.setVisibility(View.GONE);
                row.name.setText(n.fav ? "★ " + n.name : n.name);
                String sub = countText(n);
                row.now.setText(sub);
                row.now.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
                return row.view;
            }
            Item it = n.item;
            boolean isPlaying = it == playing;
            row.num.setText(isPlaying ? "▶" : String.valueOf(position + 1));
            row.num.setTextColor(isPlaying ? ROYAL_LIGHT : MUTED);
            row.logo.setVisibility(it.logo == null || it.logo.isEmpty() ? View.GONE : View.VISIBLE);
            boolean star = n.fav || (it.live() && mem.isFavLive(it.url));
            row.name.setText(star ? "★ " + n.name : n.name);
            String now = it.live() ? it.nowTitle() : progressText(it);
            row.now.setText(now);
            row.now.setVisibility(now.isEmpty() ? View.GONE : View.VISIBLE);
            images.load(it.logo, row.logo);
            return row.view;
        }
    }

    /** Filme/Folgen: „✓ gesehen“ bzw. „▶ 42 % gesehen“. */
    private String progressText(Item it) {
        long[] r = mem.resume(it.url);
        if (r == null || r[1] <= 0) return "";
        if (Memory.finished(r)) return "✓ gesehen";
        return "▶ " + Math.max(1, Math.round(100.0 * r[0] / r[1])) + " % gesehen";
    }

    /** Gibt es in dieser Ebene Sender, Filme oder Serien (Favoriten möglich)? */
    private static boolean hasFav(Node n) {
        for (Node c : n.children) {
            if (c.favKey != null) return true;
            if (c.item != null && c.item.live()) return true;
            if (c.item != null && n.favKey != null) return true;
        }
        return n.favKey != null;
    }

    private static boolean contains(Node n, Item it) {
        if (n.item != null) return n.item == it;
        for (Node c : n.children) if (contains(c, it)) return true;
        return false;
    }

    private class Row {
        final LinearLayout view = new LinearLayout(PlayerActivity.this);
        final TextView num = text(16, MUTED, true);
        final ImageView logo = new ImageView(PlayerActivity.this);
        final TextView name = lines(text(18, Color.WHITE, true), 2);   // Titel nicht abschneiden
        final TextView now = text(15, MUTED, false);

        Row() {
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(dp(8), dp(8), dp(12), dp(8));
            view.setMinimumHeight(dp(68 * textScale));
            num.setGravity(Gravity.END);
            view.addView(num, new LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT));
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(44));
            lp.leftMargin = dp(12);
            view.addView(logo, lp);
            LinearLayout texts = new LinearLayout(PlayerActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(name);
            texts.addView(now);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            tlp.leftMargin = dp(12);
            view.addView(texts, tlp);
            view.setTag(this);
        }
    }
}
