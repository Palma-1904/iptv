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
        context.getSharedPreferences("iptv", Context.MODE_PRIVATE).edit()
                .putString("pendingLink", link == null ? "" : link.trim())
                .putString("pendingView", view == null ? "" : view.trim())
                .apply();
        setResultData("vorgemerkt");
    }
}
