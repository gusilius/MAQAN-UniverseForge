package dev.worldforge.settle;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Минимальный читатель NBT без Minecraft (нужен для чтения схем .litematic, .schem, .nbt).
 * Составной тег -> Map, список -> List, массивы -> byte[]/int[]/long[], числа -> Byte/Short/Integer/Long/Float/Double.
 */
public final class Nbt {
    private Nbt() {}

    /** Корневой тег; данные могут быть сжаты gzip или нет. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(byte[] data) throws IOException {
        InputStream in = new ByteArrayInputStream(data);
        if (data.length > 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) in = new GZIPInputStream(in);
        DataInputStream d = new DataInputStream(new BufferedInputStream(in));
        int type = d.readUnsignedByte();
        if (type != 10) throw new IOException("NBT: корень не compound");
        d.readUTF();
        return (Map<String, Object>) payload(d, 10, 0);
    }

    private static Object payload(DataInputStream d, int type, int depth) throws IOException {
        if (depth > 64) throw new IOException("NBT: слишком глубокая вложенность");
        switch (type) {
            case 1: return d.readByte();
            case 2: return d.readShort();
            case 3: return d.readInt();
            case 4: return d.readLong();
            case 5: return d.readFloat();
            case 6: return d.readDouble();
            case 7: { int n = checkLen(d.readInt(), 1); byte[] b = new byte[n]; d.readFully(b); return b; }
            case 8: return d.readUTF();
            case 9: {
                int t = d.readUnsignedByte();
                int n = checkLen(d.readInt(), 1);
                List<Object> l = new ArrayList<>();
                for (int i = 0; i < n; i++) l.add(payload(d, t, depth + 1));
                return l;
            }
            case 10: {
                Map<String, Object> m = new LinkedHashMap<>();
                while (true) {
                    int t = d.readUnsignedByte();
                    if (t == 0) return m;
                    String k = d.readUTF();
                    m.put(k, payload(d, t, depth + 1));
                }
            }
            case 11: { int n = checkLen(d.readInt(), 4); int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = d.readInt(); return a; }
            case 12: { int n = checkLen(d.readInt(), 8); long[] a = new long[n]; for (int i = 0; i < n; i++) a[i] = d.readLong(); return a; }
            default: throw new IOException("NBT: неизвестный тип тега " + type);
        }
    }

    private static int checkLen(int n, int unit) throws IOException {
        if (n < 0 || (long) n * unit > 200_000_000L) throw new IOException("NBT: недопустимая длина " + n);
        return n;
    }

    // ---- удобные доступы
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o, String k) { Object v = ((Map<String, Object>) o).get(k); return v instanceof Map ? (Map<String, Object>) v : null; }

    public static int num(Map<String, Object> m, String k, int def) { return m != null && m.get(k) instanceof Number n ? n.intValue() : def; }
}
