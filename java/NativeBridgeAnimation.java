package cubism53.mcp;

import com.live2d.cubism.CEAppCtrl;
import com.live2d.cubism.CETargetVersion;
import com.live2d.cubism.doc.animation.CAnimation;
import com.live2d.cubism.doc.animation.CAnimationFileContent;
import com.live2d.cubism.doc.animation.CSceneDocument;
import com.live2d.cubism.doc.animation.io.q;
import com.live2d.cubism.doc.animation.movie.effect.attr.value.CCurveType;
import com.live2d.cubism.doc.animation.movie.effect.attr.value.CMutableSequence;
import com.live2d.cubism.doc.animation.movie.track.CMvTrack_Live2DModel_Source;
import com.live2d.cubism.doc.modeling.CModelingDocument;
import com.live2d.undo.SimpleUndo;
import java.nio.file.*;
import java.util.*;

import static cubism53.mcp.NativeBridge.*;

/** Native animation documents, curves and Motion3 serialization. */
final class NativeBridgeAnimation {
    private NativeBridgeAnimation() {}

    static List<CSceneDocument> scenes(CEAppCtrl app) {
        var scenes = new LinkedHashSet<CSceneDocument>();
        for (var document : app.getAllDocs()) {
            if (document instanceof CSceneDocument scene)
                scenes.addAll(scene.getAnimationContent().getSceneDocs());
        }
        for (var entry : app.getCurrentProject().getChildren()) {
            if (entry instanceof CAnimationFileContent file) scenes.addAll(file.getSceneDocs());
        }
        return List.copyOf(scenes);
    }

    static Object open(CEAppCtrl app, Path path) {
        var references = new com.live2d.cubism.doc.animation.movie.res.a();
        references.c(path.toFile());
        var animation = CAnimation.Companion.a(path.toFile(), true, references, false);
        animation.setup();
        var content = new CAnimationFileContent(app.getCompletePack(), animation);
        content.setFile(path.toFile());
        if (content.getCurrentSceneDoc() == null)
            throw new IllegalArgumentException("The CAN3 contains no scene");
        app.getCurrentProject().add(content, 0);
        return describe(content.getCurrentSceneDoc());
    }

    static CSceneDocument find(CEAppCtrl app, String uid) {
        if (uid == null) return null;
        return scenes(app).stream().filter(scene -> uid.equals(scene.getDocumentUID())).findFirst().orElse(null);
    }

    static Map<String,Object> describe(CSceneDocument doc) {
        var content = doc.getAnimationContent();
        var scene = doc.getSceneSource();
        var movie = scene.getMovieInfo();
        var tracks = scene.getAllTracks().stream().filter(CMvTrack_Live2DModel_Source.class::isInstance)
            .map(CMvTrack_Live2DModel_Source.class::cast)
            .map(track -> map("guid", track.getGuid().getUuidString(), "name", track.getName())).toList();
        return map("document_uid", doc.getDocumentUID(), "document_type", "animation_scene",
            "file", content.getFile() == null ? null : content.getFile().toString(),
            "scene_guid", scene.getGuid().getUuidString(), "name", scene.getSceneName(),
            "fps", movie.getFps(), "duration_frames", movie.getDuration(), "tracks", tracks,
            "modified", content.isModifiedAfterSaving(), "undo_position", doc.getUndoManager().getCurrentPos());
    }

    static Object create(CEAppCtrl app, Path workspace, CModelingDocument model, Map<String,Object> request) throws Exception {
        if (model.getFile() == null || model.isModifiedAfterSaving())
            throw new IllegalArgumentException("Save the linked .cmo3 before creating an animation scene");
        var file = NativeBridgeAssets.input(workspace, model.getFile().toString(), ".cmo3").toFile();
        String name = nonblank(request.get("name"), "name");
        double fps = number(request.get("fps"), "fps");
        int duration = integer(request.get("duration_frames"), "duration_frames");
        if (fps <= 0 || duration < 1) throw new IllegalArgumentException("Positive FPS and duration are required");
        String uid = (String) request.get("animation_document_uid");
        var existing = find(app, uid);
        if (uid != null && existing == null) throw new IllegalArgumentException("Animation scene document not found: " + uid);
        var animation = existing == null ? new CAnimation(name) : existing.getAnimation();
        if (existing == null) animation.setTargetVersion(CETargetVersion.Animation.FOR_SDK);
        var created = animation.createNewSceneWithModel(model.getModelSource(), file, duration, fps, name);
        if (created == null) throw new IllegalStateException("Cubism could not link the saved model");
        var scene = animation.getCurrentScene();
        if (!animation.getScenes().contains(scene)) animation.addScene(scene);
        animation.setCurrentScene(scene);
        CSceneDocument doc;
        if (existing == null) {
            animation.setup();
            var content = new CAnimationFileContent(app.getCompletePack(), animation);
            doc = content.getSceneDocs().stream().filter(candidate -> candidate.getSceneSource() == scene)
                .findFirst().orElseThrow(() -> new IllegalStateException("Cubism did not register the new animation scene"));
            app.getCurrentProject().add(content, 0);
        } else {
            var content = existing.getAnimationContent();
            doc = new CSceneDocument(existing.getCompletePack(), content, scene);
            content.getSceneDocs().add(doc);
            scene.setup(animation);
            content.setCurrentSceneDoc(doc);
            existing.getCompletePack().getUpdateManager().updateScene(content);
        }
        doc.updateLastModifiedTime();
        var result = describe(doc);
        result.put("model_track_guid", created.getSecond().getGuid().getUuidString());
        return result;
    }

    static Object execute(CEAppCtrl app, CSceneDocument doc, Path workspace, String op, Map<String,Object> request) throws Exception {
        switch (op) {
            case "describe": return describe(doc);
            case "set_timeline_keys": return setKeys(doc, request);
            case "get_timeline_keys", "remove_timeline_keys", "sample_timeline_parameters": return curves(doc, op, request);
            case "export_motion3": return exportMotion(doc, workspace, request);
            case "save_as_new": {
                var path = NativeBridgeAssets.output(workspace, request.get("path"), ".can3");
                Files.createDirectories(path.getParent());
                boolean saved = doc.getAnimationContent().saveDocument(path.toFile(), false);
                return map("saved", saved, "path", path.toString(), "bytes", saved ? Files.size(path) : 0);
            }
            case "close_saved":
                if (doc.getAnimationContent().isModifiedAfterSaving() || doc.getAnimationContent().getFile() == null)
                    throw new IllegalStateException("Save the CAN3 before closing");
                var content = doc.getAnimationContent();
                if (content.getSceneDocs().stream().anyMatch(scene -> !scene.getViewContexts().isEmpty())) {
                    app.command_close(doc);
                } else {
                    for (var scene : content.getSceneDocs()) scene.closeDocument();
                    app.getCurrentProject().remove(content);
                }
                return map("close_requested", true);
            case "undo":
                if (!doc.getUndoManager().canUndo()) throw new IllegalStateException("Nothing to undo");
                doc.getUndoManager().undo();
                doc.updateLastModifiedTime();
                return describe(doc);
            case "redo":
                if (!doc.getUndoManager().canRedo()) throw new IllegalStateException("Nothing to redo");
                doc.getUndoManager().redo();
                doc.updateLastModifiedTime();
                return describe(doc);
            default: throw new IllegalArgumentException("Operation requires a modeling document: " + op);
        }
    }

    private static CMvTrack_Live2DModel_Source track(CSceneDocument doc, Object value) {
        String guid = nonblank(value, "track_guid");
        return doc.getSceneSource().getAllTracks().stream().filter(CMvTrack_Live2DModel_Source.class::isInstance)
            .map(CMvTrack_Live2DModel_Source.class::cast).filter(track -> guid.equals(track.getGuid().getUuidString()))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Model track not found: " + guid));
    }

    private record Key(int frame, double value, CCurveType type) {}

    private static CMutableSequence curve(CMvTrack_Live2DModel_Source track, String id) {
        var parameter = track.getParameterSourceSet().getSources().stream()
            .filter(candidate -> id.equals(candidate.getId().toString())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Parameter is absent from the linked model: " + id));
        var attr = track.getKeyParam().getAttrByParamId(parameter);
        if (attr == null || !(attr.getValueData() instanceof CMutableSequence sequence))
            throw new IllegalStateException("Linked parameter has no editable native curve: " + id);
        return sequence;
    }

    private static Object curves(CSceneDocument doc, String operation, Map<String,Object> request) {
        var track = track(doc, request.get("track_guid"));
        String id = nonblank(request.get("parameter_id"), "parameter_id");
        var sequence = curve(track, id);
        var response = map("document_uid", doc.getDocumentUID(), "track_guid", track.getGuid().getUuidString(), "parameter_id", id);
        if ("get_timeline_keys".equals(operation)) {
            response.put("keys", timelineKeys(sequence));
            return response;
        }
        int duration = doc.getSceneSource().getMovieInfo().getDuration();
        var frames = new LinkedHashSet<Integer>();
        for (Object value : (List<?>) request.get("frames")) {
            int frame = integer(value, "frame");
            if (frame < 0 || frame >= duration || !frames.add(frame))
                throw new IllegalArgumentException("Frames must be distinct and inside the scene");
        }
        if (frames.isEmpty()) throw new IllegalArgumentException("Provide at least one frame");
        if ("sample_timeline_parameters".equals(operation)) {
            response.put("samples", frames.stream().map(frame -> map("frame", frame, "value", sequence.getDoubleValue(frame))).toList());
            return response;
        }
        var existing = Arrays.stream(sequence.getPoints()).map(point -> point.getPos()).toList();
        if (!existing.containsAll(frames)) throw new IllegalArgumentException("One or more timeline keys are missing");
        editCurve(doc, track, sequence, "Remove timeline keys", () -> frames.forEach(sequence::removeDoubleValueAuto));
        response.put("keys", timelineKeys(sequence));
        return response;
    }

    private static List<?> timelineKeys(CMutableSequence sequence) {
        return Arrays.stream(sequence.getPoints()).map(point -> {
            int frame = point.getPos();
            var type = sequence.getCurveType(frame);
            return map("frame", frame, "value", point.getValue(), "interpolation", type.name().toLowerCase(Locale.ROOT));
        }).toList();
    }

    private static Object setKeys(CSceneDocument doc, Map<String,Object> request) {
        var track = track(doc, request.get("track_guid"));
        String id = nonblank(request.get("parameter_id"), "parameter_id");
        var parameter = track.getParameterSourceSet().getSources().stream()
            .filter(candidate -> id.equals(candidate.getId().toString())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Parameter is absent from the linked model: " + id));
        int duration = doc.getSceneSource().getMovieInfo().getDuration();
        var keys = new TreeMap<Integer,Key>();
        for (var value : (List<?>) request.get("keys")) {
            var row = (Map<?,?>) value;
            int frame = integer(row.get("frame"), "frame");
            double number = number(row.get("value"), "value");
            if (frame < 0 || frame >= duration) throw new IllegalArgumentException("Keyframe is outside the scene");
            if (number < parameter.getMinValue() || number > parameter.getMaxValue())
                throw new IllegalArgumentException("Keyframe value is outside the parameter range");
            var type = CCurveType.valueOf(nonblank(row.get("interpolation"), "interpolation").toUpperCase(Locale.ROOT));
            if (keys.putIfAbsent(frame, new Key(frame, number, type)) != null)
                throw new IllegalArgumentException("Keyframe is duplicated: " + frame);
        }
        if (keys.isEmpty()) throw new IllegalArgumentException("At least one keyframe is required");
        var sequence = curve(track, id);
        editCurve(doc, track, sequence, "Set timeline keys", () -> {
            for (var key : keys.values()) {
                sequence.setDoubleValueAuto(key.frame, key.value, key.type, false, false);
                sequence.setCurveType(key.frame, key.type);
            }
        });
        return map("document_uid", doc.getDocumentUID(), "track_guid", track.getGuid().getUuidString(), "parameter_id", id,
            "keys", keys.values().stream().map(key -> map("frame", key.frame, "value", sequence.getDoubleValue(key.frame),
                "interpolation", key.type.name().toLowerCase(Locale.ROOT))).toList());
    }

    private static void editCurve(CSceneDocument doc, CMvTrack_Live2DModel_Source track,
                                  CMutableSequence sequence, String name, Runnable apply) {
        var effect = track.getKeyParam();
        var undo = new SimpleUndo("Native bridge: " + name, sequence, new com.live2d.core.a());
        try {
            apply.run();
            sequence.setDirty();
            effect.setDirty();
        } catch (RuntimeException error) {
            undo.undo();
            throw error;
        }
        undo.addUndoRedoListener(event -> {
            effect.setDirty();
            if (!doc.getViewContexts().isEmpty()) doc.resetAllViewContext();
            return kotlin.Unit.INSTANCE;
        });
        doc.getUndoManager().addEdit(undo);
        doc.updateLastModifiedTime();
        if (!doc.getViewContexts().isEmpty()) doc.resetAllViewContext();
    }

    private static Object exportMotion(CSceneDocument doc, Path workspace, Map<String,Object> request) throws Exception {
        Path path = NativeBridgeAssets.output(workspace, request.get("path"), ".motion3.json");
        var track = track(doc, request.get("track_guid"));
        var exporter = new q();
        var exported = exporter.a(doc, track, true, 2, 2, 1, 1, 1, false, true, true);
        if (!(exported instanceof q.b.c result)) throw new IllegalStateException("Cubism could not export motion: " + exported);
        var json = result.a();
        if (!(org.json.simple.JSONValue.parse(json) instanceof Map<?,?> root))
            throw new IllegalStateException("Cubism produced invalid Motion3 JSON");
        NativeBridgeAssets.writeNew(path, json);
        return map("path", path.toString(), "bytes", Files.size(path), "meta", root.get("Meta"));
    }

    private static int integer(Object value, String label) {
        if (!(value instanceof Number number) || number.intValue() != number.doubleValue())
            throw new IllegalArgumentException(label + " must be an integer");
        return number.intValue();
    }

    private static double number(Object value, String label) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()))
            throw new IllegalArgumentException(label + " must be finite");
        return number.doubleValue();
    }
}
