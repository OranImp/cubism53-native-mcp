package cubism53.mcp;

import com.live2d.cubism.CEAppCtrl;
import com.live2d.cubism.doc.webSocket.x;
import com.live2d.cubism.doc.model.drawable.artPath.Line.CArtPathBrush;
import com.live2d.cubism.doc.model.id.CArtPathBrushId;
import com.live2d.cubism.doc.animation.CSceneDocument;
import com.live2d.cubism.doc.animation.CAnimationFileContent;
import com.live2d.cubism.doc.animation.movie.track.CMvTrack_Live2DModel_Source;
import com.live2d.cubism.doc.modeling.CModelingDocument;
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
            } else if ("artpath-brush".equals(operation)) {
                var doc = (CModelingDocument) app.getCurrentDoc();
                var source = doc.getModelSource();
                var brush = new CArtPathBrush(source, "Test Brush", new CArtPathBrushId("TestBrush"));
                brush.setDefault();
                source.getArtPathBrushesSetting().addBrush(brush, 0);
                doc.updateLastModifiedTime();
            } else if (operation.startsWith("add-timeline-event=") || operation.startsWith("remove-timeline-event=")) {
                String uid = operation.substring(operation.indexOf('=') + 1);
                CSceneDocument scene = app.getCurrentProject().getChildren().stream()
                    .filter(CAnimationFileContent.class::isInstance).map(CAnimationFileContent.class::cast)
                    .flatMap(content -> content.getSceneDocs().stream()).filter(doc -> uid.equals(doc.getDocumentUID()))
                    .findFirst().orElseThrow();
                var track = scene.getSceneSource().getAllTracks().stream()
                    .filter(CMvTrack_Live2DModel_Source.class::isInstance).findFirst().orElseThrow();
                if (operation.startsWith("add-")) track.getUserData().put(70, "Test event");
                else track.getUserData().remove(70);
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
