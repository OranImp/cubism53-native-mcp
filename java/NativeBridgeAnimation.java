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
import com.live2d.undo.GroupUndo;
import com.live2d.undo.LambdaUndo;
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
        for (var doc : app.getAllDocs()) {
            if (doc instanceof CSceneDocument scene)
                for (var candidate : scene.getAnimationContent().getSceneDocs())
                    if (uid.equals(candidate.getDocumentUID())) return candidate;
        }
        for (var entry : app.getCurrentProject().getChildren()) {
            if (entry instanceof CAnimationFileContent file)
                for (var candidate : file.getSceneDocs())
                    if (uid.equals(candidate.getDocumentUID())) return candidate;
        }
        return null;
    }

    static Map<String,Object> describe(CSceneDocument doc) {
        var content = doc.getAnimationContent();
        var scene = doc.getSceneSource();
        var movie = scene.getMovieInfo();
        var tracks = scene.getAllTracks().stream().filter(CMvTrack_Live2DModel_Source.class::isInstance)
            .map(CMvTrack_Live2DModel_Source.class::cast)
            .map(NativeBridgeAnimation::trackInfo).toList();
        return map("document_uid", doc.getDocumentUID(), "document_type", "animation_scene",
            "file", content.getFile() == null ? null : content.getFile().toString(),
            "scene_guid", scene.getGuid().getUuidString(), "name", scene.getSceneName(),
            "fps", movie.getFps(), "start_frame", movie.getStartFrame(), "duration_frames", movie.getDuration(),
            "loop", movie.isLoopMotion(), "fade_in_seconds", movie.getFadeInMSec() / 1000.0,
            "fade_out_seconds", movie.getFadeOutMSec() / 1000.0, "tracks", tracks,
            "modified", content.isModifiedAfterSaving(), "undo_position", doc.getUndoManager().getCurrentPos(),
            "revision", revision(doc));
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
        checkRevision(doc, request);
        switch (op) {
            case "describe": return describe(doc);
            case "set_timeline_keys": return setKeys(doc, request);
            case "edit_animation_scene": return editScene(doc, request);
            case "edit_animation_tracks": return editTracks(doc, workspace, request);
            case "edit_animation_events": return editEvents(doc, request);
            case "batch_edit_timeline": return batchCurves(doc, request);
            case "get_timeline_keys", "remove_timeline_keys", "sample_timeline_parameters": return curves(doc, op, request);
            case "save_as_new": {
                var path = NativeBridgeAssets.output(workspace, request.get("path"), ".can3");
                return NativeBridgeAssets.saveNew(workspace, path, () -> {
                    var content = doc.getAnimationContent();
                    return NativeBridgeAssets.saved(path, content.saveDocument(path.toFile(), false),
                        content.getFile(), content.isModifiedAfterSaving(), null);
                });
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

    private static Map<String,Object> trackInfo(CMvTrack_Live2DModel_Source track) {
        return map("guid", track.getGuid().getUuidString(), "name", track.getName(),
            "start_frame", track.getStart(), "duration_frames", track.getDuration(),
            "internal_offset", track.getInternalOffset());
    }

    private static int option(Map<String,Object> request, String name, int fallback) {
        return request.get(name) == null ? fallback : integer(request.get(name), name);
    }

    private static long frameOffset(CMvTrack_Live2DModel_Source track) {
        return (long) track.getStart() - track.getInternalOffset();
    }

    private static int localFrame(CSceneDocument doc, CMvTrack_Live2DModel_Source track, int frame, boolean scene) {
        var movie = doc.getSceneSource().getMovieInfo();
        if (!scene) {
            long sceneFrame = frame + frameOffset(track);
            boolean visible = sceneFrame >= Math.max(movie.getStartFrame(), track.getStart())
                && sceneFrame < Math.min((long) movie.getStartFrame() + movie.getDuration(), (long) track.getStart() + track.getDuration());
            if (!visible && (frame < 0 || frame >= movie.getDuration()))
                throw new IllegalArgumentException("Frame is outside the scene");
            return frame;
        }
        if (frame < movie.getStartFrame() || frame >= (long) movie.getStartFrame() + movie.getDuration()
                || frame < track.getStart() || frame >= (long) track.getStart() + track.getDuration())
            throw new IllegalArgumentException("Frame is outside the visible model track");
        return Math.toIntExact(frame - frameOffset(track));
    }

    private static long displayFrame(CMvTrack_Live2DModel_Source track, int frame, boolean scene) {
        return scene ? frame + frameOffset(track) : frame;
    }

    private static Object editTracks(CSceneDocument doc, Path workspace, Map<String,Object> request) throws Exception {
        String action = nonblank(request.get("action"), "action");
        if ("inspect".equals(action)) return describe(doc);
        var scene = doc.getSceneSource();
        var movie = scene.getMovieInfo();
        boolean append = "append".equals(action);
        if (!append && !"update".equals(action)) throw new IllegalArgumentException("Unknown track action");
        var existing = append ? null : track(doc, request.get("track_guid"));
        int start = option(request, "start_frame", append ? movie.getStartFrame() : existing.getStart());
        long sceneEnd = (long) movie.getStartFrame() + movie.getDuration();
        if (start < movie.getStartFrame() || start >= sceneEnd) throw new IllegalArgumentException("Track start is outside the scene");
        int length = option(request, "duration_frames", append ? Math.toIntExact(sceneEnd - start) : existing.getDuration());
        int offset = option(request, "internal_offset", append ? 0 : existing.getInternalOffset());
        if (length < 1 || (long) start + length > sceneEnd || offset < 0 || (long) offset + length > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Track interval must be inside the scene with a non-negative offset");
        if (existing != null) {
            long end = (long) offset + length;
            var conflicts = Arrays.stream(existing.getKeyFrames()).filter(frame -> frame < offset || frame >= end).boxed().toList();
            var events = existing.getUserData().keySet().stream().filter(frame -> frame < offset || frame >= end).sorted().toList();
            if (!conflicts.isEmpty() || !events.isEmpty()) return map("applied", false, "key_frames", conflicts, "event_frames", events);
            int oldStart = existing.getStart(), oldLength = existing.getDuration(), oldOffset = existing.getInternalOffset();
            var undo = new LambdaUndo<>("Native bridge: update model track", existing,
                value -> { setInterval(existing, start, length, offset); return kotlin.Unit.INSTANCE; },
                value -> { setInterval(existing, oldStart, oldLength, oldOffset); return kotlin.Unit.INSTANCE; }, null);
            finishTrackEdit(doc, undo);
            return map("applied", true, "track", trackInfo(existing));
        }
        var file = NativeBridgeAssets.input(workspace, request.get("model_path"), ".cmo3").toFile();
        var added = CMvTrack_Live2DModel_Source.Companion.a(scene, file);
        if (added == null) throw new IllegalArgumentException("Cubism could not load the linked CMO3");
        setInterval(added, start, length, offset);
        var root = scene.getRootTrack();
        var undo = new LambdaUndo<>("Native bridge: append model track", added,
            value -> { root.addChild(added, -1, true); return kotlin.Unit.INSTANCE; },
            value -> { root.removeChild(added, true); return kotlin.Unit.INSTANCE; }, null);
        finishTrackEdit(doc, undo);
        return map("applied", true, "track", trackInfo(added));
    }

    private static void setInterval(CMvTrack_Live2DModel_Source track, int start, int length, int offset) {
        track.setStart(start);
        track.setDuration(length);
        track.setInternalOffset(offset);
    }

    private static void finishTrackEdit(CSceneDocument doc, com.live2d.undo.ACUndoable undo) {
        undo.addUndoRedoListener(event -> { refreshScene(doc); return kotlin.Unit.INSTANCE; });
        finishSceneEdit(doc, undo);
    }

    private static Object editEvents(CSceneDocument doc, Map<String,Object> request) {
        var track = track(doc, request.get("track_guid"));
        if (((List<?>) request.get("events")).isEmpty() && ((List<?>) request.get("remove_frames")).isEmpty())
            return eventInfo(track, track.getUserData());
        var before = new HashMap<>(track.getUserData());
        var after = new HashMap<>(before);
        var touched = new HashSet<Integer>();
        for (Object row : (List<?>) request.get("events")) {
            var event = (Map<?,?>) row;
            int frame = localFrame(doc, track, integer(event.get("frame"), "frame"), true);
            if (!touched.add(frame)) throw new IllegalArgumentException("Duplicate event frame");
            after.put(frame, nonblank(event.get("text"), "event text"));
        }
        for (Object value : (List<?>) request.get("remove_frames")) {
            int frame = localFrame(doc, track, integer(value, "frame"), true);
            if (!touched.add(frame) || after.remove(frame) == null)
                throw new IllegalArgumentException("Removal must name an existing, distinct event");
        }
        if (!before.equals(after)) {
            var undo = new LambdaUndo<>("Native bridge: edit animation events", track,
                value -> { track.getUserData().clear(); track.getUserData().putAll(after); return kotlin.Unit.INSTANCE; },
                value -> { track.getUserData().clear(); track.getUserData().putAll(before); return kotlin.Unit.INSTANCE; }, null);
            finishTrackEdit(doc, undo);
        }
        return eventInfo(track, after);
    }

    private static Object eventInfo(CMvTrack_Live2DModel_Source track, Map<Integer,String> events) {
        return map("track_guid", track.getGuid().getUuidString(), "events", events.entrySet().stream()
            .sorted(Map.Entry.comparingByKey()).map(entry -> map("frame", displayFrame(track, entry.getKey(), true), "text", entry.getValue())).toList());
    }

    private record Key(int frame, double value, CCurveType type) {}

    private static Object editScene(CSceneDocument doc, Map<String,Object> request) {
        var scene = doc.getSceneSource();
        var movie = scene.getMovieInfo();
        double fps = request.containsKey("fps") ? number(request.get("fps"), "fps") : movie.getFps();
        int duration = request.containsKey("duration_frames")
            ? integer(request.get("duration_frames"), "duration_frames") : movie.getDuration();
        int fadeIn = request.containsKey("fade_in_seconds") ? fadeMillis(request.get("fade_in_seconds")) : movie.getFadeInMSec();
        int fadeOut = request.containsKey("fade_out_seconds") ? fadeMillis(request.get("fade_out_seconds")) : movie.getFadeOutMSec();
        boolean loop = (boolean) request.getOrDefault("loop", movie.isLoopMotion());
        if (fps <= 0 || duration < 1) throw new IllegalArgumentException("FPS and duration must be positive");
        if (duration < movie.getDuration()) {
            int start = movie.getStartFrame();
            long end = (long) start + duration;
            var conflicts = new ArrayList<Object>();
            for (var track : scene.getAllTracks()) {
                String guid = track.getGuid().getUuidString();
                long startOffset = (long) track.getStart() - track.getInternalOffset();
                if (track != scene.getRootTrack()) {
                    long trackEnd = (long) track.getStart() + track.getDuration();
                    if (trackEnd > end || Arrays.stream(track.getKeyFrames()).anyMatch(frame -> startOffset + frame >= end))
                        conflicts.add(map("track_guid", guid, "name", track.getName(), "end_frame", trackEnd));
                }
                track.getUserData().keySet().stream().map(frame -> startOffset + frame).filter(frame -> frame >= end).sorted()
                    .forEach(frame -> conflicts.add(map("track_guid", guid, "event_frame", frame)));
            }
            scene.getMarker().keySet().stream().filter(frame -> frame >= end).sorted()
                .forEach(frame -> conflicts.add(map("marker_frame", frame)));
            scene.getOnionSkinMarker().stream().filter(frame -> frame >= end).sorted()
                .forEach(frame -> conflicts.add(map("onion_skin_marker_frame", frame)));
            if (!conflicts.isEmpty()) return map("applied", false, "conflicts", conflicts);
        }
        var undo = new SimpleUndo("Native bridge: edit animation scene", movie, new com.live2d.core.a());
        try {
            movie.setFps(fps);
            movie.setDuration(duration);
            movie.setLoopMotion(loop);
            movie.setFadeInMSec(fadeIn);
            movie.setFadeOutMSec(fadeOut);
        } catch (RuntimeException | Error error) {
            undo.undo();
            throw error;
        }
        undo.addUndoRedoListener(event -> {
            refreshScene(doc);
            return kotlin.Unit.INSTANCE;
        });
        finishSceneEdit(doc, undo);
        return map("applied", true, "fps", movie.getFps(), "duration_frames", movie.getDuration(), "loop", movie.isLoopMotion(),
            "fade_in_seconds", movie.getFadeInMSec() / 1000.0, "fade_out_seconds", movie.getFadeOutMSec() / 1000.0);
    }

    private static int fadeMillis(Object value) {
        double seconds = number(value, "fade time");
        if (seconds < 0 || seconds * 1000 > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Fade time is outside the supported range");
        return (int) Math.round(seconds * 1000);
    }

    private static com.live2d.cubism.doc.model.param.CParameterSource trackParameter(CMvTrack_Live2DModel_Source track, String id) {
        var sources = track.getParameterSourceSet();
        var parameter = sources.getIdMap().get(new com.live2d.cubism.doc.model.id.CParameterId(id));
        if (parameter != null && id.equals(parameter.getId().toString())) return parameter;
        return sources.getSources().stream()
            .filter(candidate -> id.equals(candidate.getId().toString())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Parameter is absent from the linked model: " + id));
    }

    private static CMutableSequence curve(CMvTrack_Live2DModel_Source track,
                                          com.live2d.cubism.doc.model.param.CParameterSource parameter) {
        var attr = track.getKeyParam().getAttrByParamId(parameter);
        if (attr == null || !(attr.getValueData() instanceof CMutableSequence sequence))
            throw new IllegalStateException("Linked parameter has no editable native curve: " + parameter.getId());
        return sequence;
    }

    private static Object curves(CSceneDocument doc, String operation, Map<String,Object> request) {
        var track = track(doc, request.get("track_guid"));
        String id = nonblank(request.get("parameter_id"), "parameter_id");
        var sequence = curve(track, trackParameter(track, id));
        var response = map("document_uid", doc.getDocumentUID(), "track_guid", track.getGuid().getUuidString(), "parameter_id", id);
        boolean scene = "scene".equals(request.get("frame_space"));
        if ("get_timeline_keys".equals(operation)) {
            int start = option(request, "start_frame", Integer.MIN_VALUE);
            int end = option(request, "end_frame", Integer.MAX_VALUE);
            if (end < start) throw new IllegalArgumentException("End frame precedes start frame");
            var movie = doc.getSceneSource().getMovieInfo();
            long sceneStart = Math.max(movie.getStartFrame(), track.getStart());
            long sceneEnd = Math.min((long) movie.getStartFrame() + movie.getDuration(), (long) track.getStart() + track.getDuration());
            response.put("keys", timelineKeys(sequence, track, scene,
                scene ? Math.max(start, sceneStart) : start, scene ? Math.min(end, sceneEnd - 1) : end));
            return response;
        }
        boolean sample = "sample_timeline_parameters".equals(operation);
        var frames = new LinkedHashSet<Integer>();
        for (Object value : (List<?>) request.get("frames")) {
            int requested = integer(value, "frame");
            int frame = scene || sample ? localFrame(doc, track, requested, scene) : requested;
            if (!frames.add(frame)) throw new IllegalArgumentException("Frames must be distinct");
        }
        if (frames.isEmpty()) throw new IllegalArgumentException("Provide at least one frame");
        if (sample) {
            response.put("samples", frames.stream().map(frame -> map("frame", displayFrame(track, frame, scene), "value", sequence.getDoubleValue(frame))).toList());
            return response;
        }
        var existing = new HashSet<Integer>();
        for (var point : sequence.getPoints()) existing.add(point.getPos());
        if (!existing.containsAll(frames)) throw new IllegalArgumentException("One or more timeline keys are missing");
        editCurve(doc, track, sequence, "Remove timeline keys", () -> frames.forEach(sequence::removeDoubleValueAuto));
        response.put("keys", timelineKeys(sequence, track, scene, Long.MIN_VALUE, Long.MAX_VALUE));
        return response;
    }

    private static List<?> timelineKeys(CMutableSequence sequence, CMvTrack_Live2DModel_Source track,
                                        boolean scene, long start, long end) {
        return Arrays.stream(sequence.getPoints()).filter(point -> {
            long frame = displayFrame(track, point.getPos(), scene);
            return frame >= start && frame <= end;
        }).map(point -> {
            int frame = point.getPos();
            var type = sequence.getCurveType(frame);
            return map("frame", displayFrame(track, frame, scene), "value", point.getValue(), "interpolation", type.name().toLowerCase(Locale.ROOT));
        }).toList();
    }

    private static Object setKeys(CSceneDocument doc, Map<String,Object> request) {
        var track = track(doc, request.get("track_guid"));
        String id = nonblank(request.get("parameter_id"), "parameter_id");
        boolean scene = "scene".equals(request.get("frame_space"));
        var parameter = trackParameter(track, id);
        var keys = parseKeys(doc, track, parameter, (List<?>) request.get("keys"), scene);
        if (keys.isEmpty()) throw new IllegalArgumentException("At least one keyframe is required");
        var sequence = curve(track, parameter);
        editCurve(doc, track, sequence, "Set timeline keys", () -> applyKeys(sequence, keys.values()));
        return map("document_uid", doc.getDocumentUID(), "track_guid", track.getGuid().getUuidString(), "parameter_id", id,
            "keys", keys.values().stream().map(key -> map("frame", displayFrame(track, key.frame, scene), "value", sequence.getDoubleValue(key.frame),
                "interpolation", key.type.name().toLowerCase(Locale.ROOT))).toList());
    }

    private static Map<Integer,Key> parseKeys(CSceneDocument doc, CMvTrack_Live2DModel_Source track,
                                              com.live2d.cubism.doc.model.param.CParameterSource parameter, List<?> rows, boolean scene) {
        var keys = new TreeMap<Integer,Key>();
        for (var value : rows) {
            var row = (Map<?,?>) value;
            int frame = localFrame(doc, track, integer(row.get("frame"), "frame"), scene);
            double number = number(row.get("value"), "value");
            if (number < parameter.getMinValue() || number > parameter.getMaxValue())
                throw new IllegalArgumentException("Keyframe value is outside the parameter range");
            var type = CCurveType.valueOf(nonblank(row.get("interpolation"), "interpolation").toUpperCase(Locale.ROOT));
            if (keys.putIfAbsent(frame, new Key(frame, number, type)) != null)
                throw new IllegalArgumentException("Keyframe is duplicated: " + frame);
        }
        return keys;
    }

    private static void applyKeys(CMutableSequence sequence, Collection<Key> keys) {
        for (var key : keys) {
            sequence.setDoubleValueAuto(key.frame, key.value, key.type, false, false);
            sequence.setCurveType(key.frame, key.type);
        }
    }

    private record CurveEdit(CMvTrack_Live2DModel_Source track, String parameter, CMutableSequence sequence,
                             Map<Integer,Key> keys, Set<Integer> remove) {}

    private static Object batchCurves(CSceneDocument doc, Map<String,Object> request) {
        boolean scene = "scene".equals(request.get("frame_space"));
        var edits = new ArrayList<CurveEdit>();
        var seen = new HashSet<List<String>>();
        var tracks = new HashMap<String,CMvTrack_Live2DModel_Source>();
        for (var source : doc.getSceneSource().getAllTracks())
            if (source instanceof CMvTrack_Live2DModel_Source track) tracks.putIfAbsent(track.getGuid().getUuidString(), track);
        for (Object value : (List<?>) request.get("edits")) {
            var row = (Map<?,?>) value;
            String guid = nonblank(row.get("track_guid"), "track_guid");
            var track = tracks.get(guid);
            if (track == null) throw new IllegalArgumentException("Model track not found: " + guid);
            String id = nonblank(row.get("parameter_id"), "parameter_id");
            if (!seen.add(List.of(track.getGuid().getUuidString(), id)))
                throw new IllegalArgumentException("Only one edit per track and parameter is allowed");
            var parameter = trackParameter(track, id);
            var sequence = curve(track, parameter);
            var keys = parseKeys(doc, track, parameter, (List<?>) row.get("keys"), scene);
            var existing = new HashSet<Integer>();
            for (var point : sequence.getPoints()) existing.add(point.getPos());
            var remove = new LinkedHashSet<Integer>();
            for (Object frameValue : (List<?>) row.get("remove_frames")) {
                int frame = scene ? localFrame(doc, track, integer(frameValue, "frame"), true) : integer(frameValue, "frame");
                if (!existing.contains(frame) || keys.containsKey(frame) || !remove.add(frame))
                    throw new IllegalArgumentException("Removal must name a distinct existing key outside the upserts: " + frame);
            }
            if (keys.isEmpty() && remove.isEmpty()) throw new IllegalArgumentException("Each curve needs an edit");
            edits.add(new CurveEdit(track, id, sequence, keys, remove));
        }
        if (edits.isEmpty()) throw new IllegalArgumentException("Specify at least one curve edit");
        var undo = new GroupUndo("Native bridge: batch edit timeline");
        try {
            for (var edit : edits) undo.plusAssign(applyCurve(doc, edit.track, edit.sequence, "Edit timeline curve", () -> {
                edit.remove.forEach(edit.sequence::removeDoubleValueAuto);
                applyKeys(edit.sequence, edit.keys.values());
            }, false));
        } catch (RuntimeException | Error error) {
            undo.undo();
            refreshScene(doc);
            throw error;
        }
        finishTrackEdit(doc, undo);
        return map("document_uid", doc.getDocumentUID(), "curves", edits.stream().map(edit ->
            map("track_guid", edit.track.getGuid().getUuidString(), "parameter_id", edit.parameter,
                "keys", timelineKeys(edit.sequence, edit.track, scene, Long.MIN_VALUE, Long.MAX_VALUE))).toList());
    }

    private static void editCurve(CSceneDocument doc, CMvTrack_Live2DModel_Source track,
                                  CMutableSequence sequence, String name, Runnable apply) {
        finishSceneEdit(doc, applyCurve(doc, track, sequence, name, apply, true));
    }

    private static SimpleUndo applyCurve(CSceneDocument doc, CMvTrack_Live2DModel_Source track,
                                         CMutableSequence sequence, String name, Runnable apply, boolean refresh) {
        var effect = track.getKeyParam();
        var undo = new SimpleUndo("Native bridge: " + name, sequence, new com.live2d.core.a());
        try {
            apply.run();
            sequence.setDirty();
            effect.setDirty();
        } catch (RuntimeException | Error error) {
            undo.undo();
            throw error;
        }
        undo.addUndoRedoListener(event -> {
            effect.setDirty();
            if (refresh) refreshScene(doc);
            return kotlin.Unit.INSTANCE;
        });
        return undo;
    }

    private static void finishSceneEdit(CSceneDocument doc, com.live2d.undo.ACUndoable undo) {
        doc.getUndoManager().addEdit(undo);
        doc.updateLastModifiedTime();
        refreshScene(doc);
    }

    private static void refreshScene(CSceneDocument doc) {
        if (!doc.getViewContexts().isEmpty()) doc.resetAllViewContext();
    }

    static String prepareMotion(CSceneDocument doc, Map<String,Object> request) throws Exception {
        var track = track(doc, request.get("track_guid"));
        var exporter = new q();
        var exported = exporter.a(doc, track, true, 2, 2, 1, 1, 1, true, true, true);
        if (!(exported instanceof q.b.c result)) throw new IllegalStateException("Cubism could not export motion: " + exported);
        return result.a();
    }

    static Object writeMotion(Path workspace, Path path, String json) throws java.io.IOException {
        if (!(org.json.simple.JSONValue.parse(json) instanceof Map<?,?> root))
            throw new IllegalStateException("Cubism produced invalid Motion3 JSON");
        NativeBridgeAssets.writeNew(workspace, path, json);
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
