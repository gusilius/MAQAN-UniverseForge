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

/** Client-local persistent waypoint store. No server or world data is modified. */
public final class NavigatorWaypointStore {
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE=new TypeToken<List<NavigatorWaypoint>>(){}.getType();
    private static final ReentrantReadWriteLock LOCK=new ReentrantReadWriteLock();
    private static final List<NavigatorWaypoint> WAYPOINTS=new ArrayList<>();
    private static boolean loaded;
    private NavigatorWaypointStore() {}

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("maqan").resolve("waypoints.json");
    }

    public static void load() {
        LOCK.writeLock().lock();
        try {
            if(loaded)return;
            WAYPOINTS.clear();
            Path path=file();
            if(Files.isRegularFile(path)) {
                try(Reader reader=Files.newBufferedReader(path)) {
                    List<NavigatorWaypoint> values=GSON.fromJson(reader,LIST_TYPE);
                    if(values!=null) for(NavigatorWaypoint value:values) {
                        if(value==null)continue;
                        try { value.validate(); WAYPOINTS.add(value); }
                        catch(RuntimeException ignored) { /* Skip corrupt entries, preserve the rest. */ }
                    }
                } catch(Exception ignored) {
                    // Keep the store usable; a malformed file is not allowed to crash the client.
                }
            }
            loaded=true;
        } finally { LOCK.writeLock().unlock(); }
    }

    public static List<NavigatorWaypoint> all() {
        load(); LOCK.readLock().lock();
        try { return WAYPOINTS.stream().map(NavigatorWaypointStore::copy).toList(); }
        finally { LOCK.readLock().unlock(); }
    }

    public static void add(NavigatorWaypoint waypoint) {
        Objects.requireNonNull(waypoint).validate(); load();
        LOCK.writeLock().lock();
        try { WAYPOINTS.add(copy(waypoint)); saveLocked(); }
        finally { LOCK.writeLock().unlock(); }
    }

    public static void update(NavigatorWaypoint waypoint) {
        Objects.requireNonNull(waypoint).validate(); load();
        LOCK.writeLock().lock();
        try {
            for(int i=0;i<WAYPOINTS.size();i++) if(WAYPOINTS.get(i).id.equals(waypoint.id)) {
                WAYPOINTS.set(i,copy(waypoint)); saveLocked(); return;
            }
        } finally { LOCK.writeLock().unlock(); }
    }

    public static void remove(String id) {
        load(); LOCK.writeLock().lock();
        try { WAYPOINTS.removeIf(w->Objects.equals(w.id,id)); saveLocked(); }
        finally { LOCK.writeLock().unlock(); }
    }

    public static void clearTemporary() {
        load(); LOCK.writeLock().lock();
        try { WAYPOINTS.removeIf(w->w.temporary); saveLocked(); }
        finally { LOCK.writeLock().unlock(); }
    }

    public static void save() {
        load(); LOCK.writeLock().lock();
        try { saveLocked(); } finally { LOCK.writeLock().unlock(); }
    }

    private static void saveLocked() {
        Path target=file(), temp=target.resolveSibling(target.getFileName()+".tmp");
        try {
            Files.createDirectories(target.getParent());
            try(Writer writer=Files.newBufferedWriter(temp)) { GSON.toJson(WAYPOINTS,LIST_TYPE,writer); }
            try { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException ignored) {
                Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);
            }
        } catch(Exception exception) {
            // Keep in-memory changes; report a useful error without crashing the game.
            System.err.println("[MAQAN] Could not save waypoints: "+exception.getMessage());
        }
    }

    private static NavigatorWaypoint copy(NavigatorWaypoint source) {
        NavigatorWaypoint w=new NavigatorWaypoint();
        w.id=source.id; w.name=source.name; w.dimensionId=source.dimensionId; w.systemId=source.systemId;
        w.frame=source.frame; w.scope=source.scope; w.category=source.category;
        w.group=source.group; w.icon=source.icon; w.color=source.color;
        w.x=source.x; w.y=source.y; w.z=source.z; w.visible=source.visible;
        w.temporary=source.temporary; w.createdAt=source.createdAt; w.note=source.note;
        return w;
    }
}
