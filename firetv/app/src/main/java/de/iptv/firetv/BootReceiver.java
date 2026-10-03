package de.iptv.firetv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Startet die App nach dem Einschalten des Sticks, damit man direkt beim Fernsehen landet. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Intent start = new Intent(context, MainActivity.class);
        start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(start);
    }
}
