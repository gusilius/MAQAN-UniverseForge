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
        public final String group;
        public final Map<String, Integer> mods;

        Trait(String id, String name, String desc, int color, Object... mods) {
            this.id = id; this.name = name; this.desc = desc; this.color = color;
            this.group = traitGroup(id);
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
        t(new Trait("honest", "Честный", "Предпочитает правду обману, даже когда это опасно.", GOOD, "diplomacy", 1));
        t(new Trait("deceitful", "Лживый", "Скрывает настоящие намерения и умеет вводить в заблуждение.", BAD, "intrigue", 2, "diplomacy", -1));
        t(new Trait("temperate", "Умеренный", "Умеет ограничивать желания и привычки.", GOOD, "stewardship", 1));
        t(new Trait("gluttonous", "Обжора", "Поддаётся излишествам и плохо знает меру.", BAD, "stewardship", -1));
        t(new Trait("chaste", "Целомудренный", "Сдержан в романтических привязанностях.", NEUT, "learning", 1));
        t(new Trait("lustful", "Похотливый", "Часто ставит желания выше долга.", BAD, "diplomacy", 1, "intrigue", 1));
        t(new Trait("humble", "Скромный", "Не стремится выставлять заслуги напоказ.", GOOD, "diplomacy", 1));
        t(new Trait("arrogant", "Высокомерный", "Считает себя выше окружающих и плохо переносит возражения.", BAD, "diplomacy", -2));
        t(new Trait("forgiving", "Прощающий", "Способен оставить старые обиды в прошлом.", GOOD, "diplomacy", 2));
        t(new Trait("vengeful", "Мстительный", "Долго помнит обиды и ищет возможность отплатить.", BAD, "intrigue", 2, "diplomacy", -1));
        t(new Trait("compassionate", "Сострадательный", "Чужие страдания вызывают желание помочь.", GOOD, "diplomacy", 1));
        t(new Trait("sadistic", "Садист", "Получает удовольствие от чужой боли и унижения.", BAD, "intrigue", 1, "diplomacy", -2));
        t(new Trait("trusting", "Доверчивый", "Охотно верит людям, иногда слишком поспешно.", GOOD, "diplomacy", 1, "intrigue", -1));
        t(new Trait("paranoid", "Подозрительный", "Ищет скрытые мотивы даже в дружеских поступках.", BAD, "intrigue", 2, "diplomacy", -1));
        t(new Trait("curious", "Любознательный", "Стремится узнавать новое и задаёт вопросы.", GOOD, "learning", 2));
        t(new Trait("cynical", "Циничный", "Сомневается в благородных мотивах и обещаниях.", NEUT, "intrigue", 1, "learning", 1));
        t(new Trait("zealous", "Ревностный", "Глубоко предан своей вере, идее или священному долгу.", NEUT, "martial", 1, "learning", 1));
        t(new Trait("sceptical", "Скептик", "Требует доказательств и не принимает авторитет на веру.", NEUT, "learning", 2));
        t(new Trait("adaptable", "Гибкий", "Быстро приспосабливается к переменам и новым порядкам.", GOOD, "stewardship", 1, "diplomacy", 1));
        t(new Trait("stubborn", "Упрямый", "Не отступает от принятого решения даже под давлением.", BAD, "martial", 1, "diplomacy", -1));
        t(new Trait("ambivalent", "Нерешительный", "Долго колеблется между возможностями.", BAD, "diplomacy", -1, "learning", 1));
        t(new Trait("resilient", "Стойкий", "Восстанавливается после поражений и испытаний.", GOOD, "martial", 1));
        t(new Trait("melancholic", "Меланхоличный", "Склонен к печали и внутренним сомнениям.", NEUT, "learning", 1, "diplomacy", -1));
        t(new Trait("optimistic", "Оптимистичный", "Ожидает хорошего исхода и заражает надеждой.", GOOD, "diplomacy", 1));
        t(new Trait("pessimistic", "Пессимистичный", "Прежде всего замечает угрозы и слабые места планов.", BAD, "learning", 1, "diplomacy", -1));
        t(new Trait("tactful", "Тактичный", "Умеет говорить неприятную правду без оскорблений.", GOOD, "diplomacy", 2));
        t(new Trait("ambitious_schemer", "Расчётливый интриган", "Строит многоходовые планы ради возвышения.", NEUT, "intrigue", 2, "stewardship", 1));
        t(new Trait("dutiful", "Исполнительный", "Считает обязательства важнее личного удобства.", GOOD, "stewardship", 1, "martial", 1));
        t(new Trait("independent", "Независимый", "Не любит подчиняться и предпочитает собственный путь.", NEUT, "intrigue", 1));
        t(new Trait("sociable", "Душа общества", "Умеет объединять людей и поддерживать связи.", GOOD, "diplomacy", 2));
        t(new Trait("reclusive", "Затворник", "Предпочитает уединение шумным обществам.", NEUT, "learning", 1, "diplomacy", -1));
        t(new Trait("perfectionist", "Перфекционист", "Не терпит небрежности и замечает ошибки.", NEUT, "stewardship", 1, "learning", 1));
        t(new Trait("resourceful", "Находчивый", "Находит выход при нехватке средств.", GOOD, "stewardship", 1, "intrigue", 1));
        t(new Trait("reckless", "Безрассудный", "Рискует, не всегда оценивая цену неудачи.", BAD, "martial", 1, "stewardship", -1));
        t(new Trait("cautious", "Осторожный", "Предпочитает надёжный план рискованной авантюре.", GOOD, "stewardship", 1));
        t(new Trait("eloquent", "Красноречивый", "Умеет убеждать словом и удерживать внимание.", GOOD, "diplomacy", 2));
        t(new Trait("observant", "Наблюдательный", "Замечает детали поведения и слабости окружающих.", GOOD, "intrigue", 1, "learning", 1));
        t(new Trait("traditionalist", "Приверженец традиций", "Считает проверенные обычаи опорой общества.", NEUT, "stewardship", 1));
        t(new Trait("innovative", "Новатор", "Охотно испытывает новые методы и порядки.", GOOD, "learning", 2));
        t(new Trait("honorable", "Благородный", "Считает честь и данное слово основой отношений.", GOOD, "diplomacy", 1, "martial", 1));
        t(new Trait("opportunistic", "Оппортунист", "Использует возможности, даже если это задевает других.", BAD, "intrigue", 2, "stewardship", 1));
        t(new Trait("loyal", "Верный", "Хранит преданность семье, друзьям и покровителям.", GOOD, "diplomacy", 1, "martial", 1));
        t(new Trait("fickle", "Непостоянный", "Легко меняет сторону при изменении обстоятельств.", BAD, "intrigue", 1, "diplomacy", -1));
        t(new Trait("protective", "Заботливый защитник", "Ставит безопасность близких и слабых выше выгоды.", GOOD, "martial", 1, "diplomacy", 1));
        t(new Trait("callous", "Черствый", "С трудом сопереживает чужим потерям и нуждам.", BAD, "stewardship", 1, "diplomacy", -1));
        t(new Trait("disciplined", "Дисциплинированный", "Следует распорядку и удерживает себя от импульсивности.", GOOD, "martial", 1, "stewardship", 1));
        t(new Trait("impulsive", "Импульсивный", "Действует прежде, чем взвесит последствия.", BAD, "martial", 1, "intrigue", -1));
        t(new Trait("pragmatic", "Прагматичный", "Предпочитает работающие решения красивым обещаниям.", NEUT, "stewardship", 1, "learning", 1));
        t(new Trait("idealistic", "Идеалист", "Жертвует выгодой ради представлений о должном.", NEUT, "diplomacy", 1, "learning", 1));
        t(new Trait("charismatic", "Харизматичный", "Притягивает внимание и вдохновляет окружающих.", GOOD, "diplomacy", 2));
        t(new Trait("awkward", "Неловкий", "Трудно чувствует себя в обществе и понимает намёки.", BAD, "diplomacy", -1, "learning", 1));
        t(new Trait("artistic", "Творческий", "Ищет новые формы выражения и необычные связи.", GOOD, "learning", 2));
        t(new Trait("confident", "Уверенный", "Доверяет собственным силам и не теряется под давлением.", GOOD, "martial", 1, "diplomacy", 1));
    }

    private static String traitGroup(String id) {
        return switch (id) {
            case "brave", "craven" -> "courage";
            case "just", "cruel" -> "justice";
            case "greedy", "generous" -> "wealth";
            case "diligent", "lazy" -> "industry";
            case "gregarious", "shy", "sociable", "reclusive" -> "sociability";
            case "ambitious", "content" -> "ambition";
            case "wroth", "patient" -> "temper";
            case "honest", "deceitful" -> "honesty";
            case "temperate", "gluttonous" -> "temperance";
            case "chaste", "lustful" -> "chastity";
            case "humble", "arrogant" -> "pride";
            case "forgiving", "vengeful" -> "grudge";
            case "compassionate", "sadistic" -> "empathy";
            case "trusting", "paranoid" -> "trust";
            case "optimistic", "pessimistic" -> "outlook";
            case "honorable", "opportunistic" -> "honor";
            case "loyal", "fickle" -> "loyalty";
            case "protective", "callous" -> "care";
            case "disciplined", "impulsive" -> "self_control";
            case "pragmatic", "idealistic" -> "worldview";
            case "charismatic", "awkward" -> "social_skill";
            case "reckless", "cautious" -> "risk";
            default -> id;
        };
    }

    public record Skill(String id, String name, String description, String governingAttribute) { }
    private static final Map<String, Skill> SKILLS = new LinkedHashMap<>();
    private static void skill(String id, String name, String description, String attribute) {
        SKILLS.put(id, new Skill(id, name, description, attribute));
    }
    static {
        skill("swordsmanship", "Фехтование", "Владение клинковым оружием.", "martial");
        skill("archery", "Стрельба", "Меткость из луков и дальнобойного оружия.", "martial");
        skill("polearms", "Древковое оружие", "Бой копьями, пиками и алебардами.", "martial");
        skill("riding", "Верховая езда", "Управление верховым животным в пути и бою.", "martial");
        skill("tactics", "Тактика", "Построения, манёвры и чтение поля боя.", "martial");
        skill("command", "Командование", "Управление подразделениями и дисциплина войск.", "martial");
        skill("siegecraft", "Осадное дело", "Подготовка и проведение осад.", "martial");
        skill("diplomatic_etiquette", "Дипломатический этикет", "Протокол и переговоры.", "diplomacy");
        skill("persuasion", "Убеждение", "Способность склонять людей к своей позиции.", "diplomacy");
        skill("leadership", "Лидерство", "Сплочение людей и поддержание доверия.", "diplomacy");
        skill("intrigue_networks", "Сети интриг", "Создание и поддержание тайных связей.", "intrigue");
        skill("espionage", "Шпионаж", "Сбор скрытой информации и наблюдение.", "intrigue");
        skill("deception", "Обман", "Маскировка намерений и ложные сведения.", "intrigue");
        skill("stewardship", "Хозяйственное управление", "Организация доходов, расходов и ресурсов.", "stewardship");
        skill("logistics", "Логистика", "Снабжение, склады и движение ресурсов.", "stewardship");
        skill("construction", "Строительство", "Планирование и возведение сооружений.", "stewardship");
        skill("trade", "Торговля", "Обмен, рынки и торговые соглашения.", "stewardship");
        skill("medicine", "Медицина", "Лечение ран, болезней и восстановление.", "learning");
        skill("engineering", "Инженерное дело", "Механизмы, укрепления и осадные машины.", "learning");
        skill("scholarship", "Учёность", "Исследования, летописи и систематизация знаний.", "learning");
        skill("law", "Право", "Законы, судопроизводство и обычаи.", "learning");
        skill("theology", "Теология", "Религиозные учения, обряды и институты.", "learning");
        skill("craftsmanship", "Ремесло", "Изготовление и ремонт предметов.", "stewardship");
        skill("survival", "Выживание", "Ориентирование и жизнь в дикой местности.", "learning");
        skill("administration", "Администрирование", "Работа учреждений, учёт и исполнение решений.", "stewardship");
        skill("music", "Музыка", "Исполнение, композиция и культурное влияние.", "learning");
        skill("animal_handling", "Обращение с животными", "Уход, разведение и дрессировка.", "diplomacy");
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

    /** Skill metadata delegates progression to PopulationSimulation's existing canonical skill store. */
    public static Collection<Skill> allSkills() { return Collections.unmodifiableCollection(SKILLS.values()); }
    public static Skill skill(String id) { return SKILLS.get(id); }
    public static int skillRank(Character c, String id) {
        if (!SKILLS.containsKey(id)) throw new IllegalArgumentException("Unknown skill: " + id);
        return PopulationSimulation.skillRank(c, id);
    }
    public static int trainSkill(Character c, String id, long xp) {
        if (!SKILLS.containsKey(id)) throw new IllegalArgumentException("Unknown skill: " + id);
        return PopulationSimulation.train(c, id, xp);
    }

    private static final Set<Set<String>> TRAIT_CONFLICTS = Set.of(
            Set.of("brave", "craven"), Set.of("just", "cruel"), Set.of("greedy", "generous"),
            Set.of("diligent", "lazy"), Set.of("gregarious", "shy"), Set.of("gregarious", "reclusive"),
            Set.of("sociable", "reclusive"), Set.of("ambitious", "content"), Set.of("wroth", "patient"),
            Set.of("honest", "deceitful"), Set.of("temperate", "gluttonous"), Set.of("chaste", "lustful"),
            Set.of("humble", "arrogant"), Set.of("forgiving", "vengeful"), Set.of("compassionate", "sadistic"),
            Set.of("trusting", "paranoid"), Set.of("optimistic", "pessimistic"), Set.of("reckless", "cautious"),
            Set.of("honorable", "opportunistic"), Set.of("loyal", "fickle"), Set.of("protective", "callous"),
            Set.of("disciplined", "impulsive"), Set.of("pragmatic", "idealistic"), Set.of("charismatic", "awkward")
    );

    public static boolean compatibleTraits(Collection<String> values) {
        Set<String> selected = new HashSet<>();
        for (String id : values) {
            if (!TRAITS.containsKey(id) || !selected.add(id)) return false;
        }
        for (Set<String> conflict : TRAIT_CONFLICTS)
            if (selected.containsAll(conflict)) return false;
        return true;
    }

    public static boolean addTrait(Character c, String id) {
        if (!TRAITS.containsKey(id)) throw new IllegalArgumentException("Unknown trait: " + id);
        List<String> next = new ArrayList<>(traits(c));
        if (next.contains(id)) return false;
        next.add(id);
        if (!compatibleTraits(next)) return false;
        c.traits.clear();
        c.traits.addAll(next);
        return true;
    }

    public static boolean removeTrait(Character c, String id) { return c.traits.remove(id); }

    /** 3–5 stable, varied and mutually compatible traits; the same identity always yields the same profile. */
    public static List<String> traits(Character c) {
        if (!c.traits.isEmpty()) {
            List<String> stored = new ArrayList<>();
            for (String id : c.traits) {
                if (!TRAITS.containsKey(id)) continue;
                List<String> attempt = new ArrayList<>(stored);
                attempt.add(id);
                if (compatibleTraits(attempt)) stored.add(id);
            }
            return stored;
        }
        List<String> candidates = new ArrayList<>(TRAITS.keySet());
        candidates.remove("usurper");
        candidates.sort(Comparator.comparingLong(id -> hash(c.id + "|trait|" + id)));
        List<String> out = new ArrayList<>();
        Set<String> groups = new HashSet<>();
        int target = 3 + (int) Math.floorMod(hash(c.id + "|trait-count"), 3L);
        for (String id : candidates) {
            String group = traitGroup(id);
            if (groups.contains(group)) continue;
            List<String> attempt = new ArrayList<>(out);
            attempt.add(id);
            if (!compatibleTraits(attempt)) continue;
            out.add(id);
            groups.add(group);
            if (out.size() >= target) break;
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
            List<String> normalizedTraits = traits(c);
            if (!c.traits.equals(new TreeSet<>(normalizedTraits))) {
                c.traits.clear();
                c.traits.addAll(normalizedTraits);
            }
        }
    }
}
