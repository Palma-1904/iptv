package de.iptv.firetv;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Base64;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * „Selbst-ADB“: Updates ohne „Installieren“-Klick – auch ohne Wächter (z. B. Fire OS 6).
 * Die App verbindet sich mit dem ADB-Debugging ihres eigenen Sticks (127.0.0.1:5555, wie der Mac per WLAN)
 * und installiert die neue Version wie „adb install“ (cmd package install, Daten gestreamt).
 * Einmalig erscheint am Fernseher „Debugging zulassen?“ → Haken „Immer zulassen“ + OK; danach nie wieder.
 * Voraussetzung: ADB-Debugging bleibt am Stick an. Eigener Schlüssel der App (RSA 2048, in den Einstellungen).
 * Benutzt wird die Verbindung nur für die Installation der eigenen App-Updates.
 */
final class SelfAdb {

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAX_DATA = 4096;
    private static final int TOKEN = 1, SIGNATURE = 2, RSAPUBLICKEY = 3;
    // DigestInfo für SHA-1: ADB signiert das 20-Byte-Token, als wäre es ein SHA-1-Wert
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
            0x05, 0x00, 0x04, 0x14};

    private SelfAdb() {
    }

    /** ADB-Debugging am Stick an? */
    static boolean adbEnabled(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /** Schon einmal erfolgreich verbunden (Schlüssel am Stick zugelassen) und Debugging noch an? */
    static boolean ready(Context c) {
        return adbEnabled(c) && prefs(c).getBoolean("selfAdbOk", false);
    }

    /** Für Fernwartung/Einstellungen: off (Debugging aus), new (noch nie verbunden), ok, fail (zuletzt gescheitert) */
    static String state(Context c) {
        if (!adbEnabled(c)) return "off";
        SharedPreferences p = prefs(c);
        if (p.getBoolean("selfAdbOk", false)) return "ok";
        return p.contains("selfAdbKey") ? "fail" : "new";
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("iptv", Context.MODE_PRIVATE);
    }

    /**
     * Verbinden und kurz testen (Hintergrund-Thread!). Beim ersten Mal fragt der Stick am Fernseher
     * „Debugging zulassen?“ – bis 90 s warten. Liefert eine Meldung für Bildschirm/Fernwartung.
     */
    static String setup(Context c) {
        if (!adbEnabled(c)) return "ADB-Debugging ist am Stick aus (Einstellungen → Mein Fire TV → Entwickleroptionen).";
        try (Conn k = connect(c, 90000)) {
            String out = k.run("shell:echo ok", null).trim();
            prefs(c).edit().putBoolean("selfAdbOk", out.endsWith("ok")).apply();
            return out.endsWith("ok") ? "Updates ohne Klick sind eingerichtet ✓" : "Unerwartete Antwort: " + out;
        } catch (Exception e) {
            prefs(c).edit().putBoolean("selfAdbOk", false).apply();
            return "Nicht verbunden: " + why(e);
        }
    }

    /**
     * APK installieren (Hintergrund-Thread). Bei Erfolg beendet Android diese App sofort (neue Version);
     * UpdateReceiver öffnet sie wieder. Liefert nur bei Fehlern zurück: Fehlermeldung.
     */
    static String install(Context c, File apk) {
        try (Conn k = connect(c, 15000)) {
            String out = k.run("exec:cmd package install -r -S " + apk.length(), apk).trim();
            if (out.contains("Success")) return null;
            return "Installation abgelehnt: " + out;
        } catch (Exception e) {
            String m = why(e);
            if (m.contains("zugelassen")) prefs(c).edit().putBoolean("selfAdbOk", false).apply();
            return "ADB: " + m;
        }
    }

    /** Verständlicher Grund. Fire OS lässt nur EINE ADB-Verbindung zu: ist ein Computer verbunden, wird abgewiesen. */
    private static String why(Exception e) {
        if (e instanceof java.io.EOFException || e instanceof java.net.SocketException && !(e instanceof java.net.ConnectException)) {
            return "der Stick hat abgewiesen – ist gerade ein Computer per ADB verbunden? (Fire OS erlaubt nur eine Verbindung)";
        }
        if (e instanceof java.net.ConnectException) return "ADB-Debugging antwortet nicht (aus?)";
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    // ---------- Verbindung (ADB-Protokoll) ----------

    private static final class Conn implements AutoCloseable {
        final Socket s;
        final DataInputStream in;
        final OutputStream out;
        int maxData = MAX_DATA;
        int nextId = 1;
        final int[] head = new int[4];
        byte[] data;

        Conn(Socket s) throws IOException {
            this.s = s;
            in = new DataInputStream(s.getInputStream());
            out = s.getOutputStream();
        }

        void send(int cmd, int a0, int a1, byte[] data) throws IOException {
            int len = data == null ? 0 : data.length;
            ByteBuffer b = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
            int sum = 0;
            for (int i = 0; i < len; i++) sum += data[i] & 0xff;
            b.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt(sum).putInt(~cmd);
            if (len > 0) b.put(data);
            out.write(b.array());
            out.flush();
        }

        /** Nächste Nachricht lesen: head = {cmd, arg0, arg1, len}, Inhalt in data. */
        void read() throws IOException {
            byte[] h = new byte[24];
            in.readFully(h);
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            head[0] = b.getInt();
            head[1] = b.getInt();
            head[2] = b.getInt();
            head[3] = b.getInt();
            if (head[3] < 0 || head[3] > 1024 * 1024) throw new IOException("ungültige Antwort");
            data = new byte[head[3]];
            in.readFully(data);
        }

        /** Dienst öffnen (shell:/exec:), optional Datei hineinschreiben, Ausgabe zurück. */
        String run(String service, File input) throws IOException {
            int local = nextId++;
            send(OPEN, local, 0, (service + "\0").getBytes(StandardCharsets.UTF_8));
            int remote = 0;
            StringBuilder sb = new StringBuilder();
            InputStream file = input == null ? null : new FileInputStream(input);
            byte[] buf = new byte[Math.max(1024, maxData)];
            try {
                while (true) {
                    read();
                    int cmd = head[0];
                    boolean next = false;
                    if (cmd == OKAY) {
                        remote = head[1];
                        next = file != null;      // nächstes Stück erst nach OKAY der Gegenseite
                    } else if (cmd == WRTE) {
                        sb.append(new String(data, StandardCharsets.UTF_8));
                        send(OKAY, local, head[1], null);
                    } else if (cmd == CLSE) {
                        return sb.toString();
                    }
                    if (next) {
                        int n = file.read(buf);
                        if (n > 0) {
                            send(WRTE, local, remote, java.util.Arrays.copyOf(buf, n));
                        } else {
                            file.close();
                            file = null;
                        }
                    }
                }
            } finally {
                if (file != null) file.close();
            }
        }

        @Override
        public void close() {
            try {
                s.close();
            } catch (IOException ignored) {
                // egal
            }
        }
    }

    private static Conn connect(Context c, int authWaitMs) throws Exception {
        KeyPair kp = keys(c);
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", 5555), 5000);
        s.setSoTimeout(20000);
        Conn k = new Conn(s);
        try {
            k.send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean signed = false;
            while (true) {
                k.read();
                if (k.head[0] == CNXN) {
                    k.maxData = Math.min(Math.max(k.head[2], 1024), 256 * 1024);
                    s.setSoTimeout(120000);     // Installieren dauert etwas
                    return k;
                }
                if (k.head[0] != AUTH || k.head[1] != TOKEN) throw new IOException("unerwartete Antwort");
                if (!signed) {
                    k.send(AUTH, SIGNATURE, 0, sign(kp.getPrivate(), k.data));
                    signed = true;
                } else {
                    // Schlüssel unbekannt: öffentlichen Schlüssel schicken → Frage am Fernseher
                    if (authWaitMs <= 20000) throw new IOException("Schlüssel am Stick nicht zugelassen");
                    k.send(AUTH, RSAPUBLICKEY, 0, publicKey(kp));
                    s.setSoTimeout(authWaitMs);
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            k.close();
            throw new IOException("am Fernseher nicht zugelassen (Frage „Debugging zulassen?“ mit „Immer zulassen“ bestätigen)");
        } catch (Exception e) {
            k.close();
            throw e;
        }
    }

    // ---------- Schlüssel ----------

    private static synchronized KeyPair keys(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        String priv = p.getString("selfAdbKey", null), pub = p.getString("selfAdbPub", null);
        KeyFactory f = KeyFactory.getInstance("RSA");
        if (priv != null && pub != null) {
            return new KeyPair(f.generatePublic(new X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP))),
                    f.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        p.edit().putString("selfAdbKey", Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP))
                .putString("selfAdbPub", Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP)).apply();
        return kp;
    }

    /** PKCS#1-v1.5-Signatur des Tokens (Token = „SHA-1-Wert“), RSA ohne eigenes Padding. */
    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        int size = 256;
        byte[] block = new byte[size];
        int t = SHA1_PREFIX.length + token.length;
        block[0] = 0;
        block[1] = 1;
        for (int i = 2; i < size - t - 1; i++) block[i] = (byte) 0xff;
        block[size - t - 1] = 0;
        System.arraycopy(SHA1_PREFIX, 0, block, size - t, SHA1_PREFIX.length);
        System.arraycopy(token, 0, block, size - token.length, token.length);
        Cipher ci = Cipher.getInstance("RSA/ECB/NoPadding");
        ci.init(Cipher.ENCRYPT_MODE, key);
        return ci.doFinal(block);
    }

    /** Öffentlicher Schlüssel im ADB-Format (android_pubkey, Base64) + Name, wie ~/.android/adbkey.pub. */
    private static byte[] publicKey(KeyPair kp) {
        RSAPublicKey k = (RSAPublicKey) kp.getPublic();
        BigInteger n = k.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(n0inv.intValue());
        b.put(littleEndian(n, 256)).put(littleEndian(rr, 256));
        b.putInt(k.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " Fernsehen-App@" + android.os.Build.MODEL + "\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] littleEndian(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] le = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) le[i] = be[be.length - 1 - i];
        return le;
    }
}
