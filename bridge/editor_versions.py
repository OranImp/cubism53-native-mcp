"""Exact Windows editor builds and their version-specific Java symbols."""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
from pathlib import Path


@dataclass(frozen=True)
class EditorBuild:
    version: str
    sha256: str
    evaluation_options: str
    mesh_editor: str

    def adapt_source(self, name: str, text: str) -> str:
        if name != 'NativeBridge.java':
            return text
        return text.replace('com.live2d.cubism.doc.model.ax', self.evaluation_options).replace(
            'com.live2d.cubism.view.context.action.action_meshEditor.p.', self.mesh_editor + '.')


BUILDS = (
    EditorBuild('5.3.03', 'bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166',
                'com.live2d.cubism.doc.model.ax', 'com.live2d.cubism.view.context.action.action_meshEditor.p'),
    EditorBuild('5.3.04', 'c9bb3e9960bf49908a531b7010421c393a8dd5f9376745eb6fdb273f41a1cf5d',
                'com.live2d.cubism.doc.model.az', 'com.live2d.cubism.view.context.action.action_meshEditor.r'),
)
BY_HASH = {build.sha256: build for build in BUILDS}
SUPPORTED_VERSIONS = ', '.join(build.version for build in BUILDS)


def file_digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def verify_editor(home: Path) -> EditorBuild:
    digest = file_digest(home / 'app/lib/Live2D_Cubism.jar')
    if digest not in BY_HASH:
        raise RuntimeError(f'Unsupported Cubism editor JAR: {digest}. Supported Windows builds: {SUPPORTED_VERSIONS}')
    return BY_HASH[digest]
