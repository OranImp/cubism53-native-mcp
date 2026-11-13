package cubism53.mcp;

import java.nio.file.Path;
import java.util.*;

import static cubism53.mcp.NativeBridge.map;

final class NativeBridgeSdk {
    private final Path path;
    private final Set<String> parameters;
    private final Set<String> parts;

    NativeBridgeSdk(Path path, Set<String> parameters, Set<String> parts) {
        this.path = path;
        this.parameters = parameters;
        this.parts = parts;
    }

    static final class InvalidAttachment extends IllegalArgumentException {
        final Path path;
        final String field;

        InvalidAttachment(Path path, String field, String detail) {
            super(path.getFileName() + " " + field + ": " + detail);
            this.path = path;
            this.field = field;
        }

        Map<String,Object> diagnostic() { return map("file", path.toString(), "field", field, "message", getMessage()); }
    }

    private void require(boolean condition, String field, String message) {
        if (!condition) throw new InvalidAttachment(path, field, message);
    }

    private Map<?,?> object(Object value, String field) {
        require(value instanceof Map<?,?>, field, "Expected an object");
        return (Map<?,?>) value;
    }

    private List<?> list(Object value, String field) {
        require(value instanceof List<?>, field, "Expected an array");
        return (List<?>) value;
    }

    private double number(Object value, String field) {
        require(value instanceof Number n && Double.isFinite(n.doubleValue()) && Float.isFinite(n.floatValue()),
            field, "Expected a finite number supported by Cubism");
        return ((Number) value).doubleValue();
    }

    private int integer(Object value, String field) {
        double result = number(value, field);
        require(result >= 0 && result <= Integer.MAX_VALUE && result == (int) result, field, "Expected a non-negative integer");
        return (int) result;
    }

    private void id(Object value, Set<String> known, String field) {
        require(value instanceof String key && known.contains(key), field, "Unknown model ID: " + value);
    }

    private void optionalNumber(Map<?,?> row, String field, String prefix) {
        if (row.get(field) != null) number(row.get(field), prefix + field);
    }

    void expression(Map<?,?> data) {
        optionalNumber(data, "FadeInTime", "");
        optionalNumber(data, "FadeOutTime", "");
        var rows = list(data.get("Parameters"), "Parameters");
        var seen = new HashSet<Object>();
        for (int i = 0; i < rows.size(); i++) {
            String field = "Parameters[" + i + "]";
            var row = object(rows.get(i), field);
            id(row.get("Id"), parameters, field + ".Id");
            require(seen.add(row.get("Id")), field + ".Id", "Duplicate parameter");
            number(row.get("Value"), field + ".Value");
            if (row.get("Blend") != null)
                require(row.get("Blend") instanceof String mode && Set.of("Add", "Multiply", "Overwrite").contains(mode),
                    field + ".Blend", "Expected Add, Multiply or Overwrite");
        }
    }

    void motion(Map<?,?> data) {
        require(integer(data.get("Version"), "Version") == 3, "Version", "Expected Motion3 version 3");
        var meta = object(data.get("Meta"), "Meta");
        require(number(meta.get("Duration"), "Meta.Duration") >= 0, "Meta.Duration", "Expected a non-negative duration");
        require(number(meta.get("Fps"), "Meta.Fps") > 0, "Meta.Fps", "Expected a positive frame rate");
        var curves = list(data.get("Curves"), "Curves");
        require(integer(meta.get("CurveCount"), "Meta.CurveCount") == curves.size(), "Meta.CurveCount", "Does not match Curves");
        long segments = 0, points = 0;
        for (int i = 0; i < curves.size(); i++) {
            String field = "Curves[" + i + "]";
            var curve = object(curves.get(i), field);
            Object target = curve.get("Target");
            if ("Parameter".equals(target)) id(curve.get("Id"), parameters, field + ".Id");
            else if ("PartOpacity".equals(target)) id(curve.get("Id"), parts, field + ".Id");
            else {
                require("Model".equals(target), field + ".Target", "Unknown curve target");
                require(curve.get("Id") instanceof String value && !value.isBlank(), field + ".Id", "Expected a model curve ID");
            }
            String dataField = field + ".Segments";
            var raw = list(curve.get("Segments"), dataField);
            require(raw.size() >= 2, dataField, "Missing initial point");
            double time = number(raw.get(0), dataField + "[0]");
            number(raw.get(1), dataField + "[1]");
            points++;
            for (int p = 2; p < raw.size();) {
                int kind = integer(raw.get(p), dataField + "[" + p + "]");
                require(kind <= 3, dataField + "[" + p + "]", "Unknown segment type");
                int length = kind == 1 ? 6 : 2;
                require(raw.size() - p - 1 >= length, dataField + "[" + p + "]", "Truncated segment");
                for (int j = 1; j <= length; j++) number(raw.get(p + j), dataField + "[" + (p + j) + "]");
                double next = ((Number) raw.get(p + length - 1)).doubleValue();
                require(next >= time, dataField + "[" + (p + length - 1) + "]", "Segment time moves backwards");
                time = next;
                p += length + 1;
                segments++;
                points += length / 2;
            }
        }
        require(integer(meta.get("TotalSegmentCount"), "Meta.TotalSegmentCount") == segments,
            "Meta.TotalSegmentCount", "Does not match parsed segments");
        require(integer(meta.get("TotalPointCount"), "Meta.TotalPointCount") == points,
            "Meta.TotalPointCount", "Does not match parsed points");
    }

    void physics(Map<?,?> data) {
        require(integer(data.get("Version"), "Version") == 3, "Version", "Expected Physics3 version 3");
        var meta = object(data.get("Meta"), "Meta");
        var settings = list(data.get("PhysicsSettings"), "PhysicsSettings");
        require(integer(meta.get("PhysicsSettingCount"), "Meta.PhysicsSettingCount") == settings.size(),
            "Meta.PhysicsSettingCount", "Does not match PhysicsSettings");
        long inputs = 0, outputs = 0, vertices = 0;
        for (int i = 0; i < settings.size(); i++) {
            String field = "PhysicsSettings[" + i + "]";
            var setting = object(settings.get(i), field);
            var particles = list(setting.get("Vertices"), field + ".Vertices");
            for (int j = 0; j < particles.size(); j++) {
                String at = field + ".Vertices[" + j + "]";
                var particle = object(particles.get(j), at);
                var position = object(particle.get("Position"), at + ".Position");
                number(position.get("X"), at + ".Position.X");
                number(position.get("Y"), at + ".Position.Y");
                for (String name : List.of("Mobility", "Delay", "Acceleration", "Radius")) number(particle.get(name), at + "." + name);
            }
            vertices += particles.size();
            var in = list(setting.get("Input"), field + ".Input");
            for (int j = 0; j < in.size(); j++) physicsReference(in.get(j), field + ".Input[" + j + "]", "Source");
            inputs += in.size();
            var out = list(setting.get("Output"), field + ".Output");
            for (int j = 0; j < out.size(); j++) {
                String at = field + ".Output[" + j + "]";
                var row = physicsReference(out.get(j), at, "Destination");
                int index = integer(row.get("VertexIndex"), at + ".VertexIndex");
                require(index > 0 && index < particles.size(), at + ".VertexIndex", "Outside the output particle range");
                number(row.get("Scale"), at + ".Scale");
            }
            outputs += out.size();
        }
        require(integer(meta.get("TotalInputCount"), "Meta.TotalInputCount") == inputs, "Meta.TotalInputCount", "Does not match inputs");
        require(integer(meta.get("TotalOutputCount"), "Meta.TotalOutputCount") == outputs, "Meta.TotalOutputCount", "Does not match outputs");
        require(integer(meta.get("VertexCount"), "Meta.VertexCount") == vertices, "Meta.VertexCount", "Does not match particles");
    }

    private Map<?,?> physicsReference(Object value, String field, String key) {
        var row = object(value, field);
        var ref = object(row.get(key), field + "." + key);
        require("Parameter".equals(ref.get("Target")), field + "." + key + ".Target", "Expected Parameter");
        id(ref.get("Id"), parameters, field + "." + key + ".Id");
        require(row.get("Type") instanceof String type && Set.of("X", "Y", "Angle").contains(type),
            field + ".Type", "Expected X, Y or Angle");
        double weight = number(row.get("Weight"), field + ".Weight");
        require(weight >= 0 && weight <= 100, field + ".Weight", "Expected a percentage from 0 to 100");
        return row;
    }
}
