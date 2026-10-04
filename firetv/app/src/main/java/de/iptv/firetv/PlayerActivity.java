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
public class PlayerActivity extends Activity {

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
        buildViews();

        vlc = new LibVLC(this, new ArrayList<>(Arrays.asList(
                "--http-reconnect",
                "--audio-language=de,deu,ger",
                "--audio-time-stretch")));
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
                    spinner.setVisibility(View.GONE);
                    status.setVisibility(View.GONE);
                    break;
                case MediaPlayer.Event.TimeChanged:
                    position = event.getTimeChanged();
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

        play();
        handler.post(tick);
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
            if (n == null || n.item == null) return false;
            rootNode = treeRoot;
            if ("senioren".equals(o.optString("view"))) senior = true;   // gleiche Schriftgröße wie Komplett
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

    private Item current() {
        return items().get(pendingIndex >= 0 ? pendingIndex : index);
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
        start(it);
        if (!listOpen()) showInfo();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void start(Item it) {
        Media media = new Media(vlc, Uri.parse(it.url));
        media.setHWDecoderEnabled(true, false);
        media.addOption(":http-user-agent=" + USER_AGENT);
        media.addOption(":network-caching=" + (it.live() ? 2000 : 3000));
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
        if (leaf.parent == ctx && leaf.item == items().get(index) && pendingIndex < 0 && !failed) return;
        handler.removeCallbacks(tune);
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
        String keys = it.live() || items().size() > 1
                ? "\n\n▲ ▼ anderen Sender wählen  ·  OK = in VLC öffnen" : "\n\nOK = in VLC öffnen";
        showStatus("„" + it.name + "“ kann gerade nicht abgespielt werden.\n"
                + "Läuft auf einem anderen Gerät schon Fernsehen? Der Anbieter erlaubt nur eines." + keys);
    }

    private void togglePause() {
        if (player.isPlaying()) player.pause(); else player.play();
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

    // ---------- Lebenszyklus: Verbindung immer freigeben ----------

    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing()) finish();     // z. B. Home-Taste: Stream beenden, nicht im Hintergrund weiterlaufen
    }

    @Override
    protected void onDestroy() {
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
        infoName = text(32, Color.WHITE, true);
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
        if (listOpen()) return;
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
        previewName = text(24, Color.WHITE, true);
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
        // Gruppe des laufenden Eintrags zeigen, Auswahl auf ihm
        Item playing = items().get(pendingIndex >= 0 ? pendingIndex : index);
        Node sel = null;
        for (Node c : ctx.children) if (c.item == playing) sel = c;
        showNode(ctx, sel);
        list.requestFocus();
        layoutVideo();
    }

    private void closeList() {
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
    private void showNode(Node n, Node select) {
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
        Node f = n;
        while (f != null && f.favKey == null) f = f.parent;
        if (f == null) {
            Toast.makeText(this, "Favoriten gibt es für Filme und Serien.", Toast.LENGTH_SHORT).show();
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
            Item playing = items().get(index);
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
            row.name.setText(n.fav ? "★ " + n.name : n.name);
            String now = it.nowTitle();
            row.now.setText(now);
            row.now.setVisibility(now.isEmpty() ? View.GONE : View.VISIBLE);
            images.load(it.logo, row.logo);
            return row.view;
        }
    }

    /** Gibt es in dieser Ebene Filme/Serien (Favoriten möglich)? */
    private static boolean hasFav(Node n) {
        for (Node c : n.children) {
            if (c.favKey != null) return true;
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
        final TextView name = text(20, Color.WHITE, true);
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
