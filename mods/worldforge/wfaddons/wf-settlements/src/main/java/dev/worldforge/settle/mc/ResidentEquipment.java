package dev.worldforge.settle.mc;

import dev.worldforge.people.Character;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.settle.Worker;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Persistent resident tools. Character.props owns the item NBT; the Minecraft body only mirrors it.
 * Tool choice is constrained by profession, specialist skill, RPG level and class/archetype.
 */
public final class ResidentEquipment {
    private static final String ITEM_KEY = "equipment.mainhand.nbt";
    private static final String TIER_KEY = "equipment.mainhand.tier";
    private static final String ROLE_KEY = "equipment.mainhand.role";

    private ResidentEquipment() { }

    private enum Kind { AXE, PICKAXE, HOE, SHOVEL, SWORD }
    private record Tool(Kind kind, ItemStack[] tiers, String skill, String resource, int[] cost) { }

    private static String requiredRole(Worker worker, Colony colony) {
        Colony.WorkOrder order = colony.workOrders.get(worker.id);
        if (order == null) return worker.workRole();
        return switch (order.mode) {
            case "wood" -> "lumberjack";
            case "stone", "ore", "mine" -> "miner";
            case "soil", "level" -> "builder";
            case "harvest", "farm" -> "farmer";
            case "herbs" -> "herbalist";
            default -> worker.workRole();
        };
    }

    private static Kind requiredKind(String mode) {
        return switch (mode) {
            case "wood" -> Kind.AXE;
            case "stone", "ore", "mine" -> Kind.PICKAXE;
            case "soil", "level" -> Kind.SHOVEL;
            default -> null;
        };
    }

    private static Tool tool(String role) {
        return switch (role) {
            case "lumberjack", "sawyer", "woodcutter" -> new Tool(Kind.AXE,
                    new ItemStack[]{new ItemStack(Items.WOODEN_AXE),new ItemStack(Items.STONE_AXE),new ItemStack(Items.IRON_AXE),new ItemStack(Items.DIAMOND_AXE),new ItemStack(Items.NETHERITE_AXE)},
                    "axe_mastery", "wood", new int[]{2,3,2,2,1});
            case "miner", "mason", "stonemason" -> new Tool(Kind.PICKAXE,
                    new ItemStack[]{new ItemStack(Items.WOODEN_PICKAXE),new ItemStack(Items.STONE_PICKAXE),new ItemStack(Items.IRON_PICKAXE),new ItemStack(Items.DIAMOND_PICKAXE),new ItemStack(Items.NETHERITE_PICKAXE)},
                    "mining", "stone", new int[]{2,3,2,2,1});
            case "farmer", "gardener", "herbalist" -> new Tool(Kind.HOE,
                    new ItemStack[]{new ItemStack(Items.WOODEN_HOE),new ItemStack(Items.STONE_HOE),new ItemStack(Items.IRON_HOE),new ItemStack(Items.DIAMOND_HOE),new ItemStack(Items.NETHERITE_HOE)},
                    "farming", "wood", new int[]{2,3,2,2,1});
            case "builder", "excavator", "terraformer" -> new Tool(Kind.SHOVEL,
                    new ItemStack[]{new ItemStack(Items.WOODEN_SHOVEL),new ItemStack(Items.STONE_SHOVEL),new ItemStack(Items.IRON_SHOVEL),new ItemStack(Items.DIAMOND_SHOVEL),new ItemStack(Items.NETHERITE_SHOVEL)},
                    "carpentry", "wood", new int[]{2,3,2,2,1});
            case "guard", "soldier", "hunter", "ranger" -> new Tool(Kind.SWORD,
                    new ItemStack[]{new ItemStack(Items.WOODEN_SWORD),new ItemStack(Items.STONE_SWORD),new ItemStack(Items.IRON_SWORD),new ItemStack(Items.DIAMOND_SWORD),new ItemStack(Items.NETHERITE_SWORD)},
                    "swordsmanship", "wood", new int[]{2,3,2,2,1});
            default -> null;
        };
    }

    private static String populationScope(Character person, Colony colony) {
        try {
            Class<?> people = Class.forName("dev.worldforge.people.mc.PeopleMod");
            Object raw = people.getMethod("registries").invoke(null);
            if (raw instanceof java.util.Map<?, ?> registries) {
                for (java.util.Map.Entry<?, ?> entry : registries.entrySet()) {
                    Object map = entry.getKey(), registry = entry.getValue();
                    if (registry == null) continue;
                    Object found = registry.getClass().getMethod("get", String.class).invoke(registry, person.id);
                    if (found != person) continue;
                    Object name = map.getClass().getField("name").get(map);
                    if (name != null && !String.valueOf(name).isBlank()) return String.valueOf(name);
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) { }
        return colony.dim;
    }

    private static int level(Character person, Colony colony) {
        Object raw = person.props.get("progress.level");
        if (raw instanceof Number n) return Math.max(1, n.intValue());
        // wf-progress is an optional addon. Reflection keeps settlements loadable without it.
        try {
            Class<?> mod = Class.forName("dev.worldforge.progress.mc.ProgressMod");
            Object progress = mod.getField("PROGRESS").get(null);
            Class<?> population = PopulationSimulation.class;
            Method ownerMethod = population.getMethod("progressOwner", Character.class, String.class);
            String owner = (String) ownerMethod.invoke(null, person, populationScope(person, colony));
            Method profileMethod = progress.getClass().getMethod("profile", String.class);
            Object profile = profileMethod.invoke(progress, owner);
            Field levelField = profile.getClass().getField("level");
            int result = levelField.getInt(profile);
            person.props.put("progress.level", result);
            Field archetypeField = profile.getClass().getField("archetype");
            Object archetype = archetypeField.get(profile);
            if (archetype instanceof String s && !s.isBlank()) person.props.put("rpg.class", s);
            return Math.max(1, result);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return 1;
        }
    }

    private static String archetype(Character person) {
        Object raw = person.props.getOrDefault("rpg.class", person.props.getOrDefault("class", person.props.get("archetype")));
        return raw == null ? "" : String.valueOf(raw).toLowerCase(Locale.ROOT);
    }

    private static int maxTier(Character person, Colony colony, Worker worker, Tool tool) {
        int level = level(person, colony);
        int rank = PopulationSimulation.skillRank(person, tool.skill);
        int tier = level >= 15 && rank >= 10 ? 4 : level >= 10 && rank >= 8 ? 3
                : level >= 6 && rank >= 5 ? 2 : level >= 3 && rank >= 2 ? 1 : 0;
        String clazz = archetype(person);
        String profession = worker.permanentProfession();
        boolean aligned = (tool.kind == Kind.AXE && (clazz.contains("ranger") || clazz.contains("druid") || clazz.contains("wood")))
                || (tool.kind == Kind.PICKAXE && (clazz.contains("miner") || clazz.contains("smith") || clazz.contains("craft")))
                || (tool.kind == Kind.HOE && (clazz.contains("druid") || clazz.contains("farmer") || clazz.contains("nature")))
                || (tool.kind == Kind.SHOVEL && (clazz.contains("builder") || clazz.contains("engineer")))
                || (tool.kind == Kind.SWORD && (clazz.contains("warrior") || clazz.contains("fighter") || clazz.contains("ranger")));
        if (aligned && tier < 4 && level >= 3) tier++;
        if (worker.generalist()) tier = Math.min(tier, 1);
        return Math.min(4, tier);
    }

    private static String materialForTier(int tier) {
        return switch (tier) {
            case 0 -> "wood";
            case 1 -> "stone";
            case 2 -> "iron";
            case 3 -> "diamond";
            default -> "netherite";
        };
    }

    private static boolean matches(ItemStack stack, Kind kind) {
        if (stack.isEmpty()) return false;
        Item item = stack.getItem();
        return switch (kind) {
            case AXE -> item instanceof AxeItem;
            case PICKAXE -> item instanceof PickaxeItem;
            case HOE -> item instanceof HoeItem;
            case SHOVEL -> item instanceof ShovelItem;
            case SWORD -> item instanceof SwordItem;
        };
    }

    private static void save(Character person, ItemStack stack, int tier, String role) {
        person.props.put(ITEM_KEY, stack.save(new CompoundTag()).toString());
        person.props.put(TIER_KEY, tier);
        person.props.put(ROLE_KEY, role);
    }

    private static ItemStack load(Character person) {
        Object raw = person.props.get(ITEM_KEY);
        if (!(raw instanceof String nbt) || nbt.isBlank()) return ItemStack.EMPTY;
        try { return ItemStack.of(TagParser.parseTag(nbt)); }
        catch (Exception ignored) { person.props.remove(ITEM_KEY); return ItemStack.EMPTY; }
    }

    public static void sync(WFWorker body) {
        Character person = SettleMod.residentCharacter(body);
        if (person == null) return;
        ItemStack stored = load(person);
        if (!ItemStack.matches(body.getItemBySlot(EquipmentSlot.MAINHAND), stored))
            body.setItemSlot(EquipmentSlot.MAINHAND, stored.copy());
    }

    public static String summary(Character person) {
        ItemStack stack = load(person);
        if (stack.isEmpty()) return "инструмент отсутствует";
        Object role = person.props.getOrDefault(ROLE_KEY, "рабочий инструмент");
        Object tier = person.props.getOrDefault(TIER_KEY, 0);
        return stack.getHoverName().getString() + " · " + role + " · качество " + (1 + (tier instanceof Number n ? n.intValue() : 0))
                + " · прочность " + (stack.getMaxDamage() <= 0 ? "∞" : (stack.getMaxDamage() - stack.getDamageValue()) + "/" + stack.getMaxDamage());
    }

    /** Craft a better profession tool only when the resident has the skill, level and settlement materials. */
    public static String craft(WFWorker body) {
        Colony colony = body.colony();
        Worker worker = body.worker();
        Character person = SettleMod.residentCharacter(body);
        if (colony == null || worker == null || person == null) return "Не удалось проверить данные жителя.";
        Tool tool = tool(requiredRole(worker, colony));
        if (tool == null) return "Для текущей профессии специальный инструмент не предусмотрен.";
        int allowed = maxTier(person, colony, worker, tool);
        ItemStack current = load(person);
        int currentTier = person.props.get(TIER_KEY) instanceof Number n ? n.intValue() : -1;
        int chosen = -1;
        for (int tier = allowed; tier >= 0; tier--) {
            if (tier <= currentTier) continue;
            String resource = materialForTier(tier);
            int cost = tool.cost[tier];
            if (colony.s.amount(resource) >= cost) { chosen = tier; break; }
        }
        if (chosen < 0) return current.isEmpty()
                ? "Нет подходящих материалов для инструмента. Нужны ресурсы и навык " + tool.skill + "."
                : "Текущий инструмент уже соответствует доступному уровню; для улучшения нужны уровень, навык и материалы.";
        String resource = materialForTier(chosen);
        int cost = tool.cost[chosen];
        if (!colony.s.consumeResource(resource, cost)) return "Материалы уже использованы другим заказом; изготовление отменено.";
        ItemStack crafted = tool.tiers[chosen].copy();
        save(person, crafted, chosen, requiredRole(worker, colony));
        body.setItemSlot(EquipmentSlot.MAINHAND, crafted.copy());
        person.props.put("equipment.last_craft", colony.s.year());
        return "Изготовил " + crafted.getHoverName().getString() + " (уровень " + (chosen + 1) + "), потратил " + cost + " " + resource + ".";
    }


    /** Refuse physical extraction when the required tool is missing or of the wrong type. */
    public static boolean readyForWork(WFWorker body, String mode) {
        Kind required = requiredKind(mode);
        if (required == null) return true;
        ItemStack held = body.getItemBySlot(EquipmentSlot.MAINHAND);
        if (matches(held, required)) return true;
        Worker worker = body.worker();
        if (worker != null) worker.status = "не хватает инструмента " + required.name().toLowerCase(Locale.ROOT)
                + ": ищет материалы для изготовления или ждёт инструмент от правителя";
        return false;
    }

    /** Autonomous maintenance: a resident crafts/upgrades a tool from settlement stock when work needs one. */
    public static void autoMaintain(WFWorker body) {
        Colony colony = body.colony();
        Worker worker = body.worker();
        Character person = SettleMod.residentCharacter(body);
        if (colony == null || worker == null || person == null || worker.manualFollow || worker.manualOrder) return;
        if (worker.building <= 0 && !colony.workOrders.containsKey(worker.id)) return;
        long now = (long) Math.floor(colony.s.time);
        Object last = person.props.get("equipment.craft_attempt");
        if (last instanceof Number n && now - n.longValue() < 120) return;
        person.props.put("equipment.craft_attempt", now);
        ItemStack before = load(person);
        String result = craft(body);
        if (result.startsWith("Изготовил")) worker.status = "самостоятельно " + result.toLowerCase(Locale.ROOT);
        else if (before.isEmpty()) worker.status = "нужен инструмент: " + result;
    }

    /** Wear is applied only after the owning work-order confirms a successful world action. */
    public static void onWork(WFWorker body, Colony colony, String mode) {
        Character person = SettleMod.residentCharacter(body);
        Worker worker = body.worker();
        if (person == null || worker == null) return;
        Tool used = tool(requiredRole(worker, colony));
        if (used != null && !worker.generalist()) {
            String canonicalProfession = PopulationSimulation.profession(person);
            if (canonicalProfession.isBlank()) {
                PopulationSimulation.assignProfession(person, worker.permanentProfession(), colony.s.year());
                canonicalProfession = worker.permanentProfession();
            }
            if (canonicalProfession.equals(worker.permanentProfession())) {
                try { PopulationSimulation.train(person, used.skill, 1); }
                catch (IllegalStateException ignored) { /* A profession transition or maxed skill may reject practice. */ }
            }
        }
        ItemStack stack = body.getItemBySlot(EquipmentSlot.MAINHAND);
        if (!stack.isEmpty() && stack.getMaxDamage() > 0) {
            stack.hurtAndBreak(1, body, entity -> entity.broadcastBreakEvent(EquipmentSlot.MAINHAND));
            if (stack.isEmpty()) {
                person.props.remove(ITEM_KEY);
                person.props.remove(TIER_KEY);
                person.props.remove(ROLE_KEY);
            } else {
                int tier = person.props.get(TIER_KEY) instanceof Number n ? n.intValue() : 0;
                save(person, stack, tier, requiredRole(worker, colony));
            }
        }
    }

    /** Accept a real player-supplied tool. It must match the resident's current work role. */
    public static String receive(WFWorker body, net.minecraft.server.level.ServerPlayer player) {
        Worker worker = body.worker();
        Character person = SettleMod.residentCharacter(body);
        if (worker == null || person == null) return "Данные жителя недоступны.";
        Tool expected = tool(requiredRole(worker, body.colony()));
        if (expected == null) return "Эта профессия не использует переносимый инструмент.";
        ItemStack offered = player.getMainHandItem();
        if (!matches(offered, expected.kind)) return "Нужен инструмент подходящего типа для профессии " + worker.workRole() + ".";
        ItemStack copy = offered.copy();
        copy.setCount(1);
        int tier = copy.getItem() instanceof TieredItem tiered ? Math.max(0, Math.min(4, tiered.getTier().getLevel())) : 0;
        save(person, copy, tier, worker.workRole());
        body.setItemSlot(EquipmentSlot.MAINHAND, copy.copy());
        offered.shrink(1);
        return "Принял инструмент от игрока: " + copy.getHoverName().getString() + ".";
    }
}
