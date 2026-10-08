package de.iptv.firetv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Einrichtung vom Computer (Stick-einrichten.command): Einrichtungs-Link und Ansicht vormerken,
 * die App übernimmt sie beim nächsten Öffnen. Senden darf nur ADB (Absender braucht WRITE_SECURE_SETTINGS).
 */
public class SetupReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
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
        e.apply();
        String w = intent.getStringExtra("waechter");
        if (w != null) Waechter.set(context, "1".equals(w));
        if ("0".equals(auto)) context.stopService(new Intent(context, AutostartService.class));
        setResultData("vorgemerkt");
    }
}
