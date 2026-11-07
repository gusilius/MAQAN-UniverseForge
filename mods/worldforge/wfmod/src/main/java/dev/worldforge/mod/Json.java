package dev.worldforge.mod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Минимальный разборщик JSON (без внешних библиотек): объекты, массивы, строки, числа, true/false/null. */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        return new Json(text).value();
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value() {
        ws();
        char c = s.charAt(i);
        switch (c) {
            case '{': return obj();
            case '[': return arr();
            case '"': return str();
            case 't': i += 4; return Boolean.TRUE;
            case 'f': i += 5; return Boolean.FALSE;
            case 'n': i += 4; return null;
            default: return num();
        }
    }

    private Map<String, Object> obj() {
        i++;
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            String k = str();
            ws();
            i++; // ':'
            m.put(k, value());
            ws();
            if (s.charAt(i++) == '}') break;
        }
        return m;
    }

    private List<Object> arr() {
        i++;
        List<Object> l = new ArrayList<>();
        ws();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            l.add(value());
            ws();
            if (s.charAt(i++) == ']') break;
        }
        return l;
    }

    private String str() {
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') break;
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private Double num() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        return Double.parseDouble(s.substring(st, i));
    }

    // ------------------------------------------------------------------ запись

    /** Компактная запись JSON: Map, List, String, Number, Boolean, null. Целые числа пишутся без «.0». */
    public static String write(Object o) {
        StringBuilder b = new StringBuilder();
        write(b, o);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object o) {
        if (o == null) {
            b.append("null");
        } else if (o instanceof String) {
            quote(b, (String) o);
        } else if (o instanceof Boolean) {
            b.append(o);
        } else if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (o instanceof Double || o instanceof Float) {
                if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d); else b.append(d);
            } else {
                b.append(((Number) o).longValue());
            }
        } else if (o instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(b, e.getKey());
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (o instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object x : (List<Object>) o) {
                if (!first) b.append(',');
                first = false;
                write(b, x);
            }
            b.append(']');
        } else if (o instanceof int[]) {
            b.append('[');
            int[] a = (int[]) o;
            for (int i = 0; i < a.length; i++) {
                if (i > 0) b.append(',');
                b.append(a[i]);
            }
            b.append(']');
        } else {
            quote(b, o.toString());
        }
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        b.append('"');
    }
}
