package dev.worldforge.forms;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.api.StatApi;

import java.util.*;

/**
 * Состояния превращений. У персонажа (владельца в общей шине: "p:<uuid>", "n:<id>") может быть несколько форм: каждая растёт со временем
 * (или с уровнем), проходит ступени, по триггерам (ночь, полнолуние, ярость, голод...) превращает в иной облик, имеет слабости, потребность
 * (кровь, плоть), самообладание и способы излечения. Всё считается по средам, которые подаёт игра ({@link Env}); сам движок не знает Minecraft.
 */
public final class Forms {
    /** Среда на момент тика. rage - игрок сейчас в бою и ранен; water - стоит у воды; dark - тёмное место. */
    public record Env(int day, boolean night, boolean fullMoon, boolean sun, boolean holy, boolean silver, boolean rage, boolean hunger, boolean water, boolean dark) {
        public static Env day(int d) { return new Env(d, false, false, false, false, false, false, false, false, false); }
        public Env atNight() { return new Env(day, true, fullMoon, false, holy, silver, rage, hunger, water, true); }
        public Env atFullMoon() { return new Env(day, true, true, false, holy, silver, rage, hunger, water, true); }
        public boolean has(String k) {
            return switch (k) { case "sun" -> sun; case "holy" -> holy; case "silver" -> silver; case "water" -> water; case "dark" -> dark; default -> false; };
        }
    }

    public interface Hooks {
        int level(String owner);
        boolean isRace(String owner, String race);
        /** Защита от заражения (оберег, святая вода на теле). */
        boolean protectedFrom(String owner, String form);
        int count(String owner, String item);
        boolean take(String owner, String item, int n);
        int gold(String owner);
        boolean takeGold(String owner, int n);
        boolean flag(String owner, String flag);
        void notify(String owner, String text);
        void news(String text);
    }

    public static class Hooks0 implements Hooks {
        public int level(String o) { return 1; }
        public boolean isRace(String o, String r) { return r.equals(StatApi.tag(o, "race")); }
        public boolean protectedFrom(String o, String f) { return false; }
        public int count(String o, String i) { return 0; }
        public boolean take(String o, String i, int n) { return false; }
        public int gold(String o) { return 0; }
        public boolean takeGold(String o, int n) { return false; }
        public boolean flag(String o, String f) { return false; }
        public void notify(String o, String t) { }
        public void news(String t) { }
    }

    public static final class Aff {
        public double progress, meter = -1, control;
        public int stage, since, activeUntil = -1, lastShift = -9999, resisted, shifts;
        public boolean active, exposed, voluntary;
        public Set<String> env = new LinkedHashSet<>();     // слабости, действующие сейчас
    }

    public final FormDefs defs;
    public Hooks hooks = new Hooks0();
    public String lastError = "";
    public Random rng = new Random();
    private final Map<String, Map<String, Aff>> owners = new LinkedHashMap<>();
    public final List<String> chronicle = new ArrayList<>();

    public Forms(FormDefs defs) { this.defs = defs; }

    public Map<String, Aff> of(String owner) { return owners.computeIfAbsent(owner, k -> new LinkedHashMap<>()); }
    public Aff get(String owner, String form) { Map<String, Aff> m = owners.get(owner); return m == null ? null : m.get(form); }
    public boolean has(String owner, String form) { return get(owner, form) != null; }
    private boolean fail(String m) { lastError = m; return false; }

    // ---------- заражение ----------

    /** Передаёт форму. reason - в хронику. false - нельзя (причина в lastError). */
    public boolean infect(String owner, String formId, int day, String reason) {
        FormDefs.Form f = defs.forms.get(formId);
        if (f == null) return fail("Нет такой формы.");
        if (has(owner, formId)) return fail("Уже есть.");
        if (hooks.protectedFrom(owner, formId)) return fail("Защита не пропустила порчу.");
        if (!f.onlyRaces().isEmpty() && f.onlyRaces().stream().noneMatch(r -> hooks.isRace(owner, r))) return fail("Эта форма не для вашей расы.");
        for (String r : f.immuneRaces()) if (hooks.isRace(owner, r)) return fail("Ваша раса не восприимчива.");
        if (!f.exclusive().isEmpty()) for (var e : of(owner).entrySet()) { FormDefs.Form o = defs.forms.get(e.getKey()); if (o != null && o.exclusive().equals(f.exclusive())) return fail("Несовместимо с: " + o.name() + ".");}
        Aff a = new Aff();
        a.since = day;
        if (f.need() != null) a.meter = f.need().max() * 0.8;
        if (f.control() != null) a.control = 0;
        of(owner).put(formId, a);
        refreshStage(owner, f, a);
        log(day, owner, f.name() + ": " + reason);
        hooks.notify(owner, "Вы чувствуете неладное... (" + (f.secret() ? "что-то не так" : f.name()) + ")");
        return true;
    }

    /** Подвергся источнику: укус моба, событие, квест, предмет, рождение. Для каждой подходящей формы бросок шанса. Возвращает заражённые формы. */
    public List<String> expose(String owner, String type, String key, int day) {
        List<String> got = new ArrayList<>();
        for (FormDefs.Form f : defs.forms.values()) {
            for (FormDefs.Source s : f.sources()) {
                if (!s.type().equals(type) || !(s.key().isEmpty() || s.key().equals(key))) continue;
                if (rng.nextDouble() > s.chance()) continue;
                if (infect(owner, f.id(), day, "передано (" + type + (key.isEmpty() ? "" : ":" + key) + ")")) got.add(f.id());
                break;
            }
        }
        return got;
    }

    // ---------- рост и ступени ----------

    private void refreshStage(String owner, FormDefs.Form f, Aff a) {
        int st = 0;
        for (int i = 0; i < f.stages().size(); i++) if (a.progress >= f.stages().get(i).at()) st = i;
        if (st > a.stage) {
            a.stage = st;
            hooks.notify(owner, f.secret() && !a.exposed ? "Что-то меняется в вас." : f.name() + ": " + f.stages().get(st).name());
            if (st == f.stages().size() - 1) hooks.news(f.secret() && !a.exposed ? "" : "Состояние «" + f.name() + "» достигло предела.");
        }
    }

    /** Один игровой день. env - среда (ночь, луна...). Возвращает список событий-строк (для журнала/теста). */
    public List<String> tick(String owner, Env env) {
        List<String> ev = new ArrayList<>();
        Map<String, Aff> m = owners.get(owner);
        if (m == null) return ev;
        for (var e : new ArrayList<>(m.entrySet())) {
            FormDefs.Form f = defs.forms.get(e.getKey());
            Aff a = e.getValue();
            if (f == null) continue;
            // слабости
            a.env.clear();
            for (FormDefs.Weakness w : f.weaknesses()) if (env.has(w.when())) a.env.add(w.when());
            // рост
            if (f.rate() > 0) a.progress += f.rate();
            if (f.levelScale() > 0) a.progress = Math.max(a.progress, hooks.level(owner) * f.levelScale());
            refreshStage(owner, f, a);
            // потребность
            if (f.need() != null && a.meter >= 0) {
                a.meter = Math.max(0, a.meter - f.need().drainPerDay() * (a.active ? 1.5 : 1));
                if (a.meter < f.need().max() * f.need().belowPct() / 100.0) ev.add(f.id() + ":hunger");
            }
            // окончание облика
            if (a.active && a.activeUntil >= 0 && env.day() >= a.activeUntil) { a.active = false; a.voluntary = false; ev.add(f.id() + ":end"); hooks.notify(owner, "Облик отпускает вас."); }
            // триггеры
            if (!a.active) for (FormDefs.Trigger t : f.triggers()) {
                if (a.stage < t.minStage() || !fires(t, f, a, env)) continue;
                if (f.control() != null && resist(f, a)) { ev.add(f.id() + ":resist"); hooks.notify(owner, "Вы удержали себя."); continue; }
                shift(owner, f, a, t.duration() > 0 ? env.day() + t.duration() : -1, env.day(), true);
                ev.add(f.id() + ":shift");
                break;
            } else if (a.activeUntil < 0 && !a.voluntary && !stillTriggered(f, a, env)) { a.active = false; ev.add(f.id() + ":end"); }
        }
        return ev;
    }

    private boolean fires(FormDefs.Trigger t, FormDefs.Form f, Aff a, Env env) {
        boolean cond = switch (t.on()) {
            case "night" -> env.night();
            case "day" -> !env.night();
            case "full_moon" -> env.fullMoon();
            case "rage" -> env.rage();
            case "hunger" -> a.meter >= 0 && f.need() != null && a.meter < f.need().max() * f.need().belowPct() / 100.0;
            case "water" -> env.water();
            case "always" -> true;
            default -> true;           // random
        };
        return cond && rng.nextDouble() < t.chance();
    }

    private boolean stillTriggered(FormDefs.Form f, Aff a, Env env) {
        for (FormDefs.Trigger t : f.triggers()) {
            boolean c = switch (t.on()) { case "night" -> env.night(); case "day" -> !env.night(); case "full_moon" -> env.fullMoon(); case "water" -> env.water(); case "always" -> true; default -> false; };
            if (c && a.stage >= t.minStage()) return true;
        }
        return false;
    }

    private boolean resist(FormDefs.Form f, Aff a) {
        double p = Math.min(0.85, a.control / Math.max(1, f.control().max()) * 0.9);
        if (rng.nextDouble() < p) { a.control = Math.min(f.control().max(), a.control + f.control().gainPerResist()); a.resisted++; return true; }
        return false;
    }

    private void shift(String owner, FormDefs.Form f, Aff a, int until, int day, boolean forced) {
        a.active = true; a.activeUntil = until; a.lastShift = day; a.shifts++; a.voluntary = !forced;
        if (forced && f.control() != null) a.control = Math.max(0, a.control - 1);
        hooks.notify(owner, (forced ? "Вас накрывает: " : "Вы принимаете облик: ") + f.shiftName());
        log(day, owner, f.name() + ": " + (forced ? "превращение" : "добровольное превращение"));
    }

    /** Добровольное превращение: нужно самообладание и ступень. */
    public boolean shift(String owner, String formId, boolean on, int day) {
        FormDefs.Form f = defs.forms.get(formId);
        Aff a = get(owner, formId);
        if (f == null || a == null) return fail("У вас нет этой формы.");
        if (!on) { if (!a.active) return fail("Вы и так в обычном облике."); a.active = false; a.activeUntil = -1; a.voluntary = false; return true; }
        if (a.active) return fail("Вы уже в ином облике.");
        int need = f.triggers().stream().mapToInt(FormDefs.Trigger::minStage).min().orElse(1);
        if (a.stage < Math.max(1, need)) return fail("Форма ещё не созрела.");
        if (f.control() != null && a.control < f.control().voluntaryAt()) return fail("Не хватает самообладания (" + Math.round(a.control) + "/" + Math.round(f.control().voluntaryAt()) + ").");
        if (f.control() == null && f.shiftMods().isEmpty()) return fail("Эта форма не даёт иного облика.");
        if (f.control() != null && day - a.lastShift < f.control().cooldownDays()) return fail("Облик ещё не остыл.");
        shift(owner, f, a, -1, day, false);
        return true;
    }

    /** Питание потребности (кровь, плоть). Возвращает новое значение или -1. */
    public double feed(String owner, String formId, double amount) {
        FormDefs.Form f = defs.forms.get(formId);
        Aff a = get(owner, formId);
        if (f == null || a == null || f.need() == null) { lastError = "Эта форма ничего не требует."; return -1; }
        a.meter = Math.min(f.need().max(), Math.max(0, a.meter) + amount);
        return a.meter;
    }

    public void expose(String owner, String formId) { Aff a = get(owner, formId); if (a != null) a.exposed = true; }

    // ---------- лечение ----------

    public String cureOptions(String owner, String formId) {
        FormDefs.Form f = defs.forms.get(formId);
        Aff a = get(owner, formId);
        if (f == null || a == null) return "";
        if (a.stage >= f.incurableFrom()) return "Слишком поздно: форма необратима.";
        StringBuilder sb = new StringBuilder();
        for (FormDefs.Cure c : f.cures()) if (a.stage <= c.maxStage()) sb.append(c.name()).append(c.item().isEmpty() ? "" : " (" + c.item() + " x" + c.count() + ")").append(c.gold() > 0 ? " " + c.gold() + " зол." : "").append("; ");
        return sb.length() == 0 ? "Лекарства нет." : sb.toString();
    }

    public boolean cure(String owner, String formId, String cureId, int day) {
        FormDefs.Form f = defs.forms.get(formId);
        Aff a = get(owner, formId);
        if (f == null || a == null) return fail("У вас нет этой формы.");
        if (a.stage >= f.incurableFrom()) return fail("Слишком поздно: форма необратима.");
        FormDefs.Cure c = f.cures().stream().filter(x -> x.id().equals(cureId)).findFirst().orElse(null);
        if (c == null) return fail("Такого лечения нет.");
        if (a.stage > c.maxStage()) return fail("Это средство уже не поможет.");
        if (!c.flag().isEmpty() && !hooks.flag(owner, c.flag())) return fail("Нужно условие: " + c.flag() + ".");
        if (!c.item().isEmpty() && hooks.count(owner, c.item()) < c.count()) return fail("Нужно: " + c.item() + " x" + c.count() + ".");
        if (c.gold() > 0 && hooks.gold(owner) < c.gold()) return fail("Нужно золота: " + c.gold() + ".");
        if (!c.item().isEmpty()) hooks.take(owner, c.item(), c.count());
        if (c.gold() > 0) hooks.takeGold(owner, c.gold());
        of(owner).remove(formId);
        log(day, owner, f.name() + ": излечение (" + c.name() + ")");
        hooks.notify(owner, "Вы избавились от состояния «" + f.name() + "».");
        return true;
    }

    /** Смерть: излечивает формы, у которых нет «бессмертия» (нежить остаётся), сбрасывает облики. */
    public void onDeath(String owner) {
        Map<String, Aff> m = owners.get(owner);
        if (m == null) return;
        for (Aff a : m.values()) { a.active = false; a.activeUntil = -1; a.voluntary = false; }
    }

    // ---------- поправки и метки ----------

    public Map<String, Double> mods(String owner) {
        Map<String, Double> out = new LinkedHashMap<>();
        Map<String, Aff> m = owners.get(owner);
        if (m == null) return out;
        for (var e : m.entrySet()) {
            FormDefs.Form f = defs.forms.get(e.getKey());
            Aff a = e.getValue();
            if (f == null) continue;
            f.stages().get(a.stage).mods().forEach((k, v) -> out.merge(k, v, Double::sum));
            if (a.active) f.shiftMods().forEach((k, v) -> out.merge(k, v, Double::sum));
            for (FormDefs.Weakness w : f.weaknesses()) if (a.env.contains(w.when())) w.mods().forEach((k, v) -> out.merge(k, v, Double::sum));
            if (f.need() != null && a.meter >= 0 && a.meter < f.need().max() * f.need().belowPct() / 100.0) f.need().starve().forEach((k, v) -> out.merge(k, v, Double::sum));
            if (!f.secret() || a.exposed) { if (f.opinion() != 0 && a.stage >= f.flagStage()) out.merge("opinion", (double) f.opinion(), Double::sum); }
        }
        return out;
    }

    /** Метки: form:<id> при ступени не ниже flag_stage; form:<id>:active в превращённом виде. */
    public boolean flag(String owner, String flag) {
        if (!flag.startsWith("form:")) return false;
        String[] p = flag.substring(5).split(":");
        FormDefs.Form f = defs.forms.get(p[0]);
        Aff a = get(owner, p[0]);
        if (f == null || a == null) return false;
        if (p.length > 1 && p[1].equals("active")) return a.active;
        return a.stage >= f.flagStage();
    }

    public void publish() {
        StatApi.register("forms", this::mods);
        StatApi.registerTags("forms", o -> {
            Map<String, String> t = new LinkedHashMap<>();
            Map<String, Aff> m = owners.get(o);
            if (m == null) return t;
            List<String> ids = new ArrayList<>();
            for (var e : m.entrySet()) { FormDefs.Form f = defs.forms.get(e.getKey()); if (f != null && (!f.secret() || e.getValue().exposed)) ids.add(e.getKey()); }
            if (!ids.isEmpty()) t.put("form", String.join(",", ids));
            for (var e : m.entrySet()) if (e.getValue().active) { t.put("shape", e.getKey()); break; }
            return t;
        });
    }

    private void log(int day, String owner, String text) { chronicle.add("День " + day + ": " + text); if (chronicle.size() > 100) chronicle.remove(0); }

    // ---------- вид и сохранение ----------

    public String viewJson(String owner) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> l = new ArrayList<>();
        for (var e : of(owner).entrySet()) {
            FormDefs.Form f = defs.forms.get(e.getKey());
            Aff a = e.getValue();
            if (f == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.id()); m.put("name", f.name()); m.put("kind", f.kind()); m.put("stage", a.stage); m.put("stageName", f.stages().get(a.stage).name());
            m.put("stages", f.stages().stream().map(s -> s.name()).toList());
            m.put("desc", f.stages().get(a.stage).desc().isEmpty() ? f.desc() : f.stages().get(a.stage).desc());
            m.put("active", a.active); m.put("shiftName", f.shiftName());
            double next = a.stage + 1 < f.stages().size() ? f.stages().get(a.stage + 1).at() : a.progress;
            double prev = f.stages().get(a.stage).at();
            m.put("frac", a.stage + 1 < f.stages().size() ? Math.max(0, Math.min(1, (a.progress - prev) / Math.max(1, next - prev))) : 1.0);
            if (f.need() != null) { m.put("need", f.need().name()); m.put("needFrac", Math.max(0, a.meter) / f.need().max()); m.put("starving", a.meter >= 0 && a.meter < f.need().max() * f.need().belowPct() / 100.0); }
            if (f.control() != null) { m.put("control", Math.round(a.control)); m.put("controlMax", Math.round(f.control().max())); m.put("voluntary", Math.round(f.control().voluntaryAt())); }
            m.put("weak", new ArrayList<Object>(a.env));
            m.put("cure", cureOptions(owner, f.id()));
            Map<String, Object> md = new LinkedHashMap<>();
            f.stages().get(a.stage).mods().forEach(md::put);
            m.put("mods", md);
            l.add(m);
        }
        root.put("forms", l);
        root.put("chronicle", new ArrayList<Object>(chronicle.subList(Math.max(0, chronicle.size() - 15), chronicle.size())));
        return Json.write(root);
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        for (var e : owners.entrySet()) {
            Map<String, Object> om = new LinkedHashMap<>();
            for (var x : e.getValue().entrySet()) {
                Aff a = x.getValue();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("p", a.progress); m.put("meter", a.meter); m.put("c", a.control); m.put("stage", a.stage); m.put("since", a.since); m.put("until", a.activeUntil); m.put("last", a.lastShift);
                m.put("active", a.active); m.put("vol", a.voluntary); m.put("exposed", a.exposed); m.put("shifts", a.shifts); m.put("res", a.resisted); m.put("env", new ArrayList<Object>(a.env));
                om.put(x.getKey(), m);
            }
            if (!om.isEmpty()) root.put(e.getKey(), om);
        }
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("owners", root); top.put("chronicle", new ArrayList<Object>(chronicle));
        return Json.write(top);
    }

    public void fromJson(String json) {
        owners.clear(); chronicle.clear();
        if (!(Json.parse(json) instanceof Map<?, ?> top)) return;
        if (top.get("chronicle") instanceof List<?> c) for (Object o : c) chronicle.add(String.valueOf(o));
        if (!(top.get("owners") instanceof Map<?, ?> root)) return;
        for (var e : root.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> om)) continue;
            for (var x : om.entrySet()) {
                if (!(x.getValue() instanceof Map<?, ?> m)) continue;
                Aff a = new Aff();
                a.progress = d(m.get("p")); a.meter = d(m.get("meter")); a.control = d(m.get("c")); a.stage = (int) d(m.get("stage")); a.since = (int) d(m.get("since")); a.activeUntil = (int) d(m.get("until")); a.lastShift = (int) d(m.get("last"));
                a.active = Boolean.TRUE.equals(m.get("active")); a.exposed = Boolean.TRUE.equals(m.get("exposed")); a.voluntary = Boolean.TRUE.equals(m.get("vol")); a.shifts = (int) d(m.get("shifts")); a.resisted = (int) d(m.get("res"));
                if (m.get("env") instanceof List<?> l) for (Object o : l) a.env.add(String.valueOf(o));
                of(String.valueOf(e.getKey())).put(String.valueOf(x.getKey()), a);
            }
        }
    }

    private static double d(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
}
