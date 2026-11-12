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
    private static final LinkedHashMap<String,NativeRenderSession> renderSessions = new LinkedHashMap<>();
    private static final Map<String,AutoMeshJob> autoMeshJobs = new HashMap<>();
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
                try {
                    connections.execute(() -> handle(connection, editorRequests));
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

    @SuppressWarnings("unchecked")
    private static void handle(Socket socket, ExecutorService editorRequests) {
        try {
            socket.setSoTimeout(10000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            Map<String,Object> request = (Map<String,Object>) JSONValue.parse(reader.readLine());
            if (request == null || !token.equals(request.get("token"))) throw new SecurityException("Unauthorized local client");
            String operation = nonblank(request.get("operation"), "operation");
            if ("job_status".equals(operation)) {
                reply(socket, map("ok", true, "result", NativeBridgeJobs.status(request)));
            } else if (Set.of("render_pose_set", "export_runtime_package").contains(operation)
                    || "job".equals(request.get("execution")) && Set.of("reimport_psd", "export_runtime_model").contains(operation)) {
                reply(socket, map("ok", true, "result",
                    NativeBridgeJobs.submit(operation, request, () -> execute(operation, request))));
            } else {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
                try {
                    editorRequests.execute(() -> handleEditorRequest(socket, operation, request, deadline));
                } catch (RejectedExecutionException error) {
                    throw new IllegalStateException("Editor request queue is busy; retry the request", error);
                }
            }
        } catch (Exception error) {
            reply(socket, errorResponse(error));
        }
    }

    private static void handleEditorRequest(Socket socket, String operation, Map<String,Object> request,
                                            long deadline) {
        Map<String,Object> response;
        try {
            Object result = "stop".equals(operation) ? stop(deadline) : onEditorThread(() -> {
                NativeBridgeJobs.checkOpen();
                return execute(operation, request);
            }, deadline);
            response = map("ok", true, "result", result);
        } catch (Exception error) {
            response = errorResponse(error);
        }
        reply(socket, response);
    }

    private static Object onEditorThread(Callable<Object> work, long deadline) throws Exception {
        if (deadline - System.nanoTime() <= 0)
            throw new IllegalStateException("Editor operation timed out before it started");
        var started = new java.util.concurrent.atomic.AtomicBoolean();
        FutureTask<Object> task = new FutureTask<>(() -> {
            if (!started.compareAndSet(false, true)) return null;
            if (deadline - System.nanoTime() <= 0)
                throw new IllegalStateException("Editor operation timed out before it started");
            return work.call();
        });
        SwingUtilities.invokeLater(task);
        try {
            return task.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            boolean wasQueued = started.compareAndSet(false, true);
            task.cancel(false);
            throw new IllegalStateException(wasQueued ? "Editor operation timed out before it started"
                : "Editor operation timed out after starting; inspect state before retrying", error);
        }
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
        } catch (Exception error) {
            NativeBridgeJobs.abortStop();
            throw error;
        }
        return map("stopped", true);
    }

    private static Map<String,Object> errorResponse(Exception error) {
        Throwable cause = error instanceof ExecutionException ? error.getCause() : error;
        return map("ok", false, "error", cause.toString());
    }

    private static void reply(Socket socket, Map<String,Object> response) {
        try (socket) {
            Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            writer.write(JSONValue.toJSONString(jsonCompatible(response)));
            writer.write("\n");
            writer.flush();
        } catch (IOException error) {
            if (!listener.isClosed()) System.err.println("Cubism53 bridge: " + error);
        }
    }

    private static Object execute(String op, Map<String,Object> request) throws Exception {
        CEAppCtrl app = CEAppCtrl.Companion.b();
        if (activeAutoMesh != null && Set.of("open_project", "create_blank_project", "close_saved",
                "import_psd", "create_animation_scene").contains(op))
            throw new IllegalStateException("Automatic mesh generation is running: " + activeAutoMesh.id);
        if ("status".equals(op)) {
            List<Object> documents = new ArrayList<>();
            for (IDocument doc : app.getAllDocs()) {
                if (doc instanceof CModelingDocument modelDoc) documents.add(describe(modelDoc));
            }
            for (var scene : NativeBridgeAnimation.scenes(app)) documents.add(NativeBridgeAnimation.describe(scene));
            var current = app.getCurrentDoc();
            return map("adapter", "internal-" + NativeBridgeBuild.VERSION + "-experimental",
                "editor_version", NativeBridgeBuild.VERSION, "editor_sha256", NativeBridgeBuild.SHA256,
                "editor_pid",ProcessHandle.current().pid(),"editor_thread", SwingUtilities.isEventDispatchThread(),
                "documents", documents, "current_document_uid", current instanceof CModelingDocument d ? d.getDocumentUID()
                    : current instanceof CSceneDocument d ? d.getDocumentUID() : null);
        }
        if ("open_project".equals(op)) {
            String requestedPath = nonblank(request.get("path"), "path");
            String suffix = requestedPath.toLowerCase(Locale.ROOT).endsWith(".can3") ? ".can3" : ".cmo3";
            Path file = NativeBridgeAssets.input(workspace, requestedPath, suffix);
            for (CModelingDocument doc : app.getAllModelDocs()) {
                if (doc.getFile() != null && doc.getFile().toPath().toRealPath().equals(file)) return describe(doc);
            }
            for (var doc : NativeBridgeAnimation.scenes(app)) {
                var path = doc.getAnimationContent().getFile();
                if (path != null && path.toPath().toRealPath().equals(file)) return NativeBridgeAnimation.describe(doc);
            }
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
        if ("import_psd".equals(op)) return NativeBridgeAssets.importPsd(app, workspace, request);
        if ("create_animation_scene".equals(op))
            return NativeBridgeAnimation.create(app, workspace, findDocument(app, (String) request.get("model_document_uid")), request);
        var scene = NativeBridgeAnimation.find(app, (String) request.get("document_uid"));
        if (scene != null) return NativeBridgeAnimation.execute(app, scene, workspace, op, request);
        CModelingDocument doc = findDocument(app, (String) request.get("document_uid"));
        CModelSource source = doc.getModelSource();
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
            case "suggest_glue_pairs": return suggestGluePairs(source, request);
            case "inspect_model_dependencies": return NativeBridgeModel.dependencies(source, request);
            case "set_form_colors": return NativeBridgeModel.setColors(doc, request);
            case "create_blend_shape": return NativeBridgeModel.createBlendShape(doc, request);
            case "replace_mesh_texture_resized": return NativeBridgeAssets.replaceResized(doc, workspace, request);
            case "edit_batch": return editBatch(doc, request);
            case "render_pose_set": return NativeBridgeRendering.renderSet(doc, workspace, request);
            case "fit_warp_to_children": return fitWarp(doc, request);
            case "plan_psd_reimport", "reimport_psd": return NativeBridgeAssets.reimportPsd(doc, workspace, request, op);
            case "edit_texture_atlas": return NativeBridgeAssets.editAtlas(doc, request);
            case "export_physics3": return NativeBridgeAssets.exportPhysics(doc, workspace, request);
            case "export_runtime_model", "export_runtime_package": return NativeBridgeAssets.exportRuntime(doc, workspace, request);
            case "preflight_runtime_export": return NativeBridgeAssets.preflightRuntime(doc, workspace, request);
            case "auto_generate_mesh": return startAutoMesh(doc, request);
            case "auto_mesh_status": {
                var job = autoMeshJobs.get(doc.getDocumentUID());
                String id = (String) request.get("job_id");
                if (job == null || (id != null && !id.equals(job.id)))
                    throw new IllegalArgumentException("Automatic mesh job not found in this document");
                return job.info();
            }
            case "list_glues": return map("glues", glues(source).stream().map(NativeBridge::glueInfo).toList());
            case "create_glue": return createGlue(doc, request);
            case "update_glue_weights", "remove_glue_pairs", "set_glue_intensity": return editGlue(doc, request, op);
            case "update_parameter": return updateParameter(doc, request);
            case "move_parameter_keys", "remove_parameter_keys": return editParameterKeys(doc, request, op);
            case "update_physics_group": return updatePhysicsGroup(doc, request);
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
                return objectInfo(item, false);
            }
            case "rename_object": {
                var item = findObject(source, (String) request.get("object_guid"));
                String name = nonblank(request.get("name"), "name");
                if (batchPreflight) return null;
                var undo = item.getHandler().createUndo_forBasicSetting("Native bridge: rename object");
                item.setLocalName(name);
                finishEdit(doc, undo);
                return objectInfo(item, false);
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
                var undo = part.getHandler().createUndo_forBasicSetting("Native bridge: set part offscreen drawing");
                part.setUseOffscreen(enabled);
                part.setEnableDrawOrderGroup(true);
                finishEdit(doc, undo);
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
                Path path = NativeBridgeAssets.input(workspace, request.get("path"), ".physics3.json");
                var settings=source.getPhysicsSettingsSourceSet();
                if (!settings.getSources().isEmpty() && !Boolean.TRUE.equals(request.get("replace")))
                    throw new IllegalArgumentException("Existing physics groups: explicit replace required");
                var input=net.arnx.jsonic.JSON.decode(Files.readString(path),
                    com.live2d.cubism.doc.gameData.importer.CPhysicsJsonLoader.PhysicsJson.class);
                if (input.getVersion()!=3 || input.getMeta()==null || input.getPhysicsSettings()==null)
                    throw new IllegalArgumentException("Physics version 3, Meta and PhysicsSettings required");
                // Same importer as the file command, without its modal error dialog.
                var loader=new com.live2d.cubism.doc.gameData.importer.CPhysicsJsonLoader();
                var undo=new com.live2d.undo.SimpleUndo("Native bridge: import physics",settings,new com.live2d.core.a());
                try {
                    var method=loader.getClass().getDeclaredMethod("a",CModelSource.class,input.getClass());
                    method.setAccessible(true);
                    if (!Boolean.TRUE.equals(method.invoke(loader,source,input)))
                        throw new IllegalArgumentException("Native physics import rejected: "+loader.a());
                    if (!loader.a().isEmpty() || !loader.b().isEmpty() || settings.containInvalidVertexIndex())
                        throw new IllegalArgumentException("Invalid physics references: "+loader.a()+" "+loader.b());
                    finishEdit(doc,undo);return physicsInfo(source);
                } catch (Throwable error) { undo.undo(); refresh(doc); throw error; }
            }
            case "simulate_physics": {
                var settings=source.getPhysicsSettingsSourceSet().deepCopy(new com.live2d.core.a());
                if (settings.getSources().isEmpty()) throw new IllegalArgumentException("No physics groups");
                var pose=source.getCurrentInstance().getParameterSet().deepCopy(new com.live2d.core.a());
                float dt=request.get("delta_seconds") instanceof Number n ? n.floatValue() : 1f/60;
                if (!Float.isFinite(dt) || dt<=0 || dt>.1f) throw new IllegalArgumentException("Physics time step must be 0..0.1 seconds");
                int warmup=integer(request,"warmup_frames",120);List<?> frames=(List<?>)request.get("frames");
                if (warmup<0 || warmup>3600 || frames==null || frames.isEmpty() || frames.size()>36000)
                    throw new IllegalArgumentException("Invalid frame count");
                var solver=new com.live2d.cubism.doc.gameData.physics.b();
                solver.a().setX(settings.getGravity().getX());solver.a().setY(settings.getGravity().getY());
                Set<String> outputs=new LinkedHashSet<>();
                for (var group:settings.getSources()) {
                    solver.a(group.getVertices(),settings.getGravity());
                    for (var output:group.getOutputs()) outputs.add(source.getParameterSourceSet().get(output.getDestination()).getId().toString());
                }
                List<Object> trace=new ArrayList<>();
                for (int frame=-warmup;frame<frames.size();frame++) {
                    pose.setDefaultValues();Map<?,?> values=frame<0 ? Map.of() : (Map<?,?>)frames.get(frame);
                    for (var entry:values.entrySet()) {
                        var p=findParameter(source,(String)entry.getKey());float value=((Number)entry.getValue()).floatValue();
                        checkParameterValue(p,value);pose.setValue(p.getGuid(),value);
                    }
                    for (var group:settings.getSources()) if (group.getEnable())
                        solver.a(group,pose,settings.getGravity(),settings.getWind(),dt,
                            ignored -> kotlin.Unit.INSTANCE,false,null,null,null);
                    if (frame>=0) {
                        Map<String,Object> valuesOut=new LinkedHashMap<>();
                        for (String id:outputs) {
                            float value=pose.get(findParameter(source,id).getGuid()).getValue();
                            if (!Float.isFinite(value)) throw new IllegalStateException("Nonfinite physics output: "+id);
                            valuesOut.put(id,value);
                        }
                        trace.add(map("frame",frame,"outputs",valuesOut));
                    }
                }
                List<Object> ranges=new ArrayList<>();
                for (var group:settings.getSources()) for (var output:group.getOutputs())
                    ranges.add(map("group",group.getName(),"parameter",source.getParameterSourceSet().get(output.getDestination()).getId().toString(),
                        "raw_minimum",output.getValueBelowMinimum(),"raw_maximum",output.getValueExceededMaximum()));
                return map("native_solver","Cubism5.3 physics.b","delta_seconds",dt,"warmup_frames",warmup,
                    "frames",trace,"output_ranges",ranges,"document_modified",false);
            }
            case "import_png": {
                Path file = NativeBridgeAssets.input(workspace, request.get("path"), ".png");
                String name = (String)request.get("name");
                if (name == null || name.isBlank()) throw new IllegalArgumentException("Image name required");
                for (var existing : source.getDrawableSourceSet().getSources())
                    if (name.equals(existing.getLocalName())) throw new IllegalArgumentException("Object name already exists");
                float[] affine = floatArray(request.get("affine"));
                if (affine.length != 6) throw new IllegalArgumentException("PNG-to-canvas affine requires a,b,c,d,tx,ty");
                var pixels = javax.imageio.ImageIO.read(file.toFile());
                if (pixels == null) throw new IllegalArgumentException("Image could not be decoded");
                // Cubism's image wrapper accepts packed ARGB, not ImageIO's ABGR.
                var packed = new java.awt.image.BufferedImage(pixels.getWidth(),pixels.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
                packed.setRGB(0,0,pixels.getWidth(),pixels.getHeight(),pixels.getRGB(0,0,pixels.getWidth(),pixels.getHeight(),null,0,pixels.getWidth()),0,pixels.getWidth());
                var undo = new com.live2d.undo.GroupUndo("Native bridge: import PNG");
                undo.plusAssign(new com.live2d.undo.SimpleUndo("Import material",source.getTextureManager(),new com.live2d.core.a()));
                try {
                    var resource = new com.live2d.graphics.CImageResource(packed,false);
                    var material = com.live2d.cubism.doc.model.texture.modelImage.CModelImage.Companion.a(
                        source,name,resource,"Native original artwork",new com.live2d.type.CModelImageGuid());
                    var meshes = source.getHandler().a(null,List.of(material),source.getRootPart(),integer(request,"draw_order",500),1f,undo);
                    var mesh = meshes.get(0); mesh.setLocalName(name);
                    for (var form : mesh.getKeyforms()) {
                        float[] positions = form.getPositions().clone();
                        for (int i=0;i<positions.length;i+=2) {
                            float x=positions[i],y=positions[i+1];
                            positions[i]=affine[0]*x+affine[2]*y+affine[4];
                            positions[i+1]=affine[1]*x+affine[3]*y+affine[5];
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
                return objectInfo(item,false);
            }
            case "set_clipping": {
                var item = findObject(source,(String)request.get("object_guid"));
                if (!(item instanceof com.live2d.cubism.doc.model.drawable.ACDrawableSource drawable))
                    throw new IllegalArgumentException("Drawable required");
                var masks = new com.live2d.type.CArrayList<com.live2d.type.CDrawableGuid>();
                for (var guid : (List<?>)request.get("mask_guids")) {
                    var mask = findObject(source,(String)guid);
                    if (!(mask instanceof com.live2d.cubism.doc.model.drawable.ACDrawableSource clipper) || mask == item)
                        throw new IllegalArgumentException("Valid drawable mask required");
                    masks.add(clipper.getGuid());
                }
                if (batchPreflight) return null;
                var undo = item.getHandler().createUndo_forBasicSetting("Native bridge: set clipping");
                drawable.setClipGuidList(masks);
                drawable.setInvertClippingMask(Boolean.TRUE.equals(request.get("invert")));
                finishEdit(doc,undo); return objectInfo(item,true);
            }
            case "objects": {
                List<Object> objects = new ArrayList<>();
                for (var item : source.getDrawableSourceSet().getSources()) objects.add(objectInfo(item, false));
                for (var item : source.getDeformerSourceSet().getSources()) objects.add(objectInfo(item, false));
                List<Object> parts = new ArrayList<>();
                for (var part : source.getPartSourceSet().getSources()) parts.add(map("guid",part.getGuid().getUuidString(),
                    "name",part.getLocalName(),"visible",part.isVisible(),"visible_in_hierarchy",part.isVisibleInHierarchy(),
                    "parent_part_guid",part.getParent() == null ? null : part.getParent().getGuid().getUuidString()));
                List<Object> parameters = new ArrayList<>();
                for (var p : source.getParameterSourceSet().getSources()) parameters.add(map("id",p.getId().toString(),
                    "name",p.getName(),"description",p.getDescription(),"minimum",p.getMinValue(),"maximum",p.getMaxValue(),"default",p.getDefaultValue(),
                    "group_guid",p.getParentGroupGuid().getUuidString(),
                    "current",source.getCurrentInstance().getParameterSet().get(p.getGuid()).getValue()));
                return map("objects", objects, "parts",parts,"parameters",parameters,
                    "parameter_groups",source.getParameterGroupSet().getGuidMap().values().stream()
                        .sorted(Comparator.comparing(group -> group.getGuid().getUuidString()))
                        .map(NativeBridge::parameterGroupInfo).toList());
            }
            case "object": return objectInfo(findObject(source, (String)request.get("object_guid")), true);
            case "replace_mesh_texture": {
                var item=findObject(source,(String)request.get("object_guid"));
                if (!(item instanceof CArtMeshSource mesh)) throw new IllegalArgumentException("ArtMesh required");
                Path path = NativeBridgeAssets.input(workspace, request.get("path"), ".png");
                var input=mesh.getTextureInputExtension().getModelImageInput();
                var material=input==null ? null : input.getModelImageOrNull();
                if (material==null) throw new IllegalArgumentException("Embedded model-image material required");
                var pixels=javax.imageio.ImageIO.read(path.toFile());
                if (pixels==null || pixels.getWidth()!=material.getWidth() || pixels.getHeight()!=material.getHeight())
                    throw new IllegalArgumentException("Replacement dimensions must match the embedded material");
                float[] before=input.getInputImageLocalToCanvasTransform().copy().getMatrix();
                var packed=new java.awt.image.BufferedImage(pixels.getWidth(),pixels.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
                packed.setRGB(0,0,pixels.getWidth(),pixels.getHeight(),pixels.getRGB(0,0,pixels.getWidth(),pixels.getHeight(),null,0,pixels.getWidth()),0,pixels.getWidth());
                var resource=new com.live2d.graphics.CImageResource(packed,false);
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
            case "export_mesh_texture": {
                var item = findObject(source,(String)request.get("object_guid"));
                if (!(item instanceof CArtMeshSource mesh)) throw new IllegalArgumentException("ArtMesh required");
                Path path = NativeBridgeAssets.output(workspace, request.get("path"), ".png");
                var input = mesh.getTextureInputExtension().getModelImageInput();
                var material = input == null ? null : input.getModelImageOrNull();
                if (material == null) throw new IllegalArgumentException("Embedded model-image input required");
                var resource = material.getFilteredImage();
                Files.createDirectories(path.getParent());
                // The material owns this resource; exporting must not flush or dispose it.
                resource.getImage().writeImage(path.toFile());
                var result = map("path",path.toString(),"width",resource.getWidth(),"height",resource.getHeight(),
                    "material_guid",material.getGuid().getUuidString(),"material_name",material.getName(),
                    "image_to_source",input.getInputImageLocalToCanvasTransform().copy().getMatrix());
                if (mesh.getTexture() instanceof com.live2d.graphics3d.texture.GTexture2D texture)
                    result.put("texture",map("width",texture.getWidth(),"height",texture.getHeight(),
                        "image01_to_logical01",texture.getTransformImageResource01toLogical01().getMatrix()));
                return result;
            }
            case "render_png": {
                Path path = NativeBridgeAssets.output(workspace, request.get("path"), ".png");
                int width = integer(request,"width",1024), height = integer(request,"height",2011);
                if (width < 1 || height < 1 || width > 8192 || height > 8192) throw new IllegalArgumentException("Image size out of range");
                Map<?,?> values = (Map<?,?>)request.getOrDefault("parameters",Map.of());
                var pose = pose(source, (String) request.get("base_pose"), values);
                String key=doc.getDocumentUID()+":"+width+"x"+height;
                boolean reused=renderSessions.containsKey(key);
                if (!reused) {
                    while (renderSessions.size()>=2) {
                        String first=renderSessions.keySet().iterator().next();renderSessions.remove(first).close();
                    }
                    renderSessions.put(key,new NativeRenderSession(doc.getDocumentUID(),source,width,height,pose));
                }
                var renderer=renderSessions.get(key);
                var image = renderer.render(pose);
                if (image == null) throw new IllegalStateException("Cubism offscreen renderer returned no image");
                try { Files.createDirectories(path.getParent()); image.writeImage(path.toFile()); }
                finally { image.flush(); }
                return map("path",path.toString(),"width",width,"height",height,"bytes",Files.size(path),"parameters",values,
                    "renderer_reused",reused,"renderer_frames",renderer.frames,"live_renderers",renderSessions.size());
            }
            case "reparent": {
                var item = findObject(source,(String)request.get("object_guid"));
                String parentGuid = (String)request.get("parent_guid");
                ACDeformerSource parent = parentGuid == null ? null : (ACDeformerSource)findObject(source,parentGuid);
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
                return keyformObjectInfo(item);
            }
            case "set_form_positions": {
                var item = findObject(source,(String)request.get("object_guid"));
                ACForm form = null;
                for (var candidate : item.getKeyforms())
                    if (candidate.getGuid().getUuidString().equals(request.get("form_guid"))) form = candidate;
                if (form == null) throw new IllegalArgumentException("Keyform not found");
                float[] values = floatArray(request.get("positions"));
                if (values.length != positions(form).length) throw new IllegalArgumentException("Vertex count must remain unchanged");
                if (batchPreflight) return null;
                ACUndoable undo = item.getHandler().createUndo_forKeyformEdit("Native bridge: edit form vertices");
                if (form instanceof CArtMeshForm mesh) mesh.setPositions(values);
                else if (form instanceof CWarpDeformerForm warp) warp.setPositions(values);
                form.setUpdated();
                finishEdit(doc,undo);
                return objectInfo(item,true);
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
                        else drawable.setDrawOrder((int)value);
                    }
                    else throw new IllegalArgumentException("Drawable or rotation form required");
                }
                form.setUpdated(); finishEdit(doc,undo);
                return objectInfo(item,true);
            }
            case "set_mesh_topology": {
                var item = findObject(source,(String)request.get("object_guid"));
                if (!(item instanceof CArtMeshSource mesh)) throw new IllegalArgumentException("ArtMesh required");
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
                return objectInfo(mesh,true);
            }
            case "evaluate": {
                CModel model = source.createModelInstance(com.live2d.graphics3d.rendering.RenderType.NORMAL);
                try {
                    Map<?,?> values = (Map<?,?>)request.getOrDefault("parameters",Map.of());
                    model.getParameterSet().setValuesById(pose(source, (String)request.get("base_pose"), values));
                    // Match the independent model used by Cubism's PNG exporter.
                    com.live2d.cubism.view.ay.a.a(model,new com.live2d.cubism.doc.model.ax(null),null,null,null,false);
                    List<Object> objects = new ArrayList<>();
                    for (Object guid : (List<?>)request.get("object_guids")) {
                        var item = findObject(source,(String)guid);
                        var instance = model.getObject(item.getGuid());
                        var result = map("guid",guid,"local_positions",formPositions(instance.getInterpolatedForm()),
                            "canvas_positions",formPositions(instance.getCalculatedForm()));
                        if (instance.getCalculatedForm() instanceof ACDrawableForm drawable) {
                            result.put("opacity",drawable.getOpacity()); result.put("draw_order",drawable.getDrawOrder());
                        }
                        if (instance.getInterpolatedForm() instanceof CRotationDeformerForm rotation) result.put("rotation",rotationProperties(rotation));
                        objects.add(result);
                    }
                    return map("objects",objects,"parameters",values);
                } finally { model.disposeAll(); }
            }
            case "create_warp": {
                String name = (String) request.get("name");
                if (name == null || name.isBlank()) throw new IllegalArgumentException("Name required");
                int columns = integer(request, "columns", 2), rows = integer(request, "rows", 2);
                int bezierColumns = integer(request, "bezier_columns", 2), bezierRows = integer(request, "bezier_rows", 2);
                if (columns < 1 || columns > 30 || rows < 1 || rows > 30 || bezierColumns < 1 || bezierRows < 1)
                    throw new IllegalArgumentException("Invalid grid subdivisions");
                for (ACDeformerSource existing : source.getDeformerSourceSet().getSources())
                    if (name.equals(existing.getLocalName())) throw new IllegalArgumentException("Deformer name already exists");
                float x = number(request, "x"), y = number(request, "y"), width = number(request, "width"), height = number(request, "height");
                if (width <= 0 || height <= 0) throw new IllegalArgumentException("Positive bounds required");
                // Same internal factory as the native command: part membership,
                // GUID, default form, interpolation grid, Bezier extension and undo.
                Pair<CWarpDeformerSource,ACUndoable> edit = com.live2d.cubism.doc.model.deformer.j.a.a(
                    source.getCurrentInstance(), name, source.getRootPart(), -1,
                    CDeformerGuid.Companion.a(), columns, rows, bezierColumns, bezierRows,
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
                var edit = com.live2d.cubism.doc.model.deformer.j.a.a(source.getCurrentInstance(),name,source.getRootPart(),-1,
                    CDeformerGuid.Companion.a(),null,0f,1f,0f,30f,length,new com.live2d.graphics3d.type.GVector2(x,y));
                finishEdit(doc,edit.getSecond());
                return deformer(edit.getFirst());
            }
            case "undo":
                if (!doc.getUndoManager().canUndo()) throw new IllegalStateException("Nothing to undo");
                closeRenderSessions(doc.getDocumentUID());
                doc.getUndoManager().undo(); doc.updateLastModifiedTime(); refresh(doc); return describe(doc);
            case "redo":
                if (!doc.getUndoManager().canRedo()) throw new IllegalStateException("Nothing to redo");
                closeRenderSessions(doc.getDocumentUID());
                doc.getUndoManager().redo(); doc.updateLastModifiedTime(); refresh(doc); return describe(doc);
            case "save_as_new": {
                Path path = NativeBridgeAssets.output(workspace, request.get("path"), ".cmo3");
                Files.createDirectories(path.getParent());
                boolean saved = NativeBridgeAssets.saveModel(doc, path);
                return map("saved", saved, "path", path.toString(), "bytes", Files.exists(path) ? Files.size(path) : 0);
            }
            case "close_saved":
                if (doc.isModifiedAfterSaving()) throw new IllegalStateException("Save or undo changes before closing");
                closeRenderSessions(doc.getDocumentUID());
                autoMeshJobs.remove(doc.getDocumentUID());
                app.command_close(doc); return map("close_requested", true);
            default: throw new IllegalArgumentException("Unsupported operation: " + op);
        }
    }

    private static CModelingDocument findDocument(CEAppCtrl app, String uid) {
        if (uid == null) throw new IllegalArgumentException("Explicit document_uid required");
        for (CModelingDocument doc : app.getAllModelDocs()) if (uid.equals(doc.getDocumentUID())) return doc;
        throw new IllegalArgumentException("Document not found: " + uid);
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
            "modified", doc.isModifiedAfterSaving(), "undo_position", doc.getUndoManager().getCurrentPos());
    }

    private static Map<String,Object> physicsInfo(CModelSource source) {
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
        for (var item : source.getAllObjects()) {
            var binding = item.getKeyformGridSource().findKeyformBinding(parameter.getGuid());
            if (binding == null) continue;
            if (binding.isRepeat() && (min != parameter.getMinValue() || max != parameter.getMaxValue()))
                throw new IllegalArgumentException("Changing this range requires relocating repeating endpoint keys on " + item.getId());
            for (float key : binding.getKeys()) {
                if (key < min || key > max)
                    throw new IllegalArgumentException("Existing key outside new range on " + item.getId());
            }
        }
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
        return keyformObjectInfo(item);
    }

    private static Object updatePhysicsGroup(CModelingDocument doc, Map<String,Object> request) {
        var source = doc.getModelSource();
        var settings = source.getPhysicsSettingsSourceSet();
        String guid = (String) request.get("group_guid");
        var group = settings.getSources().stream().filter(value -> guid.equals(value.getGuid().getUuidString()))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Physics group not found: " + guid));
        var patch = (Map<?,?>) request.get("patch");
        var undo = new SimpleUndo("Native bridge: update physics group", settings, new com.live2d.core.a());
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
        } catch (RuntimeException error) {
            undo.undo();
            refresh(doc);
            throw error;
        }
        finishEdit(doc, undo);
        return physicsInfo(source);
    }

    private static int rowIndex(Map<?,?> row, int count) {
        Object value = row.get("index");
        if (!(value instanceof Number index) || index.doubleValue() != index.intValue()
                || index.intValue() < 0 || index.intValue() >= count)
            throw new IllegalArgumentException("Physics row index is outside the group: " + value);
        return index.intValue();
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

    private static float finite(Object value, String label) {
        if (!(value instanceof Number number) || !Float.isFinite(number.floatValue()))
            throw new IllegalArgumentException(label + " must be finite");
        return number.floatValue();
    }

    private static float bounded(Object value, String label, float minimum, float maximum) {
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
        var deformerGuids = new HashSet<CDeformerGuid>();
        for (var item : selected) if (item instanceof ACDeformerSource d) deformerGuids.add(d.getGuid());
        for (boolean expanded = true; expanded;) {
            expanded = false;
            for (var item : originalObjects) {
                if (!deformerGuids.contains(item.getTargetDeformerGuid())) continue;
                if (!(item instanceof ACDeformerSource || item instanceof com.live2d.cubism.doc.model.drawable.ACDrawableSource))
                    continue;
                if (selected.add(item)) {
                    expanded = true;
                    if (item instanceof ACDeformerSource d) deformerGuids.add(d.getGuid());
                }
            }
        }
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
                for (int offset = 0; offset < vertices.length; offset += 2) {
                    int aIndex = glue.getTargetArtMeshA().getEditableMesh().getPointIndexByUid(vertices[offset]);
                    int bIndex = glue.getTargetArtMeshB().getEditableMesh().getPointIndexByUid(vertices[offset + 1]);
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

    private static CArtMeshSource requireMesh(CModelSource source, String guid) {
        var item = findObject(source, guid);
        if (!(item instanceof CArtMeshSource mesh)) throw new IllegalArgumentException("ArtMesh required");
        return mesh;
    }

    private static Object startAutoMesh(CModelingDocument doc, Map<String,Object> request) {
        var mesh = requireMesh(doc.getModelSource(), (String) request.get("object_guid"));
        if (activeAutoMesh != null) {
            if (activeAutoMesh.documentUid.equals(doc.getDocumentUID()) && activeAutoMesh.objectGuid.equals(mesh.getGuid().getUuidString()))
                return activeAutoMesh.info();
            throw new IllegalStateException("Automatic mesh generation is running: " + activeAutoMesh.id);
        }
        if (doc.getCompletePack().getCurrentDoc() != doc || doc.getCurrentEditMode() != doc.getEditMode_modeling())
            throw new IllegalStateException("Automatic mesh generation requires the target document to be active in Modeling mode");
        var linked = glues(doc.getModelSource()).stream()
            .filter(glue -> mesh.getGuid().equals(glue.getTargetArtMeshA_guid()) || mesh.getGuid().equals(glue.getTargetArtMeshB_guid()))
            .map(glue -> glue.getGuid().getUuidString()).toList();
        if (!linked.isEmpty()) throw new IllegalArgumentException("ArtMesh has Glue bindings: " + linked);
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

        var job = new AutoMeshJob(doc, mesh);
        activeAutoMesh = job;
        autoMeshJobs.put(job.documentUid, job);
        NativeBridgeJobs.registerMesh(job.id, job::info);
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
                        Runnable complete = () -> job.complete(doc, mesh);
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
        for (int y = alpha.getMinY(); y < alpha.getMinY() + alpha.getHeight(); y++) {
            for (int x = alpha.getMinX(); x < alpha.getMinX() + alpha.getWidth(); x++) {
                if (alpha.getSample(x, y, 0) >= threshold) return true;
            }
        }
        return false;
    }

    private static final class AutoMeshJob {
        final String id = UUID.randomUUID().toString();
        final String documentUid;
        final String objectGuid;
        final int undoPosition;
        final Map<String,Object> before;
        volatile String state = "queued";
        volatile Map<String,Object> after;
        volatile String error;

        AutoMeshJob(CModelingDocument doc, CArtMeshSource mesh) {
            documentUid = doc.getDocumentUID();
            objectGuid = mesh.getGuid().getUuidString();
            undoPosition = doc.getUndoManager().getCurrentPos();
            before = meshCounts(mesh);
        }

        Map<String,Object> info() {
            return map("job_id", id, "document_uid", documentUid, "object_guid", objectGuid,
                "state", state, "before", before, "after", after, "error", error);
        }

        void complete(CModelingDocument doc, CArtMeshSource mesh) {
            try {
                after = meshCounts(mesh);
                if (doc.getUndoManager().getCurrentPos() == undoPosition) {
                    fail("Cubism did not commit the generated mesh");
                    return;
                }
                if (mesh.getIndices().length == 0 || mesh.getPositions().length < 6) {
                    doc.getUndoManager().undo();
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
            } catch (RuntimeException error) {
                fail(error.toString());
            }
        }

        void fail(String message) {
            error = message;
            state = "failed";
            if (activeAutoMesh == this) activeAutoMesh = null;
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

    private static List<GluePair> gluePairs(Object input, CArtMeshSource a, CArtMeshSource b, boolean weighted) {
        List<GluePair> result = new ArrayList<>();
        Set<Long> usedA = new HashSet<>();
        Set<Long> usedB = new HashSet<>();
        for (var value : (List<?>) input) {
            var row = (Map<?,?>) value;
            long uidA = Long.parseLong(nonblank(row.get("a_uid"), "a_uid"));
            long uidB = Long.parseLong(nonblank(row.get("b_uid"), "b_uid"));
            if (a.getEditableMesh().getPointIndexByUid(uidA) < 0 || b.getEditableMesh().getPointIndexByUid(uidB) < 0)
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

    private static Object suggestGluePairs(CModelSource source, Map<String,Object> request) {
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
            var matches = NativeBridgeGlueSearch.find(ap, bp, tolerance);
            List<Object> pairs = new ArrayList<>();
            List<Object> ambiguous = new ArrayList<>();
            for (var match : matches.candidates()) {
                var result = map("a_uid", Long.toString(au.get(match.a()).getPointUid()),
                    "b_uid", Long.toString(bu.get(match.b()).getPointUid()), "distance", match.distance());
                (matches.unique(match) ? pairs : ambiguous).add(result);
            }
            return map("pairs", pairs, "ambiguous", ambiguous);
        } finally {
            model.disposeAll();
        }
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
        for (int i = 0; i < pairs.size(); i++) {
            var pair = pairs.get(i);
            offsets[i] = gluePairOffset(vertices, pair);
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

    private static int gluePairOffset(long[] vertices, GluePair pair) {
        for (int offset = 0; offset + 1 < vertices.length; offset += 2) {
            if (vertices[offset] == pair.a && vertices[offset + 1] == pair.b) return offset;
        }
        return -1;
    }

    private static Map<String,Object> glueInfo(CGlueSource glue) {
        List<Object> pairs = new ArrayList<>();
        long[] vertices = glue.getBindVertexUids();
        float[] weights = glue.getWeights();
        for (int offset = 0; offset + 1 < vertices.length; offset += 2) {
            pairs.add(map("a_uid", Long.toString(vertices[offset]), "b_uid", Long.toString(vertices[offset + 1]),
                "weight_a", weights[offset], "weight_b", weights[offset + 1]));
        }
        List<Object> forms = glue.getKeyforms().stream().<Object>map(form -> map(
            "guid", form.getGuid().getUuidString(), "keys", formKeys(glue, form), "intensity", form.getIntensity())).toList();
        return map("guid", glue.getGuid().getUuidString(), "id", glue.getId().toString(), "name", glue.getLocalName(),
            "mesh_a_guid", glue.getTargetArtMeshA_guid().getUuidString(), "mesh_b_guid", glue.getTargetArtMeshB_guid().getUuidString(),
            "pairs", pairs, "forms", forms, "default_form_guid", glue.getDefaultKeyForm().getGuid().getUuidString());
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
        for (var p : source.getParameterSourceSet().getSources()) if (p.getId().toString().equals(id)) return p;
        throw new IllegalArgumentException("Parameter not found: " + id);
    }

    private static void checkParameterValue(CParameterSource parameter,float value) {
        if (!Float.isFinite(value) || value < parameter.getMinValue() || value > parameter.getMaxValue())
            throw new IllegalArgumentException("Parameter outside defined range: " + parameter.getId());
    }

    static Map<String,Object> objectInfo(ACParameterControllableSource item,boolean geometry) {
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
        if (geometry) {
            List<Object> forms = new ArrayList<>();
            for (var form : item.getKeyforms()) {
                var data = map("guid",form.getGuid().getUuidString(),"keys",formKeys(item, form),"positions",formPositions(form));
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
            if (item instanceof CArtMeshSource mesh) {
                result.put("indices",mesh.getIndices()); result.put("uvs",mesh.getUvs());
                result.put("source_positions",mesh.getPositions());
                result.put("vertex_uids", mesh.getEditableMesh().getPoints().stream()
                    .map(point -> Long.toString(point.getPointUid())).toList());
            }
        }
        return result;
    }

    private static List<Object> formKeys(ACParameterControllableSource item, ACForm form) {
        List<Object> keys = new ArrayList<>();
        for (KeyformOnGrid point : item.getKeyformGridSource().getKeyformOnGridFor(form.getGuid())) {
            Map<String,Object> parameters = new LinkedHashMap<>();
            for (var key : point.a().a()) parameters.put(key.getBinding().getParameterId().toString(), key.getValue());
            keys.add(parameters);
        }
        return keys;
    }

    private static float[] positions(ACForm form) {
        if (form instanceof CArtMeshForm mesh) return mesh.getPositions().clone();
        if (form instanceof CWarpDeformerForm warp) return warp.getPositions().clone();
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

    private static Object editBatch(CModelingDocument doc, Map<String,Object> request) throws Exception {
        String mode = nonblank(request.get("mode"), "mode");
        if (!Set.of("plan", "apply").contains(mode)) throw new IllegalArgumentException("Unknown batch mode");
        if (batchPreflight || batchUndo != null) throw new IllegalStateException("Another batch is active");
        var edits = (List<?>) request.get("edits");
        if (edits.isEmpty()) throw new IllegalArgumentException("At least one edit is required");
        var source = doc.getModelSource();
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
            var object = findKeyformObject(source, guid);
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
        batchPreflight = true;
        try {
            for (var args : operations) {
                String operation = (String) args.get("operation");
                if ("move_to_part".equals(operation)) layout.move(args);
                else execute(operation, args);
            }
        } finally { batchPreflight = false; }

        var fingerprint = map("file", doc.getFile() == null ? null : doc.getFile().toString(), "modified", doc.getLastModifiedTime(),
            "undo", doc.getUndoManager().getCurrentPos(), "operations", operations, "snapshots", snapshots,
            "part_order", initialParts);
        byte[] serialized = JSONValue.toJSONString(jsonCompatible(fingerprint)).getBytes(StandardCharsets.UTF_8);
        String plan = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(serialized));
        if ("plan".equals(mode)) return map("plan_id", plan, "impact", impact);
        if (!plan.equals(request.get("plan_id"))) throw new IllegalArgumentException("Edit batch plan is stale; plan again");
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
        closeRenderSessions(doc.getDocumentUID());
        doc.getUndoManager().addEdit(undo);
        doc.updateLastModifiedTime();
        refresh(doc);
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
            var copy=com.live2d.graphics.CWritableImage.class.getMethod("copyAs$default",
                com.live2d.graphics.CWritableImage.class,com.live2d.graphics.n.class,boolean.class,int.class,Object.class);
            return (com.live2d.graphics.CWritableImage)copy.invoke(null,capture,null,false,3,null);
        }

        public void close() {
            try { model.disposeAll(); } finally {
                try { wrapper.c(); } finally { if (capture!=null) capture.flush(); }
            }
        }
    }

    private static void closeRenderSessions(String documentUid) {
        var entries=renderSessions.entrySet().iterator();
        while (entries.hasNext()) {
            var entry=entries.next();
            if (documentUid==null || documentUid.equals(entry.getValue().documentUid)) {
                NativeRenderSession session=entry.getValue();entries.remove();session.close();
            }
        }
    }

    static int integer(Map<String,Object> request, String key, int fallback) {
        return request.get(key) instanceof Number n ? n.intValue() : fallback;
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

    private static Object jsonCompatible(Object value) {
        if (value instanceof Map<?,?> source) {
            Map<String,Object> result = new LinkedHashMap<>();
            source.forEach((k,v) -> result.put(String.valueOf(k),jsonCompatible(v)));
            return result;
        }
        if (value instanceof Iterable<?> source) {
            List<Object> result = new ArrayList<>();
            for (Object element : source) result.add(jsonCompatible(element));
            return result;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            for (int i=0; i<java.lang.reflect.Array.getLength(value); i++)
                result.add(jsonCompatible(java.lang.reflect.Array.get(value,i)));
            return result;
        }
        return value;
    }
}
