package dev.worldforge.mod;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Состояние режима редактора: сессии игроков, редакторы карт, сохранение правок в папку мира.
 * Правки хранятся в <мир>/worldforge/<карта>/ (titles.json, realms.json, provinces.png) и накладываются поверх пакета при загрузке.
 */
public final class EditorManager {
    /** Immutable in-memory snapshot of dirty title/marker overlays awaiting a durable batch commit. */
    public static final class DirtySnapshot {
        private final Map<Path, byte[]> files;
        private final Set<MapData> maps;

        private DirtySnapshot(Map<Path, byte[]> files, Set<MapData> maps) {
            Map<Path, byte[]> copy = new java.util.LinkedHashMap<>();
            files.forEach((path, bytes) -> copy.put(path, bytes.clone()));
            this.files = Collections.unmodifiableMap(copy);
            Set<MapData> mapCopy = Collections.newSetFromMap(new IdentityHashMap<>());
            mapCopy.addAll(maps);
            this.maps = Collections.unmodifiableSet(mapCopy);
        }

        public Map<Path, byte[]> files() {
            Map<Path, byte[]> copy = new java.util.LinkedHashMap<>();
            files.forEach((path, bytes) -> copy.put(path, bytes.clone()));
            return copy;
        }
    }

    public static final class Session {
        public boolean on;
        public int province;            // выбранная провинция (0 = нет)
        public int tier = 3;            // уровень, чьи границы показываются частицами
        public double brush = 12;       // радиус кисти в блоках
        public long lastAction;         // тик последнего действия (защита от двойных срабатываний)
        public long lastParticles;
        public String tool = "brush";  // brush, smooth, assign
        public int assignTitle;         // титул для инструмента assign
    }

    static final String WAND_TAG = "wf_wand";
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<MapData, TitleEditor> EDITORS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Set<MapData> STALE = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    /** Maps with an uncommitted editor mutation are frozen until server restart/reload. */
    private static final Set<MapData> BLOCKED = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private static final Map<MapData, Integer> VERSIONS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<MapData, Markers> MARKERS = Collections.synchronizedMap(new IdentityHashMap<>());

    private EditorManager() {}

    /** Номер версии титулов карты: растёт после каждой правки (по нему обновляются веб-карта и клиенты). */
    public static int version(MapData m) {
        return m == null ? 0 : VERSIONS.getOrDefault(m, 1);
    }

    public static Markers markers(MinecraftServer server, MapData m) {
        if (m == null) return new Markers();
        return MARKERS.computeIfAbsent(m, k -> {
            try {
                return Markers.load(overlayDir(server, k).resolve("markers.json"));
            } catch (IOException | RuntimeException e) {
                WorldForgeMod.LOGGER.error("WorldForge: не удалось прочитать метки карты {}: {}", k.name, e.toString());
                return new Markers();
            }
        });
    }

    public static Session session(ServerPlayer p) {
        return SESSIONS.computeIfAbsent(p.getUUID(), k -> new Session());
    }

    public static Session peek(UUID id) {
        return SESSIONS.get(id);
    }

    public static void drop(UUID id) {
        SESSIONS.remove(id);
    }

    /** Правки доступны операторам в творческом режиме. */
    public static boolean canEdit(ServerPlayer p) {
        return p.isCreative() && p.createCommandSourceStack().hasPermission(2);
    }

    public static MapData dataFor(ServerLevel level) {
        if (level.getChunkSource().getGenerator() instanceof MapChunkGenerator g) {
            try {
                return g.data();
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    public static TitleEditor editorFor(MapData m) {
        if (m == null || m.titles == null) return null;
        return EDITORS.computeIfAbsent(m, k -> new TitleEditor(k.titles));
    }

    static String safeName(MapData m) {
        return m.name.replace(':', '_').replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    public static Path overlayDir(MinecraftServer server, MapData m) {
        return server.getWorldPath(LevelResource.ROOT).resolve("worldforge").resolve(safeName(m));
    }

    public static Path exportDir(MapData m) {
        return FMLPaths.CONFIGDIR.get().resolve(WorldForgeMod.MOD_ID).resolve("export").resolve(safeName(m));
    }

    public static void blockMutations(MapData map) {
        if (map != null) BLOCKED.add(map);
    }

    public static boolean mutationsBlocked(MapData map) {
        return map != null && BLOCKED.contains(map);
    }

    /** Помечает карты-предметы устаревшими после правки. */
    public static void touch(MapData m) {
        STALE.add(m);
        VERSIONS.merge(m, 2, (a, b) -> a + 1);
    }

    public static void refreshMaps(MapData m) {
        if (STALE.remove(m)) PoliticalMapRenderer.regenerateAll(m);
    }

    /**
     * Checked checkpoint variant for cross-domain political transactions. A blocked or still-dirty
     * title/marker partition means the political outcome is not committed.
     */
    /** Collects every dirty political overlay without changing dirty flags or disk state. */
    public static DirtySnapshot snapshotDirty(MinecraftServer server) throws IOException {
        if (server == null) throw new IOException("Нет сервера для политического checkpoint");
        Map<Path, byte[]> files = new java.util.LinkedHashMap<>();
        Set<MapData> maps = Collections.newSetFromMap(new IdentityHashMap<>());
        synchronized (MARKERS) {
            for (Map.Entry<MapData, Markers> entry : MARKERS.entrySet()) {
                if (!entry.getValue().isDirty()) continue;
                if (BLOCKED.contains(entry.getKey()))
                    throw new IOException("Политические метки карты заблокированы: " + entry.getKey().name);
                Path target = overlayDir(server, entry.getKey()).resolve("markers.json");
                files.put(target, entry.getValue().toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                maps.add(entry.getKey());
            }
        }
        synchronized (EDITORS) {
            for (Map.Entry<MapData, TitleEditor> entry : EDITORS.entrySet()) {
                if (!entry.getValue().isDirty()) continue;
                if (BLOCKED.contains(entry.getKey()))
                    throw new IOException("Политическая карта заблокирована: " + entry.getKey().name);
                files.putAll(TitleStore.snapshotFiles(entry.getKey(), overlayDir(server, entry.getKey())));
                maps.add(entry.getKey());
            }
        }
        return new DirtySnapshot(files, maps);
    }

    /** Acknowledges dirty flags only after the complete multi-file batch has committed. */
    public static void acknowledgeDirtySnapshot(DirtySnapshot snapshot) {
        if (snapshot == null) return;
        synchronized (MARKERS) {
            for (MapData map : snapshot.maps) {
                Markers markers = MARKERS.get(map);
                if (markers != null) markers.clearDirty();
            }
        }
        synchronized (EDITORS) {
            for (MapData map : snapshot.maps) {
                TitleEditor titleEditor = EDITORS.get(map);
                if (titleEditor != null && titleEditor.isDirty()) {
                    titleEditor.clearDirty();
                    refreshMaps(map);
                }
            }
        }
    }

    /** Freezes all affected maps if a batch cannot be committed or recovered. */
    public static void blockDirtySnapshot(DirtySnapshot snapshot) {
        if (snapshot == null) return;
        BLOCKED.addAll(snapshot.maps);
    }

    public static boolean saveDirtyChecked(MinecraftServer server) {
        DirtySnapshot snapshot = null;
        try {
            snapshot = snapshotDirty(server);
            AtomicFileBatch.commit(server.getWorldPath(LevelResource.ROOT).resolve("worldforge"), snapshot.files());
            acknowledgeDirtySnapshot(snapshot);
            return true;
        } catch (Exception error) {
            blockDirtySnapshot(snapshot);
            WorldForgeMod.LOGGER.error("WorldForge: политический пакет титулов/меток не сохранён; карты заморожены: {}",
                    error.toString(), error);
            return false;
        }
    }

    /** Сохраняет все изменённые карты. Возвращает число сохранённых. */
    public static int saveDirty(MinecraftServer server) {
        int saved = 0;
        synchronized (MARKERS) {
            for (Map.Entry<MapData, Markers> e : MARKERS.entrySet()) {
                if (BLOCKED.contains(e.getKey()) || !e.getValue().isDirty()) continue;
                try {
                    e.getValue().save(overlayDir(server, e.getKey()).resolve("markers.json"));
                    saved++;
                } catch (IOException ex) {
                    WorldForgeMod.LOGGER.error("WorldForge: не удалось сохранить метки {}: {}", e.getKey().name, ex.toString());
                }
            }
        }
        synchronized (EDITORS) {
            for (Map.Entry<MapData, TitleEditor> e : EDITORS.entrySet()) {
                if (BLOCKED.contains(e.getKey()) || !e.getValue().isDirty()) continue;
                try {
                    TitleStore.save(e.getKey(), overlayDir(server, e.getKey()));
                    e.getValue().clearDirty();
                    refreshMaps(e.getKey());
                    saved++;
                } catch (IOException ex) {
                    WorldForgeMod.LOGGER.error("WorldForge: не удалось сохранить правки карты {}: {}", e.getKey().name, ex.toString());
                }
            }
        }
        return saved;
    }

    public static void forget() {
        MARKERS.clear();
        VERSIONS.clear();
        EDITORS.clear();
        STALE.clear();
        BLOCKED.clear();
    }

    /** Накладывает сохранённые правки при загрузке карты. */
    public static void applySaved(MinecraftServer server, MapData m) {
        if (server == null || m.titles == null) return;
        try {
            AtomicFileBatch.recover(server.getWorldPath(LevelResource.ROOT).resolve("worldforge"));
            if (TitleStore.applyOverlay(m, overlayDir(server, m))) {
                PoliticalMapRenderer.regenerateAll(m);
                WorldForgeMod.LOGGER.info("WorldForge: к карте '{}' применены сохранённые правки границ", m.name);
            }
        } catch (IOException | RuntimeException e) {
            BLOCKED.add(m);
            WorldForgeMod.LOGGER.error("WorldForge: не удалось восстановить/применить политический пакет карты '{}'; карту заморожено: {}",
                    m.name, e.toString(), e);
        }
    }

    // ------------------------------------------------------------------ жезл

    public static ItemStack makeWand() {
        ItemStack s = new ItemStack(Items.BLAZE_ROD);
        s.getOrCreateTag().putBoolean(WAND_TAG, true);
        s.setHoverName(Component.literal("Жезл картографа").withStyle(ChatFormatting.GOLD).withStyle(st -> st.withItalic(false)));
        ListTag lore = new ListTag();
        String[] lines = {"ПКМ: закрасить выбранной провинцией", "Shift+ПКМ или ЛКМ по блоку: выбрать провинцию", "/worldforge editor ... : всё остальное"};
        for (String l : lines) lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(l).withStyle(ChatFormatting.GRAY).withStyle(st -> st.withItalic(false)))));
        s.getOrCreateTagElement("display").put("Lore", lore);
        return s;
    }

    public static boolean isWand(ItemStack s) {
        return !s.isEmpty() && s.hasTag() && s.getTag().getBoolean(WAND_TAG);
    }
}
