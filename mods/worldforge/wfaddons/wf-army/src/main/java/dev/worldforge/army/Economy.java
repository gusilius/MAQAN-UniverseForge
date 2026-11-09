package dev.worldforge.army;


import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.TitleEditor;

import java.util.*;

/**
 * Экономика государства: доход по статьям (баронства, развитие, налоговая политика, управляющий совета, торговые договоры, дань),
 * развитие баронств за золото и дань вассалов сюзерену. Золото хранит {@link ArmyEngine}; здесь только расчёт и приказы.
 */
public final class Economy {
    public static final int MAX_DEV = 5;
    public static final double DEV_BONUS = 0.25, FORT_BONUS = 0.1;
    public static final double[] TAX_MULT = {0.8, 1.0, 1.25};
    public static final String[] TAX_NAMES = {"Низкие налоги", "Обычные налоги", "Высокие налоги"};
    public static final int MAX_TRIBUTE = 30;

    public static final class Line {
        public final String label;
        public final double value;
        Line(String label, double value) { this.label = label; this.value = value; }
    }

    public static final class Report {
        public final List<Line> income = new ArrayList<>(), expense = new ArrayList<>();
        public double in() { double s = 0; for (Line l : income) s += l.value; return s; }
        public double out() { double s = 0; for (Line l : expense) s += l.value; return s; }
        public double net() { return in() - out(); }
    }

    private final ArmyEngine e;
    public final Map<Integer, Integer> dev = new LinkedHashMap<>();           // баронство -> уровень развития
    public final Map<String, Integer> tax = new LinkedHashMap<>();            // государство -> 0..2
    public final Map<String, Integer> tribute = new LinkedHashMap<>();        // сюзерен -> процент дани с вассалов

    Economy(ArmyEngine e) { this.e = e; }

    public int level(int barony) { return dev.getOrDefault(barony, 0); }
    public int taxPolicy(String realm) { return tax.getOrDefault(realm, 1); }
    public int tributeRate(String liege) { return tribute.getOrDefault(liege, 0); }

    public double fortOf(int barony) {
        TitleData.Title t = e.war.editor.data().get(barony);
        return t != null && t.props.get("fort") instanceof Number n ? n.doubleValue() : 1;
    }

    /** Доход одного баронства в день. */
    public double baronyIncome(int barony) {
        return ArmyEngine.INCOME_PER_BARONY * (1 + DEV_BONUS * level(barony) + FORT_BONUS * (fortOf(barony) - 1));
    }

    public double developCost(int barony) { return 12 * (level(barony) + 1); }

    /** Собственный доход до дани: баронства с развитием, политика налогов, управляющий, торговые договоры. */
    public double gross(String realm) {
        double base = 0;
        for (int b : e.baroniesOf(realm)) base += baronyIncome(b);
        double pct = 1 + councilPct(realm) / 100.0;
        return base * TAX_MULT[taxPolicy(realm)] * pct + e.pactIncome(realm);
    }

    public double councilPct(String realm) {
        TitleData.Realm r = e.war.editor.data().realms.get(realm);
        return r != null && r.props.get("council.income_pct") instanceof Number n ? n.doubleValue() : 0;
    }

    public List<String> vassals(String liege) {
        List<String> out = new ArrayList<>();
        if (liege == null || liege.isBlank()) return out;
        for (TitleData.Realm r : e.war.editor.data().realmList())
            if (liege.equals(r.overlordId)) out.add(r.id);
        return out;
    }

    public Report report(String realm) {
        Report r = new Report();
        double base = 0, devBonus = 0, fortBonus = 0;
        for (int b : e.baroniesOf(realm)) {
            base += ArmyEngine.INCOME_PER_BARONY;
            devBonus += ArmyEngine.INCOME_PER_BARONY * DEV_BONUS * level(b);
            fortBonus += ArmyEngine.INCOME_PER_BARONY * FORT_BONUS * (fortOf(b) - 1);
        }
        double land = base + devBonus + fortBonus;
        r.income.add(new Line("Баронства (" + e.baroniesOf(realm).size() + ")", base));
        if (devBonus > 0.001) r.income.add(new Line("Развитие земель", devBonus));
        if (fortBonus > 0.001) r.income.add(new Line("Крепости", fortBonus));
        double taxDelta = land * (TAX_MULT[taxPolicy(realm)] - 1);
        if (Math.abs(taxDelta) > 0.001) r.income.add(new Line(TAX_NAMES[taxPolicy(realm)], taxDelta));
        double pct = councilPct(realm);
        if (Math.abs(pct) > 0.01) r.income.add(new Line("Управляющий совета (" + (pct > 0 ? "+" : "") + Math.round(pct) + "%)", (land + taxDelta) * pct / 100.0));
        double pact = e.pactIncome(realm);
        if (pact > 0.001) r.income.add(new Line("Торговые договоры (" + e.partners(realm).size() + ")", pact));
        double tin = 0;
        for (String v : vassals(realm)) tin += tributeFrom(v, realm);
        if (tin > 0.001) r.income.add(new Line("Дань вассалов", tin));
        TitleData.Realm me = e.war.editor.data().realms.get(realm);
        if (me != null && me.overlordId != null) {
            double tout = tributeFrom(realm, me.overlordId);
            if (tout > 0.001) r.expense.add(new Line("Дань сюзерену", tout));
        }
        return r;
    }

    /** Дань вассала сюзерену в день: процент от собственного дохода вассала. */
    public double tributeFrom(String vassal, String liege) {
        int rate = tributeRate(liege);
        return rate <= 0 ? 0 : gross(vassal) * rate / 100.0;
    }

    /** Чистый денежный поток государства до расходов на армию. */
    public double net(String realm) { return report(realm).net(); }

    // ------------------------------------------------------------------ приказы

    public void develop(String realm, int barony) {
        if (!e.baroniesOf(realm).contains(barony)) throw new TitleEditor.EditException("Это баронство не ваше");
        if (level(barony) >= MAX_DEV) throw new TitleEditor.EditException("Развитие уже на пределе");
        double c = developCost(barony);
        if (e.gold(realm) < c) throw new TitleEditor.EditException("Не хватает золота: нужно " + Math.round(c));
        e.gold.put(realm, e.gold(realm) - c);
        dev.put(barony, level(barony) + 1);
        e.log(e.realmName(realm) + " развивает " + e.title(barony) + " до уровня " + level(barony));
    }

    public void setTax(String realm, int policy) {
        if (policy < 0 || policy > 2) throw new TitleEditor.EditException("Политика налогов 0-2");
        tax.put(realm, policy);
    }

    public void setTribute(String liege, int percent) {
        if (percent < 0 || percent > MAX_TRIBUTE) throw new TitleEditor.EditException("Дань 0-" + MAX_TRIBUTE + "%");
        tribute.put(liege, percent);
    }

    // ------------------------------------------------------------------ сохранение

    Map<String, Object> save() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> d = new LinkedHashMap<>();
        for (var x : dev.entrySet()) d.put(String.valueOf(x.getKey()), x.getValue());
        m.put("dev", d); m.put("tax", new LinkedHashMap<String, Object>(tax)); m.put("tribute", new LinkedHashMap<String, Object>(tribute));
        return m;
    }

    void load(Object o) {
        dev.clear(); tax.clear(); tribute.clear();
        if (!(o instanceof Map<?, ?> m)) return;
        if (m.get("dev") instanceof Map<?, ?> d) for (var x : d.entrySet()) dev.put(Integer.parseInt(String.valueOf(x.getKey())), ((Number) x.getValue()).intValue());
        if (m.get("tax") instanceof Map<?, ?> d) for (var x : d.entrySet()) tax.put(String.valueOf(x.getKey()), ((Number) x.getValue()).intValue());
        if (m.get("tribute") instanceof Map<?, ?> d) for (var x : d.entrySet()) tribute.put(String.valueOf(x.getKey()), ((Number) x.getValue()).intValue());
    }
}
