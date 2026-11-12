"""Configure the portable installation and start the common MCP stdio entry."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import winreg

from bridge.build import verify_editor
from bridge.connect import editor_pid, portable_runtime, requested_pid
from bridge.settings import ROOT, windows_powershell, write_json, write_text_atomic

SERVER_NAME = 'cubism53-native-mcp'
PROFILE_ID = re.compile(r'[A-Za-z0-9_-]{1,40}')


def editor_candidates():
    query = r"""Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" |
        Where-Object { $_.CommandLine -match 'com\.live2d\.cubism\.CECubismEditorApp' } |
        ForEach-Object { $_.ExecutablePath }"""
    try:
        result = subprocess.run([windows_powershell(), '-NoProfile', '-Command',
            '[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false); ' + query],
            capture_output=True, encoding='utf8', check=True, timeout=10)
        for path in result.stdout.splitlines():
            if path.strip():
                yield Path(path.strip()).parents[3]
    except (OSError, subprocess.SubprocessError):
        pass

    uninstall = r'Software\Microsoft\Windows\CurrentVersion\Uninstall'
    for hive in (winreg.HKEY_CURRENT_USER, winreg.HKEY_LOCAL_MACHINE):
        for subkey in (uninstall, uninstall.replace(r'Software', r'Software\WOW6432Node', 1)):
            try:
                with winreg.OpenKey(hive, subkey) as entries:
                    for i in range(winreg.QueryInfoKey(entries)[0]):
                        try:
                            with winreg.OpenKey(entries, winreg.EnumKey(entries, i)) as item:
                                name = winreg.QueryValueEx(item, 'DisplayName')[0]
                                if 'cubism' in name.casefold():
                                    location = winreg.QueryValueEx(item, 'InstallLocation')[0]
                                    if location:
                                        yield Path(location)
                        except OSError:
                            continue
            except OSError:
                continue
    for variable in ('ProgramFiles', 'ProgramW6432', 'ProgramFiles(x86)'):
        if location := os.environ.get(variable):
            yield from sorted(Path(location).glob('Live2D Cubism*'))


def select_editor(configured=None):
    if configured:
        path = Path(configured).expanduser().resolve(strict=True)
        verify_editor(path)
        return path
    for candidate in editor_candidates():
        try:
            path = candidate.resolve(strict=True)
            verify_editor(path)
            return path
        except (OSError, RuntimeError):
            continue
    raise FileNotFoundError('Supported Cubism 5.3.03 was not found. Provide its installation folder with --editor-home.')


def profile_state(path, settings):
    identity = [settings[key] for key in ('editor_home', 'workspace', 'bridge_port')]
    key = hashlib.sha256(json.dumps(identity).encode('utf8')).hexdigest()[:16]
    return path.parent / 'state' / key


def load_profile(path):
    path = Path(path).resolve(strict=True)
    profile = json.loads(path.read_text(encoding='utf8'))
    if profile['version'] != 1:
        raise ValueError('Unsupported profile version')
    state = profile_state(path, profile)
    state.mkdir(parents=True, exist_ok=True)
    state = state.resolve(strict=True)
    env = {
        'CUBISM53_EDITOR_HOME': profile['editor_home'],
        'CUBISM53_WORKSPACE': profile['workspace'],
        'CUBISM53_BRIDGE_STATE': str(state),
        'CUBISM53_BRIDGE_PORT': str(profile['bridge_port']),
        'CUBISM53_PORT': str(profile['preview_port']),
        'CUBISM53_ATTACH_DIR': str(state),
    }
    os.environ.update(env)
    return path, profile


def quote_powershell(value):
    return "'" + value.replace("'", "''") + "'"


def export_config(path):
    command = Path(sys.executable).resolve().as_posix()
    args = ['-B', (ROOT / 'launcher.py').as_posix(), 'serve', '--profile-file', path.as_posix()]
    spec = {'type': 'stdio', 'command': command, 'args': args}
    output = path.parent / 'client-configs'
    write_json(output / 'mcp.json', {'mcpServers': {SERVER_NAME: spec}})
    write_json(output / 'cc-switch.json', spec)
    plugin = {'id': SERVER_NAME, 'name': '@deepseek-ai/dsh-mcp-client', 'config': {
        'serverName': SERVER_NAME, 'transport': 'stdio', 'command': command, 'args': args,
        'toolCallTimeoutMs': 120000}}
    # JSON is accepted by the DSH YAML reader and preserves Windows paths exactly.
    write_text_atomic(output / 'dsh.patch.yml',
        json.dumps([{'insert': [plugin]}], ensure_ascii=False, indent=2) + '\n')
    write_text_atomic(output / 'codex.ps1', 'codex mcp add ' + SERVER_NAME + ' -- '
        + ' '.join(quote_powershell(value) for value in (command, *args)) + '\n')
    return output


def port(value):
    value = int(value)
    if not 1 <= value <= 65535:
        raise argparse.ArgumentTypeError('port must be between 1 and 65535')
    return value


def setup(args):
    if not PROFILE_ID.fullmatch(args.profile):
        raise ValueError('Profile must have 1–40 ASCII letters, digits, underscores or hyphens')
    folder = (Path(args.profile_root or os.environ['LOCALAPPDATA']) /
              'Cubism53MCP' / 'profiles' / args.profile).resolve()
    path = folder / 'settings.json'
    old = json.loads(path.read_text(encoding='utf8')) if path.is_file() else {}
    try:
        editor = select_editor(args.editor_home or old.get('editor_home'))
    except (OSError, RuntimeError):
        if args.non_interactive or args.editor_home:
            raise
        print('Cubism 5.3.03 was not found automatically. Enter its installation folder.')
        editor = select_editor(input('Cubism folder: ').strip().strip('"'))
    work = args.workspace or old.get('workspace')
    if not work:
        if args.non_interactive:
            raise ValueError('--workspace is required for a new non-interactive profile')
        work = input('Model workspace folder: ').strip().strip('"')
    workspace = Path(work).expanduser().resolve(strict=True)
    if not workspace.is_dir():
        raise NotADirectoryError(workspace)
    bridge_port = args.bridge_port or old.get('bridge_port', 22055)
    preview_port = args.preview_port or old.get('preview_port', 22033)
    if bridge_port == preview_port:
        raise ValueError('Native bridge and preview ports must differ')
    settings = {'version': 1, 'editor_home': str(editor), 'workspace': str(workspace),
                'bridge_port': bridge_port, 'preview_port': preview_port}
    portable_runtime()
    if old and profile_state(path, old) != profile_state(path, settings) and old['bridge_port'] == bridge_port:
        with socket.socket() as check:
            check.settimeout(1)
            if check.connect_ex(('127.0.0.1', bridge_port)) == 0:
                raise RuntimeError('Stop the active bridge before changing this profile, or choose a separate profile and port')
    write_json(path, settings)
    path = path.resolve(strict=True)
    output = export_config(path)
    if args.non_interactive:
        print(json.dumps({'profile': str(path), 'client_configs': str(output)}, ensure_ascii=True))
    else:
        print(f'Profile: {path}\nClient configurations: {output}')
    return 0


def doctor(profile):
    home = Path(profile['editor_home'])
    report = {'editor_sha256': verify_editor(home),
              'workspace': Path(profile['workspace']).is_dir(),
              'portable_runtime': portable_runtime() is not None}
    try:
        report['editor_pid'] = editor_pid(home, requested_pid())
    except RuntimeError as error:
        report['editor'] = str(error)
    with socket.socket() as check:
        check.settimeout(1)
        occupied = check.connect_ex(('127.0.0.1', profile['bridge_port'])) == 0
    report['bridge_port'] = 'available'
    if occupied:
        try:
            from bridge.client import call
            report['bridge_pid'] = call('status', response_timeout=3)['editor_pid']
            report['bridge_port'] = 'connected'
        except (OSError, ValueError, RuntimeError, KeyError):
            report['bridge_port'] = 'occupied or bridge did not respond'
    print(json.dumps(report, ensure_ascii=True))
    return 0 if report['workspace'] and report['bridge_port'] in ('available', 'connected') else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='operation', required=True)
    install = commands.add_parser('setup')
    install.add_argument('--profile', default='default')
    install.add_argument('--profile-root')
    install.add_argument('--workspace')
    install.add_argument('--editor-home')
    install.add_argument('--bridge-port', type=port)
    install.add_argument('--preview-port', type=port)
    install.add_argument('--non-interactive', action='store_true')
    for name in ('serve', 'doctor', 'stop'):
        commands.add_parser(name).add_argument('--profile-file', required=True)
    args = parser.parse_args()
    try:
        if args.operation == 'setup':
            return setup(args)
        _, profile = load_profile(args.profile_file)
        if args.operation == 'doctor':
            return doctor(profile)
        if args.operation == 'stop':
            from bridge.client import call
            print(json.dumps(call('stop')))
            return 0
        import server
        server.mcp.run(transport='stdio')
        return 0
    except (OSError, ValueError, RuntimeError, KeyError) as error:
        print(f'Cubism53 MCP: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
