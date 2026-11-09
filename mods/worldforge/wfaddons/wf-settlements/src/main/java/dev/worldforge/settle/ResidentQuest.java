package dev.worldforge.settle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent, Character-owned resident contract. Progress changes only after the
 * authoritative settlement service has accepted the delivered resource.
 */
public final class ResidentQuest {
    public enum Status { OFFERED, ACCEPTED, COMPLETED, ABANDONED }

    private final String id;
    private final String objective;
    private final String resourceId;
    private final int required;
    private final int createdYear;
    private int progress;
    private int completedYear;
    private Status status;

    public ResidentQuest(String id, String objective, int required, int createdYear) {
        this(id, objective, "bread", required, createdYear);
    }

    public ResidentQuest(String id, String objective, String resourceId, int required, int createdYear) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("quest id is required");
        if (objective == null || objective.isBlank()) throw new IllegalArgumentException("quest objective is required");
        if (resourceId == null || resourceId.isBlank()) throw new IllegalArgumentException("quest resource is required");
        if (required < 1 || required > 10000) throw new IllegalArgumentException("quest target out of range");
        this.id = id;
        this.objective = objective;
        this.resourceId = resourceId;
        this.required = required;
        this.createdYear = Math.max(0, createdYear);
        this.status = Status.OFFERED;
    }

    public String id() { return id; }
    public String objective() { return objective; }
    public String resourceId() { return resourceId; }
    public int required() { return required; }
    public int progress() { return progress; }
    public int createdYear() { return createdYear; }
    public int completedYear() { return completedYear; }
    public Status status() { return status; }
    public boolean complete() { return status == Status.COMPLETED; }

    public boolean accept() {
        if (status != Status.OFFERED) return false;
        status = Status.ACCEPTED;
        return true;
    }

    /** Adds a confirmed objective receipt. It cannot be called before acceptance or after completion. */
    public boolean addProgress(int amount, int year) {
        if (status != Status.ACCEPTED || amount <= 0) return false;
        progress = Math.min(required, progress + amount);
        if (progress >= required) {
            status = Status.COMPLETED;
            completedYear = Math.max(createdYear, year);
            return true;
        }
        return false;
    }

    public boolean abandon() {
        if (status != Status.OFFERED && status != Status.ACCEPTED) return false;
        status = Status.ABANDONED;
        return true;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("objective", objective);
        out.put("resource", resourceId);
        out.put("required", required);
        out.put("progress", progress);
        out.put("created_year", createdYear);
        out.put("completed_year", completedYear);
        out.put("status", status.name());
        return out;
    }

    public static ResidentQuest fromMap(Map<?, ?> raw) {
        Object id = raw.get("id"), objective = raw.get("objective");
        Object required = raw.get("required"), created = raw.get("created_year");
        if (!(id instanceof String) || !(objective instanceof String)
                || !(required instanceof Number) || !(created instanceof Number))
            throw new IllegalArgumentException("Malformed resident quest");
        Object resource = raw.get("resource");
        ResidentQuest quest = new ResidentQuest((String) id, (String) objective,
                resource instanceof String s && !s.isBlank() ? s : "bread",
                ((Number) required).intValue(), ((Number) created).intValue());
        Object progress = raw.get("progress");
        if (progress instanceof Number n) {
            int value = n.intValue();
            if (value < 0 || value > quest.required) throw new IllegalArgumentException("Resident quest progress out of range");
            quest.progress = value;
        }
        Object completed = raw.get("completed_year");
        if (completed instanceof Number n) quest.completedYear = Math.max(0, n.intValue());
        Object status = raw.get("status");
        if (!(status instanceof String s)) throw new IllegalArgumentException("Resident quest status is missing");
        try { quest.status = Status.valueOf(s); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Unknown resident quest status: " + s, ex); }
        if (quest.status == Status.COMPLETED && quest.progress < quest.required)
            throw new IllegalArgumentException("Completed resident quest has incomplete objective");
        if (quest.status != Status.COMPLETED && quest.completedYear != 0)
            throw new IllegalArgumentException("Uncompleted resident quest has completion year");
        return quest;
    }
}
