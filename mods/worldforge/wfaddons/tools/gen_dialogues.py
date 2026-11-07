#!/usr/bin/env python3
"""Добавляет общие узлы общения (s_*) и поручения советнику (d_*) в диалоги wf-dialogue. Запускать из wfaddons; безопасно повторять."""
import json, os, sys
DIR = os.path.join(os.path.dirname(__file__), '..', 'wf-dialogue', 'src', 'main', 'resources', 'data', 'wf_dialogue', 'wf_dialogue')
sp = "{npc_title}"
def A(do, **kw): d = {"do": do}; d.update(kw); return d
def C(i, **kw): d = {"if": i}; d.update(kw); return d
def NOT(*c): return C("not", of=list(c))
def ch(text, nxt=None, cond=None):
    d = {"text": text}
    if nxt: d["next"] = nxt
    if cond: d["if"] = cond
    return d

def social(back, hire=True):
    n = {}
    ready = lambda k: C("npc_ready", kind=k)
    n["s_menu"] = {"speaker": sp, "do": [A("npc_refresh")], "text": "{hearts}  {mood}. {reply}", "choices": [
        ch("Поболтать", "s_chat", [ready("chat")]),
        ch("Пошутить", "s_joke", [ready("joke")]),
        ch("Сделать комплимент", "s_compliment", [ready("compliment")]),
        ch("Флиртовать", "s_flirt", [ready("flirt"), NOT(C("npc_married_to_you"))]),
        ch("Обнять", "s_hug", [ready("hug")]),
        ch("Поцеловать", "s_kiss", [ready("kiss")]),
        ch("Подарить то, что в руке", "s_gift"),
        ch("Что слышно нового?", "s_rumor"),
        ch("Нет ли для меня дела?", "s_req_offer", [NOT(C("npc_has_request"))]),
        ch("Я принёс, что вы просили.", "s_req_done", [C("npc_request_ready")]),
        ch("Напомните, о чём вы просили.", "s_req_show", [C("npc_has_request"), NOT(C("npc_request_ready"))]),
        ch("Предложить встречаться", "s_date", [C("npc_can_date")]),
        ch("Предложить руку и сердце", "s_marry", [C("npc_can_marry")]),
        ch("Расстаться", "s_split", [C("npc_dating"), NOT(C("npc_married_to_you"))]),
        ch("Развестись", "s_split", [C("npc_married_to_you")]),
    ] + ([ch("Предложить службу у меня", "s_hire", [C("npc_can_hire")]), ch("Больше не нужны ваши услуги", "s_dismiss", [C("npc_employed_by_you")])] if hire else [])
      + [ch("Вернуться к делам", back)]}
    def act(do, nxt="s_menu", **kw): return {"speaker": sp, "do": [do], "text": "{reply}", "next": nxt}
    for k in ["chat", "joke", "compliment", "flirt", "hug", "kiss"]:
        n["s_" + k] = act(A("npc_interact", kind=k))
    n["s_gift"] = act(A("npc_gift"))
    n["s_rumor"] = act(A("npc_rumor"))
    n["s_req_offer"] = act(A("npc_request_offer"), "s_req_show")
    n["s_req_show"] = {"speaker": sp, "text": "{request}", "next": "s_menu"}
    n["s_req_done"] = act(A("npc_request_complete"))
    n["s_date"] = act(A("npc_date"))
    n["s_marry"] = act(A("npc_marry"))
    n["s_split"] = act(A("npc_divorce"))
    if hire:
        n["s_hire"] = act(A("npc_hire"))
        n["s_dismiss"] = {"speaker": sp, "do": [A("npc_dismiss")], "text": "Как скажете. Желаю удачи.", "next": "s_menu"}
    return n

def mine(): return [C("player_has_realm"), C("npc_realm_is_yours")]

def council(role):
    n = {}
    if role == "marshal":
        n["d_menu"] = {"speaker": sp, "text": "Слушаю, {player}. Войско - дело серьёзное. Что прикажете?", "choices": [
            ch("Созвать ополчение (50 человек)", "d_levy", mine()), ch("Набрать копейщиков (20)", "d_spear", mine()),
            ch("Нанять лучников (15)", "d_archer", mine()), ch("Нанять наёмников (10)", "d_hire", mine()), ch("Пока ничего", "hello")]}
        n["d_levy"] = {"speaker": sp, "do": [A("delegate", action="army_raise", args="levy|50", ok="Знамёна подняты, ополчение идёт.")], "text": "{reply}", "next": "d_menu"}
        n["d_spear"] = {"speaker": sp, "do": [A("delegate", action="army_raise", args="spear|20", ok="Копейщики набраны.")], "text": "{reply}", "next": "d_menu"}
        n["d_archer"] = {"speaker": sp, "do": [A("delegate", action="army_raise", args="archer|15", ok="Лучники готовы.")], "text": "{reply}", "next": "d_menu"}
        n["d_hire"] = {"speaker": sp, "do": [A("delegate", action="army_hire", args="levy|10", ok="Наёмники подписали договор.")], "text": "{reply}", "next": "d_menu"}
    elif role == "steward":
        n["d_menu"] = {"speaker": sp, "text": "Казна и земли - на мне, {player}. Какие распоряжения?", "choices": [
            ch("Снизить налоги", "d_tax0", mine()), ch("Обычные налоги", "d_tax1", mine()), ch("Повысить налоги", "d_tax2", mine()),
            ch("Дань с вассалов 10%", "d_trib10", mine()), ch("Отменить дань", "d_trib0", mine()), ch("Пока ничего", "hello")]}
        for k, (act, args, ok) in {"d_tax0": ("econ_tax", "0", "Налоги снижены: народ вздохнёт свободнее."), "d_tax1": ("econ_tax", "1", "Налоги обычные."),
                                   "d_tax2": ("econ_tax", "2", "Налоги подняты. Казна потолстеет, народ - не обрадуется."),
                                   "d_trib10": ("econ_tribute", "10", "Вассалам велено платить десятую долю."), "d_trib0": ("econ_tribute", "0", "Дань отменена.")}.items():
            n[k] = {"speaker": sp, "do": [A("delegate", action=act, args=args, ok=ok)], "text": "{reply}", "next": "d_menu"}
    elif role == "chancellor":
        c = lambda i: [C("player_has_realm"), C("npc_realm_is_yours"), C("has_var", name="neighbor%d" % i)]
        n["d_menu"] = {"speaker": sp, "text": "Я отправлю гонцов, {player}. Кому предложить торговый договор?", "choices": [
            ch("{neighbor1_name}", "d_t1", c(1)), ch("{neighbor2_name}", "d_t2", c(2)), ch("{neighbor3_name}", "d_t3", c(3)), ch("Расторгнуть договор с соседом", "d_x1", c(1)), ch("Пока ничего", "hello")]}
        for i in (1, 2, 3):
            n["d_t%d" % i] = {"speaker": sp, "do": [A("delegate", action="trade_propose", args="{neighbor%d}" % i, ok="Гонец отправлен.")], "text": "{reply}", "next": "d_menu"}
        n["d_x1"] = {"speaker": sp, "do": [A("delegate", action="trade_cancel", args="{neighbor1}", ok="Договор расторгнут.")], "text": "{reply}", "next": "d_menu"}
    return n

def quests():
    n = {}
    n["q_list"] = {"speaker": sp, "do": [A("quest_offers")], "text": "{quest_list}", "choices": [
        ch("Беру первое", "q_a1", [C("quest_offers_exist")]), ch("Беру второе", "q_a2", [C("quest_offers_exist")]), ch("Беру третье", "q_a3", [C("quest_offers_exist")]), ch("Назад", "@BACK")]}
    for i in (1, 2, 3):
        n["q_a%d" % i] = {"speaker": sp, "do": [A("quest_accept", n=i)], "text": "{reply}\n\n{quest_list}", "choices": [ch("Ещё что-нибудь?", "q_list"), ch("Назад", "@BACK")]}
    n["q_report"] = {"speaker": sp, "do": [A("quest_interact")], "text": "{reply}", "next": "@BACK"}
    return n

TRADE_GOODS = [("grain", "зерно"), ("fish", "рыбу"), ("meat", "мясо"), ("timber", "брёвна"), ("stone", "камень"), ("iron", "железо")]

def trade():
    n = {}
    n["t_menu"] = {"speaker": sp, "text": "Торговля - дело доброе. Чем интересуетесь?", "choices":
        [ch("Что нынче в цене?", "t_prices"), ch("Куда выгодно везти товар?", "t_route")]
        + [ch("Продать: %s (10)" % nm, "t_sell_" + g) for g, nm in TRADE_GOODS]
        + [ch("Купить: %s (10)" % nm, "t_buy_" + g) for g, nm in TRADE_GOODS] + [ch("Назад", "@BACK")]}
    n["t_prices"] = {"speaker": sp, "do": [A("trade_prices")], "text": "{reply}", "next": "t_menu"}
    n["t_route"] = {"speaker": sp, "do": [A("trade_route")], "text": "{reply}", "next": "t_menu"}
    for g, nm in TRADE_GOODS:
        n["t_sell_" + g] = {"speaker": sp, "do": [A("trade_sell", good=g, qty=10)], "text": "{reply}", "next": "t_menu"}
        n["t_buy_" + g] = {"speaker": sp, "do": [A("trade_buy", good=g, qty=10)], "text": "{reply}", "next": "t_menu"}
    return n

def add(fname, d, role=None, hire=True, hello_text=None):
    start = d["start"]
    nodes = d["nodes"]
    for k, v in social(start, hire).items(): nodes[k] = v
    cn = council(role) if role else {}
    for k, v in cn.items(): nodes[k] = v
    for k, v in quests().items():
        nodes[k] = json.loads(json.dumps(v).replace("@BACK", start))
    for k, v in trade().items():
        nodes[k] = json.loads(json.dumps(v).replace("@BACK", start))
    sc = nodes[start]["choices"]
    sc[:] = [c for c in sc if c.get("next") not in ("s_menu", "d_menu", "q_list", "q_report", "t_menu")]
    extra = []
    if cn: extra.append(ch("Отдать распоряжение", "d_menu", [C("player_has_realm"), C("npc_realm_is_yours")]))
    extra.append(ch("Нет ли для меня поручения?", "q_list", [C("quest_offers_exist")]))
    extra.append(ch("Я по поводу вашего дела", "q_report", [C("quest_interactable")]))
    extra.append(ch("Поговорим о торговле", "t_menu", [C("trade_market_exists")]))
    extra.append(ch("Пообщаться по душам", "s_menu"))
    sc[-1:-1] = extra
    json.dump(d, open(os.path.join(DIR, fname + ".json"), "w", encoding="utf-8"), ensure_ascii=False, indent=2)

def load(f): return json.load(open(os.path.join(DIR, f + ".json"), encoding="utf-8"))
def fresh(i, text, farewell): return {"id": "wf_people:" + i, "start": "hello", "nodes": {"hello": {"speaker": sp, "text": text, "choices": [ch(farewell)]}}}

for f, role, hire in [("villager", None, True), ("ruler", None, False), ("castellan", None, True), ("default", None, True), ("chancellor", "chancellor", False)]:
    add(f, load(f), role, hire)
add("marshal", fresh("marshal", "Маршал {npc} к вашим услугам, {player}.", "Всего доброго."), "marshal", False)
add("steward", fresh("steward", "Управляющий {npc} к вашим услугам, {player}. Казна в порядке, насколько это возможно.", "Всего доброго."), "steward", False)
add("spymaster", fresh("spymaster", "{npc} говорит тихо: стены имеют уши, {player}.", "Не буду отвлекать."), None, False)
print("ok")
