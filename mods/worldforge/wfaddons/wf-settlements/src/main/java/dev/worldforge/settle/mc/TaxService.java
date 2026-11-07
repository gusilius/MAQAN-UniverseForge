package dev.worldforge.settle.mc;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.WFNet;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.api.ActionContext;
import dev.worldforge.mod.api.ActionResult;
import dev.worldforge.mod.api.MapAction;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.settle.CityTax;
import dev.worldforge.settle.Settlement;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Налоги городов в казну государства: раз в игровой день доля короны уходит из городов в казну (TreasuryApi), правитель управляет долей, налоговой политикой и грантами
 * действиями city_crown / city_tax / city_grant; окно «Казна и города» - клавиша U, команда /wftax.
 */
public final class TaxService {
    public static final CityTax TAX = new CityTax();
    public static final String VIEW = "wf_settle:treasury";
    private static long ticks;
    private static boolean registered;

    public TaxService() {
        if (registered) return;
        registered = true;
        registerActions();
    }

    private static Path file(MinecraftServer s) { return s.getWorldPath(LevelResource.ROOT).resolve("wf_settle_tax.json"); }

    private static String realmOf(Settlement s) {
        Colony c = SettleMod.COLONIES.get(s.id);
        return c == null ? "" : SettleMod.realmOf(c);
    }

    private static List<Settlement> cities() {
        List<Settlement> l = new ArrayList<>();
        for (Colony c : SettleMod.COLONIES.values()) l.add(c.s);
        return l;
    }

    private static TitleData.Realm realm(String id) {
        for (MapData m : WorldForgeMod.loadedMaps().values()) if (m.titles != null && m.titles.realms.containsKey(id)) return m.titles.realms.get(id);
        return null;
    }

    static String realmOfPlayer(ServerPlayer p) {
        String name = p.getGameProfile().getName();
        for (MapData m : WorldForgeMod.loadedMaps().values()) if (m.titles != null) for (TitleData.Realm r : m.titles.realmList()) { Object who = r.props.get("player"); if (who != null && String.valueOf(who).equals(name)) return r.id; }
        // не правитель - казна государства, где стоит ближайшее поселение
        Colony c = SettleMod.nearest(p.serverLevel(), p.getX(), p.getZ());
        return c == null ? "" : SettleMod.realmOf(c);
    }

    private static boolean mayRule(ActionContext c) {
        if (c.operator) return true;
        TitleData.Realm r = realm(c.realmId);
        return r != null && r.props.get("player") != null && String.valueOf(r.props.get("player")).equals(c.actor);
    }

    private static void act(String id, String label, List<String> params, String hint, java.util.function.Function<ActionContext, String> body) {
        WorldForgeApi.registerAction(new MapAction(id, label, MapAction.Scope.REALM, MapAction.Perm.ANY, params, hint, ctx -> {
            if (!mayRule(ctx)) return ActionResult.fail("Это может решать только правитель государства.");
            try { return ActionResult.ok(body.apply(ctx)); }
            catch (Settlement.OrderException ex) { return ActionResult.fail(ex.getMessage()); }
            catch (RuntimeException ex) { return ActionResult.fail("Неверные параметры: " + ex.getMessage()); }
        }));
    }

    private static void registerActions() {
        act("city_crown", "Доля короны", List.of("процент 0-60"), "Какую часть налогов города отдают в казну. Высокая подать злит горожан", c -> {
            TAX.setShare(c.realmId, Integer.parseInt(c.args.get(0).trim()));
            return "Доля короны: " + TAX.share(c.realmId) + "%";
        });
        act("city_tax", "Налог во всех городах", List.of("-1 по усмотрению, 0 нет, 1 низкий, 2 обычный, 3 высокий"), "Единая налоговая политика для городов государства", c -> {
            TAX.setPolicy(c.realmId, Integer.parseInt(c.args.get(0).trim()));
            return "Политика налогов: " + TAX.policy(c.realmId);
        });
        act("city_grant", "Грант городу", List.of("id города", "золото"), "Казна платит городу золото (1 золото = 5 серебра)", c -> {
            Settlement s = null;
            for (Settlement x : cities()) if (x.id.equals(c.args.get(0).trim()) && c.realmId.equals(realmOf(x))) s = x;
            if (s == null) throw new Settlement.OrderException("Нет такого города в вашем государстве.");
            if (!TAX.grant(c.realmId, s, Double.parseDouble(c.args.get(1).trim().replace(',', '.')))) throw new Settlement.OrderException(TAX.lastError);
            return "Грант выдан городу " + s.name;
        });
    }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent e) {
        try { Path f = file(e.getServer()); if (Files.exists(f)) TAX.fromJson(Files.readString(f)); }
        catch (Exception ex) { SettleMod.LOGGER.error("wf_settle: налоги городов не прочитаны: {}", ex.toString()); }
    }

    private static void save(MinecraftServer s) {
        try { Files.writeString(file(s), TAX.toJson()); } catch (Exception ex) { SettleMod.LOGGER.error("wf_settle: налоги городов не сохранены: {}", ex.toString()); }
    }

    @SubscribeEvent public void onStopping(ServerStoppingEvent e) { save(e.getServer()); }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        ticks++;
        try {
            if (ticks % 100 == 0) {
                int days = WorldForgeApi.clock().pull("wf_settle_tax");
                if (days > 0) TAX.remit(cities(), TaxService::realmOf, (int) WorldForgeApi.clock().dayNumber());
            }
            if (ticks % 6000 == 0) save(e.getServer());
        } catch (RuntimeException ex) { SettleMod.LOGGER.error("wf_settle: ошибка сбора налогов: {}", ex.toString()); }
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("wftax").executes(c -> {
            ServerPlayer sp = c.getSource().getPlayer();
            if (sp == null) { c.getSource().sendFailure(Component.literal("Только для игрока")); return 0; }
            String r = realmOfPlayer(sp);
            if (r.isEmpty()) { c.getSource().sendFailure(Component.literal("Нет государства: поставьте поселение на земле государства.")); return 0; }
            WFNet.sendView(sp, VIEW, TAX.viewJson(r, cities(), TaxService::realmOf));
            return 1;
        }));
    }
}
