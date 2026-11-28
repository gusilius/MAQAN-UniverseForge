package dev.worldforge.mod;

import java.io.IOException;
import java.io.Writer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Минимальный разборщик JSON (без внешних библиотек): объекты, массивы, строки, числа, true/false/null. */
public final class Json {
    private static final String HEX_DIGITS = "0123456789abcdef";
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        return new Json(text).value();
    }


    /**
     * Parses a root object while consuming one named array item at a time.
     * The streamed array is deliberately not retained in the returned object: callers
     * can build their canonical index without simultaneously holding a JSON tree for
     * the entire population. Each element is parsed independently, bounding temporary
     * parser memory to the largest single record.
     */
    public record StreamedArray(Map<String, Object> fields, long itemCount, boolean present) { }

    public static StreamedArray readObjectArray(java.io.Reader input, String arrayKey,
                                                 java.util.function.Consumer<Object> consumer) throws IOException {
        if (input == null) throw new NullPointerException("input");
        if (arrayKey == null || arrayKey.isBlank()) throw new IllegalArgumentException("arrayKey is required");
        if (consumer == null) throw new NullPointerException("consumer");
        java.io.PushbackReader reader = new java.io.PushbackReader(new java.io.BufferedReader(input), 2);
        Map<String, Object> fields = new LinkedHashMap<>();
        long[] count = {0L};
        boolean present = false;
        expect(reader, '{');
        skipWhitespace(reader);
        int next = reader.read();
        if (next == '}') {
            skipWhitespace(reader);
            if (reader.read() != -1) throw new IllegalArgumentException("Trailing data after streamed JSON object");
            return new StreamedArray(fields, 0L, false);
        }
        reader.unread(next);
        while (true) {
            String key = (String) parseRawValue(reader);
            skipWhitespace(reader);
            expect(reader, ':');
            if (arrayKey.equals(key)) {
                if (present) throw new IllegalArgumentException("Duplicate streamed JSON array field: " + arrayKey);
                present = true;
                skipWhitespace(reader);
                expect(reader, '[');
                skipWhitespace(reader);
                int item = reader.read();
                if (item != ']') {
                    reader.unread(item);
                    while (true) {
                        Object value = parseRawValue(reader);
                        consumer.accept(value);
                        count[0] = Math.incrementExact(count[0]);
                        skipWhitespace(reader);
                        int separator = reader.read();
                        if (separator == ']') break;
                        if (separator != ',') throw new IllegalArgumentException("Expected ',' or ']' in streamed JSON array");
                    }
                }
                fields.put(arrayKey, Boolean.TRUE);
            } else {
                Object value = parseRawValue(reader);
                if (fields.containsKey(key)) throw new IllegalArgumentException("Duplicate JSON object field: " + key);
                fields.put(key, value);
            }
            skipWhitespace(reader);
            int separator = reader.read();
            if (separator == '}') break;
            if (separator != ',') throw new IllegalArgumentException("Expected ',' or '}' in streamed JSON object");
        }
        skipWhitespace(reader);
        if (reader.read() != -1) throw new IllegalArgumentException("Trailing data after streamed JSON object");
        return new StreamedArray(fields, count[0], present);
    }

    private static Object parseRawValue(java.io.PushbackReader reader) throws IOException {
        skipWhitespace(reader);
        StringBuilder raw = new StringBuilder(256);
        int first = reader.read();
        if (first < 0) throw new IllegalArgumentException("Unexpected end of JSON");
        raw.append((char) first);
        if (first == '"') {
            boolean escaped = false;
            while (true) {
                int ch = reader.read();
                if (ch < 0) throw new IllegalArgumentException("Unterminated JSON string");
                raw.append((char) ch);
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') break;
            }
        } else if (first == '{' || first == '[') {
            java.util.ArrayDeque<Character> nesting = new java.util.ArrayDeque<>();
            nesting.push(first == '{' ? '}' : ']');
            boolean inString = false, escaped = false;
            while (!nesting.isEmpty()) {
                int ch = reader.read();
                if (ch < 0) throw new IllegalArgumentException("Unterminated JSON container");
                raw.append((char) ch);
                if (inString) {
                    if (escaped) escaped = false;
                    else if (ch == '\\') escaped = true;
                    else if (ch == '"') inString = false;
                    continue;
                }
                if (ch == '"') inString = true;
                else if (ch == '{') nesting.push('}');
                else if (ch == '[') nesting.push(']');
                else if (ch == '}' || ch == ']') {
                    if (nesting.isEmpty() || nesting.pop() != (char) ch)
                        throw new IllegalArgumentException("Mismatched JSON container");
                }
            }
        } else {
            while (true) {
                int ch = reader.read();
                if (ch < 0) break;
                if (ch == ',' || ch == ']' || ch == '}' || Character.isWhitespace(ch)) {
                    reader.unread(ch);
                    break;
                }
                raw.append((char) ch);
            }
        }
        return parse(raw.toString());
    }

    private static void skipWhitespace(java.io.PushbackReader reader) throws IOException {
        int ch;
        do { ch = reader.read(); } while (ch >= 0 && Character.isWhitespace(ch));
        if (ch >= 0) reader.unread(ch);
    }

    private static void expect(java.io.PushbackReader reader, char expected) throws IOException {
        skipWhitespace(reader);
        int actual = reader.read();
        if (actual != expected) throw new IllegalArgumentException("Expected '" + expected + "' in JSON");
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

    public static void writeString(Writer writer, String value) throws IOException {
        if (value == null) { writer.write("null"); return; }
        writer.write('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': writer.write("\\\""); break;
                case '\\': writer.write("\\\\"); break;
                case '\n': writer.write("\\n"); break;
                case '\r': writer.write("\\r"); break;
                case '\t': writer.write("\\t"); break;
                default:
                    if (c < 0x20) {
                        writer.write("\\u00");
                        writer.write(HEX_DIGITS.charAt((c >>> 4) & 15));
                        writer.write(HEX_DIGITS.charAt(c & 15));
                    } else writer.write(c);
            }
        }
        writer.write('"');
    }

    public static void writeNumber(Writer writer, Number value) throws IOException {
        if (value == null) { writer.write("null"); return; }
        double d = value.doubleValue();
        if (value instanceof Double || value instanceof Float) {
            if (d == Math.rint(d) && Math.abs(d) < 1e15) writer.write(Long.toString((long) d));
            else writer.write(Double.toString(d));
        } else writer.write(Long.toString(value.longValue()));
    }

    /** Streams JSON without allocating a full-document StringBuilder. */
    public static void write(Object value, Writer writer) throws IOException {
        if (value == null) writer.write("null");
        else if (value instanceof String string) writeString(writer, string);
        else if (value instanceof Boolean) writer.write(value.toString());
        else if (value instanceof Number number) writeNumber(writer, number);
        else if (value instanceof Map<?, ?> map) {
            writer.write('{'); boolean first=true;
            for (Map.Entry<?, ?> entry:map.entrySet()) {
                if(!first)writer.write(','); first=false;
                writeString(writer,String.valueOf(entry.getKey())); writer.write(':'); write(entry.getValue(),writer);
            }
            writer.write('}');
        } else if (value instanceof List<?> list) {
            writer.write('['); boolean first=true;
            for(Object item:list){if(!first)writer.write(',');first=false;write(item,writer);}
            writer.write(']');
        } else if (value instanceof int[] values) {
            writer.write('['); for(int i=0;i<values.length;i++){if(i>0)writer.write(',');writer.write(Integer.toString(values[i]));} writer.write(']');
        } else writeString(writer,value.toString());
    }

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
