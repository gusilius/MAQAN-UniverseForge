package dev.maqan.platform.client;

import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Runtime capability discovery for the optional MAQAN shell.
 *
 * This registry intentionally contains identifiers and presentation metadata only.
 * The owning mod remains authoritative for screens, simulation and persistent data.
 */
public final class CapabilityRegistry {
    public record Capability(
            String id,
            String domain,
            String label,
            String icon,
            String providerModId,
            String screenClass,
            int order
    ) {}

    private static final List<Capability> DECLARED = List.of(
            new Capability("maqan.atlas.cosmos", "COSMOS", "Cosmos", "◎",
                    "maqan_galactic_navigator",
                    "com.maqan.galacticnavigator.client.NavigatorScreen", 10),
            new Capability("maqan.atlas.worlds", "WORLDS", "Worlds", "◇",
                    "maqan_galactic_navigator",
                    "com.maqan.galacticnavigator.client.NavigatorScreen", 20),
            new Capability("worldforge.map", "POLITICS", "World map", "⌖",
                    "worldforge", "dev.worldforge.mod.client.MapScreen", 25),

            new Capability("worldforge.realms.politics", "POLITICS", "Politics", "♜",
                    "wf_realms", "dev.worldforge.mod.client.MapScreen", 30),
            new Capability("worldforge.settlements", "SETTLEMENTS", "Settlements", "⌂",
                    "wf_settle", "dev.worldforge.settle.mc.client.SettleScreen", 40),
            new Capability("worldforge.people", "SOCIETY", "Society", "♙",
                    "wf_people", "dev.worldforge.people.mc.client.CharacterScreen", 50),
            new Capability("worldforge.trade", "ECONOMY", "Economy", "◈",
                    "wf_trade", "dev.worldforge.trade.mc.client.TradeScreen", 60),
            new Capability("worldforge.army", "ARMY", "Army", "⚔",
                    "wf_army", "dev.worldforge.army.mc.client.ArmyScreen", 70),
            new Capability("worldforge.troops", "ARMY", "Troops", "▤",
                    "wf_troops", "dev.worldforge.troops.mc.client.CommandScreen", 71),
            new Capability("worldforge.war", "ARMY", "War", "✥",
                    "wf_war", "dev.worldforge.war.mc.client.WarScreen", 72),
            new Capability("worldforge.rts", "TACTICAL", "RTS", "⌖",
                    "wf_rts", "dev.worldforge.rts.mc.client.RtsHud", 80),
            new Capability("worldforge.court", "POLITICS", "Court", "♛",
                    "wf_court", "dev.worldforge.court.mc.client.CourtScreen", 31),
            new Capability("worldforge.relations", "POLITICS", "Relations", "⇄",
                    "wf_relations", "dev.worldforge.relations.mc.client.RelationsScreen", 32),
            new Capability("worldforge.orders", "ARMY", "Orders", "➤",
                    "wf_orders", "dev.worldforge.orders.mc.client.OrdersScreen", 73),
            new Capability("worldforge.quests", "SOCIETY", "Quests", "▧",
                    "wf_quests", "dev.worldforge.quests.mc.client.QuestScreen", 51),
            new Capability("worldforge.dialogue", "SOCIETY", "Dialogue", "◌",
                    "wf_dialogue", "dev.worldforge.dialogue.mc.client.DialogueScreen", 52),
            new Capability("worldforge.reputation", "SOCIETY", "Reputation", "✧",
                    "wf_reputation", "", 53),
            new Capability("worldforge.origins", "SOCIETY", "Origins", "◉",
                    "wf_origins", "dev.worldforge.origins.mc.client.OriginsScreen", 54),
            new Capability("worldforge.genesis", "CREATION", "Genesis", "✳",
                    "wf_genesis", "dev.worldforge.genesis.mc.client.GenesisScreen", 90),
            new Capability("worldforge.progress", "ECONOMY", "Progress", "↗",
                    "wf_progress", "dev.worldforge.progress.mc.client.ProgressScreen", 61),
            new Capability("worldforge.forms", "SOCIETY", "Forms", "▦",
                    "wf_forms", "dev.worldforge.forms.mc.client.FormsScreen", 55)
    );

    private CapabilityRegistry() {}

    /** Full interface catalogue; entries remain visible when their provider is absent. */
    public static List<Capability> all() {
        return DECLARED.stream()
                .sorted(Comparator.comparingInt(Capability::order)
                        .thenComparing(Capability::label))
                .toList();
    }

    /** Returns only capabilities whose provider mod is installed and enabled. */
    public static List<Capability> available() {
        ModList mods = ModList.get();
        List<Capability> result = new ArrayList<>();
        for (Capability capability : DECLARED) {
            // A capability without a real embedded screen is not exposed as a clickable route.
            // Command-only providers remain usable through their own commands until a synced UI exists.
            if (mods.isLoaded(capability.providerModId()) && !capability.screenClass().isBlank()) result.add(capability);
        }
        result.sort(Comparator.comparingInt(Capability::order)
                .thenComparing(Capability::label));
        return List.copyOf(result);
    }

    public static List<Capability> inDomain(String domain) {
        return available().stream()
                .filter(capability -> capability.domain().equals(domain))
                .toList();
    }

    public static boolean isAvailable(String capabilityId) {
        return available().stream().anyMatch(capability -> capability.id().equals(capabilityId));
    }

    /** Resolve the most specific registered view for a provider screen. */
    public static Capability forProvider(String providerModId, String screenClass) {
        return DECLARED.stream()
                .filter(c -> c.providerModId().equals(providerModId))
                .filter(c -> c.screenClass().equals(screenClass))
                .findFirst()
                .orElseGet(() -> DECLARED.stream()
                        .filter(c -> c.providerModId().equals(providerModId))
                        .findFirst().orElse(null));
    }
}
