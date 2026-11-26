package com.maqan.galacticnavigator.navigation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player fog-of-war store. The only writer is the client event that records
 * the chunk occupied by the player; this class never loads or queries chunks.
 */
public final class NavigatorExplorationStore {
    /** One visited chunk with a 16x16 top-surface raster. Arrays are row-major (z * 16 + x). */
    public record ExploredChunk(int chunkX,int chunkZ,int surfaceY,String biome,
                                int mapColor,long firstVisited,long lastVisited,
                                int[] terrainColors,int[] terrainHeights) {
        public int colorAt(int index){
            return terrainColors!=null&&terrainColors.length==256&&terrainColors[index]!=0
                    ?terrainColors[index]:mapColor;
        }
        public int heightAt(int index){
            return terrainHeights!=null&&terrainHeights.length==256?terrainHeights[index]:surfaceY;
        }
    }
    public record ExploredLayer(int chunkX,int chunkZ,int sectionY,int playerY,String biome,
                                int mapColor,long firstVisited,long lastVisited) {}
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE=new TypeToken<Map<String,ExploredChunk>>(){}.getType();
    private static final Map<String,Map<String,ExploredChunk>> CACHE=new ConcurrentHashMap<>();
    private static final Type LAYER_MAP_TYPE=new TypeToken<Map<String,ExploredLayer>>(){}.getType();
    private static final Map<String,Map<String,ExploredLayer>> LAYER_CACHE=new ConcurrentHashMap<>();
    private NavigatorExplorationStore() {}

    private static String safe(String value) {
        return value.replace(':','_').replace('/','_').replace('\\','_');
    }
    private static Path file(String dimension) {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("maqan")
                .resolve("exploration").resolve(safe(dimension)+".json");
    }
    private static String layerFileName(String dimension){return safe(dimension)+"_layers.json";}
    private static Path layerFile(String dimension){
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("maqan")
                .resolve("exploration").resolve(layerFileName(dimension));
    }
    private static String key(int x,int z){return x+","+z;}
    private static String layerKey(int x,int z,int sectionY){return x+","+z+","+sectionY;}

    private static Map<String,ExploredChunk> data(String dimension) {
        return CACHE.computeIfAbsent(dimension,id->{
            Map<String,ExploredChunk> loaded=new HashMap<>();
            Path path=file(id);
            if(Files.isRegularFile(path)) try(Reader reader=Files.newBufferedReader(path)) {
                Map<String,ExploredChunk> parsed=GSON.fromJson(reader,MAP_TYPE);
                if(parsed!=null) parsed.forEach((k,v)->{if(v!=null)loaded.put(k,v);});
            } catch(Exception exception) {
                System.err.println("[MAQAN] Could not read exploration map "+id+": "+exception.getMessage());
            }
            return loaded;
        });
    }

    /** Record only a chunk the player is physically standing in. */
    public static synchronized void recordPlayerChunk(String dimension,int chunkX,int chunkZ,
                                                       int surfaceY,String biome,int mapColor,
                                                       int[] terrainColors,int[] terrainHeights) {
        if(dimension==null||dimension.isBlank())return;
        Map<String,ExploredChunk> chunks=data(dimension);
        String key=key(chunkX,chunkZ);
        long now=System.currentTimeMillis();
        ExploredChunk previous=chunks.get(key);
        int[] safeColors=terrainColors!=null&&terrainColors.length==256
                ?terrainColors.clone():null;
        int[] safeHeights=terrainHeights!=null&&terrainHeights.length==256
                ?terrainHeights.clone():null;
        chunks.put(key,new ExploredChunk(chunkX,chunkZ,surfaceY,
                biome==null?"unknown":biome,mapColor,
                previous==null?now:previous.firstVisited(),now,safeColors,safeHeights));
        save(dimension,chunks);
    }

    private static Map<String,ExploredLayer> layerData(String dimension){
        return LAYER_CACHE.computeIfAbsent(dimension,id->{
            Map<String,ExploredLayer> loaded=new HashMap<>();
            Path path=layerFile(id);
            if(Files.isRegularFile(path))try(Reader reader=Files.newBufferedReader(path)){
                Map<String,ExploredLayer> parsed=GSON.fromJson(reader,LAYER_MAP_TYPE);
                if(parsed!=null)parsed.forEach((k,v)->{if(v!=null)loaded.put(k,v);});
            }catch(Exception exception){
                System.err.println("[MAQAN] Could not read exploration layers "+id+": "+exception.getMessage());
            }
            return loaded;
        });
    }

    /** Records a vertical section only when the player physically enters it. */
    public static synchronized void recordPlayerLayer(String dimension,int chunkX,int chunkZ,int sectionY,
                                                       int playerY,String biome,int mapColor){
        if(dimension==null||dimension.isBlank())return;
        Map<String,ExploredLayer> layers=layerData(dimension);
        String key=layerKey(chunkX,chunkZ,sectionY);
        long now=System.currentTimeMillis();
        ExploredLayer previous=layers.get(key);
        layers.put(key,new ExploredLayer(chunkX,chunkZ,sectionY,playerY,
                biome==null?"unknown":biome,mapColor,
                previous==null?now:previous.firstVisited(),now));
        Path target=layerFile(dimension),temp=target.resolveSibling(target.getFileName()+".tmp");
        try{
            Files.createDirectories(target.getParent());
            try(Writer writer=Files.newBufferedWriter(temp)){GSON.toJson(layers,LAYER_MAP_TYPE,writer);}
            try{Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException ignored){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
        }catch(Exception exception){
            System.err.println("[MAQAN] Could not save exploration layer "+dimension+": "+exception.getMessage());
        }
    }

    public static synchronized List<ExploredLayer> layers(String dimension){
        if(dimension==null||dimension.isBlank())return List.of();
        return layerData(dimension).values().stream()
                .sorted(Comparator.comparingInt(ExploredLayer::sectionY)
                        .thenComparingInt(ExploredLayer::chunkX).thenComparingInt(ExploredLayer::chunkZ))
                .toList();
    }

    public static synchronized List<ExploredChunk> chunks(String dimension) {
        if(dimension==null||dimension.isBlank())return List.of();
        return data(dimension).values().stream()
                .sorted(Comparator.comparingInt(ExploredChunk::chunkX).thenComparingInt(ExploredChunk::chunkZ))
                .toList();
    }
    public static synchronized boolean explored(String dimension,int chunkX,int chunkZ) {
        return data(dimension).containsKey(key(chunkX,chunkZ));
    }
    public static synchronized int exploredCount(String dimension) {
        return data(dimension).size();
    }
    public static synchronized void flush() {
        CACHE.forEach(NavigatorExplorationStore::save);
    }
    private static void save(String dimension,Map<String,ExploredChunk> chunks) {
        Path target=file(dimension),temp=target.resolveSibling(target.getFileName()+".tmp");
        try {
            Files.createDirectories(target.getParent());
            try(Writer writer=Files.newBufferedWriter(temp)){GSON.toJson(chunks,MAP_TYPE,writer);}
            try{Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException ignored){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
        }catch(Exception exception){
            System.err.println("[MAQAN] Could not save exploration map "+dimension+": "+exception.getMessage());
        }
    }
}
