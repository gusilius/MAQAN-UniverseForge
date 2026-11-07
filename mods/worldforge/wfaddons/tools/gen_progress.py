#!/usr/bin/env python3
"""Генерирует базовые данные прокачки (атрибуты, навыки, дерево пассивок) в wf-progress/.../wf_progress/. Правьте JSON руками или меняйте скрипт."""
import json, math, os, random
OUT = os.path.join(os.path.dirname(__file__), '..', 'wf-progress', 'src', 'main', 'resources', 'data', 'wf_progress', 'wf_progress')
os.makedirs(OUT, exist_ok=True)
def dump(name, d): json.dump(d, open(os.path.join(OUT, name + '.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)

stats = [("max_health","Здоровье","int",20),("armor","Броня","int",0),("melee_dmg","Урон в ближнем бою","int",1),("ranged_dmg","Урон дальнего боя","int",0),("crit_pct","Шанс крита, %","pct",5),
 ("move_speed_pct","Скорость движения, %","pct",0),("health_regen","Восстановление здоровья","dec",0),("carry","Грузоподъёмность","int",30),("xp_gain_pct","Прирост опыта, %","pct",0),
 ("luck","Удача","int",0),("resist_pct","Сопротивление эффектам, %","pct",0),("mining_speed_pct","Скорость добычи, %","pct",0),("craft_quality_pct","Качество ремесла, %","pct",0),
 ("levy_pct","Ополчение государства, %","pct",0),("discount_pct","Скидка в торговле, %","pct",0),("tax_pct","Налоговый доход, %","pct",0),("spy_success_pct","Успех интриг, %","pct",0),
 ("opinion","Расположение людей","int",0),("heal_pct","Эффективность лечения, %","pct",0),("stealth_pct","Скрытность, %","pct",0),("dodge_pct","Уклонение, %","pct",0),("thorns","Отражение урона","int",0)]
attrs = [
 ("might","Сила","body","Мощь тела: урон в ближнем бою, грузоподъёмность.",0xD9573B,{"melee_dmg_pct":2,"carry":1.5,"armor":0.15}),
 ("agility","Ловкость","body","Скорость и меткость: движение, критические удары, дальний бой.",0x6CC96C,{"move_speed_pct":0.5,"crit_pct":0.4,"ranged_dmg_pct":2,"dodge_pct":0.3}),
 ("vitality","Выносливость","body","Здоровье и восстановление.",0xE06070,{"max_health":2,"health_regen":0.04}),
 ("intellect","Интеллект","mind","Быстрее учишься и лучше мастеришь.",0x5CA8E0,{"xp_gain_pct":0.8,"craft_quality_pct":1.2}),
 ("spirit","Дух","mind","Стойкость к страху и ядам, удача.",0xB080E0,{"resist_pct":0.8,"luck":0.25}),
 ("diplomacy","Дипломатия","court","Переговоры, дружба, выгодные сделки.",0xF0C85A,{"discount_pct":0.5,"opinion":1}),
 ("martial","Военное дело","court","Командование: ополчение и тактика.",0xC0503A,{"levy_pct":1.0,"melee_dmg_pct":0.4}),
 ("intrigue","Интрига","court","Тайные дела, шпионы, заговоры.",0x8A5CA8,{"spy_success_pct":1.5,"stealth_pct":0.6}),
 ("stewardship","Управление","court","Налоги, казна, хозяйство.",0x5CC0A8,{"tax_pct":1.0,"discount_pct":0.2}),
 ("learning","Знания","court","Книги, лекари, наука.",0x70A0D8,{"xp_gain_pct":0.5,"heal_pct":1.0,"craft_quality_pct":0.4})]
dump("core", {
 "levels": {"max": 60, "xp_base": 120, "xp_growth": 1.15, "attr_points": 2, "skill_points": 1, "passive_points": 1, "per_level": {"max_health": 0.5},
            "milestones": {"10": {"passive": 1, "skill": 1}, "20": {"passive": 2, "skill": 1}, "30": {"passive": 2, "skill": 2}, "40": {"passive": 3, "skill": 2}, "50": {"passive": 3, "skill": 3}, "60": {"passive": 4, "skill": 3}}},
 "stats": [{"id": a, "name": b, "fmt": c, "base": d} for a, b, c, d in stats],
 "attributes": [{"id": a, "name": b, "group": c, "desc": d, "color": e, "base": 5, "per_point": f} for a, b, c, d, e, f in attrs]})

# ---------------- навыки ----------------
S = []
def sk(id, name, group, desc, per, mx=8, req=None, cost=None):
    S.append({"id": id, "name": name, "group": group, "desc": desc, "max": mx, "cost": cost or [1,1,1,2,2,2,3,3,4,4][:mx], "per_rank": per, "requires": req or {}})
sk("swordsmanship","Фехтование","combat","Урон мечом и шанс критического удара.",{"melee_dmg_pct":3,"crit_pct":0.5})
sk("axe_mastery","Топоры","combat","Тяжёлые удары: урон и броня противника хуже защищает.",{"melee_dmg_pct":3.5})
sk("spear_mastery","Копья","combat","Длинные выпады, защита от кавалерии.",{"melee_dmg_pct":2.5,"dodge_pct":0.4})
sk("archery","Стрельба из лука","combat","Урон и дальность дальнего боя.",{"ranged_dmg_pct":4,"crit_pct":0.4})
sk("shield_wall","Щитовой строй","combat","Броня и отражение урона.",{"armor":0.8,"thorns":0.2},req={"attr":{"might":6}})
sk("armor_mastery","Ношение доспехов","combat","Тяжёлая броня не замедляет.",{"armor":0.6,"move_speed_pct":0.3},req={"attr":{"vitality":6}})
sk("tactics","Тактика","combat","Командуйте войском: больше ополчения.",{"levy_pct":2},req={"attr":{"martial":7}})
sk("athletics","Атлетика","survival","Бег и прыжки, носите больше.",{"move_speed_pct":0.8,"carry":2})
sk("hunting","Охота","survival","Добыча и урон по зверям.",{"melee_dmg_pct":1,"luck":0.3})
sk("foraging","Собирательство","survival","Находите больше трав и ягод.",{"luck":0.4,"health_regen":0.02})
sk("first_aid","Первая помощь","survival","Лечение лучше и быстрее.",{"heal_pct":6,"health_regen":0.03})
sk("stealth","Скрытность","survival","Вас сложнее заметить.",{"stealth_pct":4},req={"attr":{"agility":6}})
sk("lockpicking","Взлом","survival","Замки и ловушки.",{"luck":0.3,"stealth_pct":1.5},req={"attr":{"agility":7}})
sk("mining","Горное дело","craft","Быстрее добыча руды.",{"mining_speed_pct":5})
sk("smithing","Кузнечное дело","craft","Лучше оружие и броня, которые вы куёте.",{"craft_quality_pct":4},req={"attr":{"might":5}})
sk("carpentry","Плотницкое дело","craft","Постройки и мебель дешевле и лучше.",{"craft_quality_pct":3})
sk("farming","Земледелие","craft","Урожаи богаче.",{"luck":0.2,"tax_pct":0.6})
sk("alchemy","Алхимия","craft","Зелья крепче.",{"craft_quality_pct":3,"heal_pct":3},req={"attr":{"intellect":7}})
sk("cooking","Кулинария","craft","Еда лечит лучше.",{"health_regen":0.04,"heal_pct":2})
sk("haggling","Торговля","social","Скидки у купцов.",{"discount_pct":2.5},req={"attr":{"diplomacy":5}})
sk("persuasion","Убеждение","social","Люди охотнее соглашаются.",{"opinion":2})
sk("leadership","Лидерство","social","Подчинённые стараются лучше.",{"levy_pct":1.5,"opinion":1},req={"attr":{"martial":6}})
sk("intimidation","Устрашение","social","Бойтесь меня. Расположение падает, уступки растут.",{"opinion":-1,"melee_dmg_pct":1})
sk("etiquette","Этикет","social","При дворе вас любят.",{"opinion":2,"discount_pct":0.5},req={"attr":{"diplomacy":6}})
sk("espionage","Шпионаж","social","Шпионы работают чище.",{"spy_success_pct":4},req={"attr":{"intrigue":6}})
sk("oratory","Красноречие","social","Речь увлекает толпу.",{"opinion":1.5,"xp_gain_pct":0.5})
sk("logistics","Логистика","realm","Армии снабжаются лучше.",{"levy_pct":1.5,"carry":1},req={"attr":{"stewardship":6}})
sk("tax_collection","Сбор налогов","realm","Больше дохода от земель.",{"tax_pct":3},req={"attr":{"stewardship":6}})
sk("fortification","Фортификация","realm","Крепче стены и гарнизоны.",{"armor":0.5,"levy_pct":0.8},req={"attr":{"martial":6}})
sk("siegecraft","Осадное дело","realm","Быстрее берёте замки.",{"melee_dmg_pct":1,"levy_pct":1},req={"attr":{"martial":8},"skill":{"tactics":2}})
sk("statecraft","Государственное дело","realm","Дипломатия государств, меньше мятежей.",{"opinion":1.5,"tax_pct":1},req={"attr":{"diplomacy":8}})
sk("law","Право","realm","Законы работают на вас.",{"tax_pct":1.5,"opinion":1},req={"attr":{"learning":6}})
sk("scholarship","Учёность","realm","Читайте быстрее, учитесь быстрее.",{"xp_gain_pct":1.5},req={"attr":{"learning":5}})
sk("medicine","Медицина","realm","Лечите других.",{"heal_pct":5,"health_regen":0.05},req={"attr":{"learning":7},"skill":{"first_aid":3}})
dump("skills", {"skills": S})

# ---------------- дерево ----------------
rng = random.Random(7)
ARCH = [
 ("warrior","Воин","Ближний бой и сила: бьёт сильно, стоит крепко.",{"melee_dmg_pct":3,"max_health":1.5,"armor":2,"crit_pct":1.5,"levy_pct":1},{"might":3,"vitality":2,"martial":1.5},{"swordsmanship":3,"axe_mastery":3,"armor_mastery":2,"shield_wall":1.5,"tactics":1,"athletics":1}),
 ("ranger","Следопыт","Меткость и скорость: стреляет, бегает, живёт в дороге.",{"ranged_dmg_pct":3,"move_speed_pct":2,"crit_pct":2,"dodge_pct":2,"luck":1.5},{"agility":3,"spirit":1,"vitality":1},{"archery":3,"hunting":2,"athletics":2,"stealth":1.5,"foraging":1,"first_aid":1}),
 ("guardian","Страж","Броня, здоровье и защита других.",{"armor":3,"max_health":2,"health_regen":4,"thorns":2,"resist_pct":1.5},{"vitality":3,"might":2,"spirit":1},{"shield_wall":3,"armor_mastery":3,"spear_mastery":1.5,"fortification":1.5,"first_aid":1}),
 ("scholar","Учёный","Знания, ремесло и лечение.",{"xp_gain_pct":2,"craft_quality_pct":3,"heal_pct":3,"mining_speed_pct":1,"luck":1},{"intellect":3,"learning":2.5,"spirit":1},{"scholarship":3,"alchemy":2.5,"smithing":1.5,"medicine":2,"law":1,"mining":1}),
 ("courtier","Придворный","Дипломатия, торговля, управление землёй.",{"discount_pct":2,"opinion":2,"tax_pct":2,"levy_pct":1,"luck":0.8},{"diplomacy":3,"stewardship":2.5,"learning":1},{"haggling":3,"persuasion":2.5,"etiquette":2,"tax_collection":2,"statecraft":2,"oratory":1.5,"leadership":1}),
 ("rogue","Плут","Тени, интриги, внезапные удары.",{"stealth_pct":3,"spy_success_pct":3,"crit_pct":2.5,"dodge_pct":2,"luck":1.5},{"intrigue":3,"agility":2.5,"diplomacy":0.5},{"stealth":3,"lockpicking":2.5,"espionage":2.5,"swordsmanship":1.5,"intimidation":1}),
]
THEMES = {
 "warrior": [("Крепкий удар","melee_dmg_pct",3),("Закалка","max_health",3),("Грубая сила","armor",1),("Яростный натиск","crit_pct",1.5),("Воинское братство","levy_pct",1.5)],
 "ranger": [("Верный глаз","ranged_dmg_pct",3),("Лёгкая поступь","move_speed_pct",1.5),("Меткость","crit_pct",1.5),("Увёртливость","dodge_pct",1.2),("Чутьё следопыта","luck",1)],
 "guardian": [("Толстая шкура","armor",1.5),("Стойкость","max_health",4),("Живучесть","health_regen",0.08),("Шипы","thorns",1),("Несгибаемость","resist_pct",2)],
 "scholar": [("Пытливый ум","xp_gain_pct",1.5),("Золотые руки","craft_quality_pct",2.5),("Травник","heal_pct",3),("Рудознатец","mining_speed_pct",3),("Счастливый случай","luck",0.8)],
 "courtier": [("Звонкая монета","discount_pct",1.5),("Обаяние","opinion",1.5),("Хозяйская жилка","tax_pct",2),("Знамя дома","levy_pct",1.2),("Связи","luck",0.6)],
 "rogue": [("Тихий шаг","stealth_pct",3),("Тайные дела","spy_success_pct",3),("Удар в спину","crit_pct",2),("Ловкость рук","dodge_pct",1.5),("Воровская удача","luck",1)],
}
NOTABLES = {
 "warrior": [("Берсерк","Ярость битвы","melee_dmg_pct",12,"max_health",6),("Железный строй","Стоять до конца","armor",5,"levy_pct",4),("Мясник","Смерть врагам","crit_pct",6,"melee_dmg_pct",8)],
 "ranger": [("Охотник на крупную дичь","Один выстрел","ranged_dmg_pct",14,"crit_pct",4),("Ветер в спину","Быстрее всех","move_speed_pct",8,"dodge_pct",4),("Глаз орла","Не промахнуться","crit_pct",7,"luck",3)],
 "guardian": [("Бастион","Живая стена","armor",6,"max_health",10),("Рана - не конец","Кровь не течёт","health_regen",0.25,"resist_pct",6),("Ответный удар","Шипы и сталь","thorns",5,"armor",3)],
 "scholar": [("Великий труд","Всё записано","xp_gain_pct",10,"craft_quality_pct",8),("Мастер алхимии","Эликсиры","heal_pct",12,"craft_quality_pct",6),("Рудный чутьё","Жилы под ногами","mining_speed_pct",12,"luck",3)],
 "courtier": [("Казначей","Золото любит счёт","tax_pct",10,"discount_pct",5),("Голос двора","Слушают все","opinion",6,"discount_pct",4),("Знаменосец","Войска идут","levy_pct",8,"opinion",3)],
 "rogue": [("Тень","Невидимка","stealth_pct",12,"dodge_pct",5),("Паутина","Шпионы везде","spy_success_pct",12,"stealth_pct",5),("Клинок в ночи","Первый удар","crit_pct",8,"melee_dmg_pct",6)],
}
KEYSTONES = {
 "warrior": [("Кровавый обет","Бой до последнего","melee_dmg_pct",30,"armor",-6),("Железная воля","Нерушим","max_health",20,"move_speed_pct",-8)],
 "ranger": [("Призрачный стрелок","Бесшумная смерть","ranged_dmg_pct",35,"max_health",-8),("Вечный путник","Не знает усталости","move_speed_pct",18,"armor",-4)],
 "guardian": [("Несокрушимый","Ничто не берёт","armor",14,"melee_dmg_pct",-20),("Жертвенный щит","Боль врагам","thorns",12,"move_speed_pct",-10)],
 "scholar": [("Просвещение","Знание - сила","xp_gain_pct",30,"max_health",-6),("Магнум опус","Шедевр","craft_quality_pct",30,"move_speed_pct",-8)],
 "courtier": [("Золотой голос","Любой скажет да","opinion",12,"melee_dmg_pct",-15),("Властелин казны","Налоги растут","tax_pct",25,"opinion",-6)],
 "rogue": [("Мастер теней","Ни следа","stealth_pct",30,"armor",-5),("Ядовитый шёпот","Интриги везде","spy_success_pct",30,"opinion",-6)],
}
nodes = {}
def add(id, name, kind, x, y, mods, desc="", lv=1):
    nodes[id] = {"id": id, "name": name, "kind": kind, "x": round(x, 1), "y": round(y, 1), "mods": mods, "links": [], "min_level": lv, "desc": desc}
def link(a, b):
    if b not in nodes[a]["links"]: nodes[a]["links"].append(b)
    if a not in nodes[b]["links"]: nodes[b]["links"].append(a)
archs = []
for si, (aid, aname, adesc, w, aw, sw) in enumerate(ARCH):
    th = THEMES[aid]; base = si * math.pi * 2 / 6
    def pos(r, off): return (r * math.cos(base + off), r * math.sin(base + off))
    sx, sy = pos(3, 0)
    start = aid + "_start"
    add(start, aname, "start", sx, sy, {}, adesc)
    archs.append({"id": aid, "name": aname, "desc": adesc, "start": start, "weights": w, "attr_weights": aw, "skill_weights": sw})
    ends = []
    for bi, off in enumerate((-0.38, 0.0, 0.38)):
        prev = start
        for step in range(1, 13):
            r = 3 + step * 1.3
            x, y = pos(r, off * (1 + step * 0.04))
            nid = "%s_%d_%d" % (aid, bi, step)
            if step in (4, 8) and not (bi == 1 and step == 8):
                n = NOTABLES[aid][(bi + step // 4) % 3]
                add(nid, n[0], "notable", x, y, {n[2]: n[3], n[4]: n[5]}, n[1], 6 + step)
            elif bi == 1 and step == 12:
                k = KEYSTONES[aid][si % 2]
                add(nid, k[0], "keystone", x, y, {k[2]: k[3], k[4]: k[5]}, k[1], 30)
            else:
                t = th[(step + bi) % len(th)]
                val = round(t[2] * (1 + (step // 5) * 0.3), 2)
                if rng.random() < 0.35:
                    t2 = th[(step + bi + 2) % len(th)]
                    add(nid, t[0], "small", x, y, {t[1]: val, t2[1]: round(t2[2] * 0.5, 2)}, "", max(1, step - 1))
                else:
                    add(nid, t[0], "small", x, y, {t[1]: val}, "", max(1, step - 1))
            link(prev, nid); prev = nid
        ends.append("%s_%d_12" % (aid, bi))
    # поперечные связи внутри сектора
    for step in (3, 6, 9):
        link("%s_0_%d" % (aid, step), "%s_1_%d" % (aid, step)); link("%s_1_%d" % (aid, step), "%s_2_%d" % (aid, step))
# мосты между соседними секторами на среднем кольце
for si in range(6):
    a = ARCH[si][0]; b = ARCH[(si + 1) % 6][0]
    ax, ay = nodes["%s_2_6" % a]["x"], nodes["%s_2_6" % a]["y"]; bx, by = nodes["%s_0_6" % b]["x"], nodes["%s_0_6" % b]["y"]
    prev = "%s_2_6" % a
    for k in range(1, 4):
        t = k / 4
        nid = "bridge_%s_%s_%d" % (a, b, k)
        ma, mb = (rng.choice(THEMES[a]), rng.choice(THEMES[b]))
        pick = ma if k < 2 else mb
        add(nid, pick[0], "small", ax + (bx - ax) * t, ay + (by - ay) * t, {pick[1]: round(pick[2] * 0.8, 2)}, "Мост между путями", 8 + k)
        link(prev, nid); prev = nid
    link(prev, "%s_0_6" % b)
dump("passives", {"passives": {"archetypes": archs, "nodes": list(nodes.values())}})
print("узлов:", len(nodes), "навыков:", len(S))
