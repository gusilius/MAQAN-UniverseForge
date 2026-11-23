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
 * Character receipts plus the durable world baseline make catch-up resumable across partial saves and restarts.
 */
public final class PopulationComputeCoordinator {
    private static final Logger LOGGER = WorldSimMod.LOGGER;
    private static final String OPERATION_KEY = "worldsim.population.advance";
    private static final byte[] EMPTY_SECONDARY = "{}".getBytes(StandardCharsets.UTF_8);
    private static Pending pending;
    private static MinecraftServer activeServer;
    private static long lifecycleEpoch;
    private static boolean nativeDisabled;
    private static long lastScannedWorldDay = Long.MIN_VALUE;
    private static long lastScannedRegistryGeneration = Long.MIN_VALUE;
    private static final int CAPTURE_MEMBERS_PER_TICK = 2_048;
    private static PopulationComputeBridge.Scan activeScan;
    private static int scanRegistryCursor;
    private static int scanMemberCursor;
    private static long scanTargetWorldDay = Long.MIN_VALUE;
    private static long scanRegistryGeneration = Long.MIN_VALUE;
    private static int scanYear;

    private record Pending(PopulationComputeBridge.Captured captured,
                           WorldForgeComputeRuntime.Ticket ticket,long registryGeneration) {}
    private PopulationComputeCoordinator() {}

    /** Called reflectively by wf-people so that wf-worldsim remains an optional addon. */
    public static synchronized boolean tick(MinecraftServer server,long currentWorldDay,int year) {
        if(server==null||currentWorldDay<0) return false;
        if(activeServer!=server){
            activeServer=server;lifecycleEpoch++;
            lastScannedWorldDay=Long.MIN_VALUE;
            lastScannedRegistryGeneration=Long.MIN_VALUE;
            activeScan=null;scanRegistryCursor=0;scanMemberCursor=0;
            scanTargetWorldDay=Long.MIN_VALUE;scanRegistryGeneration=Long.MIN_VALUE;
        }
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

        long registryGeneration=PeopleMod.populationRegistryGeneration();
        List<CharacterRegistry> currentRegistries=registries();
        if(activeScan!=null&&!activeScan.matches(currentRegistries)){
            // Only a wholesale registry restore/repartition invalidates the scan. Appends are
            // handled as tail ranges without rebuilding or sorting the existing population.
            activeScan=null;scanRegistryCursor=0;scanMemberCursor=0;
        }

        if(completed!=null){
            Pending done=pending;
            pending=null;
            if(done!=null&&done.ticket.lifecycleEpoch()==lifecycleEpoch
                    &&done.registryGeneration()==registryGeneration
                    &&done.ticket.matches(done.captured.worldId(),OPERATION_KEY,done.captured.targetWorldDay(),
                    lifecycleEpoch,PopulationComputeBridge.SCHEMA_VERSION,NativeWorldSimulation.NATIVE_ABI_VERSION)
                    &&completed.ticket().matches(done.captured.worldId(),OPERATION_KEY,done.captured.targetWorldDay(),
                    lifecycleEpoch,PopulationComputeBridge.SCHEMA_VERSION,NativeWorldSimulation.NATIVE_ABI_VERSION)){
                try {
                    boolean applied=PopulationComputeBridge.apply(done.captured,completed.first(),currentRegistries);
                    if(applied){
                        scanRegistryCursor=done.captured.nextRegistryCursor();
                        scanMemberCursor=done.captured.nextMemberCursor();
                        if(done.captured.scanComplete()){
                            PopulationComputeBridge.Scan appended=done.captured.scan().appendedTail();
                            if(appended!=null){
                                activeScan=appended;scanRegistryCursor=0;
                                scanMemberCursor=appended.registryCount()==0?0:appended.start(0);
                            }else{
                                PeopleMod.markPopulationProcessed(done.captured.targetWorldDay());
                                lastScannedWorldDay=done.captured.targetWorldDay();
                                lastScannedRegistryGeneration=done.registryGeneration();
                                activeScan=null;scanRegistryCursor=0;scanMemberCursor=0;
                            }
                        }
                    } else {
                        LOGGER.debug("wf_worldsim: population slice became stale; retrying from the same append-only cursor");
                    }
                } catch(RuntimeException rejected) {
                    LOGGER.error("wf_worldsim: population result rejected; retaining unacknowledged world days for retry",rejected);
                }
            } else {
                LOGGER.warn("wf_worldsim: discarded stale population ticket (world lifecycle or Character registry generation changed)");
            }
        }
        if(pending!=null) return true;
        if(runtime.busy()) return true;

        if(activeScan==null){
            if(currentWorldDay==lastScannedWorldDay
                    &&registryGeneration==lastScannedRegistryGeneration) return true;
            activeScan=PopulationComputeBridge.beginScan(currentRegistries);
            scanRegistryCursor=0;scanMemberCursor=activeScan.registryCount()==0?0:activeScan.start(0);
            scanTargetWorldDay=currentWorldDay;scanRegistryGeneration=registryGeneration;scanYear=year;
        }
        long fallbackStartDay=PeopleMod.populationBaselineDay();
        String worldId=WorldForgeMod.worldId()==null
                ?server.overworld().dimension().location().toString()
                :WorldForgeMod.worldId().toString();
        PopulationComputeBridge.Captured captured;
        try {
            captured=PopulationComputeBridge.captureForWorldDay(worldId,scanYear,scanTargetWorldDay,
                    fallbackStartDay,currentRegistries,activeScan,scanRegistryCursor,scanMemberCursor,
                    CAPTURE_MEMBERS_PER_TICK);
        } catch(IllegalStateException busyOrChanging) {
            return true;
        }
        if(!captured.hasWork()){
            scanRegistryCursor=captured.nextRegistryCursor();
            scanMemberCursor=captured.nextMemberCursor();
            if(captured.scanComplete()){
                PopulationComputeBridge.Scan appended=activeScan.appendedTail();
                if(appended!=null){
                    activeScan=appended;scanRegistryCursor=0;
                    scanMemberCursor=appended.registryCount()==0?0:appended.start(0);
                }else{
                    PeopleMod.markPopulationProcessed(scanTargetWorldDay);
                    lastScannedWorldDay=scanTargetWorldDay;
                    lastScannedRegistryGeneration=registryGeneration;
                    activeScan=null;scanRegistryCursor=0;scanMemberCursor=0;
                }
            }
            return true;
        }
        WorldForgeComputeRuntime.Ticket ticket=new WorldForgeComputeRuntime.Ticket(
                worldId,OPERATION_KEY,scanTargetWorldDay,lifecycleEpoch,
                PopulationComputeBridge.SCHEMA_VERSION,NativeWorldSimulation.NATIVE_ABI_VERSION);
        boolean submitted=runtime.submit(ticket,captured.request(),EMPTY_SECONDARY,
                (primary,secondary)->new WorldForgeComputeRuntime.Output(
                        NativeWorldSimulation.advancePopulation(primary),EMPTY_SECONDARY));
        if(submitted) pending=new Pending(captured,ticket,registryGeneration);
        // If another domain owns the single worker, keep the clock cursor unchanged and retry later.
        return true;
    }

    private static List<CharacterRegistry> registries(){
        return PeopleMod.populationRegistriesSnapshot();
    }
}
