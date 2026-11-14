package dev.worldforge.worldsim.mc;

import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.mod.api.WorldForgeComputeRuntime;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.PopulationComputeBridge;
import dev.worldforge.people.mc.PeopleMod;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Population pipeline on the existing process-wide compute slot.
 * Character-level world-day receipts make catch-up resumable across partial registry saves.
 */
public final class PopulationComputeCoordinator {
    private static final Logger LOGGER = WorldSimMod.LOGGER;
    private static final String OPERATION_KEY = "worldsim.population.advance";
    private static final byte[] EMPTY_SECONDARY = "{}".getBytes(StandardCharsets.UTF_8);
    private static Pending pending;
    private static MinecraftServer activeServer;
    private static long lifecycleEpoch;
    private static boolean nativeDisabled;

    private record Pending(PopulationComputeBridge.Captured captured,
                           WorldForgeComputeRuntime.Ticket ticket) {}
    private PopulationComputeCoordinator() {}

    /** Called reflectively by wf-people so that wf-worldsim remains an optional addon. */
    public static synchronized boolean tick(MinecraftServer server,long currentWorldDay,int year) {
        if(server==null||currentWorldDay<0) return false;
        if(activeServer!=server){activeServer=server;lifecycleEpoch++;}
        if(nativeDisabled||!NativeWorldSimulation.load()) return false;

        WorldForgeComputeRuntime runtime=WorldForgeApi.computeRuntime();
        WorldForgeComputeRuntime.Result completed;
        try {
            completed=runtime.poll(OPERATION_KEY);
        } catch(RuntimeException failure) {
            pending=null;
            nativeDisabled=true;
            LOGGER.error("wf_worldsim: population native compute failed before apply; exclusive Java fallback will resume from per-Character receipts",failure);
            return false;
        }

        if(completed!=null){
            Pending done=pending;
            pending=null;
            if(done!=null&&done.ticket.lifecycleEpoch()==lifecycleEpoch
                    &&done.ticket.matches(done.captured.worldId(),OPERATION_KEY,done.captured.targetWorldDay(),
                    lifecycleEpoch,PopulationComputeBridge.SCHEMA_VERSION,NativeWorldSimulation.NATIVE_ABI_VERSION)
                    &&completed.ticket().matches(done.captured.worldId(),OPERATION_KEY,done.captured.targetWorldDay(),
                    lifecycleEpoch,PopulationComputeBridge.SCHEMA_VERSION,NativeWorldSimulation.NATIVE_ABI_VERSION)){
                try {
                    List<CharacterRegistry> registries=registries();
                    boolean applied=PopulationComputeBridge.apply(done.captured,completed.first(),registries);
                    if(applied) PeopleMod.markPopulationProcessed(done.captured.targetWorldDay());
                } catch(RuntimeException rejected) {
                    // The bridge validates the entire frame before the first canonical write.
                    // Keep Rust ownership and retry from per-Character receipts; never double-run Java here.
                    LOGGER.error("wf_worldsim: population result rejected; retaining unacknowledged world days for retry",rejected);
                }
            } else {
                LOGGER.warn("wf_worldsim: discarded stale population ticket from an earlier world lifecycle");
            }
        }
        if(pending!=null) return true;
        if(runtime.busy()) return true;

        long fallbackStartDay=PeopleMod.populationProcessedDay();
        // The process cursor is not durable proof that every Character partition was saved.
        // Always inspect per-Character receipts, including when the cursor equals the world day.
        String worldId=WorldForgeMod.worldId()==null
                ?server.overworld().dimension().location().toString()
                :WorldForgeMod.worldId().toString();
        PopulationComputeBridge.Captured captured;
        try {
            captured=PopulationComputeBridge.captureForWorldDay(worldId,year,currentWorldDay,fallbackStartDay,registries());
        } catch(IllegalStateException busyOrChanging) {
            // A canonical writer is active or the snapshot raced a mutation. Retry next server tick.
            return true;
        }
        if(!captured.hasWork()){
            PeopleMod.markPopulationProcessed(currentWorldDay);
            return true;
        }
        WorldForgeComputeRuntime.Ticket ticket=new WorldForgeComputeRuntime.Ticket(
                worldId,OPERATION_KEY,currentWorldDay,lifecycleEpoch,
                PopulationComputeBridge.SCHEMA_VERSION,NativeWorldSimulation.NATIVE_ABI_VERSION);
        boolean submitted=runtime.submit(ticket,captured.request(),EMPTY_SECONDARY,
                (primary,secondary)->new WorldForgeComputeRuntime.Output(
                        NativeWorldSimulation.advancePopulation(primary),EMPTY_SECONDARY));
        if(submitted) pending=new Pending(captured,ticket);
        // If another domain owns the single worker, keep the clock cursor unchanged and retry later.
        return true;
    }

    private static List<CharacterRegistry> registries(){
        List<CharacterRegistry> result=new ArrayList<>(PeopleMod.registries().values());
        result.add(PeopleMod.playerRegistry());
        return result;
    }
}
