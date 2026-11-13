package cubism53.mcp;

import com.live2d.cubism.doc.model.CModelSource;
import com.live2d.cubism.doc.model.param.CParameterSet;
import com.live2d.cubism.doc.modeling.CModelingDocument;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Callable;
import javax.imageio.ImageIO;
import org.json.simple.JSONValue;

import static cubism53.mcp.NativeBridge.*;

final class NativeBridgeRendering {
    private static final long MIB = 1024L * 1024L;
    private static final long UHD_PIXELS = 3840L * 2160L;
    private static final Object budget = new Object();
    private static long reserved;
    private static int active;
    private static int waitingLarge;
    private static boolean exclusive;

    static final class Permit implements AutoCloseable {
        final boolean large;
        final long bytes;

        private Permit(boolean large, long bytes) { this.large = large; this.bytes = bytes; }

        public void close() {
            synchronized (budget) {
                reserved -= bytes;
                active--;
                if (large) exclusive = false;
                budget.notifyAll();
            }
        }
    }

    static Permit reserve(int width, int height, long differencePixels, boolean wait) throws InterruptedException {
        if (width < 1 || height < 1 || width > 8192 || height > 8192)
            throw new IllegalArgumentException("Image size out of range");
        long pixels = (long) width * height;
        boolean large = pixels >= UHD_PIXELS;
        long padded = ((width + 15L) / 16 * 16) * ((height + 15L) / 16 * 16);
        // A matching baseline may be a 16-bit RGBA PNG.
        long estimate = padded * 8 + differencePixels * 12 + 32 * MIB;
        synchronized (budget) {
            if (large) waitingLarge++;
            try {
                while (exclusive || (large ? active > 0 : waitingLarge > 0)) {
                    if (!wait) throw new IllegalStateException("Render capacity is occupied by a high-resolution job");
                    budget.wait();
                }
                var runtime = Runtime.getRuntime();
                long used = runtime.totalMemory() - runtime.freeMemory();
                long headroom = Math.max(128 * MIB, runtime.maxMemory() / 10);
                if (estimate > runtime.maxMemory() - used - reserved - headroom)
                    throw new IllegalStateException("Insufficient editor heap for render buffers (estimated "
                        + ((estimate + MIB - 1) / MIB) + " MiB). Reduce the resolution or free editor memory.");
                active++;
                exclusive = large;
                reserved += estimate;
                return new Permit(large, estimate);
            } finally {
                if (large) waitingLarge--;
                budget.notifyAll();
            }
        }
    }

    private record Frame(String name, CParameterSet pose, Map<String,Double> parameters) {}

    private record RenderPlan(CModelSource snapshot, NativeRenderSession renderer, int width, int height,
                              int x, int y, int w, int h, String base, List<Frame> frames) implements AutoCloseable {
        public void close() throws Exception {
            onEditorThread(() -> {
                try { renderer.close(); }
                finally { snapshot.dispose(); }
                return null;
            }, 0);
        }
    }

    private static RenderPlan prepare(CModelingDocument doc, Map<String,Object> request) throws Exception {
        var source = doc.getModelSource();
        int canvasWidth = source.getCanvas().getPixelWidth();
        int canvasHeight = source.getCanvas().getPixelHeight();
        int width = integer(request, "width", canvasWidth);
        int height = integer(request, "height", canvasHeight);
        if (width < 1 || height < 1 || width > 8192 || height > 8192)
            throw new IllegalArgumentException("Image size out of range");
        var crop = (List<?>) request.get("crop");
        int x = 0, y = 0, w = width, h = height;
        if (crop != null) {
            if (width != canvasWidth || height != canvasHeight || crop.size() != 4)
                throw new IllegalArgumentException("A crop requires the native canvas size and [x0,y0,x1,y1]");
            x = ((Number) crop.get(0)).intValue();
            y = ((Number) crop.get(1)).intValue();
            int right = ((Number) crop.get(2)).intValue(), bottom = ((Number) crop.get(3)).intValue();
            if (x < 0 || y < 0 || right > width || bottom > height || right <= x || bottom <= y)
                throw new IllegalArgumentException("Crop is outside the canvas or empty");
            w = right - x;
            h = bottom - y;
        }
        String base = (String) request.getOrDefault("base_pose", "default");
        var frames = new ArrayList<Frame>();
        var names = new HashSet<String>();
        for (var value : (List<?>) request.get("poses")) {
            var row = (Map<?,?>) value;
            String name = nonblank(row.get("name"), "pose name");
            if (!name.matches("[A-Za-z0-9_-]+") || !names.add(name.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Pose names must be distinct portable filenames");
            var pose = pose(source, base, (Map<?,?>) row.get("parameters"));
            var actual = new TreeMap<String,Double>();
            for (var parameter : source.getAllParameters())
                actual.put(parameter.getId().toString(), (double) pose.get(parameter.getGuid()).getValue());
            frames.add(new Frame(name, pose, actual));
        }
        if (frames.isEmpty()) throw new IllegalArgumentException("At least one pose is required");
        var context = new com.live2d.core.a();
        var snapshot = context.a(() -> source.deepCopy(context));
        try {
            snapshot.setup();
            var renderer = new NativeRenderSession(doc.getDocumentUID(), snapshot, width, height, frames.get(0).pose);
            return new RenderPlan(snapshot, renderer, width, height, x, y, w, h, base, frames);
        } catch (Exception | Error error) {
            snapshot.dispose();
            throw error;
        }
    }

    static Object renderSet(Callable<CModelingDocument> document, Path workspace, Map<String,Object> request) throws Exception {
        var output = NativeBridgeAssets.output(workspace, request.get("directory"), "");
        int[] size = onEditorThread(() -> {
            var canvas = document.call().getModelSource().getCanvas();
            return new int[]{integer(request, "width", canvas.getPixelWidth()),
                integer(request, "height", canvas.getPixelHeight())};
        }, 0);
        long difference = request.get("baseline_manifest") == null ? 0 : (long) size[0] * size[1];
        if (difference > 0 && request.get("crop") instanceof List<?> crop && crop.size() == 4) {
            long width = ((Number) crop.get(2)).intValue() - (long) ((Number) crop.get(0)).intValue();
            long height = ((Number) crop.get(3)).intValue() - (long) ((Number) crop.get(1)).intValue();
            if (width > 0 && width <= size[0] && height > 0 && height <= size[1]) difference = width * height;
        }
        try (var permit = reserve(size[0], size[1], difference, true)) {
            return renderReserved(document, workspace, output, request, size, permit);
        }
    }

    private static Object renderReserved(Callable<CModelingDocument> document, Path workspace, Path output,
                                         Map<String,Object> request, int[] size, Permit permit) throws Exception {
        Files.createDirectories(output.getParent());
        Path staging = Files.createTempDirectory(output.getParent(), ".cubism-render-");
        try {
            int count;
            try (var plan = onEditorThread(() -> {
                var doc = document.call();
                var canvas = doc.getModelSource().getCanvas();
                if (size[0] != integer(request, "width", canvas.getPixelWidth())
                        || size[1] != integer(request, "height", canvas.getPixelHeight()))
                    throw new IllegalStateException("Canvas changed before rendering began");
                if (permit.large) closeRenderSessions(null);
                return prepare(doc, request);
            }, 0)) {
                writeSet(plan, workspace, staging, request);
                count = plan.frames.size();
            }
            NativeBridgeAssets.publishDirectory(staging, output);
            return map("directory", output.toString(), "manifest", output.resolve("manifest.json").toString(), "frame_count", count);
        } finally { NativeBridgeAssets.removeStaging(staging); }
    }

    private static void writeSet(RenderPlan plan, Path workspace, Path staging, Map<String,Object> request) throws Exception {
        var bounds = List.of(plan.x, plan.y, plan.x + plan.w, plan.y + plan.h);
        Path previous = null;
        var references = new HashMap<String,Path>();
        if (request.get("baseline_manifest") != null) {
            previous = NativeBridgeAssets.input(workspace, request.get("baseline_manifest"), ".json");
            if (!(JSONValue.parse(Files.readString(previous)) instanceof Map<?,?> baseline)
                || !JSONValue.toJSONString(bounds).equals(JSONValue.toJSONString(baseline.get("crop")))
                || !String.valueOf(plan.width).equals(String.valueOf(baseline.get("width")))
                || !String.valueOf(plan.height).equals(String.valueOf(baseline.get("height")))
                || !(baseline.get("frames") instanceof List<?> baselineFrames))
                throw new IllegalArgumentException("Baseline dimensions or crop differ");
            for (var frame : plan.frames) {
                var match = baselineFrames.stream().filter(v -> v instanceof Map<?,?> m && frame.name.equals(m.get("name")))
                    .map(v -> (Map<?,?>) v).findFirst().orElseThrow(() -> new IllegalArgumentException("Missing baseline pose " + frame.name));
                if (!frame.parameters.equals(match.get("parameters")))
                    throw new IllegalArgumentException("Baseline parameters differ for " + frame.name);
                references.put(frame.name, NativeBridgeAssets.input(workspace, previous.resolveSibling(frame.name + ".png").toString(), ".png"));
            }
        }
        var result = new ArrayList<Object>();
        for (var frame : plan.frames) {
            // NativeRenderSession returns an owned copy. Keep at most one detached
            // frame per worker while encoding and reading the baseline.
            var image = onEditorThread(() -> plan.renderer.render(frame.pose), 0);
            if (image == null) throw new IllegalStateException("Cubism returned no image for " + frame.name);
            String file = frame.name + ".png";
            Path target = staging.resolve(file);
            try {
                var pixels = image.getJBufferedImage().getSubimage(plan.x, plan.y, plan.w, plan.h);
                if (request.get("crop") == null) image.writeImage(target.toFile());
                else ImageIO.write(pixels, "PNG", target.toFile());
                var row = map("name", frame.name, "path", file, "parameters", frame.parameters, "sha256", hash(target));
                if (previous != null) {
                    String diff = frame.name + ".diff.png";
                    var reference = readBaseline(references.get(frame.name), plan.w, plan.h);
                    try { row.put("difference", difference(pixels, reference, staging.resolve(diff))); }
                    finally { reference.flush(); }
                }
                result.add(row);
            } finally { image.flush(); }
        }
        var manifest = map("width", plan.width, "height", plan.height, "crop", bounds, "base_pose", plan.base, "frames", result);
        NativeBridgeAssets.writeNew(staging.resolve("manifest.json"), JSONValue.toJSONString(manifest));
    }

    private static String hash(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = new java.security.DigestInputStream(Files.newInputStream(path), digest)) {
            stream.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static BufferedImage readBaseline(Path file, int width, int height) throws IOException {
        try (var stream = ImageIO.createImageInputStream(file.toFile())) {
            if (stream == null) throw new IOException("Cannot open baseline PNG: " + file.getFileName());
            var readers = ImageIO.getImageReadersByFormatName("PNG");
            if (!readers.hasNext()) throw new IOException("PNG reader unavailable");
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                if (reader.getWidth(0) != width || reader.getHeight(0) != height)
                    throw new IllegalArgumentException("Baseline image dimensions differ: " + file.getFileName());
                return reader.read(0);
            } finally { reader.dispose(); }
        }
    }

    private static Object difference(BufferedImage actual, BufferedImage expected, Path path) throws Exception {
        int width = actual.getWidth(), height = actual.getHeight();
        if (expected == null || expected.getWidth() != width || expected.getHeight() != height)
            throw new IllegalArgumentException("Baseline image dimensions differ");
        var diff = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        long changed = 0;
        int maximum = 0;
        int[] a = new int[width];
        int[] b = new int[width];
        for (int y = 0; y < height; y++) {
            actual.getRGB(0, y, width, 1, a, 0, width);
            expected.getRGB(0, y, width, 1, b, 0, width);
            for (int x = 0; x < width; x++) {
                int pixel = 0;
                for (int shift = 0; shift < 32; shift += 8)
                    pixel = Math.max(pixel, Math.abs(((a[x] >>> shift) & 255) - ((b[x] >>> shift) & 255)));
                if (pixel != 0) changed++;
                maximum = Math.max(maximum, pixel);
                a[x] = 0xff000000 | pixel * 0x010101;
            }
            diff.setRGB(0, y, width, 1, a, 0, width);
        }
        try { ImageIO.write(diff, "PNG", path.toFile()); }
        finally { diff.flush(); }
        return map("path", path.getFileName().toString(), "changed_pixels", changed, "max_channel_delta", maximum);
    }
}
