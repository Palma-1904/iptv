package de.iptv.firetv;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Wächter (Bedienungshilfe von Android): hält die App vorne und bestätigt eigene Updates.
 * - Home-, Einstellungs- und Alle-Apps-Taste werden abgefangen, solange „Home-Taste holt die App zurück“ an ist.
 * - Erscheint trotzdem etwas Fremdes (Fire-TV-Startseite, Alexa, andere App), kommt die App sofort zurück.
 * - Beim eigenen Update drückt er im Installer „Installieren“ (nur wenn dort „Fernsehen“ steht).
 * Einschalten nur vom Computer (ADB, Stick-einrichten.command) – Fire OS zeigt dafür keinen Schalter.
 * Mit dem dabei erteilten Recht WRITE_SECURE_SETTINGS schaltet die App ihn danach selbst ein/aus.
 * Pause (☰ 3 s oder Fernwartung): 10 Minuten lang nichts abfangen, z. B. für die Fire-TV-Einstellungen.
 * Fire OS lässt die Home-Taste nicht abfangen, und nach Home darf Android 5 Sekunden lang keine App öffnen:
 * darum deckt der Wächter den Fire-TV-Startbildschirm sofort mit „Einen Moment …“ ab, bis die App wieder da ist.
 */
public class Waechter extends AccessibilityService {

    static final long PAUSE_MS = 10 * 60 * 1000L;
    private static final String[] INSTALL_WORDS = {"Installieren", "Aktualisieren", "Install", "Update"};
    private static final String[] OPEN_WORDS = {"Öffnen", "Open", "Fertig", "Done"};   // Installer: „App installiert“

    private static volatile Waechter running;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> homeApps = new HashSet<>();
    private long lastReturn, lastClick;
    private long burstStart, giveUpUntil;
    private int burst;
    private int installTries;
    private View cover;
    /** Android meldet den Druck auf Home sofort („homekey“) – schneller als der Fire-TV-Startbildschirm erscheint. */
    private final BroadcastReceiver homeKey = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if ("homekey".equals(i.getStringExtra("reason")) && guarding()) bringBack();
        }
    };
    private final Runnable hideCover = this::hideCover;

    // ---------- Zustand (auch für Einstellungen und Fernwartung) ----------

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("iptv", Context.MODE_PRIVATE);
    }

    /** Läuft der Wächter gerade (von Android eingeschaltet und verbunden)? */
    static boolean running() {
        return running != null;
    }

    /** Fire OS 6 und älter (Android ≤ 8): Amazon startet dort keine fremden Bedienungshilfen. */
    static boolean supported() {
        return android.os.Build.VERSION.SDK_INT >= 28;
    }

    /** Soll er laufen? (Schalter in den Einstellungen bzw. per Fernwartung) */
    static boolean wanted(Context c) {
        return prefs(c).getBoolean("waechter", true);
    }

    /** Darf die App ihn selbst ein-/ausschalten? (Recht wird einmalig per ADB erteilt) */
    static boolean canSwitch(Context c) {
        return c.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Ist er in den Android-Einstellungen eingetragen? */
    static boolean systemEnabled(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        ComponentName me = new ComponentName(c, Waechter.class);
        for (String s : list.split(":")) {
            if (me.equals(ComponentName.unflattenFromString(s))) return true;
        }
        return false;
    }

    /** In den Android-Einstellungen ein-/austragen; false = Recht fehlt. */
    static boolean setSystem(Context c, boolean on) {
        if (!canSwitch(c)) return false;
        try {
            ContentResolver r = c.getContentResolver();
            String list = Settings.Secure.getString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            ComponentName me = new ComponentName(c, Waechter.class);
            StringBuilder out = new StringBuilder();
            if (list != null) {
                for (String s : list.split(":")) {
                    if (s.isEmpty() || me.equals(ComponentName.unflattenFromString(s))) continue;
                    if (out.length() > 0) out.append(':');
                    out.append(s);
                }
            }
            if (on) {
                if (out.length() > 0) out.append(':');
                out.append(me.flattenToString());
            }
            Settings.Secure.putString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, out.toString());
            if (on) Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Schalter umlegen (merken und in Android ein-/austragen); false = Recht fehlt. */
    static boolean set(Context c, boolean on) {
        prefs(c).edit().putBoolean("waechter", on).apply();
        return setSystem(c, on);
    }

    /** Beim Start der App: wieder einschalten, falls Fire OS ihn abgeschaltet hat (z. B. nach „Beenden erzwingen“). */
    static void ensure(Context c) {
        if (canSwitch(c) && wanted(c) != systemEnabled(c)) setSystem(c, wanted(c));
    }

    /** Pause (0 = Pause beenden). */
    static void pause(Context c, long ms) {
        prefs(c).edit().putLong("waechterPause", ms > 0 ? System.currentTimeMillis() + ms : 0).apply();
    }

    /** Verbleibende Pause in ms (0 = nicht pausiert). */
    static long pausedFor(Context c) {
        return Math.max(0, prefs(c).getLong("waechterPause", 0) - System.currentTimeMillis());
    }

    /** Eigenes Update kommt: die nächsten 10 Minuten im Installer „Installieren“ drücken. */
    static void expectInstall(Context c) {
        // 2 Stunden: kommt das Update im Standby, ist der Knopf gesperrt, bis der Fernseher wieder an ist
        prefs(c).edit().putLong("autoInstallUntil", System.currentTimeMillis() + 2 * 3600 * 1000L).apply();
        Waechter w = running;
        if (w != null) w.handler.post(w.watchInstall);
    }

    /** Solange ein Update ansteht: alle 2 s nachsehen, ob „Installieren“ gedrückt werden kann
     *  (nicht nur bei Fensterwechseln – der Knopf wird oft erst später freigegeben). */
    private final Runnable watchInstall = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);
            if (System.currentTimeMillis() >= prefs(Waechter.this).getLong("autoInstallUntil", 0)) return;
            confirmInstall();
            handler.postDelayed(this, 2000);
        }
    };

    /** Nach dem eigenen Update (UpdateReceiver): die nächsten 3 Minuten im Installer „Öffnen“ drücken. */
    static void installDone(Context c) {
        prefs(c).edit().remove("autoInstallUntil").putLong("updatedAt", System.currentTimeMillis()).apply();
    }

    private boolean justUpdated() {
        return System.currentTimeMillis() - prefs(this).getLong("updatedAt", 0) < 3 * 60 * 1000L;
    }

    /** Soll die App gerade vorne gehalten werden? */
    private boolean guarding() {
        return AutostartService.homeReturns(this) && pausedFor(this) == 0 && !AutostartService.suppressed()
                && SystemClock.elapsedRealtime() >= giveUpUntil;
    }

    // ---------- Dienst ----------

    @Override
    protected void onServiceConnected() {
        running = this;
        homeApps.clear();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo r : getPackageManager().queryIntentActivities(home, 0)) {
                homeApps.add(r.activityInfo.packageName);
            }
        } catch (Exception ignored) {
            // dann nur über die Fensterklasse erkennen
        }
        try {
            registerReceiver(homeKey, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        } catch (Exception ignored) {
            // dann über den Fensterwechsel
        }
        handler.post(watchInstall);   // ansteh. Update (z. B. nach Neustart des Wächters) weiter beobachten
        // Nach einem Update steht oft noch „App installiert – Fertig / Öffnen“ da: gleich „Öffnen“ drücken
        if (justUpdated()) {
            handler.postDelayed(this::confirmOpen, 1500);
            handler.postDelayed(this::confirmOpen, 4000);
        }
        // Gleich nach dem Hochfahren die App öffnen (die Startmeldung kommt bei Fire OS 8 manchmal spät)
        if (SystemClock.elapsedRealtime() < 3 * 60 * 1000L) AutostartService.openApp(this);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = null;
        handler.removeCallbacksAndMessages(null);
        hideCover();
        try {
            unregisterReceiver(homeKey);
        } catch (Exception ignored) {
            // war nicht angemeldet
        }
        super.onDestroy();
    }

    /** Ein Bildschirm der App ist wieder vorne: Abdeckung kurz danach entfernen. */
    static void appShown() {
        Waechter w = running;
        if (w == null) return;
        w.handler.post(() -> {
            if (w.cover == null) return;
            w.handler.removeCallbacks(w.hideCover);
            w.handler.postDelayed(w.hideCover, 400);   // erst wenn die App gezeichnet ist
        });
    }

    /** Schwarzes Bild mit Logo über alles legen (Bedienungshilfen dürfen das ohne weitere Erlaubnis). */
    private void showCover() {
        handler.removeCallbacks(hideCover);
        handler.postDelayed(hideCover, 8000);   // spätestens dann weg, falls die App nicht kommt
        if (cover != null) return;
        try {
            float dp = getResources().getDisplayMetrics().density;
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setBackgroundColor(Color.BLACK);
            ImageView logo = new ImageView(this);
            logo.setImageResource(R.mipmap.icon);
            box.addView(logo, new LinearLayout.LayoutParams((int) (140 * dp), (int) (140 * dp)));
            TextView text = new TextView(this);
            text.setText("Einen Moment …");
            text.setTextColor(Color.WHITE);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
            text.setPadding(0, (int) (24 * dp), 0, 0);
            text.setGravity(Gravity.CENTER);
            box.addView(text, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    PixelFormat.OPAQUE);
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(box, lp);   // nimmt auch die Tasten weg
            cover = box;
        } catch (Exception ignored) {
            cover = null;
        }
    }

    private void hideCover() {
        handler.removeCallbacks(hideCover);
        if (cover == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(cover);
        } catch (Exception ignored) {
            // schon weg
        }
        cover = null;
    }

    @Override
    public void onInterrupt() {
        // nichts vorzulesen
    }

    /** Home-, Einstellungs- und Alle-Apps-Taste: gar nicht erst zum Fire-TV-Startbildschirm. */
    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k != KeyEvent.KEYCODE_HOME && k != KeyEvent.KEYCODE_SETTINGS && k != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (!guarding()) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && !AutostartService.isVisible()) bringBack();
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        CharSequence p = e.getPackageName();
        if (p == null) return;
        String pkg = p.toString();
        if (pkg.equals(getPackageName())) return;
        if (System.currentTimeMillis() < prefs(this).getLong("autoInstallUntil", 0)) {
            installTries = 0;
            confirmInstall();
        } else if (justUpdated()) {
            confirmOpen();
        }
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (tolerated(pkg) || !isScreen(pkg, e.getClassName())) return;   // Dialoge, Tastatur, Hinweise: egal
        if (guarding()) bringBack();
    }

    /** System-Abfragen, die nicht verdrängt werden dürfen (ADB-Zulassen, Installer, Erlaubnisse, VPN). */
    private static boolean tolerated(String pkg) {
        return pkg.equals("android") || pkg.equals("com.android.systemui") || pkg.contains("installer")
                || pkg.contains("permissioncontroller") || pkg.equals("com.android.vpndialogs");
    }

    /** Ganzer Bildschirm einer anderen App (Activity) oder der Fire-TV-Startbildschirm? */
    private boolean isScreen(String pkg, CharSequence cls) {
        if (homeApps.contains(pkg)) return true;
        if (cls == null) return false;
        try {
            return getPackageManager().getActivityInfo(new ComponentName(pkg, cls.toString()), 0) != null;
        } catch (Exception ex) {
            return false;
        }
    }

    /** App nach vorne holen; bei Dauer-Hin-und-Her (fremde App drängt sich immer wieder vor) 5 Minuten aufgeben. */
    private void bringBack() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastReturn < 800) return;
        lastReturn = now;
        if (now - burstStart > 60000) {
            burstStart = now;
            burst = 0;
        }
        if (++burst > 10) {
            giveUpUntil = now + 5 * 60 * 1000L;
            Remote.report("Wächter: etwas drängt sich ständig vor – 5 Minuten Pause");
            return;
        }
        Intent start = new Intent(this, MainActivity.class);
        start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(start);   // Android führt das nach der Home-Taste erst nach bis zu 5 s aus
            showCover();
        } catch (Exception ignored) {
            // sollte mit verbundenem Wächter nicht vorkommen
        }
    }

    /** Installer nach dem eigenen Update: „Öffnen“ (sonst „Fertig“) drücken – nur bei unserer App. */
    private void confirmOpen() {
        if (SystemClock.elapsedRealtime() - lastClick < 2000) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return;
        if (!root.getPackageName().toString().contains("installer")) return;
        if (root.findAccessibilityNodeInfosByText(getString(R.string.app_name)).isEmpty()) return;
        for (String word : OPEN_WORDS) {
            for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(word)) {
                CharSequence cls = n.getClassName();
                if (cls == null || !cls.toString().contains("Button") || !n.isClickable() || !n.isEnabled()) continue;
                lastClick = SystemClock.elapsedRealtime();
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                prefs(this).edit().remove("updatedAt").apply();
                if (!word.startsWith("Ö") && !word.equals("Open")) bringBack();   // „Fertig“: App selbst holen
                return;
            }
        }
    }

    /** Im Installer „Installieren“ drücken – nur bei unserer App (Name „Fernsehen“ im Fenster). */
    private void confirmInstall() {
        if (SystemClock.elapsedRealtime() - lastClick < 3000) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return;
        if (!root.getPackageName().toString().contains("installer")) return;
        if (root.findAccessibilityNodeInfosByText(getString(R.string.app_name)).isEmpty()) return;
        for (String word : INSTALL_WORDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(word);
            for (AccessibilityNodeInfo n : nodes) {
                CharSequence cls = n.getClassName();
                if (cls == null || !cls.toString().contains("Button") || !n.isClickable()) continue;
                if (!n.isEnabled()) {
                    // Knopf wird erst nach einem Moment freigegeben: gleich noch einmal versuchen
                    if (installTries++ < 15) handler.postDelayed(this::confirmInstall, 700);
                    return;
                }
                lastClick = SystemClock.elapsedRealtime();
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Remote.report("Wächter: „Installieren“ gedrückt");
                return;
            }
        }
    }
}
