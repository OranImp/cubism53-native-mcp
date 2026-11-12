"""Build a Windows x64 ZIP with an adapter for every verified editor build."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from bridge.build import adapter_sources, build, build_lock, jdk_bin
from bridge.editor_versions import BUILDS, verify_editor

PYTHON_VERSION = '3.13.17'
PYTHON_SHA256 = '9e35319bffedab8b36ad44d8f377cb4f1733bcec257a0b6d04d94f01486ddb38'
JAVA_VERSION = '17.0.19+10'


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def build_package(options):
    python_zip = options.python_zip.resolve(strict=True)
    wheelhouse = options.wheelhouse.resolve(strict=True)
    editors = {}
    for path in options.editor_home:
        home = path.resolve(strict=True)
        editor = verify_editor(home)
        if editor.version in editors:
            raise ValueError(f'Editor version supplied twice: {editor.version}')
        editors[editor.version] = (editor, home)
    missing = [editor.version for editor in BUILDS if editor.version not in editors]
    if missing:
        raise ValueError('Supply --editor-home once for each missing build: ' + ', '.join(missing))
    output = options.output.resolve()
    if output.suffix.lower() != '.zip':
        raise ValueError('The package destination must end in .zip')
    if output.exists() or output.with_suffix('.zip.sha256').exists():
        raise FileExistsError('Choose a new ZIP destination')
    if digest(python_zip) != PYTHON_SHA256:
        raise ValueError('CPython embeddable archive does not match the pinned SHA-256')
    if options.jdk_home:
        os.environ['CUBISM53_JDK_HOME'] = str(options.jdk_home.resolve(strict=True))
    jdk = jdk_bin()
    release = (jdk.parent / 'release').read_text(encoding='utf8')
    for item in ('IMPLEMENTOR="Eclipse Adoptium"', f'JAVA_RUNTIME_VERSION="{JAVA_VERSION}"', 'OS_ARCH="x86_64"'):
        if item not in release.splitlines():
            raise RuntimeError(f'Build with Windows x64 Eclipse Temurin {JAVA_VERSION}')
    output.parent.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(prefix='cubism-portable-', dir=output.parent) as temporary:
        scratch = Path(temporary)
        folder = scratch / 'Cubism53-Native-MCP'
        app = folder / 'app'
        runtime = folder / 'runtime'
        python = runtime / 'python'
        python.mkdir(parents=True)
        with zipfile.ZipFile(python_zip) as archive:
            archive.extractall(python)
        (python / 'python313._pth').write_text(
            'python313.zip\n.\n../../app\nLib/site-packages\nimport site\n', encoding='ascii')
        subprocess.run([sys.executable, '-m', 'pip', 'install', '--no-index', '--no-deps',
            '--require-hashes', '--only-binary=:all:', '--platform', 'win_amd64',
            '--implementation', 'cp', '--python-version', '3.13', '--abi', 'cp313',
            '--no-compile', '--target', str(python / 'Lib/site-packages'), '--find-links', str(wheelhouse),
            '-r', str(ROOT / 'portable/wheels-win-x64.txt')], check=True)
        # pip writes entry-point scripts with build-host interpreter paths.
        # The portable entry point uses python.exe directly.
        scripts = python / 'Lib/site-packages/bin'
        if scripts.is_dir():
            if not scripts.resolve(strict=True).is_relative_to(scratch.resolve(strict=True)):
                raise RuntimeError('Unexpected wheel script directory')
            shutil.rmtree(scripts)
        subprocess.run([str(jdk / 'jlink.exe'), '--add-modules', 'jdk.attach', '--strip-debug',
            '--no-header-files', '--no-man-pages', '--compress', '2', '--output', str(runtime / 'java')],
            check=True)
        shutil.copyfile(jdk.parent / 'NOTICE', runtime / 'java/NOTICE')
        os.environ['CUBISM53_BRIDGE_STATE'] = str(scratch / 'build-state')
        (app / 'adapters').mkdir(parents=True)
        adapters = {}
        with build_lock():
            for editor, home in editors.values():
                agent = build(home, jdk, editor)
                shutil.copyfile(agent, app / 'adapters' / f'{editor.version}.jar')
                adapters[editor.sha256] = {'version': editor.version, 'sha256': digest(agent),
                                          'build_id': adapter_sources(editor)[1]}
        (app / 'portable.json').write_text(json.dumps({'adapters': adapters,
            'python_version': PYTHON_VERSION, 'java_version': JAVA_VERSION}, indent=2), encoding='utf8')
        for path in (ROOT / 'bridge').glob('*.py'):
            (app / 'bridge').mkdir(exist_ok=True)
            shutil.copyfile(path, app / 'bridge' / path.name)
        for name in ('server.py', 'launcher.py'):
            shutil.copyfile(ROOT / name, app / name)
        for source, name in (('portable/Setup.cmd', 'Setup.cmd'), ('LICENSE', 'LICENSE'),
                             ('README.md', 'README.md'), ('portable/THIRD_PARTY.md', 'THIRD_PARTY.md')):
            shutil.copyfile(ROOT / source, folder / name)
        subprocess.run([str(python / 'python.exe'), '-B', '-c', 'import mcp, pydantic, websockets, win32api; import server'],
                       cwd=folder, check=True)
        with zipfile.ZipFile(output, 'x', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for path in sorted(folder.rglob('*')):
                if path.is_file():
                    archive.write(path, path.relative_to(scratch).as_posix())
    output.with_suffix('.zip.sha256').write_text(f'{digest(output)}  {output.name}\n', encoding='ascii')
    print(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('python-zip', 'wheelhouse', 'output'):
        parser.add_argument(f'--{option}', type=Path, required=True)
    parser.add_argument('--editor-home', type=Path, required=True, action='append')
    parser.add_argument('--jdk-home', type=Path)
    build_package(parser.parse_args())


if __name__ == '__main__':
    main()
