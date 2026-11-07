#!/usr/bin/env python3
"""Генерирует data/wf_quests/wf_quests/{villagers,military,officials,rulers,orgs,board,events}.json. Запускать из wfaddons."""
import json, os
OUT = os.path.join(os.path.dirname(__file__), '..', 'wf-quests', 'src', 'main', 'resources', 'data', 'wf_quests', 'wf_quests')
os.makedirs(OUT, exist_ok=True)

def step(id, type, text, **kw):
    d = {"id": id, "type": type, "text": text}; d.update(kw); return d
def Q(id, name, giver, desc, steps, reward=None, tier=1, **kw):
    d = {"id": id, "name": name, "tier": tier, "giver": giver if isinstance(giver, list) else [giver], "desc": desc, "steps": steps}
    if reward is not None: d["reward"] = reward
    d.update(kw); return d
def save(name, quests=None, events=None):
    d = {}
    if quests: d["quests"] = quests
    if events: d["events"] = events
    json.dump(d, open(os.path.join(OUT, name + ".json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)

# ---------------- простые жители ----------------
save("villagers", [
    Q("wolves", "Волки у околицы", "villager", "Волки таскают овец у околицы. Прогоните {count} зверей - мы не забудем доброты.",
      [step("a", "kill", "Убить волков: {count}", mob="wolf", count="{count}")], {"gold": 4, "xp": 30, "rep": {"{realm}": 1}, "relation": 3},
      params={"count": {"range": [4, 7], "scale": "level"}}, max_level=18, repeat=12),
    Q("wolf_alpha", "Вожак стаи", "villager", "Волки вернулись, и теперь их ведёт крупный вожак. Только вы справитесь.",
      [step("a", "kill", "Убить волков: {count}", mob="wolf", count="{count}"), step("b", "talk", "Сообщить старосте, что стая разбита", reply="Слава богам! Староста велит поставить вам кружку.")],
      {"gold": 9, "xp": 70, "rep": {"{realm}": 2}, "relation": 5, "flags": ["wolf_slayer"]}, tier=2, requires={"quests": ["wolves"]}, params={"count": {"range": [8, 12], "scale": "level"}}, max_level=25),
    Q("firewood", "Дрова на зиму", "villager", "Скоро холода, а дров нет. Принесите {count} брёвен, заплатим серебром.",
      [step("a", "fetch", "Принести {item}: {count}", item="{item}", count="{count}", reply="Тепла хватит на всю зиму.")], {"gold": 5, "xp": 20, "relation": 2},
      params={"count": {"range": [16, 32]}, "item": ["oak_log", "spruce_log", "birch_log"]}, repeat=8),
    Q("harvest_help", "Помощь с урожаем", "villager", "Руки не справляются с жатвой. Принесите {count} штук ({item}), я расплачусь.",
      [step("a", "fetch", "Принести {item}: {count}", item="{item}", count="{count}", reply="Вот спасибо, выручили.")], {"gold": 6, "xp": 25, "relation": 3, "rep": {"{realm}": 1}},
      params={"count": {"range": [14, 28]}, "item": ["wheat", "potato", "carrot", "beetroot"]}, repeat=6),
    Q("night_guests", "Ночные гости", "villager", "По ночам у дома бродят твари. Расправьтесь с {count} из них.",
      [step("a", "kill", "Убить врагов: {count}", mob="hostile", count="{count}")], {"gold": 6, "xp": 40, "relation": 3},
      params={"count": {"range": [6, 12], "scale": "level"}}, repeat=5),
    Q("letter_to_steward", "Письмо управляющему", "villager", "Моё прошение о земле застряло. Передайте весточку управляющему, пока вам по пути.",
      [step("a", "talk", "Передать прошение управляющему", role="steward", reply="Прошение принято, передайте, что рассмотрим.")], {"gold": 3, "xp": 15, "relation": 2},
      tier=1, repeat=15),
    Q("widow_debt", "Долг вдовы", "villager", "Вдова задолжала торговцу, и тот грозит отобрать дом. Что вы сделаете?",
      [step("a", "choice", "Как поступить с долгом вдовы?", options=[
          {"id": "pay", "text": "Заплатить долг (20 золота)", "requires": {"gold": 20}, "effects": {"rep": {"{realm}": 2}, "relation": 12, "xp": 40, "flags": ["kind_heart"]}},
          {"id": "talk", "text": "Поговорить с торговцем (нужен 5-й уровень)", "requires": {"level_min": 5}, "effects": {"rep": {"{realm}": 1}, "relation": 6, "xp": 80}},
          {"id": "leave", "text": "Не вмешиваться", "effects": {"rep": {"{realm}": -1}, "relation": -4}}])],
      {"xp": 10}, tier=2, repeat=30, min_level=2),
    Q("lost_heirloom", "Семейная реликвия", "villager", "Дедушкин оберег потерялся где-то на дороге. Принесите {count} самородков золота - так я смогу выкупить его у бродяг.",
      [step("a", "fetch", "Принести золото: {count}", item="gold", count="{count}", reply="Он снова с нами. Благодарю от всей семьи.")],
      {"gold": 14, "xp": 45, "relation": 6}, tier=2, params={"count": {"range": [6, 10]}}, repeat=20, min_level=3),
])

# ---------------- военные ----------------
save("military", [
    Q("bandit_hunt", "Охота на разбойников", "military", "Разбойники режут торговцев на дорогах. Нужно {count} голов - за каждую плата.",
      [step("a", "kill", "Истребить врагов: {count}", mob="hostile", count="{count}", reply="Дороги станут безопаснее."), step("b", "talk", "Доложить {giver}", reply="Хорошая работа. Дороги чище.")],
      {"gold": 14, "xp": 90, "rep": {"{realm}": 2}, "relation": 4}, tier=2, params={"count": {"range": [10, 18], "scale": "level"}}, repeat=7, min_level=3),
    Q("patrol_border", "Патруль границы", "military", "Проедьтесь до границы с {neighbor} и вернитесь с донесением: что там происходит?",
      [step("a", "reach", "Добраться до земель {neighbor}", realm="{neighbor}"), step("b", "talk", "Доложить {giver}", reply="Понял. Наблюдатели так и говорили.")],
      {"gold": 12, "xp": 70, "rep": {"{realm}": 2, "{neighbor}": 0}}, tier=2, requires={"war": False}, params={"neighbor": "$neighbor"}, repeat=10, min_level=4),
    Q("war_scout", "Разведка у врага", "military", "Идёт война. Нужны глаза в земле {enemy}: пройдите вглубь и вернитесь живым.",
      [step("a", "reach", "Проникнуть в земли {enemy}", realm="{enemy}"), step("b", "talk", "Вернуться к {giver}", reply="Сведения бесценны. Король узнает о вашей службе.")],
      {"gold": 26, "xp": 160, "rep": {"{realm}": 4}, "relation": 6, "news": "Разведчик принёс вести из земель {enemy}."}, tier=3, requires={"war": True}, params={"enemy": "$enemy"}, repeat=10, min_level=6),
    Q("war_supplies", "Снабжение войск", "military", "Армия голодна. Принесите {count} хлеба - солдатам нужно есть.",
      [step("a", "fetch", "Принести хлеб: {count}", item="bread", count="{count}", reply="Ложки застучат. Спасибо.")], {"gold": 15, "xp": 80, "rep": {"{realm}": 2}, "relation": 4},
      tier=2, requires={"war": True}, params={"count": {"range": [24, 48]}}, repeat=7),
    Q("arrows_for_garrison", "Стрелы для гарнизона", "military", "Лучникам нужны стрелы: принесите {count}.",
      [step("a", "fetch", "Принести стрелы: {count}", item="arrow", count="{count}", reply="Хватит на хорошую осаду.")], {"gold": 10, "xp": 50, "relation": 3}, tier=2,
      params={"count": {"range": [32, 64]}}, repeat=9, min_level=3),
    Q("armor_order", "Железо для брони", "military", "Кузнецам нужно железо. Принесите {count} слитков - гарнизон получит новые доспехи.",
      [step("a", "fetch", "Принести железо: {count}", item="iron_ingot", count="{count}", reply="Хорошее железо.")], {"gold": 18, "xp": 75, "relation": 4}, tier=2,
      params={"count": {"range": [12, 24]}}, repeat=10, min_level=4),
    Q("trial_arms_1", "Испытание оружием: путь", "military", "Хотите стать чемпионом? Начните с простого: докажите силу в бою, истребите {count} врагов.",
      [step("a", "kill", "Победить врагов: {count}", mob="hostile", count="{count}"), step("b", "talk", "Вернуться к {giver}", reply="Неплохо. Но настоящее испытание впереди.")],
      {"gold": 30, "xp": 220}, tier=3, params={"count": {"range": [25, 35]}}, min_level=15, requires={"quests": ["bandit_hunt"]}),
    Q("trial_arms_2", "Испытание оружием: поединок", "military", "Последнее испытание: поединок чести. Как вы будете сражаться?",
      [step("a", "choice", "Как провести поединок?", options=[
          {"id": "honor", "text": "Сражаться честно", "effects": {"xp": 400, "gold": 50, "rep": {"{realm}": 3}, "flags": ["trial_of_arms", "honorable"], "news": "Поединок чести выигран."}},
          {"id": "cheat", "text": "Схитрить - и победить любой ценой", "effects": {"xp": 300, "gold": 30, "rep": {"{realm}": -2}, "flags": ["trial_of_arms", "dishonorable"]}}])],
      {"xp": 10}, tier=4, requires={"quests": ["trial_arms_1"]}, min_level=15),
    Q("raise_levy", "Сбор ополчения", "military", "Мне нужны надёжные люди: найдите в округе {count} крепких молодых и приведите. Хватит и вашего слова за них, плату получите сразу.",
      [step("a", "talk", "Дать слово за людей перед {giver}", reply="Беру на вашу ответственность.")], {"gold": 8, "xp": 40, "relation": 4, "action": {"id": "army_raise", "args": ["levy", "20"]}},
      tier=3, requires={"ruler": True}, params={"count": [10, 20]}, repeat=15, min_level=8),
])

# ---------------- чиновники ----------------
save("officials", [
    Q("grain_reserve", "Запас зерна", "official:steward", "Казна просит зерно в резерв: принесите {count} пшеницы. Заплатим из бюджета.",
      [step("a", "fetch", "Принести пшеницу: {count}", item="wheat", count="{count}", reply="Запасы в порядке. Казначей запишет на ваш счёт.")], {"gold": 16, "xp": 70, "rep": {"{realm}": 2}},
      tier=2, params={"count": {"range": [32, 64]}}, repeat=8, min_level=3),
    Q("audit", "Проверка отчётов", "official:steward", "В отчётах сборщиков налогов - странности. Что делать, если вы найдёте растрату?",
      [step("a", "kill", "Выследить сборщиков-воров (разбойников на дорогах): {count}", mob="hostile", count="{count}"),
       step("b", "choice", "Вы нашли доказательства растраты. Как поступить?", options=[
           {"id": "report", "text": "Доложить управляющему", "effects": {"rep": {"{realm}": 3}, "xp": 150, "gold": 20}},
           {"id": "bribe", "text": "Взять долю за молчание", "effects": {"gold": 60, "rep": {"{realm}": -3}, "flags": ["corrupt"]}}])],
      {"xp": 10}, tier=3, params={"count": {"range": [8, 12]}}, repeat=20, min_level=6),
    Q("tax_dispute", "Налоговый спор", "official:steward", "В деревне отказываются платить налог. Узнайте, что они говорят (поговорите с любым старостой) и вернитесь.",
      [step("a", "talk", "Поговорить с жителем деревни", role="", org="", npc="", reply="Люди ропщут на новый налог."), step("b", "talk", "Вернуться к {giver}", reply="Понятно. Налог пересмотрим.")],
      {"gold": 12, "xp": 60, "rep": {"{realm}": 1}}, tier=2, repeat=12, min_level=3, requires={"not_flags": ["corrupt"]}),
    Q("envoy_mission", "Посольство", "official:chancellor", "Нужно доставить письмо правителю {neighbor}. Отправляйтесь в их земли и вернитесь с ответом.",
      [step("a", "reach", "Добраться до земель {neighbor}", realm="{neighbor}"), step("b", "talk", "Вернуться к {giver} с ответом", reply="Хорошие вести. Переговоры продвигаются.")],
      {"gold": 22, "xp": 120, "rep": {"{realm}": 2, "{neighbor}": 3}, "relation": 5}, tier=3, requires={"war": False}, params={"neighbor": "$neighbor"}, repeat=12, min_level=5),
    Q("gift_for_neighbor", "Дар соседу", "official:chancellor", "Чтобы укрепить дружбу с {neighbor}, нужен достойный дар: {count} изумрудов. Получите награду и действие: торговый договор.",
      [step("a", "fetch", "Принести изумруды: {count}", item="emerald", count="{count}", reply="Дар отправлен гонцом.")],
      {"gold": 20, "xp": 110, "rep": {"{neighbor}": 4}, "action": {"id": "trade_propose", "args": ["{neighbor}"]}}, tier=3, requires={"war": False, "ruler": True}, params={"neighbor": "$neighbor", "count": {"range": [4, 8]}}, repeat=15, min_level=7),
    Q("whispers", "Шёпот в тавернах", "official:spymaster", "Мои люди слышат недобрые разговоры. Побеседуйте с жителями и вернитесь с именами.",
      [step("a", "talk", "Выслушать жителей", reply="Люди говорят шёпотом."), step("b", "talk", "Передать сведения {giver}", reply="Благодарю. Теперь мы знаем, с кого начать.")],
      {"gold": 14, "xp": 80, "relation": 4}, tier=2, repeat=10, min_level=4),
    Q("intercept_courier", "Перехват гонца", "official:spymaster", "Вражеский гонец везёт письма. Перехватите его: истребите {count} разбойников, что сопровождают.",
      [step("a", "kill", "Победить охрану: {count}", mob="hostile", count="{count}"), step("b", "fetch", "Принести бумагу: 1", item="paper", count=1, reply="Письма у нас. Хорошо.")],
      {"gold": 28, "xp": 150, "rep": {"{realm}": 3}, "flags": ["spy_trusted"]}, tier=3, params={"count": {"range": [6, 10], "scale": "level"}}, repeat=14, min_level=6),
    Q("double_agent", "Двойной агент", "official:spymaster", "Один из придворных подозревается в измене. Мне нужно ваше решение, когда доказательства у вас на руках.",
      [step("a", "choice", "Что делать с изменником?", options=[
          {"id": "expose", "text": "Разоблачить публично", "effects": {"rep": {"{realm}": 3}, "xp": 200, "gold": 30, "news": "Раскрыт заговор при дворе."}},
          {"id": "turn", "text": "Перевербовать (нужен 10-й уровень)", "requires": {"level_min": 10}, "effects": {"xp": 350, "gold": 80, "flags": ["double_agent"]}},
          {"id": "warn", "text": "Тайно предупредить его", "effects": {"rep": {"{realm}": -3}, "xp": 60, "flags": ["traitor_ally"]}}])],
      {"xp": 10}, tier=4, requires={"quests": ["intercept_courier"]}, min_level=8),
    Q("wall_repair", "Починка стен", "official:castellan", "Стены замка осыпаются. Принесите {count} булыжника - каменщики справятся.",
      [step("a", "fetch", "Принести булыжник: {count}", item="cobblestone", count="{count}", reply="Стены выстоят ещё одну зиму.")], {"gold": 10, "xp": 45, "relation": 3},
      tier=2, params={"count": {"range": [64, 128]}}, repeat=8),
    Q("garrison_ale", "Эль для гарнизона", "official:castellan", "Гарнизон унылый - нужен эль, а ещё хлеб. Принесите {count} хлеба.",
      [step("a", "fetch", "Принести хлеб: {count}", item="bread", count="{count}", reply="Настроение гарнизона заметно улучшилось.")], {"gold": 9, "xp": 40, "relation": 3},
      tier=2, params={"count": {"range": [20, 40]}}, repeat=8),
])

# ---------------- правители ----------------
save("rulers", [
    Q("charter_quest", "Грамота: право давать поручения", "ruler", "Хотите сами раздавать поручения в моих землях? Внесите {count} золота в казну - и будет вам грамота.",
      [step("a", "fetch", "Внести в казну золото: {count}", item="gold", count="{count}", reply="Грамота будет оформлена.")],
      {"rights": ["create_quest"], "xp": 120, "rep": {"{realm}": 3}, "news": "Выдана грамота на создание поручений."}, tier=3, min_level=8, requires={"quests": ["bandit_hunt"]},
      params={"count": 50}),
    Q("charter_org", "Грамота: право основать орден", "ruler", "Для ордена нужны имя, ряды и доверие. Докажите, что государство может вам доверять, и внесите {count} золота.",
      [step("a", "fetch", "Внести в казну золото: {count}", item="gold", count="{count}", reply="Орден будет признан законом.")],
      {"rights": ["create_org"], "xp": 250, "rep": {"{realm}": 5}}, tier=4, min_level=15, requires={"rights": ["create_quest"], "quests": ["envoy_mission"]}, params={"count": 200}),
    Q("royal_embassy", "Высочайшее поручение", "ruler", "Я хочу, чтобы вы лично представили нас при дворе {neighbor}. Это дело чести.",
      [step("a", "reach", "Явиться ко двору {neighbor}", realm="{neighbor}"), step("b", "talk", "Доложить правителю", reply="Вы достойно нас представили.")],
      {"gold": 60, "xp": 300, "rep": {"{realm}": 5, "{neighbor}": 4}, "relation": 8, "news": "Посланник вернулся с дарами от {neighbor}."}, tier=4, requires={"war": False},
      params={"neighbor": "$neighbor"}, repeat=25, min_level=10),
    Q("crown_loan", "Заём для короны", "ruler", "Казна пуста, а война требует средств. Дадите взаймы {count} золота?",
      [step("a", "choice", "Дать корону взаймы?", options=[
          {"id": "lend", "text": "Одолжить сто золота", "requires": {"gold": 100}, "effects": {"rep": {"{realm}": 5}, "relation": 10, "xp": 300, "flags": ["crown_creditor"], "treasury": 100}},
          {"id": "refuse", "text": "Отказать", "effects": {"rep": {"{realm}": -2}, "relation": -5}}])],
      {"xp": 10}, tier=4, requires={"war": True}, params={"count": 100}, repeat=30, min_level=10),
    Q("hunt_for_the_crown", "Охота за головой", "ruler", "Врага государства нужно найти и обезвредить. Уничтожьте {count} его приспешников.",
      [step("a", "kill", "Уничтожить приспешников: {count}", mob="hostile", count="{count}"), step("b", "talk", "Доложить правителю", reply="Корона вас не забудет.")],
      {"gold": 70, "xp": 350, "rep": {"{realm}": 4}, "relation": 6}, tier=4, params={"count": {"range": [25, 40], "scale": "level"}}, repeat=18, min_level=10),
    Q("grand_feast", "Великий пир", "ruler", "Скоро пир, и столы пусты. Принесите {count} хлеба и мяса - стол будет богат.",
      [step("a", "fetch", "Принести хлеб: {count}", item="bread", count="{count}", reply="Пир удастся на славу."), step("b", "fetch", "Принести говядину: 16", item="cooked_beef", count=16, reply="Гости будут довольны.")],
      {"gold": 40, "xp": 180, "rep": {"{realm}": 3}, "relation": 5}, tier=3, params={"count": {"range": [32, 64]}}, repeat=20, min_level=6),
])

# ---------------- ордены ----------------
save("orgs", [
    Q("caravan_escort", "Охрана каравана", "org:merchants", "Караван идёт в {neighbor}. Проводите его и вернитесь с распиской.",
      [step("a", "reach", "Довести караван до земель {neighbor}", realm="{neighbor}"), step("b", "talk", "Вернуться к {giver}", reply="Расписка принята. Прибыль есть.")],
      {"gold": 22, "xp": 100, "org_influence": {"merchants": 6}}, tier=2, params={"neighbor": "$neighbor"}, requires={"war": False}, repeat=8, min_level=3),
    Q("rare_goods", "Редкий товар", "org:merchants", "Гильдии нужны {count} изумрудов для особой сделки.",
      [step("a", "fetch", "Принести изумруды: {count}", item="emerald", count="{count}", reply="Сделка состоится.")], {"gold": 30, "xp": 110, "org_influence": {"merchants": 8}}, tier=3,
      params={"count": {"range": [5, 9]}}, repeat=12, min_level=5),
    Q("tourney_challenge", "Турнирный вызов", "org:knights", "На турнир приходят сильнейшие. Покажите, на что вы способны: победите {count} врагов.",
      [step("a", "kill", "Победить врагов: {count}", mob="hostile", count="{count}"), step("b", "talk", "Доложить в орден", reply="Орден гордится вами.")],
      {"gold": 20, "xp": 130, "org_influence": {"knights": 7}}, tier=3, params={"count": {"range": [18, 28], "scale": "level"}}, repeat=9, min_level=5),
    Q("oath_vigil", "Бдение клятвы", "org:knights", "Каждый рыцарь проходит ночное бдение. Пройдите его и вернитесь.",
      [step("a", "wait", "Провести ночь в раздумьях (1 день)", days=1), step("b", "talk", "Вернуться к магистру", reply="Клятва принята.")],
      {"xp": 100, "org_influence": {"knights": 10}, "flags": ["knights_vigil"]}, tier=2, min_level=3, repeat=60),
    Q("ore_order", "Заказ на руду", "org:smiths", "Кузнецам нужна руда: принесите {count} слитков железа.",
      [step("a", "fetch", "Принести железо: {count}", item="iron_ingot", count="{count}", reply="Горн заработает на полную.")], {"gold": 18, "xp": 80, "org_influence": {"smiths": 6}}, tier=2,
      params={"count": {"range": [16, 32]}}, repeat=7),
    Q("lost_tome", "Утраченный том", "org:scholars", "В далёких землях {neighbor} хранится копия нашей рукописи. Побывайте там и разузнайте.",
      [step("a", "reach", "Добраться до земель {neighbor}", realm="{neighbor}"), step("b", "talk", "Вернуться к библиотекарю", reply="Великая находка для науки.")],
      {"gold": 20, "xp": 140, "org_influence": {"scholars": 8}}, tier=3, params={"neighbor": "$neighbor"}, requires={"war": False}, repeat=14, min_level=4),
    Q("heist", "Дело на ночь", "org:thieves", "Есть работёнка в богатом доме. Дело тонкое: возьмёте - заплатим, откажетесь - никто не обидится.",
      [step("a", "choice", "Взяться за дело?", options=[
          {"id": "take", "text": "Взяться", "effects": {"gold": 60, "org_influence": {"thieves": 10}, "rep": {"{realm}": -3}, "xp": 150}},
          {"id": "refuse", "text": "Отказаться", "effects": {"org_influence": {"thieves": -1}}}])],
      {"xp": 5}, tier=3, repeat=14, min_level=5),
    Q("watch_arrest", "Задержание", "org:watch", "В округе орудует шайка. Задержите {count} её членов (достаточно убить сопротивляющихся).",
      [step("a", "kill", "Задержать преступников: {count}", mob="hostile", count="{count}"), step("b", "talk", "Передать дежурному", reply="Дело закрыто, спасибо.")],
      {"gold": 16, "xp": 90, "org_influence": {"watch": 6}, "rep": {"{realm}": 2}}, tier=2, params={"count": {"range": [8, 14], "scale": "level"}}, repeat=7),
])

# ---------------- доска объявлений ----------------
save("board", [
    Q("board_monsters", "Награда за чудовищ", "board", "За каждого убитого врага платит казна: {count} голов.",
      [step("a", "kill", "Убить врагов: {count}", mob="hostile", count="{count}")], {"gold": 8, "xp": 50}, tier=1, params={"count": {"range": [8, 14], "scale": "level"}}, repeat=3),
    Q("board_lumber", "Заказ на лес", "board", "Строителям нужно дерево: {count} брёвен.",
      [step("a", "fetch", "Сдать {item}: {count}", item="{item}", count="{count}", reply="Принято в казну.")], {"gold": 6, "xp": 25}, tier=1,
      params={"count": {"range": [24, 48]}, "item": ["oak_log", "spruce_log", "birch_log"]}, repeat=5),
    Q("board_ore", "Закупка руды", "board", "Купцы закупают руду: {count} слитков железа.",
      [step("a", "fetch", "Сдать железо: {count}", item="iron_ingot", count="{count}", reply="Деньги выплачены.")], {"gold": 15, "xp": 55}, tier=2,
      params={"count": {"range": [12, 20]}}, repeat=5, min_level=3),
    Q("board_bandit_bounty", "Награда за налётчиков", "board", "После налёта казна назначила награду: {count} разбойников.",
      [step("a", "kill", "Убить налётчиков: {count}", mob="hostile", count="{count}")], {"gold": 28, "xp": 120, "rep": {"{realm}": 3}}, tier=3, params={"count": {"range": [12, 20], "scale": "level"}}, repeat=999),
    Q("board_plague_herbs", "Лекарства от мора", "board", "В землях мор. Лекарям нужны травы: {count} хлеба для больных.",
      [step("a", "fetch", "Принести хлеб: {count}", item="bread", count="{count}", reply="Больным станет легче.")], {"gold": 20, "xp": 90, "rep": {"{realm}": 3}, "relation": 2}, tier=2, params={"count": {"range": [20, 40]}}, repeat=999),
    Q("board_famine_grain", "Хлеб для голодающих", "board", "В землях голод: {count} зерна поможет людям пережить зиму.",
      [step("a", "fetch", "Принести пшеницу: {count}", item="wheat", count="{count}", reply="Спасибо. Много жизней спасено.")], {"gold": 18, "xp": 80, "rep": {"{realm}": 4}}, tier=2, params={"count": {"range": [40, 80]}}, repeat=999),
    Q("board_refugee_help", "Помощь беженцам", "board", "К нам пришли беженцы. Нужны еда и приют: {count} хлеба.",
      [step("a", "fetch", "Принести хлеб: {count}", item="bread", count="{count}", reply="Беженцы получат еду.")], {"gold": 10, "xp": 60, "rep": {"{realm}": 3}, "flags": ["refugee_helper"]}, tier=2, params={"count": {"range": [18, 32]}}, repeat=999),
])

# ---------------- события мира ----------------
def ev(id, name, text, choices, weight=3, cooldown=25, expire=6, default=None, board="", requires=None):
    d = {"id": id, "name": name, "scope": "realm", "text": text, "weight": weight, "cooldown": cooldown, "expire": expire, "choices": choices}
    d["default"] = default or choices[0]["id"]
    if board: d["board"] = board
    if requires: d["requires"] = requires
    return d
def C(id, text, effects, ai=1, requires=None):
    d = {"id": id, "text": text, "effects": effects, "ai": ai}
    if requires: d["requires"] = requires
    return d
save("events", None, [
    ev("bandit_raid", "Налёт разбойников", "Шайка разбойников грабит караваны в земле {realm}.", [
        C("guard", "Усилить охрану дорог", {"treasury": -30, "prop": {"stability": 1}, "news": "В {realm} усилили охрану дорог."}, 2),
        C("bounty", "Назначить награду за головы", {"treasury": -15, "quest": "board_bandit_bounty", "news": "В {realm} назначена награда за налётчиков."}, 3),
        C("ignore", "Не обращать внимания", {"prop": {"stability": -1, "unrest": 1}, "news": "Разбойники в {realm} чувствуют себя вольготно."}, 1)], 5, 20, 5, "bounty", board="board_bandit_bounty"),
    ev("plague", "Мор", "В {realm} вспыхнул мор: больные просят помощи.", [
        C("quarantine", "Закрыть пострадавшие селения", {"treasury": -40, "prop": {"stability": 0, "unrest": 1}, "news": "В {realm} введён карантин."}, 2),
        C("herbs", "Закупить лекарства", {"treasury": -25, "quest": "board_plague_herbs", "news": "В {realm} ищут лекарства для больных."}, 3),
        C("pray", "Молиться", {"prop": {"unrest": 2}, "news": "В {realm} молятся о спасении."}, 1)], 3, 40, 6, "herbs", board="board_plague_herbs"),
    ev("famine", "Неурожай", "Засуха погубила посевы в {realm}. Зерна не хватит до весны.", [
        C("import", "Закупить зерно у соседей", {"treasury": -50, "prop": {"unrest": 0}, "news": "В {realm} закупают зерно."}, 2),
        C("appeal", "Объявить сбор зерна", {"quest": "board_famine_grain", "news": "В {realm} объявлен сбор зерна."}, 3),
        C("ignore", "Пусть справляются сами", {"prop": {"unrest": 2, "stability": -1}, "news": "В {realm} голодают."}, 1)], 3, 40, 6, "appeal", board="board_famine_grain"),
    ev("good_harvest", "Богатый урожай", "Урожай в {realm} выдался на славу.", [
        C("store", "Заполнить амбары", {"prop": {"stability": 1}, "news": "Амбары {realm} полны."}, 3),
        C("sell", "Продать излишки", {"treasury": 40, "news": "{realm} выгодно продаёт зерно."}, 2),
        C("feast", "Устроить народное гулянье", {"treasury": -20, "prop": {"stability": 2, "unrest": -1}, "news": "В {realm} гуляют всем миром."}, 1)], 3, 30, 5, "store"),
    ev("refugees", "Беженцы", "Беженцы с войны просятся в {realm}.", [
        C("accept", "Принять и расселить", {"treasury": -20, "quest": "board_refugee_help", "prop": {"stability": 0}, "news": "{realm} принимает беженцев."}, 3),
        C("turn", "Закрыть границу", {"prop": {"unrest": 1}, "news": "{realm} закрывает границу перед беженцами."}, 1)], 2, 30, 6, "accept", board="board_refugee_help"),
    ev("noble_dispute", "Тяжба знати", "Два знатных рода {realm} не поделили землю и грозят друг другу мечами.", [
        C("judge", "Рассудить по закону", {"prop": {"stability": 1}, "news": "Спор знати в {realm} разрешён судом."}, 2),
        C("favor", "Встать на сторону сильнейшего", {"treasury": 20, "prop": {"stability": -1}, "news": "Правитель {realm} принял сторону одного из родов."}, 2),
        C("ignore", "Не вмешиваться", {"prop": {"unrest": 1}, "news": "Знать {realm} в ссоре."}, 1)], 3, 30, 6, "judge"),
    ev("merchant_caravan", "Купеческий караван", "Богатый караван остановился у стен. Купцы просят защиты и права торговли.", [
        C("welcome", "Радушно принять", {"treasury": 30, "news": "В {realm} шумит торг."}, 3),
        C("tax", "Обложить пошлиной", {"treasury": 60, "prop": {"stability": 0}, "news": "{realm} собрала пошлину с каравана."}, 2),
        C("refuse", "Отказать", {"news": "Караван ушёл мимо {realm}."}, 1)], 4, 20, 5, "welcome"),
    ev("foreign_envoy", "Иноземное посольство", "К двору {realm} прибыли послы с дарами.", [
        C("receive", "Принять с почестями", {"treasury": -15, "prop": {"stability": 1}, "news": "Двор {realm} принял послов."}, 3),
        C("snub", "Принять холодно", {"news": "Послы покинули {realm} разочарованными."}, 1)], 3, 25, 5, "receive"),
    ev("wolf_pack", "Волчья стая", "Огромная стая волков бродит у границ {realm}.", [
        C("hunt", "Объявить охоту", {"treasury": -10, "quest": "board_monsters", "news": "В {realm} объявлена охота на волков."}, 3),
        C("wait", "Переждать", {"prop": {"unrest": 1}, "news": "Волки пугают жителей {realm}."}, 1)], 4, 20, 5, "hunt", board="board_monsters"),
    ev("mine_collapse", "Обвал в шахте", "В шахте {realm} произошёл обвал, рабочие под завалом.", [
        C("rescue", "Отправить спасателей", {"treasury": -30, "prop": {"stability": 1}, "news": "Спасатели {realm} вытащили рабочих."}, 3),
        C("abandon", "Закрыть шахту", {"treasury": 0, "prop": {"unrest": 2}, "news": "Шахта {realm} закрыта."}, 1)], 2, 40, 5, "rescue"),
    ev("tournament", "Турнир", "Знать {realm} просит устроить рыцарский турнир.", [
        C("host", "Устроить турнир", {"treasury": -40, "prop": {"stability": 2}, "news": "В {realm} прошёл великий турнир."}, 2),
        C("modest", "Скромное состязание", {"treasury": -10, "prop": {"stability": 1}, "news": "В {realm} прошло скромное состязание."}, 3),
        C("decline", "Отказать", {"prop": {"unrest": 1}, "news": "Турнира в {realm} не будет."}, 1)], 3, 40, 6, "modest"),
    ev("rebellion_rumor", "Слухи о мятеже", "Говорят, в {realm} зреет заговор.", [
        C("crush", "Арестовать зачинщиков", {"prop": {"stability": 1, "unrest": 0}, "news": "В {realm} раскрыт заговор."}, 2),
        C("amnesty", "Объявить амнистию", {"treasury": -20, "prop": {"unrest": -1}, "news": "В {realm} объявлена амнистия."}, 2),
        C("ignore", "Не верить слухам", {"prop": {"unrest": 2}, "news": "Заговор в {realm} набирает силу."}, 1)], 3, 40, 6, "crush"),
    ev("comet", "Знамение", "На небе над {realm} появилась комета. Жрецы толкуют это по-разному.", [
        C("ritual", "Устроить молебен", {"treasury": -10, "prop": {"stability": 1}, "news": "В {realm} прошёл молебен."}, 3),
        C("ignore", "Не обращать внимания", {"news": "Комета над {realm} исчезла."}, 2)], 2, 60, 5, "ritual"),
    ev("royal_wedding", "Свадьба при дворе", "При дворе {realm} готовится пышная свадьба.", [
        C("grand", "Устроить празднество", {"treasury": -35, "prop": {"stability": 2}, "news": "Свадьба при дворе {realm} гремит на всю округу."}, 2),
        C("quiet", "Скромный обряд", {"treasury": -5, "news": "В {realm} сыграли тихую свадьбу."}, 3)], 2, 60, 6, "quiet"),
    ev("border_incident", "Пограничный инцидент", "Отряд соседа пересёк границу {realm}.", [
        C("protest", "Выразить протест", {"news": "{realm} направила ноту протеста."}, 3),
        C("mobilize", "Усилить гарнизоны", {"treasury": -25, "prop": {"stability": 1}, "news": "{realm} усиливает границу."}, 2),
        C("ignore", "Оставить без внимания", {"prop": {"unrest": 1}, "news": "Нарушение границы {realm} осталось без ответа."}, 1)], 3, 30, 5, "protest", requires={"war": False}),
])
print("ok")
