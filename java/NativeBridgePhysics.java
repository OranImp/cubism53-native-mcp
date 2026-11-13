package cubism53.mcp;

import com.live2d.cubism.doc.gameData.physics.CPhysicsSettingsSourceSet;
import com.live2d.cubism.doc.model.param.CParameter;
import com.live2d.cubism.doc.model.param.CParameterSet;
import com.live2d.cubism.doc.modeling.CModelingDocument;
import com.live2d.type.CParameterGuid;
import java.util.*;

import static cubism53.mcp.NativeBridge.*;

final class NativeBridgePhysics {
    private record Driver(CParameterGuid guid, float value) {}
    private record Range(CParameterGuid guid, float min, float max) {}
    private record OutputRange(String group, String parameter, int groupIndex, int outputIndex) {}

    record Simulation(CPhysicsSettingsSourceSet settings, CParameterSet pose, List<List<Driver>> frames,
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
        var frames = new ArrayList<List<Driver>>();
        for (Object frame : requestedFrames) {
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
        return new Simulation(settings, pose, frames, outputs, ranges, warmup, delta, "summary".equals(request.get("result_mode")));
    }

    static Object solve(Simulation simulation, long deadline) {
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
            if (frame >= 0) for (var driver : simulation.frames.get(frame)) pose.setValue(driver.guid, driver.value);
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
            if (!simulation.summary) trace.add(map("frame", frame, "outputs", values));
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
        } else result.put("frames", trace);
        return result;
    }
}
