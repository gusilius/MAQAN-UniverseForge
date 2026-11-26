package dev.worldforge.mod.api;

import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.TitleEditor;

import java.util.List;

/** Всё, что нужно обработчику действия. realmId/titleId задаются в зависимости от области действия. */
public final class ActionContext {
    public final TitleEditor editor;
    public final String actor;           // имя игрока или "console"
    public final boolean operator;
    public final String realmId;         // null, если действие не над государством
    public final int titleId;            // 0, если действие не над титулом
    public final List<String> args;
    /** Stable ID from ActionExecutionService; domain receipts must reuse it on every retry. */
    public final String requestId;

    /** Source-compatible constructor for legacy integrations without a durable request identity. */
    public ActionContext(TitleEditor editor, String actor, boolean operator, String realmId, int titleId, List<String> args) {
        this(editor, actor, operator, realmId, titleId, args, "");
    }

    public ActionContext(TitleEditor editor, String actor, boolean operator, String realmId, int titleId,
                         List<String> args, String requestId) {
        this.editor = editor;
        this.actor = actor;
        this.operator = operator;
        this.realmId = realmId;
        this.titleId = titleId;
        this.args = List.copyOf(args);
        this.requestId = requestId == null ? "" : requestId.trim();
    }

    public TitleData data() { return editor.data(); }

    public TitleData.Realm realm() {
        TitleData.Realm r = realmId == null ? null : data().realms.get(realmId);
        if (r == null) throw new TitleEditor.EditException("Государство не найдено: " + realmId);
        return r;
    }

    public String arg(int i, String what) {
        if (i >= args.size() || args.get(i).isBlank()) throw new TitleEditor.EditException("Не указано: " + what);
        return args.get(i).trim();
    }
}
