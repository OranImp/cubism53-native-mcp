"""Find and connect to an already-running, verified Cubism Editor process."""
from __future__ import annotations

import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
from tempfile import TemporaryDirectory

from .build import build, build_lock, jdk_bin
from .client import call
from .settings import bridge_port, editor_home, state_directory, workspace, write_json

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
    result = subprocess.run(['powershell.exe', '-NoProfile', '-Command', PROCESS_QUERY],
                            check=True, capture_output=True, text=True, timeout=15,
                            env={**os.environ, 'CUBISM53_EDITOR_HOME': str(home)})
    pids = json.loads(result.stdout)
    if selected is not None:
        if selected not in pids:
            raise RuntimeError(f'PID {selected} does not match the configured Cubism Editor')
        return selected
    if len(pids) != 1:
        detail = 'Open Cubism Editor first.' if not pids else 'Set CUBISM53_EDITOR_PID to the intended editor PID.'
        raise RuntimeError(f'Expected one running Cubism 5.3.03 editor; found {len(pids)}. {detail}')
    return pids[0]


def attach(pid: int, agent: Path, bin_path: Path, config: dict) -> None:
    # The Windows JDK 17 Attach channel can mangle non-ASCII agent options.
    # Only the temporary JSON path goes through that channel.
    directory = Path(os.environ.get('CUBISM53_ATTACH_DIR', os.environ['LOCALAPPDATA'])).resolve()
    if not str(directory).isascii():
        raise ValueError('Set CUBISM53_ATTACH_DIR to an existing writable directory with an ASCII path')
    with TemporaryDirectory(prefix='Cubism53MCP-', dir=directory) as temporary:
        options = Path(temporary) / 'config.json'
        write_json(options, config)
        subprocess.run([str(bin_path / 'java.exe'), '--add-modules', 'jdk.attach', '-cp', str(agent),
                        'cubism53.mcp.Attach', str(pid), str(agent), str(options)],
                       check=True, stdout=sys.stderr, timeout=25)


def connect_editor() -> dict:
    selected = requested_pid()
    with build_lock():
        try:
            status = call('status')
        except (FileNotFoundError, ConnectionRefusedError):
            pass
        else:
            if selected is not None and status['editor_pid'] != selected:
                raise RuntimeError('This bridge state is connected to a different editor PID')
            return {'connected': True, 'already_connected': True, **status}
        port = bridge_port()
        with socket.socket() as check:
            check.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            try:
                check.bind(('127.0.0.1', port))
            except OSError as error:
                raise RuntimeError(f'Loopback port {port} is unavailable. Set a separate CUBISM53_BRIDGE_PORT.') from error
        home = editor_home()
        pid = editor_pid(home, selected)
        bin_path = jdk_bin()
        agent = build(home, bin_path)
        config = {'workspace': str(workspace()), 'editor_jar': str(home / 'app/lib/Live2D_Cubism.jar'),
                  'port': port, 'token': secrets.token_urlsafe(32), 'adapter_jar': str(agent)}
        write_json(state_directory() / 'config.json', config)
        attach(pid, agent, bin_path, config)
        return {'connected': True, 'attached_pid': pid, **call('status')}


if __name__ == '__main__':
    print(json.dumps(connect_editor(), ensure_ascii=True, indent=2))
