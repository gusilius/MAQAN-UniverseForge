package dev.worldforge.settle.mc;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.worldforge.settle.ResidentSocial;
import dev.worldforge.people.Character;
import dev.worldforge.settle.ResidentQuest;
import dev.worldforge.settle.Worker;
import dev.worldforge.people.mc.PeopleMod;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.LinkedHashMap;
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
                        new ResidentDialogueMenu(windowId, inventory, person.id, person.name, profession,
                                status, residentProfile(person), relationship(person, player), questText, age),
                        Component.literal("Диалог · " + person.name)),
                buffer -> {
                    buffer.writeUtf(person.id, 128);
                    buffer.writeUtf(person.name, 128);
                    buffer.writeUtf(profession, 128);
                    buffer.writeUtf(status, 256);
                    buffer.writeUtf(residentProfile(person), 512);
                    buffer.writeUtf(relationship(person, player), 128);
                    buffer.writeUtf(questText, 512);
                    buffer.writeVarInt(age);
                });
    }

    private static String residentProfile(Character person) {
        String traits = person.traits.isEmpty() ? "нет выраженных черт" : String.join(", ", person.traits);
        Map<String,Integer> skills = PopulationSimulation.skillRanksSnapshot(person);
        String skillText = skills.isEmpty() ? "навыки не развиты" : skills.entrySet().stream()
                .sorted(Map.Entry.<String,Integer>comparingByValue().reversed())
                .limit(4).map(e -> e.getKey() + " " + e.getValue()).collect(java.util.stream.Collectors.joining(", "));
        return family(person) + " · " + ("f".equalsIgnoreCase(person.gender) ? "женщина" : "мужчина")
                + " · культура: " + (person.culture == null || person.culture.isBlank() ? "не указана" : person.culture)
                + " · черты: " + traits + " · навыки: " + skillText;
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

    private static Component action(String label, String action, WFWorker worker) {
        String command = "/wfresident " + action + " " + worker.getUUID();
        return Component.literal("  › " + label).withStyle(Style.EMPTY
                .withColor(net.minecraft.ChatFormatting.GOLD)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }

    private static int execute(ServerPlayer player, String action, String entityId) {
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
            case "status" -> {
                player.sendSystemMessage(Component.literal("§6" + person.name + " §7· " + data.profession
                        + " · " + data.status + " · поселение " + colony.s.name));
                player.sendSystemMessage(Component.literal("§7Семья: " + family(person)
                        + " · возраст: " + Math.max(0, PeopleMod.YEAR - person.birthYear) + " лет"));
                player.sendSystemMessage(Component.literal("§7Отношение к вам: " + relationship(person, player)));
            }
            case "gift" -> {
                if (player.getInventory().countItem(Items.EMERALD) < 1) {
                    player.sendSystemMessage(Component.literal("§eДля подарка нужен изумруд."));
                    return 0;
                }
                player.getInventory().removeItem(Items.EMERALD, 1);
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
                data.manualFollow = false;
                int assigned = assignCompatibleBuilding(colony, data);
                player.sendSystemMessage(Component.literal(assigned > 0
                        ? "§a" + person.name + " назначен на работу в здание #" + assigned
                        : "§eПодходящего свободного рабочего места для профессии " + data.profession + " нет."));
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
                ResidentQuest next = new ResidentQuest("bread_supply_" + person.id,
                        "Принести 3 хлеба в поселение", 3, colony.s.year());
                person.props.put(QUEST_KEY, next.toMap());
                player.sendSystemMessage(Component.literal("§e" + person.name + ": В поселении не хватает хлеба. Принесите три буханки."));
                player.sendSystemMessage(action("Принять поручение", "accept", worker));
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
                player.sendSystemMessage(Component.literal("§aПоручение принято: принести 3 хлеба. Вернитесь ко мне с хлебом."));
            }
            case "deliver" -> deliver(player, colony, person);
            default -> {
                player.sendSystemMessage(Component.literal("Неизвестная команда жителя."));
                return 0;
            }
        }
        open(player, worker);
        return 1;
    }

    private static int assignCompatibleBuilding(Colony colony, Worker worker) {
        for (var building : colony.s.buildings) {
            boolean compatible = building.state == dev.worldforge.settle.Building.State.DONE
                    && building.def.jobs.keySet().stream().anyMatch(worker::canPerform);
            compatible |= building.state == dev.worldforge.settle.Building.State.BUILDING
                    && worker.canPerform("builder");
            if (!compatible) continue;
            try {
                colony.s.assign(worker.id, building.uid);
                return building.uid;
            } catch (RuntimeException ignored) { /* try next compatible job slot */ }
        }
        return 0;
    }

    private static void deliver(ServerPlayer player, Colony colony, Character person) {
        ResidentQuest q = quest(person);
        if (q == null || q.status() != ResidentQuest.Status.ACCEPTED) {
            player.sendSystemMessage(Component.literal("§eСначала примите поручение жителя."));
            return;
        }
        int available = player.getInventory().countItem(Items.BREAD);
        int needed = q.required() - q.progress();
        int offered = Math.min(available, needed);
        if (offered <= 0) {
            player.sendSystemMessage(Component.literal("§eУ вас нет хлеба для передачи."));
            return;
        }
        double accepted = colony.s.receiveResource("food", offered);
        int delivered = Math.min(offered, (int) Math.floor(accepted + 1e-9));
        if (accepted > delivered) colony.s.consumeResource("food", accepted - delivered);
        if (delivered <= 0) {
            player.sendSystemMessage(Component.literal("§cСклад поселения не может принять хлеб."));
            return;
        }
        int acceptedWhole = delivered;
        net.minecraft.world.item.ItemStack removed = player.getInventory().removeItem(Items.BREAD, acceptedWhole);
        delivered = Math.min(acceptedWhole, removed.getCount());
        if (delivered < acceptedWhole) colony.s.consumeResource("food", acceptedWhole - delivered);
        if (delivered <= 0) {
            player.sendSystemMessage(Component.literal("§cХлеб не удалось передать."));
            return;
        }
        boolean completed = q.addProgress(delivered, colony.s.year());
        saveQuest(person, q);
        if (completed) {
            colony.s.add("silver", 2.0);
            player.sendSystemMessage(Component.literal("§aПоручение выполнено! Хлеб доставлен, в казну поселения зачислено 2 серебра."));
        } else {
            player.sendSystemMessage(Component.literal("§aПередано хлеба: " + delivered + ". Осталось: "
                    + (q.required() - q.progress()) + "."));
        }
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
        int members = person.children.size()
                + (person.spouse == null || person.spouse.isBlank() ? 0 : 1)
                + (person.father == null || person.father.isBlank() ? 0 : 1)
                + (person.mother == null || person.mother.isBlank() ? 0 : 1);
        return members == 0 ? "не указана" : members + " близких связей";
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
