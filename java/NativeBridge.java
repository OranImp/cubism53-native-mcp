package cubism53.mcp;

import com.live2d.cubism.CEAppCtrl;
import com.live2d.cubism.doc.IDocument;
import com.live2d.cubism.doc.animation.CSceneDocument;
import com.live2d.cubism.doc.model.CModelSource;
import com.live2d.cubism.doc.model.CModel;
import com.live2d.cubism.doc.model.ACParameterControllableSource;
import com.live2d.cubism.doc.model.ACForm;
import com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource;
import com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm;
import com.live2d.cubism.doc.model.drawable.ACDrawableForm;
import com.live2d.cubism.doc.model.drawable.ColorComposition;
import com.live2d.cubism.doc.model.parts.CPartSource;
import com.live2d.cubism.doc.model.affecter.glue.CGlueSource;
import com.live2d.cubism.doc.model.affecter.glue.CGlueForm;
import com.live2d.cubism.doc.model.extension.meshGenerator.CMeshGeneratorExtension;
import com.live2d.cubism.doc.model.extension.meshGenerator.MeshGenerateSetting;
import com.live2d.cubism.doc.model.extension.textureInput.CTextureInput_ModelImage;
import com.live2d.cubism.view.palette.tool.toolMode.meshEditor.AutoMeshUtil;
import com.live2d.graphics3d.type.GVector2;
import com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformerSource;
import com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformerForm;
import com.live2d.cubism.doc.model.deformer.warp.CWarpDeformerForm;
import com.live2d.cubism.doc.model.param.CParameterSource;
import com.live2d.cubism.doc.model.param.group.CParameterGroup;
import com.live2d.cubism.doc.model.id.CParameterGroupId;
import com.live2d.cubism.doc.model.interpolator.KeyformOnGrid;
import com.live2d.cubism.doc.model.deformer.ACDeformerSource;
import com.live2d.cubism.doc.model.deformer.warp.CWarpDeformerSource;
import com.live2d.cubism.doc.modeling.CModelingDocument;
import com.live2d.graphics3d.type.GRectF;
import com.live2d.type.CDeformerGuid;
import com.live2d.type.CParameterGroupGuid;
import com.live2d.undo.ACUndoable;
import com.live2d.undo.GroupUndo;
import com.live2d.undo.LambdaUndo;
import com.live2d.undo.SimpleUndo;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;
import kotlin.Pair;
import org.json.simple.JSONValue;

/** Version-specific internal adapter. No input events, bytecode patch, or license edits. */
public final class NativeBridge {
    private static volatile ServerSocket listener;
    private static Path workspace;
    private static String token;
    // The stock one-image export recreates a GL context for every image. Reuse
    // two native offscreen sessions for the full-size QA and animation preview.
    private static final LinkedHashMap<String,CachedRender> renderSessions = new LinkedHashMap<>();
    private static AutoMeshJob activeAutoMesh;
    private static boolean batchPreflight;
    private static GroupUndo batchUndo;

    @SuppressWarnings("unchecked")
    public static synchronized void agentmain(String configFile, Instrumentation ignored) throws Exception {
        if (listener != null && !listener.isClosed()) throw new IllegalStateException("Adapter already running");
        Map<String,Object> config = (Map<String,Object>) JSONValue.parse(Files.readString(Path.of(configFile)));
        workspace = Path.of((String) config.get("workspace")).toRealPath();
        token = (String) config.get("token");
        Path expectedJar = Path.of((String) config.get("editor_jar")).toRealPath();
        Path loadedJar = Path.of(CEAppCtrl.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if (!loadedJar.equals(expectedJar)) throw new IllegalStateException("Unexpected editor build: " + loadedJar);
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var stream = new java.security.DigestInputStream(Files.newInputStream(loadedJar), digest)) {
            stream.transferTo(OutputStream.nullOutputStream());
        }
        if (!NativeBridgeBuild.SHA256.equals(HexFormat.of().formatHex(digest.digest())))
            throw new IllegalStateException("Adapter requires the verified Cubism " + NativeBridgeBuild.VERSION + " build");
        if (token == null || token.length() < 24) throw new IllegalArgumentException("Missing local token");
        ServerSocket bound = new ServerSocket();
        try {
            bound.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), ((Number) config.get("port")).intValue()));
        } catch (IOException error) {
            bound.close();
            throw error;
        }
        listener = bound;
        Thread worker = new Thread(NativeBridge::serve, "Cubism53-native-bridge");
        // The JVM Attach listener can have a null context loader. Cubism's JSON
        // library needs the editor loader when a worker is the first to use it.
        worker.setContextClassLoader(CEAppCtrl.class.getClassLoader());
        worker.setDaemon(true);
        worker.start();
    }

    private static ExecutorService workers(String name, int queueSize) {
        return new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queueSize), task -> {
            var worker = new Thread(task, name);
            worker.setDaemon(true);
            return worker;
        });
    }

    private static void serve() {
        var connections = workers("Cubism53-connection", 32);
        var editorRequests = workers("Cubism53-editor-request", 16);
        try {
            while (!listener.isClosed()) {
                Socket connection = listener.accept();
                long readDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                try {
                    connections.execute(() -> handle(connection, editorRequests, readDeadline));
                } catch (RejectedExecutionException error) {
                    reply(connection, errorResponse(new IllegalStateException("Bridge is busy; retry the request")));
                }
            }
        } catch (IOException error) {
            if (!listener.isClosed()) System.err.println("Cubism53 bridge: " + error);
        } finally {
            connections.shutdown();
            editorRequests.shutdown();
        }
    }

    private static void handle(Socket socket, ExecutorService editorRequests, long readDeadline) {
        boolean handedOff = false;
        try {
            Map<String,Object> request = NativeBridgeWire.request(socket, token, readDeadline);
            String operation = nonblank(request.get("operation"), "operation");
            if ("bridge_info".equals(operation)) {
                reply(socket, map("ok", true, "result", bridgeIdentity()));
            } else if ("job_status".equals(operation)) {
                reply(socket, map("ok", true, "result", NativeBridgeJobs.status(request)));
            } else if ("list_jobs".equals(operation)) {
                reply(socket, map("ok", true, "result", NativeBridgeJobs.list(request)));
            } else if (Set.of("render_pose_set", "export_runtime_package").contains(operation)
                    || "job".equals(request.get("execution"))
                        && Set.of("reimport_psd", "export_runtime_model", "simulate_physics").contains(operation)) {
                reply(socket, map("ok", true, "result", NativeBridgeJobs.submit(operation, request, true,
                    () -> executeBackground(operation, request, 0))));
            } else {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
                try {
                    editorRequests.execute(() -> handleEditorRequest(socket, operation, request, deadline));
                    handedOff = true;
                } catch (RejectedExecutionException error) {
                    throw new IllegalStateException("Editor request queue is busy; retry the request", error);
                }
            }
        } catch (Exception error) {
            reply(socket, errorResponse(error));
        } finally {
            if (!handedOff && !socket.isClosed()) {
                try { socket.close(); }
                catch (IOException error) { System.err.println("Cubism53 bridge: " + error); }
            }
        }
    }

    private static final Set<String> PNG_INPUTS = Set.of("import_png", "replace_mesh_texture", "replace_mesh_texture_resized");
    private static final Set<String> BACKGROUND = Set.of("export_runtime_model", "preflight_runtime_export", "simulate_physics",
        "plan_psd_reimport", "reimport_psd", "import_psd", "render_png", "suggest_glue_pairs",
        "import_png", "replace_mesh_texture", "replace_mesh_texture_resized", "export_mesh_texture",
        "import_physics3", "export_physics3", "export_expression3", "export_motion3");

    private static void handleEditorRequest(Socket socket, String operation, Map<String,Object> request,
                                            long deadline) {
        Map<String,Object> response;
        try {
            Object result;
            if ("stop".equals(operation)) {
                result = stop(deadline);
            } else if (BACKGROUND.contains(operation)) {
                NativeBridgeJobs.beginWork();
                try { result = executeBackground(operation, request, deadline); }
                finally { NativeBridgeJobs.endWork(); }
            } else {
                result = onEditorThread(() -> {
                    NativeBridgeJobs.checkOpen();
                    return execute(operation, request);
                }, deadline);
            }
            response = map("ok", true, "result", result);
        } catch (Exception | LinkageError error) {
            response = errorResponse(error);
        }
        reply(socket, response);
    }

    static <T> T onEditorThread(Callable<T> work, long deadline) throws Exception {
        if (deadline != 0 && deadline - System.nanoTime() <= 0)
            throw new IllegalStateException("Editor operation timed out before it started");
        var started = new java.util.concurrent.atomic.AtomicBoolean();
        FutureTask<T> task = new FutureTask<>(() -> {
            if (!started.compareAndSet(false, true)) return null;
            if (deadline != 0 && deadline - System.nanoTime() <= 0)
                throw new IllegalStateException("Editor operation timed out before it started");
            return work.call();
        });
        SwingUtilities.invokeLater(task);
        try {
            return deadline == 0 ? task.get() : task.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            boolean wasQueued = started.compareAndSet(false, true);
            task.cancel(false);
            throw new IllegalStateException(wasQueued ? "Editor operation timed out before it started"
                : "Editor operation timed out after starting; inspect state before retrying", error);
        }
    }

    private static Object executeBackground(String operation, Map<String,Object> request, long deadline) throws Exception {
        Callable<CModelingDocument> document = () -> {
            var doc = findDocument(CEAppCtrl.Companion.b(), (String) request.get("document_uid"));
            if (activeAutoMesh != null && activeAutoMesh.documentUid.equals(doc.getDocumentUID()))
                throw new IllegalStateException("Automatic mesh generation is running: " + activeAutoMesh.id);
            return doc;
        };
        if ("render_pose_set".equals(operation)) return NativeBridgeRendering.renderSet(document, workspace, request);
        if ("render_png".equals(operation)) return renderPng(document, request, deadline);
        if ("suggest_glue_pairs".equals(operation)) {
            var search = onEditorThread(() -> prepareGlueSearch(document.call().getModelSource(), request), deadline);
            return suggestGluePairs(search);
        }
        if (PNG_INPUTS.contains(operation)) {
            var expected = onEditorThread(() -> {
                var doc = document.call();
                checkRevision(doc, request);
                return revision(doc);
            }, deadline);
            var input = NativeBridgeAssets.readPng(workspace, request.get("path"));
            return onEditorThread(() -> {
                if (!expected.equals(revision(document.call())))
                    throw new IllegalArgumentException("Document revision changed; inspect the document before retrying");
                return execute(operation, request, input);
            }, deadline);
        }
        if ("export_mesh_texture".equals(operation)) {
            var path = NativeBridgeAssets.output(workspace, request.get("path"), ".png");
            var frame = onEditorThread(() -> exportMeshTexture(document.call().getModelSource(), request, path), deadline);
            try {
                NativeBridgeAssets.publishFile(workspace, path, ".png", temporary -> frame.image.writeImage(temporary.toFile()));
                return frame.information;
            } finally { frame.image.flush(); }
        }
        if ("import_psd".equals(operation)) {
            var input = NativeBridgeAssets.snapshotPsd(workspace, request.get("path"));
            return onEditorThread(() -> {
                if (activeAutoMesh != null) throw new IllegalStateException("Automatic mesh generation is running: " + activeAutoMesh.id);
                return NativeBridgeAssets.importPsd(CEAppCtrl.Companion.b(), input);
            }, deadline);
        }
        if ("plan_psd_reimport".equals(operation)) {
            var preview = NativeBridgeAssets.previewPsd(workspace, request.get("path"));
            return onEditorThread(() -> NativeBridgeAssets.planPsdReimport(document.call(), preview, request), deadline);
        }
        if ("reimport_psd".equals(operation)) {
            var snapshot = NativeBridgeAssets.snapshotPsd(workspace, request.get("path"));
            return onEditorThread(() -> NativeBridgeAssets.reimportPsd(document.call(), snapshot, request), deadline);
        }
        if ("simulate_physics".equals(operation)) {
            var simulation = onEditorThread(() -> NativeBridgePhysics.prepare(document.call(), request), deadline);
            var tracePath = request.get("trace_path");
            if (tracePath == null && (!"job".equals(request.get("execution")) || !NativeBridgePhysics.spill(simulation)))
                return NativeBridgePhysics.solve(simulation, deadline, null);
            if (simulation.summary()) throw new IllegalArgumentException("trace_path requires result_mode=trace");
            var path = tracePath == null ? null : NativeBridgeAssets.output(workspace, tracePath, ".json");
            try (var spool = new NativeBridgeTrace.Spool()) {
                var result = NativeBridgePhysics.solve(simulation, deadline, spool);
                var trace = spool.finish();
                if (path == null) return new NativeBridgeJobs.TraceResult(result, trace);
                try (trace) { trace.writeJson(workspace, path, result, deadline); }
                result.put("frame_count", simulation.frames().size());
                result.put("trace_path", path.toString());
                return result;
            }
        }
        if ("import_physics3".equals(operation)) {
            var path = NativeBridgeAssets.input(workspace, request.get("path"), ".physics3.json");
            var json = NativeBridgeAssets.readJsonText(path);
            Object input = Boolean.TRUE.equals(request.get("preflight"))
                ? new NativeBridgeAssets.PhysicsPreflightInput(path, JSONValue.parse(json))
                : net.arnx.jsonic.JSON.decode(json, com.live2d.cubism.doc.gameData.importer.CPhysicsJsonLoader.PhysicsJson.class);
            return onEditorThread(() -> execute(operation, request, input), deadline);
        }
        if ("export_physics3".equals(operation)) {
            var path = NativeBridgeAssets.output(workspace, request.get("path"), ".physics3.json");
            var export = onEditorThread(() -> NativeBridgeAssets.preparePhysics(document.call()), deadline);
            return NativeBridgeAssets.writePhysics(export, workspace, path);
        }
        if ("export_expression3".equals(operation)) {
            var path = NativeBridgeAssets.output(workspace, request.get("path"), ".exp3.json");
            var ids = onEditorThread(() -> NativeBridgeAssets.parameterIds(document.call().getModelSource()), deadline);
            return NativeBridgeAssets.exportExpression(ids, workspace, path, request);
        }
        if ("export_motion3".equals(operation)) {
            var path = NativeBridgeAssets.output(workspace, request.get("path"), ".motion3.json");
            var json = onEditorThread(() -> {
                var scene = NativeBridgeAnimation.find(CEAppCtrl.Companion.b(), (String) request.get("document_uid"));
                if (scene == null) throw new IllegalArgumentException("Animation scene not found");
                checkRevision(scene, request);
                return NativeBridgeAnimation.prepareMotion(scene, request);
            }, deadline);
            return NativeBridgeAnimation.writeMotion(workspace, path, json);
        }
        boolean preflight = "preflight_runtime_export".equals(operation);
        var destination = preflight ? null : NativeBridgeAssets.output(workspace, request.get("directory"), "");
        var check = onEditorThread(() -> NativeBridgeAssets.runtimeCheck(document.call(), request, preflight), deadline);
        var diagnostics = new ArrayList<Map<String,Object>>();
        var assets = NativeBridgeAssets.runtimeAssets(check, workspace, request, diagnostics);
        if (preflight) return NativeBridgeAssets.preflightRuntime(check, assets, diagnostics);
        if (!diagnostics.isEmpty()) throw new IllegalArgumentException("Runtime attachment preflight failed: " + diagnostics);
        var export = onEditorThread(() -> NativeBridgeAssets.prepareRuntime(document.call(), check, assets), deadline);
        return NativeBridgeAssets.writeRuntime(export, workspace, destination, deadline);
    }

    private static Object stop(long deadline) throws Exception {
        NativeBridgeJobs.beginStop();
        try {
            onEditorThread(() -> {
                if (activeAutoMesh != null) throw new IllegalStateException("Automatic mesh generation is running");
                closeRenderSessions(null);
                return null;
            }, deadline);
            listener.close();
            NativeBridgeJobs.stopped();
        } catch (Exception error) {
            NativeBridgeJobs.abortStop();
            throw error;
        }
        return map("stopped", true);
    }

    private static Map<String,Object> errorResponse(Throwable error) {
        Throwable cause = error instanceof ExecutionException ? error.getCause() : error;
        return map("ok", false, "error", cause.toString());
    }

    private static Map<String,Object> bridgeIdentity() {
        return map("editor_version", NativeBridgeBuild.VERSION, "editor_sha256", NativeBridgeBuild.SHA256,
            "editor_pid", ProcessHandle.current().pid(), "bridge_protocol", NativeBridgeBuild.PROTOCOL,
            "adapter_build_id", NativeBridgeBuild.ID);
    }

    private static void reply(Socket socket, Map<String,Object> response) {
        try (socket) {
            Writer writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            writeJson(response, writer);
            writer.write("\n");
            writer.flush();
        } catch (IOException error) {
            if (!listener.isClosed()) System.err.println("Cubism53 bridge: " + error);
        }
    }

    private static Object execute(String op, Map<String,Object> request) throws Exception {
        return execute(op, request, null);
    }

    private static Object execute(String op, Map<String,Object> request, Object staged) throws Exception {
        CEAppCtrl app = CEAppCtrl.Companion.b();
        if (activeAutoMesh != null && Set.of("open_project", "create_blank_project", "close_saved",
                "import_psd", "create_animation_scene").contains(op))
            throw new IllegalStateException("Automatic mesh generation is running: " + activeAutoMesh.id);
        if ("status".equals(op)) {
            pruneRenderSessions(app);
            List<Object> documents = new ArrayList<>();
            boolean lightweight = Boolean.TRUE.equals(request.get("lightweight"));
            for (IDocument doc : app.getAllDocs()) {
                if (doc instanceof CModelingDocument modelDoc) documents.add(lightweight
                    ? map("document_uid", modelDoc.getDocumentUID(), "document_type", "model")
                    : describe(modelDoc));
            }
            for (var scene : NativeBridgeAnimation.scenes(app)) documents.add(lightweight
                ? map("document_uid", scene.getDocumentUID(), "document_type", "animation_scene")
                : NativeBridgeAnimation.describe(scene));
            var current = app.getCurrentDoc();
            var status = bridgeIdentity();
            status.putAll(map("adapter", "internal-" + NativeBridgeBuild.VERSION + "-experimental",
                "editor_thread", SwingUtilities.isEventDispatchThread(), "documents", documents,
                "current_document_uid", current instanceof CModelingDocument d ? d.getDocumentUID()
                    : current instanceof CSceneDocument d ? d.getDocumentUID() : null));
            return status;
        }
        if ("open_project".equals(op) || "find_open_project".equals(op)) {
            String requestedPath = nonblank(request.get("path"), "path");
            String suffix = requestedPath.toLowerCase(Locale.ROOT).endsWith(".can3") ? ".can3" : ".cmo3";
            Path file = NativeBridgeAssets.input(workspace, requestedPath, suffix);
            for (CModelingDocument doc : app.getAllModelDocs()) {
                if (sameOpenFile(doc.getFile(), file)) return describe(doc);
            }
            for (var doc : NativeBridgeAnimation.scenes(app)) {
                var path = doc.getAnimationContent().getFile();
                if (sameOpenFile(path, file)) return NativeBridgeAnimation.describe(doc);
            }
            if ("find_open_project".equals(op)) return null;
            if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".can3"))
                return NativeBridgeAnimation.open(app, file);
            app.command_open(file.toFile(), false);
            return map("open_requested", file.toString());
        }
        if ("create_blank_project".equals(op)) {
            int width = canvasDimension(request, "width", CModelSource.MIN_CANVAS_WIDTH);
            int height = canvasDimension(request, "height", CModelSource.MIN_CANVAS_HEIGHT);
            Set<String> existingUids = new HashSet<>();
            for (CModelingDocument openDoc : app.getAllModelDocs()) {
                existingUids.add(openDoc.getDocumentUID());
            }
            // Cubism 5.3.03 command_newModel delegates to this factory with default dimensions.
            com.live2d.cubism.appCtrlImpl.ar.a.a(app, width, height);
            List<CModelingDocument> created = app.getAllModelDocs().stream()
                .filter(openDoc -> !existingUids.contains(openDoc.getDocumentUID())).toList();
            if (created.size() != 1) {
                throw new IllegalStateException("Expected one new modeling document, found " + created.size());
            }
            CModelingDocument blank = created.get(0);
            CModelSource blankSource = blank.getModelSource();
            var canvas = blankSource.getCanvas();
            if (canvas.getPixelWidth() != width || canvas.getPixelHeight() != height
                    || !blankSource.getDrawableSourceSet().getSources().isEmpty()
                    || !blankSource.getDeformerSourceSet().getSources().isEmpty()
                    || !blankSource.getTextureManager().getAllModelImages().isEmpty()
                    || !blankSource.getPhysicsSettingsSourceSet().getSources().isEmpty()) {
                throw new IllegalStateException("The new modeling document is not blank at the requested dimensions");
            }
            return describe(blank);
        }
        if ("create_animation_scene".equals(op))
            return NativeBridgeAnimation.create(app, workspace, findDocument(app, (String) request.get("model_document_uid")), request);
        var scene = NativeBridgeAnimation.find(app, (String) request.get("document_uid"));
        if (scene != null) return NativeBridgeAnimation.execute(app, scene, workspace, op, request);
        CModelingDocument doc = findDocument(app, (String) request.get("document_uid"));
        CModelSource source = doc.getModelSource();
        checkRevision(doc, request);
        if (activeAutoMesh != null && activeAutoMesh.documentUid.equals(doc.getDocumentUID())
                && !Set.of("auto_generate_mesh", "auto_mesh_status", "describe", "objects", "object",
                    "physics", "list_glues").contains(op))
            throw new IllegalStateException("Automatic mesh generation is running: " + activeAutoMesh.id);
        switch (op) {
            case "describe": return describe(doc);
            case "create_part": {
                var parent = request.get("parent_part_guid") instanceof String guid ? requirePart(source, guid) : source.getRootPart();
                var id = new com.live2d.cubism.doc.model.id.CPartId("Part" + UUID.randomUUID().toString().replace("-", ""));
                var part = CPartSource.Companion.a(nonblank(request.get("name"), "name"), source, id, 500);
                finishEdit(doc, parent.getHandler().addPartChild(part, childIndex(request, parent.getChildren().size())));
                return objectInfo(part, false);
            }
            case "create_parameter_group": {
                var parent = parameterGroup(source, (String) request.get("parent_group_guid"));
                var guid = new CParameterGroupGuid();
                var group = new CParameterGroup(nonblank(request.get("name"), "name"), guid,
                    new CParameterGroupId("ParamGroup" + guid.getUuidString().replace("-", "")));
                finishEdit(doc, parent.getHandler().a(group, childIndex(request, parent.getChildren().size())));
                return parameterGroupInfo(group);
            }
            case "move_parameter_to_group": {
                var parameter = findParameter(source, (String) request.get("parameter_id"));
                var group = parameterGroup(source, (String) request.get("group_guid"));
                int size = group.getChildren().size() - (group == parameter.getParentGroup() ? 1 : 0);
                finishEdit(doc, source.getHandler().b(group, parameter, childIndex(request, size)));
                return map("id", parameter.getId().toString(), "group_guid", group.getGuid().getUuidString());
            }
            case "duplicate_objects": return duplicateObjects(doc, request);
            case "add_glue_pairs": return addGluePairs(doc, request);
            case "inspect_model_dependencies": return NativeBridgeModel.dependencies(source, request);
            case "edit_part_rendering": return NativeBridgeAuthoring.editPart(doc, request);
            case "mirror_keyforms": return NativeBridgeAuthoring.mirror(doc, request);
            case "inspect_art_paths": return NativeBridgeAuthoring.paths(source, request);
            case "create_art_path": return NativeBridgeAuthoring.createPath(doc, request);
            case "plan_delete_objects", "delete_objects": return NativeBridgeAuthoring.delete(doc, op, request);
            case "set_form_colors": return NativeBridgeModel.setColors(doc, request);
            case "create_blend_shape": return NativeBridgeModel.createBlendShape(doc, request);
            case "edit_blend_shape": return NativeBridgeModel.editBlendShape(doc, request);
            case "edit_art_path": return NativeBridgeAuthoring.editPath(doc, request);
            case "preflight_model_edit": return NativeBridgeModel.preflight(source, request);
            case "replace_mesh_texture_resized": return NativeBridgeAssets.replaceResized(doc, (NativeBridgeAssets.PngInput) staged, request);
            case "edit_batch": return editBatch(doc, request);
            case "fit_warp_to_children": return fitWarp(doc, request);
            case "edit_texture_atlas": return NativeBridgeAssets.editAtlas(doc, request);
            case "auto_generate_mesh": return startAutoMesh(doc, request);
            case "auto_mesh_status": return NativeBridgeJobs.meshStatus(doc.getDocumentUID(), (String) request.get("job_id"));
            case "list_glues": return map("glues", glues(source).stream()
                .map(glue -> glueInfo(glue, !Boolean.FALSE.equals(request.get("include_pairs")))).toList());
            case "create_glue": return createGlue(doc, request);
            case "update_glue_weights", "remove_glue_pairs", "set_glue_intensity": return editGlue(doc, request, op);
            case "update_parameter": return updateParameter(doc, request);
            case "move_parameter_keys", "remove_parameter_keys": return editParameterKeys(doc, request, op);
            case "update_physics_group": return updatePhysicsGroup(doc, request);
            case "edit_physics_groups": return NativeBridgePhysics.editGroups(doc, request);
            case "move_to_part": {
                var item = findObject(source, (String) request.get("object_guid"));
                String partGuid = (String) request.get("part_guid");
                var root = source.getRootPart();
                var parent = partGuid == null || root.getGuid().getUuidString().equals(partGuid)
                    ? root : requirePart(source, partGuid);
                if (item == source.getRootPart() || item == parent)
                    throw new IllegalArgumentException("Cannot move the root part or parent an object to itself");
                for (var ancestor : parent.getAllParents()) {
                    if (ancestor == item) throw new IllegalArgumentException("Part hierarchy would contain a cycle");
                }
                int index = partMoveIndex(request, parent.getChildren().size());
                if (batchPreflight) return null;
                finishEdit(doc, parent.getHandler().addPartChild(item, index));
                return batchUndo == null ? objectInfo(item, false) : null;
            }
            case "rename_object": {
                var item = findObject(source, (String) request.get("object_guid"));
                String name = nonblank(request.get("name"), "name");
                if (batchPreflight) return null;
                var undo = item.getHandler().createUndo_forBasicSetting("Native bridge: rename object");
                item.setLocalName(name);
                finishEdit(doc, undo);
                return batchUndo == null ? objectInfo(item, false) : null;
            }
            case "set_blend_mode": {
                var item = findObject(source, (String) request.get("object_guid"));
                var mode = ColorComposition.valueOf(nonblank(request.get("blend_mode"), "blend_mode").toUpperCase(Locale.ROOT));
                if (item instanceof CArtMeshSource mesh) {
                    var undo = mesh.getHandler().createUndo_forBasicSetting("Native bridge: set blend mode");
                    mesh.setColorComposition(mode);
                    finishEdit(doc, undo);
                } else if (item instanceof CPartSource part && part.getUseOffscreen()) {
                    var undo = part.getHandler().createUndo_forBasicSetting("Native bridge: set part blend mode");
                    part.setColorComposition(mode);
                    finishEdit(doc, undo);
                } else {
                    throw new IllegalArgumentException("Blend mode requires an ArtMesh or a part with offscreen drawing enabled");
                }
                return objectInfo(item, false);
            }
            case "set_part_offscreen": {
                var part = requirePart(source, (String) request.get("part_guid"));
                if (part == source.getRootPart()) throw new IllegalArgumentException("Root part is not a drawing group");
                boolean enabled = (Boolean) request.get("enabled");
                NativeBridgeAuthoring.editPart(doc, map("part_guid", request.get("part_guid"),
                    "offscreen", enabled, "draw_order_group", true));
                return objectInfo(part, false);
            }
            case "create_parameter": {
                String id=(String)request.get("id"),name=(String)request.get("name");
                if (id==null || !id.matches("[A-Za-z][A-Za-z0-9_]{0,63}") || name==null || name.isBlank())
                    throw new IllegalArgumentException("Parameter ID and name required");
                for (var p:source.getAllParameters()) if (id.equals(p.getId().toString()))
                    throw new IllegalArgumentException("Parameter already exists: "+id);
                float min=number(request,"minimum"),max=number(request,"maximum"),def=number(request,"default_value");
                if (min>=max || def<min || def>max) throw new IllegalArgumentException("Invalid parameter range/default");
                var p=new CParameterSource(new com.live2d.cubism.doc.model.id.CParameterId(id),name,min,max,def,"",
                    new com.live2d.type.CParameterGuid(),CParameterSource.Type.NORMAL);
                finishEdit(doc,source.getRootParameterGroup().getHandler().a(p,-1));
                return map("id",id,"guid",p.getGuid().getUuidString(),"name",name,"minimum",min,"maximum",max,"default",def);
            }
            case "physics": return physicsInfo(source);
            case "release_renderers": {
                int before=renderSessions.size();closeRenderSessions(doc.getDocumentUID());
                return map("released",before-renderSessions.size(),"remaining",renderSessions.size());
            }
            case "import_physics3": {
                var settings=source.getPhysicsSettingsSourceSet();
                if (Boolean.TRUE.equals(request.get("preflight")))
                    return NativeBridgeAssets.preflightPhysics(source, (NativeBridgeAssets.PhysicsPreflightInput) staged,
                        Boolean.TRUE.equals(request.get("replace")));
                if (!settings.getSources().isEmpty() && !Boolean.TRUE.equals(request.get("replace")))
                    throw new IllegalArgumentException("Existing physics groups: explicit replace required");
                var input = (com.live2d.cubism.doc.gameData.importer.CPhysicsJsonLoader.PhysicsJson) staged;
                if (input.getVersion()!=3 || input.getMeta()==null || input.getPhysicsSettings()==null)
                    throw new IllegalArgumentException("Physics version 3, Meta and PhysicsSettings required");
                // Same importer as the file command, without its modal error dialog.
                var loader=new com.live2d.cubism.doc.gameData.importer.CPhysicsJsonLoader();
                var undo=new com.live2d.undo.SimpleUndo("Native bridge: import physics",settings,new com.live2d.core.a());
                Map<String,Object> result;
                try {
                    var method=loader.getClass().getDeclaredMethod("a",CModelSource.class,input.getClass());
                    method.setAccessible(true);
                    if (!Boolean.TRUE.equals(method.invoke(loader,source,input)))
                        throw new IllegalArgumentException("Native physics import rejected: "+loader.a());
                    if (!loader.a().isEmpty() || !loader.b().isEmpty() || settings.containInvalidVertexIndex())
                        throw new IllegalArgumentException("Invalid physics references: "+loader.a()+" "+loader.b());
                    result = physicsInfo(source);
                } catch (Throwable error) { undo.undo(); refresh(doc); throw error; }
                finishEdit(doc, undo);
                return result;
            }
            case "import_png": {
                String name = (String)request.get("name");
                if (name == null || name.isBlank()) throw new IllegalArgumentException("Image name required");
                for (var existing : source.getDrawableSourceSet().getSources())
                    if (name.equals(existing.getLocalName())) throw new IllegalArgumentException("Object name already exists");
                float[] affine = floatArray(request.get("affine"));
                if (affine.length != 6) throw new IllegalArgumentException("PNG-to-canvas affine requires a,b,c,d,tx,ty");
                var pixels = ((NativeBridgeAssets.PngInput) staged).pixels();
                int drawOrder = integer(request, "draw_order", 500);
                for (float x : new float[]{0, pixels.getWidth()})
                    for (float y : new float[]{0, pixels.getHeight()})
                        checkPngVertex(affine[0]*x+affine[2]*y+affine[4], affine[1]*x+affine[3]*y+affine[5]);
                var undo = new com.live2d.undo.GroupUndo("Native bridge: import PNG");
                undo.plusAssign(new com.live2d.undo.SimpleUndo("Import material",source.getTextureManager(),new com.live2d.core.a()));
                try {
                    var resource = new com.live2d.graphics.CImageResource(pixels,false);
                    var material = com.live2d.cubism.doc.model.texture.modelImage.CModelImage.Companion.a(
                        source,name,resource,"Native original artwork",new com.live2d.type.CModelImageGuid());
                    var meshes = source.getHandler().a(null,List.of(material),source.getRootPart(),drawOrder,1f,undo);
                    var mesh = meshes.get(0); mesh.setLocalName(name);
                    for (var form : mesh.getKeyforms()) {
                        float[] positions = form.getPositions().clone();
                        for (int i=0;i<positions.length;i+=2) {
                            float x=positions[i],y=positions[i+1];
                            positions[i]=affine[0]*x+affine[2]*y+affine[4];
                            positions[i+1]=affine[1]*x+affine[3]*y+affine[5];
                            checkPngVertex(positions[i], positions[i+1]);
                        }
                        form.setPositions(positions); form.setUpdated();
                    }
                    finishEdit(doc,undo); return objectInfo(mesh,true);
                } catch (Throwable error) { undo.undo(); refresh(doc); throw error; }
            }
            case "set_visibility": {
                var item = findObject(source,(String)request.get("object_guid"));
                if (!(request.get("visible") instanceof Boolean)) throw new IllegalArgumentException("visible must be a boolean");
                if (batchPreflight) return null;
                var undo = item.getHandler().createUndo_forBasicSetting("Native bridge: set visibility");
                item.setVisible((Boolean)request.get("visible"));
                finishEdit(doc,undo);
                return batchUndo == null ? objectInfo(item,false) : null;
            }
            case "set_clipping": {
                var item = findObject(source,(String)request.get("object_guid"));
                if (!(item instanceof com.live2d.cubism.doc.model.drawable.ACDrawableSource drawable))
                    throw new IllegalArgumentException("Drawable required");
                var masks = new com.live2d.type.CArrayList<com.live2d.type.CDrawableGuid>();
                for (var guid : new LinkedHashSet<>((List<?>) request.get("mask_guids"))) {
                    var mask = findObject(source,(String)guid);
                    if (!(mask instanceof com.live2d.cubism.doc.model.drawable.ACDrawableSource clipper) || mask == item)
                        throw new IllegalArgumentException("Valid drawable mask required");
                    masks.add(clipper.getGuid());
                }
                if (batchPreflight) return null;
                var undo = item.getHandler().createUndo_forBasicSetting("Native bridge: set clipping");
                drawable.setClipGuidList(masks);
                drawable.setInvertClippingMask(Boolean.TRUE.equals(request.get("invert")));
                finishEdit(doc,undo);
                return batchUndo == null ? objectInfo(item,true) : null;
            }
            case "objects": return listObjects(source, request);
            case "object": {
                var item = findObject(source, (String) request.get("object_guid"));
                var forms = request.get("form_guids") instanceof List<?> ids ? new LinkedHashSet<>(ids) : null;
                if (forms != null && !forms.isEmpty()) {
                    var available = new HashSet<String>();
                    for (var form : item.getKeyforms()) available.add(form.getGuid().getUuidString());
                    for (var id : forms)
                        if (!available.contains(nonblank(id, "form GUID"))) throw new IllegalArgumentException("Keyform not found");
                }
                return objectInfo(item, !Boolean.FALSE.equals(request.get("geometry")), forms);
            }
            case "replace_mesh_texture": {
                var item=findObject(source,(String)request.get("object_guid"));
                if (!(item instanceof CArtMeshSource mesh)) throw new IllegalArgumentException("ArtMesh required");
                var png = (NativeBridgeAssets.PngInput) staged;
                Path path = png.path();
                var input=mesh.getTextureInputExtension().getModelImageInput();
                var material=input==null ? null : input.getModelImageOrNull();
                if (material==null) throw new IllegalArgumentException("Embedded model-image material required");
                var pixels = png.pixels();
                if (pixels.getWidth()!=material.getWidth() || pixels.getHeight()!=material.getHeight())
                    throw new IllegalArgumentException("Replacement dimensions must match the embedded material");
                float[] before=input.getInputImageLocalToCanvasTransform().copy().getMatrix();
                var resource=new com.live2d.graphics.CImageResource(pixels,false);
                var undo=new MaterialPixelsUndo(doc,material);
                try {
                    closeRenderSessions(doc.getDocumentUID());
                    var filters=material.getInputFilter();
                    var imagePort=com.live2d.cubism.doc.model.extension.textureInput.inputFilter.ModelImageFilterEnv.Companion.c();
                    var output=filters.getExternalOutputs().get(imagePort.getId());
                    if (output!=null && output.getFilter().getFilterDef() instanceof com.live2d.graphics.filter.concreteFilter.EmptyFilter
                        && output.getFilter().getInputConnectors().get(output.getFilterValueDef().getId()) instanceof com.live2d.graphics.filter.concreteConnector.ConstValueConnector) {
                        output.getFilter().setInput(imagePort,new com.live2d.graphics.filter.concreteConnector.ConstValueConnector<>(resource));
                    } else {
                        var filter=filters.addFilter(new com.live2d.graphics.filter.concreteFilter.EmptyFilter(imagePort),null);
                        filter.setInput(imagePort,new com.live2d.graphics.filter.concreteConnector.ConstValueConnector<>(resource));
                        filters.connectEnvOutput(filter,imagePort,imagePort);
                    }
                    material.reset();
                    refreshMaterialTextures(source,material);
                    float[] after=input.getInputImageLocalToCanvasTransform().copy().getMatrix();
                    if (!Arrays.equals(before,after)) throw new IllegalStateException("Material transform changed");
                    finishEdit(doc,undo);
                    List<String> users=new ArrayList<>();
                    for (var other:source.getAllArtMeshes()) {
                        var mi=other.getTextureInputExtension().getModelImageInput();
                        if (mi!=null && mi.getModelImageGuid().equals(material.getGuid())) users.add(other.getGuid().getUuidString());
                    }
                    return map("material_guid",material.getGuid().getUuidString(),"width",pixels.getWidth(),"height",pixels.getHeight(),
                        "image_to_source",after,"affected_mesh_guids",users,"path",path.toString());
                } catch (Throwable error) { undo.undo();refresh(doc);throw error; }
            }
            case "reparent": {
                var item = findObject(source,(String)request.get("object_guid"));
                String parentGuid = (String)request.get("parent_guid");
                ACDeformerSource parent = parentGuid == null ? null : requireDeformer(source, parentGuid);
                if (parent == item) throw new IllegalArgumentException("An object cannot parent itself");
                if (parent != null) for (var ancestor : parent.getAllParentDeformers())
                    if (ancestor == item) throw new IllegalArgumentException("Deformer hierarchy cycle");
                var target = parent == null ? CDeformerGuid.Companion.a() : parent.getGuid();
                if (item.getTargetDeformerGuid().equals(target)) return objectInfo(item,true);
                // Author hierarchy in the neutral pose, regardless of preview state.
                CModel model = source.getCurrentInstance();
                var saved = model.saveParameters(new HashMap<>());
                try {
                    model.getParameterSet().setDefaultValues();
                    evaluateModel(doc);
                    finishEdit(doc,item.getHandler().changeTargetDeformer(model,target,true));
                } finally { model.loadParameters(saved); evaluateModel(doc); }
                return objectInfo(item,true);
            }
            case "add_parameter_keys": {
                var item = findKeyformObject(source,(String)request.get("object_guid"));
                var parameter = findParameter(source,(String)request.get("parameter_id"));
                if (!parameter.isNormal()) throw new IllegalArgumentException("Normal parameters only");
                var values = floatArray(request.get("values"));
                if (values.length == 0) throw new IllegalArgumentException("At least one key required");
                for (float value : values) checkParameterValue(parameter,value);
                if (batchPreflight) return null;
                ACUndoable undo = item.getHandler().createUndo_forKeyformGridSourceEdit("Native bridge: add parameter keys");
                try {
                    var grid = item.getKeyformGridSource();
                    if (!grid.contains(parameter.getGuid())) grid.addKey(parameter.getDefaultValue(),parameter.getGuid());
                    for (float value : values) grid.addKey(value,parameter.getGuid());
                    finishEdit(doc,undo);
                } catch (Throwable error) { undo.undo(); refresh(doc); throw error; }
                return batchUndo == null ? keyformObjectInfo(item) : null;
            }
            case "set_form_positions": {
                var item = findObject(source,(String)request.get("object_guid"));
                var form = findForm(item, (String) request.get("form_guid"));
                float[] values = floatArray(request.get("positions"));
                if (values.length != formCoordinates(form).length) throw new IllegalArgumentException("Vertex count must remain unchanged");
                if (batchPreflight) return null;
                ACUndoable undo = item.getHandler().createUndo_forKeyformEdit("Native bridge: edit form vertices");
                if (form instanceof CArtMeshForm mesh) mesh.setPositions(values);
                else if (form instanceof CWarpDeformerForm warp) warp.setPositions(values);
                form.setUpdated();
                finishEdit(doc,undo);
                return formEditReceipt(item, form, request);
            }
            case "set_form_properties": {
                var item = findObject(source,(String)request.get("object_guid"));
                var form = findForm(item,(String)request.get("form_guid"));
                Map<?,?> properties = (Map<?,?>)request.get("properties");
                if (!(form instanceof CRotationDeformerForm || form instanceof ACDrawableForm))
                    throw new IllegalArgumentException("Drawable or rotation form required");
                Set<String> allowed = form instanceof CRotationDeformerForm ? Set.of("angle","origin_x","origin_y","scale") : Set.of("opacity","draw_order");
                for (var entry : properties.entrySet()) {
                    if (!allowed.contains(entry.getKey())) throw new IllegalArgumentException("Unsupported form property: " + entry.getKey());
                    float value = ((Number)entry.getValue()).floatValue();
                    if (!Float.isFinite(value)) throw new IllegalArgumentException("Finite property values required");
                    if ("draw_order".equals(entry.getKey())) {
                        double raw = ((Number) entry.getValue()).doubleValue();
                        if (raw != (int) raw) throw new IllegalArgumentException("Draw order must be an integer");
                    }
                    if ("opacity".equals(entry.getKey()) && (value < 0 || value > 1)) throw new IllegalArgumentException("Opacity must be 0..1");
                    if ("scale".equals(entry.getKey()) && value <= 0) throw new IllegalArgumentException("Positive scale required");
                }
                if (batchPreflight) return null;
                ACUndoable undo = item.getHandler().createUndo_forKeyformEdit("Native bridge: edit form properties");
                for (var entry : properties.entrySet()) {
                    float value = ((Number)entry.getValue()).floatValue();
                    if (form instanceof CRotationDeformerForm rotation) {
                        switch ((String)entry.getKey()) {
                            case "angle": rotation.setAngle(value); break;
                            case "origin_x": rotation.setOriginX(value); break;
                            case "origin_y": rotation.setOriginY(value); break;
                            case "scale": rotation.setScale(value); break;
                        }
                    } else if (form instanceof ACDrawableForm drawable) {
                        if ("opacity".equals(entry.getKey())) drawable.setOpacity(value);
                        else drawable.setDrawOrder(((Number) entry.getValue()).intValue());
                    }
                    else throw new IllegalArgumentException("Drawable or rotation form required");
                }
                form.setUpdated(); finishEdit(doc,undo);
                return formEditReceipt(item, form, request);
            }
            case "set_mesh_topology": {
                var item = findObject(source,(String)request.get("object_guid"));
                if (!(item instanceof CArtMeshSource mesh)) throw new IllegalArgumentException("ArtMesh required");
                var dependencies = NativeBridgeModel.topologyReferences(source, mesh);
                float[] vertices = floatArray(request.get("positions"));
                List<?> inputIndices = (List<?>)request.get("indices");
                if (vertices.length < 6 || vertices.length%2 != 0 || inputIndices.size()%3 != 0 || inputIndices.isEmpty())
                    throw new IllegalArgumentException("XY vertices and triangle indices required");
                int[] indices = new int[inputIndices.size()];
                for (int i=0;i<indices.length;i++) {
                    Number n = (Number)inputIndices.get(i);
                    indices[i] = n.intValue();
                    if (n.doubleValue() != indices[i] || indices[i] < 0 || indices[i] >= vertices.length/2)
                        throw new IllegalArgumentException("Invalid triangle vertex index");
                }
                for (int offset = 0; offset < indices.length; offset += 3) {
                    int a = 2 * indices[offset], b = 2 * indices[offset + 1], c = 2 * indices[offset + 2];
                    double x = (double) vertices[b] - vertices[a], y = (double) vertices[b + 1] - vertices[a + 1];
                    if (x * ((double) vertices[c + 1] - vertices[a + 1])
                            == y * ((double) vertices[c] - vertices[a]))
                        throw new IllegalArgumentException("Degenerate triangle at index " + offset / 3);
                }
                if (Boolean.TRUE.equals(request.get("preflight"))) return dependencies;
                var newMesh = new com.live2d.graphics3d.editableMesh.GEditableMesh2();
                newMesh.initByIndices(vertices,indices,mesh.getEditableMesh().getCoordType());
                var undo = mesh.getHandler().a("Native bridge: edit mesh topology");
                var model = source.getCurrentInstance();
                var observers = new ArrayList<com.live2d.cubism.doc.model.extension.structureObserver.CTopologyObserverExtension>();
                for (var extension : mesh.getExtensions())
                    if (extension instanceof com.live2d.cubism.doc.model.extension.structureObserver.CTopologyObserverExtension observer) observers.add(observer);
                try {
                    for (var observer : observers) observer.fireTopologyWillChange(model);
                    commitTopology(mesh,newMesh);
                    source.updateModelInstances();
                    for (var observer : observers) observer.fireTopologyChanged(model,true);
                    finishEdit(doc,undo);
                } catch (Throwable error) { undo.undo(); refresh(doc); throw error; }
                var result = objectInfo(mesh, true);
                result.put("topology_impact", dependencies);
                return result;
            }
            case "evaluate": return evaluate(source, request);
            case "create_warp": {
                String name = (String) request.get("name");
                if (name == null || name.isBlank()) throw new IllegalArgumentException("Name required");
                int columns = integer(request, "columns", 2), rows = integer(request, "rows", 2);
                int bezierColumns = integer(request, "bezier_columns", 2), bezierRows = integer(request, "bezier_rows", 2);
                if (columns < 1 || columns > 30 || rows < 1 || rows > 30)
                    throw new IllegalArgumentException("Warp grid subdivisions must be 1..30");
                // The editor's Bezier division controls accept at most 100 per axis.
                if (bezierColumns < 1 || bezierColumns > 100 || bezierRows < 1 || bezierRows > 100)
                    throw new IllegalArgumentException("Bezier grid subdivisions must be 1..100");
                for (ACDeformerSource existing : source.getDeformerSourceSet().getSources())
                    if (name.equals(existing.getLocalName())) throw new IllegalArgumentException("Deformer name already exists");
                float x = number(request, "x"), y = number(request, "y"), width = number(request, "width"), height = number(request, "height");
                if (width <= 0 || height <= 0) throw new IllegalArgumentException("Positive bounds required");
                if (!Float.isFinite(x + columns * (width / columns))
                        || !Float.isFinite(y + rows * (height / rows)))
                    throw new IllegalArgumentException("Warp bounds produce nonfinite coordinates");
                var part = targetPart(source, request.get("part_guid"));
                // Same internal factory as the native command: part membership,
                // GUID, default form, interpolation grid, Bezier extension and undo.
                Pair<CWarpDeformerSource,ACUndoable> edit = com.live2d.cubism.doc.model.deformer.j.a.a(
                    source.getCurrentInstance(), name, part, childIndex(request, part.getChildren().size()),
                    targetDeformer(source, request.get("parent_guid")), columns, rows, bezierColumns, bezierRows,
                    new GRectF(x, y, width, height));
                finishEdit(doc,edit.getSecond());
                return deformer(edit.getFirst());
            }
            case "create_rotation": {
                String name = (String)request.get("name");
                if (name == null || name.isBlank()) throw new IllegalArgumentException("Name required");
                for (var existing : source.getDeformerSourceSet().getSources())
                    if (name.equals(existing.getLocalName())) throw new IllegalArgumentException("Deformer name already exists");
                float x = number(request,"x"), y = number(request,"y"), length = number(request,"handle_length");
                if (length <= 0) throw new IllegalArgumentException("Positive handle length required");
                var part = targetPart(source, request.get("part_guid"));
                var edit = com.live2d.cubism.doc.model.deformer.j.a.a(source.getCurrentInstance(), name, part,
                    childIndex(request, part.getChildren().size()), targetDeformer(source, request.get("parent_guid")),
                    null, 0f, 1f, 0f, 30f, length, new com.live2d.graphics3d.type.GVector2(x,y));
                finishEdit(doc,edit.getSecond());
                return deformer(edit.getFirst());
            }
            case "undo": return replayEdit(doc, false);
            case "redo": return replayEdit(doc, true);
            case "save_as_new": {
                Path path = NativeBridgeAssets.output(workspace, request.get("path"), ".cmo3");
                return NativeBridgeAssets.saveModel(doc, workspace, path);
            }
            case "close_saved":
                if (doc.getFile() == null || doc.isModifiedAfterSaving())
                    throw new IllegalStateException("Save or undo changes before closing");
                closeRenderSessions(doc.getDocumentUID());
                NativeBridgeJobs.closeDocument(doc.getDocumentUID());
                app.command_close(doc); return map("close_requested", true);
            default: throw new IllegalArgumentException("Unsupported operation: " + op);
        }
    }

    private static CModelingDocument findDocument(CEAppCtrl app, String uid) {
        if (uid == null) throw new IllegalArgumentException("Explicit document_uid required");
        for (CModelingDocument doc : app.getAllModelDocs()) if (uid.equals(doc.getDocumentUID())) return doc;
        throw new IllegalArgumentException("Document not found: " + uid);
    }

    private static boolean sameOpenFile(java.io.File candidate, Path target) throws IOException {
        if (candidate == null) return false;
        try { return candidate.toPath().toRealPath().equals(target); }
        catch (NoSuchFileException missing) { return false; }
    }

    static Map<String,Object> describe(CModelingDocument doc) {
        CModelSource source = doc.getModelSource();
        List<Object> deformers = new ArrayList<>();
        for (ACDeformerSource item : source.getDeformerSourceSet().getSources()) deformers.add(deformer(item));
        return map("document_uid", doc.getDocumentUID(), "file", doc.getFile() == null ? null : doc.getFile().toString(),
            "name", source.getName(), "file_editor_edition_code", source.getEditorEdition() == null ? null : source.getEditorEdition().a(),
            "target_version", String.valueOf(source.getTargetVersion()),
            "canvas_width", source.getCanvas().getPixelWidth(), "canvas_height", source.getCanvas().getPixelHeight(),
            "drawables", source.getDrawableSourceSet().getSources().size(), "deformers", deformers,
            "materials",source.getTextureManager().getAllModelImages().size(),
            "modified", doc.isModifiedAfterSaving(), "undo_position", doc.getUndoManager().getCurrentPos(),
            "revision", revision(doc));
    }

    static String revision(IDocument doc) {
        var undo = doc.getUndoManager();
        int position = undo.getCurrentPos();
        Object newest = position > 0 ? undo.getUndoList().get(position - 1) : null;
        String uid;
        long modified;
        if (doc instanceof CModelingDocument model) {
            uid = model.getDocumentUID();
            modified = model.getLastModifiedTime();
        } else if (doc instanceof CSceneDocument scene) {
            uid = scene.getDocumentUID();
            modified = scene.getAnimationContent().getLastModifiedTime();
        } else throw new IllegalArgumentException("Unsupported document type");
        return uid + ":" + modified + ":" + position + ":" + System.identityHashCode(newest);
    }

    static void checkRevision(IDocument doc, Map<String,Object> request) {
        if (request.get("expected_revision") instanceof String expected && !expected.equals(revision(doc)))
            throw new IllegalArgumentException("Document revision changed; inspect the document before retrying");
    }

    static Map<String,Object> physicsInfo(CModelSource source) {
        var settings=source.getPhysicsSettingsSourceSet();List<Object> groups=new ArrayList<>();
        for (var group:settings.getSources()) {
            List<Object> inputs=new ArrayList<>(),outputs=new ArrayList<>(),vertices=new ArrayList<>();
            for (var input:group.getInputs()) inputs.add(map("parameter",source.getParameterSourceSet().get(input.getSource()).getId().toString(),
                "type",input.getType().name(),"weight",input.getWeight(),"reverse",input.isReverse()));
            for (var output:group.getOutputs()) outputs.add(map("parameter",source.getParameterSourceSet().get(output.getDestination()).getId().toString(),
                "type",output.getType().name(),"weight",output.getWeight(),"reverse",output.isReverse(),
                "vertex_index",output.getVertexIndex(),"angle_scale",output.getAngleScale(),
                "translation_scale",List.of(output.getTranslationScale().getX(),output.getTranslationScale().getY())));
            for (var vertex:group.getVertices()) vertices.add(map("position",List.of(vertex.getPosition().getX(),vertex.getPosition().getY()),
                "mobility",vertex.getMobility(),"delay",vertex.getDelay(),"acceleration",vertex.getAcceleration(),"radius",vertex.getRadius()));
            groups.add(map("guid",group.getGuid().getUuidString(),"id",group.getId().toString(),"name",group.getName(),"enabled",group.getEnable(),
                "inputs",inputs,"outputs",outputs,"vertices",vertices,
                "normalization_position",List.of(group.getNormalizedPositionValueMin(),group.getNormalizedPositionValueMax(),group.getNormalizedPositionDefaultValue()),
                "normalization_angle",List.of(group.getNormalizedAngleValueMin(),group.getNormalizedAngleValueMax(),group.getNormalizedAngleDefaultValue())));
        }
        return map("groups",groups,"fps",settings.getSettingFPS(),"gravity",List.of(settings.getGravity().getX(),settings.getGravity().getY()),
            "wind",List.of(settings.getWind().getX(),settings.getWind().getY()));
    }

    private static Object updateParameter(CModelingDocument doc, Map<String,Object> request) {
        var source = doc.getModelSource();
        var parameter = findParameter(source, (String) request.get("parameter_id"));
        if (!parameter.isNormal()) throw new IllegalArgumentException("Normal parameters only");
        var patch = (Map<?,?>) request.get("patch");
        float min = optionalNumber(patch, "minimum", parameter.getMinValue());
        float max = optionalNumber(patch, "maximum", parameter.getMaxValue());
        float def = optionalNumber(patch, "default_value", parameter.getDefaultValue());
        if (min >= max || def < min || def > max)
            throw new IllegalArgumentException("Invalid parameter range/default");
        String name = patch.containsKey("name") ? nonblank(patch.get("name"), "name") : parameter.getName();
        String description = patch.containsKey("description") ? (String) patch.get("description") : parameter.getDescription();
        var affected = new ArrayList<Object>();
        var blockers = new ArrayList<String>();
        boolean preflight = Boolean.TRUE.equals(request.get("preflight"));
        boolean rangeChanged = min != parameter.getMinValue() || max != parameter.getMaxValue();
        if (preflight || rangeChanged || patch.containsKey("default_value")) {
            for (var item : source.getAllObjects()) {
                var binding = item.getKeyformGridSource().findKeyformBinding(parameter.getGuid());
                if (binding == null) continue;
                if (preflight) affected.add(map("object_guid", item.getGuid().getUuidString(), "keys", List.copyOf(binding.getKeys())));
                if (binding.isRepeat() && rangeChanged)
                    blockers.add("Changing this range requires relocating repeating endpoint keys on " + item.getId());
                if (binding.getKeys().stream().anyMatch(key -> key < min || key > max))
                    blockers.add("Existing key outside new range on " + item.getId());
            }
        }
        if (preflight)
            return map("ready", blockers.isEmpty(), "blockers", blockers, "affected_bindings", affected);
        if (!blockers.isEmpty()) throw new IllegalArgumentException(blockers.get(0));
        var undo = new SimpleUndo("Native bridge: update parameter", parameter, new com.live2d.core.a());
        parameter.setName(name);
        parameter.setDescription(description);
        parameter.setMinValue(min);
        parameter.setMaxValue(max);
        parameter.setDefaultValue(def);
        finishEdit(doc, undo);
        return map("id", parameter.getId().toString(), "guid", parameter.getGuid().getUuidString(),
            "name", name, "description", description, "minimum", min, "maximum", max, "default", def);
    }

    private static Object editParameterKeys(CModelingDocument doc, Map<String,Object> request, String operation) {
        var source = doc.getModelSource();
        var item = findKeyformObject(source, (String) request.get("object_guid"));
        var parameter = findParameter(source, (String) request.get("parameter_id"));
        if (!parameter.isNormal()) throw new IllegalArgumentException("Normal parameters only");
        var grid = item.getKeyformGridSource();
        var guid = parameter.getGuid();
        var binding = grid.findKeyformBinding(guid);
        if (binding == null) throw new IllegalArgumentException("Object is not bound to this parameter");
        List<Float> oldKeys = List.copyOf(binding.getKeys());
        var undo = batchPreflight ? null : item.getHandler().createUndo_forKeyformGridSourceEdit("Native bridge: " + operation);
        try {
            if ("move_parameter_keys".equals(operation)) {
                Map<Float,Float> moves = new LinkedHashMap<>();
                for (var value : (List<?>) request.get("moves")) {
                    var move = (Map<?,?>) value;
                    float from = finite(move.get("from_value"), "from_value");
                    float to = finite(move.get("to_value"), "to_value");
                    checkParameterValue(parameter, to);
                    if (!oldKeys.contains(from) || moves.putIfAbsent(from, to) != null)
                        throw new IllegalArgumentException("Source keys must exist and be distinct");
                    if (binding.isRepeat() && from != to && (from == binding.getRangeMin() || from == binding.getRangeMax()))
                        throw new IllegalArgumentException("Cannot move a repeating parameter endpoint");
                }
                List<Float> newKeys = oldKeys.stream().map(key -> moves.getOrDefault(key, key)).toList();
                if (moves.isEmpty() || new HashSet<>(newKeys).size() != newKeys.size())
                    throw new IllegalArgumentException("At least one move and distinct destination keys are required");
                if (batchPreflight) return null;
                grid.rearrangeKeyformsOnParameter(guid, oldKeys, newKeys);
            } else {
                float[] values = floatArray(request.get("values"));
                Set<Float> removed = new LinkedHashSet<>();
                for (float value : values) {
                    if (!oldKeys.contains(value) || !removed.add(value) || !grid.isAvailableRemoveKey(value, guid))
                        throw new IllegalArgumentException("Key is missing, duplicated, or cannot be removed: " + value);
                }
                if (removed.isEmpty()) throw new IllegalArgumentException("At least one key is required");
                if (batchPreflight) return null;
                for (float value : removed) grid.removeKey(value, guid);
            }
        } catch (RuntimeException error) {
            if (undo != null) { undo.undo(); refresh(doc); }
            throw error;
        }
        finishEdit(doc, undo);
        return batchUndo == null ? keyformObjectInfo(item) : null;
    }

    private static Object updatePhysicsGroup(CModelingDocument doc, Map<String,Object> request) {
        var source = doc.getModelSource();
        var settings = source.getPhysicsSettingsSourceSet();
        String guid = (String) request.get("group_guid");
        var group = settings.getSources().stream().filter(value -> guid.equals(value.getGuid().getUuidString()))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Physics group not found: " + guid));
        checkPhysicsBindings(source, request.get("expected_inputs"), group.getInputs().stream().map(input -> input.getSource()).toList());
        checkPhysicsBindings(source, request.get("expected_outputs"), group.getOutputs().stream().map(output -> output.getDestination()).toList());
        var patch = (Map<?,?>) request.get("patch");
        var undo = new SimpleUndo("Native bridge: update physics group", settings, new com.live2d.core.a());
        Map<String,Object> result;
        try {
            if (patch.containsKey("enabled")) group.setEnable((Boolean) patch.get("enabled"));
            if (patch.get("inputs") instanceof List<?> inputs) {
                for (var entry : inputs) {
                    var row = (Map<?,?>) entry;
                    var input = group.getInputs().get(rowIndex(row, group.getInputs().size()));
                    input.setWeight(bounded(row.get("weight"), "input weight", 0, 100));
                }
            }
            if (patch.get("outputs") instanceof List<?> outputs) {
                for (var entry : outputs) {
                    var row = (Map<?,?>) entry;
                    var output = group.getOutputs().get(rowIndex(row, group.getOutputs().size()));
                    output.setWeight(bounded(row.get("weight"), "output weight", 0, 100));
                }
            }
            if (patch.get("vertices") instanceof List<?> vertices) {
                for (var entry : vertices) {
                    var row = (Map<?,?>) entry;
                    var vertex = group.getVertices().get(rowIndex(row, group.getVertices().size()));
                    if (row.containsKey("mobility")) vertex.setMobility(bounded(row.get("mobility"), "mobility", 0, 1));
                    if (row.containsKey("delay")) vertex.setDelay(bounded(row.get("delay"), "delay", 0, 1));
                    if (row.containsKey("acceleration"))
                        vertex.setAcceleration(bounded(row.get("acceleration"), "acceleration", 0, Float.MAX_VALUE));
                    if (row.containsKey("radius")) vertex.setRadius(bounded(row.get("radius"), "radius", 0, Float.MAX_VALUE));
                    if (row.get("position") instanceof List<?> position) {
                        if (position.size() != 2) throw new IllegalArgumentException("Particle position requires x and y");
                        vertex.setPosition(new GVector2(finite(position.get(0), "x"), finite(position.get(1), "y")));
                    }
                }
            }
            if (patch.get("normalization_position") != null) {
                var values = normalization(patch.get("normalization_position"));
                group.setNormalizedPositionValueMin(values[0]);
                group.setNormalizedPositionValueMax(values[1]);
                group.setNormalizedPositionDefaultValue(values[2]);
            }
            if (patch.get("normalization_angle") != null) {
                var values = normalization(patch.get("normalization_angle"));
                group.setNormalizedAngleValueMin(values[0]);
                group.setNormalizedAngleValueMax(values[1]);
                group.setNormalizedAngleDefaultValue(values[2]);
            }
            group.updatePhysicsSettings();
            result = physicsInfo(source);
        } catch (RuntimeException | Error error) {
            undo.undo();
            refresh(doc);
            throw error;
        }
        finishEdit(doc, undo);
        return result;
    }

    private static int rowIndex(Map<?,?> row, int count) {
        Object value = row.get("index");
        if (!(value instanceof Number index) || index.doubleValue() != index.intValue()
                || index.intValue() < 0 || index.intValue() >= count)
            throw new IllegalArgumentException("Physics row index is outside the group: " + value);
        return index.intValue();
    }

    private static void checkPhysicsBindings(CModelSource source, Object value, List<com.live2d.type.CParameterGuid> bindings) {
        if (!(value instanceof Map<?,?> expected)) return;
        for (var entry : expected.entrySet()) {
            int index = Integer.parseInt((String) entry.getKey());
            if (index < 0 || index >= bindings.size()
                    || !findParameter(source, nonblank(entry.getValue(), "parameter_id")).getGuid().equals(bindings.get(index)))
                throw new IllegalArgumentException("Physics parameter binding changed at index " + index);
        }
    }

    private static float[] normalization(Object input) {
        float[] values = floatArray(input);
        if (values.length != 3 || values[0] >= values[1] || values[2] < values[0] || values[2] > values[1])
            throw new IllegalArgumentException("Normalization requires [minimum, maximum, default] with default in range");
        return values;
    }

    private static float optionalNumber(Map<?,?> patch, String key, float fallback) {
        return patch.containsKey(key) ? finite(patch.get(key), key) : fallback;
    }

    static float finite(Object value, String label) {
        if (!(value instanceof Number number) || !Float.isFinite(number.floatValue()))
            throw new IllegalArgumentException(label + " must be finite");
        return number.floatValue();
    }

    static float bounded(Object value, String label, float minimum, float maximum) {
        float number = finite(value, label);
        if (number < minimum || number > maximum)
            throw new IllegalArgumentException(label + " outside " + minimum + ".." + maximum);
        return number;
    }

    static String nonblank(Object value, String label) {
        if (!(value instanceof String text) || text.isBlank())
            throw new IllegalArgumentException(label + " is required");
        return text;
    }

    private static CPartSource requirePart(CModelSource source, String guid) {
        var item = findObject(source, guid);
        if (!(item instanceof CPartSource part)) throw new IllegalArgumentException("Part required");
        return part;
    }

    private static Object formEditReceipt(ACParameterControllableSource item, ACForm form, Map<String,Object> request) {
        if (batchUndo != null) return null;
        if (Boolean.TRUE.equals(request.get("compact")))
            return map("object_guid", item.getGuid().getUuidString(), "form_guid", form.getGuid().getUuidString(), "applied", true);
        return objectInfo(item, true);
    }

    private static ACDeformerSource requireDeformer(CModelSource source, String guid) {
        if (findObject(source, guid) instanceof ACDeformerSource parent) return parent;
        throw new IllegalArgumentException("A deformer GUID is required for parent_guid");
    }

    private static CDeformerGuid targetDeformer(CModelSource source, Object value) {
        return value == null ? CDeformerGuid.Companion.a() : requireDeformer(source, (String) value).getGuid();
    }

    private static CPartSource targetPart(CModelSource source, Object value) {
        var root = source.getRootPart();
        return value == null || root.getGuid().getUuidString().equals(value) ? root : requirePart(source, (String)value);
    }

    private static int childIndex(Map<String,Object> request, int size) {
        int index = integer(request, "index", -1);
        if (index < -1 || index > size) throw new IllegalArgumentException("Child index is outside the destination");
        return index;
    }

    private static CParameterGroup parameterGroup(CModelSource source, String guid) {
        if (guid == null || guid.equals(source.getRootParameterGroup().getGuid().getUuidString()))
            return source.getRootParameterGroup();
        return source.getParameterGroupSet().getGuidMap().values().stream()
            .filter(group -> guid.equals(group.getGuid().getUuidString())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Parameter group not found: " + guid));
    }

    private static Map<String,Object> parameterGroupInfo(CParameterGroup group) {
        return map("guid", group.getGuid().getUuidString(), "id", group.getId().toString(), "name", group.getName(),
            "parent_group_guid", group.getParentGroup() == null ? null : group.getParentGroup().getGuid().getUuidString(),
            "child_count", group.getChildren().size());
    }

    private static Object duplicateObjects(CModelingDocument doc, Map<String,Object> request) {
        var source = doc.getModelSource();
        var selected = new LinkedHashSet<ACParameterControllableSource>();
        var roots = (List<?>) request.get("object_guids");
        for (Object guid : roots) {
            var item = findObject(source, (String) guid);
            if (!(item instanceof CArtMeshSource || item instanceof ACDeformerSource))
                throw new IllegalArgumentException("Select ArtMeshes or deformers");
            selected.add(item);
        }
        if (selected.isEmpty()) throw new IllegalArgumentException("Select at least one object");
        var originalObjects = source.getAllObjects();
        var deformers = new HashMap<CDeformerGuid,ACParameterControllableSource>();
        for (var item : originalObjects) if (item instanceof ACDeformerSource d) deformers.put(d.getGuid(), d);
        expandInScanOrder(selected, originalObjects, item -> {
            if (!(item instanceof ACDeformerSource || item instanceof com.live2d.cubism.doc.model.drawable.ACDrawableSource))
                return List.of();
            var parent = deformers.get(item.getTargetDeformerGuid());
            return parent == null ? List.of() : List.of(parent);
        });
        for (var glue : glues(source)) {
            if (selected.contains(glue.getTargetArtMeshA()) && selected.contains(glue.getTargetArtMeshB())) selected.add(glue);
        }
        var targetPart = request.get("parent_part_guid") instanceof String guid ? requirePart(source, guid) : null;
        var context = new com.live2d.core.a(com.live2d.core.a.c.a, false);
        source.setupIdMap(context.i());
        var copies = new LinkedHashMap<ACParameterControllableSource,ACParameterControllableSource>();
        context.a(() -> {
            for (var original : selected) copies.put(original, original.deepCopy(context));
            return kotlin.Unit.INSTANCE;
        });
        var byGuid = new HashMap<com.live2d.type.CParameterControllableGuid,ACParameterControllableSource>();
        copies.forEach((original, copy) -> byGuid.put(original.getGuid(), copy));
        var vertexIndices = new HashMap<CArtMeshSource,Map<Long,Integer>>();
        for (var entry : copies.entrySet()) {
            var original = entry.getKey();
            var copy = entry.getValue();
            if (copy.getGuid().equals(original.getGuid())) throw new IllegalStateException("Native copy did not generate a GUID");
            var target = byGuid.get(original.getTargetDeformerGuid());
            copy.setTargetDeformerGuid(target instanceof ACDeformerSource d ? d.getGuid() : original.getTargetDeformerGuid());
            if (original instanceof com.live2d.cubism.doc.model.drawable.ACDrawableSource drawable
                    && copy instanceof com.live2d.cubism.doc.model.drawable.ACDrawableSource copyDrawable) {
                var masks = new com.live2d.type.CArrayList<com.live2d.type.CDrawableGuid>();
                for (var mask : drawable.getClipGuidList()) {
                    var copiedMask = byGuid.get(mask);
                    masks.add(copiedMask instanceof com.live2d.cubism.doc.model.drawable.ACDrawableSource d ? d.getGuid() : mask);
                }
                copyDrawable.setClipGuidList(masks);
            }
            if (original instanceof CGlueSource glue && copy instanceof CGlueSource copyGlue) {
                var a = (CArtMeshSource) copies.get(glue.getTargetArtMeshA());
                var b = (CArtMeshSource) copies.get(glue.getTargetArtMeshB());
                copyGlue.setTargetArtMeshA_guid(a.getGuid());
                copyGlue.setTargetArtMeshB_guid(b.getGuid());
                long[] vertices = copyGlue.getBindVertexUids();
                var aVertices = vertexIndices.computeIfAbsent(glue.getTargetArtMeshA(), NativeBridge::vertexIndex);
                var bVertices = vertexIndices.computeIfAbsent(glue.getTargetArtMeshB(), NativeBridge::vertexIndex);
                for (int offset = 0; offset < vertices.length; offset += 2) {
                    Integer aIndex = aVertices.get(vertices[offset]);
                    Integer bIndex = bVertices.get(vertices[offset + 1]);
                    if (aIndex == null || bIndex == null) throw new IllegalStateException("Glue references a missing vertex");
                    vertices[offset] = a.getEditableMesh().getPoints().get(aIndex).getPointUid();
                    vertices[offset + 1] = b.getEditableMesh().getPoints().get(bIndex).getPointUid();
                }
                copyGlue.setDirty_cached_bindVertexIndices(true);
            }
        }
        var undo = new GroupUndo("Native bridge: duplicate objects");
        try {
            for (var entry : copies.entrySet()) {
                var parent = targetPart == null ? entry.getKey().getParent() : targetPart;
                undo.plusAssign((parent == null ? source.getRootPart() : parent).getHandler().addPartChild(entry.getValue(), -1));
            }
        } catch (RuntimeException error) {
            undo.undo();
            refresh(doc);
            throw error;
        }
        finishEdit(doc, undo);
        var result = new LinkedHashMap<String,String>();
        copies.forEach((original, copy) -> result.put(original.getGuid().getUuidString(), copy.getGuid().getUuidString()));
        return map("guid_map", result);
    }

    private record ScanVisit<T>(T item, long position) {}

    /** Reproduce repeated ordered scans by visiting each reachable edge once. */
    static <T> void expandInScanOrder(Set<T> selected, List<? extends T> objects,
                                      java.util.function.Function<T,Iterable<? extends T>> parents) {
        int count = objects.size();
        var children = new HashMap<T,List<Integer>>();
        for (int i = 0; i < count; i++)
            for (var parent : parents.apply(objects.get(i)))
                children.computeIfAbsent(parent, key -> new ArrayList<>()).add(i);
        var queue = new PriorityQueue<ScanVisit<T>>(Comparator.comparingLong(ScanVisit::position));
        for (var root : selected)
            for (int child : children.getOrDefault(root, List.of()))
                queue.add(new ScanVisit<>(objects.get(child), child));
        while (!queue.isEmpty()) {
            var visit = queue.remove();
            if (!selected.add(visit.item)) continue;
            long round = visit.position / count * count;
            for (int child : children.getOrDefault(visit.item, List.of())) {
                if (selected.contains(objects.get(child))) continue;
                long position = round + child;
                if (position <= visit.position) position += count;
                queue.add(new ScanVisit<>(objects.get(child), position));
            }
        }
    }

    private static Object fitWarp(CModelingDocument doc, Map<String,Object> request) {
        var source = doc.getModelSource();
        var item = findObject(source, (String) request.get("object_guid"));
        if (!(item instanceof CWarpDeformerSource warp))
            throw new IllegalArgumentException("Warp deformer required");
        var children = source.getAllObjects().stream()
            .filter(child -> warp.getGuid().equals(child.getTargetDeformerGuid())).toList();
        var before = deformer(warp);
        if (!children.isEmpty()) {
            var undo = new GroupUndo("Native bridge: expand warp deformer");
            try {
                com.live2d.cubism.doc.model.deformer.o.a.a(List.of(warp), undo);
            } catch (RuntimeException error) {
                undo.undo();
                refresh(doc);
                throw error;
            }
            finishEdit(doc, undo);
        }
        return map("before", before, "deformer", deformer(warp), "keyforms", objectInfo(warp, true).get("forms"),
            "children", children.stream().map(child -> child.getGuid().getUuidString()).toList());
    }

    static CArtMeshSource requireMesh(CModelSource source, String guid) {
        var item = findObject(source, guid);
        if (!(item instanceof CArtMeshSource mesh)) throw new IllegalArgumentException("ArtMesh required");
        return mesh;
    }

    private static Object startAutoMesh(CModelingDocument doc, Map<String,Object> request) {
        var mesh = requireMesh(doc.getModelSource(), (String) request.get("object_guid"));
        if (doc.getCompletePack().getCurrentDoc() != doc || doc.getCurrentEditMode() != doc.getEditMode_modeling())
            throw new IllegalStateException("Automatic mesh generation requires the target document to be active in Modeling mode");
        var linked = NativeBridgeModel.topologyReferences(doc.getModelSource(), mesh);
        if (!((List<?>) linked.get("glue_guids")).isEmpty() || !((List<?>) linked.get("art_path_bindings")).isEmpty())
            throw new IllegalArgumentException("ArtMesh has Glue or ArtPath bindings: " + linked);
        if (!(mesh.getTextureInputExtension().getCurrentTextureInputData() instanceof CTextureInput_ModelImage))
            throw new IllegalArgumentException("Automatic mesh generation requires a model-image texture");

        var extension = new CMeshGeneratorExtension(mesh);
        var settings = extension.getMeshGenerateSetting();
        if (request.get("preset") instanceof String preset) {
            MeshGenerateSetting template = switch (preset) {
                case "large" -> MeshGenerateSetting.Companion.a();
                case "medium" -> MeshGenerateSetting.Companion.b();
                case "small" -> MeshGenerateSetting.Companion.c();
                default -> throw new IllegalArgumentException("Unknown automatic mesh preset: " + preset);
            };
            settings.reinit(template, new com.live2d.core.a());
        }
        if (request.get("settings") instanceof Map<?,?> patch) {
            if (patch.containsKey("outer_density")) settings.setPolygonOuterDensity(meshInteger(patch, "outer_density", 1, Integer.MAX_VALUE));
            if (patch.containsKey("inner_density")) settings.setPolygonInnerDensity(meshInteger(patch, "inner_density", 1, Integer.MAX_VALUE));
            if (patch.containsKey("margin")) settings.setPolygonMargin(meshInteger(patch, "margin", 0, Integer.MAX_VALUE));
            if (patch.containsKey("inner_margin")) settings.setPolygonInnerMargin(meshInteger(patch, "inner_margin", 0, Integer.MAX_VALUE));
            if (patch.containsKey("alpha_threshold")) settings.setThresholdAlpha(meshInteger(patch, "alpha_threshold", 0, 255));
        }

        var effective = List.of(settings.getPolygonOuterDensity(), settings.getPolygonInnerDensity(),
            settings.getPolygonMargin(), settings.getPolygonInnerMargin(), settings.getPolygonMinMargin(),
            settings.getPolygonDensityScale(), settings.getPolygonMinBoundsPt(), settings.getThresholdAlpha());
        if (activeAutoMesh != null) {
            if (activeAutoMesh.documentUid.equals(doc.getDocumentUID())
                    && activeAutoMesh.objectGuid.equals(mesh.getGuid().getUuidString())
                    && activeAutoMesh.settings.equals(effective)) return activeAutoMesh.info();
            throw new IllegalStateException("Automatic mesh generation is running: " + activeAutoMesh.id);
        }
        var job = new AutoMeshJob(doc, mesh, effective);
        activeAutoMesh = job;
        NativeBridgeJobs.registerMesh(job.documentUid, job.id, job::info);
        SwingUtilities.invokeLater(() -> runAutoMesh(doc, mesh, extension, job));
        return job.info();
    }

    private static int meshInteger(Map<?,?> values, String key, int min, int max) {
        Object value = values.get(key);
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()
                || number.intValue() < min || number.intValue() > max)
            throw new IllegalArgumentException("Invalid automatic mesh setting: " + key);
        return number.intValue();
    }

    private static void runAutoMesh(CModelingDocument doc, CArtMeshSource mesh,
            CMeshGeneratorExtension extension, AutoMeshJob job) {
        if (doc.getCompletePack().getCurrentDoc() != doc || doc.getCurrentEditMode() != doc.getEditMode_modeling()) {
            job.fail("Target document is no longer active in Modeling mode");
            return;
        }
        var mode = doc.getEditMode_modeling();
        job.state = "running";
        try {
            var input = (CTextureInput_ModelImage) mesh.getTextureInputExtension().getCurrentTextureInputData();
            var image = input.getModelImage().getFilteredImage().getImage().getJBufferedImage();
            var alpha = image.getAlphaRaster();
            int threshold = Math.max(1, extension.getMeshGenerateSetting().getThresholdAlpha());
            if (alpha != null && !containsAlpha(alpha, threshold)) {
                job.after = job.before;
                job.fail("The source image has no pixels at the requested alpha threshold");
                return;
            }
            closeRenderSessions(job.documentUid);
            var undo = mode.beginEdit("Native bridge: automatic mesh generation");
            try {
                AutoMeshUtil.INSTANCE.updateMeshFromInspector_inModelingMode(doc.getCompletePack(), com.live2d.ui.event.z.d,
                    List.of(extension), undo, () -> {
                        Runnable complete = () -> job.complete(doc, mesh, undo);
                        if (SwingUtilities.isEventDispatchThread()) complete.run();
                        else SwingUtilities.invokeLater(complete);
                        return kotlin.Unit.INSTANCE;
                    });
            } catch (RuntimeException | Error error) {
                mode.endEdit(true, null);
                throw error;
            }
        } catch (RuntimeException | Error error) {
            job.fail(error.toString());
        }
    }

    private static boolean containsAlpha(java.awt.image.Raster alpha, int threshold) {
        int width = alpha.getWidth();
        var row = new int[width];
        for (int y = alpha.getMinY(); y < alpha.getMinY() + alpha.getHeight(); y++) {
            alpha.getSamples(alpha.getMinX(), y, width, 1, 0, row);
            for (int value : row) if (value >= threshold) return true;
        }
        return false;
    }

    private static final class AutoMeshJob {
        final String id = UUID.randomUUID().toString();
        final String documentUid;
        final String objectGuid;
        final List<? extends Number> settings;
        final Map<String,Object> before;
        volatile String state = "queued";
        volatile Map<String,Object> after;
        volatile String error;

        AutoMeshJob(CModelingDocument doc, CArtMeshSource mesh, List<? extends Number> settings) {
            documentUid = doc.getDocumentUID();
            objectGuid = mesh.getGuid().getUuidString();
            this.settings = settings;
            before = meshCounts(mesh);
        }

        Map<String,Object> info() {
            return map("job_id", id, "document_uid", documentUid, "object_guid", objectGuid,
                "state", state, "before", before, "after", after, "error", error);
        }

        void complete(CModelingDocument doc, CArtMeshSource mesh, ACUndoable edit) {
            try {
                after = meshCounts(mesh);
                var manager = doc.getUndoManager();
                int position = manager.getCurrentPos();
                var edits = manager.getUndoList();
                int index = edits.indexOf(edit);
                if (index < 0 || index >= position) {
                    fail("Cubism did not commit the generated mesh; inspect the document");
                    return;
                }
                if (mesh.getIndices().length == 0 || mesh.getPositions().length < 6) {
                    if (edits.get(position - 1) != edit) {
                        fail("Cubism generated an empty mesh and another edit is above it; inspect the document");
                        return;
                    }
                    manager.undo();
                    refresh(doc);
                    after = meshCounts(mesh);
                    fail("Cubism generated an empty mesh; edit was undone");
                    return;
                }
                closeRenderSessions(documentUid);
                doc.updateLastModifiedTime();
                refresh(doc);
                state = "succeeded";
                activeAutoMesh = null;
                NativeBridgeJobs.completeMesh(id);
            } catch (RuntimeException error) {
                fail(error.toString());
            }
        }

        void fail(String message) {
            if ("failed".equals(state) || "succeeded".equals(state)) return;
            error = message;
            state = "failed";
            if (activeAutoMesh == this) activeAutoMesh = null;
            NativeBridgeJobs.completeMesh(id);
        }
    }

    private static Map<String,Object> meshCounts(CArtMeshSource mesh) {
        return map("vertices", mesh.getPositions().length / 2, "triangles", mesh.getIndices().length / 3);
    }

    static List<CGlueSource> glues(CModelSource source) {
        return source.getAffecterSourceSet().getSources().stream()
            .filter(CGlueSource.class::isInstance).map(CGlueSource.class::cast).toList();
    }

    private static CGlueSource findGlue(CModelSource source, String guid) {
        return glues(source).stream().filter(glue -> guid.equals(glue.getGuid().getUuidString()))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Glue not found: " + guid));
    }

    private record GluePair(long a, long b, float weightA, float weightB) {}

    private static Map<Long,Integer> vertexIndex(CArtMeshSource mesh) {
        var index = new HashMap<Long,Integer>();
        var points = mesh.getEditableMesh().getPoints();
        for (int i = 0; i < points.size(); i++) index.putIfAbsent(points.get(i).getPointUid(), i);
        return index;
    }

    private static boolean hasVertex(CArtMeshSource mesh, Map<Long,Integer> index, long uid) {
        return index == null ? mesh.getEditableMesh().getPointIndexByUid(uid) >= 0 : index.containsKey(uid);
    }

    private static List<GluePair> gluePairs(Object input, CArtMeshSource a, CArtMeshSource b, boolean weighted) {
        boolean batch = ((List<?>) input).size() >= 8;
        var aVertices = batch ? vertexIndex(a) : null;
        var bVertices = batch ? vertexIndex(b) : null;
        List<GluePair> result = new ArrayList<>();
        Set<Long> usedA = new HashSet<>();
        Set<Long> usedB = new HashSet<>();
        for (var value : (List<?>) input) {
            var row = (Map<?,?>) value;
            long uidA = Long.parseLong(nonblank(row.get("a_uid"), "a_uid"));
            long uidB = Long.parseLong(nonblank(row.get("b_uid"), "b_uid"));
            if (!hasVertex(a, aVertices, uidA) || !hasVertex(b, bVertices, uidB))
                throw new IllegalArgumentException("Glue vertex UID not found in the specified ArtMesh");
            if (!usedA.add(uidA) || !usedB.add(uidB))
                throw new IllegalArgumentException("Glue vertex pairs must be one-to-one");
            result.add(new GluePair(uidA, uidB,
                weighted ? bounded(row.get("weight_a"), "weight_a", 0, 1) : 0.5f,
                weighted ? bounded(row.get("weight_b"), "weight_b", 0, 1) : 0.5f));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("At least one vertex pair is required");
        return result;
    }

    private static Object createGlue(CModelingDocument doc, Map<String,Object> request) {
        var source = doc.getModelSource();
        var a = requireMesh(source, (String) request.get("mesh_a_guid"));
        var b = requireMesh(source, (String) request.get("mesh_b_guid"));
        if (a == b) throw new IllegalArgumentException("Glue requires two different ArtMeshes");
        if (glues(source).stream().anyMatch(glue -> glue.isTarget(a, b)))
            throw new IllegalArgumentException("These two ArtMeshes already have a Glue");
        var pairs = gluePairs(request.get("pairs"), a, b, true);
        var parent = a.getParent() != null && a.getParent() == b.getParent() ? a.getParent() : source.getRootPart();
        var canvas = source.getCanvas();
        var undo = new GroupUndo("Native bridge: create Glue");
        CGlueSource glue;
        try {
            glue = com.live2d.cubism.doc.model.affecter.glue.z.a.a(source, a, b,
                new GVector2(canvas.getPixelWidth() / 2f, canvas.getPixelHeight() / 2f), parent, -1, undo);
            undo.plusAssign(glue.getHandler().createUndo_forAllEdit("Native bridge: bind Glue vertices"));
            for (var pair : pairs)
                glue.addBindVertex_forMeshEditor(a, pair.a, pair.weightA, b, pair.b, pair.weightB);
            glue.setUpdated();
            if (glue.getBindVertexUids().length != pairs.size() * 2)
                throw new IllegalStateException("Cubism did not bind all requested vertex pairs");
        } catch (RuntimeException error) {
            undo.undo();
            refresh(doc);
            throw error;
        }
        finishEdit(doc, undo);
        return glueInfo(glue);
    }

    private static Object addGluePairs(CModelingDocument doc, Map<String,Object> request) {
        var glue = findGlue(doc.getModelSource(), (String) request.get("glue_guid"));
        var a = glue.getTargetArtMeshA();
        var b = glue.getTargetArtMeshB();
        var pairs = gluePairs(request.get("pairs"), a, b, true);
        long[] existing = glue.getBindVertexUids();
        Set<Long> usedA = new HashSet<>();
        Set<Long> usedB = new HashSet<>();
        for (int offset = 0; offset + 1 < existing.length; offset += 2) {
            usedA.add(existing[offset]);
            usedB.add(existing[offset + 1]);
        }
        for (var pair : pairs) {
            if (usedA.contains(pair.a) || usedB.contains(pair.b))
                throw new IllegalArgumentException("Glue vertex is already paired on one side");
        }
        var undo = glue.getHandler().createUndo_forAllEdit("Native bridge: add Glue pairs");
        try {
            for (var pair : pairs)
                glue.addBindVertex_forMeshEditor(a, pair.a, pair.weightA, b, pair.b, pair.weightB);
            glue.setUpdated();
            if (glue.getBindVertexUids().length != existing.length + pairs.size() * 2)
                throw new IllegalStateException("Cubism did not bind all requested vertex pairs");
        } catch (RuntimeException error) {
            undo.undo();
            refresh(doc);
            throw error;
        }
        finishEdit(doc, undo);
        return glueInfo(glue);
    }

    private record GlueSearch(float[] a, float[] b, long[] aUids, long[] bUids,
                              double tolerance, int limit, boolean limited) {}

    private static GlueSearch prepareGlueSearch(CModelSource source, Map<String,Object> request) {
        var a = requireMesh(source, (String) request.get("mesh_a_guid"));
        var b = requireMesh(source, (String) request.get("mesh_b_guid"));
        if (a == b) throw new IllegalArgumentException("Two different ArtMeshes are required");
        double tolerance = finite(request.get("tolerance"), "tolerance");
        if (tolerance <= 0) throw new IllegalArgumentException("Tolerance must be positive");
        CModel model = source.createModelInstance(com.live2d.graphics3d.rendering.RenderType.NORMAL);
        try {
            for (var parameter : source.getAllParameters())
                model.getParameterSet().setValue(parameter.getGuid(), parameter.getDefaultValue());
            var parameters = (Map<?,?>) request.get("parameters");
            for (var entry : parameters.entrySet()) {
                var parameter = findParameter(source, (String) entry.getKey());
                float value = finite(entry.getValue(), parameter.getId().toString());
                checkParameterValue(parameter, value);
                model.getParameterSet().setValue(parameter.getGuid(), value);
            }
            com.live2d.cubism.view.ay.a.a(model, new com.live2d.cubism.doc.model.ax(null), null, null, null, false);
            float[] ap = positions(model.getObject(a.getGuid()).getCalculatedForm());
            float[] bp = positions(model.getObject(b.getGuid()).getCalculatedForm());
            var au = a.getEditableMesh().getPoints();
            var bu = b.getEditableMesh().getPoints();
            if (ap.length != au.size() * 2 || bp.length != bu.size() * 2)
                throw new IllegalStateException("Canvas and editable vertex counts differ");
            int limit = integer(request, "max_candidates", Integer.MAX_VALUE);
            if (limit < 1) throw new IllegalArgumentException("max_candidates must be positive");
            var aUids = new long[au.size()];
            var bUids = new long[bu.size()];
            for (int i = 0; i < aUids.length; i++) aUids[i] = au.get(i).getPointUid();
            for (int i = 0; i < bUids.length; i++) bUids[i] = bu.get(i).getPointUid();
            return new GlueSearch(ap, bp, aUids, bUids, tolerance, limit, request.get("max_candidates") != null);
        } finally {
            model.disposeAll();
        }
    }

    private static Object suggestGluePairs(GlueSearch search) {
        var matches = NativeBridgeGlueSearch.find(search.a, search.b, search.tolerance, search.limit);
        List<Object> pairs = new ArrayList<>();
        List<Object> ambiguous = new ArrayList<>();
        for (var match : matches.candidates()) {
            var result = map("a_uid", Long.toString(search.aUids[match.a()]),
                "b_uid", Long.toString(search.bUids[match.b()]), "distance", match.distance());
            (matches.unique(match) ? pairs : ambiguous).add(result);
        }
        var result = map("pairs", pairs, "ambiguous", ambiguous);
        if (search.limited) {
            long count = Arrays.stream(matches.aCounts()).asLongStream().sum();
            result.put("candidate_count", count);
            result.put("truncated", count > search.limit);
        }
        return result;
    }

    private static Object editGlue(CModelingDocument doc, Map<String,Object> request, String operation) {
        var glue = findGlue(doc.getModelSource(), (String) request.get("glue_guid"));
        if ("set_glue_intensity".equals(operation)) {
            String formGuid = (String) request.get("form_guid");
            var form = formGuid == null ? glue.getDefaultKeyForm() : (CGlueForm) findForm(glue, formGuid);
            float intensity = bounded(request.get("intensity"), "intensity", CGlueForm.INTENSITY_MIN, CGlueForm.INTENSITY_MAX);
            var undo = glue.getHandler().createUndo_forKeyformEdit("Native bridge: set Glue intensity");
            form.setIntensity(intensity);
            form.setUpdated();
            finishEdit(doc, undo);
            return glueInfo(glue);
        }

        boolean weighted = "update_glue_weights".equals(operation);
        var a = glue.getTargetArtMeshA();
        var b = glue.getTargetArtMeshB();
        var pairs = gluePairs(request.get("pairs"), a, b, weighted);
        int[] offsets = new int[pairs.size()];
        long[] vertices = glue.getBindVertexUids();
        var pairOffsets = new HashMap<List<Long>,Integer>();
        for (int i = 0; i + 1 < vertices.length; i += 2)
            pairOffsets.put(List.of(vertices[i], vertices[i + 1]), i);
        for (int i = 0; i < pairs.size(); i++) {
            var pair = pairs.get(i);
            offsets[i] = pairOffsets.getOrDefault(List.of(pair.a, pair.b), -1);
            if (offsets[i] < 0) throw new IllegalArgumentException("Vertex pair does not belong to this Glue");
        }
        var undo = new GroupUndo("Native bridge: " + operation);
        undo.plusAssign(glue.getHandler().createUndo_forAllEdit("Native bridge: edit Glue pairs"));
        try {
            if (weighted) {
                float[] weights = glue.getWeights().clone();
                for (int i = 0; i < offsets.length; i++) {
                    weights[offsets[i]] = pairs.get(i).weightA;
                    weights[offsets[i] + 1] = pairs.get(i).weightB;
                }
                glue.setWeights(weights);
            } else {
                for (var pair : pairs) glue.removeBindVertex_forMeshEditor(a, pair.a, b, pair.b);
                if (glue.isEmpty()) undo.plusAssign(glue.getParent().getHandler().removePartChild(glue));
            }
            glue.setUpdated();
        } catch (RuntimeException error) {
            undo.undo();
            refresh(doc);
            throw error;
        }
        finishEdit(doc, undo);
        return glue.isEmpty() ? map("guid", glue.getGuid().getUuidString(), "removed", true) : glueInfo(glue);
    }

    private static Map<String,Object> glueInfo(CGlueSource glue) {
        return glueInfo(glue, true);
    }

    private static Map<String,Object> glueInfo(CGlueSource glue, boolean includePairs) {
        List<Object> pairs = new ArrayList<>();
        long[] vertices = glue.getBindVertexUids();
        float[] weights = glue.getWeights();
        for (int offset = 0; includePairs && offset + 1 < vertices.length; offset += 2) {
            pairs.add(map("a_uid", Long.toString(vertices[offset]), "b_uid", Long.toString(vertices[offset + 1]),
                "weight_a", weights[offset], "weight_b", weights[offset + 1]));
        }
        List<Object> forms = glue.getKeyforms().stream().<Object>map(form -> map(
            "guid", form.getGuid().getUuidString(), "keys", formKeys(glue, form), "intensity", form.getIntensity())).toList();
        var result = map("guid", glue.getGuid().getUuidString(), "id", glue.getId().toString(), "name", glue.getLocalName(),
            "mesh_a_guid", glue.getTargetArtMeshA_guid().getUuidString(), "mesh_b_guid", glue.getTargetArtMeshB_guid().getUuidString(),
            "forms", forms, "default_form_guid", glue.getDefaultKeyForm().getGuid().getUuidString());
        if (includePairs) result.put("pairs", pairs);
        else result.put("pair_count", vertices.length / 2);
        return result;
    }

    private static ACParameterControllableSource findKeyformObject(CModelSource source, String guid) {
        for (var glue : glues(source)) if (guid.equals(glue.getGuid().getUuidString())) return glue;
        return findObject(source, guid);
    }

    private static Map<String,Object> keyformObjectInfo(ACParameterControllableSource item) {
        return item instanceof CGlueSource glue ? glueInfo(glue) : objectInfo(item, true);
    }

    private static Map<String,Object> deformer(ACDeformerSource item) {
        Map<String,Object> result = map("id", item.getId().toString(), "name", item.getLocalName(),
            "guid", item.getGuid().getUuidString(), "parent_guid", item.getTargetDeformerGuid().getUuidString(),
            "type", item.getClass().getSimpleName(), "keyforms", item.getKeyforms().size());
        if (item instanceof CWarpDeformerSource warp) {
            result.put("columns", warp.getCol()); result.put("rows", warp.getRow());
            List<Float> positions = new ArrayList<>();
            for (float value : warp.getDefaultKeyForm().getPositions()) positions.add(value);
            result.put("default_positions", positions);
        }
        if (item instanceof CRotationDeformerSource rotation) result.put("default_rotation",rotationProperties(rotation.getDefaultKeyForm()));
        return result;
    }

    static ACParameterControllableSource findObject(CModelSource source,String guid) {
        for (var item : source.getDrawableSourceSet().getSources()) if (item.getGuid().getUuidString().equals(guid)) return item;
        for (var item : source.getDeformerSourceSet().getSources()) if (item.getGuid().getUuidString().equals(guid)) return item;
        for (var item : source.getPartSourceSet().getSources()) if (item.getGuid().getUuidString().equals(guid)) return item;
        throw new IllegalArgumentException("Mesh or deformer not found: " + guid);
    }

    static CParameterSource findParameter(CModelSource source,String id) {
        if (id != null && !id.isEmpty()) {
            var parameter = source.getParameterSourceSet().getIdMap().get(new com.live2d.cubism.doc.model.id.CParameterId(id));
            if (parameter != null && id.equals(parameter.getId().toString())) return parameter;
        }
        for (var p : source.getParameterSourceSet().getSources()) if (p.getId().toString().equals(id)) return p;
        throw new IllegalArgumentException("Parameter not found: " + id);
    }

    private static void checkParameterValue(CParameterSource parameter,float value) {
        if (!Float.isFinite(value) || value < parameter.getMinValue() || value > parameter.getMaxValue())
            throw new IllegalArgumentException("Parameter outside defined range: " + parameter.getId());
    }

    private static Object listObjects(CModelSource source, Map<String,Object> request) {
        var sections = request.get("sections") instanceof List<?> values ? values
            : List.of("objects", "parts", "parameters", "parameter_groups");
        CPartSource partFilter = null;
        if (request.get("part_guid") instanceof String guid) {
            var part = source.getRootPart();
            partFilter = part.getGuid().getUuidString().equals(guid) ? null : requirePart(source, guid);
        }
        var types = request.get("object_types") instanceof List<?> values ? values : null;
        var parameter = request.get("parameter_id") instanceof String id ? findParameter(source, id) : null;
        var result = new LinkedHashMap<String,Object>();
        if (sections.contains("objects")) {
            int offset = integer(request, "offset", 0);
            int limit = integer(request, "limit", Integer.MAX_VALUE);
            if (offset < 0 || limit < 1) throw new IllegalArgumentException("Offset must be non-negative and limit positive");
            List<Object> page = new ArrayList<>();
            int total = 0;
            for (var items : List.of(source.getDrawableSourceSet().getSources(), source.getDeformerSourceSet().getSources())) {
                for (var item : items) {
                    if (!selectedObject(item, partFilter, types, parameter)) continue;
                    if (total++ >= offset && page.size() < limit) page.add(objectInfo(item, false));
                }
            }
            result.put("objects", page);
            if (offset != 0 || request.get("limit") != null) {
                int end = (int) Math.min(total, (long) offset + limit);
                result.put("object_page", map("total", total, "offset", offset, "next_offset", end < total ? end : null));
            }
        }
        if (sections.contains("parts")) {
            List<Object> parts = new ArrayList<>();
            for (var part : source.getPartSourceSet().getSources()) if (inPart(part, partFilter))
                parts.add(map("guid", part.getGuid().getUuidString(), "name", part.getLocalName(), "visible", part.isVisible(),
                    "visible_in_hierarchy", part.isVisibleInHierarchy(),
                    "parent_part_guid", part.getParent() == null ? null : part.getParent().getGuid().getUuidString()));
            result.put("parts", parts);
        }
        if (sections.contains("parameters")) {
            List<Object> parameters = new ArrayList<>();
            for (var p : source.getParameterSourceSet().getSources()) if (parameter == null || p == parameter)
                parameters.add(map("id", p.getId().toString(), "name", p.getName(), "description", p.getDescription(),
                    "minimum", p.getMinValue(), "maximum", p.getMaxValue(), "default", p.getDefaultValue(),
                    "group_guid", p.getParentGroupGuid().getUuidString(),
                    "current", source.getCurrentInstance().getParameterSet().get(p.getGuid()).getValue()));
            result.put("parameters", parameters);
        }
        if (sections.contains("parameter_groups")) result.put("parameter_groups",
            source.getParameterGroupSet().getGuidMap().values().stream()
                .sorted(Comparator.comparing(group -> group.getGuid().getUuidString()))
                .map(NativeBridge::parameterGroupInfo).toList());
        return result;
    }

    private static boolean inPart(ACParameterControllableSource item, CPartSource part) {
        if (part == null || item == part) return true;
        for (var parent : item.getAllParents()) if (parent == part) return true;
        return false;
    }

    private static boolean selectedObject(ACParameterControllableSource item, CPartSource part, List<?> types,
                                          CParameterSource parameter) {
        return inPart(item, part) && (types == null || types.contains(item.getClass().getSimpleName()))
            && (parameter == null || item.getKeyformGridSource().findKeyformBinding(parameter.getGuid()) != null
                || item.getKeyformMorphTargetSet().getBoundParameterGuids().contains(parameter.getGuid()));
    }

    static Map<String,Object> objectInfo(ACParameterControllableSource item, boolean geometry) {
        return objectInfo(item, geometry, null);
    }

    private static Map<String,Object> objectInfo(ACParameterControllableSource item, boolean geometry, Set<?> selectedForms) {
        var result = map("guid",item.getGuid().getUuidString(),"id",item.getId().toString(),"name",item.getLocalName(),
            "type",item.getClass().getSimpleName(),"parent_guid",item.getTargetDeformerGuid().getUuidString(),
            "visible",item.isVisible(),"visible_in_hierarchy",item.isVisibleInHierarchy(),
            "parent_part_guid",item.getParent() == null ? null : item.getParent().getGuid().getUuidString(),
            "keyform_count",item.getKeyforms().size(),
            "default_form_guid",item.getDefaultKeyForm().getGuid().getUuidString());
        if (item instanceof com.live2d.cubism.doc.model.drawable.ACDrawableSource drawable) {
            List<String> clips = new ArrayList<>();
            for (var guid : drawable.getClipGuidList()) clips.add(guid.getUuidString());
            result.put("mask_guids",clips); result.put("invert_mask",drawable.getInvertClippingMask());
        }
        if (item instanceof CArtMeshSource mesh) result.put("blend_mode", mesh.getColorComposition().name());
        if (item instanceof CPartSource part) {
            result.put("blend_mode", part.getColorComposition().name());
            result.put("offscreen", part.getUseOffscreen());
            result.put("draw_order_group", part.getEnableDrawOrderGroup());
        }
        if (geometry || selectedForms != null) {
            List<Object> forms = new ArrayList<>();
            for (var form : item.getKeyforms()) {
                String guid = form.getGuid().getUuidString();
                if (selectedForms != null && !selectedForms.contains(guid)) continue;
                var data = map("guid", guid, "keys", formKeys(item, form));
                if (geometry) data.put("positions", formPositions(form));
                if (form instanceof ACDrawableForm drawable) {
                    data.put("opacity",drawable.getOpacity());
                    data.put("draw_order",drawable.getDrawOrder());
                    data.put("multiply_color",NativeBridgeModel.colorInfo(drawable.getMultiplyColor()));
                    data.put("screen_color",NativeBridgeModel.colorInfo(drawable.getScreenColor()));
                }
                if (form instanceof CRotationDeformerForm rotation) data.put("rotation",rotationProperties(rotation));
                forms.add(data);
            }
            result.put("forms",forms);
        }
        if (geometry) {
            if (item instanceof CArtMeshSource mesh) {
                result.put("indices",mesh.getIndices().clone()); result.put("uvs",mesh.getUvs().clone());
                result.put("source_positions",mesh.getPositions().clone());
                result.put("vertex_uids", mesh.getEditableMesh().getPoints().stream()
                    .map(point -> Long.toString(point.getPointUid())).toList());
            }
        }
        return result;
    }

    static List<Object> formKeys(ACParameterControllableSource item, ACForm form) {
        List<Object> keys = new ArrayList<>();
        for (KeyformOnGrid point : item.getKeyformGridSource().getKeyformOnGridFor(form.getGuid())) {
            Map<String,Object> parameters = new LinkedHashMap<>();
            for (var key : point.a().a()) parameters.put(key.getBinding().getParameterId().toString(), key.getValue());
            keys.add(parameters);
        }
        return keys;
    }

    static float[] positions(ACForm form) {
        return formCoordinates(form).clone();
    }

    private static float[] formCoordinates(ACForm form) {
        if (form instanceof CArtMeshForm mesh) return mesh.getPositions();
        if (form instanceof CWarpDeformerForm warp) return warp.getPositions();
        throw new IllegalArgumentException("Only mesh and warp vertices supported; actual=" + (form == null ? "null" : form.getClass().getName()));
    }

    static ACForm findForm(ACParameterControllableSource item,String guid) {
        for (var form : item.getKeyforms()) if (form.getGuid().getUuidString().equals(guid)) return form;
        throw new IllegalArgumentException("Keyform not found");
    }
    private static float[] formPositions(ACForm form) {
        return form instanceof CArtMeshForm || form instanceof CWarpDeformerForm ? positions(form) : null;
    }
    private static Map<String,Object> rotationProperties(CRotationDeformerForm form) {
        return map("angle",form.getAngle(),"origin_x",form.getOriginX(),"origin_y",form.getOriginY(),"scale",form.getScale());
    }

    static float[] floatArray(Object input) {
        List<?> values = (List<?>)input;
        float[] result = new float[values.size()];
        for (int i=0; i<result.length; i++) {
            result[i] = ((Number)values.get(i)).floatValue();
            if (!Float.isFinite(result[i])) throw new IllegalArgumentException("Finite coordinates required");
        }
        return result;
    }

    private static void checkPngVertex(float x, float y) {
        if (!Float.isFinite(x) || !Float.isFinite(y))
            throw new IllegalArgumentException("PNG transform produces a nonfinite vertex");
    }

    private static void commitTopology(CArtMeshSource mesh,com.live2d.graphics3d.editableMesh.GEditableMesh2 edited) throws Exception {
        // The obfuscated distribution has both a class and package named util.j.
        // Resolve this one signature at runtime; javac cannot name its progress type.
        Class<?> progressType = Class.forName("com.live2d.util.j.a");
        Object companion = progressType.getField("b").get(null);
        Object progress = companion.getClass().getMethod("a").invoke(companion);
        var method = com.live2d.cubism.view.context.action.action_meshEditor.p.class.getMethod("a",
            CArtMeshSource.class, com.live2d.graphics3d.editableMesh.GEditableMesh2.class, Map.class,
            com.live2d.doc.selection.d.class, progressType);
        try { method.invoke(com.live2d.cubism.view.context.action.action_meshEditor.p.a,mesh,edited,null,null,progress); }
        catch (java.lang.reflect.InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        }
    }

    private static final Map<String,Set<String>> BATCH_ARGUMENTS = Map.of(
        "set_visibility", Set.of("visible"), "set_clipping", Set.of("mask_guids", "invert"),
        "rename_object", Set.of("name"), "move_to_part", Set.of("part_guid", "index"),
        "set_form_positions", Set.of("form_guid", "positions"), "set_form_properties", Set.of("form_guid", "properties"),
        "add_parameter_keys", Set.of("parameter_id", "values"), "remove_parameter_keys", Set.of("parameter_id", "values"),
        "move_parameter_keys", Set.of("parameter_id", "moves"));

    private static int partMoveIndex(Map<String,Object> request, int size) {
        Object index = request.get("index");
        if (index == null) return -1;
        if (!(index instanceof Number value) || value.doubleValue() != value.intValue()
                || value.intValue() < -1 || value.intValue() > size)
            throw new IllegalArgumentException("Part child index must be an integer inside the destination or -1");
        return value.intValue();
    }

    private static final class BatchPartLayout {
        final CModelSource source;
        final CPartSource root;
        final Map<String,String> parents = new HashMap<>();
        final Map<String,List<String>> children = new TreeMap<>();

        BatchPartLayout(CModelSource source) {
            this.source = source;
            root = source.getRootPart();
            recordChildren(root);
            for (var part : source.getAllParts()) recordChildren(part);
        }

        private void recordChildren(CPartSource part) {
            String guid = part.getGuid().getUuidString();
            var list = new ArrayList<String>();
            for (var item : part.getChildren()) {
                String child = item.getGuid().getUuidString();
                list.add(child);
                parents.put(child, guid);
            }
            children.put(guid, list);
        }

        void move(Map<String,Object> args) {
            var item = findObject(source, (String) args.get("object_guid"));
            String guid = item.getGuid().getUuidString();
            Object target = args.get("part_guid");
            if (target != null && !(target instanceof String))
                throw new IllegalArgumentException("part_guid must be a string");
            var part = target == null || root.getGuid().getUuidString().equals(target)
                ? root : requirePart(source, (String) target);
            String parent = part.getGuid().getUuidString();
            if (item == root || item == part)
                throw new IllegalArgumentException("Cannot move the root part or parent an object to itself");
            var visited = new HashSet<String>();
            for (String next = parent; next != null; next = parents.get(next)) {
                if (guid.equals(next) || !visited.add(next))
                    throw new IllegalArgumentException("Batch part hierarchy would contain a cycle");
            }
            var destination = children.get(parent);
            int index = partMoveIndex(args, destination.size());
            String previous = parents.get(guid);
            if (previous != null) {
                if (parent.equals(previous) && index >= 0 && destination.indexOf(guid) < index) index--;
                children.get(previous).remove(guid);
            }
            destination.add(index == -1 ? destination.size() : index, guid);
            parents.put(guid, parent);
        }
    }

    private record NameChange(ACParameterControllableSource object, String previous, String next) {
        void apply(boolean forward) { object.setLocalName(forward ? next : previous); }
    }

    private record BatchNames(List<NameChange> changes) {
        kotlin.Unit apply(boolean forward) {
            for (var change : changes) change.apply(forward);
            return kotlin.Unit.INSTANCE;
        }
    }

    private static Map<String,ACParameterControllableSource> nameTargets(CModelSource source) {
        var targets = new HashMap<String,ACParameterControllableSource>();
        for (var items : List.of(source.getDrawableSourceSet().getSources(), source.getDeformerSourceSet().getSources(),
                                 source.getPartSourceSet().getSources())) {
            for (var item : items) targets.putIfAbsent(item.getGuid().getUuidString(), item);
        }
        return targets;
    }

    private static Object editBatch(CModelingDocument doc, Map<String,Object> request) throws Exception {
        String mode = nonblank(request.get("mode"), "mode");
        if (!Set.of("plan", "apply").contains(mode)) throw new IllegalArgumentException("Unknown batch mode");
        if (batchPreflight || batchUndo != null) throw new IllegalStateException("Another batch is active");
        var edits = (List<?>) request.get("edits");
        if (edits.isEmpty()) throw new IllegalArgumentException("At least one edit is required");
        var source = doc.getModelSource();
        boolean namesOnly = edits.stream().allMatch(edit -> "rename_object".equals(((Map<?,?>) edit).get("operation")));
        var targets = namesOnly ? nameTargets(source) : Map.<String,ACParameterControllableSource>of();
        var names = new LinkedHashMap<ACParameterControllableSource,String>();
        var operations = new ArrayList<Map<String,Object>>();
        var impact = new ArrayList<Object>();
        var snapshots = new TreeMap<String,Object>();
        var gridObjects = new HashSet<String>();
        var formObjects = new HashSet<String>();
        var bindings = new HashSet<List<String>>();
        for (var edit : edits) {
            var row = (Map<?,?>) edit;
            String operation = nonblank(row.get("operation"), "operation");
            var allowed = BATCH_ARGUMENTS.get(operation);
            if (allowed == null) throw new IllegalArgumentException("Operation is not supported in a batch: " + operation);
            var args = new TreeMap<String,Object>();
            for (var argument : ((Map<?,?>) row.get("arguments")).entrySet()) {
                String key = (String) argument.getKey();
                if (!"object_guid".equals(key) && !allowed.contains(key))
                    throw new IllegalArgumentException("Unexpected batch argument: " + key);
                args.put(key, argument.getValue());
            }
            String guid = nonblank(args.get("object_guid"), "object_guid");
            var object = namesOnly ? targets.get(guid) : findKeyformObject(source, guid);
            if (object == null) throw new IllegalArgumentException("Mesh or deformer not found: " + guid);
            if (namesOnly) names.put(object, nonblank(args.get("name"), "name"));
            if (args.containsKey("form_guid")) formObjects.add(guid);
            if (operation.endsWith("_parameter_keys")) {
                gridObjects.add(guid);
                String paramId = nonblank(args.get("parameter_id"), "parameter_id");
                if (!bindings.add(List.of(guid, paramId)))
                    throw new IllegalArgumentException("Only one grid edit per object and parameter is allowed in a batch");
            }
            snapshots.computeIfAbsent(guid, key -> keyformObjectInfo(object));
            args.put("operation", operation);
            args.put("document_uid", doc.getDocumentUID());
            operations.add(args);
            impact.add(map("operation", operation, "object_guid", guid, "name", object.getLocalName()));
        }
        if (!Collections.disjoint(gridObjects, formObjects))
            throw new IllegalArgumentException("Submit grid and keyform GUID edits for the same object in separate batches");
        var layout = operations.stream().anyMatch(args -> "move_to_part".equals(args.get("operation")))
            ? new BatchPartLayout(source) : null;
        var initialParts = new TreeMap<String,List<String>>();
        if (layout != null) layout.children.forEach((guid, order) -> initialParts.put(guid, List.copyOf(order)));
        if (!namesOnly) {
            batchPreflight = true;
            try {
                for (var args : operations) {
                    String operation = (String) args.get("operation");
                    if ("move_to_part".equals(operation)) layout.move(args);
                    else execute(operation, args);
                }
            } finally { batchPreflight = false; }
        }

        var fingerprint = map("file", doc.getFile() == null ? null : doc.getFile().toString(), "modified", doc.getLastModifiedTime(),
            "undo", doc.getUndoManager().getCurrentPos(), "operations", operations, "snapshots", snapshots,
            "part_order", initialParts);
        String plan = digestJson(fingerprint);
        if ("plan".equals(mode)) return map("plan_id", plan, "impact", impact);
        if (!plan.equals(request.get("plan_id"))) throw new IllegalArgumentException("Edit batch plan is stale; plan again");
        if (namesOnly) {
            applyBatchNames(doc, names);
            return map("plan_id", plan, "impact", impact, "document", describe(doc));
        }
        var group = new GroupUndo("Native bridge: batch edits");
        batchUndo = group;
        try {
            for (var args : operations) execute((String) args.get("operation"), args);
        } catch (Exception | Error error) {
            group.undo();
            closeRenderSessions(doc.getDocumentUID());
            refresh(doc);
            throw error;
        } finally { batchUndo = null; }
        finishEdit(doc, group);
        return map("plan_id", plan, "impact", impact, "document", describe(doc));
    }

    static void finishEdit(CModelingDocument doc,ACUndoable undo) {
        if (batchUndo != null) {
            batchUndo.plusAssign(undo);
            return;
        }
        registerEdit(doc, undo);
        refresh(doc);
    }

    private static void registerEdit(CModelingDocument doc, ACUndoable undo) {
        closeRenderSessions(doc.getDocumentUID());
        doc.getUndoManager().addEdit(undo);
        doc.updateLastModifiedTime();
    }

    private static void applyBatchNames(CModelingDocument doc, Map<ACParameterControllableSource,String> names) {
        var changes = names.entrySet().stream().map(entry ->
            new NameChange(entry.getKey(), entry.getKey().getLocalName(), entry.getValue())).toList();
        var undo = new LambdaUndo<>("Native bridge: batch edits", new BatchNames(changes),
            edit -> edit.getTargetData().apply(true), edit -> edit.getTargetData().apply(false), null);
        undo.addUndoRedoListener(event -> {
            refreshNamePalettes(doc);
            return kotlin.Unit.INSTANCE;
        });
        registerEdit(doc, undo);
        refreshNamePalettes(doc);
        doc.getCompletePack().updateTitle();
    }

    private static void refreshNamePalettes(CModelingDocument doc) {
        // A local name is a source property. Refresh the displayed labels
        // without rebuilding model instances and parameter widgets.
        doc.getCompletePack().updatePartPalette(true);
        doc.getCompletePack().updateDeformerPalette(true);
    }

    private static Object replayEdit(CModelingDocument doc, boolean redo) {
        var manager = doc.getUndoManager();
        if (redo ? !manager.canRedo() : !manager.canUndo())
            throw new IllegalStateException(redo ? "Nothing to redo" : "Nothing to undo");
        var edit = manager.getUndoList().get(manager.getCurrentPos() - (redo ? 0 : 1));
        closeRenderSessions(doc.getDocumentUID());
        if (redo) manager.redo();
        else manager.undo();
        doc.updateLastModifiedTime();
        if (edit instanceof LambdaUndo<?> lambda && lambda.getTargetData() instanceof BatchNames)
            doc.getCompletePack().updateTitle();
        else refresh(doc);
        return describe(doc);
    }

    private static void evaluateModel(CModelingDocument doc) {
        var context = doc.getLastActiveViewContext();
        CModel model = doc.getModelSource().getCurrentInstance();
        var options = new com.live2d.cubism.doc.model.ax(context);
        com.live2d.cubism.view.ay.a.a(model, options, doc, context, null, false);
        com.live2d.cubism.view.ay.a.a(model,model.getParameterSet(),model.getAllDeformers(),options,context,null);
        com.live2d.cubism.view.ay.a.b(model,model.getParameterSet(),model.getAllArtMeshes(),options,context,null);
    }

    static void refresh(CModelingDocument doc) {
        doc.getModelSource().updateModelInstances();
        doc.getCompletePack().updatePartPalette(true);
        doc.getCompletePack().updateDeformerPalette(true);
        doc.getCompletePack().updateParameter(true);
        doc.getCompletePack().updateTitle();
    }

    private static void refreshMaterialTextures(CModelSource source,
            com.live2d.cubism.doc.model.texture.modelImage.CModelImage material) {
        for (var mesh:source.getAllArtMeshes()) {
            var extension=mesh.getTextureInputExtension();var input=extension.getModelImageInput();
            if (input==null || !input.getModelImageGuid().equals(material.getGuid())) continue;
            if (!(extension.getCurrentTextureInputData() instanceof com.live2d.cubism.doc.model.extension.textureInput.CTextureInput_ModelImage))
                throw new IllegalArgumentException("Material pixel editing requires model-image texture mode");
            // Rebind the same-size material through the native cached-image
            // provider. Do not run updateArtMeshSourceUvs: authored UVs stay exact.
            var cached=input.getCachedImage(com.live2d.graphics.cachedImage.CachedImageParam.Companion.a());
            mesh.setTexture(cached.h());
        }
        source.updateModelInstances();
    }

    private static final class MaterialPixelsUndo extends ACUndoable {
        final CModelingDocument document;
        final com.live2d.cubism.doc.model.texture.modelImage.CModelImage material;
        final com.live2d.undo.SimpleUndo data;
        MaterialPixelsUndo(CModelingDocument document,com.live2d.cubism.doc.model.texture.modelImage.CModelImage material) {
            this.document=document;this.material=material;setUndoName("Native bridge: replace material pixels");
            data=new com.live2d.undo.SimpleUndo(getUndoName(),material,new com.live2d.core.a());
        }
        @Override public void undo() { closeRenderSessions(document.getDocumentUID());data.undo();refreshMaterialTextures(document.getModelSource(),material); }
        @Override public void redo() { closeRenderSessions(document.getDocumentUID());data.redo();refreshMaterialTextures(document.getModelSource(),material); }
        @Override public boolean getCanUndo() { return data.getCanUndo(); }
        @Override public boolean getCanRedo() { return data.getCanRedo(); }
        @Override public void die() { data.die(); }
    }

    static com.live2d.cubism.doc.model.param.CParameterSet pose(CModelSource source, String base, Map<?,?> values) {
        var result = source.getCurrentInstance().getParameterSet().deepCopy(new com.live2d.core.a());
        if ("default".equals(base)) result.setDefaultValues();
        else if (base != null && !"current".equals(base)) throw new IllegalArgumentException("Unknown base_pose");
        for (var entry : values.entrySet()) {
            var parameter = findParameter(source, (String) entry.getKey());
            float value = ((Number) entry.getValue()).floatValue();
            checkParameterValue(parameter, value);
            result.setValue(parameter.getGuid(), value);
        }
        return result;
    }

    private static Object evaluate(CModelSource source, Map<String,Object> request) {
        var items = ((List<?>) request.get("object_guids")).stream()
            .map(id -> findObject(source, nonblank(id, "object_guid"))).toList();
        var model = source.createModelInstance(com.live2d.graphics3d.rendering.RenderType.NORMAL);
        try {
            var base = pose(source, (String) request.get("base_pose"), Map.of());
            if (!(request.get("poses") instanceof List<?> poses))
                return evaluatePose(source, model, items, base, (Map<?,?>) request.getOrDefault("parameters", Map.of()));
            if (poses.isEmpty()) throw new IllegalArgumentException("Provide at least one pose");
            var names = new HashSet<String>();
            var results = new ArrayList<>();
            for (Object value : poses) {
                var row = (Map<?,?>) value;
                String name = nonblank(row.get("name"), "pose name");
                if (!names.add(name)) throw new IllegalArgumentException("Duplicate pose name: " + name);
                var result = evaluatePose(source, model, items, base, (Map<?,?>) row.get("parameters"));
                result.put("name", name);
                results.add(result);
            }
            return map("poses", results);
        } finally { model.disposeAll(); }
    }

    private static Map<String,Object> evaluatePose(CModelSource source, CModel model,
            List<ACParameterControllableSource> items, com.live2d.cubism.doc.model.param.CParameterSet base, Map<?,?> values) {
        var parameters = model.getParameterSet();
        parameters.setValuesById(base);
        for (var entry : values.entrySet()) {
            var parameter = findParameter(source, (String) entry.getKey());
            float value = ((Number) entry.getValue()).floatValue();
            checkParameterValue(parameter, value);
            parameters.setValue(parameter.getGuid(), value);
        }
        evaluateDetached(model);
        var objects = new ArrayList<>();
        for (var item : items) {
            var instance = model.getObject(item.getGuid());
            var result = map("guid", item.getGuid().getUuidString(), "local_positions", formPositions(instance.getInterpolatedForm()),
                "canvas_positions", formPositions(instance.getCalculatedForm()));
            if (instance.getCalculatedForm() instanceof ACDrawableForm drawable) {
                result.put("opacity", drawable.getOpacity());
                result.put("draw_order", drawable.getDrawOrder());
            }
            if (instance.getInterpolatedForm() instanceof CRotationDeformerForm rotation)
                result.put("rotation", rotationProperties(rotation));
            objects.add(result);
        }
        return map("objects", objects, "parameters", values);
    }

    private static PngFrame exportMeshTexture(CModelSource source, Map<String,Object> request, Path path) throws Exception {
        var mesh = requireMesh(source, (String) request.get("object_guid"));
        var input = mesh.getTextureInputExtension().getModelImageInput();
        var material = input == null ? null : input.getModelImageOrNull();
        if (material == null) throw new IllegalArgumentException("Embedded model-image input required");
        var resource = material.getFilteredImage();
        var result = map("path", path.toString(), "width", resource.getWidth(), "height", resource.getHeight(),
            "material_guid", material.getGuid().getUuidString(), "material_name", material.getName(),
            "image_to_source", input.getInputImageLocalToCanvasTransform().copy().getMatrix());
        if (mesh.getTexture() instanceof com.live2d.graphics3d.texture.GTexture2D texture)
            result.put("texture", map("width", texture.getWidth(), "height", texture.getHeight(),
                "image01_to_logical01", texture.getTransformImageResource01toLogical01().getMatrix()));
        return new PngFrame(copyImage(resource.getImage()), result);
    }

    private static com.live2d.graphics.CWritableImage copyImage(com.live2d.graphics.CWritableImage image) throws Exception {
        var copy = com.live2d.graphics.CWritableImage.class.getMethod("copyAs$default",
            com.live2d.graphics.CWritableImage.class, com.live2d.graphics.n.class, boolean.class, int.class, Object.class);
        return (com.live2d.graphics.CWritableImage) copy.invoke(null, image, null, false, 3, null);
    }

    private record PngFrame(com.live2d.graphics.CWritableImage image, Map<String,Object> information) {}

    private static Object renderPng(Callable<CModelingDocument> document, Map<String,Object> request,
                                    long deadline) throws Exception {
        Path path = NativeBridgeAssets.output(workspace, request.get("path"), ".png");
        int width = integer(request, "width", 1024), height = integer(request, "height", 2011);
        try (var permit = NativeBridgeRendering.reserve(width, height, 0, false)) {
            // Wait for the in-progress readback to return ownership of its pixels.
            var frame = onEditorThread(() -> {
                if (deadline != 0 && System.nanoTime() >= deadline)
                    throw new IllegalStateException("Editor operation timed out before it started");
                var doc = document.call();
                var source = doc.getModelSource();
                var values = (Map<?,?>) request.getOrDefault("parameters", Map.of());
                var pose = pose(source, (String) request.get("base_pose"), values);
                pruneRenderSessions(CEAppCtrl.Companion.b());
                String key = doc.getDocumentUID() + ":" + width + "x" + height;
                var cached = renderSessions.get(key);
                boolean reused = !permit.large && cached != null;
                NativeRenderSession renderer;
                if (permit.large) {
                    closeRenderSessions(null);
                    renderer = new NativeRenderSession(doc.getDocumentUID(), source, width, height, pose);
                } else if (reused) {
                    renderer = cached.renderer;
                } else {
                    while (renderSessions.size() >= 2) {
                        String first = renderSessions.keySet().iterator().next();
                        renderSessions.remove(first).close();
                    }
                    renderer = new NativeRenderSession(doc.getDocumentUID(), source, width, height, pose);
                    renderSessions.put(key, new CachedRender(doc, renderer));
                }
                try {
                    var image = renderer.render(pose);
                    if (image == null) throw new IllegalStateException("Cubism offscreen renderer returned no image");
                    return new PngFrame(image, map("path", path.toString(), "width", width, "height", height,
                        "parameters", values, "renderer_reused", reused, "renderer_frames", renderer.frames,
                        "live_renderers", renderSessions.size()));
                } finally {
                    if (permit.large) renderer.close();
                }
            }, 0);
            try {
                NativeBridgeAssets.publishFile(workspace, path, ".png", temporary -> frame.image.writeImage(temporary.toFile()));
                frame.information.put("bytes", Files.size(path));
                return frame.information;
            } finally { frame.image.flush(); }
        }
    }

    static void evaluateDetached(CModel model) {
        com.live2d.cubism.view.ay.a.a(model, new com.live2d.cubism.doc.model.ax(null), null, null, null, false);
    }

    static final class NativeRenderSession implements AutoCloseable {
        final String documentUid;
        final CModel model;
        final com.live2d.graphics3d.view.glWrapper.a wrapper;
        final int width,height,paddedWidth,paddedHeight;
        com.live2d.graphics.CWritableImage capture;
        int frames;

        @SuppressWarnings("unchecked")
        NativeRenderSession(String uid,CModelSource source,int width,int height,
                            com.live2d.cubism.doc.model.param.CParameterSet pose) throws Exception {
            documentUid=uid;this.width=width;this.height=height;
            paddedWidth=(width+15)/16*16;paddedHeight=(height+15)/16*16;
            model=source.createModelInstance(com.live2d.graphics3d.rendering.RenderType.NORMAL);
            model.getParameterSet().reinit(pose,com.live2d.core.a.a.b());
            com.live2d.cubism.view.ay.a.a(model,new com.live2d.cubism.doc.model.ax(null),null,null,null,false);
            var capabilities=com.live2d.graphics3d.shader.A.a.c();
            wrapper=new com.live2d.graphics3d.view.glWrapper.a(paddedWidth,paddedHeight,
                capabilities,new com.live2d.type.GlWrapperGuid(),com.live2d.graphics3d.shader.A.a.a(capabilities),com.live2d.graphics.n.c);
            try {
                // Exact callback used by CModelSource.getRenderedImage, with the
                // same native projection, clipping, colors and draw pipeline.
                var ctor=Class.forName("com.live2d.cubism.doc.model.V").getDeclaredConstructor(
                    CModel.class,int.class,int.class,com.live2d.type.CColor.class);
                ctor.setAccessible(true);
                var callback=(kotlin.jvm.functions.Function1<com.live2d.graphics3d.a,kotlin.Unit>)
                    ctor.newInstance(model,width,height,new com.live2d.type.CColor(0f,0f,0f,0f));
                wrapper.a(callback);
            } catch (Throwable error) { model.disposeAll();wrapper.c();throw error; }
        }

        com.live2d.graphics.CWritableImage render(com.live2d.cubism.doc.model.param.CParameterSet pose) throws Exception {
            model.getParameterSet().reinit(pose,com.live2d.core.a.a.b());
            com.live2d.cubism.view.ay.a.a(model,new com.live2d.cubism.doc.model.ax(null),null,null,null,false);
            wrapper.d();capture=wrapper.e();frames++;
            // The context owns capture and its IntBuffer across frames. Return
            // an independent image; flushing capture would invalidate readback.
            if (width!=paddedWidth || height!=paddedHeight)
                return capture.createSubImage(0,paddedHeight-height,width,height);
            return copyImage(capture);
        }

        public void close() {
            try { model.disposeAll(); } finally {
                try { wrapper.c(); } finally { if (capture!=null) capture.flush(); }
            }
        }
    }

    private static final class CachedRender implements AutoCloseable {
        final CModelingDocument document;
        final NativeRenderSession renderer;
        final long modified;
        final com.live2d.undo.CUndoManager undo;
        final com.live2d.undo.j listener = event -> invalid = true;
        volatile boolean invalid;

        CachedRender(CModelingDocument document, NativeRenderSession renderer) {
            this.document = document;
            this.renderer = renderer;
            modified = document.getLastModifiedTime();
            undo = document.getUndoManager();
            undo.addUndoStateChangeListener(listener);
        }

        boolean valid(CEAppCtrl app) {
            return !invalid && app.getAllModelDocs().contains(document)
                && document.getCurrentEditMode() == document.getEditMode_modeling()
                && document.getLastModifiedTime() == modified && document.getUndoManager() == undo;
        }

        public void close() {
            try { undo.removeUndoStateChangeListener(listener); }
            finally { renderer.close(); }
        }
    }

    private static void pruneRenderSessions(CEAppCtrl app) {
        var sessions = renderSessions.values().iterator();
        while (sessions.hasNext()) {
            var session = sessions.next();
            if (!session.valid(app)) {
                sessions.remove();
                session.close();
            }
        }
    }

    static void closeRenderSessions(String documentUid) {
        var entries=renderSessions.entrySet().iterator();
        while (entries.hasNext()) {
            var entry=entries.next();
            if (documentUid==null || documentUid.equals(entry.getValue().renderer.documentUid)) {
                var session=entry.getValue();entries.remove();session.close();
            }
        }
    }

    static int integer(Map<String,Object> request, String key, int fallback) {
        Object value = request.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue())
            throw new IllegalArgumentException(key + " must be a 32-bit integer");
        return number.intValue();
    }
    private static int canvasDimension(Map<String,Object> request, String key, int minimum) {
        if (!(request.get(key) instanceof Number value)) {
            throw new IllegalArgumentException("Canvas " + key + " in pixels is required");
        }
        int pixels = value.intValue();
        if (value.doubleValue() != pixels || pixels < minimum || pixels > CModelSource.MAX_CANVAS_SIZE) {
            throw new IllegalArgumentException("Canvas " + key + " must be an integer from "
                + minimum + " to " + CModelSource.MAX_CANVAS_SIZE);
        }
        return pixels;
    }
    private static float number(Map<String,Object> request, String key) {
        float result = ((Number) request.get(key)).floatValue();
        if (!Float.isFinite(result)) throw new IllegalArgumentException("Finite coordinates required");
        return result;
    }
    static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i=0; i<pairs.length; i+=2) result.put((String) pairs[i], pairs[i+1]);
        return result;
    }

    private static void writeJson(Object value, Writer writer) throws IOException {
        if (value instanceof Map<?,?> values) {
            writer.write('{');
            boolean comma = false;
            for (var entry : values.entrySet()) {
                if (comma) writer.write(',');
                JSONValue.writeJSONString(String.valueOf(entry.getKey()), writer);
                writer.write(':');
                writeJson(entry.getValue(), writer);
                comma = true;
            }
            writer.write('}');
        } else if (value instanceof Iterable<?> values) {
            writer.write('[');
            boolean comma = false;
            for (var item : values) {
                if (comma) writer.write(',');
                writeJson(item, writer);
                comma = true;
            }
            writer.write(']');
        } else if (value != null && value.getClass().isArray()) {
            writer.write('[');
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (i != 0) writer.write(',');
                writeJson(java.lang.reflect.Array.get(value, i), writer);
            }
            writer.write(']');
        } else {
            JSONValue.writeJSONString(value, writer);
        }
    }

    static String digestJson(Object value) throws IOException {
        java.security.MessageDigest digest;
        try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        try (var sink = new java.security.DigestOutputStream(OutputStream.nullOutputStream(), digest);
             var writer = new BufferedWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8))) {
            writeJson(value, writer);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
