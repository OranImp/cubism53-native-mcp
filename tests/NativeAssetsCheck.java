package cubism53.mcp;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import kotlin.Pair;

public final class NativeAssetsCheck {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("cubism-assets-check-").toRealPath();
        try {
            Path workspace = Files.createDirectory(root.resolve("workspace"));
            Path outside = Files.createDirectory(root.resolve("outside"));
            checkOutput(workspace, outside);
            checkImages(workspace);
            checkAttachments(workspace);
            checkRuntime(workspace);
            System.out.println("NATIVE_ASSETS_SECURITY_OK");
        } finally { NativeBridgeAssets.removeStaging(root); }
    }

    private static void checkOutput(Path workspace, Path outside) throws Exception {
        reject("Invalid file basename", () -> NativeBridgeAssets.output(workspace, "NUL.png", ".png"));
        reject("Invalid file basename", () -> NativeBridgeAssets.output(workspace, "COM1/pose.png", ".png"));
        reject("Invalid file basename", () -> NativeBridgeAssets.output(workspace, "folder./pose.png", ".png"));

        Path destination = NativeBridgeAssets.output(workspace, "nested/pose.png", ".png");
        NativeBridgeAssets.publishFile(workspace, destination, ".png", path -> Files.writeString(path, "original"));
        reject(destination.toString(), () -> NativeBridgeAssets.publishFile(workspace, destination, ".png",
            path -> Files.writeString(path, "replacement")));
        if (!"original".equals(Files.readString(destination))) throw new AssertionError("Output was replaced");

        Path failed = workspace.resolve("nested/failed.png");
        reject("injected write failure", () -> NativeBridgeAssets.publishFile(workspace, failed, ".png", path -> {
            Files.writeString(path, "partial");
            throw new IOException("injected write failure");
        }));
        try (var files = Files.list(failed.getParent())) {
            if (!files.toList().equals(List.of(destination))) throw new AssertionError("The failed write left output files");
        }

        Path model = workspace.resolve("nested/claimed.cmo3");
        reject("native refusal", () -> NativeBridgeAssets.saveNew(workspace, model, () -> {
            reject(model.toString(), () -> Files.createFile(model));
            throw new IOException("native refusal");
        }));
        if (Files.exists(model)) throw new AssertionError("Native refusal left an empty destination");
        NativeBridgeAssets.saveNew(workspace, model, () -> {
            var temporary = Files.writeString(Files.createTempFile(model.getParent(), "native", ".tmp"), "model");
            Files.move(temporary, model, StandardCopyOption.REPLACE_EXISTING);
            return null;
        });
        if (!"model".equals(Files.readString(model))) throw new AssertionError("Native replacement did not survive cleanup");

        if (!com.sun.jna.Platform.isWindows()) return;
        Path parent = Files.createDirectory(workspace.resolve("race"));
        Path target = NativeBridgeAssets.output(workspace, "race/nested/pose.png", ".png");
        Files.move(parent, workspace.resolve("detached"));
        junction(parent, outside);
        reject("Output directory changed", () -> NativeBridgeAssets.publishFile(workspace, target, ".png",
            path -> Files.writeString(path, "would leave the workspace")));
        if (Files.exists(outside.resolve("nested"))) throw new AssertionError("Writer created an external directory");
        Files.delete(parent);

        Path safe = Files.createDirectory(workspace.resolve("safe"));
        junction(parent, safe);
        var canonical = NativeBridgeAssets.output(workspace, "race/pose.png", ".png");
        if (!canonical.equals(safe.resolve("pose.png"))) throw new AssertionError("In-workspace alias was not resolved");
        NativeBridgeAssets.publishFile(workspace, canonical, ".png", path -> {
            try {
                Files.move(safe, workspace.resolve("renamed"));
                throw new AssertionError("An output parent was replaceable while publishing");
            } catch (FileSystemException expected) {
                Files.writeString(path, "protected");
            }
        });
        Files.delete(parent);
    }

    private static void checkImages(Path workspace) throws Exception {
        Path good = workspace.resolve("small.png");
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, 0x40802010);
        if (!ImageIO.write(image, "PNG", good.toFile())) throw new AssertionError("PNG writer unavailable");
        var decoded = NativeBridgeAssets.readPng(workspace, good.toString()).pixels();
        if (decoded.getRGB(1, 1) != image.getRGB(1, 1)) throw new AssertionError("PNG pixels changed");

        byte[] png = Files.readAllBytes(good);
        ByteBuffer.wrap(png).putInt(16, 100_000);
        var crc = new CRC32();
        crc.update(png, 12, 17);
        ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());
        Files.write(workspace.resolve("oversized.png"), png);
        reject("PNG dimensions", () -> NativeBridgeAssets.readPng(workspace, "oversized.png"));

        Path disguised = workspace.resolve("disguised.png");
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "JPEG", disguised.toFile());
        reject("PNG", () -> NativeBridgeAssets.readPng(workspace, "disguised.png"));

        byte[] psd = ByteBuffer.allocate(26).putInt(0x38425053).putShort((short) 1).position(12)
            .putShort((short) 4).putInt(1).putInt(100_000).putShort((short) 8).array();
        Files.write(workspace.resolve("oversized.psd"), psd);
        reject("Invalid PSD header", () -> NativeBridgeAssets.snapshotPsd(workspace, "oversized.psd"));
    }

    private static void checkAttachments(Path workspace) throws Exception {
        var check = new NativeBridgeAssets.RuntimeCheck("test", "5.3", "", Set.of("ParamX"), Set.of(), List.of(), List.of());
        String valid = "{\"Parameters\":[{\"Id\":\"ParamX\",\"Value\":1}]}";
        Files.writeString(workspace.resolve("ok.exp3.json"), valid);
        var diagnostics = new ArrayList<Map<String,Object>>();
        var assets = NativeBridgeAssets.runtimeAssets(check, workspace, expressions("ok"), diagnostics);
        if (!diagnostics.isEmpty()
                || !Arrays.equals(valid.getBytes(StandardCharsets.UTF_8), assets.files().get("expressions/ok.exp3.json")))
            throw new AssertionError("A valid SDK attachment was changed or rejected");

        Files.writeString(workspace.resolve("duplicate.exp3.json"),
            "{\"Parameters\":[{\"Id\":\"Unknown\",\"Id\":\"ParamX\",\"Value\":1}]}");
        Files.writeString(workspace.resolve("deep.exp3.json"),
            "{\"Parameters\":[],\"extra\":" + "[".repeat(64) + "0" + "]".repeat(64) + "}");
        putLargeFile(workspace.resolve("too-big.exp3.json"), valid, 17);
        NativeBridgeAssets.runtimeAssets(check, workspace, expressions("duplicate", "deep", "too-big"), diagnostics);
        expectDiagnostics(diagnostics, "Duplicate JSON key: Id", "nesting limit", "byte limit");

        for (String name : List.of("one", "two", "three")) putLargeFile(workspace.resolve(name + ".exp3.json"), valid, 11);
        diagnostics.clear();
        assets = NativeBridgeAssets.runtimeAssets(check, workspace, expressions("one", "two", "three"), diagnostics);
        expectDiagnostics(diagnostics, "byte limit");
        if (assets.files().size() != 2) throw new AssertionError("The request attachment limit was not enforced");
    }

    private static Map<String,Object> expressions(String... names) {
        var rows = Arrays.stream(names).map(name -> Map.of("name", name, "path", name + ".exp3.json")).toList();
        return Map.of("expressions", rows);
    }

    private static void putLargeFile(Path path, String prefix, int mib) throws IOException {
        try (var output = Files.newOutputStream(path)) {
            output.write(prefix.getBytes(StandardCharsets.UTF_8));
            byte[] spaces = new byte[1024 * 1024];
            Arrays.fill(spaces, (byte) ' ');
            for (int i = 0; i < mib; i++) output.write(spaces);
        }
    }

    private static void expectDiagnostics(List<Map<String,Object>> errors, String... expected) {
        if (errors.size() != expected.length) throw new AssertionError("Unexpected diagnostics " + errors);
        for (int i = 0; i < expected.length; i++)
            if (!errors.get(i).get("message").toString().contains(expected[i])) throw new AssertionError(errors);
    }

    private static void checkRuntime(Path workspace) throws Exception {
        byte[] moc = "MOC3".getBytes(StandardCharsets.US_ASCII);
        byte[] model = "{\"FileReferences\":{\"Moc\":\"unit.moc3\",\"Textures\":[\"tex/texture_00.png\"]}}"
            .getBytes(StandardCharsets.UTF_8);
        var files = List.of(new Pair<>("unit.moc3", moc), new Pair<>("unit.model3.json", model),
            new Pair<>("tex/texture_00.png", new byte[]{1}));
        var empty = new NativeBridgeAssets.RuntimeAssets(Map.of(), Map.of(), List.of(), null);
        var export = new NativeBridgeAssets.RuntimeExport("unit", "5.3", List.of(), empty, files);
        NativeBridgeAssets.writeRuntime(export, workspace, workspace.resolve("package"), 0);
        if (!Files.isRegularFile(workspace.resolve("package/unit.model3.json"))) throw new AssertionError("Missing Model3");

        var bad = new NativeBridgeAssets.RuntimeAssets(Map.of("../escape.exp3.json", new byte[]{1}), Map.of(), List.of(), null);
        var badExport = new NativeBridgeAssets.RuntimeExport("unit", "5.3", List.of(), bad, files);
        Path target = workspace.resolve("broken-package");
        reject("Unsafe native package member", () -> NativeBridgeAssets.writeRuntime(badExport, workspace, target, 0));
        if (Files.exists(target) || Files.exists(workspace.resolve("escape.exp3.json")))
            throw new AssertionError("Broken or escaped package was published");
    }

    private static void junction(Path path, Path target) throws Exception {
        var process = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
            "New-Item -ErrorAction Stop -ItemType Junction -Path $env:CUBISM_CHECK_LINK -Target $env:CUBISM_CHECK_TARGET | Out-Null");
        process.environment().put("CUBISM_CHECK_LINK", path.toString());
        process.environment().put("CUBISM_CHECK_TARGET", target.toString());
        if (process.inheritIO().start().waitFor() != 0) throw new AssertionError("Could not create the test junction");
    }

    @FunctionalInterface private interface Task { void run() throws Exception; }

    private static void reject(String reason, Task task) throws Exception {
        try {
            task.run();
            throw new AssertionError("Expected rejection: " + reason);
        } catch (IOException | IllegalArgumentException error) {
            if (!error.getMessage().contains(reason)) throw error;
        }
    }
}
