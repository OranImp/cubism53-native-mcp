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
import com.live2d.cubism.doc.model.drawable.artPath.CArtPathForm;
import com.live2d.cubism.doc.model.drawable.artPath.CArtPathPoint;
import com.live2d.cubism.doc.model.drawable.artPath.CArtPathPointToArtMeshExtension_BindingData;
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
                if (!(findObject(source, nonblank(id, "mask GUID")) instanceof ACDrawableSource mask))
                    throw new IllegalArgumentException("Drawable mask GUIDs required");
                if (seen.add(mask.getGuid())) masks.add(mask.getGuid());
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

    static Object editPath(CModelingDocument doc, Map<String,Object> request) {
        var source = doc.getModelSource();
        var item = findObject(source, nonblank(request.get("object_guid"), "object_guid"));
        if (!(item instanceof CArtPathSource path)) throw new IllegalArgumentException("ArtPath required");
        var form = request.get("form_guid") == null ? path.getDefaultKeyForm()
            : (CArtPathForm) findForm(path, nonblank(request.get("form_guid"), "form_guid"));
        var previousBrush = path.getBrushGuid();
        var brush = request.get("brush_guid") == null ? previousBrush : source.getArtPathBrushesSetting().getBrushes().stream()
            .map(value -> value.getGuid()).filter(value -> value.getUuidString().equals(request.get("brush_guid")))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Choose an existing ArtPath brush"));
        boolean previousClosed = path.isClosed();
        boolean closed = request.get("closed") instanceof Boolean value ? value : previousClosed;
        if (closed && form.getPositions().size() < 3) throw new IllegalArgumentException("A closed ArtPath requires three points");
        var extension = path.getExtensions().stream().filter(CArtPathPointToArtMeshExtension.class::isInstance)
            .map(CArtPathPointToArtMeshExtension.class::cast).findFirst().orElse(null);
        var oldBindings = extension == null ? Map.<CArtPathPointGuid,CArtPathPointToArtMeshExtension_BindingData>of()
            : extension.getArtPathPointToPointInTriangle();
        var available = new HashMap<String,CArtPathPoint>();
        for (var point : form.getPositions()) available.put(point.getCtrlPtId().getUuidString(), point);
        var canvasEdits = new LinkedHashMap<CArtPathPointGuid,GVector2>();
        var localEdits = new LinkedHashMap<CArtPathPointGuid,GVector2>();
        var oldPositions = new LinkedHashMap<CArtPathPointGuid,GVector2>();
        var bindingEdits = new LinkedHashMap<CArtPathPointGuid,CArtPathPointToArtMeshExtension_BindingData>();
        var points = (List<?>) request.get("points");
        var bindings = (List<?>) request.get("bindings");
        boolean needsModel = !points.isEmpty() || bindings.stream().anyMatch(row -> ((Map<?,?>) row).get("mesh_guid") != null);
        var model = needsModel ? source.createModelInstance(RenderType.NORMAL) : null;
        try {
            if (model != null) {
                model.getParameterSet().setDefaultValues();
                var keyPoses = formKeys(path, form);
                if (!keyPoses.isEmpty()) {
                    for (var key : ((Map<?,?>) keyPoses.get(0)).entrySet()) {
                        var parameter = findParameter(source, (String) key.getKey());
                        model.getParameterSet().get(parameter.getGuid()).setValue(finite(key.getValue(), "keyform value"));
                    }
                }
                evaluateDetached(model);
            }
            var transform = model == null ? null : model.getDeformerTransform(path.getTargetDeformerGuid());
            for (Object value : points) {
                var row = (Map<?,?>) value;
                var point = pathPoint(available, row);
                var id = point.getCtrlPtId();
                var at = new GVector2(finite(row.get("x"), "x"), finite(row.get("y"), "y"));
                if (canvasEdits.putIfAbsent(id, at) != null) throw new IllegalArgumentException("Duplicate ArtPath point");
                var local = transform == null ? at : transform.transformCanvasToLocal(at, new GVector2(), -1);
                if (!Float.isFinite(local.getX()) || !Float.isFinite(local.getY()))
                    throw new IllegalArgumentException("ArtPath point cannot be transformed to local coordinates");
                localEdits.put(id, local);
                var old = point.getCurvePointPosition().getPoint();
                oldPositions.put(id, new GVector2(old.getX(), old.getY()));
            }
            for (Object value : bindings) {
                var row = (Map<?,?>) value;
                var point = pathPoint(available, row);
                var id = point.getCtrlPtId();
                if (bindingEdits.containsKey(id)) throw new IllegalArgumentException("Duplicate ArtPath binding");
                if (row.get("mesh_guid") == null) {
                    bindingEdits.put(id, null);
                    continue;
                }
                var mesh = requireMesh(source, nonblank(row.get("mesh_guid"), "mesh_guid"));
                var at = canvasEdits.get(id);
                if (at == null) {
                    var local = point.getCurvePointPosition().getPoint();
                    at = transform == null ? local : transform.transformLocalToCanvas(local, new GVector2(), -1);
                }
                var triangle = model.getArtMesh(mesh.getGuid()).getPointInTriangle_onCanvas(at, false);
                if (triangle == null) throw new IllegalArgumentException("ArtPath point is outside the target ArtMesh");
                bindingEdits.put(id, new CArtPathPointToArtMeshExtension_BindingData(mesh.getGuid(), triangle));
            }
        } finally {
            if (model != null) model.disposeAll();
        }
        for (var point : canvasEdits.keySet())
            if (oldBindings.containsKey(point) && !bindingEdits.containsKey(point))
                throw new IllegalArgumentException("Moved bound points require explicit rebind or detach");
        if (closed == previousClosed && brush.equals(previousBrush) && localEdits.isEmpty() && bindingEdits.isEmpty())
            return pathInfo(path);
        var undo = new GroupUndo("Native bridge: edit ArtPath");
        try {
            if (closed != previousClosed || !brush.equals(previousBrush))
                undo.plusAssign(new LambdaUndo<>("ArtPath brush and closure", path,
                    ignored -> { path.setBrushGuid(brush); path.setClosed(closed); return kotlin.Unit.INSTANCE; },
                    ignored -> { path.setBrushGuid(previousBrush); path.setClosed(previousClosed); return kotlin.Unit.INSTANCE; }, null));
            if (!localEdits.isEmpty())
                undo.plusAssign(new LambdaUndo<>("ArtPath positions", form,
                    ignored -> { setPathPoints(form, localEdits); return kotlin.Unit.INSTANCE; },
                    ignored -> { setPathPoints(form, oldPositions); return kotlin.Unit.INSTANCE; }, null));
            if (!bindingEdits.isEmpty()) {
                var active = extension == null ? new CArtPathPointToArtMeshExtension(path) : extension;
                if (extension == null)
                    undo.plusAssign(new LambdaUndo<>("Add ArtPath binding extension", path,
                        ignored -> { path.addExtension(active, path.getExtensions().size()); return kotlin.Unit.INSTANCE; },
                        ignored -> { path.removeExtension(active); return kotlin.Unit.INSTANCE; }, null));
                undo.plusAssign(active.getHandler().a("Edit ArtPath mesh bindings"));
                bindingEdits.forEach((key, data) -> {
                    if (data == null) active.getArtPathPointToPointInTriangle().remove(key);
                    else active.getArtPathPointToPointInTriangle().put(key, data);
                });
                form.setUpdated();
            }
        } catch (RuntimeException | Error error) {
            undo.undo();
            refresh(doc);
            throw error;
        }
        finishEdit(doc, undo);
        return pathInfo(path);
    }

    private static CArtPathPoint pathPoint(Map<String,CArtPathPoint> available, Map<?,?> row) {
        var result = available.get(nonblank(row.get("point_guid"), "point_guid"));
        if (result == null) throw new IllegalArgumentException("ArtPath point not found on the requested form");
        return result;
    }

    private static void setPathPoints(CArtPathForm form, Map<CArtPathPointGuid,GVector2> positions) {
        positions.forEach((id, xy) -> form.getPoint(id).getCurvePointPosition().setPoint(xy));
        form.setUpdated();
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

    static List<Map<String,Object>> bindings(CArtPathSource path) {
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
        CModel model = target instanceof ACDeformerSource ? source.createModelInstance(RenderType.NORMAL) : null;
        try {
            if (model != null) {
                model.getParameterSet().setDefaultValues();
                evaluateDetached(model);
            }
            var transform = model == null ? null : model.getDeformerTransform(((ACDeformerSource) target).getGuid());
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
        } finally { if (model != null) model.disposeAll(); }
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
        expandInScanOrder(closure, all, item -> {
            if (item instanceof ACAffecterSource) return List.of();
            var ancestors = new ArrayList<ACParameterControllableSource>();
            item.getAllParents().forEach(ancestors::add);
            item.getAllParentDeformers().forEach(ancestors::add);
            return ancestors;
        });
        var descendants = closure.stream().filter(item -> !selected.contains(item)).toList();
        var blockers = new ArrayList<Map<String,Object>>();
        if (cascade) selected.addAll(descendants);
        else for (var item : descendants) blockers.add(map("kind", "child", "guid", guid(item)));
        var ids = new HashSet<>(selected.stream().map(NativeBridgeAuthoring::guid).toList());
        var clipping = new ArrayList<Map<String,Object>>();
        var maskEdits = new ArrayList<MaskEdit>();
        for (var item : all) {
            if (selected.contains(item)) continue;
            var masks = NativeBridgeModel.masks(item);
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

}
