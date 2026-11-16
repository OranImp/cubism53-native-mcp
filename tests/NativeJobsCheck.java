package cubism53.mcp;

import java.util.*;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;

public final class NativeJobsCheck {
    private static Map<?,?> submit(String id, Number value) {
        return (Map<?,?>) NativeBridgeJobs.submit("render_pose_set",
            Map.of("request_id", id, "value", value), () -> Map.of("success", true));
    }

    private static void checkTraceAndTraversal() throws Exception {
        try (var spool = new NativeBridgeTrace.Spool()) {
            for (int i = 0; i < 300; i++) spool.append(NativeBridge.map("frame", i, "outputs", Map.of("\u6d4b", 0.125)));
            var trace = spool.finish();
            try (var reader = trace.acquire()) {
                trace.close();
                var page = reader.read(127, 130);
                if (!page.equals(List.of(
                        Map.of("frame", 127L, "outputs", Map.of("\u6d4b", 0.125)),
                        Map.of("frame", 128L, "outputs", Map.of("\u6d4b", 0.125)),
                        Map.of("frame", 129L, "outputs", Map.of("\u6d4b", 0.125)))))
                    throw new AssertionError("Trace lost a frame at the UTF-8 checkpoint boundary: " + page);
                if (!reader.read(300, 300).isEmpty()) throw new AssertionError("End page must be empty");
            }
        }
        var random = new Random(318L);
        for (int trial = 0; trial < 100; trial++) {
            var order = new ArrayList<Integer>();
            var parents = new HashMap<Integer,List<Integer>>();
            for (int node = 0; node < 96; node++) {
                order.add(node);
                parents.put(node, List.of(random.nextInt(96), random.nextInt(96)));
            }
            Collections.shuffle(order, random);
            var original = new LinkedHashSet<>(List.of(0, 12, 23));
            var indexed = new LinkedHashSet<>(original);
            int size;
            do {
                size = original.size();
                for (var node : order) if (parents.get(node).stream().anyMatch(original::contains)) original.add(node);
            } while (size != original.size());
            NativeBridge.expandInScanOrder(indexed, order, parents::get);
            if (!List.copyOf(original).equals(List.copyOf(indexed)))
                throw new AssertionError("Indexed traversal changed discovery order");
        }
    }

    public static void main(String[] args) throws Exception {
        var waiting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            waiting.countDown();
            try { release.await(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        if (!waiting.await(5, TimeUnit.SECONDS)) throw new AssertionError("EDT did not start");
        try {
            var first = submit("request-0", 1);
            for (int i = 1; i < 16; i++) submit("request-" + i, i);
            try {
                submit("overflow", 0);
                throw new AssertionError("Job queue exceeded its capacity");
            } catch (IllegalStateException error) {
                if (!error.getMessage().contains("busy")) throw error;
            }
            if (!submit("request-0", 1.0).get("job_id").equals(first.get("job_id")))
                throw new AssertionError("A full queue did not replay a known request");
            if (!"queued".equals(((Map<?,?>) NativeBridgeJobs.status(Map.of("job_id", first.get("job_id")))).get("state")))
                throw new AssertionError("Queued state unavailable while the editor is blocked");
        } finally {
            release.countDown();
        }
        SwingUtilities.invokeAndWait(() -> {});
        NativeBridgeJobs.beginStop();
        NativeBridgeJobs.abortStop();
        submit("after-drain", 0);
        SwingUtilities.invokeAndWait(() -> {});
        for (int i = 0; i < 256; i++) {
            submit("retention-" + i, i);
            SwingUtilities.invokeAndWait(() -> {});
        }
        try {
            submit("request-0", 1);
            throw new AssertionError("An expired request was accepted");
        } catch (IllegalArgumentException error) {
            if (!error.getMessage().contains("expired")) throw error;
        }
        var clock = new AtomicLong();
        var retention = new NativeBridgeJobs.Retention(clock::get);
        var mesh = new NativeBridgeJobs.Key("mesh-job", null);
        retention.complete(mesh);
        clock.addAndGet(Duration.ofHours(23).toNanos());
        if (!retention.prune().isEmpty()) throw new AssertionError("Job expired too early");
        clock.addAndGet(Duration.ofHours(1).toNanos());
        if (!retention.prune().contains(mesh) || !retention.expired("mesh-job", null))
            throw new AssertionError("Automatic mesh history did not expire");
        clock.addAndGet(Duration.ofDays(7).toNanos());
        retention.prune();
        if (retention.expired("mesh-job", null)) throw new AssertionError("Expiry marker was retained too long");
        try (var render = NativeBridgeRendering.reserve(3840, 2160, 0, false)) {
            if (!render.large) throw new AssertionError("UHD render should be exclusive");
            try (var ignored = NativeBridgeRendering.reserve(256, 256, 0, false)) {
                throw new AssertionError("A second render was admitted while UHD held the permit");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("Render capacity")) throw expected;
            }
        }
        try (var render = NativeBridgeRendering.reserve(256, 256, 0, false)) {
            if (render.large) throw new AssertionError("Small render should not be exclusive");
        }
        if (Runtime.getRuntime().maxMemory() <= 512L * 1024 * 1024) {
            try (var ignored = NativeBridgeRendering.reserve(7680, 4320, 7680L * 4320, false)) {
                throw new AssertionError("8K diff exceeded the test heap budget");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("Insufficient editor heap")) throw expected;
            }
        }
        checkTraceAndTraversal();
        System.out.println("Native job capacity, replay, polling, indexed trace, ordered traversal, expiry and render admission verified");
    }
}
