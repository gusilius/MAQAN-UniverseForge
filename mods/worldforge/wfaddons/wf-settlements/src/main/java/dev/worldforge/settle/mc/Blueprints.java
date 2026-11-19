package dev.worldforge.settle.mc;

import dev.worldforge.settle.Defs;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Чертежи зданий. Сначала ищется ванильная структура data/&lt;ns&gt;/structures/&lt;path&gt;.nbt (blueprint "ns:path"),
 * иначе строится процедурная коробка размером sizeX x sizeZ: пол из булыжника, стены из досок, дверь на южной стороне, крыша из плит.
 * Блоки отсортированы снизу вверх; координаты относительны к минимальному углу.
 */
public final class Blueprints {
    private Blueprints() {}

    public record Entry(int x, int y, int z, BlockState state) {}

    public record Plan(int sx, int sy, int sz, List<Entry> blocks, boolean fromNbt, String error) {
        public Plan(int sx, int sy, int sz, List<Entry> blocks, boolean fromNbt) {
            this(sx, sy, sz, blocks, fromNbt, "");
        }
        public boolean valid() { return error == null || error.isBlank(); }
    }

    private static final Map<String, Plan> CACHE = new ConcurrentHashMap<>();

    public static void clearCache() { CACHE.clear(); }


    public static Plan get(ServerLevel level, Defs.Building d) {
        String key = d.id + "|" + d.blueprint + "|" + d.sizeX + "x" + d.sizeZ;
        return CACHE.computeIfAbsent(key, k -> {
            Plan p = null;
            if (d.blueprint != null && d.blueprint.startsWith("schem:")) {
                String schematicId = d.blueprint.substring(6);
                p = SchemLibrary.plan(schematicId);
                if (p != null) return p;
                return new Plan(Math.max(1, d.sizeX), 1, Math.max(1, d.sizeZ),
                        List.of(), true, "Схема не загружена: " + schematicId);
            }
            try {
                p = fromStructure(level, d.blueprint);
            } catch (Exception e) {
                SettleMod.LOGGER.error("wf_settle: чертёж {} не прочитан, берём процедурный: {}", d.blueprint, e.toString());
            }
            return p != null ? p : procedural(d);
        });
    }

    /** Читает структуру через StructureTemplateManager и разбирает её NBT (палитра + список блоков). Воздух и служебные блоки пропускаются. */
    static Plan fromStructure(ServerLevel level, String blueprint) {
        if (blueprint == null || blueprint.isEmpty()) return null;
        ResourceLocation rl = ResourceLocation.tryParse(blueprint);
        if (rl == null) return null;
        Optional<StructureTemplate> t = level.getStructureManager().get(rl);
        if (t.isEmpty()) return null;
        CompoundTag tag = t.get().save(new CompoundTag());
        ListTag size = tag.getList("size", Tag.TAG_INT);
        ListTag paletteTag = tag.contains("palette", Tag.TAG_LIST) ? tag.getList("palette", Tag.TAG_COMPOUND) : tag.getList("palettes", Tag.TAG_LIST).getList(0);
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < paletteTag.size(); i++) palette.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), paletteTag.getCompound(i)));
        List<Entry> list = new ArrayList<>();
        ListTag blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = blocks.getCompound(i);
            ListTag p = b.getList("pos", Tag.TAG_INT);
            int idx = b.getInt("state");
            if (idx < 0 || idx >= palette.size()) continue;
            BlockState st = palette.get(idx);
            if (st.isAir() || st.is(Blocks.STRUCTURE_VOID) || st.is(Blocks.JIGSAW) || st.is(Blocks.STRUCTURE_BLOCK)) continue;
            list.add(new Entry(p.getInt(0), p.getInt(1), p.getInt(2), st));
        }
        if (list.isEmpty()) return null;
        return new Plan(size.getInt(0), size.getInt(1), size.getInt(2), sorted(list), true);
    }

    static Plan procedural(Defs.Building d) {
        int sx = Math.max(3, d.sizeX), sz = Math.max(3, d.sizeZ), h = 3;
        BlockState floor, wall, corner, glass, roof;
        switch (d.architectureMaterial == null ? "oak" : d.architectureMaterial) {
            case "spruce" -> { floor = Blocks.COBBLED_DEEPSLATE.defaultBlockState(); wall = Blocks.SPRUCE_PLANKS.defaultBlockState(); corner = Blocks.SPRUCE_LOG.defaultBlockState(); glass = Blocks.GLASS_PANE.defaultBlockState(); roof = Blocks.SPRUCE_SLAB.defaultBlockState(); }
            case "birch" -> { floor = Blocks.SMOOTH_STONE.defaultBlockState(); wall = Blocks.BIRCH_PLANKS.defaultBlockState(); corner = Blocks.BIRCH_LOG.defaultBlockState(); glass = Blocks.GLASS_PANE.defaultBlockState(); roof = Blocks.BIRCH_SLAB.defaultBlockState(); }
            case "dark_oak" -> { floor = Blocks.DEEPSLATE_BRICKS.defaultBlockState(); wall = Blocks.DARK_OAK_PLANKS.defaultBlockState(); corner = Blocks.DARK_OAK_LOG.defaultBlockState(); glass = Blocks.TINTED_GLASS.defaultBlockState(); roof = Blocks.DARK_OAK_SLAB.defaultBlockState(); }
            case "stone" -> { floor = Blocks.STONE_BRICKS.defaultBlockState(); wall = Blocks.STONE_BRICKS.defaultBlockState(); corner = Blocks.CHISELED_STONE_BRICKS.defaultBlockState(); glass = Blocks.GLASS_PANE.defaultBlockState(); roof = Blocks.STONE_BRICK_SLAB.defaultBlockState(); }
            case "sandstone" -> { floor = Blocks.CUT_SANDSTONE.defaultBlockState(); wall = Blocks.SANDSTONE.defaultBlockState(); corner = Blocks.CHISELED_SANDSTONE.defaultBlockState(); glass = Blocks.GLASS_PANE.defaultBlockState(); roof = Blocks.SANDSTONE_SLAB.defaultBlockState(); }
            case "deepslate" -> { floor = Blocks.POLISHED_DEEPSLATE.defaultBlockState(); wall = Blocks.DEEPSLATE_BRICKS.defaultBlockState(); corner = Blocks.CHISELED_DEEPSLATE.defaultBlockState(); glass = Blocks.TINTED_GLASS.defaultBlockState(); roof = Blocks.DEEPSLATE_BRICK_SLAB.defaultBlockState(); }
            case "mud_brick" -> { floor = Blocks.PACKED_MUD.defaultBlockState(); wall = Blocks.MUD_BRICKS.defaultBlockState(); corner = Blocks.MUD_BRICKS.defaultBlockState(); glass = Blocks.GLASS_PANE.defaultBlockState(); roof = Blocks.MUD_BRICK_SLAB.defaultBlockState(); }
            case "crimson" -> { floor = Blocks.NETHER_BRICKS.defaultBlockState(); wall = Blocks.CRIMSON_PLANKS.defaultBlockState(); corner = Blocks.CRIMSON_STEM.defaultBlockState(); glass = Blocks.RED_STAINED_GLASS_PANE.defaultBlockState(); roof = Blocks.CRIMSON_SLAB.defaultBlockState(); }
            case "warped" -> { floor = Blocks.PRISMARINE_BRICKS.defaultBlockState(); wall = Blocks.WARPED_PLANKS.defaultBlockState(); corner = Blocks.WARPED_STEM.defaultBlockState(); glass = Blocks.CYAN_STAINED_GLASS_PANE.defaultBlockState(); roof = Blocks.WARPED_SLAB.defaultBlockState(); }
            default -> { floor = Blocks.COBBLESTONE.defaultBlockState(); wall = Blocks.OAK_PLANKS.defaultBlockState(); corner = Blocks.OAK_LOG.defaultBlockState(); glass = Blocks.GLASS_PANE.defaultBlockState(); roof = Blocks.OAK_SLAB.defaultBlockState(); }
        }
        BlockState doorLow = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        BlockState doorUp = doorLow.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        int doorX = sx / 2, doorZ = sz - 1;
        List<Entry> l = new ArrayList<>();
        for (int x = 0; x < sx; x++) for (int z = 0; z < sz; z++) l.add(new Entry(x, 0, z, floor));
        for (int y = 1; y <= h; y++) {
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    boolean edge = x == 0 || z == 0 || x == sx - 1 || z == sz - 1;
                    if (!edge) continue;
                    boolean isCorner = (x == 0 || x == sx - 1) && (z == 0 || z == sz - 1);
                    BlockState st;
                    if (isCorner) st = corner;
                    else if (x == doorX && z == doorZ && y <= 2) st = y == 1 ? doorLow : doorUp;
                    else if (y == 2 && (x + z) % 3 == 0) st = glass;
                    else st = wall;
                    l.add(new Entry(x, y, z, st));
                }
            }
        }
        for (int x = 0; x < sx; x++) for (int z = 0; z < sz; z++) l.add(new Entry(x, h + 1, z, roof));
        return new Plan(sx, h + 2, sz, sorted(l), false);
    }

    private static List<Entry> sorted(List<Entry> l) {
        l.sort(Comparator.comparingInt(Entry::y).thenComparingInt(Entry::x).thenComparingInt(Entry::z));
        return l;
    }
}
