#!/usr/bin/env python3
"""Расширение wf-origins, часть 2: новые расы и подрасы, расовые особенности (race_perks), переплетения (synergies), классы и профессии, связанные с формами (wf-forms).
Запускать из wfaddons ПОСЛЕ gen_origins.py; перезаписывает *_ext2.json и race_perks.json, synergies.json."""
import json, os, glob
D = os.path.join(os.path.dirname(__file__), '..', 'wf-origins', 'src', 'main', 'resources', 'data', 'wf_origins', 'wf_origins')
def save(name, d): json.dump(d, open(os.path.join(D, name + '.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)

# ---------- новые расы ----------
R = []
def race(id, name, desc, color, mods, innate, traits, weight=3, parent=None):
    r = {"id": id, "name": name, "desc": desc, "color": color, "weight": weight, "mods": mods, "innate": innate, "traits": traits}
    if parent: r["parent"] = parent
    R.append(r)
race("giant", "Великан", "Вдвое выше людей и втрое упрямее: сила рук и вечный голод.", 0xA08868, {"max_health": 10, "melee_dmg_pct": 4, "carry": 10, "stealth_pct": -8, "dodge_pct": -3}, {"Исполинская стать": "+10 здоровья, +10 ноша"}, ["wroth"], 2)
race("beastkin", "Зверолюд", "Потомки тех, кто жил рядом со зверьём так долго, что научился его языку.", 0x9A7A4A, {"move_speed_pct": 3, "dodge_pct": 3, "stealth_pct": 3, "opinion": -1}, {"Звериное чутьё": "+3% скорости и уклонения"}, ["gregarious"], 4)
race("triton", "Тритон", "Жители побережий и рифов: плавают быстро, на суше медленнее.", 0x3C8CB4, {"fish_pct": 12, "trade_profit_pct": 3, "move_speed_pct": -2, "resist_pct": 3}, {"Дитя моря": "+12% улова"}, ["patient"], 3)
race("construct", "Рукотворный", "Существа из камня, глины и шестерён - создание древних мастеров.", 0x808890, {"armor": 3, "resist_pct": 6, "health_regen": -0.02, "heal_pct": -6, "opinion": -2}, {"Каменное тело": "+3 брони, +6% сопротивления"}, ["content"], 2)
for p, i, n, d, c, m, inn, t in [
 ("giant", "frost_giant", "Ледяной великан", "Холодные нагорья закалили их кровь.", 0x9CC4E0, {"resist_pct": 6, "max_health": 4}, {"Ледяная кровь": "+6% сопротивления"}, ["brave"]),
 ("giant", "hill_giant", "Холмовой великан", "Пастухи и каменотёсы, медлительны, но огромны.", 0x8C7C4C, {"mining_speed_pct": 6, "carry": 6}, {"Каменотёс": "+6% скорости добычи"}, ["diligent"]),
 ("beastkin", "wolfkin", "Волкокровный", "Стайные охотники севера.", 0x8C8C94, {"melee_dmg_pct": 3, "levy_pct": 4, "move_speed_pct": 2}, {"Стая": "+4% ополчения"}, ["brave"]),
 ("beastkin", "foxkin", "Лисья кровь", "Хитрецы и плуты.", 0xD98040, {"stealth_pct": 5, "luck": 1, "trade_profit_pct": 2}, {"Лисья удача": "+1 удача"}, ["shrewd"]),
 ("beastkin", "catkin", "Кошачья кровь", "Ловкие и неслышные.", 0xC8B070, {"dodge_pct": 5, "crit_pct": 2, "stealth_pct": 4}, {"Кошачья грация": "+5% уклонения"}, ["shy"]),
 ("triton", "reef_triton", "Рифовый тритон", "Торговцы и перламутровщики.", 0x58B4C8, {"trade_profit_pct": 4, "luck": 0.5}, {"Перламутр": "+4% прибыли"}, ["gregarious"]),
 ("triton", "deep_triton", "Глубинный тритон", "Охотники тёмных течений.", 0x2C5C88, {"ranged_dmg_pct": 4, "stealth_pct": 3, "resist_pct": 3}, {"Давление глубин": "+3% сопротивления"}, ["cruel"]),
 ("construct", "stone_construct", "Каменный рукотворный", "Стражи руин.", 0x7C7C7C, {"armor": 3, "max_health": 6, "move_speed_pct": -3}, {"Нерушимый": "+3 брони"}, ["patient"]),
 ("construct", "clockwork", "Механический", "Шестерни и пружины: точны и холодны.", 0xB08850, {"craft_quality_pct": 6, "crit_pct": 2}, {"Точность": "+6% качества ремесла"}, ["scholar"]),
]: race(i, n, d, c, m, inn, t, 2, p)
save('races_ext2', {"races": R})

# ---------- расовые особенности ----------
PK = {}
def perks(race, *items): PK[race] = {str(l): {"name": n, "mods": m} for l, n, m in items}
perks("human", (5, "Упорство", {"xp_gain_pct": 2}), (15, "Дипломатичность", {"opinion": 1}), (30, "Закалка судьбой", {"luck": 1, "max_health": 4}))
perks("northman", (5, "Выносливость севера", {"max_health": 4}), (15, "Зов предков", {"melee_dmg_pct": 3}), (30, "Северная ярость", {"crit_pct": 3, "resist_pct": 4}))
perks("elf", (5, "Чуткий слух", {"stealth_pct": 3}), (15, "Лунный лучник", {"ranged_dmg_pct": 4}), (30, "Вековая мудрость", {"xp_gain_pct": 4, "heal_pct": 4}))
perks("dwarf", (5, "Горная закалка", {"armor": 1}), (15, "Чутьё на руду", {"mining_speed_pct": 6, "gold_find_pct": 3}), (30, "Мастер горна", {"craft_quality_pct": 6, "weapon_dmg_pct": 2}))
perks("halfling", (5, "Лёгкие ноги", {"dodge_pct": 2}), (15, "Счастливая рука", {"luck": 1}), (30, "Мирный очаг", {"farm_yield_pct": 6, "health_regen": 0.02}))
perks("orc", (5, "Крепкая шкура", {"max_health": 4}), (15, "Боевой клич", {"levy_pct": 4, "melee_dmg_pct": 3}), (30, "Ярость орды", {"melee_dmg_pct": 6, "crit_pct": 3}))
perks("goblin", (5, "Цепкие пальцы", {"craft_quality_pct": 3}), (15, "Нюх на выгоду", {"gold_find_pct": 5}), (30, "Мастер подвохов", {"dodge_pct": 3, "luck": 1}))
perks("saurian", (5, "Холодная кровь", {"resist_pct": 3}), (15, "Регенерация", {"health_regen": 0.03}), (30, "Чешуя вековых", {"armor": 3}))
perks("fae", (5, "Шёпот рощи", {"heal_pct": 3}), (15, "Танец теней", {"dodge_pct": 3, "stealth_pct": 3}), (30, "Дар лета", {"heal_pct": 8, "luck": 1}))
perks("shadekin", (5, "Зрение во тьме", {"stealth_pct": 4}), (15, "Шёпот секретов", {"spy_success_pct": 4}), (30, "Покров ночи", {"stealth_pct": 8, "crit_pct": 3}))
perks("giant", (5, "Могучая хватка", {"carry": 6}), (15, "Топот", {"melee_dmg_pct": 4}), (30, "Колосс", {"max_health": 14, "armor": 2}))
perks("beastkin", (5, "Звериный нюх", {"luck": 0.5}), (15, "Зов стаи", {"levy_pct": 3}), (30, "Дух зверя", {"move_speed_pct": 4, "dodge_pct": 3}))
perks("triton", (5, "Плавник", {"move_speed_pct": 2}), (15, "Дыхание глубин", {"resist_pct": 3}), (30, "Власть над волной", {"fish_pct": 10, "trade_profit_pct": 4}))
perks("construct", (5, "Прочный корпус", {"armor": 1}), (15, "Самоочистка", {"resist_pct": 3}), (30, "Вечный механизм", {"max_health": 10, "armor": 2}))
# подрасы: две особенности по собственным модам (усиленная главная) - уровни 10 и 25
allr = []
for f in sorted(glob.glob(os.path.join(D, 'races*.json'))):
    allr += json.load(open(f, encoding='utf-8')).get('races', [])
for r in allr:
    if r.get('parent') and r['id'] not in PK:
        items = sorted(r['mods'].items(), key=lambda kv: -abs(kv[1]))
        k, v = items[0]
        k2, v2 = (items[1] if len(items) > 1 else items[0])
        half = lambda x: round(max(abs(x) / 2, 0.5) * (1 if x > 0 else -1), 2)
        PK[r['id']] = {"10": {"name": "Наследие: " + r['name'], "mods": {k: round(v / 2 if abs(v) >= 2 else v, 2)}}, "25": {"name": "Сила рода", "mods": {k2: half(v2) if v2 > 0 else abs(v2) and 1}}}
save('race_perks', {"race_perks": PK})

# ---------- классы ----------
C = []
def cls(id, name, desc, tier, frm, level, stat, mods, perks=None, arch="", also=None, excl="", prof=None, flags=None, races=None, ban=None):
    c = {"id": id, "name": name, "desc": desc, "tier": tier, "min_level": level, "mods": mods}
    if frm: c["from"] = frm
    if arch: c["archetype"] = arch
    if stat: c["req_stat"] = stat
    if perks: c["perks"] = {str(l): {"name": n, "mods": m} for l, (n, m) in perks.items()}
    if also: c["also_from"] = also
    if excl: c["exclusive"] = excl
    if prof: c["req_prof"] = prof
    if flags: c["req_flags"] = flags
    if races: c["req_races"] = races
    if ban: c["ban_flags"] = ban
    C.append(c)
# охотники и стражи против тьмы
cls("night_hunter", "Ночной охотник", "Знает повадки нечисти и выходит на неё в одиночку.", 2, "ranger", 15, {"agility": 10}, {"ranged_dmg_pct": 4, "crit_pct": 3, "stealth_pct": 3}, {20: ("Серебряная пуля", {"crit_pct": 3})}, "ranger", ban=["form:vampire", "form:werewolf", "form:zombie"])
cls("witch_hunter", "Охотник на ведьм", "Выслеживает проклятых и одержимых.", 3, "night_hunter", 30, {"intrigue": 10, "might": 10}, {"melee_dmg_pct": 8, "resist_pct": 6, "crit_pct": 4}, excl="hunt", ban=["form:vampire", "form:werewolf", "form:zombie", "form:cursed_blood"])
cls("beast_warden", "Страж зверей", "Берёт под защиту лес и всех, кто в нём живёт, даже оборотней.", 3, "warden", 30, {"spirit": 12}, {"heal_pct": 6, "health_regen": 0.03, "stealth_pct": 6}, excl="hunt")
cls("blood_knight", "Рыцарь крови", "Присягнул ночи: жертвует кровью ради силы.", 3, "knight", 30, {"might": 12}, {"melee_dmg_pct": 10, "crit_pct": 4, "max_health": 8, "heal_pct": -6}, {35: ("Жажда", {"melee_dmg_pct": 4})}, excl="oath", flags=["form:vampire"])
cls("moon_druid", "Лунный друид", "Принимает зверя внутри и говорит с луной.", 3, "warden", 30, {"spirit": 14}, {"heal_pct": 8, "move_speed_pct": 5, "stealth_pct": 5}, {35: ("Лунный лик", {"dodge_pct": 3})}, excl="hunt", flags=["form:werewolf"])
cls("dragon_scion", "Наследник дракона", "Драконья кровь проснулась и требует службы.", 3, "knight", 30, {"might": 14, "spirit": 8}, {"melee_dmg_pct": 10, "armor": 4, "resist_pct": 8, "max_health": 10}, {35: ("Дыхание дракона", {"crit_pct": 4})}, excl="oath", flags=["form:dragon_blood"])
cls("dragon_rider", "Всадник дракона", "Нашёл общий язык с драконом.", 4, "dragon_scion", 50, {"might": 18, "agility": 12}, {"melee_dmg_pct": 16, "armor": 6, "move_speed_pct": 8, "levy_pct": 8}, flags=["form:dragon_blood"])
cls("necromancer", "Некромант", "Работает со смертью как с ремеслом.", 3, "physician", 30, {"intellect": 14}, {"crit_pct": 4, "spy_success_pct": 4, "heal_pct": -8, "opinion": -4}, {35: ("Слово праха", {"melee_dmg_pct": 6})}, excl="lore", ban=["form:blessed"])
cls("revenant_lord", "Повелитель мёртвых", "Мертвецы идут за ним без страха и без жалования.", 4, "necromancer", 50, {"intellect": 20}, {"levy_pct": 18, "crit_pct": 6, "opinion": -6}, flags=["form:zombie"])
cls("curse_breaker", "Снимающий проклятья", "Знает слова, сжигающие чужую порчу.", 3, "physician", 30, {"spirit": 14, "learning": 10}, {"heal_pct": 14, "resist_pct": 8, "opinion": 3}, excl="lore")
cls("lich_scholar", "Лич-книжник", "Оставил тело мёртвым ради вечного ума.", 4, "savant", 50, {"learning": 22, "intellect": 14}, {"xp_gain_pct": 20, "craft_quality_pct": 12, "heal_pct": -10, "opinion": -4}, flags=["form:zombie"])
cls("shapeshifter", "Перевёртыш", "Подчинил превращение и меняет облик по воле.", 3, "trickster", 30, {"agility": 12, "spirit": 10}, {"stealth_pct": 12, "dodge_pct": 6, "crit_pct": 3}, flags=["form:werewolf"])
cls("pale_noble", "Бледный аристократ", "Ночной двор и бессмертные интриги.", 3, "courtier", 30, {"diplomacy": 12, "intrigue": 10}, {"opinion": 6, "intrigue_bonus": 3, "tax_pct": 4}, flags=["form:vampire"])
cls("doom_prophet", "Пророк обречённых", "Проклятье сделало его провидцем.", 3, "scholar", 30, {"spirit": 14, "learning": 10}, {"xp_gain_pct": 8, "luck": 2, "opinion": -2}, flags=["form:cursed_blood"])
save('classes_ext2', {"classes": C})

# ---------- профессии ----------
P = []
def prof(id, name, desc, group, frm, minr, wage, mods, rk, stat=None, excl="", classes=None, wp=""):
    p = {"id": id, "name": name, "desc": desc, "wage": wage, "ranks": [{"name": n, "xp": x, "mods": (mods if i else {})} for i, (n, x) in enumerate(zip(rk, [0, 200, 800, 2500]))]}
    if frm: p["from"] = frm; p["min_rank"] = minr
    else: p["group"] = group
    if wp: p["workplace"] = wp
    if stat: p["req_stat"] = stat
    if excl: p["exclusive"] = excl
    if classes: p["req_classes"] = classes
    P.append(p)
prof("exorcist", "Экзорцист", "Изгоняет нечисть и снимает порчу.", "service", None, 0, 7, {"resist_pct": 3, "heal_pct": 3}, ["Служка", "Изгоняющий", "Экзорцист", "Великий экзорцист"], {"spirit": 6}, wp="chapel")
prof("gravedigger", "Могильщик", "Знает кладбище лучше живых.", "service", None, 0, 4, {"stealth_pct": 2, "resist_pct": 2}, ["Подмастерье", "Могильщик", "Смотритель погоста", "Хранитель склепа"], wp="chapel")
prof("beastmaster_p", "Зверовод", "Приручает и выращивает зверей.", "farm", None, 0, 5, {"carry": 3, "luck": 0.3}, ["Подпасок", "Зверовод", "Мастер зверинца", "Хозяин зверей"], wp="stables")
prof("jeweler_p", "Ювелир-огранщик", "Камни и металлы высшей пробы.", "craft", None, 0, 8, {"craft_quality_pct": 4, "gold_find_pct": 2}, ["Подмастерье", "Огранщик", "Мастер камня", "Придворный ювелир"], {"agility": 5}, wp="workshop")
prof("blood_alchemist", "Алхимик крови", "Работает с кровью, ядом и эликсирами жизни.", "scholar", "alchemist", 2, 12, {"heal_pct": 4, "crit_pct": 2}, ["Подмастерье", "Кровавый алхимик", "Мастер эликсиров", "Хранитель красной книги"], excl="alch")
prof("rune_smith", "Рунный кузнец", "Руны, что держат металл и порчу.", "craft", "smith", 2, 11, {"weapon_dmg_pct": 3, "resist_pct": 3}, ["Подмастерье", "Рунный кузнец", "Мастер рун", "Легенда рун"], excl="smithtype")
prof("silversmith", "Серебряных дел мастер", "Серебряные клинки и обереги против нечисти.", "craft", "smith", 2, 10, {"weapon_dmg_pct": 2, "crit_pct": 1, "resist_pct": 2}, ["Подмастерье", "Серебряник", "Мастер серебра", "Хранитель серебра"], excl="smithtype")
prof("grave_scholar", "Книжник теней", "Собирает запретные книги.", "scholar", "scribe", 2, 9, {"xp_gain_pct": 4, "spy_success_pct": 2}, ["Копиист", "Книжник", "Хранитель запретного", "Архивариус теней"], excl="scribetype")
prof("caravan_guard", "Караванный страж", "Бережёт чужие грузы от разбойников.", "military", "guard", 2, 9, {"armor": 1, "trade_profit_pct": 1}, ["Подручный", "Караванный страж", "Старший страж", "Начальник охраны"])
prof("sea_pilot", "Лоцман", "Проводит корабли мимо рифов.", "service", "fisher", 2, 9, {"move_speed_pct": 2, "fish_pct": 4}, ["Юнга", "Лоцман", "Мастер фарватера", "Адмиральский лоцман"])
save('professions_ext2', {"professions": P})

# ---------- переплетения ----------
S = []
def syn(id, name, desc, mods, races=None, classes=None, profs=None, flags=None):
    y = {"id": id, "name": name, "desc": desc, "mods": mods}
    if races: y["races"] = races
    if classes: y["classes"] = classes
    if profs: y["profs"] = profs
    if flags: y["flags"] = flags
    S.append(y)
syn("dwarf_smith", "Горновой род", "Гном у наковальни: металл слушается.", {"craft_quality_pct": 6}, races=["dwarf"], profs={"smith": 1})
syn("dwarf_miner", "Дитя недр", "Гном-горняк чует жилы.", {"mining_speed_pct": 8, "gold_find_pct": 4}, races=["dwarf"], profs={"miner": 1})
syn("elf_archer", "Лесной лучник", "Эльф-следопыт: каждая стрела в цель.", {"ranged_dmg_pct": 6, "crit_pct": 2}, races=["elf"], classes=["ranger"])
syn("elf_scholar", "Хранитель памяти", "Эльф-книжник помнит всё.", {"xp_gain_pct": 5, "heal_pct": 3}, races=["elf"], classes=["scholar"])
syn("orc_warrior", "Дитя орды", "Орк-воин: ярость и слитый строй.", {"melee_dmg_pct": 6, "levy_pct": 4}, races=["orc"], classes=["warrior"])
syn("halfling_merchant", "Рука на кошельке", "Полурослик-торговец: везёт и выгодно.", {"trade_profit_pct": 6, "luck": 1}, races=["halfling"], profs={"merchant": 1})
syn("halfling_farmer", "Хозяин очага", "Полурослик-земледелец.", {"farm_yield_pct": 8, "health_regen": 0.02}, races=["halfling"], profs={"farmer": 1})
syn("goblin_alchemist", "Взрывное дело", "Гоблин-алхимик: ни одного целого лабораторного стекла.", {"craft_quality_pct": 4, "crit_pct": 3}, races=["goblin"], profs={"alchemist": 1})
syn("goblin_thief", "Пальцы-пружины", "Гоблин-вор.", {"stealth_pct": 6, "gold_find_pct": 6}, races=["goblin"], profs={"thief": 1})
syn("saurian_hunter", "Болотный охотник", "Ящер-охотник: терпение и меткость.", {"ranged_dmg_pct": 5, "stealth_pct": 4}, races=["saurian"], profs={"hunter": 1})
syn("fae_healer", "Рощёвый целитель", "Сильф-лекарь: лечит одним прикосновением.", {"heal_pct": 10}, races=["fae"], profs={"healer": 1})
syn("fae_courtier", "Очарованный двор", "Сильф при дворе.", {"opinion": 4, "intrigue_bonus": 1}, races=["fae"], classes=["courtier"])
syn("shadekin_rogue", "Дитя теней", "Теневой плут.", {"stealth_pct": 8, "crit_pct": 3, "spy_success_pct": 4}, races=["shadekin"], classes=["rogue"])
syn("giant_warrior", "Колосс в бою", "Великан-воин.", {"melee_dmg_pct": 6, "max_health": 12}, races=["giant"], classes=["warrior"])
syn("giant_miner", "Горная глыба", "Великан в шахте.", {"mining_speed_pct": 10, "carry": 8}, races=["giant"], profs={"miner": 1})
syn("beastkin_ranger", "Охотник-побратим", "Зверолюд-следопыт.", {"stealth_pct": 5, "move_speed_pct": 3}, races=["beastkin"], classes=["ranger"])
syn("triton_merchant", "Купец побережья", "Тритон-торговец.", {"trade_profit_pct": 6, "fish_pct": 6}, races=["triton"], profs={"merchant": 1})
syn("triton_fisher", "Хозяин течений", "Тритон-рыбак.", {"fish_pct": 14}, races=["triton"], profs={"fisher": 1})
syn("construct_smith", "Мастер собственной шестерни", "Рукотворный-кузнец чинит и себя.", {"craft_quality_pct": 5, "health_regen": 0.02}, races=["construct"], profs={"smith": 1})
syn("construct_guard", "Неподвижный страж", "Рукотворный в страже.", {"armor": 2, "resist_pct": 4}, races=["construct"], profs={"guard": 1})
syn("human_noble", "Благородная кровь", "Человек-придворный.", {"opinion": 2, "tax_pct": 2}, races=["human"], classes=["courtier"])
syn("northman_berserk", "Северная ярость", "Северянин-берсерк.", {"melee_dmg_pct": 6, "max_health": 8}, races=["northman"], classes=["berserker"])
syn("smith_knight", "Кованый рыцарь", "Рыцарь, который сам кует доспехи.", {"armor": 2, "craft_quality_pct": 4}, classes=["knight"], profs={"smith": 1})
syn("ranger_hunter", "Следопыт-зверолов", "Следопыт-охотник.", {"ranged_dmg_pct": 4, "luck": 0.5}, classes=["ranger"], profs={"hunter": 1})
syn("physician_healer", "Врачебное дело", "Врач-лекарь.", {"heal_pct": 8}, classes=["physician"], profs={"healer": 1})
syn("scholar_scribe", "Усердный писец", "Учёный-писарь.", {"xp_gain_pct": 4, "tax_pct": 2}, classes=["scholar"], profs={"scribe": 1})
syn("rogue_merchant", "Тёмный торг", "Плут-торговец.", {"trade_profit_pct": 5, "gold_find_pct": 4}, classes=["rogue"], profs={"merchant": 1})
syn("guardian_guard", "Верный страж", "Хранитель-стражник.", {"armor": 2, "levy_pct": 3}, classes=["guardian"], profs={"guard": 1})
syn("courtier_merchant", "Торговый двор", "Придворный-купец.", {"trade_profit_pct": 4, "opinion": 2}, classes=["courtier"], profs={"merchant": 1})
syn("master_craft", "Мастер на все руки", "Две основные профессии на звании «мастер».", {"craft_quality_pct": 5, "xp_gain_pct": 3}, profs={"smith": 2, "mason": 2})
syn("vampire_noble", "Ночной двор", "Вампир при дворе.", {"opinion": 3, "intrigue_bonus": 2}, classes=["courtier"], flags=["form:vampire"])
syn("werewolf_hunter", "Зов луны", "Оборотень-охотник.", {"ranged_dmg_pct": 4, "move_speed_pct": 4}, classes=["ranger"], flags=["form:werewolf"])
syn("dragon_knight", "Рыцарь-змей", "Драконья кровь у рыцаря.", {"armor": 3, "melee_dmg_pct": 6}, classes=["knight"], flags=["form:dragon_blood"])
syn("zombie_smith", "Неутомимый кузнец", "Мёртвым не нужен отдых.", {"craft_quality_pct": 4, "max_health": 6}, profs={"smith": 1}, flags=["form:zombie"])
syn("cursed_scholar", "Книга проклятий", "Проклятый учёный видит дальше.", {"xp_gain_pct": 6, "luck": -0.5}, classes=["scholar"], flags=["form:cursed_blood"])
save('synergies', {"synergies": S})
print(len(R), len(C), len(P), len(S), len(PK))
