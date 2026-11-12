"""Find and connect to an already-running, verified Cubism Editor process."""
from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
from tempfile import TemporaryDirectory

from .build import PROTOCOL_VERSION, adapter_sources, build, build_lock, jdk_bin
from .client import call
from .editor_versions import EditorBuild, file_digest, verify_editor
from .settings import ROOT, bridge_port, editor_home, state_directory, windows_powershell, workspace, write_json

PROCESS_QUERY = r"""
$ErrorActionPreference = 'Stop'
$javaPaths = @(
    (Join-Path $env:CUBISM53_EDITOR_HOME 'app\jre\bin\java.exe'),
    (Join-Path $env:CUBISM53_EDITOR_HOME 'app\jre\bin\javaw.exe')
)
$matches = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" |
    Where-Object { $_.ExecutablePath -in $javaPaths -and
        $_.CommandLine -cmatch '(?:^|\s)com\.live2d\.cubism\.CECubismEditorApp(?:\s|$)' } |
    Select-Object -ExpandProperty ProcessId)
ConvertTo-Json -InputObject $matches -Compress
"""


def requested_pid() -> int | None:
    selected = os.environ.get('CUBISM53_EDITOR_PID')
    if selected is None:
        return None
    pid = int(selected)
    if pid <= 0:
        raise ValueError('CUBISM53_EDITOR_PID must be a positive process ID')
    return pid


def editor_pid(home: Path, selected: int | None) -> int:
    result = subprocess.run([windows_powershell(), '-NoProfile', '-Command', PROCESS_QUERY],
                            check=True, capture_output=True, text=True, timeout=15,
                            env={**os.environ, 'CUBISM53_EDITOR_HOME': str(home)})
    pids = json.loads(result.stdout)
    if selected is not None:
        if selected not in pids:
            raise RuntimeError(f'PID {selected} does not match the configured Cubism Editor')
        return selected
    if len(pids) != 1:
        detail = 'Open Cubism Editor first.' if not pids else 'Set CUBISM53_EDITOR_PID to the intended editor PID.'
        raise RuntimeError(f'Expected one running Cubism editor from the selected installation; found {len(pids)}. {detail}')
    return pids[0]


def attach(pid: int, agent: Path, bin_path: Path, config: dict) -> None:
    # Windows Attach passes its option through a narrow-character channel.
    directory = Path(os.environ.get('CUBISM53_ATTACH_DIR') or os.environ['LOCALAPPDATA']).resolve()
    with TemporaryDirectory(prefix='Cubism53MCP-', dir=directory) as temporary:
        options = Path(temporary) / 'config.json'
        write_json(options, config)
        encoded = base64.urlsafe_b64encode(str(options).encode("utf8")).decode('ascii')
        subprocess.run([str(bin_path / 'java.exe'), '--add-modules', 'jdk.attach', '-cp', str(agent),
                        'cubism53.mcp.Attach', str(pid), str(agent), 'b64:' + encoded],
                       check=True, stdout=sys.stderr, timeout=25)


def packaged_adapter(editor: EditorBuild) -> dict | None:
    metadata = ROOT / 'portable.json'
    if not metadata.is_file():
        return None
    manifest = json.loads(metadata.read_text(encoding='utf8'))
    adapter = manifest['adapters'].get(editor.sha256)
    if adapter is None or adapter['version'] != editor.version or not adapter.get('build_id'):
        raise RuntimeError(f'Portable bundle is missing the verified Cubism {editor.version} adapter')
    return adapter


def portable_runtime(editor: EditorBuild) -> tuple[Path, Path] | None:
    adapter = packaged_adapter(editor)
    if adapter is None:
        return None
    agent = ROOT / 'adapters' / f'{editor.version}.jar'
    if file_digest(agent) != adapter['sha256']:
        raise RuntimeError('Portable bridge JAR checksum mismatch; unpack the release again')
    bin_path = ROOT.parent / 'runtime/java/bin'
    if not (bin_path / 'java.exe').is_file():
        raise FileNotFoundError('Portable Java Attach runtime is missing; unpack the release again')
    return bin_path, agent


def bridge_identity(editor: EditorBuild, pid: int, *, timeout: float = 5) -> dict:
    try:
        status = call('bridge_info', response_timeout=timeout)
    except RuntimeError as error:
        if ('Unsupported operation: bridge_info' in str(error)
                or 'Explicit document_uid required' in str(error)):
            raise RuntimeError('Older bridge is running. Use stop from the old package, then reconnect.') from error
        raise
    if status.get('editor_pid') != pid:
        raise RuntimeError(f'Bridge belongs to editor PID {status.get("editor_pid")}, expected {pid}')
    if status.get('editor_sha256') != editor.sha256:
        raise RuntimeError('Bridge uses a different Cubism editor build')
    adapter = packaged_adapter(editor)
    expected = adapter['build_id'] if adapter is not None else adapter_sources(editor)[1]
    if status.get('bridge_protocol') != PROTOCOL_VERSION or status.get('adapter_build_id') != expected:
        raise RuntimeError('Bridge adapter build differs. Use stop from the old package, then reconnect.')
    return status


def connect_editor() -> dict:
    selected = requested_pid()
    home = editor_home()
    editor = verify_editor(home)
    with build_lock():
        pid = editor_pid(home, selected)
        try:
            bridge_identity(editor, pid)
        except (FileNotFoundError, ConnectionRefusedError):
            pass
        else:
            return {'connected': True, 'already_connected': True, **call('status')}
        port = bridge_port()
        with socket.socket() as check:
            check.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            try:
                check.bind(('127.0.0.1', port))
            except OSError as error:
                raise RuntimeError(f'Loopback port {port} is unavailable. Set a separate CUBISM53_BRIDGE_PORT.') from error
        packaged = portable_runtime(editor)
        if packaged is None:
            bin_path = jdk_bin()
            agent = build(home, bin_path, editor)
        else:
            bin_path, agent = packaged
        config = {'workspace': str(workspace()), 'editor_jar': str(home / 'app/lib/Live2D_Cubism.jar'),
                  'port': port, 'token': secrets.token_urlsafe(32), 'adapter_jar': str(agent)}
        write_json(state_directory() / 'config.json', config)
        attach(pid, agent, bin_path, config)
        bridge_identity(editor, pid)
        return {'connected': True, 'attached_pid': pid, **call('status')}


if __name__ == '__main__':
    print(json.dumps(connect_editor(), ensure_ascii=True, indent=2))
