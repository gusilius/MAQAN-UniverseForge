package dev.worldforge.mod.client;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.api.EventLog;

import dev.worldforge.mod.MapSync;
import dev.worldforge.mod.Markers;

/** Состояние карты на клиенте: то, что прислал сервер. Меняется только в главном потоке. */
public final class ClientState {
    public static MapSync.State state;               // null, если в этом измерении нет карты
    public static boolean localWorldSnapshot;        // true only for the bounded loaded-chunk fallback map
    public static double localWorldOriginX, localWorldOriginZ; // world-space origin of the local raster
    public static int[] terrain;                     // основа ARGB (null, пока не пришла)
    public static String terrainHash = "";
    public static Markers markers = new Markers();
    public static final MapSync.Reassembler RE = new MapSync.Reassembler();
    public static int revision;                      // растёт при любом изменении (по нему перерисовываются текстуры)
    public static String mode = "kingdom";
    public static boolean minimap = true;
    public static java.util.List<ClientAction> actions = java.util.List.of();       // действия, зарегистрированные на сервере (кнопки панелей)
    public static final EventLog events = new EventLog();
    public record TreasuryAccount(String id, String currency, double balance, double debt, boolean bankrupt) { }
    public static volatile java.util.Map<String, TreasuryAccount> treasury = java.util.Map.of();

    /** Accepts only the realm treasury projection; private settlement/player accounts never reach the UI. */
    @SuppressWarnings("unchecked")
    public static void updateTreasury(String payload) {
        Object parsed = Json.parse(payload);
        if (!(parsed instanceof java.util.Map<?, ?> root)
                || !"worldforge-realm-treasury/1".equals(root.get("format")))
            throw new IllegalArgumentException("Unsupported realm treasury snapshot");
        java.util.Map<String, TreasuryAccount> next = new java.util.LinkedHashMap<>();
        Object raw = root.get("accounts");
        if (raw instanceof java.util.List<?> accounts) for (Object value : accounts) {
            if (!(value instanceof java.util.Map<?, ?> account)) continue;
            Object idValue = account.get("id"), currencyValue = account.get("currency");
            if (!(idValue instanceof String id) || !id.startsWith("realm:") || id.length() > 136
                    || !(currencyValue instanceof String currency) || currency.isBlank() || currency.length() > 64) continue;
            double balance = finiteNumber(account.get("balance"));
            double debt = finiteNumber(account.get("debt"));
            boolean bankrupt = Boolean.TRUE.equals(account.get("bankrupt"));
            String realmId = id.substring("realm:".length());
            if (realmId.isBlank() || balance < 0 || debt < 0) continue;
            next.put(realmId, new TreasuryAccount(realmId, currency, balance, debt, bankrupt));
        }
        treasury = java.util.Map.copyOf(next);
    }

    private static double finiteNumber(Object value) {
        if (!(value instanceof Number number)) return 0;
        double result = number.doubleValue();
        return Double.isFinite(result) ? result : 0;
    }

    private ClientState() {}
}
