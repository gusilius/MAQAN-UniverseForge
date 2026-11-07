package dev.worldforge.dialogue.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.util.HashMap;

/** Склейка wf-dialogue с Minecraft: канал, загрузка диалогов из датапаков, события, команда /wfdialogue. */
@Mod("wf_dialogue")
public class DialogueMod {
    private static final int SAVE_EVERY = 6000;
    private static long ticks;

    public DialogueMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener((FMLCommonSetupEvent e) -> e.enqueueWork(DialogueNet::init));
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onReload(AddReloadListenerEvent e) {
        e.addListener((ResourceManagerReloadListener) DialogueRegistry::load);
    }

    @SubscribeEvent
    public void onAboutToStart(ServerAboutToStartEvent e) {
        DialogueMc.clearSessions();
    }

    @SubscribeEvent
    public void onStarting(ServerStartingEvent e) {
        DialogueRegistry.load(e.getServer().getResourceManager());
        DialogueMc.loadFlags(e.getServer());
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent e) {
        DialogueMc.saveFlags(e.getServer());
        DialogueMc.clearSessions();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        ticks++;
        if (ticks % 20 == 0) DialogueMc.checkDistances(e.getServer());
        if (ticks % SAVE_EVERY == 0) DialogueMc.saveFlags(e.getServer());
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        DialogueMc.drop(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) DialogueMc.close(sp);
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) DialogueMc.close(sp);
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        d.register(Commands.literal("wfdialogue").requires(s -> s.hasPermission(2))
                .then(Commands.literal("open")
                        .then(Commands.argument("id", StringArgumentType.greedyString())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(DialogueRegistry.ids(), b))
                                .executes(c -> open(c.getSource(), StringArgumentType.getString(c, "id").trim())))));
    }

    private static int open(CommandSourceStack s, String id) {
        try {
            ServerPlayer p = s.getPlayerOrException();
            if (DialogueMc.open(p, id, new HashMap<>(java.util.Map.of("npc", "Незнакомец")))) return 1;
            s.sendFailure(Component.literal("Не удалось открыть диалог '" + id + "' (нет такого или он не стартовал)"));
        } catch (Exception ex) {
            s.sendFailure(Component.literal("Команду может выполнить только игрок"));
        }
        return 0;
    }
}
