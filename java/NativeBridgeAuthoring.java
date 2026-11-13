package cubism53.mcp;

import com.live2d.cubism.doc.model.ACForm;
import com.live2d.cubism.doc.model.ACParameterControllableSource;
import com.live2d.cubism.doc.model.CModel;
import com.live2d.cubism.doc.model.CModelSource;
import com.live2d.cubism.doc.model.affecter.ACAffecterSource;
import com.live2d.cubism.doc.model.deformer.ACDeformerSource;
import com.live2d.cubism.doc.model.deformer.warp.CWarpDeformerForm;
import com.live2d.cubism.doc.model.deformer.warp.CWarpDeformerSource;
import com.live2d.cubism.doc.model.drawable.ACDrawableSource;
import com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm;
import com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource;
import com.live2d.cubism.doc.model.drawable.artPath.CArtPathPointToArtMeshExtension;
import com.live2d.cubism.doc.model.drawable.artPath.CArtPathSource;
import com.live2d.cubism.doc.model.id.CDrawableId;
import com.live2d.cubism.doc.model.parts.CPartSource;
import com.live2d.cubism.doc.modeling.CModelingDocument;
import com.live2d.graphics3d.rendering.RenderType;
import com.live2d.graphics3d.type.GVector2;
import com.live2d.type.*;
import com.live2d.undo.GroupUndo;
import com.live2d.undo.LambdaUndo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.simple.JSONValue;

import static cubism53.mcp.NativeBridge.*;

final class NativeBridgeAuthoring {
    private NativeBridgeAuthoring() {}

    private static String guid(ACParameterControllableSource object) { return object.getGuid().getUuidString(); }

    private static CPartSource part(CModelSource source, Object id) {
        var root = source.getRootPart();
        if (id == null || guid(root).equals(id)) return root;
        if (findObject(source, nonblank(id, "part_guid")) instanceof CPartSource found) return found;
        throw new IllegalArgumentException("Part GUID required");
    }

    static Object editPart(CModelingDocument doc, Map<String,Object> request) {
        var source = doc.getModelSource();
        var item = part(source, request.get("part_guid"));
        if (item == source.getRootPart()) throw new IllegalArgumentException("Choose a non-root part");
        boolean offscreen = (boolean) request.getOrDefault("offscreen", item.getUseOffscreen());
        boolean invert = (boolean) request.getOrDefault("invert_mask", item.getInvertClippingMask());
        boolean drawGroup = (boolean) request.getOrDefault("draw_order_group", item.getEnableDrawOrderGroup());
        var masks = new CArrayList<CDrawableGuid>();
        if (request.get("mask_guids") instanceof List<?> ids) {
            var seen = new HashSet<CDrawableGuid>();
            for (Object id : ids) {
                if (!(findObject(source, nonblank(id, "mask GUID")) instanceof ACDrawableSource mask) || !seen.add(mask.getGuid()))
                    throw new IllegalArgumentException("Distinct drawable mask GUIDs required");
                masks.add(mask.getGuid());
            }
        } else masks.addAll(item.getClipGuidList());
        if (!offscreen && (!masks.isEmpty() || invert))
            throw new IllegalArgumentException("Part clipping requires offscreen rendering");
        var undo = item.createUndoable_forBasicSettings("Native bridge: edit part rendering");
        item.setUseOffscreen(offscreen);
        item.setClipGuidList(masks);
        item.setInvertClippingMask(invert);
        item.setEnableDrawOrderGroup(drawGroup);
        finishEdit(doc, undo);
        return map("part_guid", guid(item), "offscreen", offscreen, "invert_mask", invert, "draw_order_group", drawGroup,
            "mask_guids", masks.stream().map(CDrawableGuid::getUuidString).toList());
    }

    private record Reflection(ACForm target, float[] positions) {}

    static Object mirror(CModelingDocument doc, Map<String,Object> request) {
        var source = doc.getModelSource();
        var from = findObject(source, nonblank(request.get("source_guid"), "source_guid"));
        var to = findObject(source, nonblank(request.get("target_guid"), "target_guid"));
        if (from.getClass() != to.getClass() || !(from instanceof CArtMeshSource || from instanceof CWarpDeformerSource))
            throw new IllegalArgumentException("Mirror requires two ArtMeshes or two Warp deformers");
        String axis = nonblank(request.get("axis"), "axis");
        if (!Set.of("x", "y").contains(axis)) throw new IllegalArgumentException("Axis must be x or y");
        float pivot = finite(request.get("pivot"), "pivot");
        int axisIndex = "x".equals(axis) ? 0 : 1;
        var indices = correspondence(from, to, request.get("vertex_indices"), axisIndex);
        var pairs = (List<?>) request.get("forms");
        if (pairs.isEmpty()) throw new IllegalArgumentException("Specify at least one keyform pair");
        var targets = new LinkedHashSet<String>();
        var edits = new ArrayList<Reflection>();
        CModel model = source.createModelInstance(RenderType.NORMAL);
        try {
            for (Object value : pairs) {
                var pair = (Map<?,?>) value;
                var origin = findForm(from, nonblank(pair.get("source_form_guid"), "source_form_guid"));
                String target = nonblank(pair.get("target_form_guid"), "target_form_guid");
                if (!targets.add(target)) throw new IllegalArgumentException("Target form appears more than once");
                var destination = findForm(to, target);
                var local = positions(origin);
                if (local.length != indices.length * 2 || positions(destination).length != local.length)
                    throw new IllegalArgumentException("Keyform point counts differ");
                setMirrorPose(model, source, from, origin, pair.get("source_parameters"));
                float[] canvas = model.getDeformerTransform(from.getTargetDeformerGuid()).createLocalToCanvasTransform()
                    .transform(local, new float[local.length]);
                var reflected = new float[canvas.length];
                for (int i = 0; i < indices.length; i++) {
                    reflected[i * 2] = canvas[indices[i] * 2];
                    reflected[i * 2 + 1] = canvas[indices[i] * 2 + 1];
                    int coordinate = i * 2 + axisIndex;
                    reflected[coordinate] = 2 * pivot - reflected[coordinate];
                }
                setMirrorPose(model, source, to, destination, pair.get("target_parameters"));
                var transform = model.getDeformerTransform(to.getTargetDeformerGuid());
                var result = transform.createCanvasToLocalTransform().transform(reflected, new float[reflected.length]);
                float[] roundtrip = transform.createLocalToCanvasTransform().transform(result, new float[result.length]);
                for (int i = 0; i < result.length; i++) {
                    if (!Float.isFinite(result[i]) || !Float.isFinite(roundtrip[i]) || Math.abs(roundtrip[i] - reflected[i]) > .1f)
                        throw new IllegalArgumentException("Target deformation cannot invert the reflected point");
                }
                edits.add(new Reflection(destination, result));
            }
        } finally { model.disposeAll(); }
        var undo = to.getHandler().createUndo_forKeyformEdit("Native bridge: mirror keyforms");
        try {
            for (var edit : edits) {
                if (edit.target instanceof CArtMeshForm mesh) mesh.setPositions(edit.positions);
                else ((CWarpDeformerForm) edit.target).setPositions(edit.positions);
            }
        } catch (RuntimeException | Error error) {
            undo.undo();
            refresh(doc);
            throw error;
        }
        finishEdit(doc, undo);
        return map("object_guid", guid(to), "form_guids", List.copyOf(targets), "point_count", indices.length);
    }

    private static void setMirrorPose(CModel model, CModelSource source, ACParameterControllableSource object, ACForm form, Object override) {
        var keys = formKeys(object, form);
        var values = override instanceof Map<?,?> explicit ? explicit : keys.isEmpty() ? Map.of() : (Map<?,?>) keys.get(0);
        if (override == null && keys.size() > 1) throw new IllegalArgumentException("Shared form needs an explicit evaluation pose");
        if (!keys.isEmpty() && keys.stream().map(row -> (Map<?,?>) row).noneMatch(row -> row.entrySet().stream().allMatch(entry ->
            values.get(entry.getKey()) instanceof Number n && n.floatValue() == ((Number) entry.getValue()).floatValue())))
            throw new IllegalArgumentException("Evaluation pose must include the keyform's bound parameter values");
        model.getParameterSet().reinit(pose(source, "default", values), new com.live2d.core.a());
        evaluateDetached(model);
    }

    private static int[] correspondence(ACParameterControllableSource from, ACParameterControllableSource to, Object requested, int axis) {
        int count = positions(from.getDefaultKeyForm()).length / 2;
        if (positions(to.getDefaultKeyForm()).length != count * 2)
            throw new IllegalArgumentException("Objects have different point counts");
        var indices = new int[count];
        if (from instanceof CWarpDeformerSource a && to instanceof CWarpDeformerSource b) {
            if (a.getCol() != b.getCol() || a.getRow() != b.getRow() || count != (a.getCol() + 1) * (a.getRow() + 1))
                throw new IllegalArgumentException("Warp subdivisions differ");
            if (requested != null) throw new IllegalArgumentException("Warp grid correspondence is derived from the axis");
            int columns = a.getCol() + 1;
            for (int row = 0; row <= a.getRow(); row++)
                for (int col = 0; col <= a.getCol(); col++)
                    indices[row * columns + col] = (axis == 1 ? a.getRow() - row : row) * columns + (axis == 0 ? a.getCol() - col : col);
        } else {
            for (int i = 0; i < count; i++) indices[i] = i;
            if (requested instanceof List<?> map) {
                if (map.size() != count) throw new IllegalArgumentException("Provide one source vertex index per target vertex");
                var used = new HashSet<Integer>();
                for (int i = 0; i < count; i++) {
                    Object value = map.get(i);
                    if (!(value instanceof Number n) || n.intValue() != n.doubleValue()
                            || n.intValue() < 0 || n.intValue() >= count || !used.add(n.intValue()))
                        throw new IllegalArgumentException("Vertex indices must be a permutation");
                    indices[i] = n.intValue();
                }
            }
            if (!triangles(((CArtMeshSource) from).getIndices(), null)
                    .equals(triangles(((CArtMeshSource) to).getIndices(), indices)))
                throw new IllegalArgumentException("Mapped ArtMesh triangle topology differs");
        }
        return indices;
    }

    private static Set<List<Integer>> triangles(int[] indices, int[] mapping) {
        var result = new HashSet<List<Integer>>();
        for (int i = 0; i < indices.length; i += 3) {
            int[] triangle = Arrays.copyOfRange(indices, i, i + 3);
            if (mapping != null) for (int j = 0; j < 3; j++) triangle[j] = mapping[triangle[j]];
            Arrays.sort(triangle);
            result.add(List.of(triangle[0], triangle[1], triangle[2]));
        }
        return result;
    }

    private static Map<String,Object> pathInfo(CArtPathSource path) {
        return map("guid", guid(path), "name", path.getLocalName(), "brush_guid", path.getBrushGuid().getUuidString(),
            "part_guid", path.getParent() == null ? null : guid(path.getParent()),
            "deformer_guid", path.getTargetDeformerGuid().getUuidString(), "closed", path.isClosed(),
            "bindings", bindings(path), "forms", path.getKeyforms().stream().map(form ->
                map("guid", form.getGuid().getUuidString(), "keys", formKeys(path, form),
                    "points", form.getPositions().stream().map(point -> {
                        var xy = point.getCurvePointPosition().getPoint();
                        return map("guid", point.getCtrlPtId().getUuidString(), "x", xy.getX(), "y", xy.getY(),
                            "width", point.getWidth(), "opacity", point.getOpacity());
                    }).toList())).toList());
    }

    private static List<Map<String,Object>> bindings(CArtPathSource path) {
        var result = new ArrayList<Map<String,Object>>();
        for (var extension : path.getExtensions()) {
            if (extension instanceof CArtPathPointToArtMeshExtension pointBinding) {
                pointBinding.getArtPathPointToPointInTriangle().forEach((point, data) ->
                    result.add(map("point_guid", point.getUuidString(), "mesh_guid", data.getGuid().getUuidString())));
            }
        }
        result.sort(Comparator.comparing(entry -> (String) entry.get("point_guid")));
        return result;
    }

    static Object paths(CModelSource source, Map<String,Object> request) {
        var paths = source.getAllArtPaths();
        if (request.get("object_guid") instanceof String id) {
            paths = paths.stream().filter(path -> id.equals(guid(path))).toList();
            if (paths.isEmpty()) throw new IllegalArgumentException("ArtPath not found: " + id);
        }
        var brushes = source.getArtPathBrushesSetting().getBrushes().stream()
            .map(brush -> map("guid", brush.getGuid().getUuidString(), "name", brush.getName())).toList();
        return map("art_paths", paths.stream().map(NativeBridgeAuthoring::pathInfo).toList(), "brushes", brushes);
    }

    static Object createPath(CModelingDocument doc, Map<String,Object> request) {
        var source = doc.getModelSource();
        var parent = part(source, request.get("part_guid"));
        ACParameterControllableSource target = parent;
        if (request.get("deformer_guid") instanceof String id) {
            target = findObject(source, id);
            if (!(target instanceof ACDeformerSource)) throw new IllegalArgumentException("Deformer GUID required");
            if (request.get("part_guid") == null && target.getParent() != null) parent = target.getParent();
        }
        String name = nonblank(request.get("name"), "name");
        var brush = request.get("brush_guid") == null ? null : source.getArtPathBrushesSetting().getBrushes().stream()
            .filter(item -> item.getGuid().getUuidString().equals(request.get("brush_guid"))).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Choose an existing brush from inspect_art_paths"));
        var rows = (List<?>) request.get("points");
        boolean closed = Boolean.TRUE.equals(request.get("closed"));
        if (rows.size() < (closed ? 3 : 2)) throw new IllegalArgumentException("Too few ArtPath points");
        var points = new CArrayList<GVector2>();
        var widths = new CArrayList<Float>();
        var colors = new CArrayList<CFloatColor>();
        var opacities = new CArrayList<Float>();
        var corners = new CArrayList<Boolean>();
        CModel model = source.createModelInstance(RenderType.NORMAL);
        try {
            model.getParameterSet().setDefaultValues();
            evaluateDetached(model);
            var transform = target instanceof ACDeformerSource deformer ? model.getDeformerTransform(deformer.getGuid()) : null;
            for (Object value : rows) {
                var row = (Map<?,?>) value;
                var point = new GVector2(finite(row.get("x"), "x"), finite(row.get("y"), "y"));
                var local = transform == null ? point : transform.transformCanvasToLocal(point, new GVector2(), -1);
                float width = row.containsKey("width") ? finite(row.get("width"), "width") : 4;
                float opacity = row.containsKey("opacity") ? finite(row.get("opacity"), "opacity") : 1;
                if (!Float.isFinite(local.getX()) || !Float.isFinite(local.getY()) || width <= 0 || opacity < 0 || opacity > 1)
                    throw new IllegalArgumentException("Invalid ArtPath point");
                points.add(local);
                widths.add(width);
                opacities.add(opacity);
                colors.add(new CFloatColor(0, 0, 0, 1));
                corners.add(Boolean.TRUE.equals(row.get("corner")));
            }
        } finally { model.disposeAll(); }
        var undo = new GroupUndo("Native bridge: create ArtPath");
        CArtPathSource path;
        try {
            path = source.getHandler().a(doc.getEditMode_modeling(), name, source,
                new CDrawableId("ArtPath" + UUID.randomUUID().toString().replace("-", "")), target, null, undo,
                points, widths, colors, opacities, corners);
            if (path == null || path.getDefaultKeyForm().getPositions().size() != points.size())
                throw new IllegalStateException("Cubism did not create the requested ArtPath");
            var previousBrush = path.getBrushGuid();
            var newBrush = brush == null ? previousBrush : brush.getGuid();
            boolean previousClosed = path.isClosed();
            undo.plusAssign(new LambdaUndo<>("Set ArtPath brush and closure", path,
                edit -> { path.setBrushGuid(newBrush); path.setClosed(closed); return kotlin.Unit.INSTANCE; },
                edit -> { path.setBrushGuid(previousBrush); path.setClosed(previousClosed); return kotlin.Unit.INSTANCE; }, null));
            if (path.getParent() != parent) undo.plusAssign(parent.getHandler().addPartChild(path, parent.getChildren().size()));
        } catch (RuntimeException | Error error) {
            undo.undo();
            refresh(doc);
            throw error;
        }
        finishEdit(doc, undo);
        return pathInfo(path);
    }

    private record MaskEdit(ACParameterControllableSource owner, CArrayList<CDrawableGuid> retained) {}

    static Object delete(CModelingDocument doc, String operation, Map<String,Object> request) throws Exception {
        var source = doc.getModelSource();
        var all = source.getAllObjects();
        var selected = new LinkedHashSet<ACParameterControllableSource>();
        for (Object value : (List<?>) request.get("object_guids")) {
            var item = findObject(source, nonblank(value, "object GUID"));
            if (item == source.getRootPart() || !selected.add(item))
                throw new IllegalArgumentException("Specify distinct non-root objects");
        }
        if (selected.isEmpty()) throw new IllegalArgumentException("Specify objects to delete");
        boolean cascade = Boolean.TRUE.equals(request.get("cascade"));
        boolean detach = Boolean.TRUE.equals(request.get("detach_references"));
        var closure = new LinkedHashSet<>(selected);
        boolean changed;
        do {
            changed = false;
            for (var item : all) {
                if (item instanceof ACAffecterSource) continue;
                if (!closure.contains(item) && (includes(item.getAllParents(), closure)
                        || includes(item.getAllParentDeformers(), closure))) changed |= closure.add(item);
            }
        } while (changed);
        var descendants = closure.stream().filter(item -> !selected.contains(item)).toList();
        var blockers = new ArrayList<Map<String,Object>>();
        if (cascade) selected.addAll(descendants);
        else for (var item : descendants) blockers.add(map("kind", "child", "guid", guid(item)));
        var ids = new HashSet<>(selected.stream().map(NativeBridgeAuthoring::guid).toList());
        var clipping = new ArrayList<Map<String,Object>>();
        var maskEdits = new ArrayList<MaskEdit>();
        for (var item : all) {
            if (selected.contains(item)) continue;
            var masks = item instanceof ACDrawableSource drawable ? drawable.getClipGuidList()
                : item instanceof CPartSource part ? part.getClipGuidList() : null;
            if (masks == null) continue;
            var removed = masks.stream().map(CDrawableGuid::getUuidString).filter(ids::contains).toList();
            if (removed.isEmpty()) continue;
            clipping.add(map("owner_guid", guid(item), "mask_guids", removed));
            var retained = new CArrayList<CDrawableGuid>();
            for (var mask : masks) if (!ids.contains(mask.getUuidString())) retained.add(mask);
            maskEdits.add(new MaskEdit(item, retained));
            if (!detach) blockers.add(map("kind", "clipping", "guid", guid(item)));
        }
        var glues = new ArrayList<Map<String,Object>>();
        var removedGlue = new HashSet<String>();
        for (var glue : source.getAllGlues()) {
            String a = glue.getTargetArtMeshA_guid().getUuidString(), b = glue.getTargetArtMeshB_guid().getUuidString();
            if (!ids.contains(a) && !ids.contains(b)) continue;
            String id = glue.getGuid().getUuidString();
            removedGlue.add(id);
            glues.add(map("guid", id, "a_guid", a, "b_guid", b));
            if (!detach && ids.contains(a) != ids.contains(b))
                blockers.add(map("kind", "glue", "guid", id));
        }
        var pathBindings = new ArrayList<Map<String,Object>>();
        for (var path : source.getAllArtPaths()) {
            for (var binding : bindings(path)) {
                if (!selected.contains(path) && !ids.contains(binding.get("mesh_guid"))) continue;
                binding.put("path_guid", guid(path));
                pathBindings.add(binding);
                if (!selected.contains(path)) blockers.add(map("kind", "art_path_binding", "guid", guid(path)));
            }
        }
        var materials = new TreeMap<String,List<String>>();
        var removedMaterials = new HashSet<String>();
        for (var mesh : source.getAllArtMeshes()) {
            var input = mesh.getTextureInputExtension().getModelImageInput();
            if (input == null) continue;
            String id = input.getModelImageGuid().getUuidString();
            if (selected.contains(mesh)) removedMaterials.add(id);
            else materials.computeIfAbsent(id, ignored -> new ArrayList<>()).add(guid(mesh));
        }
        var retained = removedMaterials.stream().sorted().map(id ->
            map("guid", id, "remaining_mesh_guids", materials.getOrDefault(id, List.of()))).toList();
        var result = map("document_uid", doc.getDocumentUID(), "delete_guids", ids.stream().sorted().toList(),
            "descendant_guids", descendants.stream().map(NativeBridgeAuthoring::guid).toList(),
            "clipping", clipping, "glues", glues, "art_path_bindings", pathBindings, "materials", retained, "blockers", blockers);
        byte[] fingerprint = JSONValue.toJSONString(map("impact", result, "cascade", cascade, "detach", detach,
            "modified", doc.getLastModifiedTime(), "undo", doc.getUndoManager().getCurrentPos())).getBytes(StandardCharsets.UTF_8);
        String plan = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fingerprint));
        result.put("plan_id", plan);
        if ("plan_delete_objects".equals(operation)) return result;
        if (!plan.equals(request.get("plan_id"))) throw new IllegalArgumentException("Delete plan is stale; plan again");
        if (!blockers.isEmpty()) throw new IllegalArgumentException("Delete plan has blockers: " + blockers);
        var undo = new GroupUndo("Native bridge: delete objects");
        var expected = new HashSet<String>();
        for (var item : all) if (!selected.contains(item) && !removedGlue.contains(guid(item))) expected.add(guid(item));
        try {
            for (var edit : maskEdits) {
                undo.plusAssign(edit.owner.getHandler().createUndo_forBasicSetting("Detach deleted masks"));
                if (edit.owner instanceof ACDrawableSource drawable) drawable.setClipGuidList(edit.retained);
                else ((CPartSource) edit.owner).setClipGuidList(edit.retained);
            }
            // The native handler deletes associated Glue and records both directions.
            undo.plusAssign(source.getHandler().a(List.copyOf(selected), source.getCurrentInstance(), false));
            var actual = new HashSet<String>();
            for (var item : source.getAllObjects()) actual.add(guid(item));
            if (!actual.equals(expected)) throw new IllegalStateException("Native deletion differs from the planned object set");
        } catch (RuntimeException | Error error) {
            undo.undo();
            refresh(doc);
            throw error;
        }
        finishEdit(doc, undo);
        result.put("applied", true);
        return result;
    }

    private static boolean includes(Iterable<? extends ACParameterControllableSource> items,
                                    Set<ACParameterControllableSource> selected) {
        for (var item : items) if (selected.contains(item)) return true;
        return false;
    }
}
