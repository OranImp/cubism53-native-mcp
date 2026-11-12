package cubism53.mcp;

import com.live2d.cubism.doc.model.param.CParameterSet;
import com.live2d.cubism.doc.modeling.CModelingDocument;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import org.json.simple.JSONValue;

import static cubism53.mcp.NativeBridge.*;

final class NativeBridgeRendering {
    private record Frame(String name, CParameterSet pose, Map<String,Double> parameters) {}

    static Object renderSet(CModelingDocument doc, Path workspace, Map<String,Object> request) throws Exception {
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
        var output = NativeBridgeAssets.output(workspace, request.get("directory"), "");
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

        var bounds = List.of(x, y, x + w, y + h);
        Path previous = null;
        if (request.get("baseline_manifest") != null) {
            previous = NativeBridgeAssets.input(workspace, request.get("baseline_manifest"), ".json");
            if (!(JSONValue.parse(Files.readString(previous)) instanceof Map<?,?> baseline)
                || !JSONValue.toJSONString(bounds).equals(JSONValue.toJSONString(baseline.get("crop")))
                || !String.valueOf(width).equals(String.valueOf(baseline.get("width")))
                || !String.valueOf(height).equals(String.valueOf(baseline.get("height")))
                || !(baseline.get("frames") instanceof List<?> baselineFrames))
                throw new IllegalArgumentException("Baseline dimensions or crop differ");
            for (var frame : frames) {
                var match = baselineFrames.stream().filter(v -> v instanceof Map<?,?> m && frame.name.equals(m.get("name")))
                    .map(v -> (Map<?,?>) v).findFirst().orElseThrow(() -> new IllegalArgumentException("Missing baseline pose " + frame.name));
                if (!frame.parameters.equals(match.get("parameters")))
                    throw new IllegalArgumentException("Baseline parameters differ for " + frame.name);
                NativeBridgeAssets.input(workspace, previous.resolveSibling(frame.name + ".png").toString(), ".png");
            }
        }
        Files.createDirectories(output.getParent());
        Path staging = Files.createTempDirectory(output.getParent(), ".cubism-render-");
        try {
            var result = new ArrayList<Object>();
            try (var renderer = new NativeRenderSession(doc.getDocumentUID(), source, width, height, frames.get(0).pose)) {
                for (var frame : frames) {
                    var image = renderer.render(frame.pose);
                    if (image == null) throw new IllegalStateException("Cubism returned no image for " + frame.name);
                    String file = frame.name + ".png";
                    Path target = staging.resolve(file);
                    try {
                        var pixels = image.getJBufferedImage().getSubimage(x, y, w, h);
                        if (crop == null) image.writeImage(target.toFile());
                        else ImageIO.write(pixels, "PNG", target.toFile());
                        var row = map("name", frame.name, "path", file, "parameters", frame.parameters, "sha256", hash(target));
                        if (previous != null) {
                            String diff = frame.name + ".diff.png";
                            var reference = ImageIO.read(previous.resolveSibling(file).toFile());
                            row.put("difference", difference(pixels, reference, staging.resolve(diff)));
                        }
                        result.add(row);
                    } finally { image.flush(); }
                }
            }
            var manifest = map("width", width, "height", height, "crop", bounds, "base_pose", base, "frames", result);
            NativeBridgeAssets.writeNew(staging.resolve("manifest.json"), JSONValue.toJSONString(manifest));
            NativeBridgeAssets.publishDirectory(staging, output);
            return map("directory", output.toString(), "manifest", output.resolve("manifest.json").toString(), "frame_count", frames.size());
        } finally { NativeBridgeAssets.removeStaging(staging); }
    }

    private static String hash(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = new java.security.DigestInputStream(Files.newInputStream(path), digest)) {
            stream.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Object difference(BufferedImage actual, BufferedImage expected, Path path) throws Exception {
        int width = actual.getWidth(), height = actual.getHeight();
        if (expected == null || expected.getWidth() != width || expected.getHeight() != height)
            throw new IllegalArgumentException("Baseline image dimensions differ");
        var diff = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        long changed = 0;
        int maximum = 0;
        for (int y = 0; y < height; y++) {
            int[] a = actual.getRGB(0, y, width, 1, null, 0, width);
            int[] b = expected.getRGB(0, y, width, 1, null, 0, width);
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
        ImageIO.write(diff, "PNG", path.toFile());
        return map("path", path.getFileName().toString(), "changed_pixels", changed, "max_channel_delta", maximum);
    }
}
