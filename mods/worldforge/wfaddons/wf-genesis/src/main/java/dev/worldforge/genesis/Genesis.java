package dev.worldforge.genesis;

import dev.worldforge.mod.Json;
import dev.worldforge.origins.OriginDefs;
import dev.worldforge.progress.ProgressDefs;

import java.util.*;

/**
 * Мастер создания персонажа (чистая логика, без Minecraft): раса, внешность, старт, поселение правителя, профессии, атрибуты, класс и навыки.
 * Каждый шаг проверяется здесь; сервер лишь применяет готовый черновик. Возвращаемая строка - причина отказа, пустая - успех.
 */
public final class Genesis {
    public final GenesisDefs defs;
    public final OriginDefs origins;
    public final ProgressDefs progress;
    private final Map<String, Draft> drafts = new LinkedHashMap<>();
    /** Завершённые персонажи: владелец -> облик (для рендера у всех игроков). */
    private final Map<String, Draft> finished = new LinkedHashMap<>();

    public Genesis(GenesisDefs defs, OriginDefs origins, ProgressDefs progress) { this.defs = defs; this.origins = origins; this.progress = progress; }

    public Draft draft(String owner) { return drafts.computeIfAbsent(owner, Draft::new); }

    public Draft finished(String owner) { return finished.get(owner); }

    public boolean isDone(String owner) { return finished.containsKey(owner); }

    public void discard(String owner) { drafts.remove(owner); }

    // ------------------------------------------------------------------ вспомогательное

    private List<String> chainLeafFirst(String race) {
        List<String> l = new ArrayList<>();
        for (OriginDefs.Race r : origins.raceChain(race)) l.add(0, r.id());
        return l;
    }

    public String raceName(String id) { OriginDefs.Race r = origins.races.get(id); return r == null ? id : r.name(); }

    /** Значение атрибута в черновике: база + вложенное. */
    public int attrValue(Draft d, String id) { ProgressDefs.Attr a = progress.attrs.get(id); return a == null ? 0 : a.base() + d.attr(id); }

    /** Цена n-го вложенного очка (1-based): до мягкого предела 1, дальше 2. */
    public int pointCost(int n) { return n <= defs.attrSoftCap ? 1 : 2; }

    public int spent(Draft d) { int t = 0; for (int v : d.attrs.values()) for (int i = 1; i <= v; i++) t += pointCost(i); return t; }

    public int budget(Draft d) { GenesisDefs.Start s = defs.starts.get(d.start); return defs.attrBudget + (s == null ? 0 : s.attrBonus()); }

    public int attrLeft(Draft d) { return budget(d) - spent(d); }

    public int skillBudget(Draft d) { GenesisDefs.Start s = defs.starts.get(d.start); return defs.skillPoints + (s == null ? 0 : s.skillBonus()); }

    public int skillSpent(Draft d) {
        int t = 0;
        for (var e : d.skills.entrySet()) { ProgressDefs.Skill s = progress.skills.get(e.getKey()); if (s == null) continue; for (int r = 0; r < e.getValue() && r < s.cost().size(); r++) t += s.cost().get(r); }
        return t;
    }

    public int skillLeft(Draft d) { return skillBudget(d) - skillSpent(d); }

    // ------------------------------------------------------------------ раса

    public String setRace(Draft d, String id) {
        if (!origins.races.containsKey(id)) return "Нет такой расы.";
        d.race = id;
        d.look.clear();
        d.look.putAll(defaultLook(id));
        d.features.removeIf(f -> !featureAllowed(f, id));
        GenesisDefs.Start s = defs.starts.get(d.start);
        if (s != null && !startAllowed(s, id)) { d.start = ""; d.focus = ""; d.settleName = ""; }
        else if (s != null && s.leader() && d.settleName.isEmpty()) d.settleName = randomName(d, new Random());
        d.profs.removeIf(p -> profBlocked(d, p) != null && profRaceBlocked(d, p));
        if (!d.cls.isEmpty()) { OriginDefs.ClassDef c = origins.classes.get(d.cls); if (c == null || !origins.raceOk(id, c.reqRaces())) d.cls = ""; }
        return "";
    }

    private boolean profRaceBlocked(Draft d, String p) { OriginDefs.Profession pr = origins.professions.get(p); return pr == null || !origins.raceOk(d.race, pr.reqRaces()); }

    /** Внешность расы по умолчанию: ползунки и цвета (цепочка от подрасы к корню, подраса перекрывает родителя). */
    public Map<String, String> defaultLook(String race) {
        Map<String, String> look = new LinkedHashMap<>();
        for (GenesisDefs.Slider s : defs.sliders) look.put(s.id(), String.valueOf(s.def()));
        for (String k : GenesisDefs.COLOR_KEYS) { List<Integer> p = defs.palettes.get(k); if (p != null && !p.isEmpty()) look.put(k, Colors.hex(p.get(0))); }
        if (!defs.hairStyles.isEmpty()) look.put("hair_style", defs.hairStyles.get(0)[0]);
        List<String> chain = chainLeafFirst(race);
        Collections.reverse(chain);
        for (String r : chain) { Map<String, Object> m = defs.raceLook.get(r); if (m != null) for (var e : m.entrySet()) look.put(e.getKey(), String.valueOf(e.getValue() instanceof Number n && !(e.getValue() instanceof Double) ? n.intValue() : e.getValue() instanceof Number n2 ? (int) n2.doubleValue() : e.getValue())); }
        return look;
    }

    private boolean featureAllowed(String fid, String race) {
        GenesisDefs.Feature f = defs.features.get(fid);
        return f != null && origins.raceOk(race, f.races());
    }

    // ------------------------------------------------------------------ внешность

    public String setLook(Draft d, String key, String value) {
        if (d.race.isEmpty()) return "Сначала выберите расу.";
        GenesisDefs.Slider s = defs.slider(key);
        if (s != null) {
            int v;
            try { v = Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return "Нужно число."; }
            if (v < s.min() || v > s.max()) return s.name() + ": от " + s.min() + " до " + s.max() + ".";
            d.look.put(key, String.valueOf(v));
            return "";
        }
        if (GenesisDefs.COLOR_KEYS.contains(key)) {
            if (!Colors.valid(value)) return "Цвет вида #rrggbb.";
            d.look.put(key, value.toLowerCase(Locale.ROOT));
            return "";
        }
        if (key.equals("hair_style")) {
            for (String[] h : defs.hairStyles) if (h[0].equals(value)) { d.look.put(key, value); return ""; }
            return "Нет такой причёски.";
        }
        return "Неизвестный параметр внешности: " + key;
    }

    /** Сдвиг цвета: key - skin/hair/eyes. */
    public String shiftColor(Draft d, String key, int dh, int ds, int db) {
        if (!GenesisDefs.COLOR_KEYS.contains(key)) return "Неизвестный цвет.";
        String cur = d.look.get(key);
        if (cur == null) return "Сначала выберите расу.";
        d.look.put(key, Colors.shift(cur, Math.max(-180, Math.min(180, dh)), Math.max(-100, Math.min(100, ds)), Math.max(-100, Math.min(100, db))));
        return "";
    }

    public String toggleFeature(Draft d, String id) {
        GenesisDefs.Feature f = defs.features.get(id);
        if (f == null) return "Нет такой особенности.";
        if (d.race.isEmpty()) return "Сначала выберите расу.";
        if (!featureAllowed(id, d.race)) return "Эта особенность не для вашей расы.";
        if (d.features.remove(id)) return "";
        d.features.removeIf(x -> { GenesisDefs.Feature o = defs.features.get(x); return o != null && o.group().equals(f.group()); });
        d.features.add(id);
        return "";
    }

    // ------------------------------------------------------------------ старт

    public boolean startAllowed(GenesisDefs.Start s, String race) {
        if (!s.races().isEmpty() && !origins.raceOk(race, s.races())) return false;
        for (String r : s.notRaces()) if (origins.isRace(race, r)) return false;
        return true;
    }

    public String settleNoun(Draft d, String tier) { return defs.style(chainLeafFirst(d.race), tier, defs.tiers.containsKey(tier) ? defs.tiers.get(tier).name() : tier); }

    public String rulerTitle(Draft d, String tier) { return defs.style(chainLeafFirst(d.race), "ruler_" + tier, "Правитель"); }

    public String randomName(Draft d, Random rng) {
        List<String> pool = null;
        for (String r : chainLeafFirst(d.race)) if (defs.namePool.containsKey(r) && !defs.namePool.get(r).isEmpty()) { pool = defs.namePool.get(r); break; }
        if (pool == null) pool = defs.namePool.getOrDefault("_default", List.of("Новый Дом"));
        return pool.get(rng.nextInt(pool.size()));
    }

    public String setStart(Draft d, String id) {
        GenesisDefs.Start s = defs.starts.get(id);
        if (s == null) return "Нет такого старта.";
        if (d.race.isEmpty()) return "Сначала выберите расу.";
        if (!startAllowed(s, d.race)) return "Этот старт недоступен вашей расе.";
        d.start = id;
        if (s.leader()) {
            if (d.settleName.isEmpty()) d.settleName = randomName(d, new Random());
            if (d.focus.isEmpty() || !defs.focus.containsKey(d.focus)) d.focus = defs.focus.isEmpty() ? "" : defs.focus.keySet().iterator().next();
        }
        return "";
    }

    public String setSettle(Draft d, String name) {
        name = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) return "Название не может быть пустым.";
        if (name.length() > defs.nameMax) return "Название длиннее " + defs.nameMax + " знаков.";
        if (!name.matches("[\\p{L}\\p{N} '\\-]+")) return "Только буквы, цифры, пробел, дефис и апостроф.";
        d.settleName = name;
        return "";
    }

    public String setFocus(Draft d, String id) { if (!defs.focus.containsKey(id)) return "Нет такого направления."; d.focus = id; return ""; }

    public String setTax(Draft d, int level) { if (level < 0 || level > 3) return "Налог: 0..3."; d.tax = level; return ""; }

    // ------------------------------------------------------------------ профессии

    /** Список заметок о профессии для игрока: пусто - всё подходит. Требования атрибутов можно выполнить позже на шаге атрибутов, поэтому они только предупреждают. */
    public String profBlocked(Draft d, String id) {
        OriginDefs.Profession p = origins.professions.get(id);
        if (p == null) return "Нет такой профессии.";
        if (!p.from().isEmpty()) return "Специализации открываются в игре.";
        if (!origins.raceOk(d.race, p.reqRaces())) return "Не для вашей расы.";
        if (!p.reqClasses().isEmpty()) return "Требует класса, доступного позже.";
        for (String x : d.profs) { OriginDefs.Profession o = origins.professions.get(x); if (o != null && !o.exclusive().isEmpty() && o.exclusive().equals(p.exclusive()) && !x.equals(id)) return "Несовместимо с: " + o.name() + ".";}
        return null;
    }

    /** Требования атрибутов профессии, которые пока не выполнены (для предупреждения). */
    public List<String> profLacks(Draft d, String id) {
        List<String> l = new ArrayList<>();
        OriginDefs.Profession p = origins.professions.get(id);
        if (p == null) return l;
        for (var e : p.reqStat().entrySet()) if (attrValue(d, e.getKey()) < e.getValue()) l.add(attrName(e.getKey()) + " " + e.getValue());
        return l;
    }

    private String attrName(String id) { ProgressDefs.Attr a = progress.attrs.get(id); return a == null ? id : a.name(); }

    public String toggleProf(Draft d, String id) {
        if (d.race.isEmpty()) return "Сначала выберите расу.";
        if (d.profs.remove(id)) return "";
        String why = profBlocked(d, id);
        if (why != null) return why;
        if (d.profs.size() >= defs.profPicks) return "Можно взять не более " + defs.profPicks + " профессий.";
        d.profs.add(id);
        return "";
    }

    // ------------------------------------------------------------------ атрибуты

    /** Меняет вложенные очки на n (положительное или отрицательное). */
    public String adjustAttr(Draft d, String id, int n) {
        if (!progress.attrs.containsKey(id)) return "Нет такого атрибута.";
        int cur = d.attr(id), nxt = cur + n;
        if (nxt < 0) return "Ниже базового значения нельзя.";
        if (nxt > defs.attrMaxDelta) return "Не больше +" + defs.attrMaxDelta + " на старте.";
        Draft t = new Draft(d.owner); t.attrs.putAll(d.attrs); t.start = d.start;
        if (nxt == 0) t.attrs.remove(id); else t.attrs.put(id, nxt);
        if (spent(t) > budget(d)) return "Не хватает очков.";
        if (nxt == 0) d.attrs.remove(id); else d.attrs.put(id, nxt);
        return "";
    }

    public String resetAttrs(Draft d) { d.attrs.clear(); return ""; }

    // ------------------------------------------------------------------ класс и навыки

    /** Причина, по которой стартовый класс недоступен, или null. */
    public String classBlocked(Draft d, OriginDefs.ClassDef c) {
        if (!c.from().isEmpty()) return "Открывается в игре.";
        if (c.minLevel() > 1) return "Нужен уровень " + c.minLevel() + ".";
        if (!origins.raceOk(d.race, c.reqRaces())) return "Только для: " + String.join(", ", c.reqRaces()) + ".";
        for (var e : c.reqStat().entrySet()) if (attrValue(d, e.getKey()) < e.getValue()) return "Нужно: " + attrName(e.getKey()) + " " + e.getValue() + ".";
        if (!c.reqFlags().isEmpty()) return "Нужно выполнить: " + String.join(", ", c.reqFlags()) + ".";
        for (var e : c.reqProf().entrySet()) if (!d.profs.contains(e.getKey()) || e.getValue() > 0) return "Нужна профессия «" + (origins.professions.containsKey(e.getKey()) ? origins.professions.get(e.getKey()).name() : e.getKey()) + "».";
        return null;
    }

    public String pickClass(Draft d, String id) {
        OriginDefs.ClassDef c = origins.classes.get(id);
        if (c == null) return "Нет такого класса.";
        if (d.race.isEmpty()) return "Сначала выберите расу.";
        String why = classBlocked(d, c);
        if (why != null) return why;
        d.cls = id;
        return "";
    }

    /** Навык можно взять на стартовых очках: требования атрибутов - по значениям черновика; уровень 1; требования других навыков - по черновику. */
    public String skillBlocked(Draft d, String id, int wantRank) {
        ProgressDefs.Skill s = progress.skills.get(id);
        if (s == null) return "Нет такого навыка.";
        if (s.minLevel() > 1) return "Нужен уровень " + s.minLevel() + ".";
        for (var e : s.reqAttr().entrySet()) if (attrValue(d, e.getKey()) < e.getValue()) return "Нужно: " + attrName(e.getKey()) + " " + e.getValue() + ".";
        for (var e : s.reqSkill().entrySet()) if (d.skills.getOrDefault(e.getKey(), 0) < e.getValue()) return "Нужно: " + (progress.skills.containsKey(e.getKey()) ? progress.skills.get(e.getKey()).name() : e.getKey()) + " " + e.getValue() + ".";
        if (wantRank > s.max()) return "Навык на максимуме.";
        return null;
    }

    public String adjustSkill(Draft d, String id, int n) {
        ProgressDefs.Skill s = progress.skills.get(id);
        if (s == null) return "Нет такого навыка.";
        int cur = d.skills.getOrDefault(id, 0), nxt = cur + n;
        if (nxt < 0) return "Нечего снимать.";
        if (n > 0) {
            String why = skillBlocked(d, id, nxt);
            if (why != null) return why;
            if (s.cost().get(cur) > skillLeft(d)) return "Не хватает очков навыков (нужно " + s.cost().get(cur) + ").";
            d.skills.put(id, nxt);
        } else {
            if (nxt == 0) d.skills.remove(id); else d.skills.put(id, nxt);
            // навыки, зависящие от снятого, тоже снимаются
            boolean again = true;
            while (again) {
                again = false;
                for (var e : new ArrayList<>(d.skills.entrySet())) {
                    ProgressDefs.Skill o = progress.skills.get(e.getKey());
                    if (o != null && skillBlocked(d, e.getKey(), 0) != null) { d.skills.remove(e.getKey()); again = true; }
                }
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ итог

    /** Что мешает завершить мастер. Пусто - можно подтверждать. */
    public List<String> problems(Draft d) {
        List<String> p = new ArrayList<>();
        if (d.race.isEmpty()) p.add("Не выбрана раса.");
        GenesisDefs.Start s = defs.starts.get(d.start);
        if (s == null) p.add("Не выбрано происхождение.");
        else {
            if (!startAllowed(s, d.race)) p.add("Старт «" + s.name() + "» недоступен вашей расе.");
            if (s.leader()) {
                if (setSettleCheck(d.settleName) != null) p.add(setSettleCheck(d.settleName));
                if (!defs.focus.isEmpty() && !defs.focus.containsKey(d.focus)) p.add("Не выбрано направление поселения.");
            }
        }
        if (d.profs.isEmpty()) p.add("Выберите хотя бы одну профессию.");
        for (String id : d.profs) {
            String why = profBlocked(d, id);
            if (why != null && !origins.professions.containsKey(id)) p.add("Профессия " + id + ": " + why);
            else if (origins.professions.containsKey(id) && !origins.raceOk(d.race, origins.professions.get(id).reqRaces())) p.add("Профессия «" + origins.professions.get(id).name() + "» не для вашей расы.");
            for (String lack : profLacks(d, id)) p.add("Профессия «" + origins.professions.get(id).name() + "»: нужно " + lack + " (повысьте атрибут).");
        }
        if (d.cls.isEmpty()) p.add("Не выбран класс.");
        else { OriginDefs.ClassDef c = origins.classes.get(d.cls); String why = c == null ? "нет класса" : classBlocked(d, c); if (why != null) p.add("Класс: " + why); }
        if (attrLeft(d) < 0) p.add("Потрачено больше очков атрибутов, чем есть.");
        if (skillLeft(d) < 0) p.add("Потрачено больше очков навыков, чем есть.");
        return p;
    }

    private String setSettleCheck(String name) {
        Draft t = new Draft("x");
        return null == name || name.isBlank() ? "Дайте название поселению." : name.length() > defs.nameMax ? "Название слишком длинное." : null;
    }

    /** Подтверждает черновик: помечает завершённым. Применение к игроку делает сервер. */
    public String confirm(Draft d) {
        List<String> p = problems(d);
        if (!p.isEmpty()) return p.get(0);
        d.done = true;
        finished.put(d.owner, d);
        return "";
    }

    /** Случайный допустимый персонаж: раса, внешность, старт, профессии, очки и класс. */
    public void randomize(Draft d, Random rng) {
        List<OriginDefs.Race> top = new ArrayList<>(origins.races.values());
        top.removeIf(r -> !r.parent().isEmpty());
        if (d.race.isEmpty()) {
            int tot = 0; for (OriginDefs.Race r : top) tot += Math.max(1, r.weight());
            int k = rng.nextInt(Math.max(1, tot));
            String pick = top.isEmpty() ? "" : top.get(0).id();
            for (OriginDefs.Race r : top) { k -= Math.max(1, r.weight()); if (k < 0) { pick = r.id(); break; } }
            List<OriginDefs.Race> subs = new ArrayList<>();
            for (OriginDefs.Race r : origins.races.values()) if (r.parent().equals(pick)) subs.add(r);
            if (!subs.isEmpty() && rng.nextInt(3) > 0) pick = subs.get(rng.nextInt(subs.size())).id();
            setRace(d, pick);
        }
        for (GenesisDefs.Slider s : defs.sliders) d.look.put(s.id(), String.valueOf(s.min() + rng.nextInt(s.max() - s.min() + 1)));
        for (String k : GenesisDefs.COLOR_KEYS) if (d.look.containsKey(k)) d.look.put(k, Colors.shift(d.look.get(k), rng.nextInt(61) - 30, rng.nextInt(31) - 15, rng.nextInt(31) - 15));
        if (!defs.hairStyles.isEmpty()) d.look.put("hair_style", defs.hairStyles.get(rng.nextInt(defs.hairStyles.size()))[0]);
        if (d.start.isEmpty()) {
            List<GenesisDefs.Start> ok = new ArrayList<>();
            for (GenesisDefs.Start s : defs.starts.values()) if (startAllowed(s, d.race) && !s.leader() && !s.wanted()) ok.add(s);
            if (!ok.isEmpty()) setStart(d, ok.get(rng.nextInt(ok.size())).id());
        }
        d.profs.clear();
        List<String> roots = new ArrayList<>();
        for (OriginDefs.Profession p : origins.professions.values()) if (profBlocked(d, p.id()) == null && p.reqStat().isEmpty()) roots.add(p.id());
        Collections.shuffle(roots, rng);
        for (int i = 0; i < roots.size() && d.profs.size() < Math.min(defs.profPicks, 2); i++) toggleProf(d, roots.get(i));
        d.attrs.clear();
        List<String> ids = new ArrayList<>(progress.attrs.keySet());
        for (int guard = 0; guard < 400 && attrLeft(d) > 0; guard++) adjustAttr(d, ids.get(rng.nextInt(ids.size())), 1);
        List<OriginDefs.ClassDef> cs = new ArrayList<>();
        for (OriginDefs.ClassDef c : origins.classes.values()) if (classBlocked(d, c) == null) cs.add(c);
        if (!cs.isEmpty()) d.cls = cs.get(rng.nextInt(cs.size())).id();
        d.skills.clear();
        List<String> sk = new ArrayList<>(progress.skills.keySet());
        Collections.shuffle(sk, rng);
        for (String id : sk) { if (skillLeft(d) <= 0) break; adjustSkill(d, id, 1); }
    }

    // ------------------------------------------------------------------ предпросмотр

    /** Итоговые характеристики: база + атрибуты + раса + старт + особенности внешности (без навыков и узлов). */
    public Map<String, Double> preview(Draft d) {
        Map<String, Double> f = new LinkedHashMap<>();
        for (ProgressDefs.Stat s : progress.stats.values()) f.merge(s.id(), s.base(), Double::sum);
        for (ProgressDefs.Attr a : progress.attrs.values()) for (var e : a.perPoint().entrySet()) f.merge(e.getKey(), e.getValue() * (a.base() + d.attr(a.id())), Double::sum);
        for (OriginDefs.Race r : origins.raceChain(d.race)) for (var e : r.mods().entrySet()) f.merge(e.getKey(), e.getValue(), Double::sum);
        GenesisDefs.Start s = defs.starts.get(d.start);
        if (s != null) for (var e : s.mods().entrySet()) f.merge(e.getKey(), e.getValue(), Double::sum);
        for (String id : d.features) { GenesisDefs.Feature ft = defs.features.get(id); if (ft != null) for (var e : ft.mods().entrySet()) f.merge(e.getKey(), e.getValue(), Double::sum); }
        for (var e : d.skills.entrySet()) { ProgressDefs.Skill sk = progress.skills.get(e.getKey()); if (sk != null) for (var m : sk.perRank().entrySet()) f.merge(m.getKey(), m.getValue() * e.getValue(), Double::sum); }
        for (String id : d.profs) { OriginDefs.Profession p = origins.professions.get(id); if (p != null && !p.ranks().isEmpty()) for (var e : p.ranks().get(0).mods().entrySet()) f.merge(e.getKey(), e.getValue(), Double::sum); }
        OriginDefs.ClassDef c = origins.classes.get(d.cls);
        if (c != null) for (var e : c.mods().entrySet()) f.merge(e.getKey(), e.getValue(), Double::sum);
        Map<String, Double> out = new LinkedHashMap<>();
        for (var e : f.entrySet()) {
            if (e.getKey().endsWith("_pct")) { out.put(e.getKey(), round(e.getValue())); continue; }
            out.put(e.getKey(), round(e.getValue() * (1 + f.getOrDefault(e.getKey() + "_pct", 0.0) / 100.0)));
        }
        return out;
    }

    private static double round(double v) { return Math.round(v * 100) / 100.0; }

    // ------------------------------------------------------------------ вид для окна

    private static Map<String, Object> obj(Object... kv) { Map<String, Object> m = new LinkedHashMap<>(); for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]); return m; }

    private List<Object> modList(Map<String, Double> mods) { List<Object> l = new ArrayList<>(); for (var e : mods.entrySet()) l.add(obj("k", e.getKey(), "v", e.getValue())); return l; }

    public String viewJson(Draft d) {
        Map<String, Object> m = obj();
        m.put("race", d.race); m.put("start", d.start); m.put("cls", d.cls); m.put("settle", d.settleName); m.put("focus", d.focus); m.put("tax", d.tax); m.put("raids", d.raids); m.put("auto", d.auto);
        m.put("attrLeft", attrLeft(d)); m.put("attrBudget", budget(d)); m.put("skillLeft", skillLeft(d)); m.put("skillBudget", skillBudget(d));
        m.put("profPicks", defs.profPicks); m.put("maxDelta", defs.attrMaxDelta); m.put("softCap", defs.attrSoftCap); m.put("nameMax", defs.nameMax);
        List<Object> races = new ArrayList<>();
        for (OriginDefs.Race r : origins.races.values()) {
            List<Object> perks = new ArrayList<>();
            for (var e : origins.perksOf(r.id()).entrySet()) perks.add(obj("lv", e.getKey(), "name", origins.perkName(r.id(), e.getKey()), "mods", modList(e.getValue())));
            races.add(obj("id", r.id(), "name", r.name(), "desc", r.desc(), "color", r.color(), "parent", r.parent(), "mods", modList(r.mods()), "innate", new LinkedHashMap<String, Object>(r.innate()), "perks", perks));
        }
        m.put("races", races);
        Map<String, Object> look = new LinkedHashMap<>(d.look);
        m.put("look", look);
        List<Object> sl = new ArrayList<>();
        for (GenesisDefs.Slider s : defs.sliders) sl.add(obj("id", s.id(), "name", s.name(), "desc", s.desc(), "min", s.min(), "max", s.max(), "def", s.def(), "unit", s.unit()));
        m.put("sliders", sl);
        Map<String, Object> pal = new LinkedHashMap<>();
        for (var e : defs.palettes.entrySet()) { List<Object> l = new ArrayList<>(); for (int c : e.getValue()) l.add(Colors.hex(c)); pal.put(e.getKey(), l); }
        m.put("palettes", pal);
        List<Object> hs = new ArrayList<>();
        for (String[] h : defs.hairStyles) hs.add(obj("id", h[0], "name", h[1]));
        m.put("hairStyles", hs);
        List<Object> fe = new ArrayList<>();
        for (GenesisDefs.Feature f : defs.features.values()) {
            if (!d.race.isEmpty() && !featureAllowed(f.id(), d.race)) continue;
            fe.add(obj("id", f.id(), "name", f.name(), "desc", f.desc(), "group", f.group(), "on", d.features.contains(f.id()), "mods", modList(f.mods())));
        }
        m.put("features", fe);
        List<Object> st = new ArrayList<>();
        for (GenesisDefs.Start s : defs.starts.values()) {
            boolean ok = d.race.isEmpty() || startAllowed(s, d.race);
            Map<String, Object> o = obj("id", s.id(), "name", s.name(), "desc", s.desc(), "kind", s.kind(), "tier", s.tier(), "ok", ok, "panel", s.panel(), "gold", s.gold(), "wanted", s.wanted(),
                    "attrBonus", s.attrBonus(), "skillBonus", s.skillBonus(), "items", new LinkedHashMap<String, Object>(s.items()), "mods", modList(s.mods()), "spawn", s.spawn());
            if (s.leader() && !d.race.isEmpty()) { o.put("noun", settleNoun(d, s.tier())); o.put("title", rulerTitle(d, s.tier())); }
            st.add(o);
        }
        m.put("starts", st);
        List<Object> fo = new ArrayList<>();
        for (GenesisDefs.Focus f : defs.focus.values()) fo.add(obj("id", f.id(), "name", f.name(), "desc", f.desc(), "stock", new LinkedHashMap<String, Object>(f.stock())));
        m.put("focusList", fo);
        GenesisDefs.Start cur = defs.starts.get(d.start);
        if (cur != null && cur.leader()) {
            GenesisDefs.Tier t = defs.tiers.get(cur.tier());
            if (t != null) m.put("tier", obj("id", t.id(), "name", t.name(), "workers", t.workers(), "stock", new LinkedHashMap<String, Object>(t.stock()), "buildings", new ArrayList<Object>(t.buildings())));
        }
        List<Object> pr = new ArrayList<>();
        for (OriginDefs.Profession p : origins.professions.values()) {
            if (!p.from().isEmpty()) continue;
            String why = d.profs.contains(p.id()) ? null : profBlocked(d, p.id());
            List<Object> lacks = new ArrayList<>(profLacks(d, p.id()));
            pr.add(obj("id", p.id(), "name", p.name(), "desc", p.desc(), "group", p.group(), "on", d.profs.contains(p.id()), "why", why == null ? "" : why, "lacks", lacks,
                    "rank0", p.ranks().get(0).name(), "mods", modList(p.ranks().get(0).mods()), "wage", p.wage()));
        }
        m.put("profs", pr);
        List<Object> at = new ArrayList<>();
        for (ProgressDefs.Attr a : progress.attrs.values()) at.add(obj("id", a.id(), "name", a.name(), "group", a.group(), "desc", a.desc(), "color", a.color(), "base", a.base(), "delta", d.attr(a.id()), "per", modList(a.perPoint())));
        m.put("attrs", at);
        m.put("groups", new LinkedHashMap<String, Object>(defs.groupNames));
        List<Object> cl = new ArrayList<>();
        for (OriginDefs.ClassDef c : origins.classes.values()) {
            if (!c.from().isEmpty() && !isChildOfTier1(c)) continue;
            String why = c.from().isEmpty() ? classBlocked(d, c) : null;
            List<Object> kids = new ArrayList<>();
            for (OriginDefs.ClassDef k : origins.classes.values()) if (origins.isParent(k, c.id())) kids.add(obj("id", k.id(), "name", k.name(), "tier", k.tier(), "desc", k.desc(), "minLevel", k.minLevel()));
            if (c.from().isEmpty()) cl.add(obj("id", c.id(), "name", c.name(), "desc", c.desc(), "archetype", c.archetype(), "why", why == null ? "" : why, "on", d.cls.equals(c.id()), "mods", modList(c.mods()), "kids", kids));
        }
        m.put("classes", cl);
        List<Object> sk = new ArrayList<>();
        for (ProgressDefs.Skill s : progress.skills.values()) {
            int rank = d.skills.getOrDefault(s.id(), 0);
            String why = rank >= s.max() ? "Максимум" : skillBlocked(d, s.id(), rank + 1);
            sk.add(obj("id", s.id(), "name", s.name(), "group", s.group(), "desc", s.desc(), "max", s.max(), "rank", rank, "cost", rank < s.cost().size() ? s.cost().get(rank) : 0, "why", why == null ? "" : why, "per", modList(s.perRank())));
        }
        m.put("skills", sk);
        Map<String, Object> pv = new LinkedHashMap<>();
        for (var e : preview(d).entrySet()) pv.put(e.getKey(), e.getValue());
        m.put("preview", pv);
        Map<String, Object> sn = new LinkedHashMap<>();
        for (ProgressDefs.Stat s : progress.stats.values()) sn.put(s.id(), s.name());
        m.put("statNames", sn);
        m.put("problems", new ArrayList<Object>(problems(d)));
        m.put("features_on", new ArrayList<Object>(d.features));
        return Json.write(m);
    }

    private boolean isChildOfTier1(OriginDefs.ClassDef c) { return false; }

    // ------------------------------------------------------------------ сохранение облика

    public String toJson() {
        List<Object> l = new ArrayList<>();
        for (Draft d : finished.values()) l.add(draftMap(d));
        return Json.write(obj("version", 1, "characters", l));
    }

    private Map<String, Object> draftMap(Draft d) {
        return obj("o", d.owner, "race", d.race, "start", d.start, "cls", d.cls, "settle", d.settleName, "look", new LinkedHashMap<String, Object>(d.look), "features", new ArrayList<Object>(d.features),
                "profs", new ArrayList<Object>(d.profs), "attrs", new LinkedHashMap<String, Object>(d.attrs), "skills", new LinkedHashMap<String, Object>(d.skills));
    }

    @SuppressWarnings("unchecked")
    public void fromJson(String json) {
        finished.clear();
        Map<String, Object> r = (Map<String, Object>) Json.parse(json);
        if (!(r.get("characters") instanceof List<?> l)) return;
        for (Object o : l) {
            Map<String, Object> m = (Map<String, Object>) o;
            Draft d = new Draft(String.valueOf(m.get("o")));
            d.race = String.valueOf(m.get("race")); d.start = String.valueOf(m.get("start")); d.cls = String.valueOf(m.get("cls")); d.settleName = String.valueOf(m.get("settle"));
            if (m.get("look") instanceof Map<?, ?> lk) for (var e : lk.entrySet()) d.look.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            if (m.get("features") instanceof List<?> fl) for (Object x : fl) d.features.add(String.valueOf(x));
            if (m.get("profs") instanceof List<?> pl) for (Object x : pl) d.profs.add(String.valueOf(x));
            if (m.get("attrs") instanceof Map<?, ?> am) for (var e : am.entrySet()) d.attrs.put(String.valueOf(e.getKey()), ((Number) e.getValue()).intValue());
            if (m.get("skills") instanceof Map<?, ?> sm) for (var e : sm.entrySet()) d.skills.put(String.valueOf(e.getKey()), ((Number) e.getValue()).intValue());
            d.done = true;
            finished.put(d.owner, d);
        }
    }

    /** Забыть завершённого персонажа (оператор сбрасывает создание). */
    public void forget(String owner) { finished.remove(owner); drafts.remove(owner); }

    /** Облик для рендера: компактная строка "race|key=val;..|features" (для пакета другим игрокам). */
    public String lookString(String owner) {
        Draft d = finished.get(owner);
        if (d == null) return "";
        StringBuilder sb = new StringBuilder(d.race).append('|');
        boolean first = true;
        for (var e : d.look.entrySet()) { if (!first) sb.append(';'); first = false; sb.append(e.getKey()).append('=').append(e.getValue()); }
        sb.append('|').append(String.join(",", d.features));
        return sb.toString();
    }
}
