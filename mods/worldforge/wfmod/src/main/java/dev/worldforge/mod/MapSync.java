package dev.worldforge.mod;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Двоичный обмен картой между сервером и клиентом: полное состояние, дельта правок, нарезка на пакеты. Без зависимостей от Minecraft. */
public final class MapSync {
    private static final int MAGIC = 0x57464D31;

    private MapSync() {}

    public static final class State {
        public String name;
        public int version, w, h, seaLevel, minY, maxY;
        public double bpp;
        public TitleData titles;
    }

    private static void writeString(DataOutputStream o, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        o.writeInt(b.length);
        o.write(b);
    }

    private static String readString(DataInputStream i) throws IOException {
        byte[] b = new byte[i.readInt()];
        i.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    public static byte[] encodeState(MapData m, int version) throws IOException {
        TitleData d = m.titles;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (DataOutputStream o = new DataOutputStream(new DeflaterOutputStream(bo))) {
            o.writeInt(MAGIC);
            o.writeInt(version);
            writeString(o, m.name);
            o.writeInt(d.w);
            o.writeInt(d.h);
            o.writeDouble(d.bpp);
            o.writeInt(m.seaLevel);
            o.writeInt(m.minY);
            o.writeInt(m.maxY);
            writeString(o, d.titlesJson());
            writeString(o, d.realmsJson());
            short[] px = d.pixels();
            for (short v : px) o.writeShort(v);
        }
        return bo.toByteArray();
    }

    public static State decodeState(byte[] data) throws IOException {
        try (DataInputStream i = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
            if (i.readInt() != MAGIC) throw new IOException("Неверный формат состояния карты");
            State s = new State();
            s.version = i.readInt();
            s.name = readString(i);
            s.w = i.readInt();
            s.h = i.readInt();
            s.bpp = i.readDouble();
            s.seaLevel = i.readInt();
            s.minY = i.readInt();
            s.maxY = i.readInt();
            String t = readString(i), r = readString(i);
            short[] px = new short[s.w * s.h];
            for (int k = 0; k < px.length; k++) px[k] = i.readShort();
            s.titles = TitleData.create(t, r, px, s.w, s.h);
            return s;
        }
    }

    /** Дельта: изменившиеся пиксели (по сравнению с old) и свежие json. */
    public static byte[] encodeDelta(TitleData d, short[] old, int version) throws IOException {
        short[] px = d.pixels();
        int n = 0;
        for (int k = 0; k < px.length; k++) if (px[k] != old[k]) n++;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (DataOutputStream o = new DataOutputStream(new DeflaterOutputStream(bo))) {
            o.writeInt(MAGIC + 1);
            o.writeInt(version);
            o.writeInt(n);
            for (int k = 0; k < px.length; k++) {
                if (px[k] != old[k]) { o.writeInt(k); o.writeShort(px[k]); }
            }
            writeString(o, d.titlesJson());
            writeString(o, d.realmsJson());
        }
        return bo.toByteArray();
    }

    public static void applyDelta(State s, byte[] data) throws IOException {
        try (DataInputStream i = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
            if (i.readInt() != MAGIC + 1) throw new IOException("Неверный формат дельты");
            int version = i.readInt(), n = i.readInt();
            short[] px = s.titles.pixels();
            for (int k = 0; k < n; k++) {
                int idx = i.readInt();
                px[idx] = i.readShort();
            }
            String t = readString(i), r = readString(i);
            s.titles.loadMeta(t, r);
            s.version = version;
        }
    }

    public static String sha1(byte[] data) {
        try {
            StringBuilder b = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-1").digest(data)) b.append(String.format("%02x", x));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ нарезка на пакеты

    public static List<byte[]> split(byte[] data, int maxChunk) {
        List<byte[]> out = new ArrayList<>();
        for (int off = 0; off < data.length || off == 0; off += maxChunk) {
            int len = Math.min(maxChunk, data.length - off);
            byte[] c = new byte[Math.max(0, len)];
            System.arraycopy(data, off, c, 0, c.length);
            out.add(c);
            if (data.length == 0) break;
        }
        return out;
    }

    /** Собирает куски в целое по номеру передачи. */
    public static final class Reassembler {
        private final Map<Integer, byte[][]> parts = new HashMap<>();

        /** Возвращает собранные данные, когда пришёл последний кусок, иначе null. */
        public byte[] add(int transferId, int index, int total, byte[] chunk) {
            byte[][] a = parts.computeIfAbsent(transferId, k -> new byte[total][]);
            if (index < 0 || index >= a.length) return null;
            a[index] = chunk;
            int size = 0;
            for (byte[] p : a) {
                if (p == null) return null;
                size += p.length;
            }
            byte[] out = new byte[size];
            int off = 0;
            for (byte[] p : a) { System.arraycopy(p, 0, out, off, p.length); off += p.length; }
            parts.remove(transferId);
            return out;
        }

        public void clear() {
            parts.clear();
        }
    }
}
