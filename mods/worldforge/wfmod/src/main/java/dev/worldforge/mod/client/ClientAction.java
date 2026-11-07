package dev.worldforge.mod.client;

import dev.worldforge.mod.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Описание действия, пришедшее с сервера (без обработчика). */
public final class ClientAction {
    public final String id, label, scope, perm, hint;
    public final List<String> params;

    ClientAction(String id, String label, String scope, String perm, String hint, List<String> params) {
        this.id = id; this.label = label; this.scope = scope; this.perm = perm; this.hint = hint; this.params = params;
    }

    public boolean op() { return "op".equals(perm); }

    @SuppressWarnings("unchecked")
    public static List<ClientAction> parseList(String json) {
        List<ClientAction> out = new ArrayList<>();
        Object root = Json.parse(json);
        if (!(root instanceof List)) return out;
        for (Object o : (List<Object>) root) {
            Map<String, Object> m = (Map<String, Object>) o;
            List<String> ps = new ArrayList<>();
            if (m.get("params") instanceof List) for (Object p : (List<Object>) m.get("params")) ps.add(String.valueOf(p));
            out.add(new ClientAction(String.valueOf(m.get("id")), String.valueOf(m.get("label")), String.valueOf(m.get("scope")),
                    String.valueOf(m.get("perm")), m.get("hint") == null ? "" : String.valueOf(m.get("hint")), ps));
        }
        return out;
    }
}
