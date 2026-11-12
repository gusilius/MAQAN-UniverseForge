package dev.worldforge.tests;

import dev.worldforge.people.NameBank;
import java.util.*;

public final class NameBankTest {
    private static int failures;
    private static void check(boolean value, String message) {
        if (!value) { failures++; System.out.println("FAIL " + message); }
        else System.out.println("ok   " + message);
    }

    public static void main(String[] args) {
        NameBank bank = new NameBank();
        bank.add("""
                {"default":"northern",
                 "cultures":{"northern":{"male":["Эйрик"],"female":["Астрид"],"houses":["Воронья Кровь"]},
                             "dwarf":{"male":["Торин"],"female":["Диса"],"houses":["Каменный Молот"]}},
                 "races":{"dwarf":{"male":["Брунар"],"female":["Бринья"],"houses":["Глубокая Кузня"],
                                    "settlements":["Кхазад-Рун"],"realms":["Подгорная Корона"]},
                          "deep_dwarf":{"male":["Двалин"],"female":["Груна"],"houses":["Чёрный Кремень"]}},
                 "race_parents":{"deep_dwarf":"dwarf"}}
                """);
        NameBank.Pool deep = bank.poolForRace("deep_dwarf", "dwarf");
        check(deep.male.containsAll(List.of("Торин", "Брунар", "Двалин")),
                "subrace inherits parent and culture given names");
        check(deep.male.size() == 3, "inherited pools merge without duplicate weighting");
        check(deep.houses.containsAll(List.of("Каменный Молот", "Глубокая Кузня", "Чёрный Кремень")),
                "house names inherit through the race lineage");
        check("Подгорная Корона".equals(bank.generate("deep_dwarf", "dwarf", "realms", new Random(1))),
                "realm naming uses the race-specific data pack pool");
        check("Кхазад-Рун".equals(bank.generate("deep_dwarf", "dwarf", "settlements", new Random(1))),
                "settlement naming uses the race-specific data pack pool");
        check("Воронья Кровь".equals(bank.generate("unknown", "unknown", "houses", new Random(1))),
                "unknown cultures fall back to the configured default pool");
        check(bank.pickGivenName("deep_dwarf", "dwarf", "m", new Random(7)) != null,
                "deterministic given-name generation is available to Character creation");
        boolean missingRejected = false;
        try { bank.generate("deep_dwarf", "dwarf", "unknown_category", new Random(1)); }
        catch (IllegalArgumentException expected) { missingRejected = true; }
        check(missingRejected, "unknown name categories fail clearly instead of inventing unrelated names");
        if (failures > 0) throw new AssertionError(failures + " name bank tests failed");
        System.out.println("NameBankTest: OK");
    }
}
