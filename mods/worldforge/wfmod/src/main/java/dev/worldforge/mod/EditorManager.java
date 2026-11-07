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

    /** Помечает карты-предметы устаревшими после правки. */
    public static void touch(MapData m) {
        STALE.add(m);
        VERSIONS.merge(m, 2, (a, b) -> a + 1);
    }

    public static void refreshMaps(MapData m) {
        if (STALE.remove(m)) PoliticalMapRenderer.regenerateAll(m);
    }

    /** Сохраняет все изменённые карты. Возвращает число сохранённых. */
    public static int saveDirty(MinecraftServer server) {
        int saved = 0;
        synchronized (MARKERS) {
            for (Map.Entry<MapData, Markers> e : MARKERS.entrySet()) {
                if (!e.getValue().isDirty()) continue;
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
                if (!e.getValue().isDirty()) continue;
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
    }

    /** Накладывает сохранённые правки при загрузке карты. */
    public static void applySaved(MinecraftServer server, MapData m) {
        if (server == null || m.titles == null) return;
        try {
            if (TitleStore.applyOverlay(m, overlayDir(server, m))) {
                PoliticalMapRenderer.regenerateAll(m);
                WorldForgeMod.LOGGER.info("WorldForge: к карте '{}' применены сохранённые правки границ", m.name);
            }
        } catch (IOException | RuntimeException e) {
            WorldForgeMod.LOGGER.error("WorldForge: не удалось применить правки карты '{}': {}", m.name, e.toString());
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
