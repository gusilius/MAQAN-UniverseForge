package dev.worldforge.dialogue.mc;

import com.mojang.logging.LogUtils;
import dev.worldforge.dialogue.Dialogue;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Загруженные диалоги из датапаков data/&lt;ns&gt;/wf_dialogue/*.json. Ключ - поле id из JSON. */
public final class DialogueRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile Map<String, Dialogue> dialogues = Collections.emptyMap();

    private DialogueRegistry() {}

    public static Dialogue get(String id) { return dialogues.get(id); }

    public static Set<String> ids() { return dialogues.keySet(); }

    /** Перечитывает все диалоги. Диалог с ошибками структуры или неизвестными условиями/эффектами пропускается. */
    public static void load(ResourceManager rm) {
        Map<String, Dialogue> out = new TreeMap<>();
        Map<ResourceLocation, Resource> found = rm.listResources("wf_dialogue", rl -> rl.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> r : found.entrySet()) {
            try (InputStream in = r.getValue().open()) {
                Dialogue d = Dialogue.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                boolean bad = false;
                for (String p : d.validate()) {
                    if (p.startsWith("Предупреждение")) {
                        LOGGER.warn("wf_dialogue: {}: {}", r.getKey(), p);
                    } else {
                        bad = true;
                        LOGGER.error("wf_dialogue: {}: {}", r.getKey(), p);
                    }
                }
                if (bad) continue;
                if (out.put(d.id, d) != null) LOGGER.warn("wf_dialogue: диалог '{}' переопределён файлом {}", d.id, r.getKey());
            } catch (Exception ex) {
                LOGGER.error("wf_dialogue: ошибка в {}: {}", r.getKey(), ex.toString());
            }
        }
        dialogues = out;
        LOGGER.info("wf_dialogue: загружено диалогов: {}", out.size());
    }
}
