package dev.worldforge.people;

import java.util.*;

/**
 * Характеристики и черты персонажа. Чистая логика: если в {@code props} нет значений (старые сохранения, персонажи из
 * {@link Courts}), они выводятся детерминированно из id, так что окно персонажа всегда показывает одно и то же.
 * {@link #ensure} записывает выведенное в props/traits, чтобы значения закрепились в сохранении.
 */
public final class CharacterStats {
    private CharacterStats() {}

    public static final String[] ATTRS = {"diplomacy", "martial", "intrigue", "stewardship", "learning"};
    public static final String[] ATTR_NAMES = {"Дипломатия", "Военное дело", "Интрига", "Управление", "Знания"};

    /** Черта: имя, описание, цвет значка (+ / - / нейтральная) и поправки к характеристикам. */
    public static final class Trait {
        public final String id, name, desc;
        public final int color;
        public final Map<String, Integer> mods;

        Trait(String id, String name, String desc, int color, Object... mods) {
            this.id = id; this.name = name; this.desc = desc; this.color = color;
            Map<String, Integer> m = new LinkedHashMap<>();
            for (int i = 0; i < mods.length; i += 2) m.put((String) mods[i], (Integer) mods[i + 1]);
            this.mods = Collections.unmodifiableMap(m);
        }
    }

    private static final int GOOD = 0xFF4FCF5A, BAD = 0xFFE04A4A, NEUT = 0xFF4AA8FF, GOLD = 0xFFF0C85A;
    private static final Map<String, Trait> TRAITS = new LinkedHashMap<>();

    private static void t(Trait t) { TRAITS.put(t.id, t); }

    static {
        t(new Trait("brave", "Храбрый", "Не боится боя: лучше командует, хуже ведёт тонкую игру.", GOOD, "martial", 2, "intrigue", -1));
        t(new Trait("craven", "Трус", "Бежит от опасности. Солдаты не следуют за ним охотно.", BAD, "martial", -2));
        t(new Trait("just", "Справедливый", "Судит по закону, а не по выгоде. Подданные ему доверяют.", GOOD, "stewardship", 1, "diplomacy", 1));
        t(new Trait("cruel", "Жестокий", "Наказывает без жалости. Боятся, но не любят.", BAD, "intrigue", 1, "diplomacy", -2));
        t(new Trait("greedy", "Алчный", "Копит золото любой ценой.", BAD, "stewardship", 1, "diplomacy", -1));
        t(new Trait("generous", "Щедрый", "Раздаёт дары, пока казна не опустеет.", GOOD, "diplomacy", 2, "stewardship", -1));
        t(new Trait("shrewd", "Хитрый", "Видит интересы других насквозь и играет на них.", NEUT, "intrigue", 3));
        t(new Trait("scholar", "Книжник", "Годы за свитками: знает много, на войне бесполезен.", NEUT, "learning", 3, "martial", -1));
        t(new Trait("diligent", "Усердный", "Доводит дела до конца.", GOOD, "stewardship", 2));
        t(new Trait("lazy", "Ленивый", "Откладывает всё на завтра.", BAD, "stewardship", -2));
        t(new Trait("gregarious", "Общительный", "Легко заводит друзей при дворе.", GOOD, "diplomacy", 2));
        t(new Trait("shy", "Робкий", "Теряется среди чужих.", BAD, "diplomacy", -2, "intrigue", 1));
        t(new Trait("ambitious", "Честолюбивый", "Хочет большего, чем имеет.", NEUT, "intrigue", 1, "stewardship", 1));
        t(new Trait("content", "Довольный", "Не рвётся к власти и не плетёт интриг.", GOOD, "intrigue", -1, "diplomacy", 1));
        t(new Trait("wroth", "Вспыльчивый", "Гнев застилает глаза.", BAD, "martial", 1, "diplomacy", -1));
        t(new Trait("patient", "Терпеливый", "Умеет ждать.", GOOD, "stewardship", 1, "learning", 1));
        t(new Trait("usurper", "Узурпатор", "Взял власть без права наследования; знать не принимает его всерьёз.", GOLD, "diplomacy", -1));
    }

    public static Trait trait(String id) { return TRAITS.get(id); }

    public static Collection<Trait> allTraits() { return TRAITS.values(); }

    private static long hash(String s) {
        long h = 0xCBF29CE484222325L;
        for (char c : s.toCharArray()) h = (h ^ c) * 0x100000001B3L;
        h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32;
        return h;
    }

    /** Базовое значение 0..20 без поправок черт: из props "stat.<key>", иначе выведено из id. */
    public static int base(Character c, String attr) {
        Object v = c.props.get("stat." + attr);
        if (v instanceof Number n) return Math.max(0, Math.min(20, n.intValue()));
        return 4 + (int) Math.floorMod(hash(c.id + "|" + attr), 9L) + (int) Math.floorMod(hash(c.id + "|b" + attr), 4L);   // 4..16
    }

    /** Черты: записанные, иначе две выведенные из id (без дубликатов). */
    public static List<String> traits(Character c) {
        if (!c.traits.isEmpty()) return new ArrayList<>(c.traits);
        List<String> ids = new ArrayList<>(TRAITS.keySet());
        ids.remove("usurper");
        List<String> out = new ArrayList<>();
        long h = hash(c.id + "|traits");
        int n = 2 + (int) Math.floorMod(h >>> 40, 2L);
        for (int i = 0; out.size() < n && i < 64; i++) {
            String id = ids.get((int) Math.floorMod(hash(c.id + "|t" + i), (long) ids.size()));
            if (!out.contains(id)) out.add(id);
        }
        Collections.sort(out);
        return out;
    }

    public static int bonus(Character c, String attr) {
        int b = 0;
        for (String id : traits(c)) { Trait t = TRAITS.get(id); if (t != null) b += t.mods.getOrDefault(attr, 0); }
        return b;
    }

    /** Итоговое значение 0..20 с поправками черт. */
    public static int value(Character c, String attr) { return Math.max(0, Math.min(20, base(c, attr) + bonus(c, attr))); }

    public static int age(Character c, int year) { return Math.max(0, year - c.birthYear); }

    /** Закрепляет выведенные характеристики и черты в данных персонажей (чтобы они попали в сохранение). */
    public static void ensure(CharacterRegistry reg) {
        for (Character c : reg.all()) {
            for (String a : ATTRS) if (!(c.props.get("stat." + a) instanceof Number)) c.props.put("stat." + a, base(c, a));
            if (c.traits.isEmpty()) c.traits.addAll(traits(c));
        }
    }
}
