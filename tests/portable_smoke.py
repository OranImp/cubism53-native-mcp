"""Exercise an unpacked Windows portable bundle with a separate Cubism session."""
import argparse
import asyncio
import base64
from collections import Counter
import json
import os
from pathlib import Path
import re
import secrets
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
from expansion_smoke import check_expansion

ROOT = Path(__file__).resolve().parents[1]

def free_preview_port():
    # Keep test listeners outside Windows' default outbound TCP port range.
    for _ in range(40):
        port = 30000 + secrets.randbelow(19000)
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            try:
                probe.bind(('', port))
                return port
            except OSError:
                continue
    raise RuntimeError('Could not reserve an isolated preview test port')


async def start_preview(probe, port):
    await probe(f'preview={port}')
    for _ in range(50):
        try:
            _, writer = await asyncio.open_connection('127.0.0.1', port)
        except OSError:
            await asyncio.sleep(.1)
        else:
            writer.close()
            await writer.wait_closed()
            return
    raise RuntimeError(f'The isolated preview server did not listen on port {port}')


def setup(portable, editor, root, env, port, preview_port):
    arguments = [portable / 'Setup.cmd', '--non-interactive', '--profile-root', root / '配置 profile',
                 '--workspace', root / '模型 空间', '--editor-home', editor, '--bridge-port', port,
                 '--preview-port', preview_port]
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
    for i, color in enumerate(('red', 'green', 'blue', 'blue'), 1):
        psd = PSDImage.new('RGBA', (256, 256), color=(0, 0, 0, 0))
        PixelLayer.frompil(Image.new('RGBA',(16,16),color), psd, name='Paint', left=80, top=80)
        if i in (1, 4):
            PixelLayer.frompil(Image.new('RGBA',(16,16),'yellow' if i == 1 else 'magenta'),
                               psd, name='Old', left=120, top=80)
        psd.save(folder/f'revision-{i}.psd')

    for name, layers in [('unrelated.psd', ['Independent']), ('conflict.psd', ['Paint', 'Paint'])]:
        psd = PSDImage.new('RGBA', (256, 256), color=(0, 0, 0, 0))
        for index, layer in enumerate(layers):
            PixelLayer.frompil(Image.new('RGBA', (16, 16), 'gray'), psd, name=layer, left=80 + 20 * index, top=80)
        psd.save(folder / name)

    physics = {'Version': 3, 'Meta': {
        'PhysicsSettingCount': 1, 'TotalInputCount': 1, 'TotalOutputCount': 1, 'VertexCount': 2, 'Fps': 60,
        'EffectiveForces': {'Gravity': {'X': 0, 'Y': -1}, 'Wind': {'X': 0, 'Y': 0}},
        'PhysicsDictionary': [{'Id': 'PhysicsSetting1', 'Name': 'Probe'}]},
        'PhysicsSettings': [{
            'Id': 'PhysicsSetting1',
            'Input': [{'Source': {'Target': 'Parameter', 'Id': 'ParamAngleZ'}, 'Weight': 100,
                       'Type': 'Angle', 'Reflect': False}],
            'Output': [{'Destination': {'Target': 'Parameter', 'Id': 'ParamAngleX'}, 'VertexIndex': 1,
                        'Scale': 1.5, 'Weight': 100, 'Type': 'Angle', 'Reflect': False}],
            'Vertices': [
                {'Position': {'X': 0, 'Y': 0}, 'Mobility': 0, 'Delay': 0, 'Acceleration': 0, 'Radius': 0},
                {'Position': {'X': 0, 'Y': 20}, 'Mobility': .8, 'Delay': .25, 'Acceleration': 1, 'Radius': 20}],
            'Normalization': {'Position': {'Minimum': -10, 'Maximum': 10, 'Default': 0},
                              'Angle': {'Minimum': -30, 'Maximum': 30, 'Default': 0}}}]}
    (folder / 'probe.physics3.json').write_text(json.dumps(physics), encoding='utf8')


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


def native_probe(portable, editor, root, pid, version):
    source = ROOT / 'tests/NativeEditorProbe.java'
    lib = editor / 'app/lib'
    classes = root / 'probe-classes'
    classes.mkdir()
    manifest = root / 'probe.mf'
    manifest.write_text('Manifest-Version: 1.0\nAgent-Class: cubism53.mcp.NativeEditorProbe\n\n', encoding='ascii')
    probe = root / 'probe.jar'
    subprocess.run(['javac', '--release', '17', '-cp', str(lib / '*'), '-d', str(classes), str(source)],
                   check=True, timeout=20)
    subprocess.run(['jar', '--create', '--file', str(probe), '--manifest', str(manifest), '-C', str(classes), '.'],
                   check=True, timeout=15)
    java = portable / 'runtime/java/bin/java.exe'
    adapter = portable / 'app/adapters' / f'{version}.jar'

    async def run(operation):
        process = await asyncio.create_subprocess_exec(
            str(java), '--add-modules', 'jdk.attach', '-cp', str(adapter), 'cubism53.mcp.Attach',
            str(pid), str(probe), operation)
        assert await asyncio.wait_for(process.wait(), 20) == 0, f'Native test probe failed: {operation}'

    return run


async def check_upgrade(previous, spec, env, profile):
    python = previous / 'runtime/python/python.exe'
    launcher = previous / 'app/launcher.py'
    args = ['-B', str(launcher), 'serve', '--profile-file', profile]
    session_env = {key: env[key] for key in ('PATH', 'LOCALAPPDATA', 'CUBISM53_EDITOR_PID')}
    old = StdioServerParameters(command=str(python), args=args, env=session_env)
    new = StdioServerParameters(command=spec['command'], args=spec['args'], env=session_env)
    async with stdio_client(old) as (reader, writer), ClientSession(reader, writer) as client:
        await client.initialize()
        first = await client.call_tool('cubism_native_connect', {})
        assert not first.isError, first.content
    async with stdio_client(new) as (reader, writer), ClientSession(reader, writer) as client:
        await client.initialize()
        result = await client.call_tool('cubism_native_connect', {})
        assert result.isError and any(message in result.content[0].text for message in
                                      ('Older bridge', 'adapter build differs')), result.content
    stopper = await asyncio.create_subprocess_exec(
        str(python), '-B', str(launcher), 'stop', '--profile-file', profile,
        env=env, stdout=asyncio.subprocess.PIPE)
    out, _ = await asyncio.wait_for(stopper.communicate(), 20)
    assert stopper.returncode == 0 and json.loads(out)['stopped']
    print('OLD_BRIDGE_REJECTED_AND_STOPPED_FOR_UPGRADE', flush=True)


async def exercise(spec, env, work, legacy, expected_version, probe, preview_port, editor, pid, high_resolution):
    config = StdioServerParameters(command=spec['command'], args=spec['args'], env={
        'PATH':env['PATH'], 'LOCALAPPDATA':env['LOCALAPPDATA'], 'CUBISM53_EDITOR_PID':env['CUBISM53_EDITOR_PID'],
        'PYTHONDONTWRITEBYTECODE':'1',
    })
    async with stdio_client(config) as (r,w), ClientSession(r,w) as mcp:
        await mcp.initialize()
        definitions = {tool.name: tool.model_dump(mode='json') for tool in (await mcp.list_tools()).tools}
        assert len(definitions) == 84
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
        assert first['editor_version'] == second['editor_version'] == expected_version
        assert first['bridge_protocol'] == 2 and first['adapter_build_id'] == second['adapter_build_id']
        assert len(first['adapter_build_id']) == 64
        assert (await invoke('status'))['editor_version'] == expected_version
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

        async def wait_job(job, request_id, *, reject=None):
            for _ in range(200):
                status = await invoke('job_status', request_id=request_id)
                assert status['job_id'] == job['job_id']
                if status['state'] == 'succeeded':
                    assert reject is None, status
                    return status['result']
                if status['state'] == 'failed':
                    if reject and reject in status['error']:
                        return None
                    raise AssertionError(status['error'])
                await asyncio.sleep(.1)
            raise AssertionError('Job did not finish')

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
        recovered = await current('plan_psd_reimport', path='revision-4.psd')
        assert not (recovered['additions'] or recovered['conflicts']), recovered
        old_match = next(row for row in recovered['updates'] if row['incoming']['path'][-1] == 'Old')
        assert old_match['existing']['source_psd_guid'] == imported['source_psd_guid']
        mapping = {old_match['incoming']['key']: old_match['existing']['key']}
        recovered = await current('plan_psd_reimport', path='revision-4.psd', layer_map=mapping)
        applied = await current('reimport_psd', path='revision-4.psd', plan_id=recovered['plan_id'], layer_map=mapping)
        restored = next(row for row in applied['layers'] if row['layer']['path'][-1] == 'Old')
        assert restored['art_mesh_guids'] == [guids['Old']], restored
        assert await geometry() == after
        await current('render_png', path='restored.png', width=256, height=256, base_pose='default')
        assert pixels('restored.png')[(255,0,255,255)] > 0
        await current('undo')
        await current('render_png', path='restored-undo.png', width=256, height=256, base_pose='default')
        assert pixels('restored-undo.png') == pixels('revision3.png')
        await current('redo')
        await current('render_png', path='restored-redo.png', width=256, height=256, base_pose='default')
        assert pixels('restored-redo.png') == pixels('restored.png')
        print('PSD_MISSING_LAYER_RESTORED_WITH_ORIGINAL_GUID', flush=True)

        paint, old = guids['Paint'], guids['Old']
        cached = await current('render_png', path='cached.png', width=256, height=256, base_pose='default')
        assert cached['renderer_reused']
        await current('set_visibility', object_guid=old, visible=False)
        refreshed = await current('render_png', path='hidden.png', width=256, height=256, base_pose='default')
        assert not refreshed['renderer_reused'] and not pixels('hidden.png')[(255,0,255,255)]
        if probe:
            await probe('undo')
            native_undo = await current('render_png', path='native-undo.png', width=256, height=256, base_pose='default')
            assert not native_undo['renderer_reused'] and pixels('native-undo.png') == pixels('restored.png')
            await probe('redo')
            native_redo = await current('render_png', path='native-redo.png', width=256, height=256, base_pose='default')
            assert not native_redo['renderer_reused'] and pixels('native-redo.png') == pixels('hidden.png')
            await probe('undo')
            print('EDITOR_NATIVE_UNDO_REDO_INVALIDATES_CACHE', flush=True)
        else:
            await current('undo')
        await current('render_png', path='single-high.png', width=1536, height=1536, base_pose='default')
        render = {'poses': [{'name': 'default', 'parameters': {}}, {'name': 'turned', 'parameters': {'ParamAngleZ': 5}}],
                  'width': 1536, 'height': 1536}
        job = await current('render_pose_set', directory='poses', request_id='pose-set', **render)
        started = time.monotonic()
        await asyncio.wait_for(invoke('status'), 5)
        print(f'STATUS_DURING_RENDER_SECONDS={time.monotonic() - started:.3f}', flush=True)
        rendered = await wait_job(job, 'pose-set')
        with Image.open(work/'single-high.png') as single, Image.open(work/'poses/default.png') as batch:
            assert single.convert('RGBA').tobytes() == batch.convert('RGBA').tobytes()
        job = await current('render_pose_set', directory='poses-compare', request_id='pose-compare',
                            baseline_manifest=rendered['manifest'], **render)
        compared = await wait_job(job, 'pose-compare')
        manifest = json.loads(Path(compared['manifest']).read_text(encoding='utf8'))
        assert all(frame['difference']['changed_pixels'] == 0 for frame in manifest['frames'])
        failed = await current('render_pose_set', directory='poses-failed', request_id='pose-failed',
                               baseline_manifest='missing.json', **render)
        await wait_job(failed, 'pose-failed', reject='NoSuchFileException')
        assert not (work / 'poses-failed').exists() and not list(work.glob('.cubism-render-*'))
        print('RENDER_CACHE_AND_BACKGROUND_PIXELS_OK', flush=True)

        if probe:
            await start_preview(probe, preview_port)
            await current('set_preview_parameters', parameters={'ParamAngleZ': 5}, reject='approve')
            await probe('approve-preview')
            assert (await current('set_preview_parameters', parameters={'ParamAngleZ': 5}))['applied']
            async with stdio_client(config) as (reader, writer), ClientSession(reader, writer) as second:
                await second.initialize()
                applied = await second.call_tool('cubism_native_set_preview_parameters',
                                                 {'document_uid': uid, 'parameters': {'ParamAngleZ': 6}})
                assert not applied.isError and applied.structuredContent['applied'], applied.content
                assert (await current('clear_preview_parameters'))['applied']
            second_port = free_preview_port()
            await start_preview(probe, second_port)
            profile = Path(spec['args'][-1])
            alternate = profile.with_name('preview-alternate.json')
            settings = json.loads(profile.read_text(encoding='utf8'))
            alternate.write_text(json.dumps({**settings, 'preview_port': second_port}), encoding='utf8')
            alternate_config = StdioServerParameters(command=spec['command'],
                args=[*spec['args'][:-1], str(alternate)], env=config.env)
            async with stdio_client(alternate_config) as (reader, writer), ClientSession(reader, writer) as other_port:
                await other_port.initialize()
                params = {'document_uid': uid, 'parameters': {'ParamAngleZ': 8}}
                pending = await other_port.call_tool('cubism_native_set_preview_parameters', params)
                assert pending.isError and 'approve' in pending.content[0].text, pending.content
                await probe('approve-preview')
                applied = await other_port.call_tool('cubism_native_set_preview_parameters', params)
                assert not applied.isError and applied.structuredContent['applied'], applied.content
            first_token, second_token = (list((profile.parent / 'state').rglob(f'preview-{port}-token.txt'))
                                         for port in (preview_port, second_port))
            assert len(first_token) == len(second_token) == 1
            assert first_token[0].read_bytes() != second_token[0].read_bytes()
            print('EDITOR_PREVIEW_APPROVAL_AND_TWO_CLIENTS_OK', flush=True)

        evaluated = await current('evaluate', object_guids=[paint, old], base_pose='default')
        assert [obj['guid'] for obj in evaluated['objects']] == [paint, old]
        assert all(obj['canvas_positions'] for obj in evaluated['objects'])
        suggestions = await current('suggest_glue_pairs', mesh_a_guid=paint, mesh_b_guid=old, tolerance=500)
        assert suggestions['pairs'] or suggestions['ambiguous']
        original = await current('object', object_guid=paint)
        positions = original['source_positions'].copy()
        positions[0] += .25
        topology = await current('set_mesh_topology', object_guid=paint,
                                 positions=positions, indices=original['indices'])
        assert len(topology['indices']) == len(original['indices'])
        assert topology['source_positions'] != original['source_positions']
        await current('undo')
        assert (await current('object', object_guid=paint))['source_positions'] == original['source_positions']
        print('EVALUATE_GLUE_AND_MESH_TRANSFER_OK', flush=True)

        await current('import_physics3', path='probe.physics3.json')
        exported = await current('export_physics3', path='physics.physics3.json')
        assert exported['source_group_count'] == 1
        atlas = await current('edit_texture_atlas', action='create_page', name='Atlas', width=256, height=256)
        atlas = await current('edit_texture_atlas', action='auto_layout', page_guid=atlas['pages'][0]['guid'])
        assert not atlas['unplaced_materials']
        runtime = await current('export_runtime_model', directory='runtime', name='probe')
        references = json.loads(Path(runtime['model3']).read_text(encoding='utf8'))['FileReferences']
        assert (work / 'runtime' / references['Moc']).read_bytes().startswith(b'MOC3')
        assert all((work / 'runtime' / texture).is_file() for texture in references['Textures'])
        job = await current('export_runtime_package', directory='runtime-job', name='probe', request_id='runtime-package')
        package = await wait_job(job, 'runtime-package')
        package_refs = json.loads(Path(package['model3']).read_text(encoding='utf8'))['FileReferences']
        assert references == package_refs
        await current('export_runtime_model', directory='runtime', name='probe', reject='FileAlreadyExistsException')
        await current('save_as_new',path='revision3.cmo3')
        scene = await invoke('create_animation_scene', model_document_uid=uid, name='Probe', fps=30, duration_frames=30)
        animation, track = scene['document_uid'], scene['model_track_guid']
        await invoke('set_timeline_keys', document_uid=animation, track_guid=track, parameter_id='ParamAngleZ',
                     keys=[{'frame': 0, 'value': 0}, {'frame': 15, 'value': 12}])
        await invoke('export_motion3', document_uid=animation, track_guid=track, path='probe.motion3.json')
        motion = json.loads((work / 'probe.motion3.json').read_text(encoding='utf8'))
        assert any(curve['Id'] == 'ParamAngleZ' for curve in motion['Curves'])
        assert (await invoke('save_as_new', document_uid=animation, path='probe.can3'))['saved']
        await invoke('close_saved', document_uid=animation)
        await current('close_saved')
        assert not (await invoke('status'))['documents']
        print('PSD_UNDO_REDO_REOPEN_PIXELS_OK', flush=True)
        print('PHYSICS_RUNTIME_AND_ANIMATION_EXPORT_OK', flush=True)
        await check_expansion(invoke, work, editor, pid, probe, high_resolution)
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
    parser.add_argument('--legacy-server', type=Path)
    parser.add_argument('--previous-package', type=Path, help='Exercise an upgrade with the previous portable package')
    parser.add_argument('--native-probe', action='store_true', help='Test editor undo and preview via a dedicated test agent')
    parser.add_argument('--high-resolution', action='store_true', help='Render and compare 4K and 8K; requires psutil')
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
        preview_port = free_preview_port()
        report,spec=setup(portable, editor, root, env, port, preview_port)
        assert setup(portable, editor, root, env, port, preview_port)==(report,spec)
        expected = re.search(r'Cubism ver (5\.3\.\d+)',
                             (editor / 'CubismEditor5.bat').read_text(encoding='ascii', errors='ignore'))[1]
        assert report['editor_version'] == expected
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
            detected = subprocess.check_output([
                spec['command'], '-B', str(portable / 'app/launcher.py'), 'setup', '--non-interactive',
                '--profile', 'detected', '--profile-root', str(root / '配置 profile'), '--workspace', str(folder),
            ], env=env, timeout=20)
            detected_profile = json.loads(Path(json.loads(detected)['profile']).read_text(encoding='utf8'))
            assert Path(detected_profile['editor_home']) == editor
            assert json.loads(detected)['editor_version'] == expected
            if options.previous_package:
                previous = options.previous_package.resolve(strict=True)
                asyncio.run(asyncio.wait_for(check_upgrade(previous, spec, env, report['profile']), 70))
            baseline = options.baseline_server.resolve(strict=True) if options.baseline_server else None
            legacy = (asyncio.run(asyncio.wait_for(seed_legacy(
                options.legacy_server.resolve(strict=True), editor, root, process.pid), 90))
                      if options.legacy_server and expected == '5.3.03' else {})
            probe = native_probe(portable, editor, root, process.pid, expected) if options.native_probe else None
            definitions = asyncio.run(asyncio.wait_for(exercise(
                spec, env, folder, legacy, expected, probe, preview_port, editor, process.pid, options.high_resolution), 240))
            assert definitions == asyncio.run(schemas(ROOT / 'server.py'))
            if baseline:
                previous = asyncio.run(schemas(baseline))
                assert len(previous) == 75 and previous.keys() <= definitions.keys()
                additions = {'objects': {'sections', 'part_guid', 'object_types', 'parameter_id'},
                             'object': {'form_guids', 'geometry'},
                             'simulate_physics': {'output_parameter_ids', 'result_mode', 'execution', 'request_id'}}
                for name, old in previous.items():
                    current = definitions[name]
                    if name == 'cubism_native_connect':
                        old['description'] = current['description']
                    extra = additions.get(name.removeprefix('cubism_native_'), set())
                    if extra:
                        old_fields = old['inputSchema']['properties']
                        current_fields = current['inputSchema']['properties']
                        assert current_fields.keys() - old_fields.keys() == extra
                        current = {**current, 'inputSchema': {**current['inputSchema'],
                            'properties': {key: current_fields[key] for key in old_fields}}}
                    assert current == old, name
            print(json.dumps({'editor_version':expected, 'portable_tools':len(definitions), 'system_python_java_git_on_path':False,
                'source_schema_match':True,'cc_switch_and_dsh_specs_match':True}),flush=True)
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(15)
            if sys.exc_info()[0]:
                log = (root / 'editor.log').read_text(encoding='utf8', errors='replace')
                print('\n'.join(log.splitlines()[-16:]), file=sys.stderr)


if __name__ == '__main__':
    main()
