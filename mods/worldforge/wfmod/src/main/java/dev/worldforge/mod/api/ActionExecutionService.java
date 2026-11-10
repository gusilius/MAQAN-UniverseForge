package dev.worldforge.mod.api;

import dev.worldforge.mod.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Durable, idempotent execution journal shared by domain action adapters.
 * The caller supplies authorization, execution and optional replanning so the core
 * remains independent of Minecraft and domain-specific modules.
 */
public final class ActionExecutionService {
    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
    public static final int MAX_RECORDS = 10000;
    public static final int MAX_ATTEMPTS = 3;

    public record Request(String requestId, String actionId, String actorId, String targetId,
                          List<String> args, long requestedMinute) {
        public Request {
            if (requestId == null || !requestId.matches("[A-Za-z0-9_.:-]{1,96}"))
                throw new IllegalArgumentException("Invalid action request id");
            if (actionId == null || !actionId.matches("[a-z0-9_.:-]{1,48}"))
                throw new IllegalArgumentException("Invalid action id");
            if (actorId == null || actorId.isBlank() || actorId.length() > 128)
                throw new IllegalArgumentException("Invalid action actor");
            if (targetId == null || targetId.length() > 256)
                throw new IllegalArgumentException("Invalid action target");
            args = List.copyOf(args == null ? List.of() : args);
            if (args.size() > 64 || args.stream().anyMatch(a -> a == null || a.length() > 1024))
                throw new IllegalArgumentException("Invalid action arguments");
            if (requestedMinute < 0) throw new IllegalArgumentException("Invalid action time");
        }
    }

    public record Plan(String actionId, String targetId, List<String> args, String reason) {
        public Plan {
            if (actionId == null || !actionId.matches("[a-z0-9_.:-]{1,48}"))
                throw new IllegalArgumentException("Invalid replanned action id");
            if (targetId == null || targetId.length() > 256)
                throw new IllegalArgumentException("Invalid replanned target");
            args = List.copyOf(args == null ? List.of() : args);
            if (args.size() > 64 || args.stream().anyMatch(a -> a == null || a.length() > 1024))
                throw new IllegalArgumentException("Invalid replanned action arguments");
            reason = reason == null ? "" : reason;
            if (reason.length() > 1024)
                throw new IllegalArgumentException("Invalid replanning reason");
        }
    }

    public record Task(String requestId, String actionId, String actorId, String targetId,
                       List<String> args, Status status, int attempts, String message,
                       long createdMinute, long updatedMinute, String receiptId) { }

    public record Receipt(String receiptId, String requestId, String actionId, String actorId,
                          String targetId, Status outcome, int attempts, String message,
                          long worldMinute) { }

    @FunctionalInterface public interface Authorizer { String denyReason(Request request); }
    @FunctionalInterface public interface Executor { ActionResult execute(Request request, int attempt); }
    @FunctionalInterface public interface Replanner { Plan replan(Request failed, ActionResult failure, int attempt); }

    private final LinkedHashMap<String, Task> tasks = new LinkedHashMap<>();
    private final LinkedHashMap<String, Receipt> receipts = new LinkedHashMap<>();

    /** Enqueue durably without executing; server tick/adapter can process it later. */
    public synchronized Task enqueue(Request request, long nowMinute, Path durableFile) {
        Objects.requireNonNull(request, "request");
        if (nowMinute < request.requestedMinute()) throw new IllegalArgumentException("Action time moved backwards");
        Task prior = tasks.get(request.requestId());
        if (prior != null) {
            if (!prior.actorId().equals(request.actorId()) || !prior.actionId().equals(request.actionId())
                    || !prior.targetId().equals(request.targetId()) || !prior.args().equals(request.args()))
                throw new IllegalStateException("Action request id was reused with a different payload: " + request.requestId());
            return prior;
        }
        if (tasks.size() >= MAX_RECORDS) throw new IllegalStateException("Action task journal is full");
        Task queued = new Task(request.requestId(), request.actionId(), request.actorId(), request.targetId(),
                request.args(), Status.QUEUED, 0, "Queued", request.requestedMinute(), nowMinute, "");
        tasks.put(request.requestId(), queued);
        if (durableFile != null) {
            try {
                save(durableFile);
            } catch (IOException ex) {
                // The domain executor has not run yet. Keep an in-memory terminal tombstone so
                // a same-process retry cannot bypass the failed durable QUEUED write.
                String receiptId = "receipt:" + request.requestId();
                String message = "Could not durably persist queued action; execution is blocked until reconciliation";
                Task blocked = new Task(queued.requestId(), queued.actionId(), queued.actorId(), queued.targetId(),
                        queued.args(), Status.FAILED, 0, message, queued.createdMinute(), nowMinute, receiptId);
                Receipt receipt = new Receipt(receiptId, queued.requestId(), queued.actionId(), queued.actorId(),
                        queued.targetId(), Status.FAILED, 0, message, nowMinute);
                tasks.put(request.requestId(), blocked);
                receipts.put(receiptId, receipt);
                throw new IllegalStateException(message + ": " + request.requestId(), ex);
            }
        }
        return queued;
    }

    /** Executes an already queued task once. Duplicate calls return its existing state/receipt. */
    public synchronized Task process(String requestId, Authorizer authorizer, Executor executor,
                                     Replanner replanner, long nowMinute, Path durableFile) {
        Objects.requireNonNull(authorizer, "authorizer");
        Objects.requireNonNull(executor, "executor");
        Task task = tasks.get(requestId);
        if (task == null) throw new IllegalArgumentException("Unknown action request: " + requestId);
        if (task.status() != Status.QUEUED) return task;
        Request original = new Request(task.requestId(), task.actionId(), task.actorId(), task.targetId(),
                task.args(), task.createdMinute());
        if (nowMinute < task.createdMinute()) throw new IllegalArgumentException("Action time moved backwards");

        String denied = authorizer.denyReason(original);
        if (denied != null) return finish(original, task, Status.FAILED, 0, denied, nowMinute, durableFile);

        Request current = original;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String attemptDenied = authorizer.denyReason(current);
            if (attemptDenied != null)
                return finish(current, task, Status.FAILED, attempt - 1, attemptDenied, nowMinute, durableFile);

            // Task identity always describes the submitted request; the final Receipt records any replanned target.
            task = new Task(original.requestId(), original.actionId(), original.actorId(), original.targetId(),
                    original.args(), Status.RUNNING, attempt, "Running", original.requestedMinute(), nowMinute, "");
            tasks.put(requestId, task);
            try {
                persistOrRollback(durableFile, requestId);
            } catch (IllegalStateException ex) {
                // The executor has not run. Convert the in-memory RUNNING transition into a
                // terminal receipt so callers never observe an unreceipted task or retry it.
                String message = "Action execution blocked before domain mutation because RUNNING state could not be persisted";
                String receiptId = "receipt:" + requestId;
                Task failed = new Task(original.requestId(), original.actionId(), original.actorId(),
                        original.targetId(), original.args(), Status.FAILED, attempt - 1,
                        message, original.requestedMinute(), nowMinute, receiptId);
                Receipt receipt = new Receipt(receiptId, original.requestId(), original.actionId(),
                        original.actorId(), original.targetId(), Status.FAILED, attempt - 1,
                        message, nowMinute);
                tasks.put(requestId, failed);
                receipts.put(receiptId, receipt);
                return failed;
            }

            ActionResult result;
            try { result = Objects.requireNonNull(executor.execute(current, attempt), "Action executor returned null"); }
            catch (RuntimeException ex) { result = ActionResult.fail("Action executor error: " + ex.getMessage()); }
            if (result.ok) return finish(current, task, Status.SUCCEEDED, attempt, result.message, nowMinute, durableFile);
            if (!result.retryable || replanner == null || attempt == MAX_ATTEMPTS)
                return finish(current, task, Status.FAILED, attempt, result.message, nowMinute, durableFile);

            Plan next;
            try { next = replanner.replan(current, result, attempt); }
            catch (RuntimeException ex) { next = null; }
            if (next == null)
                return finish(current, task, Status.FAILED, attempt, result.message, nowMinute, durableFile);
            current = new Request(original.requestId(), next.actionId(), original.actorId(), next.targetId(),
                    next.args(), original.requestedMinute());
        }
        return tasks.get(requestId);
    }

    /** Compatibility convenience: enqueue and immediately process through the same durable path. */
    public synchronized Task execute(Request request, Authorizer authorizer, Executor executor,
                                      Replanner replanner, long nowMinute, Path durableFile) {
        Task task = enqueue(request, nowMinute, durableFile);
        if (task.status() != Status.QUEUED) return task;
        return process(request.requestId(), authorizer, executor, replanner, nowMinute, durableFile);
    }

    public synchronized boolean cancel(String requestId, String actorId, boolean operator, long nowMinute, Path durableFile) {
        Task task = tasks.get(requestId);
        if (task == null || (task.status() != Status.QUEUED && task.status() != Status.RUNNING)) return false;
        if (!operator && !task.actorId().equals(actorId)) return false;
        String receiptId = "receipt:" + requestId;
        Receipt receipt = new Receipt(receiptId, requestId, task.actionId(), task.actorId(), task.targetId(),
                Status.CANCELLED, task.attempts(), "Cancelled", nowMinute);
        receipts.put(receiptId, receipt);
        Task cancelled = new Task(task.requestId(), task.actionId(), task.actorId(), task.targetId(), task.args(),
                Status.CANCELLED, task.attempts(), "Cancelled", task.createdMinute(), nowMinute, receiptId);
        tasks.put(requestId, cancelled);
        persistOrRollback(durableFile, requestId);
        return true;
    }

    private Task finish(Request request, Task prior, Status status, int attempts, String message,
                        long nowMinute, Path durableFile) {
        String receiptId = "receipt:" + request.requestId();
        Receipt receipt = new Receipt(receiptId, request.requestId(), request.actionId(), request.actorId(),
                request.targetId(), status, attempts, message == null ? "" : message, nowMinute);
        receipts.put(receiptId, receipt);
        Task done = new Task(prior.requestId(), prior.actionId(), prior.actorId(), prior.targetId(),
                prior.args(), status, attempts, message == null ? "" : message,
                prior.createdMinute(), nowMinute, receiptId);
        tasks.put(request.requestId(), done);
        persistOrRollback(durableFile, request.requestId());
        return done;
    }

    public synchronized Task task(String requestId) { return tasks.get(requestId); }
    public synchronized List<Task> tasks() { return List.copyOf(tasks.values()); }
    public synchronized List<Receipt> receipts() { return List.copyOf(receipts.values()); }

    public synchronized String toJson() {
        List<Object> taskRows = new ArrayList<>();
        for (Task t : tasks.values()) taskRows.add(Map.ofEntries(
                Map.entry("request", t.requestId()), Map.entry("action", t.actionId()),
                Map.entry("actor", t.actorId()), Map.entry("target", t.targetId()),
                Map.entry("args", t.args()), Map.entry("status", t.status().name()),
                Map.entry("attempts", t.attempts()), Map.entry("message", t.message()),
                Map.entry("created", t.createdMinute()), Map.entry("updated", t.updatedMinute()),
                Map.entry("receipt", t.receiptId())));
        List<Object> receiptRows = new ArrayList<>();
        for (Receipt r : receipts.values()) receiptRows.add(Map.of(
                "id", r.receiptId(), "request", r.requestId(), "action", r.actionId(), "actor", r.actorId(),
                "target", r.targetId(), "outcome", r.outcome().name(), "attempts", r.attempts(),
                "message", r.message(), "minute", r.worldMinute()));
        return Json.write(Map.of("format", "wf-action-execution/1", "tasks", taskRows, "receipts", receiptRows));
    }

    public synchronized void save(Path file) throws IOException {
        if (file == null) return;
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
        try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (IOException unsupported) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
    }

    public synchronized void load(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) return;
        loadJson(Files.readString(file, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    public synchronized void loadJson(String json) {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map<?, ?> root) || !"wf-action-execution/1".equals(root.get("format")))
            throw new IllegalArgumentException("Invalid action execution journal");
        if (!(root.get("tasks") instanceof List<?> taskRows) || taskRows.size() > MAX_RECORDS
                || !(root.get("receipts") instanceof List<?> receiptRows) || receiptRows.size() > MAX_RECORDS)
            throw new IllegalArgumentException("Invalid action execution journal rows");

        LinkedHashMap<String, Task> loadedTasks = new LinkedHashMap<>();
        LinkedHashMap<String, Receipt> loadedReceipts = new LinkedHashMap<>();
        for (Object row : taskRows) {
            if (!(row instanceof Map<?, ?> m)) throw new IllegalArgumentException("Malformed action task");
            String request = str(m, "request"), action = str(m, "action"), actor = str(m, "actor");
            String target = str(m, "target"), message = str(m, "message"), receipt = str(m, "receipt");
            List<String> args = strings(m.get("args"));
            Status status = Status.valueOf(str(m, "status"));
            int attempts = number(m, "attempts").intValue();
            long created = number(m, "created").longValue(), updated = number(m, "updated").longValue();
            Request validated = new Request(request, action, actor, target, args, created);
            if (attempts < 0 || updated < created || loadedTasks.containsKey(request))
                throw new IllegalArgumentException("Invalid or duplicate action task " + request);
            // A process restart must never replay an uncertain in-flight domain mutation.
            if (status == Status.RUNNING || status == Status.QUEUED) {
                status = Status.FAILED;
                message = "Interrupted by server restart; outcome requires reconciliation";
                receipt = "receipt:" + request;
            }
            loadedTasks.put(validated.requestId(), new Task(request, action, actor, target, args,
                    status, attempts, message, created, updated, receipt));
        }
        for (Object row : receiptRows) {
            if (!(row instanceof Map<?, ?> m)) throw new IllegalArgumentException("Malformed action receipt");
            String id = str(m, "id"), request = str(m, "request"), action = str(m, "action");
            String actor = str(m, "actor"), target = str(m, "target"), message = str(m, "message");
            Status outcome = Status.valueOf(str(m, "outcome"));
            int attempts = number(m, "attempts").intValue();
            long minute = number(m, "minute").longValue();
            if (!outcome.equals(Status.SUCCEEDED) && !outcome.equals(Status.FAILED) && !outcome.equals(Status.CANCELLED))
                throw new IllegalArgumentException("Invalid action receipt outcome");
            if (attempts < 0 || minute < 0 || loadedReceipts.containsKey(id) || !loadedTasks.containsKey(request))
                throw new IllegalArgumentException("Invalid or duplicate action receipt " + id);
            loadedReceipts.put(id, new Receipt(id, request, action, actor, target, outcome, attempts, message, minute));
        }
        for (Task t : loadedTasks.values()) {
            if (!t.receiptId().isEmpty() && !loadedReceipts.containsKey(t.receiptId())) {
                if (t.status() != Status.FAILED || !t.message().startsWith("Interrupted by server restart"))
                    throw new IllegalArgumentException("Action task references a missing receipt: " + t.requestId());
                loadedReceipts.put(t.receiptId(), new Receipt(t.receiptId(), t.requestId(), t.actionId(),
                        t.actorId(), t.targetId(), Status.FAILED, t.attempts(), t.message(), t.updatedMinute()));
            }
        }
        tasks.clear(); tasks.putAll(loadedTasks);
        receipts.clear(); receipts.putAll(loadedReceipts);
    }

    private void persistOrRollback(Path file, String requestId) {
        if (file == null) return;
        try { save(file); }
        catch (IOException ex) {
            // Keep the request in memory as a tombstone. Retrying the same ID must never
            // re-run a domain mutation whose durable outcome could not be confirmed.
            throw new IllegalStateException("Could not durably persist action request " + requestId
                    + "; execution is blocked for this ID until reconciliation", ex);
        }
    }

    private static String str(Map<?, ?> m, String key) {
        Object value = m.get(key);
        if (!(value instanceof String s)) throw new IllegalArgumentException("Missing action journal field " + key);
        return s;
    }
    private static Number number(Map<?, ?> m, String key) {
        Object value = m.get(key);
        if (!(value instanceof Number n)) throw new IllegalArgumentException("Missing numeric action journal field " + key);
        return n;
    }
    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Invalid action arguments");
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String s)) throw new IllegalArgumentException("Invalid action argument");
            out.add(s);
        }
        return List.copyOf(out);
    }
}
