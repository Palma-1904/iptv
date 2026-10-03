package de.iptv.firetv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lädt Senderlogos im Hintergrund, verkleinert sie und hält sie im Speicher. */
class ImageLoader {

    private static final int MAX_SIZE = 256;   // Logos werden höchstens so groß gebraucht

    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Set<String> failed = new HashSet<>();

    void load(String url, ImageView view) {
        view.setTag(url);
        if (url == null || url.isEmpty() || failed.contains(url)) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap hit = cache.get(url);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageDrawable(null);
        pool.execute(() -> {
            Bitmap bmp = fetch(url);
            main.post(() -> {
                if (bmp == null) failed.add(url);
                else cache.put(url, bmp);
                if (url.equals(view.getTag())) view.setImageBitmap(bmp);
            });
        });
    }

    void shutdown() {
        pool.shutdownNow();
    }

    private static Bitmap fetch(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;
            byte[] data;
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 4 * 1024 * 1024) return null;
                }
                data = out.toByteArray();
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= MAX_SIZE || o.outHeight / (sample * 2) >= MAX_SIZE) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
