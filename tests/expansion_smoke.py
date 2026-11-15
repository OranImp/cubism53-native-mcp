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
    combined = await model('preflight_runtime_export', motions=[{'group':'Idle', 'path':'bad.motion3.json'}],
                           expressions=invalid['expressions'], physics_path='bad.physics3.json')
    assert len(combined['diagnostics']) == 3
    attachments = dict(motions=[{'group':'Idle','path':'expanded.motion3.json'}],
                       expressions=[{'name':'Blend','path':'blend.exp3.json'}], physics_path='expanded.physics3.json')
    preflight = await model('preflight_runtime_export', **attachments)
    assert preflight['ready'], preflight
    ticket = await model('export_runtime_package', directory='expanded-sdk', request_id='sdk', name='expanded', **attachments)
    package = await finish_job(ticket)
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
    await check_v040(invoke, work)
    await check_legacy_gaps(invoke, work)
    if probe:
        await sdk_check(editor, work, Path(package['model3']), work/'offset.motion3.json')


async def check_v040(invoke, work):
    opened = await invoke('open_project', path='expanded.cmo3', wait_seconds=12)
    doc = opened['document_uid']

    async def model(op, **args):
        return await invoke(op, document_uid=doc, **args)

    listing = (await model('objects'))['objects']
    assert (await model('objects', sections=['objects'], offset=1, limit=1))['objects'] == listing[1:2]
    paint, old = [next(row['guid'] for row in listing if row['name'] == name) for name in ('Paint', 'Old')]
    before = await model('document')
    plan = await model('preflight_model_edit', texture_objects=[paint],
                       parameter_keys=[{'object_guid': paint, 'parameter_id': 'ParamAngleZ', 'values': [10]}])
    assert plan['impact'][0]['parameter_count'] == 3 and plan['impact'][1]['affected_mesh_guids']
    assert (await model('document'))['revision'] == before['revision']
    assert bool(plan['blockers']) == plan['free_model_limits'], plan
    if plan['free_model_limits']:
        assert plan['blockers'][0]['kind'] == 'native_parameter_binding_limit', plan
        await model('add_parameter_keys', object_guid=paint, parameter_id='ParamAngleZ', values=[10])
        blocked = await model('save_as_new', path='free-rejected.cmo3')
        assert blocked['saved'] is False and blocked['bytes'] == 0 and blocked['reason'] == 'native_edit_restriction', blocked
        assert not (work/'free-rejected.cmo3').exists()
        print('FREE_SAVE_RESTRICTION_REPORTED', flush=True)
        await model('undo')
        before = await model('document')
    masks = (await model('inspect_model_dependencies', kind='object', id=old))['objects'][0]['mask_user_guids']
    assert masks
    await model('set_part_offscreen', part_guid=masks[0], enabled=False, reject='clipping')
    source = await model('object', object_guid=paint)
    await model('set_form_properties', object_guid=paint, form_guid=source['default_form_guid'],
                properties={'draw_order': 1.5}, reject='integer')
    # Put the new parameter on a mesh whose FREE binding count allows it; native authoring still handles existing settings.
    mesh = await model('object', object_guid=old)
    mesh_positions = next(row['positions'] for row in mesh['forms'] if row['guid'] == mesh['default_form_guid'])
    temporary_mesh = (await model('duplicate_objects', object_guids=[old]))['guid_map'][old]
    blend = await model('create_blend_shape', object_guid=old, base_form_guid=mesh['default_form_guid'],
                        parameter_id='ParamAuditBlend', name='Audit Blend', positions=mesh_positions)
    bound = await model('edit_blend_shape', object_guid=temporary_mesh, parameter_id=blend['parameter_id'],
                        action='bind', positions=mesh_positions)
    assert bound['parameter_guid'] == blend['parameter_guid']
    await model('undo', expected_revision=before['revision'], reject='revision changed')
    await model('edit_blend_shape', object_guid=temporary_mesh, parameter_id=blend['parameter_id'],
                action='update', form_guid=bound['form_guid'], positions=mesh_positions)
    await model('edit_blend_shape', object_guid=temporary_mesh, parameter_id=blend['parameter_id'], action='unbind')
    await model('undo')
    await model('redo')
    plan = await model('plan_delete_objects', object_guids=[temporary_mesh])
    await model('delete_objects', object_guids=[temporary_mesh], plan_id=plan['plan_id'])
    await model('undo')
    await model('redo')

    path = (await model('inspect_art_paths'))['art_paths'][0]
    point, form = path['forms'][0]['points'][0]['guid'], path['forms'][0]['guid']
    edited = await model('edit_art_path', object_guid=path['guid'], form_guid=form,
                         points=[{'point_guid': point, 'x': 55, 'y': 78}])
    assert edited['forms'] != path['forms'] and edited['forms'][0]['points'][0]['guid'] == point
    await model('undo')
    assert (await model('inspect_art_paths'))['art_paths'][0]['forms'] == path['forms']
    await model('redo')
    vertices = (await model('evaluate', object_guids=[paint], base_pose='default'))['objects'][0]['canvas_positions']
    triangle = source['indices'][:3]
    xy = [sum(vertices[index * 2 + axis] for index in triangle) / 3 for axis in range(2)]
    linked = await model('edit_art_path', object_guid=path['guid'], form_guid=form,
                         points=[{'point_guid': point, 'x': xy[0], 'y': xy[1]}],
                         bindings=[{'point_guid': point, 'mesh_guid': paint}])
    assert linked['bindings'] == [{'point_guid': point, 'mesh_guid': paint}], linked
    impact = await model('inspect_model_dependencies', kind='object', id=paint)
    assert impact['objects'][0]['art_path_bindings'][0]['path_guid'] == path['guid']
    await model('auto_generate_mesh', object_guid=paint, preset='small', reject='ArtPath')
    await model('undo')
    assert not (await model('inspect_art_paths'))['art_paths'][0]['bindings']
    await model('redo')

    checked = await model('import_physics3', path='probe.physics3.json', replace=True, preflight=True)
    assert checked['ready'] and checked['replaced_group_guids']
    saved_groups = await model('physics')
    particles = [{'position': [0, 0]}, {'position': [0, 10], 'radius': 10}]
    replaced = await model('edit_physics_groups', edits=[{'action': 'add', 'name': 'Audit Physics', 'particles': particles,
        'inputs': [{'parameter_id': 'ParamAngleZ', 'type': 'angle'}],
        'outputs': [{'parameter_id': 'ParamAngleX', 'type': 'angle', 'vertex_index': 1}]}], fps=60)
    assert len(replaced['groups']) == len(saved_groups['groups']) + 1 and replaced['fps'] == 60
    added_group = replaced['groups'][-1]['guid']
    await model('edit_physics_groups', edits=[{'action': 'move', 'group_guid': added_group, 'index': 0}])
    await model('undo')
    await model('redo')
    changed = await model('edit_physics_groups', edits=[{'action': 'remove', 'group_guid': added_group}])
    assert all(row['guid'] != added_group for row in changed['groups'])
    for _ in range(3):
        await model('undo')
    assert (await model('physics')) == saved_groups

    # Use an existing saved file for the whole multi-track persistence case.
    animation = (await invoke('open_project', path='expanded-moved.can3', wait_seconds=12))['document_uid']

    async def anim(op, **args):
        return await invoke(op, document_uid=animation, **args)

    added = await anim('edit_animation_tracks', action='append', model_path='expanded.cmo3', start_frame=10,
                       duration_frames=20, internal_offset=3)
    track = added['track']['guid']
    await anim('undo')
    assert all(row['guid'] != track for row in (await anim('document'))['tracks'])
    await anim('redo')
    await anim('set_timeline_keys', track_guid=track, parameter_id='ParamAngleX', frame_space='scene',
               keys=[{'frame': 13, 'value': 9}])
    sampled = await anim('sample_timeline_parameters', track_guid=track, parameter_id='ParamAngleX', frame_space='scene', frames=[13])
    assert sampled['samples'] == [{'frame': 13, 'value': 9}]
    local = await anim('get_timeline_keys', track_guid=track, parameter_id='ParamAngleX')
    assert any(row['frame'] == 6 for row in local['keys']), local
    events = await anim('edit_animation_events', track_guid=track, events=[{'frame': 14, 'text': 'Cue'}])
    assert events['events'] == [{'frame': 14, 'text': 'Cue'}], events
    await anim('undo')
    assert not (await anim('edit_animation_events', track_guid=track))['events']
    await anim('redo')
    saved = await anim('save_as_new', path='offset.can3')
    assert saved['saved'], saved
    assert (await anim('close_saved', wait_seconds=6))['closed']
    animation = (await invoke('open_project', path='offset.can3', wait_seconds=12))['document_uid']
    assert (await anim('edit_animation_events', track_guid=track))['events'] == events['events']
    await anim('export_motion3', track_guid=track, path='offset.motion3.json')
    motion = json.loads((work / 'offset.motion3.json').read_text(encoding='utf8'))
    assert motion['UserData'] == [{'Time': .117, 'Value': 'Cue'}], motion
    assert motion['Meta']['UserDataCount'] == 1 and motion['Meta']['TotalUserDataSize'] == 3
    await anim('remove_timeline_keys', track_guid=track, parameter_id='ParamAngleX', frame_space='scene', frames=[13])
    await anim('undo')
    await anim('save_as_new', path='offset-audit.can3')
    await anim('close_saved', wait_seconds=6)
    assert (await model('save_as_new', path='audited.cmo3'))['saved']
    assert (await model('close_saved', wait_seconds=6))['closed']
    doc = (await invoke('open_project', path='audited.cmo3', wait_seconds=12))['document_uid']
    assert (await model('inspect_art_paths'))['art_paths'][0]['bindings'] == linked['bindings']
    large = await model('simulate_physics', frames=[{'ParamAngleZ': 1}] * 8100, warmup_frames=0,
                        execution='job', request_id='large-physics-trace')
    for _ in range(200):
        result = await invoke('job_status', job_id=large['job_id'], frame_offset=8099, frame_limit=1)
        if result['state'] in ('succeeded', 'failed'):
            break
        await asyncio.sleep(.1)
    assert result['state'] == 'succeeded' and result['result']['frame_count'] == 8100, result
    assert result['result']['frames'][0]['frame'] == 8099
    await model('close_saved', wait_seconds=6)
    jobs = await invoke('list_jobs', operation='simulate_physics', state='succeeded', limit=1)
    assert jobs['jobs'][0]['operation'] == 'simulate_physics'
    paged = await invoke('job_status', job_id=jobs['jobs'][0]['job_id'], frame_offset=10, frame_limit=2)
    assert [row['frame'] for row in paged['result']['frames']] == [10, 11]
    assert paged['result']['next_frame_offset'] == 12
    print('V040_NATIVE_AND_STDIO_CHECKS_OK', flush=True)


async def check_legacy_gaps(invoke, work):
    from PIL import ImageDraw
    image = Image.new('RGBA', (96, 96))
    ImageDraw.Draw(image).ellipse((9, 12, 84, 86), fill=(250, 40, 50, 255))
    image.save(work/'mesh-audit.png')
    padded = Image.new('RGBA', (160, 160))
    padded.paste(image, (32, 32))
    padded.save(work/'mesh-audit-large.png')
    uid = (await invoke('create_blank_project', width=256, height=256))['document_uid']

    async def edit(op, **kwargs):
        return await invoke(op, document_uid=uid, **kwargs)

    first, second = [
        (await edit('import_png', path='mesh-audit.png', name=f'Mesh {i}', affine=[1, 0, 0, 1, i * 16, 0]))['guid']
        for i in range(2)
    ]
    ticket = await edit('auto_generate_mesh', object_guid=first, preset='small')
    for _ in range(200):
        result = await edit('auto_mesh_status', job_id=ticket['job_id'])
        if result['state'] in ('succeeded', 'failed'):
            break
        await asyncio.sleep(.1)
    assert result['state'] == 'succeeded' and result['after']['triangles'], result
    await edit('undo')
    await edit('redo')
    png = await edit('export_mesh_texture', object_guid=first, path='mesh-audit-copy.png')
    assert png['width'] == 96
    await edit('replace_mesh_texture', object_guid=first, path='mesh-audit-copy.png')
    await edit('replace_mesh_texture_resized', object_guid=second, path='mesh-audit-large.png', origin_offset=[-32, -32])

    parameter = await edit('create_parameter', id='ParamAudit', name='Audit')
    await edit('update_parameter', parameter_id=parameter['id'], patch={'name': 'Audit Rename'})
    group = await edit('create_parameter_group', name='Audit Group')
    moved = await edit('move_parameter_to_group', parameter_id=parameter['id'], group_guid=group['guid'])
    assert moved['group_guid'] == group['guid']
    await edit('add_parameter_keys', object_guid=first, parameter_id=parameter['id'], values=[-1, 1])
    await edit('move_parameter_keys', object_guid=first, parameter_id=parameter['id'],
               moves=[{'from_value': 1, 'to_value': .5}])
    await edit('remove_parameter_keys', object_guid=first, parameter_id=parameter['id'], values=[.5])
    mesh = await edit('object', object_guid=first)
    base = next(form for form in mesh['forms'] if form['guid'] == mesh['default_form_guid'])
    await edit('set_form_positions', object_guid=first, form_guid=base['guid'], positions=base['positions'], compact=True)
    await edit('set_form_colors', object_guid=first, form_guid=base['guid'], multiply_color=[.8, .8, .8])
    await edit('set_form_properties', object_guid=first, form_guid=base['guid'], properties={'opacity': .9})
    await edit('set_blend_mode', object_guid=first, blend_mode='NORMAL')
    clipped = await edit('set_clipping', object_guid=first, mask_guids=[second, second])
    assert clipped['mask_guids'] == [second]
    await edit('set_clipping', object_guid=first, mask_guids=[])
    await edit('evaluate', object_guids=[first], poses=[{'name': 'control'}, {'name': 'other', 'parameters': {'ParamAudit': -1}}])
    await edit('release_renderers')

    a = mesh['vertex_uids']
    b = (await edit('object', object_guid=second))['vertex_uids']
    pairs = [{'a_uid': a[i], 'b_uid': b[i], 'weight_a': .5, 'weight_b': .5} for i in range(2)]
    glue = await edit('create_glue', mesh_a_guid=first, mesh_b_guid=second, pairs=pairs[:1])
    await edit('add_glue_pairs', glue_guid=glue['guid'], pairs=pairs[1:])
    await edit('update_glue_weights', glue_guid=glue['guid'], pairs=[{**pairs[0], 'weight_a': .7}])
    await edit('set_glue_intensity', glue_guid=glue['guid'], intensity=.5)
    assert (await edit('list_glues', include_pairs=False))['glues'][0]['pair_count'] == 2
    for pair in pairs:
        await edit('remove_glue_pairs', glue_guid=glue['guid'], pairs=[{key: pair[key] for key in ('a_uid', 'b_uid')}])

    part = await edit('create_part', name='Audit Part')
    await edit('set_part_offscreen', part_guid=part['guid'], enabled=True)
    warp = await edit('create_warp', name='Audit Warp', x=15, y=15, width=24, height=24, part_guid=part['guid'])
    await edit('reparent', object_guid=first, parent_guid=warp['guid'])
    await edit('fit_warp_to_children', object_guid=warp['guid'])
    await edit('undo')
    rotation = await edit('create_rotation', name='Audit Rotation', x=25, y=25, parent_guid=warp['guid'], part_guid=part['guid'])
    assert rotation['parent_guid'] == warp['guid']
    await edit('edit_part_rendering', part_guid=part['guid'], patch={'offscreen': False, 'draw_order_group': False})

    physics = await edit('import_physics3', path='probe.physics3.json')
    await edit('update_physics_group', group_guid=physics['groups'][0]['guid'],
               patch={'inputs': [{'index': 0, 'weight': 90}]}, expected_inputs={0: 'ParamAngleZ'})
    trace = await edit('simulate_physics', frames=[{'ParamAngleZ': 1}], trace_path='mesh-audit-trace.json')
    assert trace['frame_count'] == 1 and 'frames' not in trace
    assert len(json.loads((work/'mesh-audit-trace.json').read_text(encoding='utf8'))['frames']) == 1
    atlas = await edit('edit_texture_atlas', action='create_page', name='Audit Atlas', width=512, height=512)
    mat_a = (await edit('export_mesh_texture', object_guid=first, path='mesh-audit-texture-a.png'))['material_guid']
    mat_b = (await edit('export_mesh_texture', object_guid=second, path='mesh-audit-texture-b.png'))['material_guid']
    page = atlas['pages'][0]['guid']
    placements = [{'material_guid': guid, 'page_guid': page, 'x': 0, 'y': 0} for guid in (mat_a, mat_b)]
    conflict = await edit('edit_texture_atlas', action='place', placements=placements, preflight=True)
    assert not conflict['ready'] and conflict['conflicts'][0]['other_material_guid'] == mat_a, conflict
    placements[1]['x'] = 110
    placed = await edit('edit_texture_atlas', action='place', placements=placements)
    assert len(placed['pages'][0]['placements']) == 2
    save = await edit('save_as_new', path='mesh-audit.cmo3')
    assert save['saved'], save
    await edit('close_saved', wait_seconds=6)
    print('LEGACY_TOOL_GAPS_STDIO_OK', flush=True)


async def sdk_check(editor, work, model3, motion_with_events):
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
               *(str(model3.parent/path) for path in sdk_paths), 'require-mask', str(motion_with_events)]
    await asyncio.to_thread(subprocess.run, command, cwd=editor, check=True, timeout=25)
