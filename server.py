"""MCP for the in-process adapter for verified Cubism 5.3 builds."""
from __future__ import annotations
from contextlib import asynccontextmanager
import math
from typing import Any, Literal
from mcp.server.fastmcp import FastMCP
from pydantic import BaseModel, ConfigDict, Field

from bridge.client import call
from bridge.connect import connect_editor
from bridge.dispatch import RequestChannel
from bridge.preview import CanvasPreview

preview_client = None
editor_requests = None
status_requests = None


@asynccontextmanager
async def lifespan(_server):
    global preview_client, editor_requests, status_requests
    editor_requests = RequestChannel(8, 'cubism-editor')
    status_requests = RequestChannel(2, 'cubism-job-status')
    try:
        yield {}
    finally:
        editor_requests.close()
        status_requests.close()
        if preview_client is not None:
            await preview_client.close()
            preview_client = None


mcp = FastMCP('Cubism 5.3 Internal Native Bridge', lifespan=lifespan, log_level='WARNING')


class ToolInput(BaseModel):
    model_config = ConfigDict(extra='forbid', allow_inf_nan=False)

    def provided(self):
        return self.model_dump(exclude_none=True)


class ParameterPatch(ToolInput):
    name: str | None = None
    description: str | None = None
    minimum: float | None = None
    maximum: float | None = None
    default_value: float | None = None


class KeyMove(ToolInput):
    from_value: float
    to_value: float


class PhysicsWeight(ToolInput):
    index: int
    weight: float


class PhysicsParticle(ToolInput):
    index: int
    mobility: float | None = None
    delay: float | None = None
    acceleration: float | None = None
    radius: float | None = None
    position: tuple[float, float] | None = None


class PhysicsPatch(ToolInput):
    enabled: bool | None = None
    inputs: list[PhysicsWeight] | None = None
    outputs: list[PhysicsWeight] | None = None
    vertices: list[PhysicsParticle] | None = None
    normalization_position: tuple[float, float, float] | None = None
    normalization_angle: tuple[float, float, float] | None = None


class AutoMeshSettings(ToolInput):
    outer_density: int | None = None
    inner_density: int | None = None
    margin: int | None = None
    inner_margin: int | None = None
    alpha_threshold: int | None = None


class GluePair(ToolInput):
    a_uid: str
    b_uid: str


class GlueWeight(GluePair):
    weight_a: float = 0.5
    weight_b: float = 0.5


class AtlasPosition(ToolInput):
    page_guid: str
    x: int
    y: int
    rotation: Literal[0, 90, 180, 270] = 0


class AtlasPlacement(AtlasPosition):
    material_guid: str


class TimelineKey(ToolInput):
    frame: int
    value: float
    interpolation: Literal['linear', 'step', 'smooth'] = 'linear'


class TimelineEdit(ToolInput):
    track_guid: str
    parameter_id: str
    keys: list[TimelineKey] = Field(default_factory=list)
    remove_frames: list[int] = Field(default_factory=list)


class AnimationScenePatch(ToolInput):
    fps: float | None = None
    duration_frames: int | None = None
    loop: bool | None = None
    fade_in_seconds: float | None = None
    fade_out_seconds: float | None = None


class PartRenderingPatch(ToolInput):
    offscreen: bool | None = None
    mask_guids: list[str] | None = None
    invert_mask: bool | None = None
    draw_order_group: bool | None = None


class MirrorForm(ToolInput):
    source_form_guid: str
    target_form_guid: str
    source_parameters: dict[str, float] | None = None
    target_parameters: dict[str, float] | None = None


class ArtPathPoint(ToolInput):
    x: float
    y: float
    width: float | None = None
    opacity: float | None = None
    corner: bool = False


class ExpressionParameter(ToolInput):
    id: str
    value: float
    blend: Literal['Add', 'Multiply', 'Overwrite']


class RenderPose(ToolInput):
    name: str
    parameters: dict[str, float] = Field(default_factory=dict)


class BatchEdit(ToolInput):
    operation: Literal['set_visibility', 'set_clipping', 'rename_object', 'move_to_part',
                       'set_form_positions', 'set_form_properties', 'add_parameter_keys',
                       'move_parameter_keys', 'remove_parameter_keys']
    arguments: dict[str, Any]


class RuntimeMotion(ToolInput):
    group: str
    path: str


class RuntimeExpression(ToolInput):
    name: str
    path: str


async def native(operation, **arguments):
    channel = status_requests if operation == "job_status" else editor_requests
    return await channel.run(call, operation, **arguments)

@mcp.tool()
async def cubism_native_connect() -> dict[str,Any]:
    """Connect to the already-running supported Cubism 5.3 editor after a restart.

    Builds and attaches this workspace's adapter without screen input. Does not
    start or close the editor, open documents, or change project data.
    """
    return await editor_requests.run(connect_editor)

@mcp.tool()
async def cubism_native_status() -> dict[str,Any]:
    """Inspect open native model documents, object counts, and stable deformer UUIDs."""
    return await native('status')

@mcp.tool()
async def cubism_native_document(document_uid:str) -> dict[str,Any]:
    """Read a specific native document and its warp grid coordinates."""
    return await native('describe',document_uid=document_uid)

@mcp.tool()
async def cubism_native_import_png(document_uid:str,path:str,name:str,affine:list[float],draw_order:int=500) -> dict[str,Any]:
    """Import a workspace PNG as native material and ArtMesh, preserving native undo.

    affine maps PNG pixels to canvas: [a,b,c,d,tx,ty].
    """
    return await native('import_png',document_uid=document_uid,path=path,name=name,affine=affine,draw_order=draw_order)

@mcp.tool()
async def cubism_native_set_visibility(document_uid:str,object_guid:str,visible:bool) -> dict[str,Any]:
    """Set mesh, deformer or part visibility with a native undo record."""
    return await native('set_visibility',document_uid=document_uid,object_guid=object_guid,visible=visible)

@mcp.tool()
async def cubism_native_set_clipping(document_uid:str,object_guid:str,mask_guids:list[str],invert:bool=False) -> dict[str,Any]:
    """Set native drawable clipping masks with native undo."""
    return await native('set_clipping',document_uid=document_uid,object_guid=object_guid,mask_guids=mask_guids,invert=invert)

@mcp.tool()
async def cubism_native_objects(document_uid: str,
                                sections: list[Literal['objects', 'parts', 'parameters', 'parameter_groups']] | None = None,
                                part_guid: str | None = None, object_types: list[str] | None = None,
                                parameter_id: str | None = None) -> dict[str,Any]:
    """List native mesh/deformer identities and available parameter ranges."""
    return await native('objects', document_uid=document_uid, sections=sections, part_guid=part_guid,
                        object_types=object_types, parameter_id=parameter_id)

@mcp.tool()
async def cubism_native_object(document_uid: str, object_guid: str, form_guids: list[str] | None = None,
                               geometry: bool = True) -> dict[str,Any]:
    """Read a native mesh or deformer, including keyform UUIDs and vertex positions."""
    return await native('object', document_uid=document_uid, object_guid=object_guid, form_guids=form_guids, geometry=geometry)

@mcp.tool()
async def cubism_native_replace_mesh_texture(document_uid:str,object_guid:str,path:str) -> dict[str,Any]:
    """Replace embedded material pixels with a same-size workspace PNG, with native undo.

    Keeps the material identity, image transform, mesh UVs, topology and keyforms.
    A material shared by several ArtMeshes updates every listed user.
    """
    return await native('replace_mesh_texture',document_uid=document_uid,object_guid=object_guid,path=path)

@mcp.tool()
async def cubism_native_export_mesh_texture(document_uid:str,object_guid:str,path:str) -> dict[str,Any]:
    """Export one embedded ArtMesh material to a new workspace PNG, without editing it.

    Returns the image-to-source transform and texture dimensions for lossless reuse.
    """
    return await native('export_mesh_texture',document_uid=document_uid,object_guid=object_guid,path=path)

@mcp.tool()
async def cubism_native_reparent(document_uid:str,object_guid:str,parent_guid:str|None=None) -> dict[str,Any]:
    """Attach a mesh or deformer to a native deformer while preserving its shape.

    Use null parent_guid to attach directly to the model root. Records native undo.
    """
    return await native('reparent',document_uid=document_uid,object_guid=object_guid,parent_guid=parent_guid)

@mcp.tool()
async def cubism_native_add_parameter_keys(document_uid:str,object_guid:str,parameter_id:str,
                                           values:list[float]) -> dict[str,Any]:
    """Add native keyforms at values of an existing normal parameter on a model object or Glue."""
    return await native('add_parameter_keys',document_uid=document_uid,object_guid=object_guid,
                        parameter_id=parameter_id,values=values)

@mcp.tool()
async def cubism_native_set_form_positions(document_uid:str,object_guid:str,form_guid:str,
                                          positions:list[float]) -> dict[str,Any]:
    """Set one mesh/warp keyform's flat x,y coordinate array, with native undo.

    Positions use the keyform's native local coordinates; vertex count stays fixed.
    """
    return await native('set_form_positions',document_uid=document_uid,object_guid=object_guid,
                        form_guid=form_guid,positions=positions)

@mcp.tool()
async def cubism_native_set_form_properties(document_uid:str,object_guid:str,form_guid:str,
                                            properties:dict[str,float]) -> dict[str,Any]:
    """Edit a drawable keyform's opacity or a rotation keyform's angle/origin/scale.

    Rotation properties: angle (degrees), origin_x, origin_y, scale. Opacity: 0..1.
    """
    return await native('set_form_properties',document_uid=document_uid,object_guid=object_guid,
                        form_guid=form_guid,properties=properties)

@mcp.tool()
async def cubism_native_set_mesh_topology(document_uid:str,object_guid:str,positions:list[float],
                                          indices:list[int]) -> dict[str,Any]:
    """Commit ArtMesh topology through the native mesh editor's transfer method.

    Positions use the source editable mesh coordinates. Cubism remaps all keyforms
    and original UVs onto the new triangles and records native topology undo.
    """
    return await native('set_mesh_topology',document_uid=document_uid,object_guid=object_guid,
                        positions=positions,indices=indices)

@mcp.tool()
async def cubism_native_evaluate(document_uid:str,object_guids:list[str],
                                  parameters:dict[str,float]|None=None,
                                  base_pose:Literal['current','default']='current') -> dict[str,Any]:
    """Evaluate native interpolation and canvas positions at temporary parameter values.

    Restores the previous parameter values before returning. Omitted parameters
    evaluate the current pose.
    """
    return await native('evaluate',document_uid=document_uid,object_guids=object_guids,
                        parameters={} if parameters is None else parameters,base_pose=base_pose)

@mcp.tool()
async def cubism_native_render_png(document_uid:str,path:str,width:int=1024,height:int=2011,
                                    parameters:dict[str,float]|None=None,
                                    base_pose:Literal['current','default']='current') -> dict[str,Any]:
    """Render the native model offscreen to a new transparent PNG inside the workspace.

    Uses Cubism's own renderer and a copied parameter set, without screen capture.
    """
    return await native('render_png',document_uid=document_uid,path=path,width=width,height=height,
                        parameters={} if parameters is None else parameters,base_pose=base_pose)

@mcp.tool()
async def cubism_native_open_project(path:str) -> dict[str,Any]:
    """Open a workspace .cmo3 or .can3 through Cubism's native document loader.

    Opening may be asynchronous; use status to obtain the resulting document UID.
    """
    return await native('open_project',path=path)

@mcp.tool()
async def cubism_native_create_blank_project(width:int,height:int) -> dict[str,Any]:
    """Create an unsaved blank Cubism model at the specified pixel dimensions.

    Uses Cubism's new-model factory with default parts and parameters. Returns
    the new document UID for save_as_new.
    """
    return await native('create_blank_project',width=width,height=height)

@mcp.tool()
async def cubism_native_create_warp(document_uid:str,name:str,x:float,y:float,width:float,height:float,
                                    columns:int=2,rows:int=2,bezier_columns:int=2,bezier_rows:int=2) -> dict[str,Any]:
    """Create a native root warp using the Editor's factory and undo manager.

    Bounds use native canvas coordinates. Creates an empty root warp; use reparent
    to attach artwork and add_parameter_keys to establish parameter keyforms.
    """
    return await native('create_warp',document_uid=document_uid,name=name,x=x,y=y,width=width,height=height,
                        columns=columns,rows=rows,bezier_columns=bezier_columns,bezier_rows=bezier_rows)

@mcp.tool()
async def cubism_native_create_rotation(document_uid:str,name:str,x:float,y:float,
                                        handle_length:float=100) -> dict[str,Any]:
    """Create a native root rotation deformer at a canvas pivot, with native undo."""
    return await native('create_rotation',document_uid=document_uid,name=name,x=x,y=y,handle_length=handle_length)

@mcp.tool()
async def cubism_native_undo(document_uid:str) -> dict[str,Any]:
    """Undo the most recent native document operation, including manual edits."""
    return await native('undo',document_uid=document_uid)

@mcp.tool()
async def cubism_native_redo(document_uid:str) -> dict[str,Any]:
    """Redo the most recently undone native document operation."""
    return await native('redo',document_uid=document_uid)

@mcp.tool()
async def cubism_native_save_as_new(document_uid:str,path:str) -> dict[str,Any]:
    """Save through Cubism to a new workspace .cmo3 or .can3, according to the document type."""
    return await native('save_as_new',document_uid=document_uid,path=path)

@mcp.tool()
async def cubism_native_close_saved(document_uid:str) -> dict[str,Any]:
    """Close an unmodified saved document. Refuses documents with unsaved changes."""
    return await native('close_saved',document_uid=document_uid)

@mcp.tool()
async def cubism_native_create_parameter(document_uid:str,id:str,name:str,minimum:float=-1,maximum:float=1,
                                         default_value:float=0) -> dict[str,Any]:
    """Create a native normal parameter in the root group, with native undo."""
    return await native('create_parameter',document_uid=document_uid,id=id,name=name,minimum=minimum,
                        maximum=maximum,default_value=default_value)

@mcp.tool()
async def cubism_native_physics(document_uid:str) -> dict[str,Any]:
    """Read embedded native physics groups, parameter links, particles and normalization."""
    return await native('physics',document_uid=document_uid)

@mcp.tool()
async def cubism_native_release_renderers(document_uid:str) -> dict[str,Any]:
    """Close this document's cached offscreen render sessions to return editor memory."""
    return await native('release_renderers',document_uid=document_uid)

@mcp.tool()
async def cubism_native_import_physics3(document_uid:str,path:str,replace:bool=False) -> dict[str,Any]:
    """Import workspace physics3 JSON with native undo; replace existing groups only if explicit."""
    return await native('import_physics3',document_uid=document_uid,path=path,replace=replace)

@mcp.tool()
async def cubism_native_simulate_physics(document_uid:str,frames:list[dict[str,float]],delta_seconds:float=1/60,
                                         warmup_frames:int=120, output_parameter_ids: list[str] | None = None,
                                         result_mode: Literal['trace', 'summary'] = 'trace',
                                         execution: Literal['sync', 'job'] = 'sync',
                                         request_id: str | None = None) -> dict[str,Any]:
    """Run Cubism's solver on isolated copies. Each frame supplies absolute drivers.

    Unspecified parameters reset to defaults each step. Does not change the editor pose.
    """
    return await native('simulate_physics',document_uid=document_uid,frames=frames,
                        delta_seconds=delta_seconds,warmup_frames=warmup_frames, output_parameter_ids=output_parameter_ids,
                        result_mode=result_mode, execution=execution, request_id=request_id)


@mcp.tool()
async def cubism_native_update_parameter(document_uid: str, parameter_id: str, patch: ParameterPatch) -> dict[str, Any]:
    """Edit the name, description, range or default of an existing ordinary parameter."""
    changes = patch.provided()
    if not changes:
        raise ValueError('Provide at least one parameter field')
    return await native('update_parameter', document_uid=document_uid, parameter_id=parameter_id, patch=changes)


@mcp.tool()
async def cubism_native_move_parameter_keys(document_uid: str, object_guid: str, parameter_id: str,
                                            moves: list[KeyMove]) -> dict[str, Any]:
    """Move existing ordinary parameter keys and their keyforms using an explicit old-to-new mapping."""
    return await native('move_parameter_keys', document_uid=document_uid, object_guid=object_guid,
                        parameter_id=parameter_id, moves=[move.provided() for move in moves])


@mcp.tool()
async def cubism_native_remove_parameter_keys(document_uid: str, object_guid: str, parameter_id: str,
                                              values: list[float]) -> dict[str, Any]:
    """Remove the specified ordinary parameter keys from a single object's keyform grid."""
    return await native('remove_parameter_keys', document_uid=document_uid, object_guid=object_guid,
                        parameter_id=parameter_id, values=values)


@mcp.tool()
async def cubism_native_update_physics_group(document_uid: str, group_guid: str,
                                            patch: PhysicsPatch) -> dict[str, Any]:
    """Patch one physics group. Row indices are zero-based in physics readback.

    Normalization tuples are [minimum, maximum, default]. Weights are 0–100.
    """
    changes = patch.provided()
    if not changes:
        raise ValueError('Provide at least one physics field')
    return await native('update_physics_group', document_uid=document_uid, group_guid=group_guid, patch=changes)


@mcp.tool()
async def cubism_native_move_to_part(document_uid: str, object_guid: str, part_guid: str | None = None,
                                    index: int = -1) -> dict[str, Any]:
    """Move an object in the Parts tree. Omit part_guid for the root or use index -1 to append."""
    return await native('move_to_part', document_uid=document_uid, object_guid=object_guid,
                        part_guid=part_guid, index=index)


@mcp.tool()
async def cubism_native_rename_object(document_uid: str, object_guid: str, name: str) -> dict[str, Any]:
    """Set a mesh, deformer or part's display name."""
    return await native('rename_object', document_uid=document_uid, object_guid=object_guid, name=name)


@mcp.tool()
async def cubism_native_set_blend_mode(document_uid: str, object_guid: str, blend_mode: str) -> dict[str, Any]:
    """Set the native color blend mode, such as NORMAL, SCREEN or OVERLAY, on an ArtMesh or offscreen part."""
    return await native('set_blend_mode', document_uid=document_uid, object_guid=object_guid,
                        blend_mode=blend_mode)


@mcp.tool()
async def cubism_native_set_part_offscreen(document_uid: str, part_guid: str, enabled: bool) -> dict[str, Any]:
    """Toggle part-level Offscreen Drawing. Enabling also groups the part's draw order."""
    return await native('set_part_offscreen', document_uid=document_uid, part_guid=part_guid, enabled=enabled)


@mcp.tool()
async def cubism_native_edit_part_rendering(document_uid: str, part_guid: str,
                                            patch: PartRenderingPatch) -> dict[str, Any]:
    """Edit offscreen drawing, clipping, inverted masks and draw order grouping as one native undo."""
    return await native('edit_part_rendering', document_uid=document_uid, part_guid=part_guid, **patch.provided())


@mcp.tool()
async def cubism_native_mirror_keyforms(document_uid: str, source_guid: str, target_guid: str,
                                       axis: Literal['x', 'y'], pivot: float, forms: list[MirrorForm],
                                       vertex_indices: list[int] | None = None) -> dict[str, Any]:
    """Reflect the named canvas coordinate about a pixel pivot into explicit target forms.

    ArtMesh vertex_indices maps each target vertex to a source vertex. Warp grid order follows the mirror axis.
    """
    return await native('mirror_keyforms', document_uid=document_uid, source_guid=source_guid, target_guid=target_guid,
                        axis=axis, pivot=pivot, forms=[form.provided() for form in forms], vertex_indices=vertex_indices)


@mcp.tool()
async def cubism_native_plan_delete_objects(document_uid: str, object_guids: list[str], cascade: bool = False,
                                            detach_references: bool = False) -> dict[str, Any]:
    """Preview an object deletion and its effects on children, masks, Glue, ArtPath and materials."""
    return await native('plan_delete_objects', document_uid=document_uid, object_guids=object_guids,
                        cascade=cascade, detach_references=detach_references)


@mcp.tool()
async def cubism_native_delete_objects(document_uid: str, object_guids: list[str], plan_id: str,
                                       cascade: bool = False, detach_references: bool = False) -> dict[str, Any]:
    """Apply a current, unblocked deletion plan as one native undo."""
    return await native('delete_objects', document_uid=document_uid, object_guids=object_guids,
                        plan_id=plan_id, cascade=cascade, detach_references=detach_references)


@mcp.tool()
async def cubism_native_inspect_art_paths(document_uid: str, object_guid: str | None = None) -> dict[str, Any]:
    """List ArtPath brushes, native control points, keyforms and mesh bindings."""
    return await native('inspect_art_paths', document_uid=document_uid, object_guid=object_guid)


@mcp.tool()
async def cubism_native_create_art_path(document_uid: str, name: str, points: list[ArtPathPoint],
                                        brush_guid: str | None = None, part_guid: str | None = None,
                                        deformer_guid: str | None = None, closed: bool = False) -> dict[str, Any]:
    """Create a native ArtPath from canvas points at the default pose. Omit brush_guid for Cubism's default stroke."""
    return await native('create_art_path', document_uid=document_uid, name=name,
                        points=[point.provided() for point in points], brush_guid=brush_guid,
                        part_guid=part_guid, deformer_guid=deformer_guid, closed=closed)


@mcp.tool()
async def cubism_native_auto_generate_mesh(document_uid: str, object_guid: str,
                                          preset: Literal['small', 'medium', 'large'] | None = None,
                                          settings: AutoMeshSettings | None = None) -> dict[str, Any]:
    """Start native automatic mesh generation for a single ArtMesh in the active Modeling document.

    Explicit settings override the optional preset. Use auto_mesh_status to check the returned job.
    """
    return await native('auto_generate_mesh', document_uid=document_uid, object_guid=object_guid,
                        preset=preset, settings=settings.provided() if settings is not None else None)


@mcp.tool()
async def cubism_native_auto_mesh_status(document_uid: str, job_id: str | None = None) -> dict[str, Any]:
    """Read an automatic mesh job. Omit job_id for the most recent job in this document."""
    return await native('auto_mesh_status', document_uid=document_uid, job_id=job_id)


@mcp.tool()
async def cubism_native_list_glues(document_uid: str) -> dict[str, Any]:
    """Read native Glue relationships, vertex pairs, weights and keyform intensities."""
    return await native('list_glues', document_uid=document_uid)


@mcp.tool()
async def cubism_native_create_glue(document_uid: str, mesh_a_guid: str, mesh_b_guid: str,
                                    pairs: list[GlueWeight]) -> dict[str, Any]:
    """Create a native Glue from explicit vertex UID pairs. Read vertex_uids with object first."""
    return await native('create_glue', document_uid=document_uid, mesh_a_guid=mesh_a_guid,
                        mesh_b_guid=mesh_b_guid, pairs=[pair.provided() for pair in pairs])


@mcp.tool()
async def cubism_native_update_glue_weights(document_uid: str, glue_guid: str,
                                            pairs: list[GlueWeight]) -> dict[str, Any]:
    """Update the weights of existing Glue vertex pairs. Each weight is in 0–1."""
    return await native('update_glue_weights', document_uid=document_uid, glue_guid=glue_guid,
                        pairs=[pair.provided() for pair in pairs])


@mcp.tool()
async def cubism_native_remove_glue_pairs(document_uid: str, glue_guid: str, pairs: list[GluePair]) -> dict[str, Any]:
    """Unbind explicit vertex pairs. Removing the last pair also removes the Glue."""
    return await native('remove_glue_pairs', document_uid=document_uid, glue_guid=glue_guid,
                        pairs=[pair.provided() for pair in pairs])


@mcp.tool()
async def cubism_native_set_glue_intensity(document_uid: str, glue_guid: str, intensity: float,
                                           form_guid: str | None = None) -> dict[str, Any]:
    """Set Glue intensity in 0–1 on the selected keyform or the default keyform."""
    return await native('set_glue_intensity', document_uid=document_uid, glue_guid=glue_guid,
                        intensity=intensity, form_guid=form_guid)


def preview_model_uids(value):
    if isinstance(value, dict):
        for key, item in value.items():
            if key.lower() in {'modeluid', 'externalappmodeluid'} and isinstance(item, str) and item:
                yield item
            else:
                yield from preview_model_uids(item)
    elif isinstance(value, list):
        for item in value:
            yield from preview_model_uids(item)


async def preview(document_uid: str, model_uid: str | None, method: str, **arguments) -> dict[str, Any]:
    global preview_client
    if preview_client is None:
        preview_client = CanvasPreview()
    try:
        await preview_client.ready()
    except PermissionError:
        raise
    except OSError as error:
        raise ConnectionError('Enable External application integration in Cubism to use canvas preview') from error
    document = await preview_client.rpc('GetDocument', {'DocumentUID': document_uid})
    candidates = sorted(set(preview_model_uids(document)))
    if not candidates:
        raise ValueError('No model view was found for this Cubism document')
    if model_uid is None and len(candidates) != 1:
        return {'applied': False, 'document_uid': document_uid, 'model_uids': candidates}
    target = model_uid or candidates[0]
    if target not in candidates:
        raise ValueError('model_uid does not belong to the requested document')
    result = await preview_client.rpc(method, {'ModelUID': target, **arguments})
    return {'applied': True, 'document_uid': document_uid, 'model_uid': target, 'result': result}


@mcp.tool()
async def cubism_native_set_preview_parameters(document_uid: str, parameters: dict[str, float],
                                                model_uid: str | None = None) -> dict[str, Any]:
    """Temporarily set canvas parameters through the official local WebSocket API."""
    if not parameters or any(not math.isfinite(value) for value in parameters.values()):
        raise ValueError('Provide at least one finite parameter value')
    return await preview(document_uid, model_uid, 'SetParameterValues',
                         Parameters=[{'Id': key, 'Value': value} for key, value in parameters.items()])


@mcp.tool()
async def cubism_native_clear_preview_parameters(document_uid: str, model_uid: str | None = None) -> dict[str, Any]:
    """Remove external overrides for a model view. Cubism may keep the last displayed value."""
    return await preview(document_uid, model_uid, 'ClearParameterValues')


@mcp.tool()
async def cubism_native_create_part(document_uid: str, name: str, parent_part_guid: str | None = None,
                                   index: int = -1) -> dict[str, Any]:
    """Create a native Part, with undo. Omit the parent for the root; -1 appends."""
    return await native('create_part', document_uid=document_uid, name=name,
                        parent_part_guid=parent_part_guid, index=index)


@mcp.tool()
async def cubism_native_create_parameter_group(document_uid: str, name: str, parent_group_guid: str | None = None,
                                              index: int = -1) -> dict[str, Any]:
    """Create a native parameter group. Omit the parent for the root; -1 appends."""
    return await native('create_parameter_group', document_uid=document_uid, name=name,
                        parent_group_guid=parent_group_guid, index=index)


@mcp.tool()
async def cubism_native_move_parameter_to_group(document_uid: str, parameter_id: str,
                                               group_guid: str | None = None, index: int = -1) -> dict[str, Any]:
    """Move a parameter in the native group tree, retaining its ID and keyforms."""
    return await native('move_parameter_to_group', document_uid=document_uid, parameter_id=parameter_id,
                        group_guid=group_guid, index=index)


@mcp.tool()
async def cubism_native_duplicate_objects(document_uid: str, object_guids: list[str],
                                          parent_part_guid: str | None = None) -> dict[str, Any]:
    """Duplicate ArtMeshes or deformer subtrees in one model; return the old-to-new GUID map."""
    return await native('duplicate_objects', document_uid=document_uid, object_guids=object_guids,
                        parent_part_guid=parent_part_guid)


@mcp.tool()
async def cubism_native_add_glue_pairs(document_uid: str, glue_guid: str,
                                      pairs: list[GlueWeight]) -> dict[str, Any]:
    """Append explicitly identified vertex pairs and weights to an existing Glue."""
    return await native('add_glue_pairs', document_uid=document_uid, glue_guid=glue_guid,
                        pairs=[pair.provided() for pair in pairs])


@mcp.tool()
async def cubism_native_suggest_glue_pairs(document_uid: str, mesh_a_guid: str, mesh_b_guid: str,
                                          tolerance: float,
                                          parameters: dict[str, float] | None = None) -> dict[str, Any]:
    """Find nearby vertex pairs in canvas pixels at the default or specified pose; report ambiguities."""
    return await native('suggest_glue_pairs', document_uid=document_uid, mesh_a_guid=mesh_a_guid,
                        mesh_b_guid=mesh_b_guid, tolerance=tolerance, parameters=parameters or {})


@mcp.tool()
async def cubism_native_fit_warp_to_children(document_uid: str, object_guid: str) -> dict[str, Any]:
    """Use the Cubism 5.3 native warp expansion operation to fit a warp's children."""
    return await native('fit_warp_to_children', document_uid=document_uid, object_guid=object_guid)


@mcp.tool()
async def cubism_native_import_psd(path: str) -> dict[str, Any]:
    """Open a workspace PSD as a new Cubism model, retaining layer hierarchy and canvas size."""
    return await native('import_psd', path=path)


@mcp.tool()
async def cubism_native_plan_psd_reimport(document_uid: str, path: str, source_psd_guid: str | None = None,
                                         layer_map: dict[str, str] | None = None) -> dict[str, Any]:
    """Preview PSD changes. Explicit layer_map entries map incoming layer keys to existing layer keys."""
    return await native('plan_psd_reimport', document_uid=document_uid, path=path,
                        source_psd_guid=source_psd_guid, layer_map=layer_map or {})


@mcp.tool()
async def cubism_native_reimport_psd(document_uid: str, path: str, plan_id: str,
                                    source_psd_guid: str | None = None,
                                    layer_map: dict[str, str] | None = None,
                                    execution: Literal['sync', 'job'] = 'sync',
                                    request_id: str | None = None) -> dict[str, Any]:
    """Apply a matching PSD preflight plan while preserving existing objects for missing layers."""
    return await native('reimport_psd', document_uid=document_uid, path=path, plan_id=plan_id,
                        source_psd_guid=source_psd_guid, layer_map=layer_map or {},
                        execution=execution, request_id=request_id)


@mcp.tool()
async def cubism_native_edit_texture_atlas(document_uid: str,
                                           action: Literal['inspect', 'create_page', 'resize_page', 'auto_layout', 'place'],
                                           page_guid: str | None = None, name: str | None = None,
                                           width: int | None = None, height: int | None = None,
                                           placements: list[AtlasPlacement] | None = None) -> dict[str, Any]:
    """Inspect, create, resize, auto-pack or explicitly place materials on Cubism atlas pages."""
    return await native('edit_texture_atlas', document_uid=document_uid, action=action, page_guid=page_guid,
                        name=name, width=width, height=height,
                        placements=[placement.provided() for placement in placements or []])


@mcp.tool()
async def cubism_native_create_animation_scene(model_document_uid: str, name: str, fps: float,
                                               duration_frames: int,
                                               animation_document_uid: str | None = None) -> dict[str, Any]:
    """Create a native animation scene and linked model track; optionally append to an open CAN3."""
    return await native('create_animation_scene', model_document_uid=model_document_uid, name=name, fps=fps,
                        duration_frames=duration_frames, animation_document_uid=animation_document_uid)


@mcp.tool()
async def cubism_native_set_timeline_keys(document_uid: str, track_guid: str, parameter_id: str,
                                          keys: list[TimelineKey]) -> dict[str, Any]:
    """Set or replace native parameter keys at integer frames in one animation scene and track."""
    return await native('set_timeline_keys', document_uid=document_uid, track_guid=track_guid,
                        parameter_id=parameter_id, keys=[key.provided() for key in keys])


@mcp.tool()
async def cubism_native_edit_animation_scene(document_uid: str, patch: AnimationScenePatch) -> dict[str, Any]:
    """Change native scene settings with undo. Changing FPS preserves integer keyframe positions."""
    return await native('edit_animation_scene', document_uid=document_uid, **patch.provided())


@mcp.tool()
async def cubism_native_batch_edit_timeline(document_uid: str, edits: list[TimelineEdit]) -> dict[str, Any]:
    """Validate then update multiple native parameter curves with one undo entry."""
    return await native('batch_edit_timeline', document_uid=document_uid, edits=[edit.provided() for edit in edits])


@mcp.tool()
async def cubism_native_export_expression3(document_uid: str, path: str, parameters: list[ExpressionParameter],
                                            fade_in_seconds: float = 1, fade_out_seconds: float = 1) -> dict[str, Any]:
    """Write a new SDK expression file using explicit parameter IDs and blend modes."""
    return await native('export_expression3', document_uid=document_uid, path=path,
                        parameters=[parameter.provided() for parameter in parameters],
                        fade_in_seconds=fade_in_seconds, fade_out_seconds=fade_out_seconds)


@mcp.tool()
async def cubism_native_export_physics3(document_uid: str, path: str) -> dict[str, Any]:
    """Export native physics to a new workspace .physics3.json file."""
    return await native('export_physics3', document_uid=document_uid, path=path)


@mcp.tool()
async def cubism_native_export_runtime_model(document_uid: str, directory: str, name: str | None = None,
                                            execution: Literal['sync', 'job'] = 'sync',
                                            request_id: str | None = None) -> dict[str, Any]:
    """Export the native SDK model, atlas textures and referenced files to a new workspace directory."""
    return await native('export_runtime_model', document_uid=document_uid, directory=directory, name=name,
                        execution=execution, request_id=request_id)


@mcp.tool()
async def cubism_native_export_motion3(document_uid: str, track_guid: str, path: str) -> dict[str, Any]:
    """Export one native animation scene's model track to a new workspace .motion3.json file."""
    return await native('export_motion3', document_uid=document_uid, track_guid=track_guid, path=path)


@mcp.tool()
async def cubism_native_get_timeline_keys(document_uid: str, track_guid: str, parameter_id: str) -> dict[str, Any]:
    """Read native CAN3 curve keys and interpolation for a track parameter."""
    return await native('get_timeline_keys', document_uid=document_uid, track_guid=track_guid, parameter_id=parameter_id)


@mcp.tool()
async def cubism_native_remove_timeline_keys(document_uid: str, track_guid: str, parameter_id: str,
                                             frames: list[int]) -> dict[str, Any]:
    """Delete existing integer-frame CAN3 keys as a single native undo."""
    return await native('remove_timeline_keys', document_uid=document_uid, track_guid=track_guid,
                        parameter_id=parameter_id, frames=frames)


@mcp.tool()
async def cubism_native_sample_timeline_parameters(document_uid: str, track_guid: str, parameter_id: str,
                                                    frames: list[int]) -> dict[str, Any]:
    """Read native CAN3 interpolation at distinct integer frames."""
    return await native('sample_timeline_parameters', document_uid=document_uid, track_guid=track_guid,
                        parameter_id=parameter_id, frames=frames)


@mcp.tool()
async def cubism_native_preflight_runtime_export(document_uid: str, target_version: Literal['5.0', '5.3'] = '5.3',
                                                 motions: list[RuntimeMotion] | None = None,
                                                 expressions: list[RuntimeExpression] | None = None,
                                                 physics_path: str | None = None) -> dict[str, Any]:
    """Check SDK target compatibility, atlas placements, clipping and selected assets."""
    return await native('preflight_runtime_export', document_uid=document_uid, target_version=target_version,
                        motions=[motion.provided() for motion in motions or []],
                        expressions=[expression.provided() for expression in expressions or []], physics_path=physics_path)


@mcp.tool()
async def cubism_native_export_runtime_package(document_uid: str, directory: str, request_id: str,
                                               name: str | None = None, target_version: Literal['5.0', '5.3'] = '5.3',
                                               motions: list[RuntimeMotion] | None = None,
                                               expressions: list[RuntimeExpression] | None = None,
                                               physics_path: str | None = None) -> dict[str, Any]:
    """Queue a native SDK package with selected motion, expression and physics references."""
    return await native('export_runtime_package', document_uid=document_uid, directory=directory, request_id=request_id,
                        name=name, target_version=target_version, motions=[motion.provided() for motion in motions or []],
                        expressions=[expression.provided() for expression in expressions or []], physics_path=physics_path)

@mcp.tool()
async def cubism_native_render_pose_set(document_uid: str, directory: str, poses: list[RenderPose],
                                       request_id: str, base_pose: Literal['current', 'default'] = 'default',
                                       width: int | None = None, height: int | None = None,
                                       crop: tuple[int, int, int, int] | None = None,
                                       baseline_manifest: str | None = None) -> dict[str, Any]:
    """Queue named native PNG poses, optional native-size crops and comparisons with a prior manifest."""
    options = {key: value for key, value in {'width': width, 'height': height}.items() if value is not None}
    return await native('render_pose_set', document_uid=document_uid, directory=directory, request_id=request_id,
                        poses=[pose.provided() for pose in poses], base_pose=base_pose,
                        crop=crop, baseline_manifest=baseline_manifest, **options)


@mcp.tool()
async def cubism_native_job_status(job_id: str | None = None, request_id: str | None = None) -> dict[str, Any]:
    """Read queued, running or completed job state by exactly one identifier."""
    return await native('job_status', job_id=job_id, request_id=request_id)


@mcp.tool()
async def cubism_native_inspect_model_dependencies(document_uid: str, kind: Literal['object', 'parameter', 'material'],
                                                  id: str) -> dict[str, Any]:
    """Read part, deformer, binding, clipping, Glue and shared material relationships."""
    return await native('inspect_model_dependencies', document_uid=document_uid, kind=kind, id=id)


@mcp.tool()
async def cubism_native_edit_batch(document_uid: str, mode: Literal['plan', 'apply'],
                                   edits: list[BatchEdit], plan_id: str | None = None) -> dict[str, Any]:
    """Validate and apply supported edits to one document as a single native undo."""
    return await native('edit_batch', document_uid=document_uid, mode=mode,
                        edits=[edit.provided() for edit in edits], plan_id=plan_id)


@mcp.tool()
async def cubism_native_set_form_colors(document_uid: str, object_guid: str, form_guid: str,
                                        multiply_color: tuple[float, float, float] | None = None,
                                        screen_color: tuple[float, float, float] | None = None) -> dict[str, Any]:
    """Set normalized keyform RGB multiply and screen colors, preserving alpha."""
    return await native('set_form_colors', document_uid=document_uid, object_guid=object_guid,
                        form_guid=form_guid, multiply_color=multiply_color, screen_color=screen_color)


@mcp.tool()
async def cubism_native_replace_mesh_texture_resized(document_uid: str, object_guid: str, path: str,
                                                     origin_offset: tuple[int, int],
                                                     atlas_placement: AtlasPosition | None = None) -> dict[str, Any]:
    """Bind an independent PNG material. Offset is the new origin in old image pixels."""
    return await native('replace_mesh_texture_resized', document_uid=document_uid, object_guid=object_guid,
                        path=path, origin_offset=origin_offset,
                        atlas_placement=None if atlas_placement is None else atlas_placement.provided())


@mcp.tool()
async def cubism_native_create_blend_shape(document_uid: str, object_guid: str, base_form_guid: str,
                                           parameter_id: str, name: str, positions: list[float]) -> dict[str, Any]:
    """Create a native 0–1 ArtMesh Blend Shape from a base keyform and target local positions."""
    return await native('create_blend_shape', document_uid=document_uid, object_guid=object_guid,
                        base_form_guid=base_form_guid, parameter_id=parameter_id, name=name, positions=positions)


if __name__=='__main__':
    mcp.run(transport='stdio')
