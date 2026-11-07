package dev.worldforge.settle.mc;

import dev.worldforge.settle.Defs;
import dev.worldforge.settle.SchemCost;
import dev.worldforge.settle.Schematic;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Библиотека схем: файлы .litematic (Litematica), .schem (Sponge/WorldEdit) и .nbt (структуры) из папок
 * &lt;мир&gt;/worldforge/schematics и config/worldforge/schematics. Каждая схема становится зданием "schem_&lt;имя&gt;" в разделе «Схемы»:
 * стоимость и объём работ считаются по блокам, кровати дают жильё. Рядом можно положить &lt;имя&gt;.json с полями здания
 * (name, category, cost, work, housing, jobs, recipe...) - они перекроют вычисленное.
 * Строители достраивают схему блок за блоком снизу вверх, как и любое другое здание.
 */
public final class SchemLibrary {
    private SchemLibrary() {}

    private static final Map<String, Schematic> SCHEMS = new ConcurrentHashMap<>();
    private static final Map<String, Blueprints.Plan> PLANS = new ConcurrentHashMap<>();

    public static Path worldDir(MinecraftServer s) { return s.getWorldPath(LevelResource.ROOT).resolve("worldforge").resolve("schematics"); }

    public static Path configDir() { return FMLPaths.CONFIGDIR.get().resolve("worldforge").resolve("schematics"); }

    private static boolean isSchem(String n) {
        n = n.toLowerCase(Locale.ROOT);
        return n.endsWith(".litematic") || n.endsWith(".schem") || n.endsWith(".nbt");
    }

    private static String base(String file) { return file.substring(0, file.lastIndexOf('.')); }

    /** Сканирует папки, добавляет здания в defs и их JSON в raw (его получают клиенты). Возвращает отчёт построчно. */
    public static List<String> loadAll(MinecraftServer server, Defs defs, List<String> raw) {
        SCHEMS.clear();
        PLANS.clear();
        List<String> report = new ArrayList<>();
        for (Path dir : List.of(configDir(), worldDir(server))) {
            try { Files.createDirectories(dir); } catch (Exception ignored) {}
            try (Stream<Path> st = Files.list(dir)) {
                List<Path> files = st.filter(p -> Files.isRegularFile(p) && isSchem(p.getFileName().toString())).sorted().toList();
                for (Path p : files) {
                    String file = p.getFileName().toString(), b = base(file);
                    try {
                        Schematic s = Schematic.parse(Files.readAllBytes(p), file);
                        Path side = dir.resolve(b + ".json");
                        String sidecar = Files.isRegularFile(side) ? Files.readString(side, StandardCharsets.UTF_8) : null;
                        String json = SchemCost.buildingJson(b, s, sidecar);
                        defs.add(json);
                        raw.add(json);
                        SCHEMS.put(b, s);
                        Defs.Building d = defs.buildings.get(SchemCost.idFor(b));
                        report.add(d.id + ": " + s.sx + "x" + s.sy + "x" + s.sz + ", блоков " + s.solidCount() + ", стоимость " + d.cost + ", работа " + (int) d.work + (d.housing > 0 ? ", жильё " + d.housing : ""));
                    } catch (Exception ex) {
                        report.add("ОШИБКА " + file + ": " + ex.getMessage());
                        SettleMod.LOGGER.error("wf_settle: схема {} не прочитана: {}", file, ex.toString());
                    }
                }
            } catch (Exception ex) {
                SettleMod.LOGGER.error("wf_settle: папка схем {}: {}", dir, ex.toString());
            }
        }
        return report;
    }

    public static Set<String> names() { return new TreeSet<>(SCHEMS.keySet()); }

    /** Чертёж для строителей: блоки схемы снизу вверх. Неизвестные блоки (нет мода) пропускаются. */
    public static Blueprints.Plan plan(String base) {
        return PLANS.computeIfAbsent(base, k -> {
            Schematic s = SCHEMS.get(k);
            if (s == null) return null;
            BlockState[] pal = new BlockState[s.palette.size()];
            int missing = 0;
            for (int i = 0; i < pal.length; i++) {
                String str = s.palette.get(i);
                if (Schematic.isAir(str)) continue;
                try {
                    pal[i] = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), str, false).blockState();
                } catch (Exception ex) {
                    missing++;
                    SettleMod.LOGGER.warn("wf_settle: схема {}: неизвестный блок {}", k, str);
                }
            }
            if (missing > 0) SettleMod.LOGGER.warn("wf_settle: схема {}: пропущено видов блоков: {}", k, missing);
            List<Blueprints.Entry> list = new ArrayList<>();
            for (int y = 0; y < s.sy; y++) for (int z = 0; z < s.sz; z++) for (int x = 0; x < s.sx; x++) {
                BlockState st = pal[s.cells[s.index(x, y, z)]];
                if (st != null) list.add(new Blueprints.Entry(x, y, z, st));
            }
            // на одном уровне сначала опоры, потом то, что к ним крепится
            list.sort(Comparator.comparingInt((Blueprints.Entry e) -> e.y()).thenComparingInt(e -> fragile(e.state()) ? 1 : 0)
                    .thenComparingInt(Blueprints.Entry::x).thenComparingInt(Blueprints.Entry::z));
            return list.isEmpty() ? null : new Blueprints.Plan(s.sx, s.sy, s.sz, list, true);
        });
    }

    private static boolean fragile(BlockState st) {
        Block b = st.getBlock();
        return b instanceof TorchBlock || b instanceof LadderBlock || b instanceof FaceAttachedHorizontalDirectionalBlock
                || b instanceof WallSignBlock || b instanceof WallBannerBlock || b instanceof CarpetBlock || b instanceof PressurePlateBlock
                || b instanceof LanternBlock || b instanceof TripWireHookBlock;
    }
}
