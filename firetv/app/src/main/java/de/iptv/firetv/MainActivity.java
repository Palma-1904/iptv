package de.iptv.firetv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONObject;

import java.net.URISyntaxException;

/**
 * Zeigt die IPTV-Webapp im Vollbild. Streams spielt der eingebaute Player (PlayerActivity):
 * Die Webapp erkennt die App am User-Agent "IPTVApp" und ruft IPTVNative.play(...) mit der
 * Senderliste der Gruppe auf. intent://-Links (VLC) funktionieren weiterhin.
 * Menü-Taste (☰) der Fernbedienung: Einrichtung (Playlist und Ansicht) erneut öffnen.
 */
public class MainActivity extends Activity implements Remote.Target {

    private static final String VLC = "org.videolan.vlc";
    private static final String PREF_CONFIGURED = "configured";

    private WebView web;
    private static MainActivity instance;

    /** Vom Player: Playlist und Programm im Hintergrund neu laden (die Webapp schickt dann einen neuen Baum). */
    static void requestRefresh() {
        MainActivity a = instance;
        if (a == null || a.web == null) return;
        a.runOnUiThread(() -> a.web.evaluateJavascript("window.IPTV&&IPTV.nativeRefresh&&IPTV.nativeRefresh()", null));
    }
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("iptv", MODE_PRIVATE);
        instance = this;
        Remote.init(this);
        Remote.main_target = this;
        AutostartService.start(this);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);              // Webapp speichert Playlist und Ansicht im localStorage
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE); // http-Logos auf https-Seite
        s.setUserAgentString(s.getUserAgentString() + " IPTVApp/1");
        web.addJavascriptInterface(new Bridge(), "IPTVNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleLink(url);
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                // Kein Internet o. ä.: Hinweis zeigen und in 15 Sekunden erneut versuchen
                view.loadDataWithBaseURL(null,
                        "<html><body style='background:#000;color:#fff;font:28px sans-serif;text-align:center;padding-top:20%'>"
                                + "Keine Verbindung zum Internet.<br><br>Neuer Versuch in wenigen Sekunden …</body></html>",
                        "text/html", "utf-8", null);
                handler.removeCallbacksAndMessages(null);
                handler.postDelayed(() -> load(null), 15000);
            }
        });
        web.setFocusable(true);
        web.requestFocus();

        handler.postDelayed(() -> Updater.check(this, true), 8000);   // neue App-Version? (bei jedem Start)
        if (prefs.getBoolean(PREF_CONFIGURED, false)) {
            load(null);
        } else {
            showSetup();
        }
    }

    private void load(String hash) {
        String url = BuildConfig.START_URL;
        if (hash != null && !hash.isEmpty()) url += "#" + hash;
        web.loadUrl(url);
    }

    /** Normale Seiten bleiben in der App; intent://-Links (Streams) gehen an VLC. */
    private boolean handleLink(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) return false;
        try {
            Intent intent = url.startsWith("intent:")
                    ? Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setComponent(null);
            intent.setSelector(null);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Bitte zuerst VLC aus dem Amazon Appstore installieren.", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("amzn://apps/android?p=" + VLC)));
            } catch (ActivityNotFoundException ignored) {
                // kein Appstore verfügbar
            }
        } catch (URISyntaxException e) {
            Toast.makeText(this, "Ungültiger Link.", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    /** Schnittstelle für die Webapp: Wiedergabe im eingebauten Player. */
    private class Bridge {
        /** Seniorenansicht: zuletzt gesehener Sender (Start beim Öffnen der App). */
        @JavascriptInterface
        public String lastSeniorUrl() {
            return prefs.getString("lastSeniorUrl", "");
        }

        /** Hat der Player den Playlist-Baum in dieser Fassung schon? */
        @JavascriptInterface
        public boolean hasTree(String version) {
            return PlayerActivity.treeRoot != null && version != null && version.equals(PlayerActivity.treeVersion);
        }

        /** Ganze Playlist als Baum (läuft im Hintergrund-Thread der Brücke, nicht im Bild-Thread). */
        @JavascriptInterface
        public void setTree(String version, String json) {
            try {
                PlayerActivity.Node root = PlayerActivity.parseTree(new JSONObject(json));
                PlayerActivity.treeRoot = root;
                PlayerActivity.treeVersion = version;
            } catch (Exception e) {
                PlayerActivity.treeRoot = null;
                PlayerActivity.treeVersion = null;
            }
        }

        /** Fernwartung: welche Playlist und Ansicht dieses Gerät nutzt. */
        @JavascriptInterface
        public void hello(String json) {
            try {
                JSONObject o = new JSONObject(json);
                Remote.setPlaylist(o.optString("playlist"), o.optString("view"));
            } catch (Exception ignored) {
                // nicht wichtig
            }
        }

        /** Eintrag im Baum abspielen: {"path":[…], "view":"komplett|senioren"} */
        @JavascriptInterface
        public void playPath(String json) {
            runOnUiThread(() -> {
                String url = web.getUrl();
                if (url == null || !url.startsWith(BuildConfig.START_URL)) return;
                PlayerActivity.pendingPath = json;
                PlayerActivity.pending = null;
                startActivity(new Intent(MainActivity.this, PlayerActivity.class));
            });
        }

        @JavascriptInterface
        public void play(String json) {
            runOnUiThread(() -> {
                // Nur für die eigene Webapp, nicht für fremde Seiten
                String url = web.getUrl();
                if (url == null || !url.startsWith(BuildConfig.START_URL)) return;
                PlayerActivity.pending = json;
                PlayerActivity.pendingPath = null;
                startActivity(new Intent(MainActivity.this, PlayerActivity.class));
            });
        }
    }

    /** Einrichtung: Einrichtungs-Link aus dem Editor eingeben, Ansicht wählen. */
    private void showSetup() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("Einrichtungs-Link oder BENUTZER/GIST/name");
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(48, 8, 48, 0);
        box.addView(input);
        // Autostart beim Einschalten: an/aus und (ab Fire OS 8) einmalig erlauben
        final boolean auto = AutostartService.enabled(this);
        android.widget.Button autoBtn = new android.widget.Button(this);
        autoBtn.setText(auto ? "Autostart beim Einschalten: AN (ausschalten)" : "Autostart beim Einschalten: AUS (einschalten)");
        autoBtn.setOnClickListener(v -> {
            prefs.edit().putBoolean("autostart", !auto).apply();
            if (!auto) AutostartService.start(this);
            else stopService(new Intent(this, AutostartService.class));
            Toast.makeText(this, !auto ? "Autostart eingeschaltet" : "Autostart ausgeschaltet", Toast.LENGTH_SHORT).show();
            autoBtn.setText(!auto ? "Autostart beim Einschalten: AN" : "Autostart beim Einschalten: AUS");
            autoBtn.setEnabled(false);
        });
        box.addView(autoBtn);
        if (auto && android.os.Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
            android.widget.Button allowAuto = new android.widget.Button(this);
            allowAuto.setText("Autostart erlauben (einmalig: „Über anderen Apps einblenden“)");
            allowAuto.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    Toast.makeText(this, "Diese Einstellung gibt es auf diesem Stick nicht – Autostart funktioniert dann nur nach dem Hochfahren.",
                            Toast.LENGTH_LONG).show();
                }
            });
            box.addView(allowAuto);
        }
        // Einmalig erlauben, damit Updates (auch per Fernwartung) nur noch „Installieren“ brauchen
        if (!Updater.installAllowed(this)) {
            android.widget.Button allow = new android.widget.Button(this);
            allow.setText("Automatische Updates erlauben (einmalig)");
            allow.setOnClickListener(v -> Updater.openInstallPermission(this));
            box.addView(allow);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Einrichtung")
                .setMessage("Einrichtungs-Link aus dem Playlist-Editor eingeben und Ansicht wählen.\n\n"
                        + "Tipp: Mit der Amazon-Fire-TV-App auf dem Handy kann man bequem tippen oder einfügen.\n"
                        + "Später erneut: Menü-Taste (☰) auf der Fernbedienung 3 Sekunden gedrückt halten.")
                .setView(box)
                .setPositiveButton("Komplett", (d, w) -> applySetup(input.getText().toString(), "komplett"))
                .setNegativeButton("Senioren", (d, w) -> applySetup(input.getText().toString(), "senioren"))
                .setNeutralButton("Abbrechen", (d, w) -> {
                    if (web.getUrl() == null) load(null);
                    else web.evaluateJavascript("window.IPTV&&IPTV.onAppResume&&IPTV.onAppResume()", null);
                })
                .setCancelable(false)
                .create();
        dialog.show();
    }

    private void applySetup(String raw, String view) {
        String v = raw.trim();
        String hash;
        int i = v.indexOf('#');
        if (i >= 0) {
            hash = v.substring(i + 1);                       // kompletter Link: Teil hinter "#"
        } else if (v.matches("[\\w.-]+/[\\w.-]+/[\\w.-]+")) {
            hash = "liste=" + v;                             // Kurzform BENUTZER/GIST/name
        } else if (v.isEmpty()) {
            hash = "";                                       // nur Ansicht ändern
        } else {
            Toast.makeText(this, "Das ist kein Einrichtungs-Link.", Toast.LENGTH_LONG).show();
            showSetup();
            return;
        }
        hash = hash.replaceAll("&?ansicht=[a-z]+", "");
        hash = (hash.isEmpty() ? "" : hash + "&") + "ansicht=" + view;
        prefs.edit().putBoolean(PREF_CONFIGURED, true).apply();
        load(hash);
    }

    // ☰ nur nach 3 Sekunden Halten: Einrichtung (gegen versehentliches Drücken)
    private final Runnable menuHint = () -> Toast.makeText(this,
            "Für die Einrichtung ☰ weiter gedrückt halten …", Toast.LENGTH_SHORT).show();
    private final Runnable menuSetup = this::showSetup;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.getRepeatCount() == 0) {
                handler.postDelayed(menuHint, 1200);
                handler.postDelayed(menuSetup, 3000);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            handler.removeCallbacks(menuHint);
            handler.removeCallbacks(menuSetup);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /**
     * Zurück-Taste: erst Dialoge schließen bzw. offene Gruppe zuklappen (IPTV.handleBack der Webapp),
     * sonst zurückblättern; die App bleibt offen.
     */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        String js = "(function(){if(window.IPTV&&IPTV.handleBack)return IPTV.handleBack()?'closed':'';"
                + "var o=document.querySelector('[aria-modal=true]:not([hidden])');"
                + "if(o){(document.activeElement||document.body).dispatchEvent("
                + "new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));return 'closed';}return '';})()";
        web.evaluateJavascript(js, result -> {
            if (!"\"closed\"".equals(result) && web.canGoBack()) web.goBack();
        });
    }

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
    protected void onResume() {
        super.onResume();
        web.onResume();
        // Zurück aus dem Player: im Player geänderte Favoriten an die Webapp geben
        if (!PlayerActivity.favChanges.isEmpty()) {
            JSONObject changes = new JSONObject(PlayerActivity.favChanges);
            PlayerActivity.favChanges.clear();
            web.evaluateJavascript("window.IPTV&&IPTV.applyNativeFavs&&IPTV.applyNativeFavs(" + changes + ")", null);
        }
        // ☰ im Player 3 Sekunden gehalten: Einrichtung statt automatischem Start
        if (PlayerActivity.openSetup) {
            PlayerActivity.openSetup = false;
            PlayerActivity.lastUrl = null;
            showSetup();
            return;
        }
        // Fokus auf den zuletzt gesehenen Sender setzen
        String last = PlayerActivity.lastUrl;
        if (last != null) {
            PlayerActivity.lastUrl = null;
            web.evaluateJavascript("window.IPTV&&IPTV.nativeReturned&&IPTV.nativeReturned("
                    + JSONObject.quote(last) + ")", null);
        }
        Remote.status("overview", "", "");
        // Seniorenansicht: beim (erneuten) Öffnen der App gleich wieder den letzten Sender starten
        web.evaluateJavascript("window.IPTV&&IPTV.onAppResume&&IPTV.onAppResume()", null);
    }

    // ---------- Fernwartung (Befehle aus dem Editor) ----------

    @Override
    public void remote(String action, JSONObject arg) {
        Remote.Target player = Remote.player_target;
        switch (action) {
            case "play":
                remotePlay(arg.optString("norm"), 0);
                break;
            case "message":
                Remote.showMessage(this, arg.optString("text"));
                break;
            case "reload":
                if (player != null) player.remote("close", arg);
                web.reload();
                Remote.report("Neu geladen");
                break;
            case "update":
                Updater.remoteUpdate(this);   // still laden, dann nur „Installieren“ am Gerät
                break;
            case "view":
                if (player != null) player.remote("close", arg);
                String v = "senioren".equals(arg.optString("view")) ? "senioren" : "komplett";
                applySetup("", v);
                Remote.report("Ansicht: " + v);
                break;
            default:
                break;
        }
    }

    /** Sender/Film aus der Ferne starten; braucht den Baum der Webapp (notfalls anfordern). */
    private void remotePlay(String norm, int attempt) {
        org.json.JSONArray path = Remote.pathTo(PlayerActivity.treeRoot, norm);
        if (path == null) {
            if (attempt < 2) {
                web.evaluateJavascript("window.IPTV&&IPTV.nativeRefresh&&IPTV.nativeRefresh()", null);
                handler.postDelayed(() -> remotePlay(norm, attempt + 1), 6000);
            } else {
                Remote.report("Eintrag nicht gefunden: " + norm);
            }
            return;
        }
        try {
            String url = web.getUrl();
            String view = url != null && url.contains("senioren") ? "senioren" : "komplett";
            PlayerActivity.pendingPath = new JSONObject().put("path", path).put("view", view).toString();
            PlayerActivity.pending = null;
            startActivity(new Intent(this, PlayerActivity.class));
        } catch (Exception e) {
            Remote.report("Start fehlgeschlagen");
        }
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        if (Remote.main_target == this) Remote.main_target = null;
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }
}
