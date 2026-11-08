package dev.worldforge.mod;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Двоичный обмен картой: полное состояние, дельты и ограниченная сборка пакетов. */
public final class MapSync {
    private static final int MAGIC = 0x57464D31;
    private static final int MAX_PIXELS = 64_000_000;
    private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;
    private static final int MAX_TRANSFER_BYTES = 64 * 1024 * 1024;
    private static final int MAX_CHUNK_BYTES = 1024 * 1024;
    private static final int MAX_CHUNKS = 4096;
    private static final int MAX_ACTIVE_TRANSFERS = 16;

    private MapSync() {}

    public static final class State {
        public String name;
        public int version, w, h, seaLevel, minY, maxY;
        public double bpp;
        public TitleData titles;
    }

    private static void writeString(DataOutputStream o, String s) throws IOException {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        if (b.length > MAX_STRING_BYTES) throw new IOException("Строка карты слишком велика");
        o.writeInt(b.length);
        o.write(b);
    }

    private static String readString(DataInputStream i) throws IOException {
        int length = i.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) throw new IOException("Недопустимая длина строки карты: " + length);
        byte[] b = new byte[length];
        i.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static int checkedPixelCount(int w, int h) throws IOException {
        if (w <= 0 || h <= 0) throw new IOException("Размеры карты должны быть положительными");
        long count = (long) w * h;
        if (count > MAX_PIXELS) throw new IOException("Карта превышает лимит пикселей: " + count);
        return (int) count;
    }

    public static byte[] encodeState(MapData m, int version) throws IOException {
        if (m == null || m.titles == null) throw new IOException("Нет политической карты для синхронизации");
        checkedPixelCount(m.titles.w, m.titles.h);
        if (!Double.isFinite(m.titles.bpp) || m.titles.bpp <= 0) throw new IOException("Недопустимый масштаб карты");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (DataOutputStream o = new DataOutputStream(new DeflaterOutputStream(bo))) {
            o.writeInt(MAGIC);
            o.writeInt(version);
            writeString(o, m.name);
            o.writeInt(m.titles.w);
            o.writeInt(m.titles.h);
            o.writeDouble(m.titles.bpp);
            o.writeInt(m.seaLevel);
            o.writeInt(m.minY);
            o.writeInt(m.maxY);
            writeString(o, m.titles.titlesJson());
            writeString(o, m.titles.realmsJson());
            short[] px = m.titles.pixels();
            for (short v : px) o.writeShort(v);
        }
        return bo.toByteArray();
    }

    public static State decodeState(byte[] data) throws IOException {
        if (data == null || data.length == 0 || data.length > MAX_TRANSFER_BYTES)
            throw new IOException("Недопустимый размер состояния карты");
        try (DataInputStream i = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
            if (i.readInt() != MAGIC) throw new IOException("Неверный формат состояния карты");
            State s = new State();
            s.version = i.readInt();
            s.name = readString(i);
            s.w = i.readInt();
            s.h = i.readInt();
            int count = checkedPixelCount(s.w, s.h);
            s.bpp = i.readDouble();
            if (!Double.isFinite(s.bpp) || s.bpp <= 0) throw new IOException("Недопустимый масштаб карты");
            s.seaLevel = i.readInt();
            s.minY = i.readInt();
            s.maxY = i.readInt();
            if (s.minY >= s.maxY) throw new IOException("Недопустимый диапазон высот карты");
            String t = readString(i), r = readString(i);
            short[] px = new short[count];
            for (int k = 0; k < px.length; k++) px[k] = i.readShort();
            try {
                s.titles = TitleData.create(t, r, px, s.w, s.h);
            } catch (RuntimeException error) {
                throw new IOException("Повреждены метаданные карты", error);
            }
            if (Math.abs(s.titles.bpp - s.bpp) > 1.0e-9) throw new IOException("Масштаб карты не совпадает с метаданными");
            return s;
        } catch (NegativeArraySizeException | IndexOutOfBoundsException error) {
            throw new IOException("Повреждены размеры состояния карты", error);
        }
    }

    /** Дельта: изменившиеся пиксели (по сравнению с old) и свежие JSON-метаданные. */
    public static byte[] encodeDelta(TitleData d, short[] old, int version) throws IOException {
        if (d == null || old == null) throw new IOException("Нет данных для дельты");
        short[] px = d.pixels();
        if (old.length != px.length) throw new IOException("Размер предыдущей карты не совпадает");
        if (px.length > MAX_PIXELS) throw new IOException("Карта превышает лимит пикселей");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (DataOutputStream o = new DataOutputStream(new DeflaterOutputStream(bo))) {
            o.writeInt(MAGIC + 1);
            o.writeInt(version);
            int n = 0;
            for (int k = 0; k < px.length; k++) if (px[k] != old[k]) n++;
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
        if (s == null || s.titles == null || data == null || data.length == 0 || data.length > MAX_TRANSFER_BYTES)
            throw new IOException("Нет базового состояния или неверный размер дельты");
        try (DataInputStream i = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
            if (i.readInt() != MAGIC + 1) throw new IOException("Неверный формат дельты");
            int version = i.readInt(), n = i.readInt();
            // Work on a private copy: malformed metadata must never partially mutate the published state.
            short[] px = s.titles.pixels().clone();
            if (n < 0 || n > px.length) throw new IOException("Недопустимое число изменений: " + n);
            Set<Integer> changed = new HashSet<>();
            for (int k = 0; k < n; k++) {
                int idx = i.readInt();
                if (idx < 0 || idx >= px.length || !changed.add(idx))
                    throw new IOException("Недопустимый или повторный индекс пикселя: " + idx);
                px[idx] = i.readShort();
            }
            String t = readString(i), r = readString(i);
            TitleData updated;
            try {
                updated = TitleData.create(t, r, px, s.w, s.h);
            } catch (RuntimeException error) {
                throw new IOException("Повреждены метаданные дельты", error);
            }
            if (Math.abs(updated.bpp - s.bpp) > 1.0e-9) throw new IOException("Дельта изменила масштаб карты");
            s.titles = updated;
            s.version = version;
        } catch (IndexOutOfBoundsException error) {
            throw new IOException("Повреждён индекс дельты", error);
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

    public static List<byte[]> split(byte[] data, int maxChunk) {
        if (data == null) throw new IllegalArgumentException("data is required");
        if (maxChunk <= 0 || maxChunk > MAX_CHUNK_BYTES) throw new IllegalArgumentException("Недопустимый размер части");
        if (data.length > MAX_TRANSFER_BYTES) throw new IllegalArgumentException("Передача превышает лимит размера");
        List<byte[]> out = new ArrayList<>();
        for (int off = 0; off < data.length || off == 0; off += maxChunk) {
            int len = Math.min(maxChunk, data.length - off);
            out.add(Arrays.copyOfRange(data, off, off + len));
            if (data.length == 0) break;
        }
        return out;
    }

    /** Сборка чанков с ограничением памяти и проверкой целостности метаданных передачи. */
    public static final class Reassembler {
        private static final class Transfer {
            final byte[][] chunks;
            int received;
            int bytes;
            Transfer(int total) { chunks = new byte[total][]; }
        }
        private final Map<Integer, Transfer> parts = new HashMap<>();

        /** Возвращает данные после получения всех частей; неверная последовательность отклоняется. */
        public synchronized byte[] add(int transferId, int index, int total, byte[] chunk) {
            if (chunk == null || chunk.length > MAX_CHUNK_BYTES) throw new IllegalArgumentException("Недопустимый чанк");
            if (total <= 0 || total > MAX_CHUNKS || index < 0 || index >= total)
                throw new IllegalArgumentException("Недопустимые индексы передачи");
            Transfer transfer = parts.get(transferId);
            if (transfer == null) {
                if (parts.size() >= MAX_ACTIVE_TRANSFERS) throw new IllegalStateException("Слишком много незавершённых передач");
                transfer = new Transfer(total);
                parts.put(transferId, transfer);
            } else if (transfer.chunks.length != total) {
                parts.remove(transferId);
                throw new IllegalArgumentException("Количество частей передачи изменилось");
            }
            byte[] previous = transfer.chunks[index];
            if (previous != null) {
                if (!Arrays.equals(previous, chunk)) {
                    parts.remove(transferId);
                    throw new IllegalArgumentException("Повторный чанк содержит другие данные");
                }
                return null;
            }
            if ((long) transfer.bytes + chunk.length > MAX_TRANSFER_BYTES) {
                parts.remove(transferId);
                throw new IllegalArgumentException("Передача превышает лимит размера");
            }
            transfer.chunks[index] = chunk.clone();
            transfer.bytes += chunk.length;
            transfer.received++;
            if (transfer.received != total) return null;
            byte[] result = new byte[transfer.bytes];
            int offset = 0;
            for (byte[] part : transfer.chunks) {
                System.arraycopy(part, 0, result, offset, part.length);
                offset += part.length;
            }
            parts.remove(transferId);
            return result;
        }

        public synchronized void clear() { parts.clear(); }
        public synchronized int activeTransfers() { return parts.size(); }
    }
}
