package de.iptv.firetv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Kleines Livebild für die Admin-Fernbedienung: die App „fotografiert“ ihren eigenen Bildschirm
 * (Fernsehbild per PixelCopy aus der SurfaceView von VLC, darüber Liste/Infos) als JPEG, ~480 px breit.
 * Nur solange das Handy es anfordert (Fernwartung „shot“, gilt 60 s), etwa alle 1,5 s und gleich nach einer Taste.
 * Dialoge (eigene Fenster) sind nicht mit drauf.
 */
final class LiveShot {

    private static final int WIDTH = 480;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Object lock = new Object();
    private static volatile long until;
    private static boolean started;

    private LiveShot() {
    }

    /** Handy fordert an (on) oder beendet; startet beim ersten Mal den Hintergrund-Thread. */
    static synchronized void request(boolean on, Uploader up) {
        until = on ? System.currentTimeMillis() + 60000 : 0;
        if (on && !started) {
            started = true;
            Thread t = new Thread(() -> loop(up), "Livebild");
            t.setDaemon(true);
            t.start();
        }
        kick();
    }

    /** Gleich ein neues Bild (z. B. nach einem Tastendruck). */
    static void kick() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    interface Uploader {
        void upload(String jpegBase64, int w, int h) throws Exception;
    }

    private static void loop(Uploader up) {
        while (true) {
            try {
                if (System.currentTimeMillis() > until) {
                    synchronized (lock) {
                        lock.wait(30000);
                    }
                    continue;
                }
                final Bitmap[] out = new Bitmap[1];
                final CountDownLatch done = new CountDownLatch(1);
                main.post(() -> capture(bmp -> {
                    out[0] = bmp;
                    done.countDown();
                }));
                done.await(3, TimeUnit.SECONDS);
                Bitmap b = out[0];
                if (b != null) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    b.compress(Bitmap.CompressFormat.JPEG, 55, bos);
                    up.upload(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP), b.getWidth(), b.getHeight());
                    b.recycle();
                }
                synchronized (lock) {
                    lock.wait(1500);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) {
                // offline o. ä.: nächster Versuch
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    interface Done {
        void bitmap(Bitmap b);
    }

    /** Im Bild-Thread: sichtbaren App-Bildschirm aufnehmen (null, wenn keiner vorne ist). */
    private static void capture(Done cb) {
        Activity a = Reminders.front;
        if (a == null || a.isFinishing()) {
            cb.bitmap(null);
            return;
        }
        try {
            View decor = a.getWindow().getDecorView();
            int w = decor.getWidth(), h = decor.getHeight();
            if (w <= 0 || h <= 0) {
                cb.bitmap(null);
                return;
            }
            final float scale = (float) WIDTH / w;
            final Bitmap out = Bitmap.createBitmap(WIDTH, Math.max(1, Math.round(h * scale)), Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(out);
            c.drawColor(Color.BLACK);
            final SurfaceView sv = Build.VERSION.SDK_INT >= 24 ? findSurface(decor) : null;
            if (sv != null && sv.getWidth() > 0 && sv.getHolder().getSurface().isValid()) {
                final Bitmap vid = Bitmap.createBitmap(Math.max(1, Math.round(sv.getWidth() * scale)),
                        Math.max(1, Math.round(sv.getHeight() * scale)), Bitmap.Config.ARGB_8888);
                final int[] loc = new int[2];
                sv.getLocationInWindow(loc);
                PixelCopy.request(sv, vid, result -> {
                    if (result == PixelCopy.SUCCESS) c.drawBitmap(vid, loc[0] * scale, loc[1] * scale, null);
                    vid.recycle();
                    ui(a, c, scale, () -> cb.bitmap(out));
                }, main);
            } else {
                ui(a, c, scale, () -> cb.bitmap(out));
            }
        } catch (Throwable e) {
            cb.bitmap(null);
        }
    }

    /**
     * Oberfläche darüber: ab Android 8 das Fenster selbst abfotografieren (PixelCopy; Senderlogos sind dort
     * „Hardware-Bilder“, die sich nicht nachzeichnen lassen) – im Bereich des Videos ist das Fenster durchsichtig.
     * Älter (Fire OS 6): nachzeichnen.
     */
    private static void ui(Activity a, Canvas c, float scale, Runnable done) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Bitmap win = Bitmap.createBitmap(c.getWidth(), c.getHeight(), Bitmap.Config.ARGB_8888);
                PixelCopy.request(a.getWindow(), win, result -> {
                    if (result == PixelCopy.SUCCESS) c.drawBitmap(win, 0, 0, null);
                    win.recycle();
                    done.run();
                }, main);
                return;
            } catch (Throwable ignored) {
                // dann nachzeichnen
            }
        }
        try {
            drawUi(a, c, scale);
        } catch (Throwable ignored) {
            // nur das Video
        }
        done.run();
    }

    /** Oberfläche (Liste, Infos, Webapp) darüber zeichnen – ohne den schwarzen Hintergrund, der das Bild verdecken würde. */
    private static void drawUi(Activity a, Canvas c, float scale) {
        View content = a.findViewById(android.R.id.content);
        if (content == null) return;
        View root = content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0
                ? ((ViewGroup) content).getChildAt(0) : content;
        Drawable bg = root.getBackground();
        int[] loc = new int[2];
        content.getLocationInWindow(loc);
        // eigene durchsichtige Ebene: die SurfaceView „stanzt“ beim Zeichnen ihren Bereich aus (würde das Video löschen)
        Bitmap ui = Bitmap.createBitmap(c.getWidth(), c.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas uc = new Canvas(ui);
        uc.scale(scale, scale);
        uc.translate(loc[0], loc[1]);
        try {
            root.setBackground(null);
            content.draw(uc);
        } finally {
            root.setBackground(bg);
        }
        c.drawBitmap(ui, 0, 0, null);
        ui.recycle();
    }

    private static SurfaceView findSurface(View v) {
        if (v instanceof SurfaceView && v.getVisibility() == View.VISIBLE) return (SurfaceView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                SurfaceView s = findSurface(g.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
    }
}
