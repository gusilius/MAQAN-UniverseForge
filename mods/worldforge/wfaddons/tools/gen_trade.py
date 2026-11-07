#!/usr/bin/env python3
"""Генерирует data/wf_trade/wf_trade/goods.json. terrain: местность -> множитель выпуска, races: раса -> множитель спроса."""
import json, os
OUT = os.path.join(os.path.dirname(__file__), '..', 'wf-trade', 'src', 'main', 'resources', 'data', 'wf_trade', 'wf_trade', 'goods.json')
FARM = [1.0, 1.2, 1.5, 0.4]
G = []
def g(id, name, cat, base, need, terrain, races=None, item="", season=None, weight=1.0):
    d = {"id": id, "name": name, "category": cat, "base": base, "need": need, "weight": weight, "terrain": terrain}
    if races: d["races"] = races
    if item: d["item"] = item
    if season: d["season"] = season
    G.append(d)
def c(id, name, base, need, terrain, races=None, item="", weight=1.0):
    g(id, name, "contraband", base, need, terrain, races, item, None, weight)

# еда
g("grain", "Зерно", "food", 4, 4.0, {"plains": 1.5, "coast": 0.6, "forest": 0.4, "swamp": 0.3, "mountains": 0.15}, {"halfling": 1.3}, "wheat", FARM, 1)
g("fish", "Рыба", "food", 5, 2.0, {"coast": 2.8, "swamp": 0.9, "tundra": 0.5}, {"triton": 2.0, "northman": 1.4, "orc": 0.6}, "cod", [1, 1, 1, 0.6])
g("meat", "Мясо", "food", 8, 2.0, {"plains": 0.9, "forest": 0.9, "tundra": 0.7, "mountains": 0.4}, {"orc": 1.8, "beastkin": 1.6, "elf": 0.3, "fae": 0.3}, "beef", [0.9, 1, 1.3, 1.0])
g("fruit", "Фрукты", "food", 6, 1.2, {"forest": 1.0, "plains": 0.8, "coast": 0.7, "swamp": 0.3}, {"fae": 1.5, "elf": 1.4}, "apple", [1.0, 1.4, 1.5, 0.1])
g("honey", "Мёд", "food", 12, 0.5, {"forest": 1.6, "plains": 0.8}, {"fae": 1.5, "dwarf": 1.3}, "honey_bottle", [0.8, 1.4, 1.2, 0.3])
g("salt", "Соль", "food", 10, 0.8, {"coast": 2.2, "desert": 1.4, "mountains": 0.6}, None, "")
g("ale", "Эль", "food", 7, 1.5, {"plains": 1.0, "mountains": 0.5, "forest": 0.4}, {"dwarf": 2.4, "northman": 2.0, "orc": 1.6, "elf": 0.3, "halfling": 1.7})
g("wine", "Вино", "luxury", 20, 0.8, {"plains": 0.7, "coast": 0.7, "forest": 0.2}, {"elf": 1.8, "human": 1.0, "dwarf": 0.8, "orc": 0.4, "fae": 1.4})
g("spice", "Пряности", "luxury", 40, 0.4, {"desert": 2.0, "swamp": 1.3, "coast": 0.4}, {"human": 1.2, "halfling": 1.5, "orc": 0.5})
# сырьё
g("timber", "Брёвна", "material", 5, 3.0, {"forest": 2.6, "swamp": 0.7, "plains": 0.4, "tundra": 0.2}, {"dwarf": 0.6, "elf": 0.5}, "oak_log", [1, 1, 1, 0.8], 2)
g("stone", "Камень", "material", 6, 2.5, {"mountains": 2.6, "tundra": 0.7, "desert": 0.6, "plains": 0.15}, {"dwarf": 2.0, "giant": 2.0}, "cobblestone", None, 2)
g("clay", "Глина", "material", 4, 1.2, {"swamp": 1.8, "plains": 0.6, "coast": 0.8}, None, "clay_ball")
g("iron", "Железо", "material", 14, 2.0, {"mountains": 2.6, "tundra": 0.8, "forest": 0.2}, {"dwarf": 2.2, "orc": 1.4, "goblin": 1.3, "elf": 0.5}, "iron_ingot", None, 1.5)
g("coal", "Уголь", "material", 7, 1.5, {"mountains": 1.6, "tundra": 0.9, "forest": 0.2}, {"dwarf": 1.6}, "coal")
g("copper", "Медь", "material", 12, 0.8, {"mountains": 1.8, "desert": 0.8}, None, "copper_ingot")
g("silver", "Серебро", "luxury", 38, 0.3, {"mountains": 1.5, "desert": 0.4}, {"human": 1.1, "elf": 1.5, "dwarf": 1.5})
g("gold", "Золото", "luxury", 70, 0.15, {"mountains": 1.2, "desert": 0.7, "tundra": 0.3}, {"dwarf": 1.8, "goblin": 2.0, "human": 1.2}, "gold_ingot")
g("gems", "Самоцветы", "luxury", 80, 0.1, {"mountains": 1.5, "desert": 0.7, "tundra": 0.3}, {"dwarf": 2.0, "fae": 1.6, "elf": 1.4}, "emerald")
g("glass", "Стекло", "craft", 15, 0.7, {"desert": 1.6, "coast": 0.9}, None, "glass")
g("leather", "Кожа", "material", 9, 1.2, {"plains": 0.8, "forest": 0.8, "tundra": 0.6, "mountains": 0.5}, {"orc": 1.4, "beastkin": 0.5}, "leather")
g("wool", "Шерсть", "material", 6, 1.3, {"plains": 1.1, "mountains": 0.9, "tundra": 0.9}, {"northman": 1.4, "halfling": 1.3}, "white_wool", [1.3, 1, 1, 0.6])
g("furs", "Меха", "luxury", 25, 0.5, {"tundra": 2.8, "forest": 1.0, "mountains": 0.5}, {"northman": 1.8, "human": 1.0, "saurian": 0.1}, "rabbit_hide", [0.8, 0.6, 1.2, 1.6])
g("flax", "Лён", "material", 4, 1.0, {"plains": 1.2, "coast": 0.7, "swamp": 0.5}, None, "string")
# ремесло
g("cloth", "Ткань", "craft", 12, 1.5, {"plains": 0.8, "coast": 0.4, "forest": 0.3}, {"human": 1.2, "saurian": 0.4})
g("silk", "Шёлк", "luxury", 55, 0.2, {"forest": 0.5, "desert": 0.9, "coast": 0.4}, {"elf": 1.8, "fae": 1.7, "human": 1.2, "dwarf": 0.4})
g("pottery", "Керамика", "craft", 8, 0.9, {"swamp": 0.8, "plains": 0.6, "coast": 0.6, "desert": 0.5}, None)
g("tools", "Инструменты", "craft", 18, 1.0, {"mountains": 1.2, "plains": 0.5, "forest": 0.4}, {"dwarf": 1.6, "goblin": 1.4, "giant": 0.3})
g("books", "Книги", "craft", 30, 0.2, {"plains": 0.5, "coast": 0.4}, {"elf": 2.0, "human": 1.0, "orc": 0.2, "goblin": 0.3}, "paper")
g("horses", "Лошади", "military", 60, 0.2, {"plains": 2.0, "tundra": 0.3, "desert": 0.6}, {"human": 1.2, "northman": 1.2, "orc": 1.4, "giant": 0.1}, "", None, 3)
g("weapons", "Оружие", "military", 35, 0.6, {"mountains": 1.5, "plains": 0.4, "forest": 0.2}, {"orc": 2.2, "goblin": 1.8, "dwarf": 1.4, "northman": 1.6, "elf": 0.7, "halfling": 0.3}, "", None, 1.5)
g("armor", "Доспехи", "military", 50, 0.4, {"mountains": 1.4, "plains": 0.3}, {"orc": 1.8, "dwarf": 1.6, "human": 1.2, "northman": 1.4}, "", None, 2)
g("bows", "Луки", "military", 20, 0.5, {"forest": 1.5, "plains": 0.4}, {"elf": 2.0, "goblin": 1.3, "halfling": 1.2})
g("ships", "Корабли", "craft", 220, 0.05, {"coast": 1.6}, {"triton": 0.2, "northman": 2.0, "human": 1.0, "dwarf": 0.2, "orc": 0.3}, "", None, 10)
# магия
g("herbs", "Целебные травы", "magic", 14, 0.7, {"forest": 1.8, "swamp": 1.6, "plains": 0.4}, {"halfling": 1.4, "elf": 1.5, "fae": 1.6, "orc": 0.6})
g("mana_crystal", "Кристаллы маны", "magic", 90, 0.12, {"mountains": 1.0, "tundra": 0.8, "swamp": 0.5, "desert": 0.4}, {"elf": 2.4, "fae": 3.0, "shadekin": 2.5, "dwarf": 0.7, "orc": 0.3}, "amethyst_shard")
g("potions", "Зелья", "magic", 45, 0.2, {"forest": 0.6, "swamp": 0.8}, {"elf": 1.6, "human": 1.0, "orc": 0.5, "halfling": 1.3})
g("dragonbone", "Драконья кость", "magic", 300, 0.02, {"mountains": 0.5, "tundra": 0.4, "desert": 0.3}, {"dwarf": 1.8, "elf": 1.4, "giant": 1.5})
g("relics", "Реликвии", "luxury", 120, 0.05, {"desert": 0.8, "plains": 0.2, "swamp": 0.4}, {"human": 1.3, "elf": 1.5, "shadekin": 2.0})
# контрабанда
c("black_lotus", "Чёрный лотос", 70, 0.1, {"swamp": 1.6, "desert": 0.6}, {"human": 1.0, "goblin": 1.8, "shadekin": 2.2})
c("poison", "Яды", 55, 0.1, {"swamp": 1.2, "forest": 0.4}, {"shadekin": 2.0, "goblin": 1.5, "human": 0.8})
c("forbidden_tomes", "Запретные тома", 150, 0.03, {"plains": 0.2, "desert": 0.3}, {"shadekin": 2.5, "elf": 1.2, "human": 0.8})
json.dump({"goods": G}, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print(len(G))
