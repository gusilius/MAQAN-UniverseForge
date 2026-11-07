#!/usr/bin/env python3
"""Генерирует data/wf_forms/wf_forms/forms.json: вампиризм, оборотничество, драконья кровь, проклятая кровь, зомби, благословение, вендиго, прилив, окаменение, звериная кровь."""
import json, os
OUT = os.path.join(os.path.dirname(__file__), '..', 'wf-forms', 'src', 'main', 'resources', 'data', 'wf_forms', 'wf_forms', 'forms.json')
F = []
def stage(id, name, at, mods, desc=""): return {"id": id, "name": name, "at": at, "mods": mods, "desc": desc}
def form(**kw): F.append(kw)

form(id="vampire", name="Вампиризм", kind="curse", secret=True, opinion=-3, flag_stage=2, rate=1.0, exclusive="undead", immune_races=["construct"],
     desc="Жажда крови, бессмертие и страх перед рассветом.",
     stages=[stage("bitten", "Укушенный", 0, {"max_health": -2}, "Слабость и бледность: что-то не так с кровью."),
             stage("pale", "Бледный", 5, {"stealth_pct": 4, "max_health": 2}, "Солнце режет глаза, ночь зовёт."),
             stage("drinker", "Кровопийца", 20, {"melee_dmg_pct": 6, "crit_pct": 3, "stealth_pct": 8, "health_regen": 0.03, "max_health": 6}, "Вы пьёте кровь и становитесь сильнее."),
             stage("ancient", "Древний", 90, {"melee_dmg_pct": 12, "crit_pct": 6, "stealth_pct": 14, "health_regen": 0.06, "max_health": 14, "heal_pct": 6}, "Бессмертный хищник ночи.")],
     triggers=[{"on": "hunger", "min_stage": 2, "chance": 0.6, "duration": 1}],
     sources=[{"type": "mob", "key": "vampire", "chance": 0.5}, {"type": "event", "key": "vampire_bite", "chance": 1}, {"type": "quest", "key": "blood_pact", "chance": 1}],
     shift={"name": "Облик ночи", "entity": "bat", "mods": {"melee_dmg_pct": 12, "move_speed_pct": 6, "stealth_pct": 10, "armor": -1}},
     weaknesses=[{"when": "sun", "mods": {"max_health": -6, "melee_dmg_pct": -10, "health_regen": -0.05}, "damage": 1}, {"when": "holy", "mods": {"armor": -2, "health_regen": -0.05}, "damage": 1}, {"when": "silver", "mods": {"max_health": -4}, "damage": 2}],
     need={"id": "blood", "name": "Кровь", "max": 100, "drain": 10, "below_pct": 30, "starve": {"melee_dmg_pct": -8, "heal_pct": -10, "opinion": -2}, "feed_gain": 25},
     control={"gain": 2.5, "max": 100, "voluntary_at": 40, "cooldown": 2},
     cures=[{"id": "sacred_rite", "name": "Святой обряд", "item": "golden_apple", "count": 2, "gold": 50, "max_stage": 1}, {"id": "pilgrimage", "name": "Паломничество", "flag": "quest:holy_pilgrimage", "max_stage": 2}], incurable_from=3)
form(id="werewolf", name="Оборотничество", kind="curse", secret=True, opinion=-2, flag_stage=2, rate=0.8, immune_races=["construct"],
     desc="Полнолуние будит зверя.",
     stages=[stage("bitten", "Укушенный", 0, {"heal_pct": -2}, "Рана не заживает как раньше."),
             stage("lunatic", "Лунатик", 6, {"stealth_pct": 3, "move_speed_pct": 1}, "Луна волнует кровь."),
             stage("werewolf", "Оборотень", 25, {"melee_dmg_pct": 5, "move_speed_pct": 3, "max_health": 6, "health_regen": 0.03}, "Зверь внутри уже почти хозяин."),
             stage("alpha", "Альфа", 100, {"melee_dmg_pct": 10, "move_speed_pct": 5, "max_health": 12, "health_regen": 0.05, "levy_pct": 4}, "Вожак стаи.")],
     triggers=[{"on": "full_moon", "min_stage": 2, "chance": 1.0, "duration": 1}, {"on": "night", "min_stage": 3, "chance": 0.35, "duration": 1}, {"on": "rage", "min_stage": 2, "chance": 0.25, "duration": 1}],
     sources=[{"type": "mob", "key": "werewolf", "chance": 0.4}, {"type": "event", "key": "wolf_bite", "chance": 1}],
     shift={"name": "Облик волка", "entity": "wolf", "mods": {"melee_dmg_pct": 14, "move_speed_pct": 8, "dodge_pct": 4, "armor": -1, "stealth_pct": -6}},
     weaknesses=[{"when": "silver", "mods": {"max_health": -8, "health_regen": -0.1}, "damage": 2}],
     control={"gain": 3, "max": 100, "voluntary_at": 55, "cooldown": 3},
     cures=[{"id": "moonflower", "name": "Отвар лунного цвета", "item": "dandelion", "count": 8, "gold": 40, "max_stage": 1}, {"id": "lunar_rite", "name": "Лунный обряд", "flag": "quest:lunar_rite", "max_stage": 2}], incurable_from=3)
form(id="dragon_blood", name="Драконья кровь", kind="bloodline", opinion=2, flag_stage=1, rate=0, level_scale=0.5, only_races=["human", "northman", "orc", "giant", "dwarf"],
     desc="Предок-дракон в родословной; растёт вместе с вами.",
     stages=[stage("awake", "Пробуждение", 0, {"resist_pct": 2}, "Жар в крови по утрам."),
             stage("fire", "Огненная кровь", 8, {"melee_dmg_pct": 4, "resist_pct": 6, "armor": 1}, "Пламя не страшно вам."),
             stage("breath", "Драконье дыхание", 20, {"melee_dmg_pct": 8, "resist_pct": 10, "armor": 3, "max_health": 8}, "Сильный голос и звериное чутьё."),
             stage("drake", "Дракон", 40, {"melee_dmg_pct": 14, "resist_pct": 16, "armor": 5, "max_health": 16, "levy_pct": 6, "opinion": 3}, "Вы - наследие дракона.")],
     triggers=[{"on": "rage", "min_stage": 2, "chance": 0.2, "duration": 1}],
     sources=[{"type": "birth", "key": "dragon_house", "chance": 1}, {"type": "event", "key": "dragon_egg", "chance": 1}, {"type": "quest", "key": "dragon_trial", "chance": 1}],
     shift={"name": "Драконий облик", "entity": "dragon", "mods": {"melee_dmg_pct": 18, "armor": 6, "max_health": 20, "move_speed_pct": -2, "stealth_pct": -20, "resist_pct": 15}},
     control={"gain": 2, "max": 100, "voluntary_at": 50, "cooldown": 5})
form(id="cursed_blood", name="Проклятая кровь", kind="curse", opinion=-1, flag_stage=1, rate=1.0,
     desc="Род обречён превращаться - в кого и когда, решает проклятье.",
     stages=[stage("mark", "Знак", 0, {"luck": -0.5}, "Родимое пятно жжёт."),
             stage("brand", "Метка", 10, {"luck": -1, "resist_pct": 4, "crit_pct": 2}, "Метка проступила."),
             stage("seal", "Печать", 40, {"luck": -1.5, "resist_pct": 8, "crit_pct": 4, "max_health": 6}, "Проклятье в полной силе."),
             stage("doomed", "Обречённый", 120, {"luck": -2, "resist_pct": 12, "crit_pct": 6, "max_health": 10, "xp_gain_pct": 4}, "Проклятье и сила стали неразделимы.")],
     triggers=[{"on": "random", "min_stage": 1, "chance": 0.08, "duration": 1}, {"on": "rage", "min_stage": 2, "chance": 0.3, "duration": 1}],
     sources=[{"type": "quest", "key": "cursed_tomb", "chance": 1}, {"type": "event", "key": "witch_curse", "chance": 1}, {"type": "item", "key": "cursed_idol", "chance": 1}],
     shift={"name": "Облик проклятья", "entity": "", "mods": {"melee_dmg_pct": 10, "crit_pct": 6, "max_health": 6, "opinion": -4, "luck": -2, "heal_pct": -12}},
     control={"gain": 2, "max": 100, "voluntary_at": 70, "cooldown": 3},
     cures=[{"id": "exorcism", "name": "Изгнание", "gold": 120, "max_stage": 1}, {"id": "unbinding", "name": "Снятие печати", "flag": "quest:curse_broken", "max_stage": 2}], incurable_from=3)
form(id="zombie", name="Нежить", kind="curse", opinion=-6, flag_stage=3, rate=1.2, exclusive="undead", immune_races=["construct"],
     desc="Тело гниёт, но не умирает.",
     stages=[stage("infected", "Заражённый", 0, {"max_health": -4, "move_speed_pct": -3}, "Лихорадка и озноб."),
             stage("fever", "Лихорадка", 4, {"max_health": -6, "health_regen": -0.04, "move_speed_pct": -4}, "Кожа сереет."),
             stage("turning", "Обращающийся", 10, {"max_health": -8, "resist_pct": 10, "heal_pct": -12, "opinion": -4}, "Сердце бьётся всё реже."),
             stage("dead", "Мертвец", 18, {"max_health": 20, "armor": 3, "resist_pct": 25, "heal_pct": -20, "move_speed_pct": -4, "opinion": -10}, "Вы больше не живы - и не мертвы.")],
     triggers=[{"on": "hunger", "min_stage": 3, "chance": 0.5, "duration": 1}],
     sources=[{"type": "mob", "key": "zombie", "chance": 0.08}, {"type": "mob", "key": "husk", "chance": 0.1}, {"type": "mob", "key": "drowned", "chance": 0.06}, {"type": "event", "key": "plague", "chance": 1}],
     shift={"name": "Голодное безумие", "entity": "zombie", "mods": {"melee_dmg_pct": 12, "move_speed_pct": 3, "armor": 2, "opinion": -6}},
     weaknesses=[{"when": "holy", "mods": {"armor": -3}, "damage": 2}, {"when": "sun", "mods": {"max_health": -4}, "damage": 1}],
     need={"id": "flesh", "name": "Плоть", "max": 100, "drain": 6, "below_pct": 30, "starve": {"melee_dmg_pct": -10, "max_health": -10}, "feed_gain": 30},
     control={"gain": 1.5, "max": 80, "voluntary_at": 50, "cooldown": 2},
     cures=[{"id": "antidote", "name": "Противоядие", "item": "golden_apple", "count": 1, "gold": 30, "max_stage": 1}, {"id": "holy_rite", "name": "Святой обряд", "flag": "quest:holy_rite", "max_stage": 2}], incurable_from=3)
form(id="blessed", name="Благословение", kind="blessing", opinion=1, flag_stage=1, rate=0.5, exclusive="undead",
     desc="Свет хранит вас.",
     stages=[stage("anointed", "Помазанник", 0, {"heal_pct": 4, "resist_pct": 4, "opinion": 1}), stage("blessed", "Благословлённый", 10, {"heal_pct": 10, "resist_pct": 8, "opinion": 3}), stage("saint", "Святой", 60, {"heal_pct": 18, "resist_pct": 14, "opinion": 5, "health_regen": 0.03})],
     sources=[{"type": "quest", "key": "holy_pilgrimage", "chance": 1}, {"type": "event", "key": "miracle", "chance": 1}], cures=[], incurable_from=99)
form(id="wendigo", name="Вендиго", kind="curse", secret=True, opinion=-4, flag_stage=2, rate=0.7, immune_races=["construct"],
     desc="Ненасытный голод превращает человека в нечто иное.",
     stages=[stage("hungry", "Голодный", 0, {"carry": -2}), stage("gaunt", "Исхудавший", 8, {"move_speed_pct": 2, "stealth_pct": 3}), stage("possessed", "Одержимый", 30, {"melee_dmg_pct": 6, "move_speed_pct": 4, "max_health": 6}), stage("wendigo", "Вендиго", 90, {"melee_dmg_pct": 12, "move_speed_pct": 6, "max_health": 14, "resist_pct": 8})],
     triggers=[{"on": "hunger", "min_stage": 2, "chance": 0.5, "duration": 2}],
     sources=[{"type": "event", "key": "famine_winter", "chance": 1}, {"type": "quest", "key": "cannibal_pact", "chance": 1}, {"type": "item", "key": "black_meat", "chance": 1}],
     shift={"name": "Облик вендиго", "entity": "", "mods": {"melee_dmg_pct": 16, "move_speed_pct": 8, "max_health": 14, "stealth_pct": -8, "opinion": -8}},
     need={"id": "hunger", "name": "Сытость", "max": 100, "drain": 12, "below_pct": 40, "starve": {"melee_dmg_pct": 10, "opinion": -4, "heal_pct": -8}, "feed_gain": 30},
     control={"gain": 2, "max": 100, "voluntary_at": 60, "cooldown": 3},
     cures=[{"id": "feast_rite", "name": "Обряд сытости", "item": "bread", "count": 20, "gold": 60, "max_stage": 1}], incurable_from=3)
form(id="tide_touched", name="Дитя прилива", kind="blessing", opinion=0, flag_stage=1, rate=0.5, only_races=["triton", "human", "northman", "islander"],
     desc="Море зовёт: у воды вы сильнее.",
     stages=[stage("salt", "Солёный", 0, {"fish_pct": 6, "luck": 0.5}), stage("tide", "Дитя прилива", 10, {"fish_pct": 12, "trade_profit_pct": 2, "luck": 1}), stage("lord", "Морской владыка", 50, {"fish_pct": 20, "trade_profit_pct": 4, "luck": 1.5})],
     triggers=[{"on": "water", "min_stage": 1, "chance": 1.0, "duration": 0}],
     sources=[{"type": "event", "key": "sea_blessing", "chance": 1}, {"type": "quest", "key": "sea_pact", "chance": 1}, {"type": "birth", "key": "islander", "chance": 0.1}],
     shift={"name": "Морской облик", "entity": "", "mods": {"fish_pct": 20, "move_speed_pct": 8, "dodge_pct": 6, "resist_pct": 8}}, cures=[])
form(id="gargoyle_curse", name="Каменное проклятье", kind="curse", opinion=-2, flag_stage=2, rate=0.6,
     desc="Днём тело каменеет.",
     stages=[stage("numb", "Онемение", 0, {"move_speed_pct": -2}), stage("stiff", "Окаменение", 12, {"armor": 2, "resist_pct": 3, "move_speed_pct": -2}), stage("stone", "Каменный", 50, {"armor": 5, "resist_pct": 10, "move_speed_pct": -3})],
     triggers=[{"on": "day", "min_stage": 1, "chance": 0.7, "duration": 0}],
     sources=[{"type": "item", "key": "gorgon_eye", "chance": 1}, {"type": "mob", "key": "gargoyle", "chance": 0.15}, {"type": "quest", "key": "stone_idol", "chance": 1}],
     shift={"name": "Каменная статуя", "entity": "", "mods": {"armor": 14, "resist_pct": 20, "move_speed_pct": -30, "melee_dmg_pct": -10, "dodge_pct": -20}},
     control={"gain": 1, "max": 60, "voluntary_at": 40, "cooldown": 1},
     cures=[{"id": "stone_rite", "name": "Обряд размягчения", "gold": 200, "flag": "quest:stone_rite", "max_stage": 1}], incurable_from=2)
form(id="beast_blood", name="Звериная кровь", kind="bloodline", opinion=0, flag_stage=1, rate=0, level_scale=0.4, only_races=["beastkin", "wolfkin", "foxkin", "catkin"],
     desc="Звериный дух в крови: чем сильнее вы, тем ближе он.",
     stages=[stage("call", "Зов", 0, {"stealth_pct": 2}), stage("hunter", "Охотник", 6, {"stealth_pct": 4, "move_speed_pct": 2}), stage("pack", "Стая", 16, {"stealth_pct": 6, "move_speed_pct": 4, "melee_dmg_pct": 4, "dodge_pct": 3})],
     triggers=[{"on": "full_moon", "min_stage": 1, "chance": 0.6, "duration": 1}],
     sources=[{"type": "birth", "key": "beastkin", "chance": 1}],
     shift={"name": "Звериный облик", "entity": "", "mods": {"melee_dmg_pct": 10, "move_speed_pct": 8, "stealth_pct": 4, "dodge_pct": 4}},
     control={"gain": 3, "max": 100, "voluntary_at": 30, "cooldown": 2})
json.dump({"forms": F}, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print(len(F))
