package de.iptv.firetv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

/**
 * Einrichtung vom Computer (Stick-einrichten.command): Einrichtungs-Link und Ansicht vormerken,
 * die App übernimmt sie beim nächsten Öffnen. Senden darf nur ADB (Absender braucht WRITE_SECURE_SETTINGS).
 * Optional (ab App 73): normvol, ownlists ("1"/"0"), schedOff/schedOn ("HH:MM" oder ""), sleepH (Stunden).
 * „report“ = "1": nichts ändern, sondern Stand als JSON zurückgeben (Abschlussbericht des Skripts).
 */
public class SetupReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if ("1".equals(intent.getStringExtra("report"))) {
            setResultData(report(context));
            return;
        }
        String link = intent.getStringExtra("link");
        String view = intent.getStringExtra("ansicht");
        android.content.SharedPreferences.Editor e = context.getSharedPreferences("iptv", Context.MODE_PRIVATE).edit();
        if (link != null || view != null) {
            e.putString("pendingLink", link == null ? "" : link.trim())
                    .putString("pendingView", view == null ? "" : view.trim());
        }
        // Optional: Autostart / Home-Rückkehr / Wächter an ("1") oder aus ("0")
        String auto = intent.getStringExtra("autostart");
        if (auto != null) e.putBoolean("autostart", "1".equals(auto));
        String home = intent.getStringExtra("home");
        if (home != null) e.putBoolean("homeReturns", "1".equals(home));
        String norm = intent.getStringExtra("normvol");
        if (norm != null) e.putBoolean("normvol", "1".equals(norm));
        String own = intent.getStringExtra("ownlists");
        if (own != null) e.putBoolean("ownLists", "1".equals(own));
        String kompakt = intent.getStringExtra("kompakt");            // Fassung: 1 = kompakt (nur Fernsehen)
        if (kompakt != null) e.putBoolean("kompakt", "1".equals(kompakt));
        String kself = intent.getStringExtra("kompaktSelbst");        // 1 = darf selbst umschalten
        if (kself != null) e.putBoolean("kompaktSelbst", "1".equals(kself));
        e.apply();
        // Zeitplan und Schlaf-Timer wie aus der Admin-Fernbedienung
        if (intent.hasExtra("schedOff") || intent.hasExtra("schedOn") || intent.hasExtra("sleepH")) {
            try {
                JSONObject s = new JSONObject();
                if (intent.hasExtra("schedOff")) s.put("off", intent.getStringExtra("schedOff"));
                if (intent.hasExtra("schedOn")) s.put("on", intent.getStringExtra("schedOn"));
                if (intent.hasExtra("sleepH")) s.put("sleepH", Integer.parseInt(intent.getStringExtra("sleepH")));
                Schedule.set(context, s);
            } catch (Exception ignored) {
                // ungültige Angabe: so lassen
            }
        }
        String w = intent.getStringExtra("waechter");
        if (w != null) Waechter.set(context, "1".equals(w));
        if ("0".equals(auto)) context.stopService(new Intent(context, AutostartService.class));
        setResultData("vorgemerkt");
    }

    /** Stand für den Abschlussbericht: Version, Erlaubnisse, Wächter, Einstellungen, Handy-Link. */
    private static String report(Context c) {
        try {
            Remote.init(c);
            android.content.SharedPreferences p = c.getSharedPreferences("iptv", Context.MODE_PRIVATE);
            String phone = Remote.phoneLink();
            return new JSONObject()
                    .put("ver", BuildConfig.VERSION_CODE)
                    .put("overlay", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c))
                    .put("install", Updater.installAllowed(c))
                    .put("waechter", Waechter.running())
                    .put("autostart", AutostartService.enabled(c))
                    .put("home", AutostartService.homeReturns(c))
                    .put("normvol", p.getBoolean("normvol", true))
                    .put("ownlists", p.getBoolean("ownLists", false))
                    .put("kompakt", p.getBoolean("kompakt", false))
                    .put("sched", Schedule.json(c))
                    .put("phone", phone == null ? "" : phone)
                    .toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
