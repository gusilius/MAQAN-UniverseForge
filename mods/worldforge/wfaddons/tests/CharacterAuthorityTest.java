package dev.worldforge.tests;

import dev.worldforge.mod.identity.WorldId;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterAuthority;
import dev.worldforge.people.CharacterIdentity;
import dev.worldforge.people.CharacterRegistry;

import java.util.UUID;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Modifier;

/** Global character authority collision and legacy-lookup tests. */
public final class CharacterAuthorityTest {
    private static int failures;
    private static void check(boolean value, String label) {
        System.out.println((value ? "ok   " : "FAIL ") + label);
        if (!value) failures++;
    }

    private static boolean authorityIdentityIndexStoresOnlyKeys() {
        try {
            Field field = CharacterAuthority.class.getDeclaredField("byIdentity");
            if (!java.util.Set.class.isAssignableFrom(field.getType())) return false;
            if (!(field.getGenericType() instanceof ParameterizedType generic)) return false;
            String elementType = generic.getActualTypeArguments()[0].getTypeName();
            return elementType.equals("dev.worldforge.people.CharacterIdentity");
        } catch (ReflectiveOperationException ex) {
            return false;
        }
    }

    public static void main(String[] args) {
        check(authorityIdentityIndexStoresOnlyKeys(),
                "global authority retains compact identity keys rather than a second Character object graph");
        CharacterAuthority authority = new CharacterAuthority();
        WorldId world = new WorldId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
        authority.beginWorld(world);
        CharacterRegistry north = new CharacterRegistry();
        CharacterRegistry south = new CharacterRegistry();
        Character a = north.create("Aldric", "", "", "m", 0);
        Character b = south.create("Beatrice", "", "", "f", 0);
        check(a.id.equals("c1") && b.id.equals("c1"), "legacy per-map IDs remain unchanged during migration");
        check(!a.globalId.equals(b.globalId), "each new Character receives a unique immutable global UUID");
        CharacterRegistry officeRegistry = new CharacterRegistry();
        Character ruler = officeRegistry.create("Ruler", "House A", "", "m", 0);
        Character marshal = officeRegistry.create("Marshal", "House B", "", "m", 0);
        Character clerk = officeRegistry.create("Clerk", "", "", "f", 0);
        officeRegistry.appoint(ruler.id, "ruler", "realm:test");
        officeRegistry.appoint(marshal.id, "marshal", "realm:test");
        officeRegistry.appoint(clerk.id, "marshal", "realm:other");
        check(officeRegistry.holders("ruler", "realm:test").size() == 1
                        && officeRegistry.holders("marshal", "realm:test").size() == 1
                        && officeRegistry.holdersAtPlace("realm:test").size() == 2,
                "role/place and place-only reverse indexes return exact living office holders");
        officeRegistry.dismiss(marshal.id, "marshal", "realm:test");
        check(officeRegistry.holders("marshal", "realm:test").isEmpty()
                        && officeRegistry.holdersAtPlace("realm:test").size() == 1,
                "dismissal removes the character from both reverse office indexes");
        String officeSave = officeRegistry.toJson();
        CharacterRegistry restoredOffices = new CharacterRegistry();
        restoredOffices.fromJson(officeSave, world + "|office-index");
        check(restoredOffices.holders("ruler", "realm:test").size() == 1
                        && restoredOffices.holders("marshal", "realm:other").size() == 1
                        && restoredOffices.holdersAtPlace("realm:test").size() == 1,
                "office reverse indexes are reconstructed from persisted posts");

        authority.registerMap("north", north);
        authority.registerMap("south", south);
        check(authority.size() == 2 && authority.mapCount() == 2, "global authority indexes both map partitions");
        var firstIdentitySnapshot = authority.identities();
        check(firstIdentitySnapshot == authority.identities(),
                "sorted global identity snapshot is cached while registry membership is unchanged");
        CharacterIdentity aIdentity = new CharacterIdentity(world, "north", "c1", a.globalId);
        CharacterIdentity bIdentity = new CharacterIdentity(world, "south", "c1", b.globalId);
        check(authority.get(aIdentity) == a && authority.get(bIdentity) == b,
                "qualified identity resolves colliding legacy IDs");
        check(authority.getGlobal(a.globalId) == a && authority.registryForGlobal(b.globalId) == south,
                "global UUID resolves person and owning map registry");
        check(authority.getLegacy("c1") == null, "ambiguous bare legacy ID fails closed");
        check(authority.bindEntity(a.globalId, "npc", "shared-physical-entity"),
                "global authority binds a body to its canonical Character");
        check(!authority.bindEntity(b.globalId, "troop", "shared-physical-entity"),
                "one physical entity UUID cannot be attached to two Characters across map partitions");
        check(authority.bindEntity(b.globalId, "troop", "another-physical-entity"),
                "a distinct physical body can bind to a different map-owned Character");
        check(authority.bindEntity(a.globalId, "portrait", "shared-physical-entity"),
                "one Character may expose the same canonical body UUID through multiple body projections");
        boolean registryAliasRejected = false;
        try {
            authority.registerMap("north-alias", north);
        } catch (IllegalStateException expected) {
            registryAliasRejected = expected.getMessage().contains("already registered as map north");
        }
        check(registryAliasRejected && authority.mapCount() == 2
                        && authority.getGlobal(a.globalId) == a,
                "one Character persistence partition cannot be aliased under two map IDs");
        Character late = north.create("Late arrival", "", "", "m", 1);
        check(authority.getGlobal(late.globalId) == late,
                "characters created after map registration are indexed without a second registration");
        check(firstIdentitySnapshot != authority.identities() && authority.identities().size() == 3,
                "identity snapshot cache is invalidated when a registered partition gains a Character");
        Character lateChild = north.addChild(a, b, "Late descendant", "m", 2);
        check(authority.getGlobal(lateChild.globalId) == lateChild
                        && authority.kinCandidates(lateChild).contains(a)
                        && authority.kinCandidates(a).contains(lateChild)
                        && authority.kinCandidates(b).contains(lateChild),
                "append-only births update global identity and succession candidates without rebuilding the world index");

        // Legacy cN identifiers collide across map partitions. Once global parent links
        // exist, an unrelated c1 from a third map must never enter the child's kin set.
        CharacterAuthority familyAuthority = new CharacterAuthority();
        familyAuthority.beginWorld(world);
        CharacterRegistry familyFatherRegistry = new CharacterRegistry();
        CharacterRegistry familyMotherRegistry = new CharacterRegistry();
        CharacterRegistry unrelatedRegistry = new CharacterRegistry();
        CharacterRegistry familyChildRegistry = new CharacterRegistry();
        Character familyFather = familyFatherRegistry.create("Family father", "", "", "m", 0);
        Character familyMother = familyMotherRegistry.create("Family mother", "", "", "f", 0);
        Character unrelatedC1 = unrelatedRegistry.create("Unrelated c1", "", "", "m", 0);
        familyAuthority.registerMap("family-father", familyFatherRegistry);
        familyAuthority.registerMap("family-mother", familyMotherRegistry);
        familyAuthority.registerMap("unrelated", unrelatedRegistry);
        familyAuthority.registerMap("family-child", familyChildRegistry);
        Character familyChild = familyChildRegistry.addChild(
                familyFather, familyMother, "Family child", "m", 1);
        var familyCandidates = familyAuthority.kinCandidates(familyChild);
        check(familyFather.id.equals("c1") && familyMother.id.equals("c1")
                        && unrelatedC1.id.equals("c1")
                        && familyCandidates.contains(familyFather)
                        && familyCandidates.contains(familyMother)
                        && !familyCandidates.contains(unrelatedC1),
                "canonical global parent UUIDs prevent false relatives from colliding legacy IDs across maps");

        CharacterRegistry legacyFamilyRegistry = new CharacterRegistry();
        Character legacyFather = legacyFamilyRegistry.create("Legacy father", "", "", "m", 0);
        Character legacyMother = legacyFamilyRegistry.create("Legacy mother", "", "", "f", 0);
        Character legacyChild = legacyFamilyRegistry.create("Legacy child", "", "", "m", 1);
        legacyChild.father = legacyFather.id;
        legacyChild.mother = legacyMother.id;
        legacyFather.children.add(legacyChild.id);
        legacyMother.children.add(legacyChild.id);
        CharacterAuthority legacyFamilyAuthority = new CharacterAuthority();
        legacyFamilyAuthority.beginWorld(world);
        CharacterRegistry collidingLegacyRegistry = new CharacterRegistry();
        Character collidingLegacy = collidingLegacyRegistry.create("Other map c1", "", "", "m", 0);
        legacyFamilyAuthority.registerMap("legacy-family", legacyFamilyRegistry);
        legacyFamilyAuthority.registerMap("legacy-collision", collidingLegacyRegistry);
        var legacyKin = legacyFamilyAuthority.kinCandidates(legacyChild);
        check(legacyKin.contains(legacyFather) && legacyKin.contains(legacyMother)
                        && !legacyKin.contains(collidingLegacy),
                "legacy-only parent and child links remain local to their map when cN IDs collide");

        CharacterRegistry legacyMarriageRegistry = new CharacterRegistry();
        Character legacyPartner = legacyMarriageRegistry.create("Legacy partner", "", "", "f", 0);
        Character legacySpouse = legacyMarriageRegistry.create("Legacy spouse", "", "", "m", 0);
        legacySpouse.spouse = legacyPartner.id; // old one-sided spouse pointer
        CharacterAuthority legacyMarriageAuthority = new CharacterAuthority();
        legacyMarriageAuthority.beginWorld(world);
        legacyMarriageAuthority.registerMap("legacy-marriage", legacyMarriageRegistry);
        check(legacyMarriageAuthority.kinCandidates(legacyPartner).contains(legacySpouse),
                "one-sided legacy spouse links remain discoverable through the reverse family index");


        CharacterRegistry reload = new CharacterRegistry();
        Character beforeReload = reload.create("Before reload", "", "", "m", 1);
        authority.registerMap("reload", reload);
        CharacterRegistry replacement = new CharacterRegistry();
        Character afterReload = replacement.create("After reload", "", "", "m", 2);
        Object previousCanonicalStore;
        try {
            Field canonicalStore = CharacterRegistry.class.getDeclaredField("byId");
            canonicalStore.setAccessible(true);
            previousCanonicalStore = canonicalStore.get(reload);
            boolean replaceable = !Modifier.isFinal(canonicalStore.getModifiers());
            reload.fromJson(replacement.toJson(), world + "|reload");
            check(replaceable && canonicalStore.get(reload) != previousCanonicalStore,
                    "validated registry restore atomically adopts parsed canonical map without copying all map nodes");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not inspect canonical registry restore", failure);
        }
        check(authority.getGlobal(beforeReload.globalId) == null
                        && authority.getGlobal(afterReload.globalId) == reload.get(afterReload.id),
                "global identity index refreshes after a same-size registry replacement");

        CharacterRegistry players = new CharacterRegistry();
        Character player = players.create("PlayerOne", "", "human", "m", 80);
        player.props.put("genesis.player_uuid", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        player.props.put("genesis.initialized", true);
        String playerSave = players.toJson();
        CharacterRegistry restoredPlayers = new CharacterRegistry();
        restoredPlayers.fromJson(playerSave, world + "|__players__");
        authority.registerMap("__players__", restoredPlayers);
        Character restoredPlayer = restoredPlayers.all().iterator().next();
        check(restoredPlayer.globalId.equals(player.globalId)
                        && "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa".equals(restoredPlayer.props.get("genesis.player_uuid"))
                        && authority.getGlobal(player.globalId) == restoredPlayer,
                "Genesis player identity and player UUID survive registry restart without duplication");

        CharacterRegistry duplicate = new CharacterRegistry();
        Character clone = duplicate.create("Clone", "", "", "m", 0);
        String duplicateSave = duplicate.toJson().replace(clone.globalId, a.globalId);
        duplicate.fromJson(duplicateSave, world + "|duplicate");
        boolean duplicateRejected = false;
        try {
            authority.registerMap("duplicate", duplicate);
        } catch (IllegalStateException expected) {
            duplicateRejected = expected.getMessage().contains("Global Character UUID collision");
        }
        check(duplicateRejected, "registration rejects a real duplicate global UUID from another map");
        check(authority.getGlobal(a.globalId) == a
                        && authority.getGlobal(late.globalId) == late
                        && authority.size() == 6
                        && authority.mapCount() == 4,
                "failed map registration preserves every previously indexed Character and registry");
        authority.beginWorld(world);
        check(authority.getGlobal(a.globalId) == a && authority.size() == 6 && authority.mapCount() == 4,
                "re-entering the same WorldId preserves already registered Character partitions");

        authority.clear();
        check(authority.size() == 0 && authority.worldId() == null, "world shutdown clears authority index");
        CharacterAuthority nextWorld = new CharacterAuthority();
        nextWorld.beginWorld(new WorldId(UUID.fromString("44444444-4444-4444-8444-444444444444")));
        nextWorld.registerMap("north", north);
        check(nextWorld.getGlobal(a.globalId) == a && nextWorld.registry("north") == north,
                "cleared registry can be rebound to a new world authority without stale ownership");
        CharacterAuthority wrongWorld = new CharacterAuthority();
        wrongWorld.beginWorld(new WorldId(UUID.fromString("55555555-5555-4555-8555-555555555555")));
        boolean alreadyAttachedRejected = false;
        try {
            wrongWorld.registerMap("north", north);
        } catch (IllegalStateException expected) {
            alreadyAttachedRejected = expected.getMessage().contains("already attached");
        }
        check(alreadyAttachedRejected && wrongWorld.mapCount() == 0
                        && nextWorld.getGlobal(a.globalId) == a,
                "failed cross-world registration leaves no stale partition in the rejected authority");
        wrongWorld.clear();
        nextWorld.clear();
        if (failures > 0) throw new AssertionError(failures + " CharacterAuthority tests failed");
        System.out.println("CharacterAuthority: ALL OK");
    }
}
