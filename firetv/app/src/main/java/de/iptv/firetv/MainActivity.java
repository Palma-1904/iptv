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
public class MainActivity extends Activity {

    private static final String VLC = "org.videolan.vlc";
    private static final String PREF_CONFIGURED = "configured";

    private WebView web;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("iptv", MODE_PRIVATE);

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

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Einrichtung")
                .setMessage("Einrichtungs-Link aus dem Playlist-Editor eingeben und Ansicht wählen.\n\n"
                        + "Tipp: Mit der Amazon-Fire-TV-App auf dem Handy kann man bequem tippen oder einfügen.\n"
                        + "Später erneut: Menü-Taste (☰) auf der Fernbedienung.")
                .setView(input)
                .setPositiveButton("Komplett", (d, w) -> applySetup(input.getText().toString(), "komplett"))
                .setNegativeButton("Senioren", (d, w) -> applySetup(input.getText().toString(), "senioren"))
                .setNeutralButton("Abbrechen", (d, w) -> {
                    if (web.getUrl() == null) load(null);
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showSetup();
            return true;
        }
        return super.onKeyDown(keyCode, event);
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
        // Fokus auf den zuletzt gesehenen Sender setzen
        String last = PlayerActivity.lastUrl;
        if (last != null) {
            PlayerActivity.lastUrl = null;
            web.evaluateJavascript("window.IPTV&&IPTV.nativeReturned&&IPTV.nativeReturned("
                    + JSONObject.quote(last) + ")", null);
        }
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }
}
