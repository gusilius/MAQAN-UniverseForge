package dev.worldforge.settle.mc;

import dev.worldforge.settle.Building;
import dev.worldforge.settle.Defs;
import dev.worldforge.settle.Settlement;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Жезл поселения (нужен оператор): ПКМ по блоку - заложить выбранное здание в этой точке, Shift+ПКМ в воздухе - выбрать следующее,
 * ЛКМ по зданию - отменить стройку или снести. Выбор хранится в NBT предмета (def).
 */
public class SettleWand extends Item {
    public SettleWand(Properties props) { super(props); }

    private static List<String> ids() {
        Defs d = SettleMod.DEFS;
        return d == null ? new ArrayList<>() : new ArrayList<>(d.buildings.keySet());
    }

    static String selected(ItemStack st) {
        List<String> ids = ids();
        if (ids.isEmpty()) return "";
        String cur = st.hasTag() ? st.getTag().getString("def") : "";
        return ids.contains(cur) ? cur : ids.get(0);
    }

    private static void say(Player p, String text, boolean error) {
        p.displayClientMessage(error ? Component.literal(text).withStyle(ChatFormatting.RED) : Component.literal(text), true);
    }

    private static boolean op(Player p) { return p instanceof ServerPlayer sp && dev.worldforge.mod.WFPerm.trusted(sp); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack st = player.getItemInHand(hand);
        if (!level.isClientSide && SettleMod.DEFS != null) {
            if (!op(player)) {
                say(player, "Нужны права оператора", true);
            } else {
                List<String> ids = ids();
                if (ids.isEmpty()) return InteractionResultHolder.sidedSuccess(st, false);
                String cur = selected(st);
                if (player.isShiftKeyDown()) {
                    cur = ids.get((ids.indexOf(cur) + 1) % ids.size());
                    st.getOrCreateTag().putString("def", cur);
                }
                Defs.Building d = SettleMod.DEFS.buildings.get(cur);
                say(player, "Здание: " + d.name + " (" + cur + ", " + d.sizeX + "x" + d.sizeZ + ")", false);
            }
        }
        return InteractionResultHolder.sidedSuccess(st, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        Player pl = ctx.getPlayer();
        if (!(level instanceof ServerLevel sl) || pl == null || SettleMod.DEFS == null) return InteractionResult.sidedSuccess(level.isClientSide);
        if (!op(pl)) {
            say(pl, "Нужны права оператора", true);
            return InteractionResult.FAIL;
        }
        BlockPos pos = ctx.getClickedPos();
        Colony c = SettleMod.nearest(sl, pos.getX(), pos.getZ());
        if (c == null) {
            say(pl, "Нет поселения: /wfsettle found <имя>", true);
            return InteractionResult.FAIL;
        }
        ServerPlayer sp = (ServerPlayer) pl;
        String def = selected(ctx.getItemInHand());
        String payload = def + "|" + pos.getX() + "|" + pos.getZ();
        var task = executeWandAction(sp, c, "settlement.wand_place", payload,
                (req, attempt) -> {
                    try {
                        Building building = c.s.place(def, pos.getX() - c.cx, pos.getZ() - c.cz);
                        if (!SettleMod.persistActionMutation(sp.server, c))
                            return dev.worldforge.mod.api.ActionResult.fail("Здание создано в памяти, но снимок поселения не сохранён; дальнейшие изменения заблокированы до сверки");
                        return dev.worldforge.mod.api.ActionResult.ok("Заложено: " + building.def.name + " #" + building.uid);
                    } catch (Settlement.OrderException ex) {
                        return dev.worldforge.mod.api.ActionResult.fail(ex.getMessage());
                    }
                });
        say(pl, task.message(), task.status() != dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED);
        return task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED
                ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }

    private static dev.worldforge.mod.api.ActionExecutionService.Task executeWandAction(
            ServerPlayer player, Colony colony, String actionId, String payload,
            dev.worldforge.mod.api.ActionExecutionService.Executor executor) {
        long minute = dev.worldforge.mod.api.WorldForgeApi.clock().worldMinute();
        String actor = player.getUUID().toString();
        String dimension = player.serverLevel().dimension().location().toString();
        String requestId = "settle-wand:" + java.util.UUID.nameUUIDFromBytes(
                (actor + ":" + player.server.getTickCount() + ":" + dimension + ":" + actionId + ":" + payload)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request = new dev.worldforge.mod.api.ActionExecutionService.Request(
                requestId, actionId, actor, colony.s.id, List.of(payload), minute);
        return dev.worldforge.mod.api.WorldForgeApi.actionExecution().execute(request,
                req -> {
                    if (player.server.getPlayerList().getPlayer(player.getUUID()) == null)
                        return "Игрок отключился до исполнения приказа";
                    if (!dev.worldforge.mod.WFPerm.trusted(player)) return "Нужны права оператора";
                    Colony current = SettleMod.colony(req.targetId());
                    if (current != colony || !dimension.equals(current.dim))
                        return "Поселение или измерение изменились до исполнения приказа";
                    if (current.actionPersistenceBlocked || current.genesisProvisioningRuntimeBlocked)
                        return "Изменения поселения заблокированы после ошибки сохранения; требуется сверка";
                    return null;
                }, executor, null, minute, dev.worldforge.mod.WorldForgeMod.actionExecutionFile(player.server));
    }

    /** Жезл не ломает блоки в творческом режиме (ЛКМ - это приказ). */
    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) { return false; }

    /** ЛКМ жезлом по зданию отменяет его. */
    public static final class Events {
        @SubscribeEvent
        public void leftClick(PlayerInteractEvent.LeftClickBlock e) {
            if (!(e.getItemStack().getItem() instanceof SettleWand)) return;
            e.setCanceled(true);
            if (!(e.getLevel() instanceof ServerLevel sl) || SettleMod.DEFS == null) return;
            if (e.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) return;
            Player pl = e.getEntity();
            if (!op(pl)) return;
            BlockPos pos = e.getPos();
            Colony c = SettleMod.nearest(sl, pos.getX(), pos.getZ());
            Building b = c == null ? null : c.buildingAt(pos.getX(), pos.getZ());
            if (b == null) {
                say(pl, "Здесь нет здания", true);
                return;
            }
            ServerPlayer sp = (ServerPlayer) pl;
            String payload = Integer.toString(b.uid);
            var task = executeWandAction(sp, c, "settlement.wand_cancel", payload,
                    (req, attempt) -> {
                        try {
                            String name = b.def.name;
                            c.s.cancel(b.uid);
                            if (!SettleMod.persistActionMutation(sp.server, c))
                                return dev.worldforge.mod.api.ActionResult.fail("Изменение поселения не сохранено; дальнейшие изменения заблокированы до сверки");
                            return dev.worldforge.mod.api.ActionResult.ok("Отменено: " + name);
                        } catch (Settlement.OrderException ex) {
                            return dev.worldforge.mod.api.ActionResult.fail(ex.getMessage());
                        }
                    });
            say(pl, task.message(), task.status() != dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED);
        }
    }
}
