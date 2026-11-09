package dev.maqan.platform.client;

import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime capability catalogue for optional MAQAN modules.
 * Built-in declarations remain available as discoverable metadata; installed
 * providers can register and unregister capabilities without editing Platform.
 */
public final class CapabilityRegistry {
    public record Capability(
            String id, String domain, String label, String icon,
            String providerModId, String screenClass, int order
    ) {
        public Capability {
            id = required(id, "id");
            domain = required(domain, "domain");
            label = required(label, "label");
            icon = icon == null ? "" : icon;
            providerModId = required(providerModId, "providerModId");
            screenClass = screenClass == null ? "" : screenClass;
        }
    }

    private static final Map<String, Capability> DECLARED = new ConcurrentHashMap<>();
    private static final Map<String, Capability> REGISTERED = new ConcurrentHashMap<>();

    static {
        declare(new Capability("maqan.atlas.cosmos", "COSMOS", "Cosmos", "◎",
                "maqan_galactic_navigator", "com.maqan.galacticnavigator.client.NavigatorScreen", 10));
        declare(new Capability("maqan.atlas.worlds", "WORLDS", "Worlds", "◇",
                "maqan_galactic_navigator", "com.maqan.galacticnavigator.client.NavigatorScreen", 20));
        declare(new Capability("worldforge.map", "POLITICS", "World map", "⌖",
                "worldforge", "dev.worldforge.mod.client.MapScreen", 25));
        declare(new Capability("worldforge.realms.politics", "POLITICS", "Politics", "♜",
                "wf_realms", "dev.worldforge.mod.client.MapScreen", 30));
        declare(new Capability("worldforge.settlements", "SETTLEMENTS", "Settlements", "⌂",
                "wf_settle", "dev.worldforge.settle.mc.client.SettleScreen", 40));
        declare(new Capability("worldforge.people", "SOCIETY", "Society", "♙",
                "wf_people", "dev.worldforge.people.mc.client.CharacterScreen", 50));
        declare(new Capability("worldforge.trade", "ECONOMY", "Economy", "◈",
                "wf_trade", "dev.worldforge.trade.mc.client.TradeScreen", 60));
        declare(new Capability("worldforge.army", "ARMY", "Army", "⚔",
                "wf_army", "dev.worldforge.army.mc.client.ArmyScreen", 70));
        declare(new Capability("worldforge.troops", "ARMY", "Troops", "▤",
                "wf_troops", "dev.worldforge.troops.mc.client.CommandScreen", 71));
        declare(new Capability("worldforge.war", "ARMY", "War", "✥",
                "wf_war", "dev.worldforge.war.mc.client.WarScreen", 72));
        declare(new Capability("worldforge.rts", "TACTICAL", "RTS", "⌖",
                "wf_rts", "dev.worldforge.rts.mc.client.RtsHud", 80));
        declare(new Capability("worldforge.court", "POLITICS", "Court", "♛",
                "wf_court", "dev.worldforge.court.mc.client.CourtScreen", 31));
        declare(new Capability("worldforge.relations", "POLITICS", "Relations", "⇄",
                "wf_relations", "dev.worldforge.relations.mc.client.RelationsScreen", 32));
        declare(new Capability("worldforge.orders", "ARMY", "Orders", "➤",
                "wf_orders", "dev.worldforge.orders.mc.client.OrdersScreen", 73));
        declare(new Capability("worldforge.quests", "SOCIETY", "Quests", "▧",
                "wf_quests", "dev.worldforge.quests.mc.client.QuestScreen", 51));
        declare(new Capability("worldforge.dialogue", "SOCIETY", "Dialogue", "◌",
                "wf_dialogue", "dev.worldforge.dialogue.mc.client.DialogueScreen", 52));
        declare(new Capability("worldforge.reputation", "SOCIETY", "Reputation", "✧",
                "wf_reputation", "", 53));
        declare(new Capability("worldforge.origins", "SOCIETY", "Origins", "◉",
                "wf_origins", "dev.worldforge.origins.mc.client.OriginsScreen", 54));
        declare(new Capability("worldforge.genesis", "CREATION", "Genesis", "✳",
                "wf_genesis", "dev.worldforge.genesis.mc.client.GenesisScreen", 90));
        declare(new Capability("worldforge.progress", "ECONOMY", "Progress", "↗",
                "wf_progress", "dev.worldforge.progress.mc.client.ProgressScreen", 61));
        declare(new Capability("worldforge.forms", "SOCIETY", "Forms", "▦",
                "wf_forms", "dev.worldforge.forms.mc.client.FormsScreen", 55));
    }

    private CapabilityRegistry() {}

    private static void declare(Capability capability) {
        DECLARED.put(capability.id(), capability);
    }

    /** Register a runtime capability supplied by an installed feature module. */
    public static void register(Capability capability) {
        if (capability == null) throw new IllegalArgumentException("Capability is required");
        Capability declared = DECLARED.get(capability.id());
        if (declared != null && !declared.providerModId().equals(capability.providerModId()))
            throw new IllegalArgumentException("Capability id belongs to another provider: " + capability.id());
        Capability previous = REGISTERED.putIfAbsent(capability.id(), capability);
        if (previous != null && !previous.equals(capability))
            throw new IllegalArgumentException("Capability is already registered: " + capability.id());
    }

    public static void unregister(String capabilityId) {
        REGISTERED.remove(capabilityId);
    }

    public static void unregisterProvider(String providerModId) {
        REGISTERED.entrySet().removeIf(entry ->
                entry.getValue().providerModId().equals(providerModId));
        WorkspaceRuntime.unregisterProvider(providerModId);
    }

    /** All built-in and runtime capabilities, including entries whose providers are absent. */
    public static List<Capability> all() {
        Map<String, Capability> merged = new ConcurrentHashMap<>(DECLARED);
        merged.putAll(REGISTERED);
        return merged.values().stream().sorted(order()).toList();
    }

    /** Capabilities whose provider is installed or explicitly registered at runtime. */
    public static List<Capability> available() {
        ModList mods = ModList.get();
        List<Capability> result = new ArrayList<>();
        for (Capability capability : all()) {
            if (REGISTERED.containsKey(capability.id()) || mods.isLoaded(capability.providerModId()))
                result.add(capability);
        }
        result.sort(order());
        return List.copyOf(result);
    }

    public static List<Capability> inDomain(String domain) {
        return available().stream().filter(capability -> capability.domain().equals(domain)).toList();
    }

    public static boolean isAvailable(String capabilityId) {
        return available().stream().anyMatch(capability -> capability.id().equals(capabilityId));
    }

    public static Capability forProvider(String providerModId, String screenClass) {
        return all().stream()
                .filter(capability -> capability.providerModId().equals(providerModId))
                .filter(capability -> capability.screenClass().equals(screenClass))
                .findFirst()
                .orElseGet(() -> all().stream()
                        .filter(capability -> capability.providerModId().equals(providerModId))
                        .findFirst().orElse(null));
    }

    private static Comparator<Capability> order() {
        return Comparator.comparingInt(Capability::order).thenComparing(Capability::label);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
