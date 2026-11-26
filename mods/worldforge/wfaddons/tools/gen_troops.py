import json, os
U = [
 ("levy_spear","Ополченец с копьём","melee",20,3,2.5,1.0,0,24,8,0.1,"iron_spear","","leather_chestplate","","","Крестьянин, которого позвали на войну."),
 ("militia_archer","Ополченец-лучник","ranged",16,3,16,1.0,0,28,10,0.1,"bow","","","","","Охотник с коротким луком."),
 ("footman","Пехотинец","melee",26,5,2.5,1.0,4,24,20,0.3,"iron_sword","iron_helmet","iron_chestplate","","","Стандартный линейный воин."),
 ("pikeman","Пикинёр","melee",28,4,3.5,0.9,5,24,22,0.3,"iron_spear","iron_helmet","chainmail_chestplate","","","Длинное копьё держит конницу."),
 ("archer","Лучник","ranged",18,4,20,1.0,1,32,22,0.3,"bow","leather_helmet","leather_chestplate","","","Обученный стрелок."),
 ("crossbowman","Арбалетчик","ranged",20,7,24,0.9,3,32,30,0.4,"crossbow","iron_helmet","chainmail_chestplate","","","Пробивает броню."),
 ("light_cavalry","Лёгкая конница","cavalry",24,5,2.5,1.6,2,40,35,0.5,"iron_sword","chainmail_helmet","chainmail_chestplate","","","Быстрые рейды и преследование."),
 ("knight","Рыцарь","cavalry",40,8,2.8,1.4,10,36,70,1.0,"diamond_sword","iron_helmet","iron_chestplate","iron_leggings","iron_boots","Тяжёлый кавалерист."),
 ("scout","Разведчик","ranged",14,3,14,1.3,0,60,15,0.2,"bow","","leather_chestplate","","","Видит дальше всех."),
 ("ram","Таран (расчёт)","siege",40,6,3.0,0.7,6,24,45,0.5,"iron_axe","iron_helmet","iron_chestplate","","","Бьёт ворота и стены."),
 ("catapult","Катапульта (расчёт)","siege",22,2,30,0.7,1,40,60,0.6,"","leather_helmet","leather_chestplate","","","Метает камни по стенам издалека."),
 ("healer","Лекарь","support",16,1,2,1.0,0,24,25,0.3,"","","","","","Лечит раненых рядом."),
 ("shieldbearer","Щитоносец","melee",32,4,2.3,0.85,8,22,26,0.4,"iron_sword","iron_helmet","iron_chestplate","iron_leggings","","Держит строй под обстрелом."),
 ("sergeant","Сержант","melee",34,6,2.5,1.0,6,28,40,0.6,"iron_sword","iron_helmet","iron_chestplate","chainmail_leggings","","Командир отделения."),
]
K=["id","name","role","hp","dmg","range","speed","armor","sight","cost","upkeep","weapon","head","chest","legs","feet","desc"]
AURA={"sergeant":12,"knight":8}; HEAL={"healer":2.0}; SIEGE={"ram":14,"catapult":9}; SOLDIER_SIEGE=0.4
d={"army_map":{"levy":"levy_spear","spear":"pikeman","archer":"archer","cavalry":"light_cavalry","knight":"knight","siege":"ram"},"units":[dict(zip(K,u), **({"aura":AURA[u[0]]} if u[0] in AURA else {}), **({"heal":HEAL[u[0]]} if u[0] in HEAL else {}), **({"siege":SIEGE[u[0]]} if u[0] in SIEGE else ({"siege":SOLDIER_SIEGE} if u[2]=="melee" else {}))) for u in U]}
p=os.path.join(os.path.dirname(__file__),"..","wf-troops/src/main/resources/data/wf_troops/wf_troops/units.json")
os.makedirs(os.path.dirname(p),exist_ok=True)
json.dump(d,open(p,"w",encoding="utf-8"),ensure_ascii=False,indent=1)
print(len(U),"units")
