package cubism53.mcp;

import com.live2d.cubism.doc.model.ACParameterControllableSource;
import com.live2d.cubism.doc.model.CModelSource;
import com.live2d.cubism.doc.model.drawable.ACDrawableSource;
import com.live2d.cubism.doc.model.drawable.ACDrawableForm;
import com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource;
import com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm;
import com.live2d.cubism.doc.modeling.CModelingDocument;
import com.live2d.cubism.doc.model.param.CParameterSource;
import com.live2d.type.CFloatColor;
import com.live2d.undo.GroupUndo;
import java.util.*;

import static cubism53.mcp.NativeBridge.*;

final class NativeBridgeModel {
    static List<Float> colorInfo(CFloatColor color) {
        return List.of(color.getR(), color.getG(), color.getB(), color.getA());
    }

    private static CFloatColor color(Object value, CFloatColor previous) {
        if (value == null) return null;
        var rgb = floatArray(value);
        if (rgb.length != 3) throw new IllegalArgumentException("Color requires RGB");
        for (float channel : rgb) if (channel < 0 || channel > 1) throw new IllegalArgumentException("RGB must be in 0..1");
        return new CFloatColor(rgb[0], rgb[1], rgb[2], previous.getA());
    }

    static Object setColors(CModelingDocument doc, Map<String,Object> request) {
        var item = findObject(doc.getModelSource(), (String) request.get("object_guid"));
        var form = findForm(item, (String) request.get("form_guid"));
        if (!(form instanceof ACDrawableForm drawable)) throw new IllegalArgumentException("Drawable form required");
        var multiply = color(request.get("multiply_color"), drawable.getMultiplyColor());
        var screen = color(request.get("screen_color"), drawable.getScreenColor());
        if (multiply == null && screen == null) throw new IllegalArgumentException("Provide at least one color");
        var undo = item.getHandler().createUndo_forKeyformEdit("Native bridge: keyform colors");
        if (multiply != null) drawable.setMultiplyColor(multiply);
        if (screen != null) drawable.setScreenColor(screen);
        form.setUpdated();
        finishEdit(doc, undo);
        return map("object_guid", item.getGuid().getUuidString(), "form_guid", form.getGuid().getUuidString(),
            "multiply_color", colorInfo(drawable.getMultiplyColor()), "screen_color", colorInfo(drawable.getScreenColor()));
    }

    static Object createBlendShape(CModelingDocument doc, Map<String,Object> request) {
        var source = doc.getModelSource();
        var item = findObject(source, (String) request.get("object_guid"));
        if (!(item instanceof CArtMeshSource mesh)) throw new IllegalArgumentException("ArtMesh required for Blend Shape");
        var base = (CArtMeshForm) findForm(mesh, (String) request.get("base_form_guid"));
        if (base.isMorphTarget()) throw new IllegalArgumentException("The base must be an ordinary keyform");
        float[] positions = floatArray(request.get("positions"));
        if (positions.length != base.getPositions().length) throw new IllegalArgumentException("Vertex count must remain unchanged");
        String id = nonblank(request.get("parameter_id"), "parameter_id");
        String name = nonblank(request.get("name"), "name");
        if (!id.matches("[A-Za-z][A-Za-z0-9_]{0,63}") || source.getAllParameters().stream().anyMatch(p -> id.equals(p.getId().toString())))
            throw new IllegalArgumentException("A new valid parameter ID is required");
        var parameter = com.live2d.cubism.doc.model.morphTarget.MorphTargetParameterUtils.a.a(
            new com.live2d.cubism.doc.model.id.CParameterId(id), CParameterSource.Type.MORPH_TARGET, 0f, 1f, 0f, "");
        parameter.setName(name);
        var undo = new GroupUndo("Native bridge: create Blend Shape");
        String shapeGuid;
        try {
            undo.plusAssign(source.getRootParameterGroup().getHandler().a(parameter, -1));
            source.updateModelInstances();
            var shape = com.live2d.cubism.doc.model.morphTarget.MorphTargetUtils.a.a(
                source.getCurrentInstance(), mesh, 1f, parameter.getGuid(), undo);
            if (shape == null || !(shape.getKeyform() instanceof CArtMeshForm form))
                throw new IllegalStateException("Cubism could not create a mesh Blend Shape");
            var original = base.getPositions();
            var target = form.getPositions().clone();
            for (int i = 0; i < target.length; i++) target[i] += positions[i] - original[i];
            form.setPositions(target);
            form.setUpdated();
            shapeGuid = form.getGuid().getUuidString();
        } catch (RuntimeException | Error error) {
            undo.undo();
            refresh(doc);
            throw error;
        }
        finishEdit(doc, undo);
        return map("parameter_id", id, "parameter_guid", parameter.getGuid().getUuidString(),
            "object_guid", mesh.getGuid().getUuidString(), "form_guid", shapeGuid, "key", 1);
    }

    private record Entry(ACParameterControllableSource object, List<String> parts, List<String> deformers,
                         List<Object> bindings, String material, List<String> masks) {
        String guid() { return object.getGuid().getUuidString(); }
    }

    static Object dependencies(CModelSource source, Map<String,Object> request) {
        String kind = nonblank(request.get("kind"), "kind");
        String id = nonblank(request.get("id"), "id");
        var all = new LinkedHashMap<String,ACParameterControllableSource>();
        for (var object : source.getAllObjects()) all.put(object.getGuid().getUuidString(), object);
        switch (kind) {
            case "object" -> findObject(source, id);
            case "parameter" -> findParameter(source, id);
            case "material" -> {
                if (source.getTextureManager().getAllModelImages().stream().noneMatch(image -> id.equals(image.getGuid().getUuidString())))
                    throw new IllegalArgumentException("Material not found: " + id);
            }
            default -> throw new IllegalArgumentException("Unknown dependency selector");
        }
        var parameters = new HashMap<com.live2d.type.CParameterGuid,String>();
        for (var parameter : source.getAllParameters()) parameters.put(parameter.getGuid(), parameter.getId().toString());
        var entries = new ArrayList<Entry>();
        var byGuid = new HashMap<String,Entry>();
        var maskUsers = new HashMap<String,List<String>>();
        var materialUsers = new HashMap<String,List<String>>();
        for (var object : all.values()) {
            var parts = new ArrayList<String>();
            for (var parent : object.getAllParents()) parts.add(parent.getGuid().getUuidString());
            var deformers = new ArrayList<String>();
            var next = all.get(object.getTargetDeformerGuid().getUuidString());
            while (next != null && !deformers.contains(next.getGuid().getUuidString())) {
                deformers.add(next.getGuid().getUuidString());
                next = all.get(next.getTargetDeformerGuid().getUuidString());
            }
            var bindings = new ArrayList<Object>();
            for (var binding : object.getKeyformGridSource().getKeyformBindings())
                bindings.add(map("parameter_id", binding.getParameterId().toString(), "keys", List.copyOf(binding.getKeys())));
            for (var guid : object.getKeyformMorphTargetSet().getBoundParameterGuids())
                bindings.add(map("parameter_id", parameters.get(guid), "kind", "blend_shape",
                    "keys", object.getKeyformMorphTargetSet().getMorphTargetList(guid).stream().map(shape -> shape.getKeyValue()).toList()));
            String material = null;
            if (object instanceof CArtMeshSource mesh) {
                var input = mesh.getTextureInputExtension().getModelImageInput();
                if (input != null) material = input.getModelImageGuid().getUuidString();
            }
            var masks = object instanceof ACDrawableSource drawable
                ? drawable.getClipGuidList().stream().map(guid -> guid.getUuidString()).toList() : List.<String>of();
            var entry = new Entry(object, parts, deformers, bindings, material, masks);
            entries.add(entry);
            byGuid.put(entry.guid(), entry);
            for (var mask : new HashSet<>(entry.masks))
                maskUsers.computeIfAbsent(mask, key -> new ArrayList<>()).add(entry.guid());
            if (entry.material != null)
                materialUsers.computeIfAbsent(entry.material, key -> new ArrayList<>()).add(entry.guid());
        }
        var glueByMesh = new HashMap<String,List<Map<String,Object>>>();
        for (var glue : glues(source)) {
            var a = glue.getTargetArtMeshA_guid().getUuidString();
            var b = glue.getTargetArtMeshB_guid().getUuidString();
            var info = map("guid", glue.getGuid().getUuidString(), "a_guid", a, "b_guid", b);
            glueByMesh.computeIfAbsent(a, key -> new ArrayList<>()).add(info);
            if (!a.equals(b)) glueByMesh.computeIfAbsent(b, key -> new ArrayList<>()).add(info);
        }
        var result = new ArrayList<Object>();
        for (var entry : entries) {
            boolean matches = switch (kind) {
                case "object" -> id.equals(entry.guid());
                case "material" -> id.equals(entry.material);
                default -> containsParameter(entry.bindings, id)
                    || entry.deformers.stream().anyMatch(guid -> containsParameter(byGuid.get(guid).bindings, id));
            };
            if (!matches) continue;
            var inherited = new ArrayList<Object>();
            for (var ancestor : entry.deformers) {
                var parent = byGuid.get(ancestor);
                if (parent != null && !parent.bindings.isEmpty())
                    inherited.add(map("deformer_guid", ancestor, "bindings", parent.bindings));
            }
            result.add(map("guid", entry.guid(), "id", entry.object.getId().toString(),
                "name", entry.object.getLocalName(), "type", entry.object.getClass().getSimpleName(),
                "part_chain", entry.parts, "deformer_chain", entry.deformers, "bindings", entry.bindings, "inherited_bindings", inherited,
                "mask_guids", entry.masks, "mask_user_guids", maskUsers.getOrDefault(entry.guid(), List.of()),
                "material_guid", entry.material, "material_user_guids", materialUsers.getOrDefault(entry.material, List.of()),
                "glue", glueByMesh.getOrDefault(entry.guid(), List.of())));
        }
        return map("kind", kind, "id", id, "objects", result);
    }

    private static boolean containsParameter(List<Object> bindings, String id) {
        return bindings.stream().anyMatch(binding -> id.equals(((Map<?,?>) binding).get("parameter_id")));
    }
}
