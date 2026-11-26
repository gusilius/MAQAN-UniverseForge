package dev.worldforge.mod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transfers runtime autonomous realm projections to clients without persisting a second
 * political registry. The authoritative source remains WorldSim/Statecraft on the server.
 */
public final class RealmProjectionCodec {
    private RealmProjectionCodec() {}

    public static List<Object> encode(TitleData data) {
        List<Object> out = new ArrayList<>();
        if (data == null) return out;
        for (TitleData.Realm realm : data.realmList()) {
            if (!Boolean.TRUE.equals(realm.props.get("emergence.virtual"))) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", realm.id);
            row.put("name", realm.name);
            row.put("ruler", realm.ruler == null ? "" : realm.ruler);
            row.put("overlord", realm.overlordId);
            row.put("relation", realm.relation == null ? "" : realm.relation);
            row.put("rgb", realm.rgb);
            Map<String, Object> props = new LinkedHashMap<>();
            realm.props.forEach((key, value) -> props.put(key, safe(value)));
            row.put("props", props);
            out.add(row);
        }
        return out;
    }

    public static void apply(TitleData data, Object raw) {
        if (data == null || !(raw instanceof List<?> rows)) return;
        List<TitleData.Realm> projections = new ArrayList<>();
        for (Object value : rows) {
            if (!(value instanceof Map<?, ?> row)) continue;
            Object rawId = row.get("id");
            if (rawId == null || String.valueOf(rawId).isBlank()) continue;
            TitleData.Realm realm = new TitleData.Realm();
            realm.id = String.valueOf(rawId);
            Object name = row.get("name");
            realm.name = name == null ? realm.id : String.valueOf(name);
            Object ruler = row.get("ruler");
            realm.ruler = ruler == null ? "" : String.valueOf(ruler);
            Object overlord = row.get("overlord");
            realm.overlordId = overlord == null || String.valueOf(overlord).isBlank()
                    ? null : String.valueOf(overlord);
            Object relation = row.get("relation");
            realm.relation = relation == null ? "" : String.valueOf(relation);
            Object rgb = row.get("rgb");
            realm.rgb = rgb instanceof Number number ? number.intValue() : 0x888888;
            Object props = row.get("props");
            if (props instanceof Map<?, ?> values) for (var entry : values.entrySet())
                realm.props.put(String.valueOf(entry.getKey()), entry.getValue());
            realm.props.put("emergence.virtual", true);
            projections.add(realm);
        }
        data.replaceRealmProjections(projections);
    }

    private static Object safe(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, nested) -> out.put(String.valueOf(key), safe(nested)));
            return out;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> out = new ArrayList<>();
            for (Object nested : collection) out.add(safe(nested));
            return out;
        }
        if (value.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) out.add(safe(java.lang.reflect.Array.get(value, i)));
            return out;
        }
        return String.valueOf(value);
    }
}
