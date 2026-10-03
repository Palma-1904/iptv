package de.iptv.firetv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
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
import android.view.SurfaceView;
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

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Eingebauter Player (ExoPlayer) für die Fire-TV-App.
 *
 * Live:   ▲/▼ Sender davor/danach (innerhalb der Gruppe), ◀/▶ oder ☰ Senderliste, OK Info.
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
    private static final int PANEL = 0xEE0B1220;
    private static final int MUTED = 0xFFB8C2D6;
    private static final long TUNE_DELAY = 700;   // ms Ruhe nach dem Umschalten, dann verbinden
    private static final long INFO_MS = 6000;
    private static final long LIST_MS = 15000;

    static class Item {
        String name, url, logo, tvgId, type, group;
        long[] start = new long[0], end = new long[0];
        String[] title = new String[0];

        boolean live() { return "live".equals(type); }

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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", Locale.GERMANY);
    private final List<Item> items = new ArrayList<>();
    private int index;
    private int pendingIndex = -1;
    private int retries;
    private boolean failed;
    private float textScale = 1f;

    private ExoPlayer player;
    private FrameLayout root;
    private SurfaceView surface;
    private int videoW, videoH;
    private ProgressBar spinner;
    private TextView status;
    private LinearLayout info;
    private ImageView infoLogo;
    private TextView infoNum, infoName, infoNow, infoNext, infoClock, infoHint;
    private ProgressBar infoProgress;
    private LinearLayout listPanel;
    private ListView list;
    private ChannelAdapter adapter;
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
        if (!readItems(pending)) {
            finish();
            return;
        }
        pending = null;
        images = new ImageLoader();
        buildViews();

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .build();
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                spinner.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (state == Player.STATE_READY) {
                    retries = 0;
                    failed = false;
                    status.setVisibility(View.GONE);
                } else if (state == Player.STATE_ENDED) {
                    onEnded();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                onError(error);
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                videoW = Math.round(size.width * size.pixelWidthHeightRatio);
                videoH = size.height;
                layoutVideo();
            }
        });

        play();
        handler.post(tick);
    }

    private boolean readItems(String json) {
        if (json == null) return false;
        try {
            JSONObject o = new JSONObject(json);
            JSONArray arr = o.getJSONArray("items");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject j = arr.getJSONObject(i);
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
                items.add(it);
            }
            index = Math.max(0, Math.min(items.size() - 1, o.optInt("index")));
            if ("senioren".equals(o.optString("view"))) textScale = 1.25f;
            return !items.isEmpty();
        } catch (Exception e) {
            Toast.makeText(this, "Wiedergabe nicht möglich: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    // ---------- Wiedergabe ----------

    private Item current() {
        return items.get(pendingIndex >= 0 ? pendingIndex : index);
    }

    private void play() {
        Item it = items.get(index);
        lastUrl = it.url;
        failed = false;
        status.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE);
        player.setMediaItem(MediaItem.fromUri(it.url));
        player.prepare();
        player.setPlayWhenReady(true);
        showInfo();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    /** Sender/Folge wechseln: alten Stream sofort beenden, neuen erst nach kurzer Ruhe laden. */
    private void step(int delta) {
        int size = items.size();
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

    private void jumpTo(int i) {
        if (i == index && pendingIndex < 0 && !failed) return;
        pendingIndex = i;
        player.stop();
        handler.removeCallbacks(tune);
        tune.run();
    }

    private void onEnded() {
        Item it = items.get(index);
        if (it.live()) {
            onError(null);
        } else if (index < items.size() - 1) {
            step(1);                       // nächste Folge
        } else {
            finish();
        }
    }

    private void onError(PlaybackException error) {
        Item it = items.get(index);
        // Live-Stream zu weit zurück: einfach an die aktuelle Stelle springen
        if (error != null && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition();
            player.prepare();
            return;
        }
        int code = 0;
        Throwable cause = error == null ? null : error.getCause();
        if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
            code = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
        }
        if (retries < 2) {
            retries++;
            showStatus("Verbindung wird erneut aufgebaut …");
            spinner.setVisibility(View.VISIBLE);
            final int at = index;
            handler.postDelayed(() -> {
                if (at == index && pendingIndex < 0) {
                    player.prepare();
                    player.setPlayWhenReady(true);
                }
            }, 2500);
            return;
        }
        failed = true;
        spinner.setVisibility(View.GONE);
        String why = code == 403 || code == 458 || code == 509 || code == 429
                ? "Der Anbieter erlaubt nur ein Gerät gleichzeitig.\nBitte auf den anderen Geräten das Fernsehen beenden."
                : "„" + it.name + "“ kann gerade nicht abgespielt werden.";
        String keys = it.live() || items.size() > 1 ? "\n\n▲ ▼ anderen Sender wählen  ·  OK = in VLC öffnen" : "\n\nOK = in VLC öffnen";
        showStatus(why + keys);
    }

    private void togglePause() {
        player.setPlayWhenReady(!player.getPlayWhenReady());
        showInfo();
    }

    private void seek(long ms) {
        long dur = player.getDuration();
        long pos = Math.max(0, player.getCurrentPosition() + ms);
        if (dur != C.TIME_UNSET) pos = Math.min(pos, Math.max(0, dur - 1000));
        player.seekTo(pos);
        showInfo();
    }

    private void openInVlc() {
        Item it = items.get(index);
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean live = current().live();
        if (listPanel.getVisibility() == View.VISIBLE) {
            handler.removeCallbacks(hideList);
            handler.postDelayed(hideList, LIST_MS);
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
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
        if (listPanel.getVisibility() == View.VISIBLE) {
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
            player.release();
            player = null;
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

        surface = new SurfaceView(this);
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

    /** Video im Seitenverhältnis einpassen (schwarze Ränder statt Verzerrung). */
    private void layoutVideo() {
        int w = root.getWidth(), h = root.getHeight();
        if (videoW <= 0 || videoH <= 0 || w == 0 || h == 0) return;
        float aspect = (float) videoW / videoH;
        int vw = w, vh = Math.round(w / aspect);
        if (vh > h) {
            vh = h;
            vw = Math.round(h * aspect);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (lp.width == vw && lp.height == vh) return;
        surface.setLayoutParams(new FrameLayout.LayoutParams(vw, vh, Gravity.CENTER));
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
        infoProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF4D7CFF));
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
        infoNum.setText(items.size() > 1 ? (i + 1) + "/" + items.size() : "");
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
                infoNow.setText(it.group == null ? "" : it.group);
                infoProgress.setVisibility(View.GONE);
                infoNext.setText("");
            }
            infoHint.setText(items.size() > 1
                    ? "▲ ▼  Sender wechseln     ◀ ▶  Senderliste     OK  Info     ↩  Übersicht"
                    : "OK  Info     ↩  Übersicht");
        } else {
            long pos = player.getCurrentPosition(), dur = player.getDuration();
            boolean known = dur != C.TIME_UNSET && dur > 0;
            infoNow.setText((player.getPlayWhenReady() ? "▶  " : "❚❚  Pause   ")
                    + duration(pos) + (known ? "  /  " + duration(dur) : ""));
            infoProgress.setVisibility(known ? View.VISIBLE : View.GONE);
            if (known) infoProgress.setProgress((int) (1000 * pos / dur));
            infoNext.setText(items.size() > 1 && i + 1 < items.size() ? "Nächste Folge:  " + items.get(i + 1).name : "");
            infoHint.setText("◀ ▶  Spulen (gedrückt halten = schneller)     OK  Pause"
                    + (items.size() > 1 ? "     ▲ ▼  Folge" : "") + "     ↩  Übersicht");
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

    // ---------- Senderliste ----------

    private void buildList() {
        listPanel = new LinearLayout(this);
        listPanel.setOrientation(LinearLayout.VERTICAL);
        listPanel.setBackgroundColor(PANEL);
        listPanel.setPadding(dp(16), dp(24), dp(16), dp(16));

        TextView head = text(22, Color.WHITE, true);
        Item first = items.get(index);
        head.setText(first.group == null || first.group.isEmpty() ? "Sender" : first.group);
        head.setPadding(dp(8), 0, dp(8), dp(12));
        listPanel.addView(head);

        list = new ListView(this);
        list.setDivider(new ColorDrawable(0x22FFFFFF));
        list.setDividerHeight(1);
        list.setSelector(new ColorDrawable(ROYAL));
        list.setDrawSelectorOnTop(false);
        list.setItemsCanFocus(false);
        adapter = new ChannelAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) -> {
            closeList();
            jumpTo(position);
        });
        listPanel.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView hint = text(14, MUTED, false);
        hint.setText("▲ ▼ auswählen   OK umschalten   ◀ ▶ schließen");
        hint.setPadding(dp(8), dp(10), dp(8), 0);
        listPanel.addView(hint);

        listPanel.setVisibility(View.GONE);
        root.addView(listPanel, new FrameLayout.LayoutParams(dp(480 * Math.min(textScale, 1.15f)),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
    }

    private void openList() {
        if (items.size() < 2) {
            showInfo();
            return;
        }
        info.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
        listPanel.setVisibility(View.VISIBLE);
        int sel = pendingIndex >= 0 ? pendingIndex : index;
        list.requestFocus();
        list.setSelectionFromTop(sel, dp(160));
        list.setSelection(sel);
        handler.removeCallbacks(hideList);
        handler.postDelayed(hideList, LIST_MS);
    }

    private void closeList() {
        handler.removeCallbacks(hideList);
        listPanel.setVisibility(View.GONE);
        root.requestFocus();
    }

    private class ChannelAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row = convertView instanceof LinearLayout && convertView.getTag() instanceof Row
                    ? (Row) convertView.getTag() : new Row();
            Item it = items.get(position);
            boolean playing = position == index;
            row.num.setText(playing ? "▶" : String.valueOf(position + 1));
            row.num.setTextColor(playing ? 0xFF8FB0FF : MUTED);
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
            lp.rightMargin = dp(12);
            view.addView(logo, lp);
            LinearLayout texts = new LinearLayout(PlayerActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(name);
            texts.addView(now);
            view.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            view.setTag(this);
        }
    }
}
