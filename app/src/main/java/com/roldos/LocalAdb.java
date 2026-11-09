package com.roldos;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Collection;

import javax.crypto.Cipher;

/**
 * Talks to the projector's own debug service (adbd, TCP 5555 on this firmware) the way a PC
 * does, so "Close all apps" can run {@code force-stop}: the full close Android reserves for
 * system apps (the app can't restart itself until it's opened again). The first use shows
 * Android's "Allow debugging?" prompt once for RoldOS's key; without it, or if the debug port
 * is off, callers fall back to {@code killBackgroundProcesses}.
 */
final class LocalAdb {
    private static final int PORT = 5555;
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAX_DATA = 4096;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    /** ASN.1 DigestInfo header for SHA-1: adbd verifies the token as if it were a SHA-1 digest. */
    private static final byte[] SHA1_INFO = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
    private static final String DONE = "roldos-done";

    private LocalAdb() { }

    /**
     * Force-stops the given packages. Blocks (call off the UI thread); waits up to
     * {@code approvalWaitMs} the first time, while the "Allow debugging?" prompt is up.
     * Returns false when the debug service can't be used, so nothing was stopped.
     */
    static boolean forceStop(Context ctx, Collection<String> pkgs, int approvalWaitMs) {
        StringBuilder cmd = new StringBuilder();
        for (String p : pkgs) {
            if (!p.matches("[A-Za-z0-9._]+")) continue; // package names only: nothing for the shell
            cmd.append("cmd activity force-stop ").append(p).append(';');
        }
        cmd.append("echo ").append(DONE);
        String out = shell(ctx, cmd.toString(), approvalWaitMs);
        return out != null && out.contains(DONE);
    }

    /**
     * Runs one shell command through adbd; returns its output, or null on any failure.
     * approvalWaitMs <= 0: never trigger the on-screen prompt (unapproved key = null).
     */
    static String shell(Context ctx, String command, int approvalWaitMs) {
        byte[] open = ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8);
        if (open.length > MAX_DATA) return null;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 1000);
            s.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            send(out, CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));

            KeyPair keys = null;
            boolean signed = false, offeredKey = false;
            for (Msg m = read(in); m.cmd != CNXN; m = read(in)) {
                if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) return null;
                if (keys == null) keys = keys(ctx);
                if (!signed) {
                    signed = true; // known key: the signature is enough
                    send(out, AUTH, AUTH_SIGNATURE, 0, sign(keys, m.data));
                } else if (!offeredKey && approvalWaitMs > 0) {
                    offeredKey = true; // unknown key: Android asks on screen
                    send(out, AUTH, AUTH_RSAPUBLICKEY, 0, publicKey(keys));
                    s.setSoTimeout(approvalWaitMs);
                } else {
                    Log.w("RoldOS", "adb: key not accepted");
                    return null;
                }
            }

            s.setSoTimeout(20_000);
            send(out, OPEN, 1, 0, open);
            StringBuilder res = new StringBuilder();
            while (true) {
                Msg m = read(in);
                if (m.cmd == WRTE) {
                    res.append(new String(m.data, StandardCharsets.UTF_8));
                    send(out, OKAY, 1, m.arg0, null);
                } else if (m.cmd == CLSE) {
                    send(out, CLSE, 1, m.arg0, null);
                    return res.toString();
                }
            }
        } catch (Exception e) {
            Log.w("RoldOS", "adb: " + e);
            return null;
        }
    }

    // ------------------------------------------------------------------ wire format

    private static final class Msg {
        int cmd, arg0;
        byte[] data;
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data)
            throws IOException {
        int len = data == null ? 0 : data.length, sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~cmd);
        out.write(h.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    private static Msg read(DataInputStream in) throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        h.getInt(); // arg1
        int len = h.getInt();
        if (len < 0 || len > 1 << 20) throw new IOException("bad length");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ------------------------------------------------------------------ RoldOS's key

    /**
     * RoldOS's own RSA key, made once and kept in its private files (both halves: the private
     * key read back from PKCS#8 isn't a CRT key here, so the public half can't be derived).
     * The approval Android stores is for this exact key: never replace a readable one.
     */
    private static synchronized KeyPair keys(Context ctx) throws Exception {
        File privF = new File(ctx.getFilesDir(), "adbkey2");
        File pubF = new File(ctx.getFilesDir(), "adbkey2.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (privF.isFile() && pubF.isFile()) {
            try {
                return new KeyPair(
                        kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pubF.toPath()))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privF.toPath()))));
            } catch (Exception broken) {
                Log.w("RoldOS", "adb key unreadable, making a new one", broken);
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        save(pubF, kp.getPublic().getEncoded());
        save(privF, kp.getPrivate().getEncoded());
        new File(ctx.getFilesDir(), "adbkey").delete(); // first version's file, unreadable
        return kp;
    }

    private static void save(File f, byte[] bytes) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(bytes);
        }
        if (!tmp.renameTo(f)) throw new IOException("can't save " + f.getName());
    }

    /** PKCS#1 v1.5 signature of the token, treated as a SHA-1 digest (what adbd checks). */
    private static byte[] sign(KeyPair keys, byte[] token) throws Exception {
        int size = ((RSAPublicKey) keys.getPublic()).getModulus().bitLength() / 8;
        byte[] block = new byte[size];
        int ff = size - 3 - SHA1_INFO.length - token.length;
        block[1] = 0x01;
        Arrays.fill(block, 2, 2 + ff, (byte) 0xff);
        System.arraycopy(SHA1_INFO, 0, block, 3 + ff, SHA1_INFO.length);
        System.arraycopy(token, 0, block, 3 + ff + SHA1_INFO.length, token.length);
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, keys.getPrivate());
        return c.doFinal(block);
    }

    /** The public key in adbd's format (Android's RSAPublicKey struct, base64) + a name. */
    private static byte[] publicKey(KeyPair keys) {
        RSAPublicKey pub = (RSAPublicKey) keys.getPublic();
        BigInteger n = pub.getModulus();
        int words = n.bitLength() / 32;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        int n0inv = r32.subtract(n.mod(r32).modInverse(r32)).intValue();
        BigInteger rr = BigInteger.ONE.shiftLeft(n.bitLength() * 2).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 8 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv).put(littleEndian(n, words * 4))
                .put(littleEndian(rr, words * 4)).putInt(pub.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " RoldOS@projector\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] littleEndian(BigInteger v, int size) {
        byte[] be = v.toByteArray(), out = new byte[size];
        for (int i = 0; i < size && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }
}
