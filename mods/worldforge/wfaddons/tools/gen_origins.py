#!/usr/bin/env python3
"""Расширение wf-origins: новые расы и подрасы, ветвистые классы (до 4-й ступени, сходящиеся ветви, взаимоисключающие пути), профессии и специализации.
Запускать из wfaddons; перезаписывает *_ext.json."""
import json, os
D = os.path.join(os.path.dirname(__file__), '..', 'wf-origins', 'src', 'main', 'resources', 'data', 'wf_origins', 'wf_origins')
def save(name, key, items): json.dump({key: items}, open(os.path.join(D, name + '.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)

# ---------------- расы ----------------
R = []
def race(id, name, desc, color, mods, innate, traits, weight=6, parent=None):
    r = {"id": id, "name": name, "desc": desc, "color": color, "weight": weight, "mods": mods, "innate": innate, "traits": traits}
    if parent: r["parent"] = parent
    R.append(r)
race("goblin", "Гоблин", "Мелкий и шустрый народ нор и руин: ловкие руки, дурная слава.", 0x6E9B4A, {"dodge_pct": 4, "craft_quality_pct": 3, "luck": 1, "max_health": -3, "opinion": -1}, {"Ловкие пальцы": "+3% качества ремесла", "Везучий": "+1 удача"}, ["shrewd"], 5)
race("saurian", "Ящер", "Чешуйчатые жители болот и пустынь: холодная кровь, прочная шкура.", 0x4E8F6A, {"armor": 2, "resist_pct": 4, "health_regen": 0.02, "move_speed_pct": -2}, {"Чешуя": "+2 брони", "Живучесть": "регенерация"}, ["patient"], 4)
race("fae", "Сильф", "Лёгкий лесной народ: чуют людей, редко держат слово без выгоды.", 0xB69CE0, {"stealth_pct": 6, "dodge_pct": 4, "heal_pct": 5, "armor": -1, "max_health": -2}, {"Благословение рощи": "+5% лечения", "Тихий шаг": "+6% скрытности"}, ["shy"], 3)
race("shadekin", "Теневой", "Потомки тех, кто слишком долго жил в подземельях: видят в темноте, не любимы светом.", 0x5C5080, {"stealth_pct": 8, "spy_success_pct": 4, "crit_pct": 2, "opinion": -2}, {"Ночное зрение": "видит в темноте", "Шёпот теней": "+4% успеха шпионажа"}, ["ambitious"], 3)
# подрасы: тип (родитель, id, имя, описание, цвет, mods, innate, traits)
sub = [
 ("human", "nomad", "Кочевник", "Степные всадники: быстры, привычны к дорогам и торгу на ходу.", 0xD9A35E, {"move_speed_pct": 4, "trade_profit_pct": 3, "armor": -1}, {"Дитя дорог": "+4% скорости"}, ["gregarious"]),
 ("human", "highborn", "Знатный", "Выросшие при дворе: слово весомо, руки мягки.", 0xE0C050, {"opinion": 3, "tax_pct": 2, "max_health": -2}, {"Воспитание": "+3 расположения"}, ["ambitious"]),
 ("northman", "seawolf", "Морской волк", "Мореходы фьордов: стойки и расчётливы в бою.", 0x5C86A8, {"melee_dmg_pct": 4, "carry": 4}, {"Солёная кровь": "+4% урона в ближнем бою"}, ["brave"]),
 ("elf", "dark_elf", "Тёмный эльф", "Подземные родичи эльфов: ядовитый язык и верная рука.", 0x6C5C9A, {"crit_pct": 3, "stealth_pct": 3, "spy_success_pct": 3, "heal_pct": -3}, {"Острый нрав": "+3% крита"}, ["cruel"]),
 ("dwarf", "deep_dwarf", "Глубинный гном", "Жители нижних копей: терпеливы, суровы, хорошо бьют.", 0x8C6A4A, {"mining_speed_pct": 6, "melee_dmg_pct": 3, "move_speed_pct": -2}, {"Глубинная закалка": "+6% скорости добычи"}, ["diligent"]),
 ("dwarf", "forge_dwarf", "Горновой гном", "Кузнечные кланы: огонь и металл - их язык.", 0xC86432, {"craft_quality_pct": 6, "weapon_dmg_pct": 2}, {"Огненное сердце": "+6% качества ремесла"}, ["diligent"]),
 ("halfling", "river_halfling", "Речной полурослик", "Лодочники и рыбаки: везучи и неприметны.", 0x78A0C0, {"luck": 1, "stealth_pct": 3, "farm_yield_pct": 3}, {"Речная удача": "+1 удача"}, ["content"]),
 ("orc", "steppe_orc", "Степной орк", "Орды равнин: быстры, жестоки, любят набеги.", 0x9C7C34, {"move_speed_pct": 4, "levy_pct": 6, "melee_dmg_pct": 3}, {"Зов орды": "+6% ополчения"}, ["wroth"]),
 ("orc", "mountain_orc", "Горный орк", "Суровые кланы нагорий: стойкие бойцы, плохие торговцы.", 0x5C6C3C, {"max_health": 4, "armor": 1, "discount_pct": -3}, {"Каменная шкура": "+1 броня"}, ["brave"]),
 ("goblin", "city_goblin", "Городской гоблин", "Рыночные пройдохи: торгуют, воруют, дружат со всеми.", 0x84A860, {"trade_profit_pct": 4, "opinion": 1, "luck": 0.5}, {"Рыночный глаз": "+4% прибыли в торговле"}, ["shrewd"]),
 ("goblin", "cave_goblin", "Пещерный гоблин", "Копатели и взрывники подземелий.", 0x587848, {"mining_speed_pct": 6, "dodge_pct": 2}, {"Копатель": "+6% скорости добычи"}, ["wroth"]),
 ("saurian", "marsh_saurian", "Болотный ящер", "Скрытные охотники трясин.", 0x3E7A52, {"stealth_pct": 5, "ranged_dmg_pct": 4}, {"Болотный охотник": "+5% скрытности"}, ["patient"]),
 ("saurian", "desert_saurian", "Пустынный ящер", "Жители барханов: не боятся зноя, торгуют караванами.", 0xC8A050, {"resist_pct": 4, "trade_profit_pct": 3}, {"Зной не страшен": "+4% сопротивления"}, ["shrewd"]),
 ("fae", "court_fae", "Дворовый сильф", "Интриганы и танцоры: красота как оружие.", 0xD090E0, {"opinion": 3, "intrigue_bonus": 1}, {"Очарование": "+3 расположения"}, ["gregarious"]),
 ("fae", "grove_fae", "Рощёвый сильф", "Целители и следопыты лесных кругов.", 0x86C08A, {"heal_pct": 6, "ranged_dmg_pct": 3}, {"Дар рощи": "+6% лечения"}, ["scholar"]),
 ("shadekin", "dusk_shadekin", "Сумеречный", "Живущие на границе света и тьмы, торгуют секретами.", 0x7A6AA0, {"intrigue_bonus": 1, "spy_success_pct": 3, "gold_find_pct": 4}, {"Торговля тайнами": "+4% добычи золота"}, ["shrewd"]),
]
for p, i, n, d, c, m, inn, t in sub: race(i, n, d, c, m, inn, t, 3, p)
save('races_ext', 'races', R)

# ---------------- классы ----------------
C = []
def cls(id, name, desc, tier, frm, level, stat, mods, perks=None, arch="", also=None, excl="", prof=None, flags=None, races=None):
    c = {"id": id, "name": name, "desc": desc, "tier": tier, "from": frm, "min_level": level, "mods": mods}
    if arch: c["archetype"] = arch
    if stat: c["req_stat"] = stat
    if perks: c["perks"] = {str(l): {"name": n, "mods": m} for l, (n, m) in perks.items()}
    if also: c["also_from"] = also
    if excl: c["exclusive"] = excl
    if prof: c["req_prof"] = prof
    if flags: c["req_flags"] = flags
    if races: c["req_races"] = races
    C.append(c)
# воин
cls("duelist", "Дуэлянт", "Один на один быстрее и точнее любого рыцаря.", 2, "warrior", 15, {"agility": 10}, {"crit_pct": 4, "dodge_pct": 4, "melee_dmg_pct": 3}, {20: ("Выпад", {"crit_pct": 3})}, "warrior")
cls("sellsword", "Наёмник", "Служит тому, кто платит: знает цену крови и золоту.", 2, "warrior", 15, {"martial": 8}, {"gold_find_pct": 6, "melee_dmg_pct": 3, "max_health": 4}, {20: ("Контракт", {"discount_pct": 3})}, "warrior", prof={"soldier": 1})
cls("legionary", "Легионер", "Строй, щит и дисциплина.", 2, "warrior", 15, {"vitality": 8}, {"armor": 3, "levy_pct": 5, "max_health": 4}, {20: ("Черепаха", {"armor": 2})}, "warrior", prof={"guard": 1})
cls("blademaster", "Мастер клинка", "Дуэль доведена до искусства.", 3, "duelist", 30, {"agility": 14, "martial": 10}, {"crit_pct": 6, "melee_dmg_pct": 8, "dodge_pct": 4}, {35: ("Танец стали", {"crit_pct": 4})}, excl="stance")
cls("iron_wall", "Железная стена", "Не бьёт первым, но и не падает.", 3, "legionary", 30, {"vitality": 14}, {"armor": 6, "max_health": 12, "thorns": 1}, {35: ("Несокрушимый", {"resist_pct": 6})}, excl="stance")
cls("reaver", "Опустошитель", "Берсерк, пьяный от крови.", 3, "berserker", 30, {"might": 14}, {"melee_dmg_pct": 14, "max_health": 8, "armor": -2}, excl="oath")
cls("wolfsworn", "Волчий присягнувший", "Клятва стае: сражается рядом с побратимами.", 3, "berserker", 30, {"might": 12, "diplomacy": 6}, {"levy_pct": 8, "melee_dmg_pct": 8, "opinion": 2}, excl="oath")
cls("oath_mercy", "Рыцарь милосердия", "Клятва щадить побеждённых и защищать слабых.", 3, "knight", 30, {"spirit": 10}, {"heal_pct": 8, "opinion": 4, "armor": 3}, {35: ("Рука помощи", {"health_regen": 0.03})}, excl="oath", flags=["quest:kind_heart"])
cls("oath_vengeance", "Рыцарь возмездия", "Клятва не оставлять обиды без ответа.", 3, "knight", 30, {"might": 12}, {"melee_dmg_pct": 10, "crit_pct": 3, "opinion": -2}, excl="oath")
cls("paragon", "Образец рыцарства", "Легенда, которой клянутся оруженосцы.", 4, "champion", 50, {"might": 18, "martial": 14}, {"melee_dmg_pct": 12, "armor": 6, "levy_pct": 8, "opinion": 4, "max_health": 14}, {55: ("Знамя отваги", {"levy_pct": 6})})
cls("grand_marshal", "Великий маршал", "Командует армиями державы.", 4, "warlord", 50, {"martial": 20}, {"levy_pct": 20, "opinion": 4, "melee_dmg_pct": 6}, also=["champion"])
cls("avatar_of_war", "Воплощение войны", "Там, где он идёт, битвы не заканчиваются.", 4, "reaver", 50, {"might": 20}, {"melee_dmg_pct": 22, "max_health": 20, "crit_pct": 6}, also=["wolfsworn"])
cls("battle_surgeon", "Полевой хирург", "Воин, умеющий и резать, и зашивать.", 3, "knight", 30, {"learning": 10}, {"heal_pct": 14, "max_health": 8, "armor": 2}, also=["physician", "bulwark"], prof={"healer": 2})
# страж
cls("sentinel", "Часовой", "Видит всё и не спит.", 2, "guardian", 15, {"agility": 8}, {"armor": 3, "dodge_pct": 3, "stealth_pct": -2}, {20: ("Бдительность", {"crit_pct": 2})}, "guardian", prof={"guard": 1})
cls("fortress_lord", "Владыка крепости", "Держит стены и казну гарнизона.", 3, "bulwark", 30, {"stewardship": 10, "vitality": 12}, {"armor": 6, "levy_pct": 10, "tax_pct": 3, "max_health": 10})
cls("shield_captain", "Капитан щита", "Выводит стражу и ополчение в стройный отряд.", 3, "bulwark", 30, {"martial": 10}, {"armor": 5, "levy_pct": 12, "opinion": 2}, also=["sentinel"])
cls("living_bastion", "Живой бастион", "Лучше любой стены.", 4, "fortress_lord", 50, {"vitality": 20}, {"armor": 10, "max_health": 30, "resist_pct": 10, "thorns": 2}, also=["shield_captain", "iron_wall"])
# следопыт
cls("skirmisher", "Застрельщик", "Бьёт издалека и отходит.", 2, "ranger", 15, {"agility": 10}, {"ranged_dmg_pct": 6, "move_speed_pct": 4, "dodge_pct": 3}, {20: ("Отход", {"move_speed_pct": 3})}, "ranger")
cls("beastmaster", "Повелитель зверей", "Звери идут за ним по своей воле.", 3, "warden", 30, {"spirit": 10}, {"opinion": 2, "carry": 8, "health_regen": 0.03, "ranged_dmg_pct": 4})
cls("pathwarden", "Страж троп", "Знает каждую тропу и каждый брод.", 3, "warden", 30, {"agility": 12}, {"move_speed_pct": 8, "stealth_pct": 8, "luck": 1}, also=["skirmisher"])
cls("horse_archer", "Конный лучник", "Стреляет на скаку.", 3, "marksman", 30, {"agility": 14}, {"ranged_dmg_pct": 12, "move_speed_pct": 6, "crit_pct": 3}, also=["skirmisher"])
cls("wild_legend", "Легенда дикого края", "О нём слагают песни в лесах.", 4, "pathwarden", 50, {"agility": 18, "spirit": 14}, {"stealth_pct": 12, "ranged_dmg_pct": 14, "move_speed_pct": 10, "luck": 2}, also=["beastmaster", "deadeye"])
# учёный
cls("apothecary", "Аптекарь", "Настои и мази на любой случай.", 2, "scholar", 15, {"intellect": 9}, {"heal_pct": 8, "craft_quality_pct": 4}, {20: ("Крепкий настой", {"heal_pct": 4})}, "scholar", prof={"alchemist": 1})
cls("loremaster", "Знаток преданий", "Помнит всё, что было записано.", 2, "scholar", 15, {"learning": 10}, {"xp_gain_pct": 8, "opinion": 1, "tax_pct": 2}, {20: ("Память веков", {"xp_gain_pct": 3})}, "scholar", prof={"scribe": 1})
cls("savant", "Мудрец", "Знание - его власть.", 3, "loremaster", 30, {"learning": 14, "intellect": 10}, {"xp_gain_pct": 14, "craft_quality_pct": 6, "opinion": 2}, excl="lore")
cls("inventor", "Изобретатель", "Придумывает машины и ломает старые правила.", 3, "artificer", 30, {"intellect": 14}, {"craft_quality_pct": 12, "build_speed_pct": 8, "luck": 1}, excl="lore")
cls("poison_master", "Мастер ядов", "Знает, чем лечат и чем убивают.", 3, "apothecary", 30, {"intellect": 12}, {"crit_pct": 4, "spy_success_pct": 6, "heal_pct": 6}, also=["assassin"], excl="alch")
cls("grand_healer", "Великий целитель", "Возвращает с порога смерти.", 3, "apothecary", 30, {"spirit": 12}, {"heal_pct": 20, "health_regen": 0.05, "opinion": 3}, excl="alch")
cls("sage_of_ages", "Мудрец веков", "Живая библиотека державы.", 4, "savant", 50, {"learning": 20}, {"xp_gain_pct": 22, "craft_quality_pct": 10, "tax_pct": 4, "opinion": 4}, also=["inventor", "archmage_scribe"])
# придворный
cls("envoy", "Посол", "Говорит от имени державы.", 2, "courtier", 15, {"diplomacy": 10}, {"opinion": 4, "intrigue_bonus": 1, "discount_pct": 3}, {20: ("Дипломатическая неприкосновенность", {"opinion": 2})}, "courtier")
cls("merchant_prince", "Торговый князь", "Купеческая империя на одном имени.", 2, "courtier", 15, {"stewardship": 8}, {"trade_profit_pct": 8, "discount_pct": 5, "tax_pct": 3}, {20: ("Кредит", {"trade_profit_pct": 3})}, "courtier", prof={"merchant": 1})
cls("treasurer", "Казначей", "Каждая монета на счету.", 2, "courtier", 15, {"stewardship": 10}, {"tax_pct": 8, "trade_profit_pct": 3, "opinion": -1}, {}, "courtier", prof={"scribe": 1})
cls("grand_vizier", "Великий визирь", "Правая рука правителя.", 3, "chancellor", 30, {"stewardship": 14, "diplomacy": 12}, {"tax_pct": 8, "opinion": 4, "levy_pct": 4, "intrigue_bonus": 1}, excl="court")
cls("grand_envoy", "Верховный посол", "Заключает союзы и расторгает войны.", 3, "envoy", 30, {"diplomacy": 14}, {"opinion": 8, "discount_pct": 6, "intrigue_bonus": 2}, also=["chancellor"], excl="court")
cls("trade_magnate", "Магнат", "Деньги решают всё.", 3, "merchant_prince", 30, {"stewardship": 14}, {"trade_profit_pct": 14, "discount_pct": 8, "tax_pct": 4}, also=["treasurer"], excl="court")
cls("kingmaker", "Делатель королей", "Не носит короны, но короны носят по его воле.", 4, "grand_vizier", 50, {"diplomacy": 20, "intrigue": 14}, {"opinion": 10, "intrigue_bonus": 3, "tax_pct": 8, "levy_pct": 8}, also=["grand_envoy", "trade_magnate"])
# плут
cls("smuggler", "Контрабандист", "Ночные тропы и тайные склады.", 2, "rogue", 15, {"agility": 8}, {"gold_find_pct": 8, "trade_profit_pct": 4, "stealth_pct": 4}, {20: ("Двойное дно", {"carry": 6})}, "rogue", prof={"merchant": 1})
cls("trickster", "Обманщик", "Языком добьётся большего, чем ножом.", 2, "rogue", 15, {"intrigue": 8}, {"opinion": 3, "luck": 1, "stealth_pct": 3}, {20: ("Подмена", {"spy_success_pct": 4})}, "rogue")
cls("nightblade", "Ночной клинок", "Бесшумная смерть.", 3, "assassin", 30, {"agility": 14}, {"crit_pct": 10, "stealth_pct": 10, "melee_dmg_pct": 6}, excl="shadow")
cls("whisperer", "Шепчущий", "Знает чужие секреты раньше, чем их хозяева.", 3, "spymaster", 30, {"intrigue": 14}, {"spy_success_pct": 12, "intrigue_bonus": 3, "opinion": 2}, also=["trickster"], excl="shadow")
cls("guildmaster_thief", "Глава воровского братства", "Город живёт по его правилам.", 3, "smuggler", 30, {"intrigue": 12}, {"gold_find_pct": 14, "stealth_pct": 8, "tax_pct": -4, "opinion": 2}, also=["trickster"], excl="shadow")
cls("phantom", "Призрак", "О нём не знает никто, а он знает всё.", 4, "nightblade", 50, {"agility": 20}, {"stealth_pct": 20, "crit_pct": 12, "dodge_pct": 8, "spy_success_pct": 8}, also=["whisperer", "shadow_master"])
save('classes_ext', 'classes', C)

# ---------------- профессии ----------------
P = []
def ranks(names, xps, mods): return [{"name": n, "xp": x, "mods": (mods if i else {})} for i, (n, x) in enumerate(zip(names, xps))]
def prof(id, name, desc, group, workplace, wage, stat, rk, mods):
    p = {"id": id, "name": name, "desc": desc, "group": group, "workplace": workplace, "wage": wage, "ranks": ranks(rk, [0, 150, 600, 2000], mods)}
    if stat: p["req_stat"] = stat
    P.append(p)
def spec(id, name, desc, frm, rank, wage, mods, rk, excl="", classes=None, wp=""):
    p = {"id": id, "name": name, "desc": desc, "from": frm, "min_rank": rank, "wage": wage, "ranks": ranks(rk, [0, 200, 800, 2500], mods)}
    if excl: p["exclusive"] = excl
    if classes: p["req_classes"] = classes
    if wp: p["workplace"] = wp
    P.append(p)
prof("fisher", "Рыбак", "Река и море кормят.", "farm", "fishery", 4, None, ["Подмастерье", "Рыбак", "Мастер лова", "Старый морской волк"], {"luck": 0.5, "carry": 3})
prof("herbalist", "Травник", "Лесные травы лечат и кормят.", "farm", "healer_hut", 4, None, ["Сборщик", "Травник", "Знахарь", "Мудрый травник"], {"heal_pct": 3, "luck": 0.3})
prof("tailor", "Портной", "Одежда и знамёна.", "craft", "tailor", 5, None, ["Подмастерье", "Портной", "Мастер иглы", "Придворный портной"], {"craft_quality_pct": 3, "opinion": 0.5})
prof("tanner", "Кожевник", "Кожа и упряжь.", "craft", "tannery", 5, None, ["Подмастерье", "Кожевник", "Мастер кожи", "Мастер-шорник"], {"craft_quality_pct": 3, "armor": 0.3})
prof("baker", "Пекарь", "Хлеб - основа любого города.", "service", "bakery", 4, None, ["Подмастерье", "Пекарь", "Мастер печи", "Хлебодар"], {"health_regen": 0.01, "opinion": 0.5})
prof("shipwright", "Корабел", "Лодки, барки, корабли.", "craft", "shipyard", 7, {"might": 5}, ["Подмастерье", "Плотник верфи", "Корабел", "Мастер флота"], {"craft_quality_pct": 3, "build_speed_pct": 3})
prof("bard", "Бард", "Песни, новости и слухи.", "service", "tavern", 4, {"diplomacy": 5}, ["Уличный певец", "Бард", "Придворный бард", "Легенда сцены"], {"opinion": 1, "xp_gain_pct": 1})
prof("cartographer_p", "Картограф", "Карты, пути и границы.", "scholar", "library", 6, {"learning": 6}, ["Чертёжник", "Картограф", "Мастер карт", "Королевский картограф"], {"move_speed_pct": 1, "xp_gain_pct": 2})
prof("engineer", "Инженер", "Механизмы, осадные машины, мосты.", "craft", "workshop", 8, {"intellect": 8}, ["Подмастерье", "Инженер", "Мастер-инженер", "Главный инженер"], {"build_speed_pct": 4, "craft_quality_pct": 2})
prof("trainer", "Конюший", "Лошади и боевые звери.", "military", "stables", 6, {"agility": 5}, ["Конюх", "Объездчик", "Мастер-конюший", "Главный конюший"], {"move_speed_pct": 2, "carry": 3})
# специализации
spec("bladesmith", "Мастер мечей", "Клинки, что поют.", "smith", 2, 10, {"weapon_dmg_pct": 3, "crit_pct": 1}, ["Подмастерье мечей", "Мечник-кузнец", "Мастер мечей", "Легенда клинка"], "smithtype")
spec("polearm_smith", "Мастер древкового", "Копья, алебарды и боевые молоты.", "smith", 2, 9, {"weapon_dmg_pct": 3, "melee_dmg_pct": 1}, ["Подмастерье", "Мастер копий", "Мастер алебард", "Легенда древка"], "smithtype")
spec("banker", "Банкир", "Ссуды, векселя и проценты.", "merchant", 2, 12, {"tax_pct": 2, "trade_profit_pct": 3}, ["Меняла", "Банкир", "Глава банка", "Казначей державы"], "trade", wp="bank")
spec("smuggler_trader", "Скупщик", "Всё, что нельзя продавать, продаётся здесь.", "merchant", 2, 9, {"trade_profit_pct": 5, "gold_find_pct": 3}, ["Перекупщик", "Скупщик", "Барыга", "Король чёрного рынка"], "trade", wp="market")
spec("poisoner", "Отравитель", "Лечит и калечит одним ремеслом.", "alchemist", 2, 11, {"crit_pct": 2, "spy_success_pct": 2}, ["Подмастерье", "Травильщик", "Отравитель", "Мастер ядов"], "alch")
spec("apothecary_p", "Аптекарь", "Лекарства и укрепляющие настои.", "alchemist", 2, 10, {"heal_pct": 6, "health_regen": 0.02}, ["Подмастерье", "Аптекарь", "Старший аптекарь", "Мастер снадобий"], "alch")
spec("sapper", "Сапёр", "Подкопы и осадные работы.", "soldier", 2, 9, {"mining_speed_pct": 4, "build_speed_pct": 3}, ["Землекоп", "Сапёр", "Мастер подкопа", "Главный сапёр"], "troop")
spec("standard_bearer", "Знаменосец", "Держит знамя там, где всего опаснее.", "soldier", 2, 9, {"levy_pct": 4, "armor": 1}, ["Оруженосец", "Знаменосец", "Старший знаменосец", "Хранитель штандарта"], "troop")
spec("drillmaster", "Муштровщик", "Из новобранцев делает солдат.", "soldier", 3, 11, {"levy_pct": 6, "xp_gain_pct": 3}, ["Капрал", "Муштровщик", "Старший сержант", "Мастер строя"], "troop")
spec("notary", "Нотариус", "Печати, договоры, наследства.", "scribe", 2, 9, {"tax_pct": 3, "opinion": 1}, ["Копиист", "Нотариус", "Старший нотариус", "Государственный нотариус"], "scribetype")
spec("chronicler", "Летописец", "Записывает всё, что было.", "scribe", 2, 9, {"xp_gain_pct": 5, "luck": 0.3}, ["Переписчик", "Хронист", "Летописец", "Придворный летописец"], "scribetype")
spec("architect", "Зодчий", "Здания, что стоят веками.", "mason", 2, 10, {"build_speed_pct": 6, "craft_quality_pct": 3}, ["Подмастерье", "Зодчий", "Мастер-зодчий", "Главный зодчий"], "buildtype")
spec("fortifier", "Фортификатор", "Стены, башни и рвы.", "mason", 2, 10, {"armor": 1, "build_speed_pct": 3}, ["Подмастерье", "Фортификатор", "Мастер крепостей", "Главный фортификатор"], "buildtype")
spec("beekeeper", "Пасечник", "Мёд и воск.", "farmer", 1, 6, {"farm_yield_pct": 3, "heal_pct": 2}, ["Подмастерье", "Пасечник", "Мастер мёда", "Хранитель ульев"])
spec("vintner", "Винодел", "Вино, что пьют короли.", "farmer", 2, 8, {"trade_profit_pct": 3, "opinion": 1}, ["Виноградарь", "Винодел", "Мастер вина", "Королевский виночерпий"])
spec("falconer", "Сокольничий", "Охота с ловчими птицами.", "hunter", 2, 8, {"ranged_dmg_pct": 3, "luck": 0.5}, ["Птичник", "Сокольничий", "Старший сокольничий", "Мастер ловчих"])
spec("captain_guard", "Начальник стражи", "Порядок в городе - на нём.", "guard", 2, 10, {"armor": 1, "levy_pct": 3, "opinion": 1}, ["Десятник", "Начальник стражи", "Старший начальник", "Главный страж"], classes=["warrior", "guardian", "knight", "bulwark", "legionary", "sentinel"])
spec("battle_medic", "Полевой лекарь", "Лечит под стрелами.", "healer", 2, 10, {"heal_pct": 6, "max_health": 2}, ["Санитар", "Полевой лекарь", "Старший лекарь", "Мастер походной медицины"], classes=["warrior", "guardian", "knight", "physician", "apothecary", "battle_surgeon"])
save('professions_ext', 'professions', P)
print(len(R), len(C), len(P))
