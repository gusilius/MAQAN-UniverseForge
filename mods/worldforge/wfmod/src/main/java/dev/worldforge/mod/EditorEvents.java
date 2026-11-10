package dev.worldforge.mod;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.List;

/** Жезл картографа: выбор и закраска провинций, подсветка границ частицами, автосохранение. */
public final class EditorEvents {
    private static final int AUTOSAVE_TICKS = 1200;
    private static final double REACH = 160;

    // ------------------------------------------------------------------ вспомогательное

    private static boolean ready(ServerPlayer sp, EditorManager.Session s) {
        return s.on && EditorManager.canEdit(sp);
    }

    /** Точка, куда смотрит игрок (до REACH блоков), или null. */
    private static Vec3 target(ServerPlayer sp) {
        HitResult hit = sp.pick(REACH, 1.0F, false);
        return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : null;
    }

    private static boolean debounce(ServerPlayer sp, EditorManager.Session s, int ticks) {
        long now = sp.tickCount;
        if (now - s.lastAction < ticks && now >= s.lastAction) return true;
        s.lastAction = now;
        return false;
    }

    static void select(ServerPlayer sp, EditorManager.Session s, MapData m, double x, double z) {
        TitleData.Title p = m.provinceAt(x, z);
        if (p == null) {
            sp.displayClientMessage(Component.literal("Здесь нет провинции"), true);
            return;
        }
        s.province = p.id;
        sp.displayClientMessage(describe(m, p), true);
    }

    static Component describe(MapData m, TitleData.Title p) {
        Component c = Component.literal("Выбрано: ").append(Component.literal(p.name).withStyle(Style.EMPTY.withColor(p.rgb)));
        List<TitleData.Title> chain = m.titles.chain(p);
        for (int i = 1; i < chain.size(); i++) {
            TitleData.Title t = chain.get(i);
            c = c.copy().append(Component.literal(" < " + t.name).withStyle(Style.EMPTY.withColor(t.rgb)));
        }
        return c;
    }

    // ------------------------------------------------------------------ жезл

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof ServerPlayer sp)) return;
        if (!EditorManager.isWand(sp.getMainHandItem())) return;
        e.setCanceled(true);                                     // жезл не ломает блоки
        EditorManager.Session s = EditorManager.session(sp);
        if (!ready(sp, s) || debounce(sp, s, 3)) return;
        MapData m = EditorManager.dataFor(sp.serverLevel());
        if (m == null || m.titles == null) return;
        select(sp, s, m, e.getPos().getX() + 0.5, e.getPos().getZ() + 0.5);
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (e.getLevel().isClientSide() || e.getHand() != InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer sp)) return;
        if (!EditorManager.isWand(sp.getMainHandItem())) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        use(sp);
    }

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem e) {
        if (e.getLevel().isClientSide() || e.getHand() != InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer sp)) return;
        if (!EditorManager.isWand(sp.getMainHandItem())) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        use(sp);
    }

    private void use(ServerPlayer sp) {
        EditorManager.Session s = EditorManager.session(sp);
        if (!s.on) {
            sp.displayClientMessage(Component.literal("Режим редактора выключен: /worldforge editor on"), true);
            return;
        }
        if (!EditorManager.canEdit(sp)) {
            sp.displayClientMessage(Component.literal("Редактор доступен операторам в творческом режиме"), true);
            return;
        }
        if (debounce(sp, s, 2)) return;
        MapData m = EditorManager.dataFor(sp.serverLevel());
        TitleEditor ed = EditorManager.editorFor(m);
        if (ed == null) {
            sp.displayClientMessage(Component.literal("В этом измерении нет карты с титулами"), true);
            return;
        }
        Vec3 t = target(sp);
        if (t == null) {
            sp.displayClientMessage(Component.literal("Цель слишком далеко (до " + (int) REACH + " блоков)"), true);
            return;
        }
        if (sp.isShiftKeyDown()) {
            select(sp, s, m, t.x, t.z);
            return;
        }
        executeWandMutation(sp, s, m, ed, t.x, t.z);
    }

    /** Command path is already inside ActionExecutionService; returns false for a rejected edit. */
    static boolean applyTool(ServerPlayer sp, EditorManager.Session s, MapData m, TitleEditor ed, double bx, double bz) {
        dev.worldforge.mod.api.ActionResult result = applyToolAction(sp, s, m, ed, bx, bz);
        sp.displayClientMessage(Component.literal(result.message), true);
        return result.ok;
    }

    private static dev.worldforge.mod.api.ActionResult applyToolAction(
            ServerPlayer player, EditorManager.Session s, MapData m, TitleEditor ed, double bx, double bz) {
        try {
            switch (s.tool) {
                case "smooth" -> {
                    int n = ed.smoothAtBlock(bx, bz, s.brush);
                    if (n > 0) {
                        EditorManager.touch(m);
                        if (!persistEditorChanges(player, ed)) return dev.worldforge.mod.api.ActionResult.fail(
                                "Изменение границ не сохранено на диск; операция требует сверки");
                    }
                    return dev.worldforge.mod.api.ActionResult.ok(n == 0 ? "Границы здесь уже гладкие" : "Сглажено пикселей: " + n);
                }
                case "assign" -> {
                    TitleData.Title target = m.titles.get(s.assignTitle);
                    if (target == null) return dev.worldforge.mod.api.ActionResult.fail(
                            "Титул для назначения не выбран: /worldforge editor tool assign <уровень>");
                    TitleData.Title moved = ed.assignAtBlock(bx, bz, target.id);
                    EditorManager.touch(m);
                    if (!persistEditorChanges(player, ed)) return dev.worldforge.mod.api.ActionResult.fail(
                            "Перенос титула не сохранён на диск; операция требует сверки");
                    return dev.worldforge.mod.api.ActionResult.ok(moved.name + " → " + target.name);
                }
                default -> {
                    if (m.titles.get(s.province) == null) return dev.worldforge.mod.api.ActionResult.fail(
                            "Сначала выберите провинцию: Shift+ПКМ или ЛКМ по блоку");
                    int n = ed.paintAtBlock(bx, bz, s.brush, s.province);
                    if (n > 0) {
                        EditorManager.touch(m);
                        if (!persistEditorChanges(player, ed)) return dev.worldforge.mod.api.ActionResult.fail(
                                "Закраска не сохранена на диск; операция требует сверки");
                    }
                    return dev.worldforge.mod.api.ActionResult.ok(n == 0 ? "Здесь нечего менять"
                            : "Закрашено: " + n + " px → " + m.titles.get(s.province).name);
                }
            }
        } catch (TitleEditor.EditException ex) {
            return dev.worldforge.mod.api.ActionResult.fail(ex.getMessage());
        }
    }

    private static boolean persistEditorChanges(ServerPlayer player, TitleEditor editor) {
        EditorManager.saveDirty(player.server);
        return !editor.isDirty();
    }

    /** The wand is a direct player input path, so its world mutations must use the shared executor too. */
    private static void executeWandMutation(ServerPlayer player, EditorManager.Session session,
                                            MapData map, TitleEditor editor, double x, double z) {
        long minute = dev.worldforge.mod.api.WorldForgeApi.clock().worldMinute();
        String actor = player.getUUID().toString();
        String dimension = player.serverLevel().dimension().location().toString();
        String payload = dimension + "|" + session.tool + "|" + session.province + "|" + session.assignTitle
                + "|" + Double.toHexString(x) + "|" + Double.toHexString(z) + "|" + Double.toHexString(session.brush);
        String requestId = "editor-wand:" + java.util.UUID.nameUUIDFromBytes(
                (actor + ":" + player.server.getTickCount() + ":" + payload)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request = new dev.worldforge.mod.api.ActionExecutionService.Request(
                requestId, "worldforge.editor_wand", actor, dimension,
                java.util.List.of(session.tool, session.province, session.assignTitle, payload), minute);
        try {
            var task = dev.worldforge.mod.api.WorldForgeApi.actionExecution().execute(request,
                    req -> {
                        if (player.server.getPlayerList().getPlayer(player.getUUID()) == null)
                            return "Игрок отключился до применения инструмента";
                        if (!EditorManager.canEdit(player)) return "Полномочия редактора отозваны";
                        EditorManager.Session current = EditorManager.session(player);
                        if (current != session || !current.on) return "Сеанс редактора изменился или выключен";
                        MapData currentMap = EditorManager.dataFor(player.serverLevel());
                        if (currentMap != map || EditorManager.editorFor(currentMap) != editor
                                || !dimension.equals(player.serverLevel().dimension().location().toString()))
                            return "Карта или измерение изменились до применения инструмента";
                        return null;
                    },
                    (req, attempt) -> applyToolAction(player, session, map, editor, x, z),
                    null, minute, WorldForgeMod.actionExecutionFile(player.server));
            player.displayClientMessage(Component.literal(task.message()), true);
        } catch (RuntimeException ex) {
            player.displayClientMessage(Component.literal("Не удалось применить инструмент: " + ex.getMessage()), true);
        }
    }

    // ------------------------------------------------------------------ частицы границ

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.player instanceof ServerPlayer sp) || sp.tickCount % 20 != 0) return;
        EditorManager.Session s = EditorManager.peek(sp.getUUID());
        if (s == null || !ready(sp, s) || !EditorManager.isWand(sp.getMainHandItem())) return;
        ServerLevel level = sp.serverLevel();
        MapData m = EditorManager.dataFor(level);
        if (m == null || m.titles == null) return;
        TitleData d = m.titles;
        int tier = Math.max(0, Math.min(d.tiers.size() - 1, s.tier));
        int[] lut = d.provinceLut(tier);
        int cx = d.pixelX(sp.getX()), cz = d.pixelZ(sp.getZ());
        int r = (int) Math.ceil(72 / d.bpp) + 1, budget = 700;
        for (int pz = Math.max(0, cz - r); pz <= Math.min(d.h - 1, cz + r) && budget > 0; pz++) {
            for (int px = Math.max(0, cx - r); px <= Math.min(d.w - 1, cx + r) && budget > 0; px++) {
                int a = d.provinceIdAtPixel(px, pz);
                if (a == 0) continue;
                for (int side = 0; side < 2; side++) {
                    int b = side == 0 ? d.provinceIdAtPixel(px + 1, pz) : d.provinceIdAtPixel(px, pz + 1);
                    if (b == 0 || a == b) continue;
                    boolean sel = a == s.province || b == s.province;
                    boolean tierEdge = lut[a] != lut[b];
                    if (!sel && !tierEdge) continue;
                    // середина общей грани пикселей в блоках
                    double bx = (side == 0 ? px + 1 : px + 0.5) * d.bpp - d.w * d.bpp * 0.5;
                    double bz = (side == 0 ? pz + 0.5 : pz + 1) * d.bpp - d.h * d.bpp * 0.5;
                    double y = Math.max(m.yAt(bx, bz), m.seaLevel) + 2.0;
                    level.sendParticles(sp, sel ? ParticleTypes.FLAME : ParticleTypes.END_ROD, true, bx, y, bz, 1, 0, 0.2, 0, 0.0);
                    budget--;
                    if (side == 0 || sel) {
                        // вторая точка по грани, чтобы линия казалась сплошной
                        double bx2 = side == 0 ? bx : bx + d.bpp * 0.3, bz2 = side == 0 ? bz + d.bpp * 0.3 : bz;
                        level.sendParticles(sp, sel ? ParticleTypes.FLAME : ParticleTypes.END_ROD, true, bx2, y, bz2, 1, 0, 0.2, 0, 0.0);
                    }
                }
            }
        }
        Vec3 t = target(sp);
        if (t != null) {                                         // кольцо кисти
            double br = Math.max(1.0, s.brush);
            int pts = (int) Math.min(48, Math.max(16, br * 2));
            for (int i = 0; i < pts; i++) {
                double ang = Math.PI * 2 * i / pts, x = t.x + Math.cos(ang) * br, z = t.z + Math.sin(ang) * br;
                level.sendParticles(sp, ParticleTypes.SOUL_FIRE_FLAME, true, x, Math.max(m.yAt(x, z), m.seaLevel) + 1.5, z, 1, 0, 0, 0, 0.0);
            }
        }
    }

    // ------------------------------------------------------------------ сохранение

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && server.getTickCount() % AUTOSAVE_TICKS == 0) EditorManager.saveDirty(server);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent e) {
        EditorManager.saveDirty(e.getServer());
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        EditorManager.drop(e.getEntity().getUUID());
    }
}
