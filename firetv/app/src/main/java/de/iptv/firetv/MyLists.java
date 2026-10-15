package de.iptv.firetv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Eigene Listen (z. B. „⚽ Fussball“) am Stick bearbeiten – nur mit Freigabe (Admin ⚙️ „Darf eigene Listen
 * bearbeiten“, Prefs „ownLists“). Die Listen liegen zentral in Firebase unter der Adresse aus dem Kopf der Playlist
 * (x-mylists, die Webapp meldet sie über IPTVNative.setMyLists). Daten: {l: {id: {n, o, i: [norm…]}}}.
 * Nach einer Änderung meldet Firebase sie der Webapp, die den Baum neu schickt – die Liste steht dann oben.
 */
final class MyLists {
    static volatile String url;   // https://<db>/<schlüssel> oder null

    private MyLists() {
    }

    static boolean allowed(Context c) {
        return url != null && c.getSharedPreferences("iptv", Context.MODE_PRIVATE).getBoolean("ownLists", false);
    }

    private static final class L {
        String id, name;
        int order;
        List<String> items = new ArrayList<>();
    }

    /** Menü zu einem Sender: Lieblingssender, Listen an/aus, neue Liste. fav = bisheriges „OK lang“. */
    static void menu(Activity a, String streamUrl, String channel, Runnable fav) {
        String base = url;
        if (base == null) {
            fav.run();
            return;
        }
        new Thread(() -> {
            List<L> lists = new ArrayList<>();
            try {
                lists = read(base);
            } catch (Exception e) {
                a.runOnUiThread(() -> Toast.makeText(a, "Eigene Listen gerade nicht erreichbar", Toast.LENGTH_SHORT).show());
            }
            final List<L> ls = lists;
            a.runOnUiThread(() -> show(a, base, ls, Memory.norm(streamUrl), channel, fav));
        }, "Eigene Listen").start();
    }

    private static void show(Activity a, String base, List<L> ls, String norm, String channel, Runnable fav) {
        if (a.isFinishing()) return;
        String[] rows = new String[ls.size() + 2];
        rows[0] = "★  Lieblingssender (nur dieser Fernseher)";
        for (int i = 0; i < ls.size(); i++) {
            rows[i + 1] = (ls.get(i).items.contains(norm) ? "☑  " : "☐  ") + ls.get(i).name;
        }
        rows[rows.length - 1] = "＋  Neue Liste …";
        new AlertDialog.Builder(a)
                .setTitle(channel)
                .setItems(rows, (d, which) -> {
                    if (which == 0) {
                        fav.run();
                    } else if (which == rows.length - 1) {
                        create(a, base, ls.size() + 1, norm);
                    } else {
                        L l = ls.get(which - 1);
                        boolean add = !l.items.contains(norm);
                        if (add) l.items.add(norm);
                        else l.items.remove(norm);
                        save(a, base, l, add ? "Zu „" + l.name + "“ hinzugefügt" : "Aus „" + l.name + "“ entfernt");
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private static void create(Activity a, String base, int order, String norm) {
        EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint("z. B. Fussball");
        new AlertDialog.Builder(a)
                .setTitle("Neue Liste")
                .setView(input)
                .setPositiveButton("Anlegen", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    L l = new L();
                    l.id = "l" + Long.toString(System.currentTimeMillis(), 36);
                    l.name = name;
                    l.order = order;
                    l.items.add(norm);
                    save(a, base, l, "Liste „" + name + "“ angelegt – steht gleich oben in der Übersicht");
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private static void save(Activity a, String base, L l, String ok) {
        new Thread(() -> {
            String msg = ok;
            try {
                JSONObject o = new JSONObject().put("n", l.name).put("o", l.order).put("i", new JSONArray(l.items));
                request(base + "/l/" + l.id + ".json", "PUT", o.toString());
            } catch (Exception e) {
                msg = "Nicht gespeichert – keine Verbindung?";
            }
            final String m = msg;
            a.runOnUiThread(() -> Toast.makeText(a, m, Toast.LENGTH_LONG).show());
        }, "Eigene Listen").start();
    }

    private static List<L> read(String base) throws Exception {
        List<L> out = new ArrayList<>();
        String body = request(base + ".json", "GET", null);
        if (body == null || body.equals("null")) return out;
        JSONObject l = new JSONObject(body).optJSONObject("l");
        if (l == null) return out;
        for (Iterator<String> it = l.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject x = l.optJSONObject(id);
            if (x == null || x.optString("n").isEmpty()) continue;
            L e = new L();
            e.id = id;
            e.name = x.optString("n");
            e.order = x.optInt("o");
            JSONArray i = x.optJSONArray("i");
            if (i != null) for (int k = 0; k < i.length(); k++) e.items.add(i.optString(k));
            out.add(e);
        }
        java.util.Collections.sort(out, (p, q) -> p.order != q.order ? Integer.compare(p.order, q.order) : p.name.compareTo(q.name));
        return out;
    }

    private static String request(String u, String method, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestMethod(method);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        if (code >= 300) throw new Exception("HTTP " + code);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
