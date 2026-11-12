import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from bridge.build import PROTOCOL_VERSION
from bridge.connect import bridge_identity, portable_runtime
from bridge.editor_versions import BUILDS, file_digest, verify_editor


class EditorVersionsTest(unittest.TestCase):
    def test_identifies_supported_hashes_and_rejects_unknown(self):
        for build in BUILDS:
            with self.subTest(build=build.version), patch('bridge.editor_versions.file_digest', return_value=build.sha256):
                self.assertEqual(verify_editor(Path('editor')), build)
        with patch('bridge.editor_versions.file_digest', return_value='0' * 64):
            with self.assertRaisesRegex(RuntimeError, 'Unsupported Cubism editor JAR'):
                verify_editor(Path('editor'))

    def test_portable_runtime_selects_and_checks_the_requested_adapter(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            app = root / 'app'
            (app / 'adapters').mkdir(parents=True)
            java = root / 'runtime/java/bin'
            java.mkdir(parents=True)
            (java / 'java.exe').touch()
            adapters = {}
            for build in BUILDS:
                agent = app / 'adapters' / f'{build.version}.jar'
                agent.write_bytes(build.version.encode('ascii'))
                adapters[build.sha256] = {'version': build.version, 'sha256': file_digest(agent), 'build_id': build.sha256}
            (app / 'portable.json').write_text(json.dumps({'adapters': adapters}), encoding='utf8')
            with patch('bridge.connect.ROOT', app):
                for build in BUILDS:
                    binary, agent = portable_runtime(build)
                    self.assertEqual(binary, java)
                    self.assertEqual(agent.name, f'{build.version}.jar')
                agent.write_bytes(b'unexpected adapter bytes')
                with self.assertRaisesRegex(RuntimeError, 'JAR checksum mismatch'):
                    portable_runtime(build)

    def test_bridge_identity_requires_pid_protocol_and_adapter(self):
        editor = BUILDS[0]
        status = {'editor_pid': 42, 'editor_sha256': editor.sha256, 'bridge_protocol': PROTOCOL_VERSION,
                  'adapter_build_id': 'verified-id'}
        with patch('bridge.connect.packaged_adapter', return_value={'build_id': 'verified-id'}), \
                patch('bridge.connect.call', return_value=status) as client:
            self.assertEqual(bridge_identity(editor, 42), status)
            with self.assertRaisesRegex(RuntimeError, 'expected 43'):
                bridge_identity(editor, 43)
            for mismatch in ({'bridge_protocol': 1}, {'adapter_build_id': 'old'}):
                client.return_value = {**status, **mismatch}
                with self.assertRaisesRegex(RuntimeError, 'adapter build differs'):
                    bridge_identity(editor, 42)
            client.side_effect = RuntimeError('Explicit document_uid required')
            with self.assertRaisesRegex(RuntimeError, 'Older bridge is running'):
                bridge_identity(editor, 42)
