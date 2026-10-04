package de.iptv.firetv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Nach einem Update über die App (Fernwartung oder Start-Abfrage) die App gleich wieder öffnen. */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        SharedPreferences p = context.getSharedPreferences("iptv", Context.MODE_PRIVATE);
        if (!p.getBoolean("restartAfterUpdate", false)) return;
        p.edit().putBoolean("restartAfterUpdate", false).apply();
        Intent start = new Intent(context, MainActivity.class);
        start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(start);
        } catch (Exception ignored) {
            // Fire OS blockiert den Start: dann „Öffnen“ im Installer
        }
    }
}
