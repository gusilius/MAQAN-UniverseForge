package dev.worldforge.people;

import java.util.*;

/**
 * Что один персонаж (actor, обычно игрок) может сделать с другим (target), и почему нельзя. Чистая логика для окна
 * персонажа: список действий с флагом доступности и причиной отказа. Права дают роли с {@code unlocks}:
 * "appoint" (назначать и снимать), "diplomacy" (устраивать браки), правитель государства (жаловать титулы).
 */
public final class CharacterActions {
    private CharacterActions() {}

    public static final int MARRIAGE_AGE = 16;

    public static final class Action {
        public final String id, label, reason;
        public final boolean enabled;

        Action(String id, String label, boolean enabled, String reason) { this.id = id; this.label = label; this.enabled = enabled; this.reason = enabled ? "" : reason; }
    }

    /** Вариант назначения: роль и место. */
    public static final class Slot {
        public final String role, roleName, place, label, reason;
        public final boolean enabled;

        Slot(Role r, String place, String label, boolean enabled, String reason) { this.role = r.id; this.roleName = r.name; this.place = place; this.label = label; this.enabled = enabled; this.reason = enabled ? "" : reason; }
    }

    /** Места, где actor вправе назначать на должности: государства, где он носит роль с unlocks "appoint". */
    public static List<String> appointPlaces(CharacterRegistry reg, RoleDefs defs, Character actor) {
        List<String> out = new ArrayList<>();
        if (actor == null || !actor.alive) return out;
        for (Character.Post p : actor.posts) {
            Role r = defs.get(p.role);
            if (r != null && r.unlocks.contains("appoint") && !out.contains(p.place)) out.add(p.place);
        }
        return out;
    }

    private static boolean mayDiplomacy(RoleDefs defs, Character a) {
        for (Character.Post p : a.posts) { Role r = defs.get(p.role); if (r != null && r.unlocks.contains("diplomacy")) return true; }
        return false;
    }

    private static boolean rulerAt(RoleDefs defs, Character a) {
        for (Character.Post p : a.posts) { Role r = defs.get(p.role); if (r != null && r.ruler && p.place.startsWith("realm:")) return true; }
        return false;
    }

    private static boolean isAdult(Character c, int year) { return year < 0 || year - c.birthYear >= MARRIAGE_AGE; }

    /** Первая снимаемая должность target в местах власти actor, или null. Правителя снять нельзя. */
    public static Character.Post dismissable(CharacterRegistry reg, RoleDefs defs, Character actor, Character target) {
        List<String> places = appointPlaces(reg, defs, actor);
        for (Character.Post p : target.posts) {
            Role r = defs.get(p.role);
            if (r != null && !r.ruler && places.contains(p.place)) return p;
        }
        return null;
    }

    public static List<Action> available(CharacterRegistry reg, RoleDefs defs, Character actor, Character target) { return available(reg, defs, actor, target, -1); }

    /** year < 0 - возраст не проверяется. Порядок и id действий постоянны: talk, appoint, dismiss, marry, grant. */
    public static List<Action> available(CharacterRegistry reg, RoleDefs defs, Character actor, Character target, int year) {
        List<Action> out = new ArrayList<>();
        boolean self = actor != null && target != null && actor.id.equals(target.id);
        boolean actorOk = actor != null && actor.alive;
        String selfWhy = "Это вы сами";
        String deadWhy = target.name + " мёртв";

        // Talk
        out.add(new Action("talk", "Поговорить", target.alive && !self, self ? selfWhy : deadWhy));

        // Appoint
        String why = null;
        if (!actorOk) why = "У вас нет живого персонажа";
        else if (!target.alive) why = deadWhy;
        else if (self) why = selfWhy;
        else if (appointPlaces(reg, defs, actor).isEmpty()) why = "Нужна роль с правом назначать (правитель)";
        else if (!isAdult(target, year)) why = target.name + " ещё слишком молод";
        else if (slots(reg, defs, actor, target).stream().noneMatch(s -> s.enabled)) why = "Нет свободных должностей, подходящих " + target.name;
        out.add(new Action("appoint", "Назначить на должность...", why == null, why));

        // Dismiss
        Character.Post dp = actorOk && target.alive && !self ? dismissable(reg, defs, actor, target) : null;
        if (dp != null) out.add(new Action("dismiss", "Снять с должности", true, ""));
        else {
            String w = !actorOk ? "У вас нет живого персонажа" : self ? selfWhy : !target.alive ? deadWhy
                    : appointPlaces(reg, defs, actor).isEmpty() ? "Нужна роль с правом назначать (правитель)"
                    : target.posts.isEmpty() ? target.name + " не занимает должностей" : "Должность вне вашей власти или это правитель";
            out.add(new Action("dismiss", "Снять с должности", false, w));
        }

        // Marry
        String mw = null;
        if (!actorOk) mw = "У вас нет живого персонажа";
        else if (!target.alive) mw = deadWhy;
        else if (self) mw = selfWhy;
        else if (!target.spouse.isEmpty()) mw = target.name + " уже в браке";
        else if (!isAdult(target, year)) mw = target.name + " ещё не достиг " + MARRIAGE_AGE + " лет";
        else if (!mayDiplomacy(defs, actor)) mw = "Нужна роль с правом дипломатии";
        out.add(new Action("marry", "Устроить брак", mw == null, mw));

        // Grant title
        String gw = null;
        if (!actorOk) gw = "У вас нет живого персонажа";
        else if (!target.alive) gw = deadWhy;
        else if (self) gw = selfWhy;
        else if (!rulerAt(defs, actor)) gw = "Жаловать титулы может только правитель государства";
        else if (target.house.isEmpty()) gw = target.name + " не из знати";
        else if (!isAdult(target, year)) gw = target.name + " ещё слишком молод";
        out.add(new Action("grant", "Пожаловать титул", gw == null, gw));
        return out;
    }

    /** Варианты назначения для подменю: роли государств, где actor вправе назначать. Занятые и неподходящие даны с причиной. */
    public static List<Slot> slots(CharacterRegistry reg, RoleDefs defs, Character actor, Character target) {
        List<Slot> out = new ArrayList<>();
        for (String place : appointPlaces(reg, defs, actor)) {
            if (!place.startsWith("realm:")) continue;
            for (Role r : defs.all()) {
                if (!r.per.equals("realm") || r.ruler || r.family) continue;
                String label = r.name;
                String why = null;
                if (target.has(r.id, place)) why = target.name + " уже занимает эту должность";
                else if (r.noble && target.house.isEmpty()) why = "Роль только для знати";
                else if (reg.holders(r.id, place).size() >= r.count) { Character h = reg.holder(r.id, place); why = "Занято: " + (h == null ? "?" : h.name) + " (сначала снимите)"; }
                out.add(new Slot(r, place, label, why == null, why));
            }
        }
        return out;
    }

    /** Степень родства from -> to словами ("брат", "дед", "супруг"), или "" если не родня в пределах двух поколений. */
    public static String kinship(CharacterRegistry reg, Character from, Character to) {
        if (from == null || to == null) return "";
        if (from.id.equals(to.id)) return "это вы";
        boolean m = to.gender.equals("m");
        if (to.id.equals(from.spouse)) return m ? "супруг" : "супруга";
        if (to.id.equals(from.father)) return "отец";
        if (to.id.equals(from.mother)) return "мать";
        if (from.children.contains(to.id)) return m ? "сын" : "дочь";
        Character f = reg.get(from.father), mo = reg.get(from.mother);
        for (Character p : new Character[]{f, mo}) {
            if (p == null) continue;
            if (to.id.equals(p.father)) return m ? "дед" : "бабка";
            if (to.id.equals(p.mother)) return m ? "дед" : "бабка";
            if (p.children.contains(to.id)) return m ? "брат" : "сестра";
        }
        for (String k : from.children) { Character kid = reg.get(k); if (kid != null && kid.children.contains(to.id)) return m ? "внук" : "внучка"; }
        return "";
    }
}
