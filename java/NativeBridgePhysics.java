package cubism53.mcp;

import com.live2d.cubism.doc.gameData.physics.CPhysicsSettingsSourceSet;
import com.live2d.cubism.doc.gameData.physics.CPhysicsSettingsSource;
import com.live2d.cubism.doc.gameData.physics.CPhysicsInput;
import com.live2d.cubism.doc.gameData.physics.CPhysicsOutput;
import com.live2d.cubism.doc.gameData.physics.CPhysicsVertex;
import com.live2d.cubism.doc.gameData.physics.CPhysicsController.CPhysicsSourceType;
import com.live2d.cubism.doc.model.CModelSource;
import com.live2d.cubism.doc.model.id.CPhysicsSettingId;
import com.live2d.graphics3d.type.GVector2;
import com.live2d.type.CArrayList;
import com.live2d.undo.SimpleUndo;
import com.live2d.cubism.doc.model.param.CParameter;
import com.live2d.cubism.doc.model.param.CParameterSet;
import com.live2d.cubism.doc.modeling.CModelingDocument;
import com.live2d.type.CParameterGuid;
import java.util.*;

import static cubism53.mcp.NativeBridge.*;

final class NativeBridgePhysics {
    static Object editGroups(CModelingDocument doc, Map<String,Object> request) {
        var source = doc.getModelSource();
        var settings = source.getPhysicsSettingsSourceSet();
        var edits = (List<?>) request.get("edits");
        Integer fps = request.get("fps") == null ? null : integer(request, "fps", 0);
        if (fps != null && fps < 1) throw new IllegalArgumentException("FPS must be a positive integer");
        var gravity = request.get("gravity") == null ? null : vector(request.get("gravity"));
        var wind = request.get("wind") == null ? null : vector(request.get("wind"));
        if (edits.isEmpty() && fps == null && gravity == null && wind == null)
            throw new IllegalArgumentException("Provide at least one physics edit");
        var candidate = settings.deepCopy(new com.live2d.core.a());
        for (Object value : edits) applyGroup(source, candidate, (Map<?,?>) value);
        var undo = new SimpleUndo("Native bridge: edit physics groups", settings, new com.live2d.core.a());
        try {
            settings.reinit(candidate, new com.live2d.core.a());
            if (fps != null) settings.setSettingFPS(fps);
            if (gravity != null) settings.setGravity(gravity);
            if (wind != null) settings.setWind(wind);
            for (var group : settings.getSources()) group.updatePhysicsSettings();
        } catch (RuntimeException | Error failure) {
            undo.undo();
            refresh(doc);
            throw failure;
        }
        finishEdit(doc, undo);
        return physicsInfo(source);
    }

    private static void applyGroup(CModelSource source, CPhysicsSettingsSourceSet settings, Map<?,?> row) {
        String action = nonblank(row.get("action"), "action");
        var group = "add".equals(action) ? null : settings.getSources().stream()
            .filter(value -> value.getGuid().getUuidString().equals(row.get("group_guid"))).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Physics group not found: " + row.get("group_guid")));
        if ("remove".equals(action)) {
            settings.remove(group);
            return;
        }
        if ("move".equals(action)) {
            int index = groupIndex(row, settings.getSources().size() - 1);
            settings.remove(group);
            settings.add(group, index);
            return;
        }
        boolean add = "add".equals(action);
        if (!add && !"update".equals(action)) throw new IllegalArgumentException("Unknown physics group action");
        if (add) group = new CPhysicsSettingsSource(new CPhysicsSettingId("Physics" + UUID.randomUUID().toString().replace("-", "")));
        if (add || row.containsKey("name")) group.setName(nonblank(row.get("name"), "name"));
        if (row.get("particles") instanceof List<?> particles) {
            if (particles.size() < 2) throw new IllegalArgumentException("Provide at least two physics particles");
            var list = new CArrayList<CPhysicsVertex>();
            for (Object value : particles) {
                var item = (Map<?,?>) value;
                var vertex = new CPhysicsVertex();
                vertex.setPosition(vector(item.get("position")));
                vertex.setMobility(bounded(item.get("mobility"), "mobility", 0, 1));
                vertex.setDelay(bounded(item.get("delay"), "delay", 0, 1));
                vertex.setAcceleration(bounded(item.get("acceleration"), "acceleration", 0, Float.MAX_VALUE));
                vertex.setRadius(bounded(item.get("radius"), "radius", 0, Float.MAX_VALUE));
                list.add(vertex);
            }
            group.setVertices(list);
        }
        if (group.getVertices().size() < 2) throw new IllegalArgumentException("This physics group needs at least two particles");
        if (row.get("inputs") instanceof List<?> inputs) {
            var list = new CArrayList<CPhysicsInput>();
            for (Object value : inputs) {
                var link = (Map<?,?>) value;
                var input = new CPhysicsInput();
                input.setSource(findParameter(source, nonblank(link.get("parameter_id"), "parameter_id")).getGuid());
                input.setType(linkType(link));
                input.setWeight(bounded(link.get("weight"), "weight", 0, 100));
                input.setReverse(Boolean.TRUE.equals(link.get("reverse")));
                list.add(input);
            }
            group.setInputs(list);
        }
        if (row.get("outputs") instanceof List<?> outputs) {
            var list = new CArrayList<CPhysicsOutput>();
            for (Object value : outputs) {
                var link = (Map<?,?>) value;
                var output = new CPhysicsOutput();
                output.setDestination(findParameter(source, nonblank(link.get("parameter_id"), "parameter_id")).getGuid());
                var type = linkType(link);
                output.setType(type);
                output.setWeight(bounded(link.get("weight"), "weight", 0, 100));
                output.setReverse(Boolean.TRUE.equals(link.get("reverse")));
                output.setVertexIndex(groupIndex(link, "vertex_index", group.getVertices().size() - 1));
                float scale = finite(link.get("scale"), "scale");
                if (type == CPhysicsSourceType.SRC_TO_G_ANGLE) output.setAngleScale(scale);
                else output.setTranslationScale(new GVector2(scale, scale));
                list.add(output);
            }
            group.setOutputs(list);
        }
        for (var output : group.getOutputs())
            if (output.getVertexIndex() < 1 || output.getVertexIndex() >= group.getVertices().size())
                throw new IllegalArgumentException("Output particle index is outside the group");
        if (add) settings.add(group, row.containsKey("index") ? groupIndex(row, settings.getSources().size()) : settings.getSources().size());
    }

    private static int groupIndex(Map<?,?> row, int max) {
        return groupIndex(row, "index", max);
    }

    private static int groupIndex(Map<?,?> row, String name, int max) {
        if (!(row.get(name) instanceof Number value) || value.intValue() != value.doubleValue()
                || value.intValue() < 0 || value.intValue() > max)
            throw new IllegalArgumentException("Invalid physics " + name);
        return value.intValue();
    }

    private static CPhysicsSourceType linkType(Map<?,?> link) {
        return switch (nonblank(link.get("type"), "link type")) {
            case "x" -> CPhysicsSourceType.SRC_TO_X;
            case "y" -> CPhysicsSourceType.SRC_TO_Y;
            case "angle" -> CPhysicsSourceType.SRC_TO_G_ANGLE;
            default -> throw new IllegalArgumentException("Unknown physics link type");
        };
    }

    private static GVector2 vector(Object value) {
        var coordinates = floatArray(value);
        if (coordinates.length != 2) throw new IllegalArgumentException("An XY vector is required");
        return new GVector2(coordinates[0], coordinates[1]);
    }

    private record Driver(CParameterGuid guid, float value) {}
    private record Range(CParameterGuid guid, float min, float max) {}
    private record OutputRange(String group, String parameter, int groupIndex, int outputIndex) {}

    record Simulation(CPhysicsSettingsSourceSet settings, CParameterSet pose, List<?> frames,
                      Map<String,Range> parameters,
                      Map<String,CParameterGuid> outputs, List<OutputRange> ranges, int warmup, float delta,
                      boolean summary) {}

    static Simulation prepare(CModelingDocument doc, Map<String,Object> request) {
        var source = doc.getModelSource();
        var settings = source.getPhysicsSettingsSourceSet().deepCopy(new com.live2d.core.a());
        if (settings.getSources().isEmpty()) throw new IllegalArgumentException("No physics groups");
        // CParameter.deepCopy retains the original source, including its mutable range and default.
        var pose = new CParameterSet();
        var context = new com.live2d.core.a();
        for (var parameter : source.getCurrentInstance().getParameterSet().getParameters())
            pose.unaryPlus(new CParameter(parameter.getSource().deepCopy(context)));
        float delta = request.get("delta_seconds") instanceof Number n ? n.floatValue() : 1f / 60;
        if (!Float.isFinite(delta) || delta <= 0 || delta > .1f)
            throw new IllegalArgumentException("Physics time step must be 0..0.1 seconds");
        int warmup = integer(request, "warmup_frames", 120);
        var requestedFrames = (List<?>) request.get("frames");
        if (warmup < 0 || warmup > 3600 || requestedFrames == null || requestedFrames.isEmpty() || requestedFrames.size() > 36000)
            throw new IllegalArgumentException("Invalid frame count");

        var parameters = new HashMap<String,Range>();
        var names = new HashMap<CParameterGuid,String>();
        for (var parameter : source.getAllParameters()) {
            String id = parameter.getId().toString();
            parameters.put(id, new Range(parameter.getGuid(), parameter.getMinValue(), parameter.getMaxValue()));
            names.put(parameter.getGuid(), id);
        }
        var selected = new HashSet<Object>();
        boolean filtered = request.get("output_parameter_ids") instanceof List<?>;
        if (filtered) selected.addAll((List<?>) request.get("output_parameter_ids"));
        var outputs = new LinkedHashMap<String,CParameterGuid>();
        var ranges = new ArrayList<OutputRange>();
        var groups = settings.getSources();
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            var group = groups.get(groupIndex);
            for (int i = 0; i < group.getOutputs().size(); i++) {
                var output = group.getOutputs().get(i);
                String id = names.get(output.getDestination());
                if (id == null) throw new IllegalArgumentException("Physics output references a missing parameter");
                if (!filtered || selected.contains(id)) {
                    outputs.put(id, output.getDestination());
                    ranges.add(new OutputRange(group.getName(), id, groupIndex, i));
                }
            }
        }
        if (!outputs.keySet().containsAll(selected)) throw new IllegalArgumentException("Requested parameter is not a physics output");
        return new Simulation(settings, pose, requestedFrames, parameters, outputs, ranges, warmup, delta, "summary".equals(request.get("result_mode")));
    }

    private static List<List<Driver>> resolveFrames(Simulation simulation) {
        var frames = new ArrayList<List<Driver>>(simulation.frames.size());
        var parameters = simulation.parameters;
        for (Object frame : simulation.frames) {
            var drivers = new ArrayList<Driver>();
            for (var entry : ((Map<?,?>) frame).entrySet()) {
                var parameter = parameters.get(entry.getKey());
                if (parameter == null) throw new IllegalArgumentException("Parameter not found: " + entry.getKey());
                float value = finite(entry.getValue(), "parameter " + entry.getKey());
                if (value < parameter.min || value > parameter.max)
                    throw new IllegalArgumentException("Parameter outside defined range: " + entry.getKey());
                drivers.add(new Driver(parameter.guid, value));
            }
            frames.add(drivers);
        }
        return frames;
    }

    static boolean spill(Simulation simulation) {
        return !simulation.summary && (long) simulation.frames.size() * (simulation.outputs.size() + 1) >= 16000;
    }

    static Map<String,Object> solve(Simulation simulation, long deadline, NativeBridgeTrace.Spool spool) throws java.io.IOException {
        var drivers = resolveFrames(simulation);
        var settings = simulation.settings;
        var pose = simulation.pose;
        var solver = new com.live2d.cubism.doc.gameData.physics.b();
        solver.a().setX(settings.getGravity().getX());
        solver.a().setY(settings.getGravity().getY());
        var groups = settings.getSources();
        for (var group : groups) solver.a(group.getVertices(), settings.getGravity());

        var trace = new ArrayList<Object>();
        var extrema = new LinkedHashMap<String,float[]>();
        if (simulation.summary) simulation.outputs.keySet().forEach(id ->
            extrema.put(id, new float[]{Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}));
        for (int frame = -simulation.warmup; frame < simulation.frames.size(); frame++) {
            if (deadline != 0 && deadline - System.nanoTime() <= 0)
                throw new IllegalStateException("Physics simulation timed out; retry with execution=job");
            pose.setDefaultValues();
            if (frame >= 0) for (var driver : drivers.get(frame)) pose.setValue(driver.guid, driver.value);
            for (var group : groups) if (group.getEnable())
                solver.a(group, pose, settings.getGravity(), settings.getWind(), simulation.delta,
                    ignored -> kotlin.Unit.INSTANCE, false, null, null, null);
            if (frame < 0) continue;
            var values = simulation.summary ? null : new LinkedHashMap<String,Object>();
            for (var output : simulation.outputs.entrySet()) {
                float value = pose.get(output.getValue()).getValue();
                if (!Float.isFinite(value)) throw new IllegalStateException("Nonfinite physics output: " + output.getKey());
                if (simulation.summary) {
                    var range = extrema.get(output.getKey());
                    range[0] = Math.min(range[0], value);
                    range[1] = Math.max(range[1], value);
                } else values.put(output.getKey(), value);
            }
            if (!simulation.summary) {
                var row = map("frame", frame, "outputs", values);
                if (spool == null) trace.add(row);
                else spool.append(row);
            }
        }
        var ranges = new ArrayList<Object>();
        for (var range : simulation.ranges) {
            var output = groups.get(range.groupIndex).getOutputs().get(range.outputIndex);
            ranges.add(map("group", range.group, "parameter", range.parameter,
                "raw_minimum", output.getValueBelowMinimum(), "raw_maximum", output.getValueExceededMaximum()));
        }
        var result = map("native_solver", "Cubism5.3 physics.b", "delta_seconds", simulation.delta,
            "warmup_frames", simulation.warmup, "output_ranges", ranges, "document_modified", false);
        if (simulation.summary) {
            var summary = new LinkedHashMap<String,Object>();
            extrema.forEach((id, range) -> summary.put(id, map("minimum", range[0], "maximum", range[1])));
            result.put("frame_count", simulation.frames.size());
            result.put("summary", summary);
        } else if (spool == null) result.put("frames", trace);
        return result;
    }
}
