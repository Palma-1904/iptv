package de.iptv.firetv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Autostart: „Stick einschalten“ ist meist nur Aufwachen aus dem Standby (kein Hochfahren).
 * Dieser kleine Dienst bemerkt das (Bildschirm an) und öffnet die App. Ab Fire OS 8 braucht die
 * App dafür einmalig die Erlaubnis „Über anderen Apps einblenden“ (Knopf in der Einrichtung).
 */
public class AutostartService extends Service {

    private static final String CHANNEL = "autostart";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver screenOn;

    static boolean enabled(Context c) {
        return c.getSharedPreferences("iptv", Context.MODE_PRIVATE).getBoolean("autostart", true);
    }

    static void start(Context c) {
        if (!enabled(c)) return;
        Intent i = new Intent(c, AutostartService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) {
            // z. B. vom System abgelehnt – dann nur Start nach dem Hochfahren
        }
    }

    /** Home-Taste: kommt die App gleich wieder nach vorne? Standard: an in der Seniorenansicht. */
    static boolean homeReturns(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("iptv", Context.MODE_PRIVATE);
        if (p.contains("homeReturns")) return p.getBoolean("homeReturns", false);
        return "senioren".equals(p.getString("view", ""));
    }

    private static final Handler bounce = new Handler(Looper.getMainLooper());
    private static int visible;               // sichtbare eigene Bildschirme (MainActivity, Player)
    private static long suppressUntil;        // z. B. Installieren-Fenster, Einstellungen, VLC

    static void shown() {
        visible++;
        Waechter.appShown();
    }

    static void hidden() { if (visible > 0) visible--; }

    /** Ist gerade ein Bildschirm der App vorne? */
    static boolean isVisible() { return visible > 0; }

    /** Hat die App gerade selbst etwas Fremdes geöffnet (Installer, Einstellungen, VLC)? */
    static boolean suppressed() { return System.currentTimeMillis() < suppressUntil; }

    /** Die App öffnet selbst etwas Fremdes (Installer, Einstellungen, VLC): nicht zurückholen. */
    static void suppress(long ms) {
        suppressUntil = System.currentTimeMillis() + ms;
        bounce.removeCallbacksAndMessages(null);
    }

    /** Nach der Home-Taste die App kurz darauf wieder öffnen (Home selbst kann keine App sperren). */
    static void bounceBack(Context c) {
        if (!homeReturns(c)) return;
        Context app = c.getApplicationContext();
        bounce.removeCallbacksAndMessages(null);
        bounce.postDelayed(() -> {
            // App ist noch/wieder vorne (z. B. nur Wechsel Übersicht -> Player) oder bewusst ausgeblendet
            if (visible > 0 || System.currentTimeMillis() < suppressUntil) return;
            Intent start = new Intent(app, MainActivity.class);
            start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                app.startActivity(start);
            } catch (Exception ignored) {
                // Erlaubnis „Über anderen Apps einblenden“ fehlt
            }
        }, 1500);
    }

    /** App nach vorne holen (Seniorenansicht startet dann gleich den letzten Sender). */
    static void openApp(Context c) {
        if (!enabled(c)) return;
        Intent start = new Intent(c, MainActivity.class);
        start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(start);
        } catch (Exception ignored) {
            // Fire OS blockiert den Start (Erlaubnis fehlt)
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Autostart", NotificationManager.IMPORTANCE_MIN));
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setContentTitle("Fernsehen startet beim Einschalten")
                    .setSmallIcon(R.mipmap.icon)
                    .build();
            startForeground(1, n);
        }
        screenOn = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // kurz warten, bis der Fire-TV-Startbildschirm da ist, dann darüber öffnen
                handler.postDelayed(() -> openApp(getApplicationContext()), 2500);
            }
        };
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        Remote.init(this);   // Fernwartung hört auch zu, wenn die App geschlossen ist („Einschalten“)
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!enabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (screenOn != null) unregisterReceiver(screenOn);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
