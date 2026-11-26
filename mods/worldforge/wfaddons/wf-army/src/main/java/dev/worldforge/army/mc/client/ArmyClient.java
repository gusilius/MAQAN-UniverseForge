package dev.worldforge.army.mc.client;

import dev.worldforge.army.ArmyEngine;
import dev.worldforge.mod.Json;
import dev.worldforge.mod.TitleEditor;
import dev.worldforge.mod.client.ClientState;
import dev.worldforge.mod.client.ClientViews;
import dev.worldforge.war.WarEngine;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Map;

/** Клиентская сторона окна армий: собрать движки поверх клиентской копии карты и открыть либо обновить ArmyScreen. */
public final class ArmyClient {
    private ArmyClient() {}

    public static void register() { ClientViews.register("wf_army:main", ArmyClient::open); }

    @SuppressWarnings("unchecked")
    private static void open(String payload) {
        try {
            if (ClientState.state == null || ClientState.state.titles == null) return;
            Map<String, Object> m = (Map<String, Object>) Json.parse(payload);
            WarEngine e = new WarEngine(new TitleEditor(ClientState.state.titles));
            ArmyEngine a = new ArmyEngine(e);
            for (Object o : (List<Object>) m.get("raw")) a.loadUnits((String) o);
            e.fromJson((String) m.get("war"));
            a.fromJson((String) m.get("army"));
            String realm = (String) m.get("realm");
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof ArmyScreen s) s.setModel(e, a);
            else mc.setScreen(new ArmyScreen(e, a, realm.isEmpty() ? null : realm, ClientViews::command));
        } catch (RuntimeException ex) {
            dev.worldforge.army.mc.ArmyMod.LOGGER.error("wf_army: не удалось открыть окно: {}", ex.toString());
        }
    }
}
