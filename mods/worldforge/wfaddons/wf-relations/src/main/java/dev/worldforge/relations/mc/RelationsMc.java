package dev.worldforge.relations.mc;

import dev.worldforge.mod.EditorManager;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.WFPerm;
import dev.worldforge.mod.api.ActionContext;
import dev.worldforge.mod.api.ActionResult;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.court.CourtSim;
import dev.worldforge.court.mc.CourtMod;
import dev.worldforge.people.mc.PeopleMod;
import dev.worldforge.relations.Relations;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Связь отношений с игрой: инвентарь и золото игрока, действия карты, сплетни двора, сохранение. Чистая логика - в {@link Relations}. */
public final class RelationsMc {
    private RelationsMc() {}

    public static final Relations REL = new Relations();
    private static final String GOLD = "minecraft:gold_nugget";

    private static ServerPlayer player(String id) {
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s == null) return null;
        try { return s.getPlayerList().getPlayer(UUID.fromString(id)); } catch (IllegalArgumentException e) { return null; }
    }

    private static MapData map(ServerPlayer p) { return p == null ? null : EditorManager.dataFor(p.serverLevel()); }

    private static Item item(String id) {
        ResourceLocation rl = id.contains(":") ? new ResourceLocation(id) : new ResourceLocation("minecraft", id);
        return BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }

    public static int dayNumber() { return (int) WorldForgeApi.clock().dayNumber(); }

    /** Ценность предмета как подарка (1-10): сокровища дороги, еда и цветы дешёвы. */
    static int giftValue(ItemStack st) {
        if (st.isEmpty()) return 0;
        String id = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
        if (id.contains("netherite") || id.equals("nether_star") || id.equals("enchanted_golden_apple")) return 10;
        if (id.contains("diamond") || id.equals("emerald_block") || id.equals("totem_of_undying")) return 9;
        if (id.contains("emerald") || id.equals("gold_block")) return 7;
        if (id.contains("gold") || id.contains("amethyst") || id.equals("lapis_lazuli") || id.equals("book") || id.equals("enchanted_book")) return 5;
        if (id.contains("iron") || id.equals("cake") || id.equals("golden_apple") || id.contains("dye")) return 4;
        if (id.contains("flower") || id.equals("poppy") || id.equals("dandelion") || id.equals("rose_bush") || id.equals("peony") || id.equals("lilac") || id.equals("sweet_berries")) return 3;
        if (st.getItem().isEdible()) return 2;
        return 1;
    }

    public static final Relations.Hooks HOOKS = new Relations.Hooks() {
        @Override public int count(String pl, String it) {
            ServerPlayer p = player(pl); Item i = item(it);
            if (p == null || i == null) return 0;
            int n = 0;
            for (ItemStack s : p.getInventory().items) if (s.is(i)) n += s.getCount();
            return n;
        }
        @Override public boolean take(String pl, String it, int n) {
            ServerPlayer p = player(pl); Item i = item(it);
            if (p == null || i == null || count(pl, it) < n) return false;
            for (ItemStack s : p.getInventory().items) {
                if (n <= 0) break;
                if (s.is(i)) { int d = Math.min(n, s.getCount()); s.shrink(d); n -= d; }
            }
            return true;
        }
        @Override public void give(String pl, String it, int n) {
            ServerPlayer p = player(pl);
            Item i = it.equals("gold") ? Items.GOLD_NUGGET : item(it);
            if (p == null || i == null) return;
            ItemStack st = new ItemStack(i, n);
            if (!p.getInventory().add(st)) p.drop(st, false);
        }
        @Override public String realmOf(String pl) {
            ServerPlayer p = player(pl);
            MapData m = map(p);
            if (m == null || m.titles == null) return "";
            CourtSim cs = CourtMod.court(m);
            if (cs == null) return "";
            Character c = cs.playing(p.getGameProfile().getName());
            return c == null ? "" : cs.realmOf(c);
        }
        @Override public String run(String pl, String action, String realm, List<String> args) {
            ServerPlayer p = player(pl);
            MapData m = map(p);
            if (m == null) { REL.lastError = "карта недоступна"; return null; }
            ActionContext ctx = new ActionContext(EditorManager.editorFor(m), p.getGameProfile().getName(), WFPerm.trusted(p.createCommandSourceStack()), realm, 0, args);
            ActionResult r = WorldForgeApi.run(action, ctx);
            if (!r.ok) { REL.lastError = r.message; return null; }
            EditorManager.touch(m);
            return r.message;
        }
        @Override public List<String> rumors() {
            List<String> out = new ArrayList<>();
            for (MapData m : dev.worldforge.mod.WorldForgeMod.loadedMaps().values()) {
                CourtSim cs = m.titles == null ? null : CourtMod.court(m);
                if (cs == null) continue;
                int from = Math.max(0, cs.news.size() - 12);
                out.addAll(cs.news.subList(from, cs.news.size()));
            }
            return out;
        }
        @Override public int takeGift(String pl) {
            ServerPlayer p = player(pl);
            if (p == null) return 0;
            ItemStack st = p.getMainHandItem();
            int v = giftValue(st);
            if (v > 0) st.shrink(1);
            return v;
        }
        @Override public List<String> neighbors(String realm) {
            ServerPlayer any = null;
            List<String> out = new ArrayList<>();
            for (MapData m : dev.worldforge.mod.WorldForgeMod.loadedMaps().values()) {
                if (m.titles == null) continue;
                TitleData.Realm r = m.titles.realms.get(realm);
                if (r == null) continue;
                if (r.overlordId != null && !r.overlordId.isEmpty()) out.add(r.overlordId);
                for (String s : r.subjectIds) out.add(s);
                for (TitleData.Realm o : m.titles.realmList()) {
                    if (out.size() >= 3) break;
                    if (!o.id.equals(realm) && !out.contains(o.id) && o.areaBlocks > 0) out.add(o.id);
                }
                break;
            }
            return out.size() > 3 ? out.subList(0, 3) : out;
        }
    };

    private static Path file(MinecraftServer s) { return s.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("wf_relations.json"); }

    public static void load(MinecraftServer s) {
        REL.hooks = HOOKS;
        try { if (Files.exists(file(s))) REL.fromJson(Files.readString(file(s))); }
        catch (Exception e) { RelationsMod.LOGGER.error("wf_relations: wf_relations.json: {}", e.toString()); }
    }

    public static void save(MinecraftServer s) {
        try { Files.writeString(file(s), REL.toJson()); }
        catch (Exception e) { RelationsMod.LOGGER.error("wf_relations: не удалось сохранить отношения: {}", e.toString()); }
    }
}
