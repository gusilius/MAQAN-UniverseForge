package dev.worldforge.court.mc.client;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.client.ClientState;
import dev.worldforge.mod.client.ClientViews;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.court.CourtSim;
import dev.worldforge.people.NameBank;
import dev.worldforge.people.RoleDefs;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Map;

/** Клиентская сторона окна двора: срез двора, присланный сервером, собирается в CourtSim и показывается в CourtScreen. */
public final class CourtClient {
    private CourtClient() {}

    public static void register() { ClientViews.register("wf_court:court", CourtClient::open);
    }

    @SuppressWarnings("unchecked")
    private static void open(String payload) {
        try {
            if (ClientState.state == null || ClientState.state.titles == null) return;
            Map<String, Object> m = (Map<String, Object>) Json.parse(payload);
            dev.worldforge.mod.RealmProjectionCodec.apply(ClientState.state.titles, m.get("realm_projections"));
            RoleDefs defs = new RoleDefs();
            for (Object o : (List<Object>) m.get("roles")) defs.add((String) o);
            CharacterRegistry reg = new CharacterRegistry();
            reg.fromJson((String) m.get("reg"));
            CourtSim sim = new CourtSim(ClientState.state.titles, null, reg, defs, new NameBank(), 0L, ((Number) m.get("start")).intValue());
            for (Object o : (List<Object>) m.get("events")) sim.addEvent((String) o);
            sim.fromJson((String) m.get("sim"));
            String realm = (String) m.get("realm");
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof CourtScreen cs) cs.setModel(sim); else mc.setScreen(new CourtScreen(sim, realm, ClientViews::command));
        } catch (RuntimeException ex) {
            dev.worldforge.court.mc.CourtMod.LOGGER.error("wf_court: не удалось открыть окно двора: {}", ex.toString());
        }
    }
}
