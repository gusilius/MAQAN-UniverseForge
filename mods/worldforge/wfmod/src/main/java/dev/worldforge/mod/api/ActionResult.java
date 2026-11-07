package dev.worldforge.mod.api;

public final class ActionResult {
    public final boolean ok;
    public final String message;

    private ActionResult(boolean ok, String message) { this.ok = ok; this.message = message == null ? "" : message; }

    public static ActionResult ok(String message) { return new ActionResult(true, message); }

    public static ActionResult fail(String message) { return new ActionResult(false, message); }
}
