package dev.worldforge.war.mc.client;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.TitleEditor;
import dev.worldforge.mod.client.ClientState;
import dev.worldforge.mod.client.ClientViews;
import dev.worldforge.war.WarEngine;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Map;

/** Клиентская сторона окна войн: собрать движки поверх клиентской копии карты и открыть либо обновить WarScreen. */
public final class WarClient {
    private WarClient() {}

    public static void register() { ClientViews.register("wf_war:main", WarClient::open); }

    @SuppressWarnings("unchecked")
    private static void open(String payload) {
        try {
            if (ClientState.state == null || ClientState.state.titles == null) return;
            Map<String, Object> m = (Map<String, Object>) Json.parse(payload);
            dev.worldforge.mod.RealmProjectionCodec.apply(ClientState.state.titles, m.get("realm_projections"));
            WarEngine e = new WarEngine(new TitleEditor(ClientState.state.titles));
            for (Object o : (List<Object>) m.get("raw")) e.loadCasusBelli((String) o);
            e.fromJson((String) m.get("war"));
            String realm = (String) m.get("realm");
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof WarScreen ws) ws.setModel(e);
            else mc.setScreen(new WarScreen(e, realm.isEmpty() ? null : realm, ClientViews::command));
        } catch (RuntimeException ex) {
            dev.worldforge.war.mc.WarMod.LOGGER.error("wf_war: не удалось открыть окно: {}", ex.toString());
        }
    }
}
