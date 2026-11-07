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
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Client-local territory geometry and political metadata. */
public final class NavigatorTerritoryStore {
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE=new TypeToken<List<NavigatorTerritory>>(){}.getType();
    private static final ReentrantReadWriteLock LOCK=new ReentrantReadWriteLock();
    private static final List<NavigatorTerritory> TERRITORIES=new ArrayList<>();
    private static boolean loaded;
    private NavigatorTerritoryStore() {}

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("maqan").resolve("territories.json");
    }

    public static void load() {
        LOCK.writeLock().lock();
        try {
            if(loaded)return;
            TERRITORIES.clear();
            Path path=file();
            if(Files.isRegularFile(path)) {
                try(Reader reader=Files.newBufferedReader(path)) {
                    List<NavigatorTerritory> values=GSON.fromJson(reader,LIST_TYPE);
                    if(values!=null)for(NavigatorTerritory value:values) {
                        if(value==null)continue;
                        try { value.validate(); TERRITORIES.add(copy(value)); }
                        catch(RuntimeException ignored) { /* Skip malformed polygon, keep valid territories. */ }
                    }
                } catch(Exception ignored) { /* Store remains available after malformed user data. */ }
            }
            loaded=true;
        } finally { LOCK.writeLock().unlock(); }
    }

    public static List<NavigatorTerritory> all() {
        load(); LOCK.readLock().lock();
        try { return TERRITORIES.stream().map(NavigatorTerritoryStore::copy).toList(); }
        finally { LOCK.readLock().unlock(); }
    }

    public static void add(NavigatorTerritory territory) {
        Objects.requireNonNull(territory).validate(); load();
        LOCK.writeLock().lock();
        try { TERRITORIES.add(copy(territory)); saveLocked(); }
        finally { LOCK.writeLock().unlock(); }
    }

    public static void update(NavigatorTerritory territory) {
        Objects.requireNonNull(territory).validate(); load();
        LOCK.writeLock().lock();
        try {
            for(int i=0;i<TERRITORIES.size();i++)if(Objects.equals(TERRITORIES.get(i).id,territory.id)) {
                TERRITORIES.set(i,copy(territory)); saveLocked(); return;
            }
        } finally { LOCK.writeLock().unlock(); }
    }

    public static void remove(String id) {
        load(); LOCK.writeLock().lock();
        try { TERRITORIES.removeIf(t->Objects.equals(t.id,id)); saveLocked(); }
        finally { LOCK.writeLock().unlock(); }
    }

    private static void saveLocked() {
        Path target=file(),temp=target.resolveSibling(target.getFileName()+".tmp");
        try {
            Files.createDirectories(target.getParent());
            try(Writer writer=Files.newBufferedWriter(temp)) { GSON.toJson(TERRITORIES,LIST_TYPE,writer); }
            try { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException ignored) { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING); }
        } catch(Exception exception) {
            System.err.println("[MAQAN] Could not save territories: "+exception.getMessage());
        }
    }

    private static NavigatorTerritory copy(NavigatorTerritory source) {
        NavigatorTerritory t=new NavigatorTerritory();
        t.id=source.id;t.name=source.name;t.owner=source.owner;t.dimensionId=source.dimensionId;
        t.systemId=source.systemId;t.frame=source.frame;t.kind=source.kind;t.group=source.group;
        t.color=source.color;t.fillAlpha=source.fillAlpha;t.visible=source.visible;t.locked=source.locked;
        t.createdAt=source.createdAt;t.vertices=new ArrayList<>();
        for(var v:source.vertices)t.vertices.add(new NavigatorTerritory.Vertex(v.x,v.y,v.z));
        return t;
    }
}
