package cubism53.mcp;

import java.util.*;
import java.math.BigDecimal;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

import static cubism53.mcp.NativeBridge.map;

/** Process-local job state. Only the job's native work runs on the editor thread. */
final class NativeBridgeJobs {
    private static final Map<String, Job> jobs = new HashMap<>();
    private static final Map<String, Job> requests = new HashMap<>();
    private static final Map<String, Supplier<Map<String,Object>>> meshes = new HashMap<>();
    private static final int MAX_PENDING = 16;
    private static int pending;
    private static boolean closing;

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
        String requestId = NativeBridge.nonblank(request.get("request_id"), "request_id");
        var arguments = new HashMap<>(request);
        arguments.remove("token");
        var existing = requests.get(requestId);
        if (existing != null) {
            if (!existing.arguments.equals(normalize(arguments)))
                throw new IllegalArgumentException("request_id already belongs to a different request");
            return existing.status;
        }
        checkOpen();
        if (pending >= MAX_PENDING) throw new IllegalStateException("Job queue is busy; retry the request");
        var job = new Job(requestId, operation, arguments);
        pending++;
        jobs.put(job.id, job);
        requests.put(requestId, job);
        SwingUtilities.invokeLater(() -> {
            job.update("running", null, null);
            try { job.update("succeeded", work.call(), null); }
            catch (Exception | Error error) { job.update("failed", null, error.toString()); }
            finally { complete(); }
        });
        return job.status;
    }

    private static synchronized void complete() { pending--; }

    static synchronized void registerMesh(String id, Supplier<Map<String,Object>> info) {
        meshes.put(id, info);
    }

    static synchronized Object status(Map<String,Object> request) {
        String jobId = (String) request.get("job_id");
        String requestId = (String) request.get("request_id");
        if ((jobId == null) == (requestId == null))
            throw new IllegalArgumentException("Provide exactly one of job_id and request_id");
        var job = jobId == null ? requests.get(requestId) : jobs.get(jobId);
        if (job != null) return job.status;
        var mesh = meshes.get(jobId);
        if (mesh != null) return mesh.get();
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
