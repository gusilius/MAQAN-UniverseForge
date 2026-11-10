package dev.worldforge.relations.mc;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import dev.worldforge.mod.WFNet;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.people.Character;
import dev.worldforge.people.mc.PeopleMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Аддон поверх wf_people: личные отношения игроков с жителями (подарки, свидания, браки, найм, просьбы, слухи).
 * Состояние в мире (wf_relations.json), ежедневный тик по игровым часам, команда /wfrel и окно (клавиша B).
 * Диалоги подключаются мягко: условия и эффекты регистрируются, только если есть wf_dialogue.
 */
@Mod("wf_relations")
public class RelationsMod {
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final int SAVE_EVERY = 6000, STEP = 100;
    private static long ticks;

    public RelationsMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener((FMLCommonSetupEvent e) -> e.enqueueWork(() -> {
            RelationsMc.REL.hooks = RelationsMc.HOOKS;
            if (ModList.get().isLoaded("wf_dialogue")) { DialogueLink.register(); LOGGER.info("wf_relations: мост к wf_dialogue подключён"); }
        }));
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onAboutToStart(ServerAboutToStartEvent e) { ticks = 0; RelationsMc.load(e.getServer()); }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent e) { RelationsMc.save(e.getServer()); }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        ticks++;
        try {
            if (ticks % STEP == 0 && WorldForgeApi.clock().pull("wf_relations") > 0) RelationsMc.REL.tick(RelationsMc.dayNumber());
            if (ticks % SAVE_EVERY == 0) RelationsMc.save(e.getServer());
        } catch (Exception ex) { LOGGER.error("wf_relations: ошибка тика: {}", ex.toString()); }
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("wfrel")
                .executes(c -> open(c.getSource()))
                .then(Commands.literal("dismiss").then(Commands.argument("id", StringArgumentType.word()).executes(c -> dismiss(c.getSource(), StringArgumentType.getString(c, "id"))))));
    }

    private static int open(CommandSourceStack s) {
        ServerPlayer sp = s.getPlayer();
        if (sp == null) { s.sendFailure(Component.literal("Только для игрока")); return 0; }
        String json = RelationsMc.REL.viewJson(sp.getUUID().toString(), PeopleMod::findNpc, PeopleMod::roleName, RelationsMc.dayNumber());
        WFNet.sendView(sp, "wf_relations:relations", json);
        return 1;
    }

    private static int dismiss(CommandSourceStack source, String id) {
        ServerPlayer player = source.getPlayer();
        Character npc = PeopleMod.findNpc(id);
        if (player == null || npc == null) return 0;
        String actor = player.getUUID().toString();
        long minute = WorldForgeApi.clock().worldMinute();
        String requestId = "relations:dismiss:" + java.util.UUID.nameUUIDFromBytes(
                (actor + ":" + source.getServer().getTickCount() + ":" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            var task = WorldForgeApi.actionExecution().execute(
                    new dev.worldforge.mod.api.ActionExecutionService.Request(
                            requestId, "relations.dismiss_companion", actor, id, java.util.List.of(id), minute),
                    req -> source.getServer().getPlayerList().getPlayer(player.getUUID()) == null
                            ? "Игрок отключился до изменения отношений" : null,
                    (req, attempt) -> {
                        if (!RelationsMc.REL.bond(actor, npc.id).employed)
                            return dev.worldforge.mod.api.ActionResult.fail("Этот житель не состоит у вас на службе.");
                        RelationsMc.REL.dismiss(actor, npc);
                        return dev.worldforge.mod.api.ActionResult.ok(npc.name + " освобождён от службы.");
                    }, null, minute, dev.worldforge.mod.WorldForgeMod.actionExecutionFile(source.getServer()));
            if (task.status() != dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED) {
                source.sendFailure(Component.literal(task.message()));
                return 0;
            }
            source.sendSuccess(() -> Component.literal(task.message()), false);
            return open(source);
        } catch (RuntimeException ex) {
            source.sendFailure(Component.literal("Не удалось освободить жителя: " + ex.getMessage()));
            return 0;
        }
    }
}
