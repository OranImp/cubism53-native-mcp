# Cubism 5.3 Native MCP

[简体中文](README.md) · **English** · [日本語](README.ja.md)

Operate Live2D Cubism Editor **5.3.03** and **5.3.04** on Windows x64 through MCP stdio. The bridge provides 93 tools and calls the running editor's native modeling, animation, physics, rendering, and export functions. Canvas preview uses Cubism's official local WebSocket API.

**v0.4.1** improves PSD reimport preflight for large files, batch renames, and pagination of long physics traces. All 93 tools passed regression checks on both editor builds. See the [validation record](VALIDATION.md).

## Downloads

- [Windows x64 portable ZIP](https://github.com/OranImp/cubism53-native-mcp/releases/latest/download/Cubism53-Native-MCP-win-x64.zip)
- [SHA-256 checksum file](https://github.com/OranImp/cubism53-native-mcp/releases/latest/download/Cubism53-Native-MCP-win-x64.zip.sha256)
- [Release notes and third-party source links](https://github.com/OranImp/cubism53-native-mcp/releases/latest)

The portable ZIP includes both prebuilt adapters, Python, and the Java Attach runtime. Install the official Cubism Editor 5.3.03 or 5.3.04 separately. Run this check in the download directory.

```powershell
$archive = 'Cubism53-Native-MCP-win-x64.zip'
$expected = ((Get-Content -LiteralPath "$archive.sha256" -Raw) -split '\s+')[0]
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ine $expected) {
    throw 'Portable ZIP SHA-256 verification failed'
}
```

## Installation and connection

### Windows x64 portable package

1. Install and launch one of the official Cubism Editor builds listed below. Setup checks `app/lib/Live2D_Cubism.jar` in the installation directory. The bridge loads the matching adapter when it connects.
2. Extract the ZIP to a directory you intend to keep and run `Setup.cmd`. Setup looks at running editor processes, Windows installation records, and common installation directories. Choose the model workspace and specify the editor installation directory if prompted.
3. Pick the file for your client from the `client-configs` directory shown by Setup. Update an existing Cubism entry's command and arguments if needed. Start Cubism, call `cubism_native_connect`, and then use `cubism_native_status` to get the current document UID.

The portable package includes CPython, MCP dependencies, prebuilt bridge JARs, and the Java Attach runtime. Setup and startup work offline. Run the MCP client and Cubism in the same Windows user session.

| Supported Windows x64 build | `Live2D_Cubism.jar` SHA-256 |
| --- | --- |
| 5.3.03 | `bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166` |
| 5.3.04 | `c9bb3e9960bf49908a531b7010421c393a8dd5f9376745eb6fdb273f41a1cf5d` |

For unattended setup, run the following from the extracted directory.

```powershell
.\Setup.cmd --non-interactive --editor-home 'C:/Program Files/Live2D Cubism 5.3' --workspace 'E:/Models'
```

Setup stores the default configuration at `%LOCALAPPDATA%\Cubism53MCP\profiles\default\settings.json`. Use `--profile` and `--bridge-port` to create separate configurations for other model workspaces. Run Setup again after moving the portable package so the generated paths point to the new location.

When both editor versions are installed, choose the installation with `--editor-home`. To connect to both versions at once, assign each one its own profile and bridge port. Setup, `doctor`, and `cubism_native_status` report the selected editor version.

| Client | Configuration |
| --- | --- |
| CC Switch | Add a stdio MCP server named `cubism53-native-mcp` using the command and arguments in `cc-switch.json`, then select the clients to sync. For an existing entry, update its command and arguments. |
| Claude Code and other stdio clients | Merge the server entry from `mcp.json` into your client's configuration. Claude Code supports a project-level `.mcp.json` in the model project. |
| Codex | Run the generated `codex.ps1` or enable the same server for Codex through CC Switch. |
| DeepSeek Harness | Apply `dsh.patch.yml` as a local profile overlay, for example with `dsh web --patch <file>`. For the desktop app, use the `@deepseek-ai/dsh-mcp-client` plugin entry under `insert` in the same file. |

The generated files are in the profile's `client-configs` directory. Setup writes the files to that profile. Each client handles import and synchronization and uses the same standard MCP stdio entry point.

The diagnostic command checks the editor version, workspace, Attach runtime, and port. Substitute the actual profile path reported by Setup.

```powershell
.\runtime\python\python.exe -B .\app\launcher.py doctor --profile-file "$env:LOCALAPPDATA/Cubism53MCP/profiles/default/settings.json"
```

#### Upgrades and troubleshooting

- Let active jobs finish before upgrading. If the old bridge is still running, run the old package's diagnostic command with `stop` in place of `doctor`. Extract the new ZIP, run Setup with the same profile from the new directory, update the client entry, and reopen the client session.
- `doctor` checks the target editor PID, editor build, bridge protocol, and adapter build ID. If it reports an older bridge or an adapter mismatch, stop the bridge with its existing package and reconnect using the new package.
- `Unsupported Cubism editor JAR` means the selected build is outside the compatibility table. Rerun Setup with `--editor-home` pointing to a supported installation.
- `Expected one running Cubism` means the bridge found zero or multiple matching processes. Start the appropriate editor or set `CUBISM53_EDITOR_PID` to the target Java process.
- If a port is in use, run `doctor` to check whether it belongs to this profile. Assign each concurrent profile a distinct `--bridge-port`.
- After upgrading from v0.2.0, approve **Cubism53 Native MCP** under **External Application Integration** the first time you use each preview port. Keep the requesting MCP session connected and retry after approval. The same preview port in the same profile reuses its registered token.

### Install from source

On Windows, use Python 3.11 or later and JDK 17. Run `launcher.py setup` to generate configuration files for the supported clients.

```powershell
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe .\launcher.py setup --editor-home 'C:/Program Files/Live2D Cubism 5.3' --workspace 'E:/Models'
```

You can also start the server directly with `python server.py`. Use these environment variables for source installations or to select the target editor.

| Environment variable | Purpose |
| --- | --- |
| `CUBISM53_EDITOR_HOME` | Cubism Editor installation directory. |
| `CUBISM53_JDK_HOME` | JDK 17 for source builds. Falls back to `JAVA_HOME` and `PATH`. |
| `CUBISM53_WORKSPACE` | Root directory for file operations. Defaults to the repository when running from source. |
| `CUBISM53_BRIDGE_STATE` | Directory for bridge state and tokens. Defaults to `.state` when running from source. |
| `CUBISM53_BRIDGE_PORT` | Native bridge port. Defaults to `22055`. |
| `CUBISM53_EDITOR_PID` | Target Java process PID when multiple editors are running. |
| `CUBISM53_PORT` | External Application Integration port for canvas preview. Defaults to `22033`. |
| `CUBISM53_ATTACH_DIR` | Temporary Attach configuration directory. Defaults to `LOCALAPPDATA` when running from source. |

Clients connected to the same editor process share its open documents and native undo stack. Separate configurations should use distinct bridge ports and state directories. The bridge listens on `127.0.0.1`. For canvas preview, enable **External Application Integration**, keep the MCP session connected, and approve **Cubism53 Native MCP**.

### Build the portable package

On Windows x64, prepare the official CPython 3.13.17 Windows x64 embeddable ZIP, Eclipse Temurin JDK 17.0.19+10, and both supported Cubism Editor installations. Download the pinned CPython 3.13 Windows x64 wheels listed in `portable/wheels-win-x64.txt`. The build uses these local inputs.

```powershell
python -m pip download --require-hashes --no-deps --only-binary=:all: --platform win_amd64 --implementation cp --python-version 3.13 --abi cp313 -r portable/wheels-win-x64.txt -d wheelhouse
python portable/build.py --python-zip python-3.13.17-embed-amd64.zip --wheelhouse wheelhouse --editor-home 'C:/Cubism/5.3.03' --editor-home 'C:/Cubism/5.3.04' --output dist/Cubism53-Native-MCP-win-x64.zip
```

The build produces the ZIP and its SHA-256 file. Third-party runtimes and their licenses are documented in `THIRD_PARTY.md` at the root of the portable package and in the source repository's `portable/` directory.

## Tools

| Tools (all prefixed with `cubism_native_`) | Purpose |
| --- | --- |
| `connect` | Find and connect to a running supported editor. |
| `status`, `document` | Read document paths, UIDs, object counts, and undo state. |
| `objects`, `object` | Read part IDs, parameter ranges, keyforms, UVs, and mesh data with optional filters. |
| `import_png` | Import a PNG as a native material and ArtMesh using a pixel-to-canvas affine transform. |
| `export_mesh_texture` | Export an ArtMesh's embedded material as a lossless PNG and return the image-to-source transform. |
| `replace_mesh_texture` | Replace embedded material pixels with a PNG of the same size. All ArtMeshes sharing the material receive the update. |
| `set_visibility`, `set_clipping` | Set visibility and native clipping with undo. |
| `create_blank_project` | Create a blank model with the specified canvas dimensions. |
| `open_project`, `save_as_new`, `close_saved` | Open CMO3 or CAN3 files, save to a new file, and close saved documents. |
| `create_warp`, `create_rotation` | Create native warp and rotation deformers. |
| `reparent` | Preserve the object's shape at default parameter values while reparenting, then restore the preview parameters. |
| `add_parameter_keys` | Add keyforms for an existing normal parameter. |
| `set_form_positions` | Update the existing vertices of an ArtMesh or warp deformer keyform. |
| `set_form_properties` | Set draw order, opacity, or a rotation deformer's angle, pivot, and scale. |
| `set_mesh_topology` | Rebuild topology and remap UVs and keyforms with the native Mesh Editor. |
| `auto_generate_mesh`, `auto_mesh_status` | Start native automatic mesh generation and check job status and vertex and triangle counts before and after. |
| `evaluate` | Evaluate interpolation and canvas vertices in an isolated model instance while preserving the document's preview parameters. |
| `render_png` | Render a transparent PNG offscreen with an independent parameter copy. |
| `release_renderers` | Release a document's cached render sessions. |
| `undo`, `redo` | Undo or redo operations on the native document. |
| `create_parameter` | Create a normal parameter in the root group with the native factory and undo. |
| `update_parameter` | Change a normal parameter's name, description, range, or default. |
| `move_parameter_keys`, `remove_parameter_keys` | Move or remove native keys for an object and parameter. |
| `move_to_part`, `rename_object` | Reorder or move an object in the part tree, or change its display name. |
| `set_blend_mode`, `set_part_offscreen` | Set ArtMesh or part blend mode, or enable offscreen drawing for a part. |
| `physics` | Inspect physics groups, input and output links, particles, and normalization settings. |
| `update_physics_group` | Edit one physics group using the indices returned by `physics`. |
| `import_physics3` | Import physics JSON with the editor's native importer and undo. |
| `simulate_physics` | Run Cubism's physics solver on an independent copy and return the frame-by-frame output. |
| `list_glues`, `create_glue` | Inspect Glue pairs, weights, and intensity, or create a Glue from explicit vertex UID pairs on two ArtMeshes. |
| `update_glue_weights`, `remove_glue_pairs`, `set_glue_intensity` | Edit or remove Glue pairs, or set Glue intensity on a keyform. |
| `set_preview_parameters`, `clear_preview_parameters` | Temporarily set or clear canvas parameters in a model view through the official local WebSocket API. |
| `create_part`, `duplicate_objects` | Create a part or duplicate object subtrees with a returned GUID map. |
| `create_parameter_group`, `move_parameter_to_group` | Create a parameter group or move an existing parameter in the group tree. |
| `add_glue_pairs`, `suggest_glue_pairs` | Add pairs to an existing Glue or find potential matches and ambiguities by canvas distance. |
| `fit_warp_to_children` | Fit a warp deformer around its children with Cubism 5.3's native expansion function. |
| `import_psd`, `plan_psd_reimport`, `reimport_psd` | Create a model from a PSD, preflight its layer mappings, and reimport. |
| `edit_texture_atlas` | Inspect texture atlases, create or resize pages, lay out materials automatically, or place and rotate them manually. |
| `create_animation_scene`, `set_timeline_keys` | Create or append a scene with a linked model and write native parameter curves. |
| `export_physics3`, `export_runtime_model`, `export_motion3` | Export physics, an SDK model and textures, or a motion from one track. |
| `render_pose_set`, `job_status` | Render named poses and check render, automatic mesh, PSD, and SDK export jobs. |
| `inspect_model_dependencies`, `edit_batch` | Look up model dependencies and preflight and apply edits in one native undo group. |
| `get_timeline_keys`, `remove_timeline_keys`, `sample_timeline_parameters` | Read, delete, or sample native CAN3 parameter curves. |
| `edit_animation_tracks`, `edit_animation_events` | Append model tracks to an existing scene, shift or trim tracks, and edit events in scene frames. |
| `preflight_model_edit` | Check planned normal parameter bindings, keyform counts, and shared material impact. |
| `edit_physics_groups` | Add, remove, or reorder physics groups and edit their input and output links, gravity, wind, and FPS. |
| `edit_blend_shape` | Reuse an existing Blend Shape parameter, edit the target shape, or unbind one object. |
| `edit_art_path` | Edit an ArtPath's brush, open or closed state, control points, and ArtMesh bindings. |
| `list_preview_views`, `get_preview_parameters` | List model view UIDs and read parameters through the authorized official API. |
| `list_jobs` | Find jobs in the current bridge session with paging and filters for document, type, and status. |
| `preflight_runtime_export`, `export_runtime_package` | Check SDK compatibility and bundle the native model, motions, expressions, and physics. |
| `set_form_colors`, `replace_mesh_texture_resized` | Edit multiply and screen colors on a keyform or replace an ArtMesh texture with a different size. |
| `create_blend_shape` | Create a native Blend Shape based on a normal ArtMesh keyform. |
| `export_expression3` | Write an SDK expression with parameter IDs, blend modes, and fade times. |
| `edit_animation_scene`, `batch_edit_timeline` | Edit scene FPS, duration, loop, and fade times, or edit multiple parameter curves in one undo group. |
| `edit_part_rendering` | Set part offscreen rendering, clipping, inverted masks, and draw order groups. |
| `mirror_keyforms` | Mirror ArtMesh or warp keyforms around a canvas position to explicit target keyforms. |
| `plan_delete_objects`, `delete_objects` | Review the impact of deleting objects and apply the matching plan as one native undo group. |
| `inspect_art_paths`, `create_art_path` | Inspect paths, brushes, points, and bindings, or create a native ArtPath from canvas coordinates. |

## Calling conventions

- Edits target a document UID. Save-as and export operations require a new path inside the workspace. Native undo also includes manual edits made in the editor.
- Batch rendering and runtime package export require `request_id`. The same applies to `reimport_psd` and `export_runtime_model` with `execution=job`. Poll with `job_status`. A bridge session retains the 256 most recent completed jobs for up to 24 hours. Reusing a request ID with identical arguments returns the existing job during that period. The most recent 4,096 expiration markers are kept for up to seven days. Once a job record expires, check the document or output before assigning a new ID to a job that needs to run again.
- Job status uses a separate channel. Ordinary requests can return a retryable busy error when their queue is full. If a synchronous edit times out after starting, check the document or output before retrying.
- Use `plan_psd_reimport` to check for conflicts before reimporting. Existing objects for missing layers stay in the model. When a restored layer uniquely matches a historical layer from the same PSD source, reimport updates the original material. To resolve a conflict, put the selected candidate's `key` in `layer_map` and run preflight again. Historical keys have the form `source_GUID:layer_key`. Use `new` as the mapping value to create a layer explicitly.
- Call `edit_batch` with `mode=plan`, then submit the same edits with `plan_id`. The tool checks part moves in sequence. Changes to relevant document state invalidate the plan.
- The native 5.3.03 SDK exporter omits ArtPaths. `export_runtime_package` blocks models with visible ArtPaths or ArtPaths used for clipping. `export_runtime_model` reports omissions as warnings.
- `objects` accepts `sections`, `part_guid`, `parameter_id`, and `object_types`. Use the returned type name, such as `CArtMeshSource`. `parameter_id` matches direct normal parameter or Blend Shape bindings. `object` accepts `form_guids` to restrict the keyforms returned and `geometry=false` to omit vertices, topology, and UVs.
- `simulate_physics` returns the frame-by-frame trace by default. Use `result_mode=summary` for ranges, `output_parameter_ids` to select outputs, or `execution=job` with `request_id` to poll asynchronously. Renders at 4K or higher reserve exclusive rendering capacity. If memory is insufficient, the render reports an error before allocating its buffers.
- For expressions, `Add` is a delta, `Multiply` is a factor, and `Overwrite` sets an absolute value. Runtime package preflight and export use the same attachment checks. Diagnostics identify the file and field.
- `edit_animation_scene` preserves integer keyframe positions when changing FPS. Curve tools use `frame_space=track` by default. Set `frame_space=scene` to work in scene frames. Event tools use scene frames. Motion3 exports track events through the native exporter.
- `document` returns a `revision` for concurrency checks. Pass it as `expected_revision` to `undo`, `redo`, or `edit_batch`. `open_project` and `close_saved` accept `wait_seconds` to wait for completion and return immediately by default.
- Page through objects with `objects(offset, limit)`, evaluate several poses with `evaluate(poses=...)`, or get document UIDs with `status(lightweight=true)`. Use `trace_path` to write a physics trace to a new JSON file. Use `job_status(frame_offset=..., frame_limit=...)` to page through physics job results.
- The `place` and `resize_page` actions in `edit_texture_atlas` accept `preflight=true` to report out-of-bounds materials and overlapping pairs. Mesh topology changes and physics imports also support `preflight=true`. Automatic mesh generation reports existing Glue or ArtPath bindings on its target.
- For mirroring, `axis=x` flips the horizontal coordinate and `axis=y` flips the vertical coordinate. `vertex_indices` maps target vertex order to source indices. Deletion preflight blocks on omitted descendants or references from objects that would remain. Use `cascade` and `detach_references` to extend the requested deletion. Use `edit_art_path` to unbind points on ArtPaths that would remain. Omit `brush_guid` in new models without custom brushes to use the native default stroke.

## Verification

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

These tests use temporary local ports to exercise stdio tool discovery, structured errors, and preview re-registration through the official protocol. For a live smoke test, start an isolated blank editor session, set `CUBISM53_EDITOR_PID`, `CUBISM53_WORKSPACE`, `CUBISM53_BRIDGE_STATE`, and a dedicated `CUBISM53_BRIDGE_PORT`, then run `.\.venv\Scripts\python.exe tests/native_smoke.py`. The smoke test uses generated temporary images and models. See the validation log for portable setup, batch editing, PSD reimport, and Glue search performance. [Validation log (Chinese)](https://github.com/OranImp/cubism53-native-mcp/blob/main/VALIDATION.md)

## License

The bridge source is released under the MIT License. Install Live2D Cubism Editor separately under Live2D's license terms. [MIT License](LICENSE)
