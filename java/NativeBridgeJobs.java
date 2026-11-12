package cubism53.mcp;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

import static cubism53.mcp.NativeBridge.map;

/** Job state shared by editor and background workers. */
final class NativeBridgeJobs {
    private static final int MAX_PENDING = 16;
    private static final Map<String, Job> jobs = new HashMap<>();
    private static final Map<String, Job> requests = new HashMap<>();
    private static final Map<String, Supplier<Map<String,Object>>> meshes = new HashMap<>();
    private static final Map<String, String> latestMesh = new HashMap<>();
    private static final Retention history = new Retention(System::nanoTime);
    // The shared pending limit bounds the sum of queued, running and synchronous work.
    private static final ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
        var worker = new Thread(task, "Cubism53-background-job");
        worker.setDaemon(true);
        return worker;
    });
    private static int pending;
    private static boolean closing;

    record Key(String id, String requestId) {}

    static final class Retention {
        private static final long COMPLETED_AGE = Duration.ofHours(24).toNanos();
        private static final long EXPIRED_AGE = Duration.ofDays(7).toNanos();
        private final LongSupplier clock;
        private final Map<Key,Long> completed = new LinkedHashMap<>();
        private final Map<Key,Long> expired = new LinkedHashMap<>();

        Retention(LongSupplier clock) { this.clock = clock; }

        void complete(Key key) { completed.put(key, clock.getAsLong()); }

        List<Key> prune() {
            long now = clock.getAsLong();
            var removed = new ArrayList<Key>();
            var retained = completed.entrySet().iterator();
            while (retained.hasNext()) {
                var entry = retained.next();
                if (completed.size() <= 256 && now - entry.getValue() < COMPLETED_AGE) break;
                removed.add(entry.getKey());
                long age = now - entry.getValue();
                expired.put(entry.getKey(), age >= COMPLETED_AGE ? entry.getValue() + COMPLETED_AGE : now);
                retained.remove();
            }
            var old = expired.entrySet().iterator();
            while (old.hasNext()) {
                var entry = old.next();
                if (expired.size() <= 4096 && now - entry.getValue() < EXPIRED_AGE) break;
                old.remove();
            }
            return removed;
        }

        boolean expired(String id, String requestId) {
            return expired.keySet().stream().anyMatch(key -> id != null ? id.equals(key.id) : requestId.equals(key.requestId));
        }
    }

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final String requestId;
        final String operation;
        final Object arguments;
        volatile Map<String,Object> status;

        Job(String requestId, String operation, Map<String,Object> arguments) {
            this.requestId = requestId;
            this.operation = operation;
            this.arguments = normalize(arguments);
            status = map("job_id", id, "request_id", requestId, "operation", operation,
                "document_uid", arguments.get("document_uid"), "state", "queued", "result", null, "error", null);
        }

        void update(String state, Object result, String error) {
            status = map("job_id", id, "request_id", requestId, "operation", operation,
                "document_uid", status.get("document_uid"), "state", state, "result", result, "error", error);
        }
    }

    static synchronized Object submit(String operation, Map<String,Object> request, Callable<Object> work) {
        return submit(operation, request, false, work);
    }

    static synchronized Object submit(String operation, Map<String,Object> request, boolean background, Callable<Object> work) {
        prune();
        String requestId = NativeBridge.nonblank(request.get("request_id"), "request_id");
        var arguments = new HashMap<>(request);
        arguments.remove("token");
        var existing = requests.get(requestId);
        if (existing != null) {
            if (!existing.arguments.equals(normalize(arguments)))
                throw new IllegalArgumentException("request_id already belongs to a different request");
            return existing.status;
        }
        checkExpired(null, requestId);
        var job = new Job(requestId, operation, arguments);
        beginWork();
        jobs.put(job.id, job);
        requests.put(requestId, job);
        Runnable run = () -> {
            job.update("running", null, null);
            try { job.update("succeeded", work.call(), null); }
            catch (Exception | Error error) { job.update("failed", null, error.toString()); }
            finally { complete(job); }
        };
        if (background) workers.execute(run);
        else SwingUtilities.invokeLater(run);
        return job.status;
    }

    static synchronized void beginWork() {
        checkOpen();
        if (pending >= MAX_PENDING) throw new IllegalStateException("Job queue is busy; retry the request");
        pending++;
    }

    static synchronized void endWork() { pending--; }

    private static synchronized void complete(Job job) {
        endWork();
        history.complete(new Key(job.id, job.requestId));
        prune();
    }

    static synchronized void registerMesh(String document, String id, Supplier<Map<String,Object>> info) {
        prune();
        meshes.put(id, info);
        latestMesh.put(document, id);
    }

    static synchronized void completeMesh(String id) {
        history.complete(new Key(id, null));
        prune();
    }

    static synchronized void closeDocument(String document) { latestMesh.remove(document); }

    static synchronized Object meshStatus(String document, String id) {
        prune();
        if (id == null) id = latestMesh.get(document);
        var mesh = meshes.get(id);
        if (mesh != null) {
            var result = mesh.get();
            if (document.equals(result.get("document_uid"))) return result;
        }
        if (id != null) checkExpired(id, null);
        throw new IllegalArgumentException("Automatic mesh job not found in this document");
    }

    private static void prune() {
        for (var key : history.prune()) {
            jobs.remove(key.id);
            if (key.requestId != null) requests.remove(key.requestId);
            if (meshes.remove(key.id) != null) latestMesh.values().remove(key.id);
        }
    }

    private static void checkExpired(String jobId, String requestId) {
        if (history.expired(jobId, requestId))
            throw new IllegalArgumentException("Job record expired. Inspect document or output before submitting a new request ID.");
    }

    static synchronized Object status(Map<String,Object> request) {
        String jobId = (String) request.get("job_id");
        String requestId = (String) request.get("request_id");
        if ((jobId == null) == (requestId == null))
            throw new IllegalArgumentException("Provide exactly one of job_id and request_id");
        prune();
        var job = jobId == null ? requests.get(requestId) : jobs.get(jobId);
        if (job != null) return job.status;
        var mesh = meshes.get(jobId);
        if (mesh != null) return mesh.get();
        checkExpired(jobId, requestId);
        throw new IllegalArgumentException("Job not found in this bridge session");
    }

    static synchronized void checkOpen() {
        if (closing) throw new IllegalStateException("Bridge is stopping");
    }

    static synchronized void beginStop() {
        checkOpen();
        if (pending != 0)
            throw new IllegalStateException("A bridge job is queued or running");
        closing = true;
    }

    static synchronized void abortStop() { closing = false; }

    static void stopped() { workers.shutdown(); }

    private static Object normalize(Object value) {
        if (value instanceof Number number) return new BigDecimal(number.toString()).stripTrailingZeros();
        if (value instanceof List<?> list) return list.stream().map(NativeBridgeJobs::normalize).toList();
        if (value instanceof Map<?,?> map) {
            var normalized = new HashMap<Object,Object>();
            map.forEach((key, item) -> normalized.put(key, normalize(item)));
            return normalized;
        }
        return value;
    }
}
