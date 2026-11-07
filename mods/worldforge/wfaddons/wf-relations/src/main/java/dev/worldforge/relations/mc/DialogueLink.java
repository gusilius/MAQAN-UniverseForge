package dev.worldforge.relations.mc;

import dev.worldforge.people.mc.PeopleMod;
import dev.worldforge.relations.bridge.RelationsBridge;

/** Мягкий мост к wf-dialogue. Трогается ТОЛЬКО если ModList.isLoaded("wf_dialogue"). */
final class DialogueLink {
    private DialogueLink() {}

    static void register() {
        RelationsBridge.relations = RelationsMc.REL;
        RelationsBridge.npcs = PeopleMod::findNpc;
        RelationsBridge.today = RelationsMc::dayNumber;
        RelationsBridge.register();
    }
}
