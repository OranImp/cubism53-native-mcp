"""Build the adapter against a locally installed Cubism 5.3.03 editor."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory
import time

from .settings import ROOT, editor_home, state_directory

SUPPORTED_EDITOR_SHA256 = 'bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166'


@contextmanager
def build_lock():
    import msvcrt

    state = state_directory()
    state.mkdir(parents=True, exist_ok=True)
    with (state / 'build.lock').open('a+b') as stream:
        deadline = time.monotonic() + 120
        while True:
            stream.seek(0)
            try:
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                break
            except OSError as error:
                if time.monotonic() >= deadline:
                    raise TimeoutError('Timed out waiting for the bridge build or attach') from error
                time.sleep(0.1)
        try:
            yield
        finally:
            stream.seek(0)
            msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)


def jdk_bin() -> Path:
    configured = os.environ.get('CUBISM53_JDK_HOME')
    homes = (configured,) if configured else (os.environ.get('JAVA_HOME'),)
    candidates = [Path(home) / 'bin' for home in homes if home]
    if not configured and (javac := shutil.which('javac')):
        candidates.append(Path(javac).parent)
    for bin_path in candidates:
        if all((bin_path / exe).is_file() for exe in ('javac.exe', 'java.exe', 'jar.exe')):
            check = subprocess.run([str(bin_path / 'javac.exe'), '-version'],
                                   capture_output=True, text=True, timeout=10)
            if check.returncode == 0 and re.match(r'javac 17(?:[.\s]|$)', check.stdout.strip()):
                return bin_path
    raise FileNotFoundError('Set CUBISM53_JDK_HOME to a JDK 17 installation, or put JDK 17 on PATH')


def verify_editor(home: Path) -> str:
    libraries = home / 'app/lib'
    with (libraries / 'Live2D_Cubism.jar').open('rb') as stream:
        editor_digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if editor_digest != SUPPORTED_EDITOR_SHA256:
        raise RuntimeError(f'Unsupported Cubism editor JAR for the 5.3.03 adapter: {editor_digest}')
    return editor_digest


def build(home: Path, bin_path: Path) -> Path:
    verify_editor(home)
    libraries = home / 'app/lib'
    sources = sorted((ROOT / 'java').glob('*.java'))
    manifest = 'Manifest-Version: 1.0\nAgent-Class: cubism53.mcp.BootstrapV3\n\n'
    source_digest = hashlib.sha256(manifest.encode('ascii'))
    for source in sources:
        source_digest.update(source.name.encode('ascii') + b'\0' + source.read_bytes() + b'\0')
    state = state_directory()
    agent = state / f'cubism53-mcp-{source_digest.hexdigest()[:16]}.jar'
    if agent.is_file():
        return agent
    with TemporaryDirectory(prefix='build-', dir=state) as temporary:
        folder = Path(temporary)
        classes = folder / 'classes'
        classes.mkdir()
        classpath = os.pathsep.join([str(libraries / '*'), str(libraries / 'jogl/*')])
        subprocess.run([str(bin_path / 'javac.exe'), '--release', '17', '-encoding', 'UTF-8',
                        '-classpath', classpath, '-d', str(classes), *map(str, sources)],
                       check=True, stdout=sys.stderr, timeout=60)
        manifest_path = folder / 'MANIFEST.MF'
        manifest_path.write_text(manifest, encoding='ascii', newline='\n')
        package = folder / agent.name
        subprocess.run([str(bin_path / 'jar.exe'), '--create', '--file', str(package),
                        '--manifest', str(manifest_path), '-C', str(classes), '.'],
                       check=True, stdout=sys.stderr, timeout=15)
        package.replace(agent)
    return agent


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    with build_lock():
        agent = build(editor_home(), jdk_bin())
    print(json.dumps({'agent_jar': str(agent), 'editor_version': '5.3.03'}))


if __name__ == '__main__':
    main()
