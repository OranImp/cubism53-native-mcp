"""Paths and ports for one standalone bridge configuration."""
from __future__ import annotations

import json
import os
from pathlib import Path
from tempfile import NamedTemporaryFile

ROOT = Path(__file__).resolve().parents[1]


def workspace() -> Path:
    path = Path(os.environ.get('CUBISM53_WORKSPACE', ROOT)).resolve(strict=True)
    if not path.is_dir():
        raise NotADirectoryError(path)
    return path


def state_directory() -> Path:
    return Path(os.environ.get('CUBISM53_BRIDGE_STATE', ROOT / '.state')).resolve()


def port_from_env(name: str, default: int) -> int:
    port = int(os.environ.get(name, default))
    if not 1 <= port <= 65535:
        raise ValueError(f'{name} must be between 1 and 65535')
    return port


def bridge_port() -> int:
    return port_from_env('CUBISM53_BRIDGE_PORT', 22055)


def editor_home() -> Path:
    configured = os.environ.get('CUBISM53_EDITOR_HOME')
    candidates = [Path(configured)] if configured else [
        Path(directory) / name
        for directory in (os.environ.get('ProgramFiles'), os.environ.get('ProgramW6432'))
        if directory
        for name in ('Live2D Cubism 5.3', 'Live2D Cubism')
    ]
    for candidate in candidates:
        if (candidate / 'app/lib/Live2D_Cubism.jar').is_file():
            return candidate.resolve()
    raise FileNotFoundError('Set CUBISM53_EDITOR_HOME to a supported Cubism installation directory')


def windows_powershell() -> str:
    system = Path(os.environ.get('SystemRoot', 'C:/Windows'))
    executable = system / 'System32/WindowsPowerShell/v1.0/powershell.exe'
    return str(executable) if executable.is_file() else 'powershell.exe'


def write_text_atomic(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    stream = NamedTemporaryFile(dir=path.parent, encoding='utf8', mode='w', delete=False)
    pending = Path(stream.name)
    try:
        with stream:
            stream.write(value)
        pending.replace(path)
    finally:
        pending.unlink(missing_ok=True)


def write_json(path: Path, value: dict) -> None:
    write_text_atomic(path, json.dumps(value, ensure_ascii=False, indent=2))
