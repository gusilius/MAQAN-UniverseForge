#!/usr/bin/env python3
"""Генерирует data/wf_genesis/wf_genesis/genesis.json: мастер создания персонажа (внешность, старты, поселения правителя)."""
import json, os
OUT = os.path.join(os.path.dirname(__file__), '..', 'wf-genesis', 'src', 'main', 'resources', 'data', 'wf_genesis', 'wf_genesis', 'genesis.json')

def C(*h): return [int(x.lstrip('#'), 16) for x in h]

d = {}
d["config"] = {"attr_budget": 40, "attr_max_delta": 8, "attr_soft_cap": 4, "prof_picks": 2, "skill_points": 3, "name_max": 24}
d["groups"] = {"body": "Тело", "mind": "Разум", "social": "Общение", "court": "Двор", "politics": "Власть и право", "econ": "Хозяйство и ремесло", "soul": "Дух"}
d["sliders"] = [
    {"id": "height", "name": "Рост", "desc": "Выше или ниже обычного для расы.", "min": 80, "max": 125, "def": 100, "unit": "%"},
    {"id": "width", "name": "Телосложение", "desc": "Худощавый или плотный.", "min": 80, "max": 125, "def": 100, "unit": "%"},
    {"id": "head", "name": "Размер головы", "desc": "", "min": 85, "max": 120, "def": 100, "unit": "%"},
    {"id": "shoulders", "name": "Плечи", "desc": "Узкие или широкие.", "min": 85, "max": 125, "def": 100, "unit": "%"},
    {"id": "age", "name": "Возраст", "desc": "Влияет на седину и морщины.", "min": 16, "max": 90, "def": 25, "unit": " лет"},
    {"id": "scars", "name": "Шрамы", "desc": "Следы прошлых боёв.", "min": 0, "max": 5, "def": 0, "unit": ""},
    {"id": "voice", "name": "Голос", "desc": "Низкий или высокий.", "min": 0, "max": 10, "def": 5, "unit": ""},
]
d["palettes"] = {
    "skin": C("f1c9a5", "e0ac85", "c68642", "8d5524", "5b3a1e", "f8dcc8", "b5d99c", "9fc5e8", "c9c9d6", "7a8a5a"),
    "hair": C("2b1d12", "5a3a1e", "a0652a", "d4a24c", "e8d9a0", "b5361f", "1c1c1c", "d9d9d9", "4a6fa5", "6a3d9a"),
    "eyes": C("4b2e1a", "2e6f4e", "3a6ea5", "7d7d7d", "c9a227", "a52a2a", "6a3d9a", "1c1c1c"),
}
d["hair_styles"] = [["short", "Короткая"], ["long", "Длинная"], ["braid", "Коса"], ["ponytail", "Хвост"], ["bun", "Пучок"], ["mohawk", "Ирокез"], ["bald", "Лысый"], ["curly", "Кудри"], ["shaved_sides", "Бритые виски"], ["mane", "Грива"]]
FEATURES = [
    ("pointed_ears", "Заострённые уши", "ears", ["elf", "fae", "goblin", "shadekin"]),
    ("long_ears", "Длинные уши", "ears", ["elf", "goblin", "halfling"]),
    ("animal_ears", "Звериные уши", "ears", ["beastkin"]),
    ("short_horns", "Короткие рога", "horns", ["orc", "beastkin", "giant", "saurian"]),
    ("curved_horns", "Изогнутые рога", "horns", ["beastkin", "shadekin", "giant"]),
    ("tusks", "Клыки", "mouth", ["orc", "goblin", "giant"]),
    ("fangs", "Острые зубы", "mouth", ["saurian", "shadekin", "beastkin"]),
    ("long_beard", "Длинная борода", "beard", ["dwarf", "human", "northman", "giant"]),
    ("braided_beard", "Заплетённая борода", "beard", ["dwarf", "northman"]),
    ("tail", "Хвост", "tail", ["beastkin", "saurian", "shadekin"]),
    ("fox_tail", "Пушистый хвост", "tail", ["beastkin"]),
    ("scales", "Чешуя", "skin_fx", ["saurian"]),
    ("fur", "Мех", "skin_fx", ["beastkin"]),
    ("runes", "Руны на коже", "skin_fx", ["dwarf", "construct", "fae"]),
    ("gills", "Жабры", "neck", ["triton"]),
    ("fins", "Плавники", "ears", ["triton"]),
    ("glow_eyes", "Светящиеся глаза", "eyes_fx", ["shadekin", "fae", "construct"]),
    ("tribal_paint", "Боевая раскраска", "paint", ["orc", "northman", "goblin", "human"]),
    ("tattoos", "Татуировки", "paint", ["human", "northman", "halfling", "orc", "dwarf"]),
    ("freckles", "Веснушки", "face", ["human", "halfling", "elf"]),
]
d["features"] = [{"id": i, "name": n, "group": g, "races": r, "desc": ""} for i, n, g, r in FEATURES]
d["race_look"] = {
    "dwarf": {"height": 85, "width": 115, "shoulders": 115, "skin": "#e0ac85", "hair": "#a0652a"},
    "halfling": {"height": 80, "width": 105, "head": 105, "skin": "#f1c9a5"},
    "orc": {"height": 112, "width": 115, "shoulders": 120, "skin": "#7a8a5a", "eyes": "#a52a2a"},
    "elf": {"height": 108, "width": 90, "age": 120 if False else 60, "skin": "#f8dcc8", "hair": "#e8d9a0"},
    "goblin": {"height": 82, "width": 90, "head": 115, "skin": "#b5d99c"},
    "giant": {"height": 125, "width": 125, "shoulders": 125, "skin": "#c9c9d6"},
    "saurian": {"skin": "#7a8a5a", "eyes": "#c9a227"},
    "shadekin": {"skin": "#c9c9d6", "hair": "#1c1c1c", "eyes": "#6a3d9a"},
    "triton": {"skin": "#9fc5e8", "hair": "#4a6fa5"},
    "northman": {"height": 108, "shoulders": 112, "hair": "#d4a24c"},
}
d["styles"] = {
    "_default": {"village": "Деревня", "town": "Город", "capital": "Столица", "ruler_village": "Староста", "ruler_town": "Бургомистр", "ruler_capital": "Государь"},
    "elf": {"village": "Роща", "town": "Лесной город", "capital": "Древесная столица", "ruler_village": "Хранитель рощи", "ruler_town": "Совет-старейшина", "ruler_capital": "Владыка"},
    "dwarf": {"village": "Горный чертог", "town": "Подгорный город", "capital": "Каменная столица", "ruler_village": "Старейшина чертога", "ruler_town": "Лорд-гном", "ruler_capital": "Король под горой"},
    "orc": {"village": "Стойбище", "town": "Становище", "capital": "Твердыня орды", "ruler_village": "Вожак", "ruler_town": "Военный вождь", "ruler_capital": "Великий хан"},
    "halfling": {"village": "Холмы", "town": "Речное поселение", "capital": "Большие Холмы", "ruler_village": "Староста холмов", "ruler_town": "Мэр", "ruler_capital": "Старший мэр"},
    "goblin": {"village": "Нора", "town": "Подземный рынок", "capital": "Город-свалка", "ruler_village": "Старший по норе", "ruler_town": "Король барахолки", "ruler_capital": "Великий гоблин"},
    "northman": {"village": "Хутор", "town": "Город конунга", "capital": "Тронный зал севера", "ruler_village": "Хёвдинг", "ruler_town": "Ярл", "ruler_capital": "Конунг"},
    "triton": {"village": "Коралловый посёлок", "town": "Подводный город", "capital": "Жемчужный дворец", "ruler_village": "Хранитель отмели", "ruler_town": "Владыка течений", "ruler_capital": "Морской король"},
    "saurian": {"village": "Гнездовье", "town": "Болотный город", "capital": "Храм-столица", "ruler_village": "Мудрый змей", "ruler_town": "Жрец-правитель", "ruler_capital": "Верховный жрец"},
}
d["name_pool"] = {
    "_default": ["Берёзовка", "Заречье", "Малый Брод", "Сосновый Угол", "Ясная Падь", "Каменка", "Новая Слобода"],
    "elf": ["Эльдарион", "Лаурелин", "Сильвавен", "Ирисмир", "Талавар"],
    "dwarf": ["Дум-Карак", "Кхазад-Тор", "Глубокий Молот", "Железный Зев", "Кальдрум"],
    "orc": ["Гром-Кар", "Красный Клык", "Чёрная Яма", "Зуб Ярости", "Урук-Тар"],
    "halfling": ["Малые Холмы", "Тёплая Нора", "Сладкий Ручей", "Пирожки", "Зелёные Бугры"],
    "goblin": ["Ржавая Нора", "Хлам-Таун", "Крысиный Угол", "Блестяшка"],
    "northman": ["Ульфсвик", "Железный Фьорд", "Скальхейм", "Вороний Мыс"],
    "triton": ["Перламутр", "Глубь-Рифа", "Солёный Купол"],
}
d["tiers"] = {
    "village": {"name": "Деревня", "workers": 6, "stock": {"wood": 80, "food": 60, "stone": 20}, "buildings": ["house", "house", "farm"]},
    "town": {"name": "Город", "workers": 14, "stock": {"wood": 160, "food": 120, "stone": 80}, "buildings": ["house", "house", "house", "farm", "farm", "lumber_camp", "quarry"]},
    "capital": {"name": "Столица", "workers": 28, "stock": {"wood": 320, "food": 240, "stone": 200}, "buildings": ["house", "house", "house", "house", "farm", "farm", "farm", "lumber_camp", "sawmill", "quarry", "granary", "watch_post"]},
}
d["focus"] = {
    "farming": {"name": "Земледелие", "desc": "Больше запасов еды: растут быстрее.", "stock": {"food": 60}, "workers": 0},
    "forestry": {"name": "Лесное дело", "desc": "Запас дерева на стройку.", "stock": {"wood": 80}, "workers": 0},
    "mining": {"name": "Горное дело", "desc": "Камень на стены и башни.", "stock": {"stone": 60}, "workers": 0},
    "people": {"name": "Люди", "desc": "Больше поселенцев на старте.", "stock": {}, "workers": 4},
}
S = []
def start(id, name, desc, kind="member", spawn="settlement", tier="", **kw):
    s = {"id": id, "name": name, "desc": desc, "kind": kind, "spawn": spawn}
    if tier: s["tier"] = tier
    s.update(kw); S.append(s)
start("villager", "Селянин", "Родились в деревне: скромно, но спокойно. Свой дом, поле и соседи.", tier="village", gold=2, items={"bread": 6, "wooden_hoe": 1, "stone_axe": 1}, prof_xp=30, flags=["origin:villager"], standing={"realm": 5})
start("townsman", "Горожанин", "Выросли на улицах города: ремесло, рынок, стража на углу.", tier="town", gold=5, items={"bread": 6, "iron_pickaxe": 1}, prof_xp=20, flags=["origin:townsman"], standing={"realm": 5})
start("noble_capital", "Дворянин столицы", "Знатная семья при дворе: деньги, связи, шпионы и враги.", tier="capital", gold=25, items={"bread": 8, "iron_sword": 1, "leather_chestplate": 1}, attr_bonus=-4, skill_bonus=1, mods={"opinion": 2}, flags=["origin:noble", "noble"], standing={"realm": 15})
start("soldier", "Новобранец гарнизона", "Присягнули, получили меч и койку в казарме.", tier="town", gold=3, items={"bread": 8, "iron_sword": 1, "shield": 1, "leather_chestplate": 1}, mods={"melee_dmg_pct": 2}, flags=["origin:soldier"], standing={"realm": 8})
start("merchant", "Странствующий торговец", "Повозка, товар и дальние дороги. Без дома, зато с кошельком.", spawn="random", gold=12, items={"bread": 6, "emerald": 6}, mods={"trade_profit_pct": 3, "discount_pct": 2}, flags=["origin:merchant"])
start("wanderer", "Скиталец", "Нет ни дома, ни имени. Мир велик и неизвестен.", spawn="random", gold=1, items={"bread": 4, "stone_sword": 1}, skill_bonus=1, flags=["origin:wanderer"])
start("hermit", "Отшельник", "Годы вдали от людей: тишина, травы, книги.", spawn="random", items={"bread": 4, "book": 3}, mods={"resist_pct": 3, "xp_gain_pct": 3}, flags=["origin:hermit"])
start("exile", "Изгнанник", "Лишены рода и земель. Враги помнят, друзья забыли.", spawn="random", gold=8, items={"bread": 3, "iron_sword": 1}, attr_bonus=2, flags=["origin:exile"], standing={"realm": -10})
start("bandit", "Разбойник", "Лес — дом, дорога — добыча. Стража знает ваше лицо.", kind="outlaw", spawn="camp", gold=6, items={"bread": 4, "bow": 1, "arrow": 24, "leather_chestplate": 1}, mods={"stealth_pct": 4, "crit_pct": 2}, flags=["origin:bandit", "outlaw"], standing={"realm": -30}, wanted=True)
start("leader_village", "Староста деревни", "Вы основатель и глава маленькой деревни. Её судьба в ваших руках.", kind="leader", spawn="here", tier="village", panel=True, gold=4, items={"bread": 6}, flags=["origin:leader", "leader"], standing={"realm": 10})
start("leader_town", "Правитель города", "Город растёт под вашей рукой: рынок, стража, налоги.", kind="leader", spawn="here", tier="town", panel=True, gold=10, items={"bread": 6}, attr_bonus=-4, flags=["origin:leader", "leader"], standing={"realm": 10})
start("leader_capital", "Правитель столицы", "Вы у власти в большой столице. Много возможностей и много врагов.", kind="leader", spawn="here", tier="capital", panel=True, gold=30, items={"bread": 8}, attr_bonus=-10, skill_bonus=-1, flags=["origin:leader", "leader", "noble"], standing={"realm": 25})
d["starts"] = S
os.makedirs(os.path.dirname(OUT), exist_ok=True)
json.dump(d, open(OUT, 'w'), ensure_ascii=False, indent=1)
print("ok", len(S), "стартов", len(FEATURES), "особенностей")
