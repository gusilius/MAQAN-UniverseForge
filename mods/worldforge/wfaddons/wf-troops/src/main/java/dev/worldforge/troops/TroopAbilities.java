package dev.worldforge.troops;

import java.util.*;

/**
 * Каталог тактических умений. Описывает доступность и тип цели; исполнение эффекта
 * остаётся за игровыми системами, чтобы UI не мог выдавать умение за выполненное действие.
 */
public final class TroopAbilities {
    private TroopAbilities() {}

    public enum Target { SELF, FRIENDLY_UNIT, ENEMY_UNIT, AREA, FORMATION }

    public record Ability(String id, String title, String description, Target target,
                          String role, int minimumVeteranRank, boolean commanderOnly) {
        public Ability {
            if (id == null || id.isBlank() || title == null || title.isBlank() || target == null
                    || role == null || minimumVeteranRank < 0 || minimumVeteranRank > 4)
                throw new IllegalArgumentException("Некорректное описание способности");
        }
    }

    /** eligibleCount позволяет UI показать групповое умение и число бойцов, способных его выполнить. */
    public record Offer(Ability ability, int eligibleCount, int selectedCount) {
        public Offer {
            if (ability == null || selectedCount < 1 || eligibleCount < 0 || eligibleCount > selectedCount)
                throw new IllegalArgumentException("Некорректное предложение способности");
        }
        public boolean availableForWholeSelection() { return eligibleCount == selectedCount; }
    }

    private static final List<Ability> CATALOG = List.of(
            new Ability("shield_wall", "Щитовая стена", "Сомкнуть щиты и удерживать фронт.", Target.FORMATION, "melee", 0, false),
            new Ability("brace", "Упор против конницы", "Подготовить копья и удерживать позицию.", Target.FORMATION, "melee", 1, false),
            new Ability("charge", "Натиск", "Атаковать выбранную вражескую цель.", Target.ENEMY_UNIT, "melee", 2, false),
            new Ability("volley", "Залп", "Сосредоточенный залп по области.", Target.AREA, "ranged", 1, false),
            new Ability("precision_shot", "Прицельный выстрел", "Выстрел по выбранной цели.", Target.ENEMY_UNIT, "ranged", 2, false),
            new Ability("cavalry_charge", "Кавалерийский удар", "Атаковать цель строевым натиском.", Target.ENEMY_UNIT, "cavalry", 1, false),
            new Ability("screen", "Прикрытие", "Развернуть конницу для прикрытия фланга.", Target.FORMATION, "cavalry", 2, false),
            new Ability("field_treatment", "Первая помощь", "Оказать помощь выбранному союзнику.", Target.FRIENDLY_UNIT, "support", 0, false),
            new Ability("rally", "Воодушевление", "Поднять боевой дух союзников поблизости.", Target.AREA, "support", 2, false),
            new Ability("bombard", "Осадный залп", "Обстрелять выбранную область.", Target.AREA, "siege", 0, false),
            new Ability("deploy_engine", "Развернуть орудие", "Подготовить осадное орудие к работе.", Target.SELF, "siege", 1, false),
            new Ability("command_rally", "Командирский сбор", "Собрать подчинённые отряды.", Target.AREA, "", 0, true),
            new Ability("tactical_march", "Тактический марш", "Согласованное перемещение подразделения.", Target.FORMATION, "", 1, true),
            new Ability("hold_the_line", "Держать строй", "Повысить устойчивость строя и дисциплину.", Target.FORMATION, "", 2, true)
    );

    public static List<Ability> catalog() { return CATALOG; }

    public static List<Ability> forUnit(TroopDefs.Unit unit, int veteranRank, boolean appointedCommander) {
        if (unit == null) return List.of();
        int rank = Math.max(0, Math.min(4, veteranRank));
        return CATALOG.stream()
                .filter(a -> !a.commanderOnly()
                        ? a.role().equals(unit.role()) && rank >= a.minimumVeteranRank()
                        : appointedCommander && rank >= a.minimumVeteranRank())
                .toList();
    }

    public static List<Offer> forGroup(List<TroopDefs.Unit> units, List<Integer> ranks, boolean hasCommander) {
        if (units == null || units.isEmpty() || ranks == null || ranks.size() != units.size()) return List.of();
        List<Offer> offers = new ArrayList<>();
        for (Ability ability : CATALOG) {
            int eligible = 0;
            for (int i = 0; i < units.size(); i++) {
                TroopDefs.Unit unit = units.get(i);
                if (unit != null && forUnit(unit, ranks.get(i) == null ? 0 : ranks.get(i), hasCommander)
                        .stream().anyMatch(candidate -> candidate.id().equals(ability.id()))) eligible++;
            }
            if (eligible > 0) offers.add(new Offer(ability, eligible, units.size()));
        }
        return List.copyOf(offers);
    }
}
