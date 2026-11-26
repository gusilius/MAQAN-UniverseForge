package dev.worldforge.mod;

import java.util.Locale;
import java.util.function.Predicate;

/** Ключи титулов и государств: латиница, цифры и подчёркивания (кириллица транслитерируется). */
final class Slug {
    private static final String[] RU = {"a", "b", "v", "g", "d", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "h", "ts", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya"};

    private Slug() {}

    static String of(String name) {
        StringBuilder b = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') b.append(c);
            else if (c >= 'а' && c <= 'я') b.append(RU[c - 'а']);
            else if (c == 'ё') b.append('e');
            else if (b.length() > 0 && b.charAt(b.length() - 1) != '_') b.append('_');
        }
        String s = b.toString().replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "x" : s;
    }

    /** Уникальный ключ: prefix + slug, при совпадении добавляется _2, _3... */
    static String unique(String prefix, String name, Predicate<String> taken) {
        String base = prefix + of(name), k = base;
        for (int i = 2; taken.test(k); i++) k = base + "_" + i;
        return k;
    }
}
