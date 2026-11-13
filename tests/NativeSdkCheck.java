package cubism53.mcp;

import com.live2d.sdk.cubism.core.CubismModel;
import com.live2d.sdk.cubism.a.f.b;
import com.live2d.sdk.cubism.a.f.d;
import com.live2d.sdk.cubism.a.f.e;
import com.live2d.sdk.cubism.a.f.j;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Exercise the SDK runtime shipped with the official Viewer. */
public final class NativeSdkCheck {
    public static void main(String[] args) throws Exception {
        com.live2d.sdk.cubism.a.b.a((com.live2d.sdk.cubism.a.b.a) null);
        com.live2d.sdk.cubism.a.b.a();
        // The editor JAR has a class and package with the same obfuscated name.
        var mocType = Class.forName("com.live2d.sdk.cubism.a.e.a");
        var moc = mocType.getMethod("a", byte[].class, boolean.class).invoke(null, bytes(args[0]), true);
        if (moc == null) throw new AssertionError("Viewer SDK rejected the MOC3");
        var model = mocType.getMethod("a").invoke(moc);
        if (model == null) throw new AssertionError("Viewer SDK could not instantiate the model");
        var modelType = model.getClass();
        modelType.getMethod("f").invoke(model);
        try (var core = (CubismModel) modelType.getMethod("g").invoke(model)) {
            if (args.length > 3 && Arrays.stream(core.getOffscreenRendering().getMaskCounts()).noneMatch(count -> count > 0))
                throw new AssertionError("SDK did not receive the offscreen part clipping mask");
            var motion = e.a(bytes(args[1]));
            var expression = b.a(bytes(args[2]));
            if (motion == null || expression == null || expression.g().size() != 3)
                throw new AssertionError("Viewer SDK did not load the motion and all expression parameters");
            var motions = new j();
            motions.a(motion);
            var updateMotion = j.class.getMethod("a", modelType, float.class);
            updateMotion.invoke(motions, model, 0f);
            updateMotion.invoke(motions, model, .25f);
            float x = parameter(core, "ParamAngleX"), y = parameter(core, "ParamAngleY"), z = parameter(core, "ParamAngleZ");
            if (Math.abs(x) < .01f || Math.abs(y) < .01f || Math.abs(z) < .01f)
                throw new AssertionError("Motion did not drive all three parameters");
            var expressions = new d();
            expressions.a(expression);
            d.class.getMethod("a", modelType, float.class).invoke(expressions, model, .25f);
            requireClose(parameter(core, "ParamAngleX"), x + 3);
            requireClose(parameter(core, "ParamAngleY"), y * .5f);
            requireClose(parameter(core, "ParamAngleZ"), 7);
            System.out.println("VIEWER_SDK_MOTION_AND_EXPRESSION_BLEND_OK");
        }
    }

    private static byte[] bytes(String path) throws Exception { return Files.readAllBytes(Path.of(path)); }

    private static float parameter(CubismModel model, String id) {
        var parameters = model.getParameters();
        int index = Arrays.asList(parameters.getIds()).indexOf(id);
        if (index < 0) throw new AssertionError("MOC3 is missing parameter " + id);
        return parameters.getValues()[index];
    }

    private static void requireClose(float actual, float expected) {
        if (!Float.isFinite(actual) || Math.abs(actual - expected) > .001f)
            throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
