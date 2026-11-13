"""Real stdio acceptance for v0.3.0, using only generated fixtures."""
from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
import subprocess
import time

from PIL import Image


async def check_expansion(invoke, work, editor, pid, probe, high_resolution):
    async def reopen(path, *, document_type=None):
        opened = await invoke('open_project', path=path)
        if 'document_uid' in opened:
            return opened['document_uid']
        for _ in range(30):
            for doc in (await invoke('status'))['documents']:
                if doc.get('file') and Path(doc['file']).name == path and (
                        document_type is None or doc.get('document_type') == document_type):
                    return doc['document_uid']
            await asyncio.sleep(.15)
        raise AssertionError(f'Open timed out: {path}')

    stale = (await invoke('create_blank_project', width=128, height=128))['document_uid']
    await invoke('save_as_new', document_uid=stale, path='stale.cmo3')
    (work/'stale.cmo3').rename(work/'moved.cmo3')
    # A valid request must get past the stale file's failed toRealPath.
    other = await reopen('revision3.cmo3')
    assert other == await reopen('revision3.cmo3')
    await invoke('close_saved', document_uid=other)
    await invoke('close_saved', document_uid=stale)

    doc = (await invoke('import_psd', path='revision-1.psd'))['document_uid']

    async def model(op, **args):
        return await invoke(op, document_uid=doc, **args)

    async def finish_job(job, *, error=None):
        for _ in range(400):
            result = await invoke('job_status', job_id=job['job_id'])
            if result['state'] == 'succeeded':
                assert error is None, result
                return result['result']
            if result['state'] == 'failed':
                assert error and error in result['error'], result
                return result
            await asyncio.sleep(.1)
        raise AssertionError('Job timed out')

    listing = await model('objects')
    meshes = {item['name']: item for item in listing['objects']}
    paint, old = meshes['Paint']['guid'], meshes['Old']['guid']
    part = (await model('create_part', name='Render Group'))['guid']
    await model('move_to_part', object_guid=paint, part_guid=part)
    await model('add_parameter_keys', object_guid=paint, parameter_id='ParamAngleX', values=[10])
    subset = await model('objects', sections=['objects'], part_guid=part, object_types=['CArtMeshSource'],
                         parameter_id='ParamAngleX')
    all_objects = (await model('objects'))['objects']
    assert subset == {'objects': [item for item in all_objects if item['guid'] == paint]}
    full = await model('object', object_guid=paint)
    summary = await model('object', object_guid=paint, geometry=False, form_guids=[full['default_form_guid']])
    expected = next(row for row in full['forms'] if row['guid'] == full['default_form_guid'])
    assert summary['forms'] == [{key: value for key, value in expected.items() if key != 'positions'}]
    assert 'indices' not in summary

    await model('edit_part_rendering', part_guid=part, patch={'offscreen': True, 'draw_order_group': True})
    await model('render_png', path='before-part-mask.png', width=256, height=256, base_pose='default')
    masked = await model('edit_part_rendering', part_guid=part, patch={'mask_guids': [old], 'invert_mask': False})
    assert masked['offscreen'] and masked['mask_guids'] == [old]
    await model('render_png', path='after-part-mask.png', width=256, height=256, base_pose='default')
    with Image.open(work/'before-part-mask.png') as before, Image.open(work/'after-part-mask.png') as after:
        assert before.convert('RGBA').tobytes() != after.convert('RGBA').tobytes()
    await model('undo')
    await model('redo')

    duplicate = (await model('duplicate_objects', object_guids=[paint]))['guid_map'][paint]
    target = await model('object', object_guid=duplicate)
    mapping = [{'source_form_guid': full['default_form_guid'], 'target_form_guid': target['default_form_guid']}]
    result = await model('mirror_keyforms', source_guid=paint, target_guid=duplicate, axis='x', pivot=128, forms=mapping)
    assert result['form_guids'] == [target['default_form_guid']]
    mirror = await model('object', object_guid=duplicate)
    assert mirror['uvs'] == target['uvs'] and mirror['indices'] == target['indices']
    assert mirror['forms'] != target['forms']
    await model('undo')
    assert (await model('object', object_guid=duplicate))['forms'] == target['forms']
    await model('redo')
    assert (await model('object', object_guid=duplicate))['forms'] == mirror['forms']
    warp_pair = [await model('create_warp', name=name, x=x, y=45, width=35, height=45)
                 for name,x in [('Left Warp', 40), ('Right Warp', 180)]]
    warp_forms = [await model('object', object_guid=item['guid']) for item in warp_pair]
    await model('mirror_keyforms', source_guid=warp_pair[0]['guid'], target_guid=warp_pair[1]['guid'],
                axis='x', pivot=120, forms=[{'source_form_guid': warp_forms[0]['default_form_guid'],
                                            'target_form_guid': warp_forms[1]['default_form_guid']}])
    changed = (await model('object', object_guid=warp_pair[1]['guid']))['forms'][0]['positions']
    assert changed[0] == 165

    blocked = await model('plan_delete_objects', object_guids=[old])
    assert any(blocker['kind'] == 'clipping' for blocker in blocked['blockers'])
    await model('delete_objects', object_guids=[old], plan_id=blocked['plan_id'], reject='blockers')
    planned = await model('plan_delete_objects', object_guids=[duplicate])
    await model('rename_object', object_guid=duplicate, name='Mirrored')
    await model('delete_objects', object_guids=[duplicate], plan_id=planned['plan_id'], reject='stale')
    planned = await model('plan_delete_objects', object_guids=[duplicate])
    assert paint in planned['materials'][0]['remaining_mesh_guids']
    glue = await model('create_glue', mesh_a_guid=paint, mesh_b_guid=duplicate,
                       pairs=[{'a_uid': full['vertex_uids'][0], 'b_uid': target['vertex_uids'][0]}])
    planned = await model('plan_delete_objects', object_guids=[duplicate])
    assert any(blocker['kind'] == 'glue' for blocker in planned['blockers'])
    assert planned['glues'][0]['guid'] == glue['guid']
    planned = await model('plan_delete_objects', object_guids=[duplicate], detach_references=True)
    await model('delete_objects', object_guids=[duplicate], detach_references=True, plan_id=planned['plan_id'])
    assert not (await model('list_glues'))['glues']
    await model('undo')
    assert (await model('object', object_guid=duplicate))['guid'] == duplicate
    assert (await model('list_glues'))['glues'][0]['guid'] == glue['guid']
    await model('redo')
    await model('object', object_guid=duplicate, reject='not found')

    # Exercise a cascade through a Part into a Warp with a child in a different Part.
    prune_part = (await model('create_part', name='Prune Group'))['guid']
    prune_warp = (await model('create_warp', name='Prune Warp', x=60, y=60, width=40, height=40))['guid']
    await model('move_to_part', object_guid=prune_warp, part_guid=prune_part)
    await model('reparent', object_guid=paint, parent_guid=prune_warp)
    plan = await model('plan_delete_objects', object_guids=[prune_part], cascade=True)
    assert set(plan['delete_guids']) == {prune_part, prune_warp, paint}, plan
    await model('delete_objects', object_guids=[prune_part], cascade=True, plan_id=plan['plan_id'])
    await model('undo')
    assert (await model('object', object_guid=paint))['parent_guid'] == prune_warp

    assert not (await model('inspect_art_paths'))['art_paths']
    if probe:
        await probe('artpath-brush')
    brushes = (await model('inspect_art_paths'))['brushes']
    if probe:
        assert brushes
    path = await model('create_art_path', name='Stroke', points=[{'x':50,'y':70}, {'x':65,'y':90}],
                       brush_guid=brushes[0]['guid'] if brushes else None, part_guid=part,
                       deformer_guid=warp_pair[0]['guid'])
    assert path['part_guid'] == part and path['deformer_guid'] == warp_pair[0]['guid']
    if brushes:
        assert path['brush_guid'] == brushes[0]['guid']
    await model('undo')
    assert not (await model('inspect_art_paths'))['art_paths']
    await model('redo')
    await model('save_as_new', path='artpath.cmo3')
    await model('close_saved')
    doc = await reopen('artpath.cmo3')
    assert (await model('inspect_art_paths', object_guid=path['guid']))['art_paths'][0]['forms'] == path['forms']

    atlas = await model('edit_texture_atlas', action='create_page', name='Release', width=256, height=256)
    await model('edit_texture_atlas', action='auto_layout', page_guid=atlas['pages'][0]['guid'])
    check = await model('preflight_runtime_export')
    assert any('ArtPath' in reason for reason in check['blockers'])
    await model('set_visibility', object_guid=path['guid'], visible=False)
    check = await model('preflight_runtime_export', target_version='5.0')
    assert any('Offscreen part requires SDK 5.3' in reason for reason in check['blockers'])

    await model('import_physics3', path='probe.physics3.json')
    assert (await model('export_physics3', path='expanded.physics3.json'))['source_group_count'] == 1
    frames = [{'ParamAngleZ': i % 41 - 20} for i in range(241)]
    trace = await model('simulate_physics', frames=frames, warmup_frames=30)
    ticket = await model('simulate_physics', frames=frames, warmup_frames=30, execution='job', request_id='physics')
    assert await finish_job(ticket) == trace
    values = [row['outputs']['ParamAngleX'] for row in trace['frames']]
    summary = await model('simulate_physics', frames=frames, warmup_frames=30, output_parameter_ids=['ParamAngleX'],
                          result_mode='summary')
    assert summary['frame_count'] == len(frames) and 'frames' not in summary
    assert summary['summary'] == {'ParamAngleX': {'minimum': min(values), 'maximum': max(values)}}

    # The fixture must also save in FREE, which limits parameter bindings per object.
    await model('add_parameter_keys', object_guid=paint, parameter_id='ParamAngleY', values=[10])
    await model('add_parameter_keys', object_guid=old, parameter_id='ParamAngleZ', values=[10])
    saved = await model('save_as_new', path='expanded.cmo3')
    assert saved['saved'], saved
    created = await invoke('create_animation_scene', model_document_uid=doc, name='Expanded', fps=30, duration_frames=30)
    animation, track = created['document_uid'], created['model_track_guid']

    async def anim(op, **args):
        return await invoke(op, document_uid=animation, **args)

    denied = await anim('edit_animation_scene', patch={'duration_frames': 10})
    assert not denied['applied'] and denied['conflicts']
    edited = await anim('edit_animation_scene', patch={'fps':60,'duration_frames':90,'loop':True,
                                                       'fade_in_seconds':.25,'fade_out_seconds':.75})
    assert edited['duration_frames'] == 90
    await anim('undo')
    assert (await anim('document'))['fps'] == 30
    await anim('redo')
    if probe:
        await probe(f'add-timeline-event={animation}')
        with_event = await anim('edit_animation_scene', patch={'duration_frames': 45})
        assert not with_event['applied'] and {'track_guid': track, 'event_frame': 70} in with_event['conflicts'], with_event
        await probe(f'remove-timeline-event={animation}')
    shortened = await anim('edit_animation_scene', patch={'duration_frames': 45})
    assert shortened['applied'] and shortened['duration_frames'] == 45
    await anim('undo')
    entries = [{'track_guid': track, 'parameter_id': param, 'keys': [{'frame': frame, 'value': 8}]}
               for param,frame in [('ParamAngleX',12),('ParamAngleY',15),('ParamAngleZ',18)]]
    await anim('batch_edit_timeline', edits=entries)
    await anim('undo')
    assert not (await anim('get_timeline_keys', track_guid=track, parameter_id='ParamAngleX'))['keys']
    await anim('redo')
    await anim('batch_edit_timeline', edits=[{'track_guid': track, 'parameter_id':'ParamAngleX', 'remove_frames':[12, 13]}],
               reject='existing key')
    assert (await anim('get_timeline_keys', track_guid=track, parameter_id='ParamAngleX'))['keys'][0]['frame'] == 12
    motion = await anim('export_motion3', track_guid=track, path='expanded.motion3.json')
    meta = motion['meta']
    assert (meta['Fps'], meta['Duration'], meta['Loop'], meta['FadeInTime'], meta['FadeOutTime']) == (60, .5, True, .25, .75)
    await anim('save_as_new', path='expanded.can3')
    (work/'expanded.can3').rename(work/'expanded-moved.can3')
    other = await reopen('probe.can3', document_type='animation_scene')
    assert other == await reopen('probe.can3')
    await invoke('close_saved', document_uid=other)
    await anim('close_saved')
    animation = await reopen('expanded-moved.can3', document_type='animation_scene')
    reopened = await anim('document')
    assert reopened['fps'] == 60 and reopened['duration_frames'] == 90
    await anim('close_saved')

    expression = [{'id':'ParamAngleX','value':3,'blend':'Add'}, {'id':'ParamAngleY','value':.5,'blend':'Multiply'},
                  {'id':'ParamAngleZ','value':7,'blend':'Overwrite'}]
    await model('export_expression3', path='blend.exp3.json', parameters=expression, fade_in_seconds=0, fade_out_seconds=0)
    bad_expr = json.loads((work/'blend.exp3.json').read_text(encoding='utf8'))
    default_expr = {'Parameters': [{'Id': 'ParamAngleX', 'Value': 1}]}
    (work/'default.exp3.json').write_text(json.dumps(default_expr), encoding='utf8')
    assert (await model('preflight_runtime_export',
        expressions=[{'name': 'Default', 'path': 'default.exp3.json'}]))['ready']
    bad_expr['Parameters'][0]['Blend'] = 'Invalid'
    (work/'bad.exp3.json').write_text(json.dumps(bad_expr), encoding='utf8')
    invalid = {'expressions': [{'name':'bad', 'path':'bad.exp3.json'}]}
    assert (await model('preflight_runtime_export', **invalid))['diagnostics'][0]['field'] == 'Parameters[0].Blend'
    ticket = await model('export_runtime_package', directory='invalid-export', request_id='bad-exp', **invalid)
    await finish_job(ticket, error='Parameters[0].Blend')
    assert not (work/'invalid-export').exists()
    bad_motion = json.loads((work/'expanded.motion3.json').read_text(encoding='utf8'))
    bad_motion['Curves'][0]['Segments'].pop()
    (work/'bad.motion3.json').write_text(json.dumps(bad_motion), encoding='utf8')
    assert 'Segments' in (await model('preflight_runtime_export',
        motions=[{'group':'Idle','path':'bad.motion3.json'}]))['diagnostics'][0]['field']
    bad_physics = json.loads((work/'probe.physics3.json').read_text(encoding='utf8'))
    bad_physics['PhysicsSettings'][0]['Output'][0]['VertexIndex'] = 200
    (work/'bad.physics3.json').write_text(json.dumps(bad_physics), encoding='utf8')
    assert (await model('preflight_runtime_export', physics_path='bad.physics3.json'))['diagnostics'][0]['field'].endswith('.VertexIndex')
    attachments = dict(motions=[{'group':'Idle','path':'expanded.motion3.json'}],
                       expressions=[{'name':'Blend','path':'blend.exp3.json'}], physics_path='expanded.physics3.json')
    preflight = await model('preflight_runtime_export', **attachments)
    assert preflight['ready'], preflight
    ticket = await model('export_runtime_package', directory='expanded-sdk', request_id='sdk', name='expanded', **attachments)
    package = await finish_job(ticket)
    if probe:
        await sdk_check(editor, work, Path(package['model3']))
    if high_resolution:
        import psutil
        for width,height in [(3840,2160),(7680,4320)]:
            size = dict(width=width,height=height,poses=[{'name':'neutral','parameters':{}}])
            start = time.monotonic()
            ticket = await model('render_pose_set', directory=f'large-{width}', request_id=f'large-{width}', **size)
            tick = time.monotonic()
            await asyncio.wait_for(invoke('status'), timeout=6)
            status_time = time.monotonic() - tick
            baseline = await finish_job(ticket)
            if width == 3840:
                bad_directory = work / 'bad-diff'
                bad_directory.mkdir()
                (bad_directory / 'manifest.json').write_bytes(Path(baseline['manifest']).read_bytes())
                Image.new('RGBA', (16, 16)).save(bad_directory / 'neutral.png')
                malformed = await model('render_pose_set', directory='bad-diff-output', request_id='bad-diff',
                                        baseline_manifest=str(bad_directory / 'manifest.json'), **size)
                await finish_job(malformed, error='Baseline image dimensions differ')
                assert not (work / 'bad-diff-output').exists()
            ticket = await model('render_pose_set', directory=f'diff-{width}', request_id=f'diff-{width}',
                                 baseline_manifest=baseline['manifest'], **size)
            comparison = await finish_job(ticket)
            manifest = json.loads(Path(comparison['manifest']).read_text(encoding='utf8'))
            assert manifest['frames'][0]['difference']['changed_pixels'] == 0
            peak = psutil.Process(pid).memory_info().peak_wset // (1024**2)
            print(f'RENDER_{width}x{height} seconds={time.monotonic()-start:.3f} status={status_time:.3f} peak_working_set_mib={peak}', flush=True)
    await model('close_saved')
    assert not (await invoke('status'))['documents']
    print('EXPANSION_MCP_STDIO_OK', flush=True)


async def sdk_check(editor, work, model3):
    refs = json.loads(model3.read_text(encoding='utf8'))['FileReferences']
    classes = work/'sdk-classes'
    classes.mkdir()
    lib = str(editor/'app/lib/*')
    source = Path(__file__).with_name('NativeSdkCheck.java').resolve()
    await asyncio.to_thread(subprocess.run, ['javac','--release','17','-cp',lib,'-d',str(classes),str(source)],
                            check=True, timeout=20)
    sdk_paths = [refs['Moc'], refs['Motions']['Idle'][0]['File'], refs['Expressions'][0]['File']]
    command = [str(editor/'app/jre/bin/java.exe'), '-Djava.library.path=app/dll64;app/dll64/windows-amd64',
               '-cp',os.pathsep.join((str(classes),lib)), 'cubism53.mcp.NativeSdkCheck',
               *(str(model3.parent/path) for path in sdk_paths), 'require-mask']
    await asyncio.to_thread(subprocess.run, command, cwd=editor, check=True, timeout=25)
