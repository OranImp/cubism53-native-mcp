package cubism53.mcp;

import com.live2d.cubism.CEAppCtrl;
import com.live2d.cubism.doc.webSocket.x;
import java.lang.instrument.Instrumentation;
import javax.swing.SwingUtilities;

/** Invoke editor commands directly in the dedicated portable smoke-test process. */
public final class NativeEditorProbe {
    public static void agentmain(String operation, Instrumentation ignored) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var app = CEAppCtrl.Companion.b();
            if ("undo".equals(operation)) {
                app.command_undo(app.getCurrentDoc());
            } else if ("redo".equals(operation)) {
                app.command_redo(app.getCurrentDoc());
            } else if (operation.startsWith("preview=")) {
                var preview = x.a.a();
                preview.a(false);
                preview.a(Integer.parseInt(operation.substring("preview=".length())));
                preview.d();
            } else if ("approve-preview".equals(operation)) {
                var entries = x.a.a().g();
                if (entries.size() != 1) throw new IllegalStateException("Expected one isolated preview connection");
                entries.get(0).a(true);
            } else {
                throw new IllegalArgumentException("Unknown probe operation");
            }
        });
    }
}
