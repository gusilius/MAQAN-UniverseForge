package dev.worldforge.mod.api;

import java.util.List;

/**
 * Действие над государством, титулом или картой: «сменить правителя», «объявить войну», «собрать ополчение».
 * Действия регистрируются через {@link WorldForgeApi#registerAction}; панели карты в игре показывают их кнопками,
 * команда {@code /worldforge action <id> ...} запускает их с сервера. Основной мод ничего не знает о войнах и NPC:
 * их обработчики приходят из аддонов.
 */
public final class MapAction {
    public enum Scope { REALM, TITLE, MAP }

    /** ANY - любой игрок, OP - только оператор (уровень 2). */
    public enum Perm { ANY, OP }

    @FunctionalInterface
    public interface Handler {
        ActionResult run(ActionContext ctx);
    }

    public final String id, label, hint;
    public final Scope scope;
    public final Perm perm;
    public final List<String> params;      // имена дополнительных аргументов (вводятся в панели текстом)
    public final Handler handler;

    public MapAction(String id, String label, Scope scope, Perm perm, List<String> params, String hint, Handler handler) {
        if (id == null || !id.matches("[a-z0-9_.:-]{1,48}")) throw new IllegalArgumentException("Недопустимый id действия: " + id);
        this.id = id;
        this.label = label;
        this.scope = scope;
        this.perm = perm;
        this.params = List.copyOf(params);
        this.hint = hint == null ? "" : hint;
        this.handler = handler;
    }

    /** Описание для передачи клиентам (без обработчика). */
    public String toJson() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("scope", scope.name().toLowerCase());
        m.put("perm", perm.name().toLowerCase());
        m.put("params", new java.util.ArrayList<Object>(params));
        m.put("hint", hint);
        return dev.worldforge.mod.Json.write(m);
    }
}
