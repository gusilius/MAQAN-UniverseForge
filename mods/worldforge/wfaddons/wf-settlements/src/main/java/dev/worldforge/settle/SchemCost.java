package dev.worldforge.settle;

import dev.worldforge.mod.Json;

import java.util.*;

/** Превращает схему в описание здания: стоимость по материалам, объём работ, жильё по кроватям. Всё можно переопределить файлом-спутником <имя>.json. */
public final class SchemCost {
    private SchemCost() {}

    public static final String CATEGORY = "schem";
    /** Блоков на единицу материала. */
    public static final int BLOCKS_PER_UNIT = 8;
    /** Блоков на единицу работы (1 строитель = 1 работа в секунду). */
    public static final int BLOCKS_PER_WORK = 6;

    static boolean stoneLike(String id) {
        for (String k : new String[]{"stone", "brick", "cobble", "deepslate", "granite", "diorite", "andesite", "concrete", "terracotta", "sandstone", "tuff", "basalt", "obsidian"}) if (id.contains(k)) return true;
        return false;
    }

    /** Стоимость: камнеподобные блоки -> stone, остальное -> wood. */
    public static Map<String, Integer> cost(Map<String, Integer> blockCounts) {
        int stone = 0, wood = 0;
        for (Map.Entry<String, Integer> e : blockCounts.entrySet()) {
            if (stoneLike(e.getKey())) stone += e.getValue(); else wood += e.getValue();
        }
        Map<String, Integer> c = new LinkedHashMap<>();
        if (wood > 0) c.put("wood", Math.max(1, (wood + BLOCKS_PER_UNIT - 1) / BLOCKS_PER_UNIT));
        if (stone > 0) c.put("stone", Math.max(1, (stone + BLOCKS_PER_UNIT - 1) / BLOCKS_PER_UNIT));
        return c;
    }

    public static double work(int solid) { return Math.max(20, solid / (double) BLOCKS_PER_WORK); }

    /** Кроватей (изголовий) - столько жителей поселятся. */
    public static int beds(Schematic s) { return s.countMatching("_bed", "part=head"); }

    public static String idFor(String fileBase) {
        String id = fileBase.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}_]+", "_").replaceAll("^_+|_+$", "");
        return "schem_" + (id.isEmpty() ? "x" : id);
    }

    /** JSON набора с одним зданием для Defs.add. sidecar (может быть null) - поля здания, которые перекрывают вычисленные. */
    @SuppressWarnings("unchecked")
    public static String buildingJson(String fileBase, Schematic s, String sidecar) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", fileBase);
        b.put("category", CATEGORY);
        b.put("blueprint", "schem:" + fileBase);
        b.put("size", List.of(s.sx, s.sz));
        b.put("cost", new LinkedHashMap<String, Object>(cost(s.blockCounts())));
        b.put("work", work(s.solidCount()));
        int beds = beds(s);
        if (beds > 0) b.put("housing", beds);
        if (sidecar != null && !sidecar.isBlank()) {
            Map<String, Object> over = (Map<String, Object>) Json.parse(sidecar);
            b.putAll(over);
            b.put("blueprint", "schem:" + fileBase);
        }
        Map<String, Object> bs = new LinkedHashMap<>();
        bs.put(idFor(fileBase), b);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("buildings", bs);
        return Json.write(root);
    }
}
