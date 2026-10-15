package de.iptv.firetv;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Letzte Senderliste (Baum der Webapp) auf dem Gerät: beim Start sofort laden und den letzten Sender spielen,
 * statt ~8 s auf die Webapp zu warten. Die Webapp schickt danach nur dann eine neue, wenn sich ihr Fingerabdruck
 * (Version) geändert hat.
 */
final class TreeCache {

    private TreeCache() {
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "tree.json");
    }

    /** Im Hintergrund speichern (Version in der ersten Zeile). */
    static void save(Context c, String version, String json) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            File tmp = new File(app.getFilesDir(), "tree.json.tmp");
            try (OutputStream o = new FileOutputStream(tmp)) {
                o.write((version.replace('\n', ' ') + "\n").getBytes(StandardCharsets.UTF_8));
                o.write(json.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                return;
            }
            if (!tmp.renameTo(file(app))) tmp.delete();
        }, "Senderliste speichern").start();
    }

    /** Gespeicherte Liste einlesen (Hintergrund-Thread!); setzt treeRoot/treeVersion, wenn noch keiner da ist. */
    static boolean load(Context c) {
        File f = file(c);
        if (!f.exists() || PlayerActivity.treeRoot != null) return false;
        long t0 = Zeit.now();
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            String s = new String(b, 0, n, StandardCharsets.UTF_8);
            int nl = s.indexOf('\n');
            if (nl < 0) return false;
            PlayerActivity.Node root = PlayerActivity.parseTree(new JSONObject(s.substring(nl + 1)));
            synchronized (PlayerActivity.class) {
                if (PlayerActivity.treeRoot != null) return false;   // Webapp war schneller
                PlayerActivity.treeRoot = root;
                PlayerActivity.treeVersion = s.substring(0, nl);
            }
            Zeit.log("Senderliste aus dem Speicher in " + (Zeit.now() - t0) + "ms");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void clear(Context c) {
        file(c).delete();
    }
}
