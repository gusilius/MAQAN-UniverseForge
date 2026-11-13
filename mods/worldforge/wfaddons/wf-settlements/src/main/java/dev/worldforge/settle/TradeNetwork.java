package dev.worldforge.settle;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.economy.TreasuryService;
import dev.worldforge.mod.economy.TreasuryLedger;

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

    /** Physical anchor supplied by the Minecraft adapter; absent anchors remain strategic-only. */
    public record Location(String dimension, double x, double z) {
        public Location { dimension = dimension == null ? "" : dimension; if (!Double.isFinite(x) || !Double.isFinite(z)) throw new IllegalArgumentException("invalid location"); }
    }
    /** Route planner is supplied by the Minecraft adapter; simulation remains independent of loaded chunks. */
    @FunctionalInterface public interface RoutePlanner {
        double[][] plan(String from, String to, Location origin, Location destination);
    }
    /** Persisted consignment backed by real TreasuryLedger escrow accounts. */
    public static final class Shipment {
        public final long id; public final int routeId;
        public final String from, to, res, dutyRealm, fromDimension, toDimension;
        public final double quantity, sellerPayment, marketFee, duty, profit, fromX, fromZ, toX, toZ, travelSeconds;
        private final double[][] path;
        public double elapsed; public String status = "в пути";
        Shipment(long id,int routeId,String from,String to,String res,double quantity,double sellerPayment,
                 double marketFee,double duty,String dutyRealm,Location a,Location b,double travelSeconds,double profit,double[][] path) {
            this.id=id; this.routeId=routeId; this.from=from; this.to=to; this.res=res; this.quantity=quantity;
            this.sellerPayment=sellerPayment; this.marketFee=marketFee; this.duty=duty; this.profit=profit; this.dutyRealm=dutyRealm == null ? "" : dutyRealm;
            fromDimension=a==null?"":a.dimension(); toDimension=b==null?"":b.dimension();
            fromX=a==null?0:a.x(); fromZ=a==null?0:a.z(); toX=b==null?0:b.x(); toZ=b==null?0:b.z();
            this.travelSeconds=travelSeconds;
            this.path = copyPath(path);
        }
        public double progress() { return Math.max(0,Math.min(1,elapsed/Math.max(1,travelSeconds))); }
        public boolean physical() { return !fromDimension.isBlank() && fromDimension.equals(toDimension); }
        public double[][] path() { return copyPath(path); }
        public double remainingSeconds() { return Math.max(0, travelSeconds - elapsed); }
    }
    public final Map<Integer, Route> routes = new LinkedHashMap<>();
    public final Map<Long, Shipment> shipments = new LinkedHashMap<>();
    public final List<String> events = new ArrayList<>();
    private int next = 1;
    private long nextShipment = 1;

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

    public void tick(Map<String, Settlement> cities, double dt) { tick(cities, dt, Map.of()); }

    /** Existing consignments settle first; new cargo is reserved in escrow and never teleported to the buyer. */
    public void tick(Map<String, Settlement> cities, double dt, Map<String, Location> locations) {
        tick(cities, dt, locations, null);
    }

    /** The adapter may provide the same title-road route used by national trade caravans. */
    public void tick(Map<String, Settlement> cities, double dt, Map<String, Location> locations, RoutePlanner planner) {
        if (!Double.isFinite(dt) || dt <= 0) return;
        Map<String,Location> anchors = locations == null ? Map.of() : locations;
        TreasuryLedger ledger = TreasuryService.current();
        for (Shipment shipment : shipments.values()) {
            if (!shipment.status.equals("в пути") && !shipment.status.equals("ожидает места")
                    && !shipment.status.equals("ожидает расчёта")) continue;
            Settlement destination = cities.get(shipment.to), source = cities.get(shipment.from);
            if (destination == null || source == null) { shipment.status = "нет поселения"; continue; }
            shipment.elapsed = Math.min(shipment.travelSeconds, shipment.elapsed + dt);
            if (shipment.elapsed + 1e-9 < shipment.travelSeconds) continue;
            destination.amount(shipment.res);
            String cargoEscrow = cargoEscrow(shipment.id), paymentEscrow = paymentEscrow(shipment.id);
            String goodsRef = shipmentReference(shipment.id,"arrival-goods");
            String destAccount = destination.ledgerAccountId(shipment.res);
            boolean goodsPosted = ledger.hasTransaction(destAccount,TreasuryLedger.Kind.TRADE,goodsRef);
            if (!goodsPosted && destination.capacity(shipment.res)-destination.amount(shipment.res)
                    - reservedIncoming(shipment.to, shipment.res, shipment.id) + 1e-9 < shipment.quantity) {
                shipment.status = "ожидает места"; continue;
            }
            if (!ledger.transferOnce(cargoEscrow,destAccount,shipment.quantity,TreasuryLedger.Kind.TRADE,
                    "shipment-arrival",goodsRef,destination.worldMinute(),"settlement shipment delivered")) continue;
            destination.refreshResourceMirror(shipment.res);
            destination.amount(shipment.res);
            if (!goodsPosted) {
                destination.flow.recordTrade(destination.time,shipment.res,shipment.quantity,true);
                destination.shiftDemand(shipment.res,-shipment.quantity);
            }
            source.amount(Defs.SILVER);
            if (!ledger.transferOnce(paymentEscrow,source.ledgerAccountId(Defs.SILVER),shipment.sellerPayment,
                    TreasuryLedger.Kind.TRADE,"shipment-settlement",shipmentReference(shipment.id,"seller-payment"),
                    source.worldMinute(),"settlement seller payment")) { shipment.status="ожидает расчёта"; continue; }
            source.refreshResourceMirror(Defs.SILVER);
            if (shipment.marketFee>1e-9 && !ledger.withdrawOnce(paymentEscrow,shipment.marketFee,
                    TreasuryLedger.Kind.TRADE,"shipment-market-fee",shipmentReference(shipment.id,"market-fee"),
                    destination.worldMinute(),"settlement market fee")) { shipment.status="ожидает расчёта"; continue; }
            if (shipment.duty>1e-9) {
                String realmAccount="realm:"+shipment.dutyRealm;
                ledger.open(realmAccount,shipment.dutyRealm,Defs.SILVER,0);
                if (!ledger.transferOnce(paymentEscrow,realmAccount,shipment.duty,TreasuryLedger.Kind.DUTY,
                        "customs-duty",shipmentReference(shipment.id,"customs-duty"),destination.worldMinute(),
                        "settlement shipment customs duty")) { shipment.status="ожидает расчёта"; continue; }
            }
            source.amount(Defs.SILVER); shipment.status="доставлен";
            Route route=routes.get(shipment.routeId);
            if(route!=null){ route.carried+=shipment.quantity; route.profit+=shipment.profit; route.status="доставлен"; }
            events.add("Доставка #"+shipment.id+": "+shipment.quantity+" "+shipment.res+" из "+shipment.from+" в "+shipment.to);
        }

        for(Route route:routes.values()){
            Settlement a=cities.get(route.from), b=cities.get(route.to);
            if(a==null||b==null){route.status="нет города";continue;}
            if(a.tradePerMin()<=0||b.tradePerMin()<=0){route.status="нет рынка";continue;}
            route.acc+=route.rate*dt/60; int n=(int)Math.floor(route.acc); if(n<=0)continue;
            double pa=a.price(route.res),pb=b.price(route.res);
            if(pb<pa*MIN_SPREAD){route.status="нет разницы";route.acc=Math.min(route.acc,1);continue;}
            double mid=(pa+pb)/2,give=mid*(1-CUT),pay=mid;
            double dutyPerUnit=mid*route.dutyRate,buyerCostPerUnit=pay+dutyPerUnit;
            double reserve=exportReserve(a,route.res);
            n=(int)Math.min(n,a.amount(route.res)-reserve);
            n=(int)Math.min(n,b.capacity(route.res)-b.amount(route.res)-reservedIncoming(route.to,route.res,-1));
            n=(int)Math.min(n,Math.min(a.tradeLeft,b.tradeLeft));
            if(buyerCostPerUnit>0)n=(int)Math.min(n,Math.floor(b.amount(Defs.SILVER)/buyerCostPerUnit));
            if(n<=0){route.status=a.amount(route.res)-reserve<1?"защита резерва":b.amount(Defs.SILVER)<pay?"нет серебра":"нет места";continue;}

            long id=nextShipment++; double seller=n*give,fee=n*Math.max(0,pay-give),duty=n*dutyPerUnit,total=seller+fee+duty;
            String owner="shipment:"+id, cargo=cargoEscrow(id), payment=paymentEscrow(id);
            ledger.open(cargo,owner,route.res,0); ledger.open(payment,owner,Defs.SILVER,0);
            String dispatch=shipmentReference(id,"dispatch");
            if(!b.transferResourceToAccount(Defs.SILVER,payment,owner,total,TreasuryLedger.Kind.TRADE,
                    "shipment-payment-escrow",dispatch)){route.status="нет серебра";continue;}
            if(!a.transferResourceToAccount(route.res,cargo,owner,n,TreasuryLedger.Kind.TRADE,
                    "shipment-cargo-escrow",dispatch)){
                ledger.transferOnce(payment,b.ledgerAccountId(Defs.SILVER),total,TreasuryLedger.Kind.TRADE,
                        "shipment-payment-refund",shipmentReference(id,"dispatch-refund"),b.worldMinute(),
                        "refund failed shipment dispatch");
                b.refreshResourceMirror(Defs.SILVER); b.amount(Defs.SILVER);route.status="нет товара";continue;
            }
            a.tradeLeft-=n;b.tradeLeft-=n;a.shiftDemand(route.res,n);route.acc-=n;
            Location from=anchors.get(route.from),to=anchors.get(route.to);
            double[][] path = planner == null ? null : planner.plan(route.from, route.to, from, to);
            if (!validPath(path) && from != null && to != null && from.dimension().equals(to.dimension()))
                path = new double[][]{{from.x(),from.z()},{to.x(),to.z()}};
            if (!validPath(path)) path = new double[0][];
            double travel=60;
            if(from!=null&&to!=null&&from.dimension().equals(to.dimension()))
                travel=Math.max(15,pathLength(path)/8.0);
            double expectedProfit=n*(give-pa)+n*(pb-pay)-duty;
            Shipment shipment=new Shipment(id,route.id,route.from,route.to,route.res,n,seller,fee,duty,
                    route.dutyRealm,from,to,travel,expectedProfit,path);
            shipments.put(id,shipment);route.status="в пути";
            events.add("Караван #"+id+" отправлен: "+n+" "+route.res+" из "+route.from+" в "+route.to);
        }
        if(shipments.size()>600){
            Iterator<Map.Entry<Long,Shipment>> it=shipments.entrySet().iterator();
            while(shipments.size()>500&&it.hasNext()){
                String status=it.next().getValue().status;
                if(!status.equals("в пути")&&!status.equals("ожидает места")&&!status.equals("ожидает расчёта"))it.remove();
            }
        }
    }

    /** Cargo already committed to this destination but not yet posted to its stock account. */
    private double reservedIncoming(String city, String resource, long exceptShipment) {
        double reserved = 0;
        for (Shipment shipment : shipments.values()) {
            if (shipment.id == exceptShipment || !shipment.to.equals(city) || !shipment.res.equals(resource)) continue;
            if ((shipment.status.equals("в пути") || shipment.status.equals("ожидает места")
                    || shipment.status.equals("ожидает расчёта"))
                    && TreasuryService.current().balance(cargoEscrow(shipment.id)) > 1e-9)
                reserved += shipment.quantity;
        }
        return reserved;
    }

    public static String cargoEscrow(long id){return "shipment:cargo:"+id;}
    public static String paymentEscrow(long id){return "shipment:payment:"+id;}
    public static String shipmentReference(long id,String leg){return "shipment:"+id+":"+leg;}

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
        root.put("format", "wf-trade/2"); root.put("next", next); root.put("nextShipment", nextShipment);
        List<Object> l = new ArrayList<>();
        for (Route r : routes.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id); m.put("from", r.from); m.put("to", r.to); m.put("res", r.res); m.put("rate", r.rate);
            m.put("carried", r.carried); m.put("profit", r.profit); m.put("acc", r.acc); m.put("status", r.status);
            m.put("dutyRealm", r.dutyRealm); m.put("dutyRate", r.dutyRate);
            l.add(m);
        }
        root.put("routes", l);
        List<Object> consignments=new ArrayList<>();
        for(Shipment s:shipments.values()){
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("id",s.id);m.put("route",s.routeId);m.put("from",s.from);m.put("to",s.to);m.put("res",s.res);
            m.put("quantity",s.quantity);m.put("sellerPayment",s.sellerPayment);m.put("marketFee",s.marketFee);
            m.put("duty",s.duty);m.put("profit",s.profit);m.put("dutyRealm",s.dutyRealm);m.put("fromDimension",s.fromDimension);
            m.put("toDimension",s.toDimension);m.put("fromX",s.fromX);m.put("fromZ",s.fromZ);
            m.put("toX",s.toX);m.put("toZ",s.toZ);m.put("travelSeconds",s.travelSeconds);
            m.put("elapsed",s.elapsed);m.put("status",s.status);
            List<Object> routePoints=new ArrayList<>();
            for(double[] point:s.path()) routePoints.add(List.of(point[0],point[1]));
            m.put("path",routePoints);consignments.add(m);
        }
        root.put("shipments",consignments);
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public static TradeNetwork fromJson(String json) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        if (!"wf-trade/1".equals(root.get("format")) && !"wf-trade/2".equals(root.get("format")))
            throw new IllegalArgumentException("Неверный формат торговли");
        TradeNetwork n = new TradeNetwork();
        n.next = ((Number) root.get("next")).intValue();
        if(root.get("nextShipment") instanceof Number v)n.nextShipment=Math.max(1,v.longValue());
        for (Object o : (List<Object>) root.get("routes")) {
            Map<String, Object> m = (Map<String, Object>) o;
            Route r = new Route(((Number) m.get("id")).intValue(), (String) m.get("from"), (String) m.get("to"), (String) m.get("res"), ((Number) m.get("rate")).doubleValue());
            r.carried = ((Number) m.get("carried")).doubleValue(); r.profit = ((Number) m.get("profit")).doubleValue(); r.acc = ((Number) m.get("acc")).doubleValue(); r.status = (String) m.get("status");
            if (m.get("dutyRealm") instanceof String realm) r.dutyRealm = realm;
            if (m.get("dutyRate") instanceof Number rate) r.dutyRate = Math.max(0, Math.min(0.5, rate.doubleValue()));
            n.routes.put(r.id, r);
        }
        if(root.get("shipments") instanceof List<?> rows)for(Object row:rows){
            if(!(row instanceof Map<?,?> m))continue;
            long id=((Number)m.get("id")).longValue();
            Location a=new Location(m.get("fromDimension") instanceof String v?v:"",num(m.get("fromX")),num(m.get("fromZ")));
            Location b=new Location(m.get("toDimension") instanceof String v?v:"",num(m.get("toX")),num(m.get("toZ")));
            Shipment shipment=new Shipment(id,((Number)m.get("route")).intValue(),(String)m.get("from"),
                    (String)m.get("to"),(String)m.get("res"),((Number)m.get("quantity")).doubleValue(),
                    ((Number)m.get("sellerPayment")).doubleValue(),((Number)m.get("marketFee")).doubleValue(),
                    ((Number)m.get("duty")).doubleValue(),m.get("dutyRealm") instanceof String v?v:"",a,b,
                    Math.max(1,((Number)m.get("travelSeconds")).doubleValue()),num(m.get("profit")),readPath(m.get("path"),a,b));
            shipment.elapsed=Math.max(0,Math.min(shipment.travelSeconds,num(m.get("elapsed"))));
            shipment.status=m.get("status") instanceof String v?v:"в пути";
            n.shipments.put(id,shipment);n.nextShipment=Math.max(n.nextShipment,id+1);
        }
        return n;
    }
    private static double num(Object value){return value instanceof Number n&&Double.isFinite(n.doubleValue())?n.doubleValue():0;}
    private static boolean validPath(double[][] path) {
        if (path == null || path.length < 2) return false;
        for (double[] point : path) if (point == null || point.length < 2
                || !Double.isFinite(point[0]) || !Double.isFinite(point[1])) return false;
        return true;
    }
    private static double pathLength(double[][] path) {
        if (!validPath(path)) return 0;
        double length=0;
        for(int i=1;i<path.length;i++) length+=Math.hypot(path[i][0]-path[i-1][0],path[i][1]-path[i-1][1]);
        return length;
    }
    private static double[][] copyPath(double[][] path) {
        if (path == null) return new double[0][];
        double[][] copy=new double[path.length][];
        for(int i=0;i<path.length;i++) copy[i]=path[i]==null?new double[0]:path[i].clone();
        return copy;
    }
    private static double[][] readPath(Object raw, Location from, Location to) {
        if(raw instanceof List<?> rows) {
            List<double[]> points=new ArrayList<>();
            for(Object row:rows) if(row instanceof List<?> pair && pair.size()>=2) {
                double x=num(pair.get(0)),z=num(pair.get(1));
                if(Double.isFinite(x)&&Double.isFinite(z)) points.add(new double[]{x,z});
            }
            double[][] path=points.toArray(double[][]::new);
            if(validPath(path)) return path;
        }
        return from!=null&&to!=null&&!from.dimension().isBlank()
                && from.dimension().equals(to.dimension())
                ? new double[][]{{from.x(),from.z()},{to.x(),to.z()}} : new double[0][];
    }
}
