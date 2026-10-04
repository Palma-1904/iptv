package de.iptv.firetv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Nach dem Hochfahren: Autostart-Dienst starten (für das Aufwachen aus dem Standby) und die App öffnen. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        AutostartService.start(context);
        AutostartService.openApp(context);
    }
}
