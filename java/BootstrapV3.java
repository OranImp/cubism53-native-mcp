package cubism53.mcp;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.json.simple.JSONValue;

/** Reload our adapter in an isolated loader; Cubism classes come from its own loader. */
public final class BootstrapV3 {
    public static void agentmain(String option, Instrumentation instrumentation) throws Exception {
        String configFile = option.startsWith("b64:") ? new String(
            Base64.getUrlDecoder().decode(option.substring(4)), StandardCharsets.UTF_8) : option;
        try {
            Map<?,?> config = (Map<?,?>) JSONValue.parse(Files.readString(Path.of(configFile)));
            URL jar = Path.of((String)config.get("adapter_jar")).toUri().toURL();
            URLClassLoader loader = new URLClassLoader(new URL[]{jar}, BootstrapV3.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.startsWith("cubism53.mcp.NativeBridge")) {
                        synchronized (getClassLoadingLock(name)) {
                            Class<?> loaded = findLoadedClass(name);
                            if (loaded == null) loaded = findClass(name);
                            if (resolve) resolveClass(loaded);
                            return loaded;
                        }
                    }
                    return super.loadClass(name, resolve);
                }
            };
            Class<?> adapter = loader.loadClass("cubism53.mcp.NativeBridge");
            adapter.getMethod("agentmain", String.class, Instrumentation.class).invoke(null, configFile, instrumentation);
        } catch (InvocationTargetException error) {
            throw new IllegalStateException("Cubism53 adapter initialization failed", error.getCause());
        }
    }
}
