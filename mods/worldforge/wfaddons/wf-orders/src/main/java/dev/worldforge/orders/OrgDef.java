package dev.worldforge.orders;

import dev.worldforge.mod.Json;

import java.util.*;

/** Описание организации из JSON: звания, взносы, задания, прошения, соперники. */
public final class OrgDef {
    public record Rank(String id, String name, int influence, Map<String, Integer> perks) {}
    public record Mission(String id, String text, String type, String item, int min, int max, int reward, int influence, int minRank, int days) {}
    public record Petition(String id, String name, String desc, int cost, int minRank, String kind, int amount, String action, String args, int cooldown) {}

    public String id, name, kind = "guild", desc = "", seat = "";
    public int color = 0x888888, joinGold = 30, dues = 2, startPower = 40, startTreasury = 200;
    public final List<Rank> ranks = new ArrayList<>();
    public final List<Mission> missions = new ArrayList<>();
    public final List<Petition> petitions = new ArrayList<>();
    public final List<String> rivals = new ArrayList<>(), liked = new ArrayList<>(), disliked = new ArrayList<>();

    private static int num(Object o, int d) { return o instanceof Number n ? n.intValue() : d; }
    private static String str(Object o, String d) { return o == null ? d : String.valueOf(o); }

    @SuppressWarnings("unchecked")
    public static OrgDef parse(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("Организация должна быть объектом JSON");
        Map<String, Object> m = (Map<String, Object>) root;
        if (!(m.get("id") instanceof String) || !(m.get("name") instanceof String)) throw new IllegalArgumentException("Нужны id и name");
        OrgDef d = new OrgDef();
        d.id = (String) m.get("id"); d.name = (String) m.get("name");
        d.kind = str(m.get("kind"), "guild"); d.desc = str(m.get("desc"), ""); d.seat = str(m.get("seat"), "");
        d.color = num(m.get("color"), 0x888888); d.joinGold = num(m.get("join_gold"), 30); d.dues = num(m.get("dues"), 2);
        d.startPower = num(m.get("power"), 40); d.startTreasury = num(m.get("treasury"), 200);
        for (String k : new String[]{"rivals", "liked", "disliked"}) if (m.get(k) instanceof List<?> l) for (Object o : l) (k.equals("rivals") ? d.rivals : k.equals("liked") ? d.liked : d.disliked).add(String.valueOf(o));
        if (m.get("ranks") instanceof List<?> l) for (Object o : l) {
            Map<String, Object> r = (Map<String, Object>) o;
            Map<String, Integer> perks = new LinkedHashMap<>();
            if (r.get("perks") instanceof Map<?, ?> pm) for (var e : pm.entrySet()) perks.put(String.valueOf(e.getKey()), num(e.getValue(), 0));
            d.ranks.add(new Rank(str(r.get("id"), "r" + d.ranks.size()), str(r.get("name"), "Звание"), num(r.get("influence"), 0), perks));
        }
        if (d.ranks.isEmpty()) throw new IllegalArgumentException(d.id + ": нужно хотя бы одно звание");
        if (d.ranks.get(0).influence != 0) throw new IllegalArgumentException(d.id + ": первое звание должно быть с influence 0");
        for (int i = 1; i < d.ranks.size(); i++) if (d.ranks.get(i).influence <= d.ranks.get(i - 1).influence) throw new IllegalArgumentException(d.id + ": influence званий должен расти");
        if (m.get("missions") instanceof List<?> l) for (Object o : l) {
            Map<String, Object> r = (Map<String, Object>) o;
            String type = str(r.get("type"), "fetch");
            if (!Set.of("fetch", "gold", "deed").contains(type)) throw new IllegalArgumentException(d.id + ": тип задания " + type);
            int min = num(r.get("min"), 1);
            d.missions.add(new Mission(str(r.get("id"), "m" + d.missions.size()), str(r.get("text"), "Задание"), type, str(r.get("item"), ""), min, num(r.get("max"), min),
                    num(r.get("reward"), 0), num(r.get("influence"), 5), num(r.get("min_rank"), 0), num(r.get("days"), 10)));
        }
        if (m.get("petitions") instanceof List<?> l) for (Object o : l) {
            Map<String, Object> r = (Map<String, Object>) o;
            String kind = str(r.get("kind"), "gold");
            if (!Set.of("gold", "action").contains(kind)) throw new IllegalArgumentException(d.id + ": вид прошения " + kind);
            d.petitions.add(new Petition(str(r.get("id"), "p" + d.petitions.size()), str(r.get("name"), "Прошение"), str(r.get("desc"), ""), num(r.get("cost"), 10), num(r.get("min_rank"), 0),
                    kind, num(r.get("amount"), 0), str(r.get("action"), ""), str(r.get("args"), ""), num(r.get("cooldown"), 10)));
        }
        return d;
    }
}
