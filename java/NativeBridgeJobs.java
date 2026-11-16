package cubism53.mcp;

import java.io.IOException;
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
    private static long sequence;
    private static final Map<String,JobTime> times = new HashMap<>();

    private record JobTime(long sequence, long createdAt) {}

    private static void track(String id) {
        times.put(id, new JobTime(++sequence, System.currentTimeMillis()));
    }

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

    record TraceResult(Map<String,Object> metadata, NativeBridgeTrace trace) {}

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final String requestId;
        final String operation;
        final String arguments;
        volatile Map<String,Object> status;
        NativeBridgeTrace trace;

        Job(String requestId, String operation, Map<String,Object> arguments, String fingerprint) {
            this.requestId = requestId;
            this.operation = operation;
            this.arguments = fingerprint;
            status = map("job_id", id, "request_id", requestId, "operation", operation,
                "document_uid", arguments.get("document_uid"), "state", "queued", "result", null, "error", null);
        }

        Object retainResult(Object result) {
            if (!(result instanceof TraceResult completed)) return result;
            trace = completed.trace;
            return completed.metadata;
        }

        void update(String state, Object result, String error) {
            status = map("job_id", id, "request_id", requestId, "operation", operation,
                "document_uid", status.get("document_uid"), "state", state, "result", result, "error", error);
        }
    }

    static Object submit(String operation, Map<String,Object> request, Callable<Object> work) {
        return submit(operation, request, false, work);
    }

    static Object submit(String operation, Map<String,Object> request, boolean background, Callable<Object> work) {
        String requestId = NativeBridge.nonblank(request.get("request_id"), "request_id");
        var arguments = new HashMap<>(request);
        arguments.remove("token");
        var fingerprint = fingerprint(arguments);
        JobReport replay;
        synchronized (NativeBridgeJobs.class) {
            prune();
            var existing = requests.get(requestId);
            if (existing == null) {
                checkExpired(null, requestId);
                var job = new Job(requestId, operation, arguments, fingerprint);
                beginWork();
                track(job.id);
                jobs.put(job.id, job);
                requests.put(requestId, job);
                Runnable run = () -> {
                    job.update("running", null, null);
                    try { job.update("succeeded", job.retainResult(work.call()), null); }
                    catch (Exception | Error error) { job.update("failed", null, error.toString()); }
                    finally { complete(job); }
                };
                if (background) workers.execute(run);
                else SwingUtilities.invokeLater(run);
                return job.status;
            }
            if (!existing.arguments.equals(fingerprint))
                throw new IllegalArgumentException("request_id already belongs to a different request");
            replay = snapshot(existing);
        }
        try (replay) {
            return report(replay, Map.of());
        } catch (IOException error) {
            throw new IllegalStateException("Could not read the completed job result", error);
        }
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
        track(id);
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
            times.remove(key.id);
            var removed = jobs.remove(key.id);
            if (removed != null && removed.trace != null) removed.trace.close();
            if (key.requestId != null) requests.remove(key.requestId);
            if (meshes.remove(key.id) != null) latestMesh.values().remove(key.id);
        }
    }

    private static void checkExpired(String jobId, String requestId) {
        if (history.expired(jobId, requestId))
            throw new IllegalArgumentException("Job record expired. Inspect document or output before submitting a new request ID.");
    }

    static Object status(Map<String,Object> request) throws IOException {
        String jobId = (String) request.get("job_id");
        String requestId = (String) request.get("request_id");
        if ((jobId == null) == (requestId == null))
            throw new IllegalArgumentException("Provide exactly one of job_id and request_id");
        JobReport snapshot;
        synchronized (NativeBridgeJobs.class) {
            prune();
            var job = jobId == null ? requests.get(requestId) : jobs.get(jobId);
            if (job == null) {
                var mesh = meshes.get(jobId);
                if (mesh != null) return mesh.get();
                checkExpired(jobId, requestId);
                throw new IllegalArgumentException("Job not found in this bridge session");
            }
            snapshot = snapshot(job);
        }
        try (snapshot) { return report(snapshot, request); }
    }

    private record JobReport(Map<String,Object> status, NativeBridgeTrace.ReadLease trace, int count) implements AutoCloseable {
        @Override public void close() { if (trace != null) trace.close(); }
    }

    private static JobReport snapshot(Job job) {
        var status = job.status;
        var trace = "succeeded".equals(status.get("state")) ? job.trace : null;
        return new JobReport(status, trace == null ? null : trace.acquire(), trace == null ? 0 : trace.count);
    }

    private static Map<String,Object> report(JobReport job, Map<String,Object> request) throws IOException {
        var status = job.status;
        if (!"succeeded".equals(status.get("state"))) return status;
        boolean page = request.containsKey("frame_offset") || request.get("frame_limit") != null;
        if (job.trace == null && !page) return status;
        if (!(status.get("result") instanceof Map<?,?> result)) return status;
        var frames = result.get("frames") instanceof List<?> list ? list : null;
        if (frames == null && job.trace == null) return status;
        int total = job.trace == null ? frames.size() : job.count;
        int offset = NativeBridge.integer(request, "frame_offset", 0);
        int limit = NativeBridge.integer(request, "frame_limit", Integer.MAX_VALUE);
        if (offset < 0 || limit < 1) throw new IllegalArgumentException("Invalid physics frame page");
        int start = Math.min(total, offset);
        int end = (int) Math.min(total, (long) start + limit);
        var data = new LinkedHashMap<Object,Object>(result);
        data.put("frames", job.trace == null ? frames.subList(start, end) : job.trace.read(start, end));
        if (page) {
            data.put("frame_count", total);
            data.put("next_frame_offset", end < total ? end : null);
        }
        var reply = new LinkedHashMap<>(status);
        reply.put("result", data);
        return reply;
    }

    static synchronized Object list(Map<String,Object> request) {
        prune();
        int limit = NativeBridge.integer(request, "limit", 50);
        if (limit < 1 || limit > 256) throw new IllegalArgumentException("Job page size must be 1..256");
        long before = request.get("before") instanceof Number n ? n.longValue() : Long.MAX_VALUE;
        var page = new ArrayList<Map<String,Object>>();
        var ordered = times.entrySet().stream().filter(entry -> entry.getValue().sequence < before)
            .sorted(Map.Entry.<String,JobTime>comparingByValue(Comparator.comparingLong(JobTime::sequence)).reversed()).toList();
        boolean more = false;
        for (var entry : ordered) {
            var job = jobs.get(entry.getKey());
            var row = job != null ? job.status : meshes.get(entry.getKey()).get();
            String operation = job != null ? job.operation : "auto_generate_mesh";
            if (!matches(request, "document_uid", row.get("document_uid"))
                    || !matches(request, "job_operation", operation) || !matches(request, "job_state", row.get("state"))) continue;
            if (page.size() == limit) {
                more = true;
                break;
            }
            var time = entry.getValue();
            page.add(map("job_id", entry.getKey(), "request_id", row.get("request_id"),
                "operation", operation, "document_uid", row.get("document_uid"), "state", row.get("state"),
                "error", row.get("error"), "created_at_ms", time.createdAt, "sequence", time.sequence));
        }
        return map("jobs", page, "next_before", more ? page.get(page.size() - 1).get("sequence") : null);
    }

    private static boolean matches(Map<String,Object> request, String key, Object value) {
        return request.get(key) == null || Objects.equals(request.get(key), value);
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

    static synchronized void stopped() {
        workers.shutdown();
        for (var job : jobs.values()) if (job.trace != null) job.trace.close();
    }

    private static String fingerprint(Map<String,Object> arguments) {
        try { return NativeBridge.digestJson(normalize(arguments)); }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Object normalize(Object value) {
        if (value instanceof Number number) return new BigDecimal(number.toString()).stripTrailingZeros();
        if (value instanceof List<?> list) return list.stream().map(NativeBridgeJobs::normalize).toList();
        if (value instanceof Map<?,?> map) {
            var normalized = new TreeMap<String,Object>();
            map.forEach((key, item) -> normalized.put((String) key, normalize(item)));
            return normalized;
        }
        return value;
    }
}
