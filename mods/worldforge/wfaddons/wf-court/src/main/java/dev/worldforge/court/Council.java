package dev.worldforge.court;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.CharacterStats;
import dev.worldforge.people.Courts;
import dev.worldforge.people.Role;
import dev.worldforge.people.RoleDefs;

import java.util.*;

/**
 * Совет государства: каждая роль с полем council в JSON даёт государству прирост по характеристике носителя (как советники CK3).
 * Правитель добавляет половину своих характеристик ко всем статьям. Результат пригоден для других аддонов: {@link CourtSim#syncProps}
 * пишет его в свойства государства "council.<ключ>".
 */
public final class Council {
    private Council() {}

    public static final class Seat {
        public final Role role;
        public final Character holder;                          // null - место пустует
        public final int value;                                  // характеристика носителя (с чертами)
        public final String attr;

        Seat(Role role, Character holder, int value, String attr) { this.role = role; this.holder = holder; this.value = value; this.attr = attr; }

        public String attrName() { for (int i = 0; i < CharacterStats.ATTRS.length; i++) if (CharacterStats.ATTRS[i].equals(attr)) return CharacterStats.ATTR_NAMES[i]; return attr; }
    }

    public static final Map<String, String> EFFECT_NAMES = new LinkedHashMap<>();

    static {
        EFFECT_NAMES.put("income_pct", "доход, %");
        EFFECT_NAMES.put("levy_pct", "ополчение, %");
        EFFECT_NAMES.put("commander", "умение полководцев");
        EFFECT_NAMES.put("vassal_opinion", "мнение вассалов");
        EFFECT_NAMES.put("ai_accept", "склонность к договорам");
        EFFECT_NAMES.put("plot_defense", "защита от козней, %");
        EFFECT_NAMES.put("scheme_power", "сила своих козней");
        EFFECT_NAMES.put("fort_days", "дни осады крепостей");
        EFFECT_NAMES.put("unrest_pct", "беспорядки, %");
    }

    public static List<Seat> seats(CharacterRegistry reg, RoleDefs defs, String realm) {
        List<Seat> out = new ArrayList<>();
        for (Role r : defs.all()) {
            if (r.councilAttr.isEmpty() || !r.per.equals("realm")) continue;
            Character h = reg.holder(r.id, Courts.realmPlace(realm));
            out.add(new Seat(r, h, h == null ? 0 : CharacterStats.value(h, r.councilAttr), r.councilAttr));
        }
        return out;
    }

    /** Эффект за пустое место 0; за носителя - (характеристика - 5) за единицу из JSON (ниже 5 - штраф), плюс половина правителя. */
    public static Map<String, Double> effects(CharacterRegistry reg, RoleDefs defs, String realm) {
        Map<String, Double> m = new LinkedHashMap<>();
        Role rulerRole = defs.ruler();
        Character ruler = rulerRole == null ? null : reg.holder(rulerRole.id, Courts.realmPlace(realm));
        for (Seat s : seats(reg, defs, realm)) {
            double v = s.holder == null ? 0 : s.value - 5;
            if (ruler != null && ruler != s.holder) v += (CharacterStats.value(ruler, s.attr) - 5) * 0.5;
            for (Map.Entry<String, Double> e : s.role.councilEffects.entrySet()) m.merge(e.getKey(), e.getValue() * v, Double::sum);
        }
        return m;
    }
}
