"""Run against an isolated, already-running Cubism Editor with no open documents."""
from __future__ import annotations

import asyncio
import base64
import json
import os
from pathlib import Path
import subprocess
import sys
from uuid import uuid4

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

ROOT = Path(__file__).resolve().parents[1]

# A tiny opaque RGB PNG, embedded so the fixture requires no artwork or imaging dependency.
PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAI0lEQVR4nGO8G6XFQApgIkk1w6gG4gATkergYFQDMYDkUAIAa00Bgc+odncAAAAASUVORK5CYII=')


async def main():
    for name in ('CUBISM53_EDITOR_PID', 'CUBISM53_WORKSPACE', 'CUBISM53_BRIDGE_STATE'):
        if not os.environ.get(name):
            raise ValueError(f'Set {name} to an isolated test configuration')
    workspace = Path(os.environ['CUBISM53_WORKSPACE']).resolve(strict=True)
    folder = workspace / f'smoke-{uuid4().hex[:8]}'
    folder.mkdir()
    (folder / 'source.png').write_bytes(PNG)
    relative = lambda name: (folder / name).relative_to(workspace).as_posix()
    env = {key: value for key, value in os.environ.items() if key.startswith('CUBISM53_')}
    env['LOCALAPPDATA'] = os.environ['LOCALAPPDATA']
    options = StdioServerParameters(command=sys.executable, args=[str(ROOT / 'server.py')], env=env)
    async with stdio_client(options) as (reader, writer), ClientSession(reader, writer) as session:
        await session.initialize()
        listing = await session.list_tools()
        assert len({tool.name for tool in listing.tools}) == 75
        async def invoke(operation, **arguments):
            result = await session.call_tool('cubism_native_' + operation, arguments)
            if result.isError:
                raise RuntimeError(result.content[0].text)
            return result.structuredContent

        async def rejected(operation, fragment, **arguments):
            result = await session.call_tool('cubism_native_' + operation, arguments)
            assert result.isError and fragment in result.content[0].text, result

        first, second = await asyncio.gather(invoke('connect'), invoke('connect'))
        assert first['editor_thread'] and second['editor_thread']
        assert sum(bool(item.get('already_connected')) for item in (first, second)) == 1, (first, second)
        assert not first['documents'], 'Start the smoke test with an empty editor'
        await rejected('document', 'Document not found', document_uid='missing')
        created = await invoke('create_blank_project', width=256, height=256)
        uid = created['document_uid']
        assert (created['canvas_width'], created['canvas_height']) == (256, 256)
        warp = await invoke('create_warp', document_uid=uid, name='Smoke Warp', x=10, y=10, width=100, height=100)
        assert not (await invoke('undo', document_uid=uid))['deformers']
        assert (await invoke('redo', document_uid=uid))['deformers'] == [warp]
        for name in ('Mask', 'Masked'):
            await invoke('import_png', document_uid=uid, name=name, path=relative('source.png'),
                         affine=[40, 0, 0, 40, 20, 20])
        items = (await invoke('objects', document_uid=uid))['objects']
        by_name = {item['name']: item for item in items}
        mask, masked = (by_name[name]['guid'] for name in ('Mask', 'Masked'))
        await invoke('set_clipping', document_uid=uid, object_guid=masked, mask_guids=[mask])
        data = (await invoke('inspect_model_dependencies', document_uid=uid, kind='object', id=mask))['objects'][0]
        assert data['mask_user_guids'] == [masked]
        assert data['material_user_guids'] == [mask]
        assert not data['glue']
        data = (await invoke('inspect_model_dependencies', document_uid=uid, kind='material',
                             id=data['material_guid']))['objects']
        assert [item['guid'] for item in data] == [mask]
        await rejected('save_as_new', 'workspace destination', document_uid=uid, path='../outside.cmo3')
        junction = folder / 'outside'
        outside = workspace.parent
        subprocess.run(['cmd.exe', '/d', '/c', 'mklink', '/J', str(junction), str(outside)],
                       check=True, capture_output=True)
        try:
            await rejected('save_as_new', 'outside the workspace', document_uid=uid, path=relative('outside/escaped.cmo3'))
        finally:
            junction.rmdir()
        await invoke('render_png', document_uid=uid, path=relative('native.png'), width=256, height=256)
        assert (folder / 'native.png').read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
        saved = await invoke('save_as_new', document_uid=uid, path=relative('scratch.cmo3'))
        assert saved['saved'] and saved['bytes'] > 0
        await rejected('save_as_new', 'FileAlreadyExistsException', document_uid=uid, path=relative('scratch.cmo3'))
        await invoke('close_saved', document_uid=uid)
        await invoke('open_project', path=relative('scratch.cmo3'))
        for _ in range(30):
            status = await invoke('status')
            if status['documents']:
                reopened = status['documents'][0]
                assert reopened['drawables'] == 2 and reopened['deformers'] == [warp]
                await invoke('close_saved', document_uid=reopened['document_uid'])
                break
            await asyncio.sleep(0.2)
        else:
            raise AssertionError('Saved model did not reopen')
        print(json.dumps({'native_smoke': 'passed', 'tools': len(listing.tools),
                          'fixture': str(folder), 'no_documents_left': not (await invoke('status'))['documents']}))


if __name__ == '__main__':
    asyncio.run(main())
