"""Generate bounded performance samples inside portable_smoke's isolated project."""
import asyncio
import json
import math
import statistics
import time

from PIL import Image
from psd_tools import PSDImage
from psd_tools.api.layers import PixelLayer


async def measure(name, operation, repeats=10):
    await operation()
    elapsed = []
    result = None
    for _ in range(repeats):
        start = time.perf_counter()
        result = await operation()
        elapsed.append((time.perf_counter() - start) * 1000)
    print(f'PERF {name} p50_ms={statistics.median(elapsed):.3f} '
          f'p95_ms={sorted(elapsed)[math.ceil(.95 * len(elapsed)) - 1]:.3f}', flush=True)
    return result


async def physics_trace(invoke, work, pid):
    import psutil

    doc = (await invoke('open_project', path='audited.cmo3', wait_seconds=12))['document_uid']

    async def model(op, **arguments):
        return await invoke(op, document_uid=doc, **arguments)

    rows = (await model('objects', sections=['parameters']))['parameters']
    parameters = [row['id'] for row in rows if row['id'] != 'ParamAngleZ'][:16]
    group = (await model('physics'))['groups'][0]['guid']
    await model('edit_physics_groups', edits=[{'action': 'update', 'group_guid': group,
        'outputs': [{'parameter_id': name, 'type': 'angle', 'weight': 50, 'vertex_index': 1}
                    for name in parameters]}])
    frames = [{'ParamAngleZ': (i % 10) - 5} for i in range(36000)]
    process = psutil.Process(pid)
    start_working_set = process.memory_info().rss
    peak = start_working_set
    start = time.perf_counter()
    launched = await model('simulate_physics', frames=frames, warmup_frames=0,
                           execution='job', request_id='perf-36000')

    async def tail():
        return await invoke('job_status', job_id=launched['job_id'], frame_offset=35996, frame_limit=4)

    for _ in range(500):
        peak = max(peak, process.memory_info().rss)
        page = await tail()
        if page['state'] in ('succeeded', 'failed'):
            break
        await asyncio.sleep(.01)
    assert page['state'] == 'succeeded', page
    print(f'PERF trace_36000 seconds={time.perf_counter() - start:.3f} '
          f'base_rss_mib={start_working_set / 2**20:.0f} peak_rss_mib={peak / 2**20:.0f}', flush=True)
    assert page['result']['frame_count'] == len(frames)
    assert page['result']['next_frame_offset'] is None
    assert [row['frame'] for row in page['result']['frames']] == list(range(35996, 36000))
    first = await invoke('job_status', job_id=launched['job_id'], frame_offset=0, frame_limit=4)
    empty = await invoke('job_status', job_id=launched['job_id'], frame_offset=len(frames), frame_limit=4)
    assert not empty['result']['frames']
    expected = await model('simulate_physics', frames=frames[:4], warmup_frames=0)
    assert first['result']['frames'] == expected['frames']
    assert (await measure('trace_last_page', tail, 30)) == page
    receipt = await model('simulate_physics', frames=frames[:256], warmup_frames=0, trace_path='perf-trace.json')
    written = json.loads((work / 'perf-trace.json').read_text(encoding='utf8'))
    assert receipt['frame_count'] == len(written['frames']) == 256
    assert written == await model('simulate_physics', frames=frames[:256], warmup_frames=0)
    content = (work / 'perf-trace.json').read_bytes()
    await model('simulate_physics', frames=frames[:4], warmup_frames=0,
                trace_path='perf-trace.json', reject='FileAlreadyExistsException')
    assert (work / 'perf-trace.json').read_bytes() == content
    await model('undo')


async def offset_track(invoke, work):
    opened = await invoke('open_project', path='offset-audit.can3', wait_seconds=12)
    doc = opened['document_uid']

    async def anim(op, **arguments):
        return await invoke(op, document_uid=doc, **arguments)

    track = (await anim('edit_animation_tracks', action='append', model_path='expanded.cmo3',
                        start_frame=0, duration_frames=10, internal_offset=10000))['track']['guid']
    options = dict(track_guid=track, parameter_id='ParamAngleX')
    await anim('set_timeline_keys', **options, frame_space='scene', keys=[{'frame': 3, 'value': 9}])
    sampled = await anim('sample_timeline_parameters', **options, frames=[10003])
    assert sampled['samples'] == [{'frame': 10003, 'value': 9}]
    assert (await anim('get_timeline_keys', **options, start_frame=10003, end_frame=10003))['keys'][0]['frame'] == 10003
    await anim('remove_timeline_keys', **options, frames=[10003])
    assert not (await anim('get_timeline_keys', **options))['keys']
    await anim('undo')
    assert (await anim('sample_timeline_parameters', **options, frame_space='scene', frames=[3]))['samples'][0]['value'] == 9
    await anim('export_motion3', track_guid=track, path='perf-offset.motion3.json')
    assert json.loads((work/'perf-offset.motion3.json').read_text(encoding='utf8'))['Meta']['CurveCount'] >= 1
    assert (await anim('save_as_new', path='perf-offset.can3'))['saved']
    await anim('close_saved', wait_seconds=6)
    doc = (await invoke('open_project', path='perf-offset.can3', wait_seconds=12))['document_uid']
    assert (await anim('get_timeline_keys', **options))['keys'][0]['frame'] == 10003
    await anim('close_saved', wait_seconds=6)
    print('PERF_OFFSET_FRAME_ROUNDTRIP_OK', flush=True)


async def layered_assets(invoke, work, count):
    for variant, color in [('base', '#8844aa'), ('incoming', '#449988')]:
        psd = PSDImage.new('RGBA', (256, 256), color=(0, 0, 0, 0))
        layer = Image.new('RGBA', (4, 4), color)
        for i in range(count):
            PixelLayer.frompil(layer, psd, f'Layer {i:04}', left=(i % 40) * 6, top=(i // 40) * 6)
        psd.save(work / f'perf-{count}-{variant}.psd')
    start = time.perf_counter()
    imported = await invoke('import_psd', path=f'perf-{count}-base.psd')
    print(f'PERF psd_{count}_import seconds={time.perf_counter() - start:.3f}', flush=True)
    assert len(imported['layers']) == count
    assert all(len(layer['material_guids']) == len(layer['art_mesh_guids']) == 1 for layer in imported['layers'])
    assert len({layer['art_mesh_guids'][0] for layer in imported['layers']}) == count
    doc = imported['document_uid']

    async def model(op, **arguments):
        return await invoke(op, document_uid=doc, **arguments)

    async def pd_plan():
        return await model('plan_psd_reimport', path=f'perf-{count}-incoming.psd')

    plan = await measure(f'psd_{count}', pd_plan, 5)
    assert not plan['conflicts'] and not plan['missing'] and len(plan['updates']) == count, {
        key: len(plan[key]) for key in ('conflicts', 'missing', 'updates', 'additions')}
    if count != 1000:
        return
    objects = (await model('objects', sections=['objects']))['objects']
    page = await measure('objects_1000_page', lambda: model('objects', sections=['objects'], offset=555, limit=20))
    assert page['objects'] == objects[555:575]
    edits = [{'operation': 'rename_object', 'arguments': {'object_guid': obj['guid'], 'name': f'Batch {i}'}}
             for i, obj in enumerate(objects[:128])]

    async def batch():
        plan = await model('edit_batch', mode='plan', edits=edits)
        result = await model('edit_batch', mode='apply', edits=edits, plan_id=plan['plan_id'])
        await model('undo')
        return result

    assert len((await measure('batch_128_plan_apply_undo', batch, 6))['impact']) == len(edits)
    assert (await model('objects', sections=['objects']))['objects'] == objects
    await model('redo')
    renamed = (await model('objects', sections=['objects'], limit=len(edits)))['objects']
    assert [row['name'] for row in renamed] == [edit['arguments']['name'] for edit in edits]
    await model('undo')
    assert (await model('objects', sections=['objects']))['objects'] == objects
    atlas = await model('edit_texture_atlas', action='create_page', name='Perf atlas', width=512, height=512)
    page_guid = atlas['pages'][0]['guid']
    placements = [{'material_guid': material['guid'], 'page_guid': page_guid, 'x': i % 40 * 10, 'y': i // 40 * 10}
                  for i, material in enumerate(atlas['unplaced_materials'])]
    assert len(placements) == count
    check = await measure('atlas_1000_sparse', lambda: model('edit_texture_atlas', action='place',
                                                            placements=placements, preflight=True), 8)
    assert check['ready'], check
    collision = [{**row, 'x': 0, 'y': 0} for row in placements[:80]]
    check = await measure('atlas_80_dense', lambda: model('edit_texture_atlas', action='place',
                                                         placements=collision, preflight=True), 5)
    assert len(check['conflicts']) == 80 * 79 // 2, check.keys()
    assert check['conflicts'][0]['other_material_guid'] == placements[0]['material_guid']


async def runtime_attachments(invoke, work):
    doc = (await invoke('import_psd', path='revision-1.psd'))['document_uid']

    async def model(op, **arguments):
        return await invoke(op, document_uid=doc, **arguments)

    atlas = await model('edit_texture_atlas', action='create_page', name='Runtime atlas', width=512, height=512)
    await model('edit_texture_atlas', action='auto_layout', page_guid=atlas['pages'][0]['guid'])
    motions = [{'group': f'Group{i}', 'path': 'expanded.motion3.json'} for i in range(64)]
    expressions = [{'name': f'Expression{i}', 'path': 'blend.exp3.json'} for i in range(64)]
    options = dict(motions=motions, expressions=expressions, physics_path='expanded.physics3.json')
    ready = await measure('preflight_128_shared_attachments', lambda: model('preflight_runtime_export', **options))
    assert ready['ready'] and len(ready['attachments']) == 3, ready
    await model('render_png', path='perf-runtime-before.png', width=256, height=256)
    for i in range(3):
        job = await model('export_runtime_package', directory=f'perf-export-{i}', name='perf', request_id=f'perf-export-{i}', **options)
        for _ in range(150):
            result = await invoke('job_status', job_id=job['job_id'])
            if result['state'] in ('succeeded', 'failed'):
                break
            await asyncio.sleep(.025)
        assert result['state'] == 'succeeded', result
        package = json.loads((work/f'perf-export-{i}'/'perf.model3.json').read_text(encoding='utf8'))
        assert len(package['FileReferences']['Motions']) == len(motions)
        assert len(package['FileReferences']['Expressions']) == len(expressions)
    await model('render_png', path='perf-runtime-after.png', width=256, height=256)
    with Image.open(work/'perf-runtime-before.png') as before, Image.open(work/'perf-runtime-after.png') as after:
        assert before.tobytes() == after.tobytes()


async def check_performance(invoke, work, pid, candidate):
    if candidate:
        await offset_track(invoke, work)
    await physics_trace(invoke, work, pid)
    await runtime_attachments(invoke, work)
    for layers in (100, 1000):
        await layered_assets(invoke, work, layers)
