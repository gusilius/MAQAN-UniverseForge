package dev.worldforge.settle;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.economy.TreasuryService;

import java.util.*;

/**
 * Торговые маршруты между поселениями. Караван возит ресурс из города, где он дешевле, в город, где дороже, пока есть разница в цене.
 * Сделка идёт по середине между двумя ценами: продавец получает больше своей цены, покупатель платит меньше своей, поэтому выигрывают оба,
 * а цены сближаются (вывоз дорожает дома, ввоз дешевеет на месте). Нужны рынки с торговцами в обоих городах.
 */
public final class TradeNetwork {
    public static final double MIN_SPREAD = 1.08, CUT = 0.04, RESERVE = 10;

    public static final class Route {
        public final int id;
        public final String from, to, res;
        public double rate;            // единиц в минуту
        public double carried, profit, acc;
        public String dutyRealm = "";
        public double dutyRate;
        public String status = "ждёт";

        Route(int id, String from, String to, String res, double rate) { this.id = id; this.from = from; this.to = to; this.res = res; this.rate = rate; }
    }

    public static final class TradeException extends RuntimeException { public TradeException(String m) { super(m); } }

    public final Map<Integer, Route> routes = new LinkedHashMap<>();
    private int next = 1;

    public Route add(Map<String, Settlement> cities, String from, String to, String res, double rate) {
        Settlement a = cities.get(from), b = cities.get(to);
        if (a == null || b == null) throw new TradeException("Нет такого поселения");
        if (from.equals(to)) throw new TradeException("Нужны два разных поселения");
        if (a.basePrice(res) <= 0 || res.equals(Defs.SILVER)) throw new TradeException("Этот ресурс не торгуется");
        if (!Double.isFinite(rate) || rate <= 0 || rate > 30) throw new TradeException("Скорость должна быть конечной величиной от 0 до 30 ед. в минуту");
        for (Route r : routes.values()) if (r.from.equals(from) && r.to.equals(to) && r.res.equals(res)) throw new TradeException("Такой маршрут уже есть");
        Route r = new Route(next++, from, to, res, rate);
        routes.put(r.id, r);
        return r;
    }

    public void remove(int id) { if (routes.remove(id) == null) throw new TradeException("Нет маршрута " + id); }

    /** Configure an ad-valorem customs duty paid by the importing settlement to the destination realm. */
    public void setDuty(int routeId, String realmId, double rate) {
        Route route = routes.get(routeId);
        if (route == null) throw new TradeException("Нет маршрута " + routeId);
        if (!Double.isFinite(rate) || rate < 0 || rate > 0.5) throw new TradeException("Пошлина должна быть от 0 до 50%");
        if (rate > 0 && (realmId == null || realmId.isBlank())) throw new TradeException("Для пошлины нужна казна государства");
        route.dutyRealm = rate == 0 ? "" : realmId.trim();
        route.dutyRate = rate;
    }

    public void tick(Map<String, Settlement> cities, double dt) {
        if (!Double.isFinite(dt) || dt <= 0) return;
        for (Route r : routes.values()) {
            Settlement a = cities.get(r.from), b = cities.get(r.to);
            if (a == null || b == null) { r.status = "нет города"; continue; }
            if (a.tradePerMin() <= 0 || b.tradePerMin() <= 0) { r.status = "нет рынка"; continue; }
            r.acc += r.rate * dt / 60;
            int n = (int) Math.floor(r.acc);
            if (n <= 0) continue;
            double pa = a.price(r.res), pb = b.price(r.res);
            if (pb < pa * MIN_SPREAD) { r.status = "нет разницы"; r.acc = Math.min(r.acc, 1); continue; }
            double mid = (pa + pb) / 2, give = mid * (1 - CUT), pay = mid * (1 + CUT * 0);
            double dutyPerUnit = mid * r.dutyRate;
            double buyerCostPerUnit = pay + dutyPerUnit;
            double protectedReserve = exportReserve(a, r.res);
            n = (int) Math.min(n, a.amount(r.res) - protectedReserve);
            n = (int) Math.min(n, b.capacity(r.res) - b.amount(r.res));
            n = (int) Math.min(n, Math.min(a.tradeLeft, b.tradeLeft));
            if (buyerCostPerUnit > 0) n = (int) Math.min(n, Math.floor(b.amount(Defs.SILVER) / buyerCostPerUnit));
            if (n <= 0) {
                r.status = a.amount(r.res) - protectedReserve < 1 ? "защита резерва"
                        : b.amount(Defs.SILVER) < pay ? "нет серебра" : "нет места";
                continue;
            }
            double sellerPayment = n * give;
            double marketFee = n * Math.max(0, pay - give);
            double duty = n * dutyPerUnit;
            if (duty > 0) TreasuryService.current().open("realm:" + r.dutyRealm, r.dutyRealm, Defs.SILVER, 0);
            if (!b.transferSilverTo(a, sellerPayment, "caravan-trade", "route:" + r.id)
                    || !b.chargeSilver(marketFee, "market-fee", "route:" + r.id)
                    || (duty > 0 && !b.transferSilverToAccount("realm:" + r.dutyRealm, duty,
                    dev.worldforge.mod.economy.TreasuryLedger.Kind.DUTY, "customs-duty", "route:" + r.id))) {
                r.status = "нет серебра";
                continue;
            }
            if (!a.transferResourceTo(b, r.res, n, "caravan-goods", "route:" + r.id)) {
                // Preconditions above make this unreachable in the single-threaded server tick.
                throw new TradeException("Inventory changed during route settlement " + r.id);
            }
            a.tradeLeft -= n; b.tradeLeft -= n;
            a.shiftDemand(r.res, n); b.shiftDemand(r.res, -n);
            r.acc -= n; r.carried += n;
            r.profit += n * (give - pa) + n * (pb - pay) - duty;
            r.status = "возит";
        }
    }

    /**
     * Creates a bounded set of autonomous supply links between staffed markets.
     * Price spread and available stock decide direction; each settlement can participate
     * in only a small number of routes, preventing every village from becoming a trade hub.
     */
    public int ensureAutonomousRoutes(Map<String, Settlement> cities, int perSettlementLimit,
                                      int maxNewRoutes, double rate) {
        if (cities == null || cities.size() < 2 || perSettlementLimit < 1 || maxNewRoutes < 1
                || !Double.isFinite(rate) || rate <= 0 || rate > 30) return 0;
        Map<String, Integer> participation = new HashMap<>();
        for (Route route : routes.values()) {
            participation.merge(route.from, 1, Integer::sum);
            participation.merge(route.to, 1, Integer::sum);
        }
        int created = 0;
        for (String[] suggestion : suggestions(cities, Math.max(8, cities.size() * 4))) {
            if (created >= maxNewRoutes) break;
            Settlement from = cities.get(suggestion[0]), to = cities.get(suggestion[1]);
            if (from == null || to == null || from.tradePerMin() <= 0 || to.tradePerMin() <= 0) continue;
            String resource = suggestion[2];
            if (!hasSupplyNeed(from, to, resource)) continue;
            if (participation.getOrDefault(from.id, 0) >= perSettlementLimit
                    || participation.getOrDefault(to.id, 0) >= perSettlementLimit) continue;
            boolean duplicate = routes.values().stream().anyMatch(route ->
                    route.from.equals(from.id) && route.to.equals(to.id) && route.res.equals(suggestion[2]));
            if (duplicate) continue;
            try {
                add(cities, from.id, to.id, suggestion[2], rate);
                participation.merge(from.id, 1, Integer::sum);
                participation.merge(to.id, 1, Integer::sum);
                created++;
            } catch (TradeException ignored) {
                // A stale price/stock suggestion is harmless; reconsider it on the next review.
            }
        }
        return created;
    }

    /** Shared eligibility rule for automatic route creation and the settlement trade panel. */
    private static double exportReserve(Settlement settlement, String resource) {
        return Math.max(RESERVE * 2, settlement.consumedPerMin(resource) * 120.0);
    }

    private static boolean hasSupplyNeed(Settlement from, Settlement to, String resource) {
        if (from == null || to == null || resource == null || resource.isBlank()) return false;
        double sourceStock = from.amount(resource), destinationStock = to.amount(resource);
        return sourceStock >= exportReserve(from, resource)
                && destinationStock < sourceStock * 0.5
                && to.capacity(resource) - destinationStock >= 1.0;
    }

    /** Same candidate policy for the client projection; all values originate from Settlement. */
    public static List<String[]> suggestions(List<CityRow> cities, int limit) {
        List<double[]> scores = new ArrayList<>();
        List<String[]> keys = new ArrayList<>();
        if (cities == null || limit <= 0) return List.of();
        for (CityRow from : cities) for (CityRow to : cities) {
            if (from == to || from.tradeRate <= 0 || to.tradeRate <= 0) continue;
            for (var entry : from.prices.entrySet()) {
                String resource = entry.getKey();
                double sourcePrice = entry.getValue(), destinationPrice = to.prices.getOrDefault(resource, 0.0);
                double sourceStock = from.stock.getOrDefault(resource, 0.0);
                double destinationStock = to.stock.getOrDefault(resource, 0.0);
                double exportReserve = Math.max(RESERVE * 2,
                        from.consumption.getOrDefault(resource, 0.0) * 120.0);
                if (sourcePrice <= 0 || destinationPrice < sourcePrice * MIN_SPREAD
                        || sourceStock < exportReserve || destinationStock >= sourceStock * 0.5
                        || to.capacity.getOrDefault(resource, 0.0) - destinationStock < 1.0) continue;
                scores.add(new double[]{destinationPrice / sourcePrice});
                keys.add(new String[]{from.id, to.id, resource});
            }
        }
        Integer[] order = new Integer[keys.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(scores.get(b)[0], scores.get(a)[0]));
        List<String[]> result = new ArrayList<>();
        for (int index : order) {
            if (result.size() >= limit) break;
            result.add(keys.get(index));
        }
        return result;
    }

    /** Лучшие пары для маршрутов: отношение цен, убывание. */
    public static List<String[]> suggestions(Map<String, Settlement> cities, int limit) {
        List<double[]> tmp = new ArrayList<>();
        List<String[]> keys = new ArrayList<>();
        for (Settlement a : cities.values()) for (Settlement b : cities.values()) {
            if (a == b) continue;
            for (String res : a.defs.prices.keySet()) {
                if (res.equals(Defs.SILVER)) continue;
                double pa = a.price(res), pb = b.price(res);
                if (pa <= 0 || pb < pa * MIN_SPREAD || !hasSupplyNeed(a, b, res)) continue;
                tmp.add(new double[]{pb / pa}); keys.add(new String[]{a.id, b.id, res});
            }
        }
        Integer[] idx = new Integer[keys.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (x, y) -> Double.compare(tmp.get(y)[0], tmp.get(x)[0]));
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < idx.length && out.size() < limit; i++) out.add(keys.get(idx[i]));
        return out;
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "wf-trade/1"); root.put("next", next);
        List<Object> l = new ArrayList<>();
        for (Route r : routes.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id); m.put("from", r.from); m.put("to", r.to); m.put("res", r.res); m.put("rate", r.rate);
            m.put("carried", r.carried); m.put("profit", r.profit); m.put("acc", r.acc); m.put("status", r.status);
            m.put("dutyRealm", r.dutyRealm); m.put("dutyRate", r.dutyRate);
            l.add(m);
        }
        root.put("routes", l);
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public static TradeNetwork fromJson(String json) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        if (!"wf-trade/1".equals(root.get("format"))) throw new IllegalArgumentException("Неверный формат торговли");
        TradeNetwork n = new TradeNetwork();
        n.next = ((Number) root.get("next")).intValue();
        for (Object o : (List<Object>) root.get("routes")) {
            Map<String, Object> m = (Map<String, Object>) o;
            Route r = new Route(((Number) m.get("id")).intValue(), (String) m.get("from"), (String) m.get("to"), (String) m.get("res"), ((Number) m.get("rate")).doubleValue());
            r.carried = ((Number) m.get("carried")).doubleValue(); r.profit = ((Number) m.get("profit")).doubleValue(); r.acc = ((Number) m.get("acc")).doubleValue(); r.status = (String) m.get("status");
            if (m.get("dutyRealm") instanceof String realm) r.dutyRealm = realm;
            if (m.get("dutyRate") instanceof Number rate) r.dutyRate = Math.max(0, Math.min(0.5, rate.doubleValue()));
            n.routes.put(r.id, r);
        }
        return n;
    }
}
