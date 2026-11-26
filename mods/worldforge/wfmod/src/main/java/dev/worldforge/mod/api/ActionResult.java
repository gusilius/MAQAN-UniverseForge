package dev.worldforge.mod.api;

public final class ActionResult {
    public final boolean ok;
    public final boolean retryable;
    public final String message;

    private ActionResult(boolean ok, boolean retryable, String message) {
        this.ok = ok;
        this.retryable = retryable;
        this.message = message == null ? "" : message;
    }

    public static ActionResult ok(String message) { return new ActionResult(true, false, message); }

    public static ActionResult fail(String message) { return new ActionResult(false, false, message); }

    /** Explicitly marks a transient failure eligible for bounded replanning/retry. */
    public static ActionResult retry(String message) { return new ActionResult(false, true, message); }
}
