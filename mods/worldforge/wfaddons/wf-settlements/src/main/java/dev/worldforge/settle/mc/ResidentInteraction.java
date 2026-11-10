package dev.worldforge.settle.mc;

import dev.worldforge.mod.api.ActionResult;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.worldforge.settle.ResidentSocial;
import dev.worldforge.people.Character;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.settle.ResidentQuest;
import dev.worldforge.settle.Worker;
import dev.worldforge.people.mc.PeopleMod;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** First-person resident conversation, role-aware actions and Character-persisted resident contracts. */
public final class ResidentInteraction {
    private static final String QUEST_KEY = "wf_resident_quest";

    @SubscribeEvent
    public void interact(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof WFWorker worker) || event.getLevel().isClientSide
                || !(event.getEntity() instanceof ServerPlayer player)
                || event.getHand() != InteractionHand.MAIN_HAND) return;
        Colony colony = SettleMod.colony(worker.colonyId);
        if (!validApproach(player, worker, colony)) return;
        event.setCanceled(true);
        open(player, worker);
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("wfresident")
                .then(Commands.argument("action", StringArgumentType.word())
                        .then(Commands.argument("resident", StringArgumentType.word())
                                .executes(ctx -> execute(ctx.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(ctx, "action"),
                                        StringArgumentType.getString(ctx, "resident"))))));
    }

    private static boolean validApproach(ServerPlayer player, WFWorker worker, Colony colony) {
        if (colony == null || worker.worker() == null || worker.level() != player.level()) return false;
        if (!player.serverLevel().dimension().location().toString().equals(colony.dim)) return false;
        return player.distanceToSqr(worker) <= 36.0;
    }

    private static Character person(WFWorker worker) {
        return SettleMod.residentCharacter(worker);
    }

    private static void open(ServerPlayer player, WFWorker worker) {
        Colony colony = SettleMod.colony(worker.colonyId);
        Character person = person(worker);
        Worker data = worker.worker();
        if (colony == null || person == null || data == null || !person.alive) {
            player.sendSystemMessage(Component.literal("Житель сейчас не может поговорить с вами."));
            return;
        }
        ResidentQuest currentQuest = quest(person);
        String questText = currentQuest == null
                ? (person.props.containsKey(QUEST_KEY) ? "Повреждённая запись · сохранена для восстановления" : "Нет активного поручения")
                : currentQuest.status().name() + " · " + currentQuest.objective()
                    + " · " + currentQuest.progress() + "/" + currentQuest.required();
        String profession = colony.s.defs.professionName(data.profession);
        String status = data.status == null || data.status.isBlank() ? "Повседневная жизнь" : data.status;
        int age = Math.max(0, PeopleMod.YEAR - person.birthYear);
        net.minecraftforge.network.NetworkHooks.openScreen(player,
                new net.minecraft.world.SimpleMenuProvider((windowId, inventory, viewer) ->
                        new ResidentDialogueMenu(windowId, inventory, worker.getUUID().toString(), person.name, profession,
                                status, residentProfile(person), relationship(person, player), questText, age),
                        Component.literal("Диалог · " + person.name)),
                buffer -> {
                    buffer.writeUtf(worker.getUUID().toString(), 128);
                    buffer.writeUtf(person.name, 128);
                    buffer.writeUtf(profession, 128);
                    buffer.writeUtf(status, 256);
                    buffer.writeUtf(residentProfile(person), 512);
                    buffer.writeUtf(relationship(person, player), 128);
                    buffer.writeUtf(questText, 512);
                    buffer.writeVarInt(age);
                });
    }

    private static String socialSummary(Character person) {
        Object raw = person.props.get("social.bonds");
        if (!(raw instanceof Map<?,?> bonds) || bonds.isEmpty()) return "пока мало знакомых";
        return bonds.entrySet().stream().limit(6)
                .map(entry -> String.valueOf(entry.getKey()) + " — " + String.valueOf(entry.getValue()))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String residentProfile(Character person) {
        String traits = person.traits.isEmpty() ? "нет выраженных черт" : String.join(", ", person.traits);
        Map<String,Integer> skills = PopulationSimulation.skillRanksSnapshot(person);
        String skillText = skills.isEmpty() ? "навыки не развиты" : skills.entrySet().stream()
                .sorted(Map.Entry.<String,Integer>comparingByValue().reversed())
                .limit(4).map(e -> e.getKey() + " " + e.getValue()).collect(java.util.stream.Collectors.joining(", "));
        String profile = family(person) + " · " + ("f".equalsIgnoreCase(person.gender) ? "женщина" : "мужчина")
                + " · культура: " + (person.culture == null || person.culture.isBlank() ? "не указана" : person.culture)
                + " · черты: " + traits + " · навыки: " + skillText;
        return profile.length() > 220 ? profile.substring(0, 217) + "..." : profile;
    }

    private static String greeting(Character person, Worker worker, Colony colony) {
        String status = worker.status == null ? "" : worker.status;
        if (status.contains("укрывается")) return "Сейчас опасно. Я стараюсь укрыть жителей.";
        if (status.contains("спит")) return "Я отдыхал. Хорошо, что вы заглянули.";
        if (status.contains("общается")) return "Мы обсуждали новости поселения.";
        if (worker.profession.equals("guard") || worker.profession.equals("soldier"))
            return "Я охраняю " + colony.s.name + ". Что прикажете?";
        if (worker.profession.equals("builder")) return "Стройка требует материалов и рабочих рук.";
        return "Рад вас видеть. У нас сейчас " + colony.s.seasonName().toLowerCase()
                + ", а поселению нужны еда, безопасность и работа.";
    }



    private static int execute(ServerPlayer player, String action, String entityId) {
        long minute = dev.worldforge.mod.api.WorldForgeApi.clock().worldMinute();
        String actor = player.getUUID().toString();
        String payload = action + ":" + entityId;
        String requestId = "resident:" + UUID.nameUUIDFromBytes(
                (actor + ":" + player.server.getTickCount() + ":" + payload)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request = new dev.worldforge.mod.api.ActionExecutionService.Request(
                requestId, "settlement.resident_interaction", actor, entityId, List.of(action), minute);
        try {
            var task = dev.worldforge.mod.api.WorldForgeApi.actionExecution().execute(request,
                    req -> {
                        if (player.server.getPlayerList().getPlayer(player.getUUID()) == null)
                            return "Игрок отключился до исполнения действия";
                        UUID target;
                        try { target = UUID.fromString(req.targetId()); }
                        catch (IllegalArgumentException ex) { return "Некорректный ID жителя"; }
                        Entity entity = player.serverLevel().getEntity(target);
                        if (!(entity instanceof WFWorker current)) return "Житель больше не находится рядом";
                        Colony currentColony = SettleMod.colony(current.colonyId);
                        if (!validApproach(player, current, currentColony)) return "Житель больше не находится рядом";
                        if (SettleMod.actionsBlocked())
                            return "Все изменения поселений заблокированы после ошибки сохранения; требуется перезапуск и сверка";
                        if (requiresRuler(req.args().get(0))
                                && !WorkerControlAccess.mayControl(player, currentColony))
                            return "Недостаточно полномочий для этого приказа";
                        return null;
                    },
                    (req, attempt) -> {
                        String[] outcome = {""};
                        int result = executeDirect(player, req.args().get(0), req.targetId(), outcome);
                        String message = outcome[0].isBlank() ? "Действие жителя выполнено" : outcome[0];
                        if (result == 1) return dev.worldforge.mod.api.ActionResult.ok(message);
                        if (result == 2) return dev.worldforge.mod.api.ActionResult.retry(message);
                        return dev.worldforge.mod.api.ActionResult.fail(outcome[0].isBlank()
                                ? "Действие жителя отклонено без изменения состояния" : outcome[0]);
                    },
                    (req, failure, attempt) -> {
                        String requested = req.args().isEmpty() ? "" : req.args().get(0);
                        if (requested.startsWith("work_"))
                            return new dev.worldforge.mod.api.ActionExecutionService.Plan(
                                    req.actionId(), req.targetId(), List.of("work"),
                                    "Пробуем автоматически подобрать другую совместимую профессию и рабочее место");
                        return null;
                    }, minute, dev.worldforge.mod.WorldForgeMod.actionExecutionFile(player.server));
            if (task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.FAILED)
                player.sendSystemMessage(Component.literal("Действие не выполнено: " + task.message()));
            return task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED ? 1 : 0;
        } catch (RuntimeException ex) {
            player.sendSystemMessage(Component.literal("Не удалось выполнить действие: " + ex.getMessage()));
            return 0;
        }
    }

    private static boolean requiresRuler(String action) {
        return switch (action) {
            case "repair", "tool", "equip", "follow", "stop", "work",
                    "work_builder", "work_lumberjack", "work_miner", "work_farmer", "work_hauler",
                    "work_area_resume", "work_area_cancel" -> true;
            default -> false;
        };
    }

    private static int executeDirect(ServerPlayer player, String action, String entityId, String[] outcome) {
        UUID id;
        try { id = UUID.fromString(entityId); }
        catch (IllegalArgumentException ex) {
            player.sendSystemMessage(Component.literal("Некорректный ID жителя."));
            return 0;
        }
        Entity entity = player.serverLevel().getEntity(id);
        if (!(entity instanceof WFWorker worker)) {
            player.sendSystemMessage(Component.literal("Житель больше не находится рядом. Откройте диалог повторно."));
            return 0;
        }
        Colony colony = SettleMod.colony(worker.colonyId);
        if (!validApproach(player, worker, colony)) {
            player.sendSystemMessage(Component.literal("Подойдите к жителю ближе, в том же измерении."));
            return 0;
        }
        Character person = person(worker);
        Worker data = worker.worker();
        if (person == null || !person.alive || data == null) {
            player.sendSystemMessage(Component.literal("Связь жителя с реестром персонажей повреждена; действие отменено."));
            return 0;
        }
        boolean ruler = WorkerControlAccess.mayControl(player, colony);
        switch (action) {
            case "open" -> { }
            case "talk" -> player.sendSystemMessage(Component.literal("§f" + greeting(person, data, colony)
                    + " §7(Отношение: " + relationship(person, player) + ")"));
            case "talk_family" -> player.sendSystemMessage(Component.literal("§e" + person.name + ": §f"
                    + family(person) + " · Дом: " + (person.house == null || person.house.isBlank() ? "не указан" : person.house)));
            case "talk_work" -> player.sendSystemMessage(Component.literal("§e" + person.name + ": §fМоя профессия — "
                    + colony.s.defs.professionName(data.profession) + ". Сейчас я "
                    + (data.assignedProfession == null || data.assignedProfession.isBlank() ? "свободен" : "работаю как " + data.assignedProfession)
                    + "; состояние: " + data.status + "."));
            case "talk_world" -> player.sendSystemMessage(Component.literal("§e" + person.name + ": §fВ поселении "
                    + colony.s.name + " · запас еды " + (int) colony.s.amount("food")
                    + " · год " + colony.s.year() + " · "
                    + (colony.s.raidIncoming ? "опасность рядом" : "сейчас спокойно") + "."));
            case "talk_opinion" -> player.sendSystemMessage(Component.literal("§e" + person.name + ": §fК вам я отношусь так: "
                    + relationship(person, player) + ". Мои связи: " + socialSummary(person) + "."));
            case "status" -> {
                player.sendSystemMessage(Component.literal("§6" + person.name + " §7· " + data.profession
                        + " · " + data.status + " · поселение " + colony.s.name));
                player.sendSystemMessage(Component.literal("§7Семья: " + family(person)
                        + " · возраст: " + Math.max(0, PeopleMod.YEAR - person.birthYear) + " лет"));
                player.sendSystemMessage(Component.literal("§7Отношение к вам: " + relationship(person, player)));
                player.sendSystemMessage(Component.literal("§7Инструмент: " + ResidentEquipment.summary(person, colony, data)));
            }
            case "repair" -> {
                if (!ruler) { deny(player); return 0; }
                ActionResult result = ResidentEquipment.repairAction(worker);
                player.sendSystemMessage(Component.literal((result.ok ? "§a" : "§e") + person.name + ": " + result.message));
                if (!result.ok) { outcome[0] = result.message; return 0; }
            }
            case "tool" -> {
                if (!ruler) { deny(player); return 0; }
                ActionResult result = ResidentEquipment.craftAction(worker);
                player.sendSystemMessage(Component.literal((result.ok ? "§a" : "§e") + person.name + ": " + result.message));
                if (!result.ok) { outcome[0] = result.message; return 0; }
            }
            case "equip" -> {
                if (!ruler) { deny(player); return 0; }
                ActionResult result = ResidentEquipment.receiveAction(worker, player);
                player.sendSystemMessage(Component.literal((result.ok ? "§a" : "§e") + person.name + ": " + result.message));
                if (!result.ok) { outcome[0] = result.message; return 0; }
            }
            case "gift" -> {
                if (player.getInventory().countItem(Items.EMERALD) < 1) {
                    player.sendSystemMessage(Component.literal("§eДля подарка нужен изумруд."));
                    return 0;
                }
                removeMatching(player, Items.EMERALD, 1);
                int score = ResidentSocial.adjust(person, player.getUUID().toString(), 10);
                player.sendSystemMessage(Component.literal("§a" + person.name
                        + " благодарит за подарок. Отношение: " + score + "/100."));
            }
            case "follow" -> {
                if (!ruler) { deny(player); return 0; }
                data.manualFollow = true;
                data.manualFollowOwner = player.getUUID().toString();
                data.manualOrder = false;
                data.assignedProfession = "";
                data.manualHauler = false;
                data.lifeAction = "";
                data.building = 0;
                data.orderQueue.clear();
                data.status = "следует за правителем";
                player.sendSystemMessage(Component.literal("§a" + person.name + " следует за вами."));
            }
            case "stop" -> {
                if (!ruler) { deny(player); return 0; }
                colony.s.holdAt(data.id, data.x, data.z);
                data.manualFollow = false;
                data.status = "удерживает позицию";
                player.sendSystemMessage(Component.literal("§e" + person.name + " остановился."));
            }
            case "work" -> {
                if (!ruler) { deny(player); return 0; }
                int assigned = assignCompatibleBuilding(colony, data, null);
                if (assigned <= 0) {
                    outcome[0] = "Подходящего свободного рабочего места для профессии " + data.profession + " нет.";
                    player.sendSystemMessage(Component.literal("§e" + outcome[0]));
                    return 2;
                }
                data.manualFollow = false;
                colony.workOrders.remove(data.id);
                player.sendSystemMessage(Component.literal("§a" + person.name + " назначен на подходящую работу в здании #" + assigned));
            }
            case "work_builder", "work_lumberjack", "work_miner", "work_farmer", "work_hauler" -> {
                if (!ruler) { deny(player); return 0; }
                String role = action.substring("work_".length());
                if (!data.canPerform(role)) {
                    player.sendSystemMessage(Component.literal("§c" + person.name + " не обучен работе: " + role
                            + ". Нужен соответствующий специалист или универсальный рабочий."));
                    return 0;
                }
                int assigned = assignCompatibleBuilding(colony, data, role);
                if (assigned <= 0) {
                    outcome[0] = "Нет готового здания или свободного места для роли «" + role + "». Ищу совместимую альтернативу.";
                    player.sendSystemMessage(Component.literal("§e" + outcome[0]));
                    return 2;
                }
                data.manualFollow = false;
                colony.workOrders.remove(data.id);
                player.sendSystemMessage(Component.literal("§aПоручение принято: " + person.name + " назначен на роль «" + role
                        + "» в здании #" + assigned + "."));
            }
            case "work_area_resume" -> {
                if (!ruler) { deny(player); return 0; }
                Colony.WorkOrder order = colony.workOrders.get(data.id);
                if (order == null) {
                    player.sendSystemMessage(Component.literal("§eУ этого жителя нет назначенного участка. Выделите его и задайте участок в режиме RTS."));
                    return 0;
                }
                order.complete = false;
                order.cursor = 0;
                data.manualFollow = false;
                data.manualOrder = false;
                data.status = "возвращается к поручению на участке";
                player.sendSystemMessage(Component.literal("§a" + person.name + " возвращается к поручению: " + order.mode
                        + " · область " + (order.halfX * 2 + 1) + "×" + (order.halfZ * 2 + 1) + "."));
            }
            case "work_area_cancel" -> {
                if (!ruler) { deny(player); return 0; }
                Colony.WorkOrder removed = colony.workOrders.remove(data.id);
                if (removed == null) {
                    outcome[0] = "У этого жителя нет активного поручения по участку.";
                    player.sendSystemMessage(Component.literal("§e" + outcome[0]));
                    return 0;
                }
                player.sendSystemMessage(Component.literal("§aПоручение по участку отменено. " + person.name + " снова свободен для других задач."));
            }
            case "quest" -> {
                if (person.props.containsKey(QUEST_KEY) && quest(person) == null) {
                    player.sendSystemMessage(Component.literal("§cСуществующая запись поручения повреждена; новое поручение не создано."));
                    return 0;
                }
                if (quest(person) != null && quest(person).status() != ResidentQuest.Status.ABANDONED) {
                    player.sendSystemMessage(Component.literal("У этого жителя уже есть поручение."));
                    return 0;
                }
                String resource = switch (data.profession) {
                    case "lumberjack", "sawyer" -> "wood";
                    case "miner", "builder" -> "stone";
                    case "smith", "blacksmith", "armorer" -> "iron";
                    case "farmer" -> "food";
                    default -> "food";
                };
                int required = "iron".equals(resource) ? 2 : "stone".equals(resource) || "wood".equals(resource) ? 4 : 3;
                String resourceName = colony.s.defs.resourceNames.getOrDefault(resource, resource);
                ResidentQuest next = new ResidentQuest("supply_" + resource + "_" + person.id + "_" + colony.s.year(),
                        "Доставить " + required + " ед. ресурса «" + resourceName + "» в поселение",
                        resource, required, colony.s.year());
                person.props.put(QUEST_KEY, next.toMap());
                player.sendSystemMessage(Component.literal("§e" + person.name + ": Нам нужны " + required
                        + " ед. ресурса «" + resourceName + "». Поможете доставить?"));
            }
            case "abandon" -> {
                ResidentQuest q = quest(person);
                if (q == null || !q.abandon()) {
                    player.sendSystemMessage(Component.literal("§eЭто поручение уже нельзя отменить."));
                    return 0;
                }
                saveQuest(person, q);
                player.sendSystemMessage(Component.literal("§7Поручение отменено. Житель может предложить новое позже."));
            }
            case "accept" -> {
                ResidentQuest q = quest(person);
                if (q == null || !q.accept()) {
                    player.sendSystemMessage(Component.literal("Это поручение уже нельзя принять."));
                    return 0;
                }
                saveQuest(person, q);
                player.sendSystemMessage(Component.literal("§aПоручение принято: " + q.objective() + ". Вернитесь ко мне с грузом."));
            }
            case "deliver" -> {
                if (deliver(player, colony, person, outcome) == 0) return 0;
            }
            default -> {
                player.sendSystemMessage(Component.literal("Неизвестная команда жителя."));
                return 0;
            }
        }
        open(player, worker);
        return 1;
    }

    private static int assignCompatibleBuilding(Colony colony, Worker worker, String requestedRole) {
        for (var building : colony.s.buildings) {
            boolean construction = building.state == dev.worldforge.settle.Building.State.BUILDING;
            boolean complete = building.state == dev.worldforge.settle.Building.State.DONE;
            boolean compatible;
            if (requestedRole != null && !requestedRole.isBlank()) {
                compatible = "builder".equals(requestedRole)
                        ? construction && worker.canPerform("builder")
                        : complete && building.def.jobs.containsKey(requestedRole) && worker.canPerform(requestedRole);
            } else {
                compatible = complete && building.def.jobs.keySet().stream().anyMatch(worker::canPerform);
                compatible |= construction && worker.canPerform("builder");
            }
            if (!compatible) continue;
            try {
                colony.s.assign(worker.id, building.uid, requestedRole);
                return building.uid;
            } catch (RuntimeException ignored) { /* try next compatible job slot */ }
        }
        return 0;
    }

    private static int deliver(ServerPlayer player, Colony colony, Character person, String[] outcome) {
        ResidentQuest q = quest(person);
        if (q == null || q.status() != ResidentQuest.Status.ACCEPTED) {
            outcome[0] = "Сначала примите поручение жителя.";
            player.sendSystemMessage(Component.literal("§e" + outcome[0]));
            return 0;
        }
        if (!colony.s.defs.resourceNames.containsKey(q.resourceId())) {
            outcome[0] = "Ресурс этого поручения отсутствует в экономике поселения; запись сохранена.";
            player.sendSystemMessage(Component.literal("§c" + outcome[0]));
            return 0;
        }
        net.minecraft.world.item.Item item = questItem(q.resourceId());
        int available = player.getInventory().countItem(item);
        int needed = q.required() - q.progress();
        int offered = Math.min(available, needed);
        if (offered <= 0) {
            outcome[0] = "У вас нет ресурса «" + colony.s.defs.resourceNames.getOrDefault(q.resourceId(), q.resourceId()) + "» для передачи.";
            player.sendSystemMessage(Component.literal("§e" + outcome[0]));
            return 0;
        }
        double accepted = colony.s.receiveResource(q.resourceId(), offered);
        int delivered = Math.min(offered, (int) Math.floor(accepted + 1e-9));
        if (accepted > delivered) colony.s.consumeResource(q.resourceId(), accepted - delivered);
        if (delivered <= 0) {
            outcome[0] = "Склад поселения не может принять этот ресурс.";
            player.sendSystemMessage(Component.literal("§c" + outcome[0]));
            return 0;
        }
        int acceptedWhole = delivered;
        delivered = removeMatching(player, item, acceptedWhole);
        if (delivered < acceptedWhole) colony.s.consumeResource(q.resourceId(), acceptedWhole - delivered);
        if (delivered <= 0) {
            outcome[0] = "Не удалось передать ресурс.";
            player.sendSystemMessage(Component.literal("§c" + outcome[0]));
            return 0;
        }
        boolean completed = q.addProgress(delivered, colony.s.year());
        saveQuest(person, q);
        if (completed) {
            colony.s.add("silver", 2.0);
            player.sendSystemMessage(Component.literal("§aПоручение выполнено! Ресурс доставлен, в казну поселения зачислено 2 серебра."));
        } else {
            player.sendSystemMessage(Component.literal("§aПередано ресурсов: " + delivered + ". Осталось: "
                    + (q.required() - q.progress()) + "."));
        }
        outcome[0] = completed ? "Поручение жителя выполнено" : "Ресурсы доставлены: " + delivered;
        return 1;
    }

    /** Remove an exact item count by scanning inventory slots; Minecraft Inventory has no removeItem(Item, count) overload. */
    private static int removeMatching(ServerPlayer player, Item item, int amount) {
        if (amount <= 0) return 0;
        var inventory = player.getInventory();
        int remaining = amount;
        int removed = 0;
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.is(item)) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            if (stack.isEmpty()) inventory.setItem(slot, ItemStack.EMPTY);
            remaining -= take;
            removed += take;
        }
        if (removed > 0) inventory.setChanged();
        return removed;
    }

    private static net.minecraft.world.item.Item questItem(String resource) {
        return switch (resource) {
            case "wood" -> Items.OAK_LOG;
            case "stone" -> Items.COBBLESTONE;
            case "iron" -> Items.IRON_INGOT;
            case "food" -> Items.BREAD;
            default -> Items.BREAD;
        };
    }

    @SuppressWarnings("unchecked")
    private static ResidentQuest quest(Character person) {
        Object raw = person.props.get(QUEST_KEY);
        if (!(raw instanceof Map<?, ?> map)) return null;
        try { return ResidentQuest.fromMap(map); }
        catch (RuntimeException ex) {
            return null; // retain malformed persisted data; never silently erase a resident's contract
        }
    }

    private static void saveQuest(Character person, ResidentQuest quest) {
        person.props.put(QUEST_KEY, new LinkedHashMap<>(quest.toMap()));
    }

    private static String relationship(Character person, ServerPlayer player) {
        int score = ResidentSocial.score(person, player.getUUID().toString());
        return score + "/100 · " + ResidentSocial.bond(person, player.getUUID().toString());
    }

    private static String family(Character person) {
        java.util.List<String> links = new java.util.ArrayList<>();
        if (person.spouse != null && !person.spouse.isBlank()) {
            Object spouseName = person.props.get("family.spouse_name");
            links.add("супруг: " + (spouseName instanceof String name ? name : person.spouse));
        }
        Object dating = person.props.get("family.dating_name");
        if (dating instanceof String name && !name.isBlank()) links.add("встречается: " + name);
        if (person.children.size() > 0) links.add("детей: " + person.children.size());
        if (person.father != null && !person.father.isBlank()) {
            Object name = person.props.get("family.father_name");
            links.add("отец: " + (name instanceof String value ? value : person.father));
        }
        if (person.mother != null && !person.mother.isBlank()) {
            Object name = person.props.get("family.mother_name");
            links.add("мать: " + (name instanceof String value ? value : person.mother));
        }
        return links.isEmpty() ? "не указана" : String.join(" · ", links);
    }

    private static void deny(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("§cУправлять работой этого жителя может только правитель поселения."));
    }

    private static final class WorkerControlAccess {
        static boolean mayControl(ServerPlayer player, Colony colony) {
            return player.hasPermissions(2) || player.server.isSingleplayerOwner(player.getGameProfile())
                    || (colony != null && player.getUUID().toString().equals(colony.owner));
        }
    }
}
