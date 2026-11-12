package cubism53.mcp;

import com.live2d.cubism.CEAppCtrl;
import com.live2d.cubism.doc.model.CModelSource;
import com.live2d.cubism.doc.model.exporter.CModelExportSettingDialogData;
import com.live2d.cubism.doc.model.exporter.CModelJsonFormat;
import com.live2d.cubism.doc.model.exporter.dD;
import com.live2d.cubism.doc.model.extension.textureInput.CTextureInput_TextureAtlasRegion;
import com.live2d.cubism.doc.model.extension.textureInput.CTextureInput_ModelImage;
import com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource;
import com.live2d.cubism.doc.model.extension.textureInput.inputFilter.CLayerInputData;
import com.live2d.cubism.doc.model.texture.LayeredImageWrapper;
import com.live2d.cubism.doc.model.texture.modelImage.CModelImage;
import com.live2d.cubism.doc.model.texture.modelImage.CModelImageGroup;
import com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas;
import com.live2d.cubism.doc.modeling.CModelingDocument;
import com.live2d.cubism.doc.modeling.editor.Editor_Texture;
import com.live2d.cubism.doc.resources.ACLayerEntry;
import com.live2d.cubism.doc.resources.CImageDocumentProjectEntry;
import com.live2d.cubism.doc.resources.CLayer;
import com.live2d.cubism.doc.resources.CLayerGroup;
import com.live2d.cubism.doc.resources.CLayeredImage;
import com.live2d.cubism.process.psd.CEPsdImport_Import;
import com.live2d.type.CAffine;
import com.live2d.undo.GroupUndo;
import com.live2d.undo.LambdaUndo;
import com.live2d.undo.SimpleUndo;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;
import kotlin.Unit;

import static cubism53.mcp.NativeBridge.*;

/** Native file, material and texture-atlas operations for the pinned Cubism build. */
final class NativeBridgeAssets {
    private NativeBridgeAssets() {}

    static Path input(Path workspace, Object value, String suffix) throws IOException {
        Path path = workspace.resolve(nonblank(value, "path")).toRealPath();
        if (!path.startsWith(workspace) || !Files.isRegularFile(path) || !hasSuffix(path, suffix))
            throw new IllegalArgumentException("A workspace " + suffix + " file is required");
        return path;
    }

    static Path output(Path workspace, Object value, String suffix) throws IOException {
        Path path = workspace.resolve(nonblank(value, "destination")).normalize();
        if (path.equals(workspace) || !path.startsWith(workspace) || !hasSuffix(path, suffix))
            throw new IllegalArgumentException("A workspace destination" + (suffix.isEmpty() ? "" : " ending in " + suffix) + " is required");
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
            throw new FileAlreadyExistsException(path.toString());
        Path parent = path.getParent();
        while (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) parent = parent.getParent();
        if (parent == null || !parent.toRealPath().startsWith(workspace))
            throw new IllegalArgumentException("Destination resolves outside the workspace");
        return path;
    }

    private static boolean hasSuffix(Path path, String suffix) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix);
    }

    static void writeNew(Path path, String contents) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    static Object replaceResized(CModelingDocument doc, Path workspace, Map<String,Object> request) throws Exception {
        var source = doc.getModelSource();
        var item = findObject(source, (String) request.get("object_guid"));
        if (!(item instanceof CArtMeshSource mesh)) throw new IllegalArgumentException("ArtMesh required");
        var extension = mesh.getTextureInputExtension();
        if (!(extension.getCurrentTextureInputData() instanceof CTextureInput_ModelImage input))
            throw new IllegalArgumentException("Resizable replacement requires model-image texture mode");
        var previous = input.getModelImage();
        var path = input(workspace, request.get("path"), ".png");
        var pixels = javax.imageio.ImageIO.read(path.toFile());
        if (pixels == null) throw new IllegalArgumentException("Image could not be decoded");
        var offsets = (List<?>) request.get("origin_offset");
        if (offsets.size() != 2) throw new IllegalArgumentException("Origin offset requires [x,y]");
        var dx = ((Number) offsets.get(0)).intValue();
        var dy = ((Number) offsets.get(1)).intValue();
        var manager = source.getTextureManager();
        var placement = (Map<?,?>) request.get("atlas_placement");
        if (!manager.getTextureAtlases().isEmpty() && placement == null)
            throw new IllegalArgumentException("Provide an atlas placement for the replacement material");
        var page = placement == null ? null : atlasPage(source, placement.get("page_guid"));
        var cacheParam = com.live2d.graphics.cachedImage.CachedImageParam.Companion.a();
        var oldImage = input.getCachedImage(cacheParam);
        var originalUv = mesh.getUvs().clone();
        var sampled = originalUv.clone();
        oldImage.f().createInverse().transform(sampled, 0, sampled, 0, sampled.length / 2);
        for (int i = 0; i < sampled.length; i += 2) {
            sampled[i] = sampled[i] * oldImage.b().getWidth() - dx;
            sampled[i + 1] = sampled[i + 1] * oldImage.b().getHeight() - dy;
            if (sampled[i] < -.001f || sampled[i + 1] < -.001f || sampled[i] > pixels.getWidth() + .001f || sampled[i + 1] > pixels.getHeight() + .001f)
                throw new IllegalArgumentException("The replacement would crop existing mesh samples");
        }
        var packed = new java.awt.image.BufferedImage(pixels.getWidth(), pixels.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        packed.setRGB(0, 0, pixels.getWidth(), pixels.getHeight(),
            pixels.getRGB(0, 0, pixels.getWidth(), pixels.getHeight(), null, 0, pixels.getWidth()), 0, pixels.getWidth());
        var transform = new CAffine(previous.getModelImageLocalToCanvasTransform());
        transform.translate((float) dx, (float) dy);
        var undo = new GroupUndo("Native bridge: resize mesh texture");
        undo.plusAssign(new SimpleUndo("Replacement material and atlas", manager, new com.live2d.core.a()));
        var oldGuid = previous.getGuid();
        var newGuid = new com.live2d.type.CModelImageGuid();
        try {
            var resource = new com.live2d.graphics.CImageResource(packed, false);
            var material = CModelImage.Companion.a(source, previous.getName() + " (resized)", resource, "Native original artwork", newGuid);
            var transformPort = com.live2d.cubism.doc.model.extension.textureInput.inputFilter.ModelImageFilterEnv.Companion.d();
            var output = material.getInputFilter().getExternalOutputs().get(transformPort.getId());
            output.getFilter().setInput(transformPort, new com.live2d.graphics.filter.concreteConnector.ConstValueConnector<>(transform));
            material.reset();
            if (page != null) {
                page.getModelImages().add(new CTextureAtlas.ModelImageEntry(page, newGuid, placementTransform(material, placement)));
                validateAtlas(source, page);
            }
            var newImage = material.getCachedImage(cacheParam);
            for (int i = 0; i < sampled.length; i += 2) {
                sampled[i] /= newImage.b().getWidth();
                sampled[i + 1] /= newImage.b().getHeight();
            }
            newImage.f().transform(sampled, 0, sampled, 0, sampled.length / 2);
            undo.plusAssign(new LambdaUndo<>("Replace ArtMesh image input", input,
                edit -> { input.replaceModelInput(newGuid); mesh.setUvs(sampled.clone()); return Unit.INSTANCE; },
                edit -> { input.replaceModelInput(oldGuid); mesh.setUvs(originalUv.clone()); return Unit.INSTANCE; }, null));
            refreshResized(source, mesh, sampled);
        } catch (RuntimeException | Error error) {
            undo.undo();
            refreshResized(source, mesh, originalUv);
            refresh(doc);
            throw error;
        }
        undo.addUndoRedoListener(event -> {
            var current = mesh.getTextureInputExtension().getModelImageInput();
            refreshResized(source, mesh, newGuid.equals(current.getModelImageGuid()) ? sampled : originalUv);
            return Unit.INSTANCE;
        });
        finishEdit(doc, undo);
        return map("object_guid", mesh.getGuid().getUuidString(), "old_material_guid", oldGuid.getUuidString(),
            "material_guid", newGuid.getUuidString(), "width", pixels.getWidth(), "height", pixels.getHeight(),
            "origin_offset", offsets);
    }

    private static void refreshResized(CModelSource source, CArtMeshSource mesh, float[] uvs) {
        refreshTextures(source);
        mesh.setUvs(uvs.clone());
        mesh.setMeshUpdatedFlag__testImpl();
    }

    private static CLayeredImage readPsd(Path path) {
        var entry = new CImageDocumentProjectEntry(path.toFile());
        entry.open();
        return entry.getOpenedImageDocument().a();
    }

    static Object importPsd(CEAppCtrl app, Path workspace, Map<String,Object> request) throws Exception {
        Path path = input(workspace, request.get("path"), ".psd");
        var raw = readPsd(path);
        var source = CEPsdImport_Import.a.a(raw, path.toFile(), psdOption(), new GroupUndo("Native bridge: import PSD"));
        var doc = com.live2d.cubism.appCtrlImpl.ar.a.a(app, source.getName(), source, null, true, null, false);
        var result = describe(doc);
        result.put("source_psd_guid", raw.getGuid().getUuidString());
        result.put("layers", layerMaterials(source, raw));
        return result;
    }

    private static CEPsdImport_Import.a psdOption() throws ReflectiveOperationException {
        // Kotlin's singleton and the nested option type share the obfuscated name "a".
        return (CEPsdImport_Import.a) Class.forName("com.live2d.cubism.process.psd.CEPsdImport_Import$a")
            .getField("a").get(null);
    }

    private record LayerRow(String key, List<String> path, CLayer layer) {
        String id() { return layer.getLayerIdentifier().getLayerId(); }
        Map<String,Object> info() { return map("key", key, "path", path, "layer_id", id()); }
    }

    private static List<LayerRow> layers(CLayeredImage raw) {
        var result = new ArrayList<LayerRow>();
        collectLayers(raw.getRootLayer(), List.of(), result);
        return result;
    }

    private static void collectLayers(CLayerGroup parent, List<String> path, List<LayerRow> result) {
        for (var entry : parent.getChildren()) {
            var childPath = new ArrayList<>(path);
            childPath.add(entry.getName());
            if (entry instanceof CLayerGroup group) collectLayers(group, childPath, result);
            else if (entry instanceof CLayer layer) result.add(new LayerRow("layer-" + result.size(), childPath, layer));
        }
    }

    private static List<Object> layerMaterials(CModelSource source, CLayeredImage raw) {
        var result = new ArrayList<Object>();
        for (var row : layers(raw)) {
            var materials = source.getTextureManager().getHandler().a(row.layer);
            var meshes = source.getAllArtMeshes().stream().filter(mesh -> {
                var input = mesh.getTextureInputExtension().getModelImageInput();
                return input != null && materials.contains(input.getModelImageGuid());
            }).map(mesh -> mesh.getGuid().getUuidString()).toList();
            result.add(map("layer", row.info(), "material_guids", materials.stream().map(guid -> guid.getUuidString()).sorted().toList(),
                "art_mesh_guids", meshes));
        }
        return result;
    }

    private record Match(LayerRow oldLayer, LayerRow incoming) {
        Object info() { return map("existing", oldLayer.info(), "incoming", incoming.info()); }
    }

    private record ReimportPlan(String id, CLayeredImage previous, CLayeredImage incoming, List<Match> matches,
            List<LayerRow> added, List<LayerRow> missing, List<Object> conflicts) {
        Map<String,Object> info() {
            return map("plan_id", id, "source_psd_guid", previous.getGuid().getUuidString(),
                "updates", matches.stream().map(Match::info).toList(), "additions", added.stream().map(LayerRow::info).toList(),
                "missing", missing.stream().map(LayerRow::info).toList(), "conflicts", conflicts);
        }
    }

    private static CLayeredImage sourcePsd(CModelSource source, Object guid) {
        var images = source.getTextureManager().getRawImages();
        if (guid instanceof String id) {
            return images.stream().map(LayeredImageWrapper::getImage)
                .filter(raw -> id.equals(raw.getGuid().getUuidString())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Source PSD GUID not found: " + id));
        }
        var candidates = images.stream().filter(raw -> !raw.isReplaced()).map(LayeredImageWrapper::getImage).toList();
        if (candidates.size() != 1) {
            var descriptions = candidates.stream().map(raw -> raw.getName() + " (" + raw.getGuid().getUuidString() + ")").toList();
            throw new IllegalArgumentException("Select source_psd_guid from " + descriptions);
        }
        return candidates.get(0);
    }

    private static ReimportPlan plan(CModelingDocument doc, Path path, Map<String,Object> request) throws Exception {
        var previous = sourcePsd(doc.getModelSource(), request.get("source_psd_guid"));
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = new DigestInputStream(Files.newInputStream(path), digest)) {
            stream.transferTo(java.io.OutputStream.nullOutputStream());
        }
        var incoming = readPsd(path);
        var oldLayers = layers(previous);
        var newLayers = layers(incoming);
        var explicit = (Map<?,?>) request.get("layer_map");
        var matches = new ArrayList<Match>();
        var added = new ArrayList<LayerRow>();
        var conflicts = new ArrayList<Object>();
        var used = new HashSet<LayerRow>();
        var explicitlyMatched = new HashSet<LayerRow>();
        for (var entry : explicit.entrySet()) {
            var newRow = newLayers.stream().filter(row -> row.key.equals(entry.getKey())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown incoming layer key: " + entry.getKey()));
            var oldRow = oldLayers.stream().filter(row -> row.key.equals(entry.getValue())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown existing layer key: " + entry.getValue()));
            if (!used.add(oldRow)) throw new IllegalArgumentException("Two explicit mappings use the same existing layer");
            explicitlyMatched.add(newRow);
            matches.add(new Match(oldRow, newRow));
        }
        for (var newRow : newLayers) {
            if (explicitlyMatched.contains(newRow)) continue;
            List<LayerRow> candidates = List.of();
            if (newRow.id() != null && !newRow.id().isBlank() && !newRow.id().equals("0"))
                candidates = oldLayers.stream().filter(row -> newRow.id().equals(row.id())).toList();
            if (candidates.isEmpty()) candidates = oldLayers.stream().filter(row -> row.path.equals(newRow.path)).toList();
            if (candidates.isEmpty()) {
                added.add(newRow);
            } else if (candidates.size() == 1 && used.add(candidates.get(0))) {
                matches.add(new Match(candidates.get(0), newRow));
            } else {
                conflicts.add(map("incoming", newRow.info(), "existing_candidates", candidates.stream().map(LayerRow::info).toList()));
            }
        }
        var missing = oldLayers.stream().filter(row -> !used.contains(row)).toList();
        var model = doc.getModelSource();
        var snapshot = map("document", doc.getDocumentUID(), "undo", doc.getUndoManager().getCurrentPos(),
            "source", previous.getGuid().getUuidString(), "layers", layerMaterials(model, previous),
            "matches", matches.stream().map(Match::info).toList());
        digest.update(org.json.simple.JSONValue.toJSONString(snapshot).getBytes(StandardCharsets.UTF_8));
        return new ReimportPlan(HexFormat.of().formatHex(digest.digest()), previous, incoming, matches, added, missing, conflicts);
    }

    static Object reimportPsd(CModelingDocument doc, Path workspace, Map<String,Object> request, String op) throws Exception {
        Path path = input(workspace, request.get("path"), ".psd");
        var plan = plan(doc, path, request);
        if ("plan_psd_reimport".equals(op)) return plan.info();
        if (!plan.id.equals(request.get("plan_id"))) throw new IllegalArgumentException("PSD plan is stale; run preflight again");
        if (!plan.conflicts.isEmpty()) throw new IllegalArgumentException("Resolve PSD layer conflicts with explicit mappings first");
        var source = doc.getModelSource();
        var manager = source.getTextureManager();
        var oldGuid = plan.previous.getGuid();
        var newGuid = plan.incoming.getGuid();
        var replacements = new HashMap<com.live2d.type.CLayerGuid,CLayer>();
        for (var match : plan.matches) replacements.put(match.oldLayer.layer.getGuid(), match.incoming.layer);
        var undo = new GroupUndo("Native bridge: reimport PSD");
        List<String> changed = new ArrayList<>();
        try {
            var wrapper = new LayeredImageWrapper(plan.incoming, System.currentTimeMillis(), path.toFile().lastModified(), false);
            undo.plusAssign(manager.getHandler().a(wrapper, 0));
            for (var material : List.copyOf(manager.getAllModelImages())) {
                var env = material.getInputFilterEnv();
                if (!env.getHasLayerInputData()) continue;
                var selector = env.getLayerInputData();
                var oldInputs = selector.get(oldGuid);
                if (oldInputs == null || oldInputs.stream().noneMatch(entry -> replacements.containsKey(entry.getLayer().getGuid())))
                    continue;
                var inputs = oldInputs.stream().map(entry -> {
                    var replacement = replacements.get(entry.getLayer().getGuid());
                    return replacement == null ? entry : new CLayerInputData(replacement, new CAffine(entry.getAffine()), entry.getClippingOnTexturePx());
                }).toList();
                var active = env.getCurrentImageGuid();
                undo.plusAssign(new LambdaUndo<>("Replace PSD layer selection", selector,
                    edit -> { selector.put(newGuid, inputs); env.setCurrentImageGuid(newGuid); material.reset(); return Unit.INSTANCE; },
                    edit -> { selector.remove(newGuid); env.setCurrentImageGuid(active); material.reset(); return Unit.INSTANCE; }, null));
                changed.add(material.getGuid().getUuidString());
            }
            if (!plan.added.isEmpty()) {
                var group = CModelImageGroup.Companion.a(plan.incoming.getName(), newGuid);
                undo.plusAssign(manager.getHandler().a(group, 0));
                Set<ACLayerEntry> skip = new HashSet<>();
                for (var match : plan.matches) skip.add(match.incoming.layer);
                CEPsdImport_Import.a.a(source, plan.incoming, plan.incoming.getName(), skip, group,
                    source.getHandler().c(), psdOption(), undo);
            }
            refreshTextures(source);
        } catch (Exception | Error error) {
            undo.undo();
            refreshTextures(source);
            refresh(doc);
            throw error;
        }
        undo.addUndoRedoListener(event -> { refreshTextures(source); return Unit.INSTANCE; });
        finishEdit(doc, undo);
        var result = plan.info();
        result.put("new_source_psd_guid", newGuid.getUuidString());
        result.put("updated_material_guids", changed);
        result.put("layers", layerMaterials(source, plan.incoming));
        return result;
    }

    static Object exportPhysics(CModelingDocument doc, Path workspace, Map<String,Object> request) throws Exception {
        Path path = output(workspace, request.get("path"), ".physics3.json");
        var source = doc.getModelSource();
        var settings = source.getPhysicsSettingsSourceSet();
        var verified = settings.createVerifiedSettingSet(source, false);
        var serializer = Class.forName("com.live2d.cubism.doc.gameData.a.a");
        String json = (String) serializer.getMethod("a", CModelSource.class, verified.getClass())
            .invoke(serializer.getConstructor().newInstance(), source, verified);
        if (!(org.json.simple.JSONValue.parse(json) instanceof Map<?,?> result))
            throw new IllegalStateException("Cubism did not generate a valid physics export");
        writeNew(path, json);
        return map("path", path.toString(), "groups", result.get("PhysicsSettings"),
            "source_group_count", settings.getSources().size(), "bytes", Files.size(path));
    }

    static Object exportRuntime(CModelingDocument doc, Path workspace, Map<String,Object> request) throws Exception {
        var destination = output(workspace, request.get("directory"), "");
        String name = request.get("name") == null ? doc.getModelSource().getName() : nonblank(request.get("name"), "name");
        if (name.isBlank() || name.matches(".*[\\\\/:*?\"<>|].*") || name.endsWith(".") || name.endsWith(" "))
            throw new IllegalArgumentException("Export name must be a valid file basename");
        var source = doc.getModelSource();
        String sdk = targetVersion(request);
        var blockers = runtimeBlockers(source, sdk);
        var artPathWarnings = omittedArtPaths(source);
        if ("export_runtime_package".equals(request.get("operation"))) blockers.addAll(artPathWarnings);
        if (!blockers.isEmpty()) throw new IllegalArgumentException("Runtime preflight failed: " + blockers);
        var assets = runtimeAssets(source, workspace, request);
        var options = new CModelExportSettingDialogData();
        options.setVersion("5.3".equals(sdk) ? CModelExportSettingDialogData.FormatVersion.VERSION_53 : CModelExportSettingDialogData.FormatVersion.VERSION_50);
        var exporter = new com.live2d.cubism.doc.model.exporter.w();
        exporter.a(options.toMocSetting());
        var context = new com.live2d.core.a();
        var exportSource = context.a(() -> source.deepCopy(context));
        exportSource.setup();
        rebuildAtlasInputs(exportSource);
        exportSource.getTextureManager().changeModelTextureInput_toAtlas();
        var result = exporter.a(exportSource, null);
        if (result.b().isEmpty())
            throw new IOException("Cubism produced no atlas textures for " + exportSource.getAllArtMeshes().stream()
                .map(mesh -> mesh.getId() + ":" + (mesh.getTextureInputExtension().getTextureAtlasInput() != null)).toList());
        var companion = com.live2d.cubism.doc.model.exporter.b.a;
        var exportVariant = Class.forName("com.live2d.cubism.doc.model.exporter.b$b");
        var standardField = exportVariant.getDeclaredField("a");
        standardField.setAccessible(true);
        var standard = standardField.get(null);
        var packageFiles = companion.getClass().getDeclaredMethod("a", String.class, CModelSource.class, dD.class,
            CModelJsonFormat.class, int.class, boolean.class, boolean.class, boolean.class, boolean.class,
            boolean.class, boolean.class, boolean.class, exportVariant);
        packageFiles.setAccessible(true);
        var files = (List<?>) packageFiles.invoke(companion, name, source, result, options.getVersion().b(),
            options.getExportTextureScale(), assets.physics == null, true, false, true, true, false, true, standard);

        Files.createDirectories(destination.getParent());
        Path staging = Files.createTempDirectory(destination.getParent(), ".cubism-export-");
        var manifest = new ArrayList<Map<String,Object>>();
        try {
            for (var entry : files) {
                var pair = (kotlin.Pair<?,?>) entry;
                Path file = staging.resolve((String) pair.getFirst()).normalize();
                if (!file.startsWith(staging) || file.equals(staging))
                    throw new IOException("Unsafe native export path: " + pair.getFirst());
                byte[] bytes = (byte[]) pair.getSecond();
                Files.createDirectories(file.getParent());
                Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
            }
            Path model = staging.resolve(name + ".model3.json");
            if (!(org.json.simple.JSONValue.parse(Files.readString(model)) instanceof Map<?,?> root)
                    || !(root.get("FileReferences") instanceof Map<?,?> references))
                throw new IOException("Native export has no valid Model3 file references");
            if (!(references.get("Moc") instanceof String moc) || !(references.get("Textures") instanceof List<?> textures) || textures.isEmpty())
                throw new IOException("Native export is missing the MOC3 or atlas textures: " + references.keySet());
            if (!assets.files.isEmpty()) {
                for (var item : assets.files.entrySet()) {
                    var file = staging.resolve(item.getKey());
                    Files.createDirectories(file.getParent());
                    Files.write(file, item.getValue(), StandardOpenOption.CREATE_NEW);
                }
                var outputReferences = new LinkedHashMap<String,Object>();
                references.forEach((key, value) -> outputReferences.put((String) key, value));
                if (!assets.motions.isEmpty()) outputReferences.put("Motions", assets.motions);
                if (!assets.expressions.isEmpty()) outputReferences.put("Expressions", assets.expressions);
                if (assets.physics != null) outputReferences.put("Physics", assets.physics);
                var modelJson = new LinkedHashMap<String,Object>();
                root.forEach((key, value) -> modelJson.put((String) key, value));
                modelJson.put("FileReferences", outputReferences);
                Files.writeString(model, org.json.simple.JSONValue.toJSONString(modelJson), StandardCharsets.UTF_8);
                verifyExportReferences(staging, outputReferences);
            } else verifyExportReferences(staging, references);
            try (var stream = Files.newInputStream(staging.resolve(moc))) {
                if (!Arrays.equals(stream.readNBytes(4), new byte[]{'M', 'O', 'C', '3'}))
                    throw new IOException("Native export has an invalid MOC3 header");
            }
            try (var paths = Files.walk(staging)) {
                for (var file : paths.filter(Files::isRegularFile).sorted().toList())
                    manifest.add(map("path", staging.relativize(file).toString().replace('\\', '/'), "bytes", Files.size(file)));
            }
            publishDirectory(staging, destination);
        } finally { removeStaging(staging); }
        var resultInfo = map("directory", destination.toString(), "model3", destination.resolve(name + ".model3.json").toString(),
            "files", manifest, "sdk_version", sdk);
        if (!artPathWarnings.isEmpty()) resultInfo.put("warnings", artPathWarnings);
        return resultInfo;
    }

    private static String targetVersion(Map<String,Object> request) {
        String sdk = (String) request.getOrDefault("target_version", "5.3");
        if (!Set.of("5.0", "5.3").contains(sdk)) throw new IllegalArgumentException("Unsupported SDK target");
        return sdk;
    }

    static Object preflightRuntime(CModelingDocument doc, Path workspace, Map<String,Object> request) {
        String sdk = targetVersion(request);
        var blockers = runtimeBlockers(doc.getModelSource(), sdk);
        blockers.addAll(omittedArtPaths(doc.getModelSource()));
        var files = List.<String>of();
        try { files = List.copyOf(runtimeAssets(doc.getModelSource(), workspace, request).files.keySet()); }
        catch (IOException | IllegalArgumentException error) { blockers.add(error.getMessage()); }
        return map("sdk_version", sdk, "ready", blockers.isEmpty(), "blockers", blockers, "attachments", files);
    }

    private static List<String> omittedArtPaths(CModelSource source) {
        var paths = source.getAllArtPaths();
        if (paths.isEmpty()) return List.of();
        var masks = new HashSet<com.live2d.type.CDrawableGuid>();
        for (var drawable : source.getDrawableSourceSet().getSources()) {
            if (drawable.isVisibleInHierarchy()) masks.addAll(drawable.getClipGuidList());
        }
        for (var part : source.getAllParts()) {
            if (part.getUseOffscreen() && part.isVisibleInHierarchy()) masks.addAll(part.getClipGuidList());
        }
        // The pinned SDK exporter removes ArtPath sources before writing the MOC.
        return paths.stream().filter(path -> path.isVisibleInHierarchy() || masks.contains(path.getGuid()))
            .map(path -> "Cubism SDK export omits ArtPath " + path.getId() + " (" + path.getLocalName() + ")")
            .toList();
    }

    private static List<String> runtimeBlockers(CModelSource source, String sdk) {
        var blockers = new ArrayList<String>();
        var atlases = source.getTextureManager().getTextureAtlases();
        if (atlases.isEmpty()) blockers.add("Create and populate a texture atlas before exporting");
        var placed = new HashSet<com.live2d.type.CModelImageGuid>();
        var pageIds = new HashSet<com.live2d.type.CTextureAtlasGuid>();
        for (var page : atlases) {
            try { validateAtlas(source, page); }
            catch (IllegalArgumentException error) { blockers.add(page.getName() + ": " + error.getMessage()); }
            for (var entry : page.getModelImages()) placed.add(entry.getModelImageGuid());
            pageIds.add(page.getGuid());
        }
        var drawableIds = new HashSet<com.live2d.type.CDrawableGuid>();
        for (var drawable : source.getDrawableSourceSet().getSources()) drawableIds.add(drawable.getGuid());
        for (var drawable : source.getDrawableSourceSet().getSources()) {
            for (var mask : drawable.getClipGuidList()) {
                if (!drawableIds.contains(mask)) blockers.add("Missing clipping reference on " + drawable.getId() + ": " + mask.getUuidString());
            }
        }
        for (var part : source.getAllParts()) {
            if ("5.0".equals(sdk) && part.getUseOffscreen())
                blockers.add("Offscreen part requires SDK 5.3: " + part.getId());
            for (var mask : part.getClipGuidList()) {
                if (!drawableIds.contains(mask)) blockers.add("Missing clipping reference on part " + part.getId());
            }
        }
        for (var mesh : source.getAllArtMeshes()) {
            var material = mesh.getTextureInputExtension().getModelImageInput();
            if (material == null ? mesh.getTextureInputExtension().getTextureInputs().stream()
                    .noneMatch(input -> input instanceof CTextureInput_TextureAtlasRegion region && pageIds.contains(region.getTextureAtlasGuid()))
                    : !placed.contains(material.getModelImageGuid()))
                blockers.add("ArtMesh material has no atlas placement: " + mesh.getId());
        }
        return blockers;
    }

    private record RuntimeAssets(Map<String,byte[]> files, Map<String,List<Object>> motions, List<Object> expressions, String physics) {}

    private static RuntimeAssets runtimeAssets(CModelSource source, Path workspace, Map<String,Object> request) throws IOException {
        var files = new LinkedHashMap<String,byte[]>();
        var motions = new LinkedHashMap<String,List<Object>>();
        var expressions = new ArrayList<Object>();
        var names = new HashSet<String>();
        var parameters = new HashSet<String>();
        var parts = new HashSet<String>();
        for (var parameter : source.getAllParameters()) parameters.add(parameter.getId().toString());
        for (var part : source.getAllParts()) parts.add(part.getId().toString());
        for (var value : (List<?>) request.getOrDefault("motions", List.of())) {
            var row = (Map<?,?>) value;
            String group = nonblank(row.get("group"), "motion group");
            var path = input(workspace, row.get("path"), ".motion3.json");
            var asset = readAttachment(path);
            for (var curve : rows(asset.data, "Curves")) {
                if ("Parameter".equals(curve.get("Target"))) requireId(parameters, curve.get("Id"), path);
                if ("PartOpacity".equals(curve.get("Target"))) requireId(parts, curve.get("Id"), path);
            }
            String file = addAttachment(files, "motions", path, asset.bytes);
            motions.computeIfAbsent(group, ignored -> new ArrayList<>()).add(map("File", file));
        }
        for (var value : (List<?>) request.getOrDefault("expressions", List.of())) {
            var row = (Map<?,?>) value;
            String name = nonblank(row.get("name"), "expression name");
            if (!names.add(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Duplicate expression name: " + name);
            var path = input(workspace, row.get("path"), ".exp3.json");
            var asset = readAttachment(path);
            for (var parameter : rows(asset.data, "Parameters")) requireId(parameters, parameter.get("Id"), path);
            expressions.add(map("Name", name, "File", addAttachment(files, "expressions", path, asset.bytes)));
        }
        String physics = null;
        if (request.get("physics_path") != null) {
            var path = input(workspace, request.get("physics_path"), ".physics3.json");
            var asset = readAttachment(path);
            for (var setting : rows(asset.data, "PhysicsSettings")) {
                for (var row : rows(setting, "Input")) {
                    if (!(row.get("Source") instanceof Map<?,?> ref)) throw new IllegalArgumentException("Physics input lacks a source");
                    requireId(parameters, ref.get("Id"), path);
                }
                for (var row : rows(setting, "Output")) {
                    if (!(row.get("Destination") instanceof Map<?,?> ref)) throw new IllegalArgumentException("Physics output lacks a destination");
                    requireId(parameters, ref.get("Id"), path);
                }
            }
            physics = addAttachment(files, "physics", path, asset.bytes);
        }
        return new RuntimeAssets(files, motions, expressions, physics);
    }

    private record Attachment(byte[] bytes, Map<?,?> data) {}

    private static Attachment readAttachment(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (!(org.json.simple.JSONValue.parse(new String(bytes, StandardCharsets.UTF_8)) instanceof Map<?,?> data))
            throw new IllegalArgumentException("Invalid JSON attachment: " + path.getFileName());
        return new Attachment(bytes, data);
    }

    private static List<Map<?,?>> rows(Map<?,?> data, String key) {
        if (!(data.get(key) instanceof List<?> rows)) throw new IllegalArgumentException("Attachment requires " + key);
        var result = new ArrayList<Map<?,?>>();
        for (var row : rows) {
            if (!(row instanceof Map<?,?> entry)) throw new IllegalArgumentException("Invalid attachment row in " + key);
            result.add(entry);
        }
        return result;
    }

    private static void requireId(Set<String> known, Object id, Path path) {
        if (!(id instanceof String key) || !known.contains(key))
            throw new IllegalArgumentException(path.getFileName() + " references an unknown model ID: " + id);
    }

    private static String addAttachment(Map<String,byte[]> files, String folder, Path path, byte[] bytes) {
        String file = folder + "/" + path.getFileName();
        for (var existing : files.keySet()) {
            if (existing.equalsIgnoreCase(file)) {
                if (existing.equals(file) && Arrays.equals(files.get(existing), bytes)) return file;
                throw new IllegalArgumentException("Attachment file name conflict: " + file);
            }
        }
        files.put(file, bytes);
        return file;
    }

    static void removeStaging(Path staging) throws IOException {
        if (Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) {
            try (var paths = Files.walk(staging)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    static void publishDirectory(Path staging, Path destination) throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            try {
                Files.move(staging, destination);
                return;
            } catch (AccessDeniedException error) {
                // Windows file scanners can briefly hold the new directory open.
                if (attempt == 4 || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) throw error;
                Thread.sleep(75);
            }
        }
    }

    private static void verifyExportReferences(Path directory, Map<?,?> references) throws IOException {
        for (var entry : references.entrySet()) {
            if (entry.getValue() instanceof String path) verifyReference(directory, path);
            if ("Textures".equals(entry.getKey()) && entry.getValue() instanceof List<?> textures)
                for (var texture : textures) verifyReference(directory, (String) texture);
            if ("Expressions".equals(entry.getKey()) && entry.getValue() instanceof List<?> expressions)
                for (var expression : expressions) verifyReference(directory, (String) ((Map<?,?>) expression).get("File"));
            if ("Motions".equals(entry.getKey()) && entry.getValue() instanceof Map<?,?> groups) {
                for (var group : groups.values())
                    for (var motion : (List<?>) group) {
                        var fields = (Map<?,?>) motion;
                        verifyReference(directory, (String) fields.get("File"));
                        if (fields.get("Sound") instanceof String sound) verifyReference(directory, sound);
                    }
            }
        }
    }

    private static void verifyReference(Path directory, String reference) throws IOException {
        if (reference == null || Path.of(reference).isAbsolute()) throw new IOException("Invalid native file reference: " + reference);
        Path path = directory.resolve(reference).normalize();
        if (!path.startsWith(directory) || !Files.isRegularFile(path))
            throw new IOException("Missing native file reference: " + reference);
    }

    static Object editAtlas(CModelingDocument doc, Map<String,Object> request) throws Exception {
        var source = doc.getModelSource();
        String action = (String) request.get("action");
        if ("inspect".equals(action)) return atlasInfo(source);
        var manager = source.getTextureManager();
        var undo = new GroupUndo("Native bridge: " + action + " texture atlas");
        try {
            switch (action) {
                case "create_page" -> {
                    var page = new CTextureAtlas(source, nonblank(request.get("name"), "name"),
                        atlasSize(request.get("width")), atlasSize(request.get("height")));
                    undo.plusAssign(manager.getHandler().a(page, -1));
                }
                case "resize_page" -> {
                    var page = atlasPage(source, request.get("page_guid"));
                    undo.plusAssign(new SimpleUndo("Resize texture atlas", page, new com.live2d.core.a()));
                    page.setWidth(atlasSize(request.get("width")));
                    page.setHeight(atlasSize(request.get("height")));
                }
                case "auto_layout" -> {
                    var page = atlasPage(source, request.get("page_guid"));
                    var assignedElsewhere = new HashSet<com.live2d.type.CModelImageGuid>();
                    for (var other : manager.getTextureAtlases()) {
                        if (other == page) continue;
                        for (var entry : other.getModelImages()) assignedElsewhere.add(entry.getModelImageGuid());
                    }
                    var materials = manager.getAllModelImages().stream()
                        .filter(material -> !assignedElsewhere.contains(material.getGuid())).toList();
                    undo.plusAssign(new SimpleUndo("Automatically place atlas materials", page, new com.live2d.core.a()));
                    page.getModelImages().clear();
                    autoLayout(source, page, materials);
                }
                case "place" -> {
                    var placements = (List<?>) request.get("placements");
                    if (placements.isEmpty()) throw new IllegalArgumentException("At least one placement is required");
                    var requested = new LinkedHashMap<CModelImage,Map<?,?>>();
                    for (var item : placements) {
                        var placement = (Map<?,?>) item;
                        var material = atlasMaterial(source, placement.get("material_guid"));
                        if (requested.putIfAbsent(material, placement) != null)
                            throw new IllegalArgumentException("Material appears twice in placements");
                        atlasPage(source, placement.get("page_guid"));
                    }
                    for (var page : manager.getTextureAtlases()) {
                        boolean touched = page.getModelImages().stream().anyMatch(entry -> requested.keySet().stream()
                            .anyMatch(material -> material.getGuid().equals(entry.getModelImageGuid())));
                        if (touched || requested.values().stream().anyMatch(value -> page.getGuid().getUuidString().equals(value.get("page_guid"))))
                            undo.plusAssign(new SimpleUndo("Place atlas materials", page, new com.live2d.core.a()));
                    }
                    for (var entry : requested.entrySet()) {
                        var material = entry.getKey();
                        var placement = entry.getValue();
                        var page = atlasPage(source, placement.get("page_guid"));
                        for (var other : manager.getTextureAtlases())
                            other.getModelImages().removeIf(existing -> material.getGuid().equals(existing.getModelImageGuid()));
                        page.getModelImages().add(new CTextureAtlas.ModelImageEntry(page, material.getGuid(), placementTransform(material, placement)));
                    }
                }
                default -> throw new IllegalArgumentException("Unknown atlas action: " + action);
            }
            for (var page : manager.getTextureAtlases()) validateAtlas(source, page);
            refreshTextures(source);
        } catch (Exception | Error error) {
            undo.undo();
            refreshTextures(source);
            refresh(doc);
            throw error;
        }
        undo.addUndoRedoListener(event -> { refreshTextures(source); return Unit.INSTANCE; });
        finishEdit(doc, undo);
        return atlasInfo(source);
    }

    private static void autoLayout(CModelSource source, CTextureAtlas page, List<CModelImage> materials) throws Exception {
        if (materials.isEmpty()) return;
        var editor = Editor_Texture.a;
        // This is the native initial atlas placement operation, underneath the editor dialog.
        var layout = editor.getClass().getDeclaredMethod("a", CTextureAtlas.class, CModelSource.class, int.class, int.class, List.class);
        layout.setAccessible(true);
        layout.invoke(editor, page, source, page.getWidth(), page.getHeight(), materials);
        if (page.getModelImages().size() != materials.size())
            throw new IllegalStateException("Cubism did not lay out all atlas materials");
    }

    private static CTextureAtlas atlasPage(CModelSource source, Object value) {
        String guid = nonblank(value, "page_guid");
        return source.getTextureManager().getTextureAtlases().stream().filter(page -> guid.equals(page.getGuid().getUuidString()))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Texture atlas page not found: " + guid));
    }

    private static CModelImage atlasMaterial(CModelSource source, Object value) {
        String guid = nonblank(value, "material_guid");
        return source.getTextureManager().getAllModelImages().stream().filter(material -> guid.equals(material.getGuid().getUuidString()))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Material not found: " + guid));
    }

    private static int atlasSize(Object size) {
        if (!(size instanceof Number value) || value.doubleValue() != value.intValue() || value.intValue() <= 0)
            throw new IllegalArgumentException("Atlas dimensions must be positive integers");
        return value.intValue();
    }

    private static CAffine placementTransform(CModelImage material, Map<?,?> placement) {
        int rotation = ((Number) placement.get("rotation")).intValue();
        if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)
            throw new IllegalArgumentException("Atlas rotation must be 0, 90, 180 or 270");
        var transform = java.awt.geom.AffineTransform.getQuadrantRotateInstance(rotation / 90);
        var bounds = transform.createTransformedShape(new Rectangle2D.Double(0, 0, material.getWidth(), material.getHeight())).getBounds2D();
        transform.preConcatenate(java.awt.geom.AffineTransform.getTranslateInstance(
            ((Number) placement.get("x")).doubleValue() - bounds.getX(), ((Number) placement.get("y")).doubleValue() - bounds.getY()));
        return new CAffine(transform);
    }

    private static Rectangle2D atlasBounds(CModelSource source, CTextureAtlas.ModelImageEntry entry) {
        var material = atlasMaterial(source, entry.getModelImageGuid().getUuidString());
        var rectangle = new Rectangle2D.Double(0, 0, material.getWidth(), material.getHeight());
        return entry.calcModelImageLocalToAtlasTransform().createTransformedShape(rectangle).getBounds2D();
    }

    private static void validateAtlas(CModelSource source, CTextureAtlas page) {
        var bounds = new ArrayList<Rectangle2D>();
        for (var entry : page.getModelImages()) {
            var rectangle = atlasBounds(source, entry);
            if (rectangle.getMinX() < -0.01 || rectangle.getMinY() < -0.01
                    || rectangle.getMaxX() > page.getWidth() + 0.01 || rectangle.getMaxY() > page.getHeight() + 0.01)
                throw new IllegalArgumentException("Material is outside the texture atlas: " + entry.getModelImageGuid().getUuidString());
            if (bounds.stream().anyMatch(other -> other.intersects(rectangle)))
                throw new IllegalArgumentException("Material overlaps another atlas placement: " + entry.getModelImageGuid().getUuidString());
            bounds.add(rectangle);
        }
    }

    private static Object atlasInfo(CModelSource source) {
        var manager = source.getTextureManager();
        var pages = new ArrayList<Object>();
        var assigned = new HashSet<com.live2d.type.CModelImageGuid>();
        for (var page : manager.getTextureAtlases()) {
            var entries = new ArrayList<Object>();
            for (var entry : page.getModelImages()) {
                var bounds = atlasBounds(source, entry);
                assigned.add(entry.getModelImageGuid());
                entries.add(map("material_guid", entry.getModelImageGuid().getUuidString(), "x", bounds.getX(), "y", bounds.getY(),
                    "width", bounds.getWidth(), "height", bounds.getHeight(),
                    "matrix", entry.calcModelImageLocalToAtlasTransform().copy().getMatrix()));
            }
            pages.add(map("guid", page.getGuid().getUuidString(), "name", page.getName(), "width", page.getWidth(),
                "height", page.getHeight(), "placements", entries));
        }
        return map("pages", pages, "unplaced_materials", manager.getAllModelImages().stream()
            .filter(material -> !assigned.contains(material.getGuid()))
            .map(material -> map("guid", material.getGuid().getUuidString(), "name", material.getName(),
                "width", material.getWidth(), "height", material.getHeight())).toList());
    }

    private static void refreshTextures(CModelSource source) {
        var manager = source.getTextureManager();
        rebuildAtlasInputs(source);
        for (var page : manager.getTextureAtlases()) {
            page.setDirty_cachedAtlasImage(true);
            page.updateTexture(true, true, null);
        }
        manager.reinitModelTextureInput();
    }

    private static void rebuildAtlasInputs(CModelSource source) {
        var manager = source.getTextureManager();
        var regions = new HashMap<com.live2d.type.CModelImageGuid,CTextureAtlas.ModelImageEntry>();
        var pages = new HashMap<com.live2d.type.CModelImageGuid,CTextureAtlas>();
        for (var page : manager.getTextureAtlases()) {
            for (var entry : page.getModelImages()) {
                regions.putIfAbsent(entry.getModelImageGuid(), entry);
                pages.putIfAbsent(entry.getModelImageGuid(), page);
            }
        }
        for (var mesh : source.getAllArtMeshes()) {
            var extension = mesh.getTextureInputExtension();
            var material = extension.getModelImageInput();
            if (material == null) continue;
            for (var input : List.copyOf(extension.getTextureInputs())) {
                if (input instanceof CTextureInput_TextureAtlasRegion) extension.removeTextureInput(input);
            }
            var region = regions.get(material.getModelImageGuid());
            if (region == null) continue;
            var toAtlas = region.calcModelImageLocalToAtlasTransform();
            if (toAtlas.getDeterminant() == 0) throw new IllegalArgumentException("Atlas transform is singular for " + mesh.getId());
            var atlasInput = new CTextureInput_TextureAtlasRegion(extension, pages.get(material.getModelImageGuid()));
            var toCanvas = new CAffine();
            toCanvas.concatenate(material.getModelImageLocalToCanvasTransform());
            toCanvas.concatenate(toAtlas.createInverse());
            atlasInput.setAtlasLocalToCanvasTransform(toCanvas);
            atlasInput.setOptionalTransformOnCanvas(new CAffine(material.getOptionalTransformOnCanvas()));
            extension.addTextureInput(atlasInput);
        }
    }
}
