package dev.worldforge.tests;

import dev.worldforge.mod.MapData;
import dev.worldforge.mod.TitleData;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.realms.PoliticalIdentitySynchronizer;
import dev.worldforge.realms.Statecraft;

import java.util.*;

/** Exercises the actual map-title import and the shared ruler-to-title identity bridge. */
public final class PoliticalIdentitySynchronizerTest {
    private static int failures;
    private static void check(boolean condition, String message) {
        System.out.println((condition ? "ok   " : "FAIL ") + message);
        if (!condition) failures++;
    }

    public static void main(String[] args) throws Exception {
        MapData map = MapData.load("identity-sync-test", name -> {
            try (var zip = new java.util.zip.ZipFile(args[0])) {
                var entry = zip.getEntry("data/planets/worldforge_maps/game_of_thrones/" + name);
                return entry == null ? null : zip.getInputStream(entry).readAllBytes();
            }
        });
        TitleData titles = map.titles;
        if (titles == null || titles.realmList().isEmpty()) throw new AssertionError("test map has no realms");
        TitleData.Realm realm = titles.realmList().get(0);
        if (realm.titleKeys.isEmpty()) throw new AssertionError("test realm has no root title");

        CharacterRegistry people = new CharacterRegistry();
        Character ruler = people.create("Ruler One", "House Test", "human", "m", 250);
        realm.props.put("rulerCharacterId", ruler.globalId);
        Statecraft politics = new Statecraft();
        int imported = politics.syncMapTitles(map.name, titles);
        int repeatedImport = politics.syncMapTitles(map.name, titles);
        check(imported > 0 && repeatedImport == 0 && politics.titles().size() == imported,
                "Statecraft title import is stable and idempotent");

        PoliticalIdentitySynchronizer.Report first =
                PoliticalIdentitySynchronizer.synchronizeRulers(map.name, titles, people, politics);
        TitleData.Title crown = titles.byKey(realm.titleKeys.stream().sorted().findFirst().orElseThrow());
        // The realm's root title is the highest tier among its direct title keys.
        for (String key : realm.titleKeys) {
            TitleData.Title candidate = titles.byKey(key);
            if (candidate != null && crown != null && candidate.tier > crown.tier) crown = candidate;
        }
        String titleId = Statecraft.mapTitleId(map.name, crown.key);
        check(first.linked() == 1 && politics.title(titleId).holderCharacterId.equals(ruler.globalId),
                "map rulerCharacterId is linked to the canonical Statecraft crown holder");
        PoliticalIdentitySynchronizer.Report repeated =
                PoliticalIdentitySynchronizer.synchronizeRulers(map.name, titles, people, politics);
        check(repeated.linked() == 0 && repeated.alreadyLinked() == 1,
                "repeated ruler synchronization does not create or replace title holders");

        people.kill(ruler.id);
        Character successor = people.create("Ruler Successor", "House Test", "human", "m", 275);
        realm.props.put("rulerCharacterId", successor.globalId);
        PoliticalIdentitySynchronizer.Report succession =
                PoliticalIdentitySynchronizer.synchronizeRulers(map.name, titles, people, politics);
        check(succession.linked() == 1 && politics.title(titleId).holderCharacterId.equals(successor.globalId),
                "canonical court succession replaces a deceased crown holder with the living heir");

        Character rival = people.create("Rival", "House Rival", "human", "m", 248);
        politics.grantTitle(titleId, rival.globalId);
        PoliticalIdentitySynchronizer.Report conflict =
                PoliticalIdentitySynchronizer.synchronizeRulers(map.name, titles, people, politics);
        check(!conflict.problems().isEmpty() && politics.title(titleId).holderCharacterId.equals(rival.globalId),
                "conflicting existing title holder is reported and never silently overwritten");

        CharacterRegistry duplicateProbe = new CharacterRegistry();
        Character a = duplicateProbe.create("A", "House A", "human", "m", 220);
        Character b = duplicateProbe.create("B", "House B", "human", "m", 221);
        String duplicateJson = duplicateProbe.toJson().replace(b.globalId, a.globalId);
        boolean duplicateRejected = false;
        try { new CharacterRegistry().fromJson(duplicateJson, "duplicate-id-test"); }
        catch (IllegalArgumentException expected) { duplicateRejected = true; }
        check(duplicateRejected, "CharacterRegistry rejects two independent records with one global Character ID");

        System.out.println("Political identity synchronization: titles=" + politics.titles().size()
                + ", characters=" + people.size() + ", linked=" + first.linked()
                + ", conflictProblems=" + conflict.problems().size());
        if (failures > 0) throw new AssertionError("Political identity synchronization failures: " + failures);
    }
}
