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

    static Object preflight(CModelSource source, Map<String,Object> request) throws ReflectiveOperationException {
        Object nativeChecks = NativeBridgeAssets.nativeEditionChecks();
        Class<?> constraints = nativeChecks.getClass();
        boolean freeLimits = (boolean) constraints.getMethod("l").invoke(nativeChecks);
        var exceedsKeyBindingLimit = constraints.getMethod("a", int.class, boolean.class);
        var blockers = new ArrayList<Object>();
        var warnings = new ArrayList<Object>();
        var impact = new ArrayList<Object>();
        var proposed = new LinkedHashMap<String,Map<String,Set<Float>>>();
        for (Object value : (List<?>) request.get("parameter_keys")) {
            var row = (Map<?,?>) value;
            String object = nonblank(row.get("object_guid"), "object_guid");
            var item = findObject(source, object);
            var parameter = findParameter(source, nonblank(row.get("parameter_id"), "parameter_id"));
            if (!parameter.isNormal()) throw new IllegalArgumentException("Normal parameters only");
            var byParameter = proposed.computeIfAbsent(object, key -> new LinkedHashMap<>());
            var values = byParameter.computeIfAbsent(parameter.getId().toString(), key -> new LinkedHashSet<>());
            if (item.getKeyformGridSource().findKeyformBinding(parameter.getGuid()) == null) values.add(parameter.getDefaultValue());
            var requested = floatArray(row.get("values"));
            if (requested.length == 0) throw new IllegalArgumentException("At least one key is required");
            for (float key : requested) {
                if (key < parameter.getMinValue() || key > parameter.getMaxValue())
                    throw new IllegalArgumentException("Key is outside the parameter range");
                values.add(key);
            }
        }
        for (var edit : proposed.entrySet()) {
            var item = findObject(source, edit.getKey());
            var byParameter = edit.getValue();
            for (var binding : item.getKeyformGridSource().getKeyformBindings())
                byParameter.computeIfAbsent(binding.getParameterId().toString(), key -> new LinkedHashSet<>()).addAll(binding.getKeys());
            long estimate = 1;
            for (var values : byParameter.values())
                estimate = estimate > Long.MAX_VALUE / Math.max(1, values.size()) ? Long.MAX_VALUE : estimate * Math.max(1, values.size());
            var row = map("object_guid", edit.getKey(), "parameter_count", byParameter.size(),
                "existing_keyforms", item.getKeyforms().size(), "estimated_grid_keyforms", estimate);
            if (estimate > item.getKeyforms().size())
                warnings.add(map("kind", "keyform_grid_growth", "object_guid", edit.getKey(), "estimated_grid_keyforms", estimate));
            if ((boolean) exceedsKeyBindingLimit.invoke(nativeChecks, byParameter.size(), false))
                blockers.add(map("kind", "native_parameter_binding_limit", "object_guid", edit.getKey(), "parameter_count", byParameter.size()));
            impact.add(row);
        }
        var textureObjects = (List<?>) request.get("texture_objects");
        var textureUsers = new HashMap<String,List<String>>();
        if (!textureObjects.isEmpty()) {
            for (var mesh : source.getAllArtMeshes()) {
                String material = materialGuid(mesh);
                if (material != null)
                    textureUsers.computeIfAbsent(material, ignored -> new ArrayList<>()).add(mesh.getGuid().getUuidString());
            }
        }
        for (Object key : textureObjects) {
            var item = findObject(source, nonblank(key, "object_guid"));
            if (!(item instanceof CArtMeshSource mesh)) throw new IllegalArgumentException("ArtMesh required for texture impact");
            var input = mesh.getTextureInputExtension().getModelImageInput();
            if (input == null) {
                blockers.add(map("kind", "missing_model_image", "object_guid", key));
                continue;
            }
            var material = input.getModelImageGuid().getUuidString();
            var users = textureUsers.getOrDefault(material, List.of());
            impact.add(map("object_guid", key, "material_guid", material, "affected_mesh_guids", users));
            if (users.size() > 1) warnings.add(map("kind", "shared_material", "object_guid", key, "affected_mesh_guids", users));
        }
        return map("ready", blockers.isEmpty(), "free_model_limits", freeLimits, "blockers", blockers, "warnings", warnings, "impact", impact);
    }

    static Object editBlendShape(CModelingDocument doc, Map<String,Object> request) {
        var source = doc.getModelSource();
        var item = findObject(source, nonblank(request.get("object_guid"), "object_guid"));
        if (!(item instanceof CArtMeshSource mesh)) throw new IllegalArgumentException("ArtMesh required for Blend Shape");
        var parameter = findParameter(source, nonblank(request.get("parameter_id"), "parameter_id"));
        if (!parameter.isMorphTarget()) throw new IllegalArgumentException("A Blend Shape parameter is required");
        var guid = parameter.getGuid();
        var shapes = mesh.getKeyformMorphTargetSet().getMorphTargetList(guid);
        String action = nonblank(request.get("action"), "action");
        var undo = new GroupUndo("Native bridge: edit Blend Shape");
        String formGuid = null;
        try {
            var nativeEdit = com.live2d.cubism.doc.model.morphTarget.MorphTargetUtils.a;
            switch (action) {
                case "bind" -> {
                    float key = finite(request.get("key"), "key");
                    if (key < parameter.getMinValue() || key > parameter.getMaxValue() || key == parameter.getDefaultValue()
                            || shapes.stream().anyMatch(shape -> shape.getKeyValue() == key))
                        throw new IllegalArgumentException("Blend Shape key must be unique and inside the nondefault parameter range");
                    var base = request.get("form_guid") == null ? mesh.getDefaultKeyForm()
                        : (CArtMeshForm) findForm(mesh, nonblank(request.get("form_guid"), "form_guid"));
                    if (base.isMorphTarget()) throw new IllegalArgumentException("Ordinary base keyform required");
                    float[] positions = floatArray(request.get("positions"));
                    if (positions.length != base.getPositions().length) throw new IllegalArgumentException("Vertex count must remain unchanged");
                    var shape = nativeEdit.a(mesh, base, guid, key, undo);
                    if (shape == null || !(shape.getKeyform() instanceof CArtMeshForm form))
                        throw new IllegalStateException("Cubism could not bind the Blend Shape");
                    form.setPositions(positions);
                    form.setUpdated();
                    formGuid = form.getGuid().getUuidString();
                }
                case "update" -> {
                    String selected = nonblank(request.get("form_guid"), "form_guid");
                    var shape = shapes.stream().filter(candidate -> selected.equals(candidate.getKeyformGuid().getUuidString()))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("Blend Shape form not found on this parameter"));
                    var form = (CArtMeshForm) shape.getKeyform();
                    float[] positions = floatArray(request.get("positions"));
                    if (positions.length != form.getPositions().length) throw new IllegalArgumentException("Vertex count must remain unchanged");
                    undo.plusAssign(mesh.getHandler().createUndo_forKeyformEdit("Edit Blend Shape positions"));
                    form.setPositions(positions);
                    form.setUpdated();
                    formGuid = selected;
                }
                case "unbind" -> {
                    if (shapes.isEmpty()) throw new IllegalArgumentException("The object has no binding to this parameter");
                    nativeEdit.a(mesh, guid, undo);
                    if (!mesh.getKeyformMorphTargetSet().getMorphTargetList(guid).isEmpty())
                        throw new IllegalStateException("Cubism did not remove the selected binding");
                }
                default -> throw new IllegalArgumentException("Unknown Blend Shape action");
            }
        } catch (RuntimeException | Error error) {
            undo.undo();
            refresh(doc);
            throw error;
        }
        finishEdit(doc, undo);
        return map("object_guid", mesh.getGuid().getUuidString(), "parameter_id", parameter.getId().toString(),
            "parameter_guid", guid.getUuidString(), "form_guid", formGuid, "action", action);
    }

    static Map<String,Object> topologyReferences(CModelSource source, CArtMeshSource mesh) {
        var key = mesh.getGuid();
        var glueGuids = glues(source).stream()
            .filter(glue -> key.equals(glue.getTargetArtMeshA_guid()) || key.equals(glue.getTargetArtMeshB_guid()))
            .map(glue -> glue.getGuid().getUuidString()).toList();
        var pathBindings = new ArrayList<Map<String,Object>>();
        for (var path : source.getAllArtPaths()) {
            for (var binding : NativeBridgeAuthoring.bindings(path)) {
                if (!key.getUuidString().equals(binding.get("mesh_guid"))) continue;
                binding.put("path_guid", path.getGuid().getUuidString());
                pathBindings.add(binding);
            }
        }
        return map("object_guid", key.getUuidString(), "glue_guids", glueGuids, "art_path_bindings", pathBindings);
    }

    static List<com.live2d.type.CDrawableGuid> masks(ACParameterControllableSource object) {
        if (object instanceof ACDrawableSource drawable) return drawable.getClipGuidList();
        if (object instanceof com.live2d.cubism.doc.model.parts.CPartSource part) return part.getClipGuidList();
        return List.of();
    }

    private static String materialGuid(ACParameterControllableSource object) {
        if (!(object instanceof CArtMeshSource mesh)) return null;
        var input = mesh.getTextureInputExtension().getModelImageInput();
        return input == null ? null : input.getModelImageGuid().getUuidString();
    }

    private static List<Object> bindings(ACParameterControllableSource object, Map<com.live2d.type.CParameterGuid,String> parameters) {
        var bindings = new ArrayList<Object>();
        for (var binding : object.getKeyformGridSource().getKeyformBindings())
            bindings.add(map("parameter_id", binding.getParameterId().toString(), "keys", List.copyOf(binding.getKeys())));
        for (var guid : object.getKeyformMorphTargetSet().getBoundParameterGuids())
            bindings.add(map("parameter_id", parameters.get(guid), "kind", "blend_shape",
                "keys", object.getKeyformMorphTargetSet().getMorphTargetList(guid).stream().map(shape -> shape.getKeyValue()).toList()));
        return bindings;
    }

    private static List<String> deformerChain(ACParameterControllableSource object, Map<String,ACParameterControllableSource> all) {
        var ancestors = new LinkedHashSet<String>();
        var next = all.get(object.getTargetDeformerGuid().getUuidString());
        while (next != null && ancestors.add(next.getGuid().getUuidString()))
            next = all.get(next.getTargetDeformerGuid().getUuidString());
        return List.copyOf(ancestors);
    }

    static Object dependencies(CModelSource source, Map<String,Object> request) {
        String kind = nonblank(request.get("kind"), "kind");
        String id = nonblank(request.get("id"), "id");
        var all = new LinkedHashMap<String,ACParameterControllableSource>();
        for (var object : source.getAllObjects()) all.put(object.getGuid().getUuidString(), object);
        for (var part : source.getAllParts()) all.putIfAbsent(part.getGuid().getUuidString(), part);
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
        var selected = new LinkedHashMap<String,List<String>>();
        var directBindings = new HashMap<String,List<Object>>();
        var maskUsers = new HashMap<String,List<String>>();
        var materialUsers = new HashMap<String,List<String>>();
        var bound = new HashSet<String>();
        if ("parameter".equals(kind))
            all.forEach((guid, object) -> { if (boundTo(object, id, parameters)) bound.add(guid); });
        for (var row : all.entrySet()) {
            var guid = row.getKey();
            var object = row.getValue();
            var material = materialGuid(object);
            for (var mask : new LinkedHashSet<>(masks(object)))
                maskUsers.computeIfAbsent(mask.getUuidString(), key -> new ArrayList<>()).add(guid);
            if (material != null) materialUsers.computeIfAbsent(material, key -> new ArrayList<>()).add(guid);
            if ("object".equals(kind) && !id.equals(guid) || "material".equals(kind) && !id.equals(material)) continue;
            var chain = deformerChain(object, all);
            if (!"parameter".equals(kind) || bound.contains(guid) || chain.stream().anyMatch(bound::contains))
                selected.put(guid, chain);
        }
        var glueByMesh = new HashMap<String,List<Map<String,Object>>>();
        for (var glue : glues(source)) {
            var a = glue.getTargetArtMeshA_guid().getUuidString();
            var b = glue.getTargetArtMeshB_guid().getUuidString();
            var info = map("guid", glue.getGuid().getUuidString(), "a_guid", a, "b_guid", b);
            glueByMesh.computeIfAbsent(a, key -> new ArrayList<>()).add(info);
            if (!a.equals(b)) glueByMesh.computeIfAbsent(b, key -> new ArrayList<>()).add(info);
        }
        var artPathByObject = new HashMap<String,List<Map<String,Object>>>();
        for (var path : source.getAllArtPaths()) {
            String pathGuid = path.getGuid().getUuidString();
            for (var binding : NativeBridgeAuthoring.bindings(path)) {
                binding.put("path_guid", pathGuid);
                artPathByObject.computeIfAbsent(pathGuid, key -> new ArrayList<>()).add(binding);
                artPathByObject.computeIfAbsent((String) binding.get("mesh_guid"), key -> new ArrayList<>()).add(binding);
            }
        }
        var result = new ArrayList<Object>();
        for (var entry : selected.entrySet()) {
            String guid = entry.getKey();
            var object = all.get(guid);
            var parts = new ArrayList<String>();
            for (var parent : object.getAllParents()) parts.add(parent.getGuid().getUuidString());
            var inherited = new ArrayList<Object>();
            for (var ancestor : entry.getValue()) {
                var parentBindings = directBindings.computeIfAbsent(ancestor, key -> bindings(all.get(key), parameters));
                if (!parentBindings.isEmpty()) inherited.add(map("deformer_guid", ancestor, "bindings", parentBindings));
            }
            var ownBindings = directBindings.computeIfAbsent(guid, key -> bindings(object, parameters));
            var material = materialGuid(object);
            result.add(map("guid", guid, "id", object.getId().toString(), "name", object.getLocalName(),
                "type", object.getClass().getSimpleName(), "part_chain", parts, "deformer_chain", entry.getValue(),
                "bindings", ownBindings, "inherited_bindings", inherited,
                "mask_guids", masks(object).stream().map(mask -> mask.getUuidString()).toList(),
                "mask_user_guids", maskUsers.getOrDefault(guid, List.of()),
                "material_guid", material, "material_user_guids", materialUsers.getOrDefault(material, List.of()),
                "glue", glueByMesh.getOrDefault(guid, List.of()),
                "art_path_bindings", artPathByObject.getOrDefault(guid, List.of())));
        }
        return map("kind", kind, "id", id, "objects", result);
    }

    private static boolean boundTo(ACParameterControllableSource object, String id,
                                   Map<com.live2d.type.CParameterGuid,String> parameters) {
        for (var binding : object.getKeyformGridSource().getKeyformBindings())
            if (id.equals(binding.getParameterId().toString())) return true;
        for (var guid : object.getKeyformMorphTargetSet().getBoundParameterGuids())
            if (id.equals(parameters.get(guid))) return true;
        return false;
    }
}
