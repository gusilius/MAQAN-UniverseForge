package dev.worldforge.mod;

import net.minecraft.server.MinecraftServer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;

/** Optional add-on bridge for live strategic map objects. Providers publish snapshots only. */
public final class MapMarkerProviders {
    @FunctionalInterface
    public interface Provider {
        List<Markers.Marker> collect(MinecraftServer server, MapData map);
    }
    private static final ConcurrentSkipListMap<String, Provider> PROVIDERS = new ConcurrentSkipListMap<>();
    private MapMarkerProviders() {}
    public static void register(String id, Provider provider) {
        if (id == null || id.isBlank() || provider == null)
            throw new IllegalArgumentException("A marker provider id and implementation are required");
        PROVIDERS.put(id, provider);
    }
    public static void unregister(String id) {
        if (id != null) PROVIDERS.remove(id);
    }
    public static List<Markers.Marker> collect(MinecraftServer server, MapData map) {
        if (server == null || map == null) return List.of();
        List<Markers.Marker> result = new ArrayList<>();
        for (var entry : PROVIDERS.entrySet()) {
            try {
                List<Markers.Marker> provided = entry.getValue().collect(server, map);
                if (provided == null) continue;
                for (Markers.Marker marker : provided) {
                    if (marker != null && marker.id != null && marker.id.startsWith(entry.getKey() + ":")
                            && marker.name != null && !marker.name.isBlank()
                            && Markers.TYPES.contains(marker.type)) result.add(marker);
                }
            } catch (RuntimeException failure) {
                WorldForgeMod.LOGGER.warn("Map marker provider '{}' failed for map '{}': {}",
                        entry.getKey(), map.name, failure.toString());
            }
        }
        return List.copyOf(result);
    }
}
