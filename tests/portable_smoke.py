"""Exercise an unpacked Windows portable bundle with a separate Cubism session."""
import argparse
import asyncio
import base64
from collections import Counter
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
from tempfile import TemporaryDirectory
import time

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from PIL import Image
from psd_tools import PSDImage
from psd_tools.api.layers import PixelLayer

ROOT = Path(__file__).resolve().parents[1]

def setup(portable, editor, root, env, port):
    arguments = [portable / 'Setup.cmd', '--non-interactive', '--profile-root', root / '配置 profile',
                 '--workspace', root / '模型 空间', '--editor-home', editor, '--bridge-port', port]
    command = '& ' + ' '.join("'" + str(value).replace("'", "''") + "'" for value in arguments)
    command += '; exit $LASTEXITCODE'
    encoded = base64.b64encode(command.encode('utf-16le')).decode('ascii')
    result = subprocess.run(['powershell.exe', '-NoProfile', '-EncodedCommand', encoded],
                            env=env, capture_output=True, text=True, timeout=20, check=True)
    report = json.loads(result.stdout)
    cfg = Path(report['client_configs'])
    spec = json.loads((cfg/'mcp.json').read_text(encoding='utf8'))['mcpServers']['cubism53-native-mcp']
    assert json.loads((cfg/'cc-switch.json').read_text(encoding='utf8')) == spec
    dsh = json.loads((cfg/'dsh.patch.yml').read_text(encoding='utf8'))[0]['insert'][0]
    assert dsh['name'] == '@deepseek-ai/dsh-mcp-client'
    assert all(dsh['config'][key] == spec[key] for key in ('command','args'))
    return report, spec

def fixture(folder):
    for i, color in enumerate(('red', 'green', 'blue'), 1):
        psd = PSDImage.new('RGBA', (256, 256), color=(0, 0, 0, 0))
        PixelLayer.frompil(Image.new('RGBA',(16,16),color), psd, name='Paint', left=80, top=80)
        if i == 1:
            PixelLayer.frompil(Image.new('RGBA',(16,16),'yellow'), psd, name='Old', left=120, top=80)
        psd.save(folder/f'revision-{i}.psd')

    for name, layers in [('unrelated.psd', ['Independent']), ('conflict.psd', ['Paint', 'Paint'])]:
        psd = PSDImage.new('RGBA', (256, 256), color=(0, 0, 0, 0))
        for index, layer in enumerate(layers):
            PixelLayer.frompil(Image.new('RGBA', (16, 16), 'gray'), psd, name=layer, left=80 + 20 * index, top=80)
        psd.save(folder / name)


def launch(editor, root, env):
    batch = (editor / 'CubismEditor5.bat').read_text(encoding='ascii', errors='ignore')
    classpath = re.search(r'^set CLASS_PATH=(.+)$', batch, re.M).group(1).strip()
    with (root/'editor.log').open('wb') as log:
        return subprocess.Popen([
            str(editor/'app/jre/bin/java.exe'), '-classpath', classpath,
            '-Djava.library.path=app/dll64;app/dll64/windows-amd64',
            '-Djogamp.gluegen.UseTempJarCache=false', '-Dsun.java2d.d3d=false',
            '-Duser.language=en', '-Djava.locale.providers=COMPAT,SPI',
            '-Duser.home='+str(root/'home'), '-Xmx2048m',
            '-Dhttp.proxyHost=127.0.0.1', '-Dhttp.proxyPort=1',
            '-Dhttps.proxyHost=127.0.0.1', '-Dhttps.proxyPort=1',
            'com.live2d.cubism.CECubismEditorApp',
        ], cwd=editor, env={**env,'APPDATA':str(root/'roaming'),'LOCALAPPDATA':str(root/'local'),
            'PATH':str(editor/'app/dll64')+os.pathsep+os.environ['PATH']},
            stdout=log, stderr=log, creationflags=subprocess.CREATE_NO_WINDOW)

async def seed_legacy(server, editor, root, editor_pid):
    with socket.socket() as spare:
        spare.bind(('127.0.0.1', 0))
        port = spare.getsockname()[1]
    options = StdioServerParameters(command=sys.executable, args=['-B', str(server)], env={
        'CUBISM53_EDITOR_HOME': str(editor), 'CUBISM53_EDITOR_PID': str(editor_pid),
        'CUBISM53_WORKSPACE': str(root / '模型 空间'), 'CUBISM53_BRIDGE_STATE': str(root / 'legacy-state'),
        'CUBISM53_BRIDGE_PORT': str(port),
    })
    async with stdio_client(options) as (reader, writer), ClientSession(reader, writer) as session:
        await session.initialize()

        async def call(op, **arguments):
            result = await session.call_tool('cubism_native_' + op, arguments)
            if result.isError:
                raise RuntimeError(result.content[0].text)
            return result.structuredContent

        await call('connect')
        revisions = {}
        for path, output in [('revision-2.psd', 'legacy.cmo3'), ('unrelated.psd', 'independent.cmo3')]:
            imported = await call('import_psd', path='revision-1.psd')
            uid = imported['document_uid']
            planned = await call('plan_psd_reimport', document_uid=uid, path=path)
            result = await call('reimport_psd', document_uid=uid, path=path, plan_id=planned['plan_id'])
            revisions[output] = result['new_source_psd_guid']
            assert (await call('save_as_new', document_uid=uid, path=output))['saved']
            await call('close_saved', document_uid=uid)
        return revisions


async def exercise(spec, env, work, legacy):
    config = StdioServerParameters(command=spec['command'], args=spec['args'], env={
        'PATH':env['PATH'], 'LOCALAPPDATA':env['LOCALAPPDATA'], 'CUBISM53_EDITOR_PID':env['CUBISM53_EDITOR_PID'],
        'PYTHONDONTWRITEBYTECODE':'1',
    })
    async with stdio_client(config) as (r,w), ClientSession(r,w) as mcp:
        await mcp.initialize()
        definitions = {tool.name: tool.model_dump(mode='json') for tool in (await mcp.list_tools()).tools}
        assert len(definitions) == 75
        async def invoke(op, *, reject=None, **kwargs):
            result = await mcp.call_tool('cubism_native_'+op, kwargs)
            if reject is not None:
                assert result.isError and reject.lower() in result.content[0].text.lower(), result.content[0].text
                return None
            if result.isError:
                raise RuntimeError(result.content[0].text)
            return result.structuredContent

        first, second = await asyncio.gather(invoke('connect'), invoke('connect'))
        assert first['editor_thread'] and second['editor_thread'] and not first['documents']
        assert sum(bool(item.get('already_connected')) for item in (first, second)) == 1
        async def reopen(path):
            await invoke('open_project', path=path)
            for _ in range(30):
                docs = (await invoke('status'))['documents']
                if docs:
                    return docs[0]['document_uid']
                await asyncio.sleep(.15)
            raise AssertionError('Reopen timed out')

        if legacy:
            uid = await reopen('legacy.cmo3')
            plan = await invoke('plan_psd_reimport', document_uid=uid, path='revision-3.psd')
            assert plan['source_psd_guid'] == legacy['legacy.cmo3']
            await invoke('close_saved', document_uid=uid)
            uid = await reopen('independent.cmo3')
            await invoke('plan_psd_reimport', document_uid=uid, path='revision-3.psd', reject='Select source_psd_guid')
            await invoke('plan_psd_reimport', document_uid=uid, path='revision-3.psd',
                         source_psd_guid=legacy['independent.cmo3'])
            await invoke('close_saved', document_uid=uid)
            print('LEGACY_AND_MULTIPLE_PSD_SOURCES_OK', flush=True)

        await invoke('status')
        doc = await invoke('create_blank_project',width=256,height=256)
        uid = doc['document_uid']
        async def current(op, **kwargs):
            return await invoke(op, document_uid=uid, **kwargs)

        a = (await current('create_part', name='A'))['guid']
        b = (await current('create_part', name='B',parent_part_guid=a))['guid']
        c = (await current('create_part', name='C'))['guid']
        def move(obj, **target):
            return {'operation':'move_to_part','arguments':{'object_guid':obj,**target}}
        valid = [move(b),move(a,part_guid=b)]
        invalid = [move(b),move(c,part_guid=a,index=1)]
        await current('edit_batch', mode='plan', edits=invalid, reject='part child index')
        await current('edit_batch', mode='plan', edits=[move(b,index=.5)], reject='integer')
        plan = await current('edit_batch',mode='plan',edits=valid)
        await current('edit_batch',mode='apply',edits=valid,plan_id=plan['plan_id'])
        assert (await current('object',object_guid=a))['parent_part_guid'] == b
        await current('undo')
        assert (await current('object',object_guid=b))['parent_part_guid'] == a
        await current('redo')
        assert (await current('object',object_guid=a))['parent_part_guid'] == b
        same = [move(c,index=-1), move(c,index=0)]
        plan = await current('edit_batch',mode='plan',edits=same)
        await current('rename_object',object_guid=c,name='C renamed')
        await current('edit_batch',mode='apply',edits=same,plan_id=plan['plan_id'],reject='stale')
        plan = await current('edit_batch',mode='plan',edits=same)
        await current('edit_batch',mode='apply',edits=same,plan_id=plan['plan_id'])
        await current('save_as_new',path='batch.cmo3')
        await current('close_saved')
        print('BATCH_OK', flush=True)

        imported = await invoke('import_psd',path='revision-1.psd')
        uid = imported['document_uid']
        guids = {item['name']:item['guid'] for item in (await current('objects'))['objects']}
        async def geometry():
            return {name: {k:v for k,v in (await current('object',object_guid=guid)).items()
                if k in {'indices','uvs','source_positions','vertex_uids','forms'}} for name,guid in guids.items()}
        before = await geometry()
        plan = await current('plan_psd_reimport',path='revision-2.psd')
        assert len(plan['updates'])==1 and len(plan['missing'])==1
        updated = await current('reimport_psd',path='revision-2.psd',plan_id=plan['plan_id'])
        next_plan = await current('plan_psd_reimport',path='revision-3.psd')
        assert next_plan['source_psd_guid']==updated['new_source_psd_guid']
        await current('undo')
        assert (await current('plan_psd_reimport',path='revision-2.psd'))['source_psd_guid']==imported['source_psd_guid']
        await current('redo')
        assert (await current('plan_psd_reimport',path='revision-3.psd'))['source_psd_guid']==updated['new_source_psd_guid']
        await current('render_png',path='revision2.png',width=256,height=256,base_pose='default')
        await current('save_as_new',path='revisions.cmo3')
        await current('close_saved')
        uid = await reopen('revisions.cmo3')
        next_plan = await current('plan_psd_reimport',path='revision-3.psd')
        assert next_plan['source_psd_guid']==updated['new_source_psd_guid']
        await current('reimport_psd',path='revision-3.psd',plan_id=next_plan['plan_id'])
        await current('render_png',path='revision3.png',width=256,height=256,base_pose='default')
        after = await geometry()
        assert set(before)==set(after) and all(before[n]['indices']==after[n]['indices'] and
            before[n]['source_positions']==after[n]['source_positions'] and before[n]['uvs']==after[n]['uvs']
            and before[n]['forms']==after[n]['forms'] for n in before)
        def pixels(path):
            with Image.open(work/path) as image:
                return Counter(px for px in image.convert('RGBA').get_flattened_data() if px[3]==255)
        assert pixels('revision2.png')[(0,128,0,255)] and pixels('revision3.png')[(0,0,255,255)]
        assert pixels('revision2.png')[(255,255,0,255)] == pixels('revision3.png')[(255,255,0,255)] > 0
        conflict = await current('plan_psd_reimport', path='conflict.psd')
        assert conflict['conflicts']
        await current('reimport_psd', path='conflict.psd', plan_id=conflict['plan_id'], reject='conflicts')
        await current('save_as_new',path='revision3.cmo3')
        await current('close_saved')
        assert not (await invoke('status'))['documents']
        print('PSD_UNDO_REDO_REOPEN_PIXELS_OK', flush=True)
        return definitions

async def schemas(server):
    config = StdioServerParameters(command=sys.executable,args=['-B',str(server)])
    async with stdio_client(config) as (r,w), ClientSession(r,w) as session:
        await session.initialize()
        return {tool.name:tool.model_dump(mode='json') for tool in (await session.list_tools()).tools}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package', type=Path, required=True)
    parser.add_argument('--editor-home', type=Path, required=True)
    parser.add_argument('--baseline-server', type=Path)
    options = parser.parse_args()
    portable = options.package.resolve(strict=True)
    editor = options.editor_home.resolve(strict=True)
    with TemporaryDirectory(prefix='cubism-portable-test-') as tmp:
        root = Path(tmp)
        for name in ('home','local','roaming','模型 空间','配置 profile'):
            (root/name).mkdir()
        folder=root/'模型 空间'
        fixture(folder)
        env = dict(os.environ)
        env['PATH'] = os.pathsep.join([str(Path(os.environ['SystemRoot'])/'System32'), str(Path(os.environ['SystemRoot'])/'System32/WindowsPowerShell/v1.0'), os.environ['SystemRoot']])
        for name in ('JAVA_HOME','CUBISM53_JDK_HOME','PYTHONHOME','PYTHONPATH'):
            env.pop(name,None)
        env['HTTP_PROXY'] = env['HTTPS_PROXY'] = 'http://127.0.0.1:1'
        with socket.socket() as spare:
            spare.bind(('127.0.0.1',0))
            port=spare.getsockname()[1]
        report,spec=setup(portable, editor, root, env, port)
        assert setup(portable, editor, root, env, port)==(report,spec)
        fake=root/'wrong-editor/app/lib'
        fake.mkdir(parents=True)
        (fake/'Live2D_Cubism.jar').write_bytes(b'not a supported editor')
        bad = subprocess.run([spec['command'],str(portable/'app/launcher.py'),'setup','--non-interactive',
            '--profile-root',str(root/'配置 profile'),'--workspace',str(folder),'--editor-home',str(root/'wrong-editor')],
            env=env, capture_output=True, text=True, timeout=15)
        assert bad.returncode==1 and 'Unsupported Cubism editor' in bad.stderr
        with socket.socket() as collision:
            collision.bind(('127.0.0.1',port))
            collision.listen()
            check = subprocess.run([spec['command'],str(portable/'app/launcher.py'),'doctor','--profile-file',report['profile']],
                                   env=env,capture_output=True,text=True,timeout=20)
            assert check.returncode==1 and 'occupied' in check.stdout, (check.returncode,check.stdout,check.stderr)
        process = launch(editor, root, env)
        try:
            time.sleep(8)
            assert process.poll() is None
            env['CUBISM53_EDITOR_PID']=str(process.pid)
            baseline = options.baseline_server.resolve(strict=True) if options.baseline_server else None
            legacy = asyncio.run(asyncio.wait_for(seed_legacy(baseline, editor, root, process.pid), 90)) if baseline else {}
            definitions = asyncio.run(asyncio.wait_for(exercise(spec, env, folder, legacy), 90))
            assert definitions == asyncio.run(schemas(ROOT / 'server.py'))
            if options.baseline_server:
                assert definitions == asyncio.run(schemas(options.baseline_server.resolve(strict=True)))
            print(json.dumps({'portable_tools':len(definitions), 'system_python_java_git_on_path':False,
                'source_schema_match':True,'cc_switch_and_dsh_specs_match':True}),flush=True)
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(15)


if __name__ == '__main__':
    main()
