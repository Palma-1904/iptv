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

    static class Group {
        final String name;
        final List<Item> items = new ArrayList<>();

        Group(String name) {
            this.name = name;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", Locale.GERMANY);
    private final List<Group> groups = new ArrayList<>();
    private int group;              // spielende Gruppe
    private int index;              // spielender Eintrag in dieser Gruppe
    private int pendingIndex = -1;  // Ziel beim Umschalten mit ▲/▼ (gleiche Gruppe)
    private int retries;
    private boolean failed;
    private float textScale = 1f;

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
    private boolean showGroups;     // Liste zeigt gerade die Gruppen statt der Sender
    private int browseGroup;        // Gruppe, deren Sender die Liste zeigt
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
        if (!readData(pending)) {
            finish();
            return;
        }
        pending = null;
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

    /** Neue Webapp: "groups" + "group" + "index"; ältere: nur "items" + "index". */
    private boolean readData(String json) {
        if (json == null) return false;
        try {
            JSONObject o = new JSONObject(json);
            JSONArray gs = o.optJSONArray("groups");
            if (gs != null && gs.length() > 0) {
                for (int g = 0; g < gs.length(); g++) {
                    JSONObject jg = gs.getJSONObject(g);
                    Group grp = new Group(jg.optString("name"));
                    JSONArray arr = jg.getJSONArray("items");
                    for (int i = 0; i < arr.length(); i++) grp.items.add(readItem(arr.getJSONObject(i)));
                    if (!grp.items.isEmpty()) groups.add(grp);
                }
                group = Math.max(0, Math.min(groups.size() - 1, o.optInt("group")));
            } else {
                JSONArray arr = o.getJSONArray("items");
                Group grp = new Group("");
                for (int i = 0; i < arr.length(); i++) grp.items.add(readItem(arr.getJSONObject(i)));
                if (!grp.items.isEmpty()) groups.add(grp);
                group = 0;
            }
            if (groups.isEmpty()) return false;
            index = Math.max(0, Math.min(items().size() - 1, o.optInt("index")));
            if ("senioren".equals(o.optString("view"))) textScale = 1.25f;
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "Wiedergabe nicht möglich: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    // ---------- Wiedergabe ----------

    private List<Item> items() {
        return groups.get(group).items;
    }

    private Item current() {
        return items().get(pendingIndex >= 0 ? pendingIndex : index);
    }

    private void play() {
        Item it = items().get(index);
        lastUrl = it.url;
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

    /** Direkt umschalten (aus der Senderliste), auch in eine andere Gruppe. */
    private void jumpTo(int g, int i) {
        if (g == group && i == index && pendingIndex < 0 && !failed) return;
        handler.removeCallbacks(tune);
        player.stop();
        group = g;
        index = i;
        pendingIndex = -1;
        retries = 0;
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
            final int atGroup = group, at = index;
            handler.postDelayed(() -> {
                if (atGroup == group && at == index && pendingIndex < 0) {
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
        if (listOpen()) {
            handler.removeCallbacks(hideList);
            handler.postDelayed(hideList, LIST_MS);
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (!showGroups && groups.size() > 1) showGroupList();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (showGroups) showChannelList(list.getSelectedItemPosition());
                    else closeList();
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    closeList();
                    return true;
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
            case KeyEvent.KEYCODE_MENU:
                if (live) openList(); else showInfo();
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
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (listOpen()) {
            closeList();
        } else if (info.getVisibility() == View.VISIBLE && !failed) {
            info.setVisibility(View.GONE);
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
        infoName.setText(it.name);
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
                String g = groups.get(group).name;
                infoNow.setText(g == null ? "" : g);
                infoProgress.setVisibility(View.GONE);
                infoNext.setText("");
            }
            infoHint.setText(size > 1 || groups.size() > 1
                    ? "▲ ▼  Sender wechseln     ◀ ▶  Senderliste     OK  Info     ↩  Übersicht"
                    : "OK  Info     ↩  Übersicht");
        } else {
            long pos = position, dur = length;
            boolean known = dur > 0;
            infoNow.setText((player.isPlaying() ? "▶  " : "❚❚  Pause   ")
                    + duration(pos) + (known ? "  /  " + duration(dur) : ""));
            infoProgress.setVisibility(known ? View.VISIBLE : View.GONE);
            if (known) infoProgress.setProgress((int) (1000 * pos / dur));
            infoNext.setText(size > 1 && i + 1 < size ? "Nächste Folge:  " + items().get(i + 1).name : "");
            infoHint.setText("◀ ▶  Spulen (gedrückt halten = schneller)     OK  Pause"
                    + (size > 1 ? "     ▲ ▼  Folge" : "") + "     ↩  Übersicht");
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
            if (showGroups) showChannelList(position);
            else jumpTo(browseGroup, position);
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
        if (items().size() < 2 && groups.size() < 2) {
            showInfo();
            return;
        }
        info.setVisibility(View.GONE);
        int w = root.getWidth();
        int pw = Math.max(dp(380 * Math.min(textScale, 1.15f)), Math.round(w * 0.42f));
        listPanel.getLayoutParams().width = pw;
        listPanel.setVisibility(View.VISIBLE);
        preview.setVisibility(View.VISIBLE);
        listPanel.requestLayout();
        showChannelList(group);
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

    /** Liste zeigt die Sender einer Gruppe; Auswahl auf dem laufenden Sender, falls in dieser Gruppe. */
    private void showChannelList(int g) {
        if (g < 0 || g >= groups.size()) g = group;
        showGroups = false;
        browseGroup = g;
        String name = groups.get(g).name;
        listTitle.setText(name == null || name.isEmpty() ? "Sender" : name);
        listHint.setText(groups.size() > 1
                ? "▲ ▼ auswählen   OK umschalten   ◀ Gruppen   ▶ / ↩ schließen"
                : "▲ ▼ auswählen   OK umschalten   ▶ / ↩ schließen");
        adapter.notifyDataSetChanged();
        int sel = g == group ? (pendingIndex >= 0 ? pendingIndex : index) : 0;
        list.setSelectionFromTop(sel, dp(120));
        list.setSelection(sel);
        updatePreview(sel);
        handler.removeCallbacks(hideList);
        handler.postDelayed(hideList, LIST_MS);
    }

    private void showGroupList() {
        showGroups = true;
        listTitle.setText("Gruppen");
        listHint.setText("▲ ▼ auswählen   OK / ▶ Sender zeigen   ↩ schließen");
        adapter.notifyDataSetChanged();
        list.setSelectionFromTop(browseGroup, dp(120));
        list.setSelection(browseGroup);
        updatePreview(browseGroup);
    }

    /** Unter dem kleinen Bild: Programm des markierten Senders bzw. Inhalt der markierten Gruppe. */
    private void updatePreview(int position) {
        if (showGroups) {
            if (position < 0 || position >= groups.size()) return;
            Group g = groups.get(position);
            previewName.setText(g.name);
            StringBuilder sb = new StringBuilder(g.items.size() + " Sender");
            for (int k = 0; k < Math.min(4, g.items.size()); k++) sb.append("\n").append(g.items.get(k).name);
            previewEpg.setText(sb);
            return;
        }
        List<Item> its = groups.get(browseGroup).items;
        if (position < 0 || position >= its.size()) return;
        Item it = its.get(position);
        previewName.setText(it.name);
        int n = it.now();
        if (n < 0) {
            previewEpg.setText(it.start.length == 0 ? "Keine Programmdaten" : "");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int k = n; k < Math.min(it.start.length, n + 5); k++) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(k == n ? "Jetzt  " : time(it.start[k]) + "   ").append(it.title[k]);
        }
        previewEpg.setText(sb);
    }

    private class ListAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return showGroups ? groups.size() : groups.get(browseGroup).items.size();
        }

        @Override
        public Object getItem(int position) {
            return showGroups ? groups.get(position) : groups.get(browseGroup).items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row = convertView != null && convertView.getTag() instanceof Row
                    ? (Row) convertView.getTag() : new Row();
            if (showGroups) {
                Group g = groups.get(position);
                boolean playing = position == group;
                row.num.setText(playing ? "▶" : "");
                row.num.setTextColor(ROYAL_LIGHT);
                row.logo.setVisibility(View.GONE);
                row.name.setText(g.name);
                row.now.setText(g.items.size() + " Sender");
                row.now.setVisibility(View.VISIBLE);
                return row.view;
            }
            Item it = groups.get(browseGroup).items.get(position);
            boolean playing = browseGroup == group && position == index;
            row.num.setText(playing ? "▶" : String.valueOf(position + 1));
            row.num.setTextColor(playing ? ROYAL_LIGHT : MUTED);
            row.logo.setVisibility(View.VISIBLE);
            row.name.setText(it.name);
            String now = it.nowTitle();
            row.now.setText(now);
            row.now.setVisibility(now.isEmpty() ? View.GONE : View.VISIBLE);
            images.load(it.logo, row.logo);
            return row.view;
        }
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
