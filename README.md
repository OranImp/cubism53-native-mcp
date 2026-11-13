# Cubism 5.3 Native MCP

在 Windows x64 上通过 MCP stdio 操作 Live2D Cubism Editor **5.3.03** 和 **5.3.04**，共 84 个工具。桥接器在已运行的官方编辑器进程中调用原生建模、动画、物理、渲染和导出接口。两项临时画布预览工具使用 Cubism 官方本机 WebSocket API。

## 下载

- [Windows x64 便携包 ZIP](https://github.com/OranImp/cubism53-native-mcp/releases/latest/download/Cubism53-Native-MCP-win-x64.zip)
- [SHA-256 校验文件](https://github.com/OranImp/cubism53-native-mcp/releases/latest/download/Cubism53-Native-MCP-win-x64.zip.sha256)
- [版本记录和第三方源码](https://github.com/OranImp/cubism53-native-mcp/releases/latest)

同一个便携包包含两个预编译适配器、Python 和 Java Attach 运行时。另行安装官方 Cubism Editor 5.3.03 或 5.3.04 即可使用。在下载目录运行以下命令校验 ZIP。

```powershell
$archive = 'Cubism53-Native-MCP-win-x64.zip'
$expected = ((Get-Content -LiteralPath "$archive.sha256" -Raw) -split '\s+')[0]
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ine $expected) {
    throw '便携包 SHA-256 校验失败'
}
```

### English quick start

On Windows x64, install Live2D Cubism Editor 5.3.03 or 5.3.04, extract the portable ZIP,
and run `Setup.cmd`. Choose the folder containing your models. Setup prints
the location of the generated stdio configurations for CC Switch, DeepSeek
Harness, Codex, Claude Code, and other MCP clients. Start the editor, then
call `cubism_native_connect` and `cubism_native_status` from your client.

## 安装和连接

### Windows x64 便携包

1. 安装并能启动下表中的官方 Cubism Editor。Setup 校验安装目录下的 `app/lib/Live2D_Cubism.jar`，连接时自动加载对应适配器。
2. 将 ZIP 解压到准备长期保留的目录，运行 `Setup.cmd`。程序查找已运行的编辑器、Windows 安装记录和常见安装目录。按提示确认模型工作区，必要时输入自定义的编辑器安装目录。
3. 从 Setup 显示的 `client-configs` 目录选用下表对应的文件。客户端已有 Cubism 条目时，更新该条目的启动命令和参数。启动 Cubism 后调用 `cubism_native_connect`，再用 `cubism_native_status` 获取当前文档 UID。

便携包内有 CPython、MCP 依赖、预编译桥接 JAR 和 Java Attach 运行时。安装和启动可以离线完成。客户端与 Cubism 应运行在同一 Windows 用户会话。

| 支持的 Windows x64 构建 | `Live2D_Cubism.jar` SHA-256 |
| --- | --- |
| 5.3.03 | `bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166` |
| 5.3.04 | `c9bb3e9960bf49908a531b7010421c393a8dd5f9376745eb6fdb273f41a1cf5d` |

静默配置示例。在解压后的目录执行。

```powershell
.\Setup.cmd --non-interactive --editor-home 'C:/Program Files/Live2D Cubism 5.3' --workspace 'E:/Models'
```

默认配置写入 `%LOCALAPPDATA%\Cubism53MCP\profiles\default\settings.json`。用 `--profile` 和 `--bridge-port` 为其他模型工作区创建独立配置。移动便携包后，重新运行 Setup 生成新路径。

同时安装两个版本时，用 `--editor-home` 明确选择所用安装目录。若要并行连接两个版本，为它们分别指定 profile 和桥接端口。Setup、`doctor` 和 `cubism_native_status` 均显示选中的编辑器版本。

| 接入方式 | 配置 |
| --- | --- |
| CC Switch | 在 MCP 管理中添加名为 `cubism53-native-mcp` 的 stdio 服务，使用 `cc-switch.json` 的命令和参数，再选择要同步的客户端。已有同名或旧版服务可直接更新该条目的启动配置。 |
| Claude Code 和通用 stdio 客户端 | 将 `mcp.json` 的服务器条目合并到客户端配置。Claude Code 支持模型项目里的 `.mcp.json`。 |
| Codex | 运行生成的 `codex.ps1`，或在 CC Switch 中为 Codex 启用同一条服务。 |
| DeepSeek Harness | 把 `dsh.patch.yml` 用作本机 profile overlay，例如 `dsh web --patch <文件>`。桌面版使用同一文件中 `insert` 下的 `@deepseek-ai/dsh-mcp-client` 插件条目。 |

这些文件位于 profile 的 `client-configs` 目录。配置生成器只写该 profile，导入和同步由各客户端处理。每个客户端均调用同一标准 MCP stdio 入口。

诊断命令会检查编辑器版本、工作区、Attach 运行时和端口状态。将 profile 路径换成 Setup 输出的实际路径。

```powershell
.\runtime\python\python.exe -B .\app\launcher.py doctor --profile-file "$env:LOCALAPPDATA/Cubism53MCP/profiles/default/settings.json"
```

#### 升级和故障排查

- 升级前等作业完成。若旧桥接仍在运行，用旧包的诊断命令将 `doctor` 换成 `stop`。解压新版，在新版目录用同一 profile 运行 Setup，更新客户端条目并重开会话。
- `doctor` 会核对目标编辑器 PID、编辑器构建、桥接协议和适配器构建 ID。若提示旧桥接或适配器不匹配，先用旧包停止桥接，再用新版重连。
- `Unsupported Cubism editor JAR` 表示选中的编辑器构建不在兼容表内。重新运行 Setup，通过 `--editor-home` 指定受支持的安装目录。
- `Expected one running Cubism` 表示编辑器未启动或有多个匹配进程。启动对应版本，或者用 `CUBISM53_EDITOR_PID` 指定目标 Java 进程。
- 端口已占用时，先用 `doctor` 确认该端口是否属于本 profile。并行使用多个 profile 时，为每个 profile 指定不同的 `--bridge-port`。
- 从 v0.2.0 升级后，各预览端口首次使用时需在「外部应用程序集成」中批准 **Cubism53 Native MCP**。保持发起预览的客户端会话连接，批准后重试。同一个 profile 的同一预览端口会复用已登记的令牌。

### 从源码安装

Windows 上需要 Python 3.11+ 和 JDK 17。源码方式同样支持用 `launcher.py setup` 生成各客户端配置。

```powershell
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe .\launcher.py setup --editor-home 'C:/Program Files/Live2D Cubism 5.3' --workspace 'E:/Models'
```

直接启动方式仍支持 `python server.py`。以下变量可用于源码方式或选择目标编辑器。

| 环境变量 | 用途 |
| --- | --- |
| `CUBISM53_EDITOR_HOME` | Cubism Editor 安装根目录。 |
| `CUBISM53_JDK_HOME` | 源码构建用 JDK 17。未指定时检查 `JAVA_HOME` 和 `PATH`。 |
| `CUBISM53_WORKSPACE` | 文件工具的工作区根目录，直接运行源码时默认本仓库。 |
| `CUBISM53_BRIDGE_STATE` | 桥接和令牌目录，直接运行源码时默认 `.state`。 |
| `CUBISM53_BRIDGE_PORT` | 原生桥接端口，默认 `22055`。 |
| `CUBISM53_EDITOR_PID` | 有多个编辑器时指定目标 Java 进程 PID。 |
| `CUBISM53_PORT` | 画布预览使用的外部程序集成端口，默认 `22033`。 |
| `CUBISM53_ATTACH_DIR` | Attach 临时配置目录，直接运行源码时默认 `LOCALAPPDATA`。 |

同一编辑器进程内的多个客户端共享打开的文档和原生撤销栈。独立配置应使用各自的桥接端口和状态目录。桥接只监听 `127.0.0.1`。首次使用画布预览时，在 Cubism 启用「外部应用程序集成」，保持 MCP 会话连接并批准 **Cubism53 Native MCP**。

### 构建便携包

在 Windows x64 上准备官方 CPython 3.13.17 Windows x64 嵌入 ZIP、Eclipse Temurin JDK 17.0.19+10，以及表中两个 Cubism Editor 安装目录。用 `portable/wheels-win-x64.txt` 下载锁定的 CPython 3.13 Windows x64 wheels。构建步骤读取这些本机输入。

```powershell
python -m pip download --require-hashes --no-deps --only-binary=:all: --platform win_amd64 --implementation cp --python-version 3.13 --abi cp313 -r portable/wheels-win-x64.txt -d wheelhouse
python portable/build.py --python-zip python-3.13.17-embed-amd64.zip --wheelhouse wheelhouse --editor-home 'C:/Cubism/5.3.03' --editor-home 'C:/Cubism/5.3.04' --output dist/Cubism53-Native-MCP-win-x64.zip
```

输出同时包含 ZIP 的 SHA-256 文件。第三方运行时及其许可见 `THIRD_PARTY.md`（便携包根目录和源码的 `portable/` 目录中）。

## 工具


| 工具（均以 `cubism_native_` 开头） | 用途 |
| --- | --- |
| `connect` | 发现并连接已经运行的指定版本编辑器 |
| `status`、`document` | 文档路径、UID、对象数量及撤销状态 |
| `objects`、`object` | 部件标识、参数范围、关键形、UV、网格数据，支持按需筛选 |
| `import_png` | 原生材质与 ArtMesh 导入，输入 PNG 像素到画布的仿射矩阵 |
| `export_mesh_texture` | 无损导出已嵌入的 ArtMesh 材质 PNG，返回图像到源网格变换；不改写源模型 |
| `replace_mesh_texture` | 用同尺寸 PNG 替换内嵌材质像素，共享该材质的 ArtMesh 会一同更新 |
| `set_visibility`、`set_clipping` | 部件显隐与原生裁剪，均记录原生撤销 |
| `create_blank_project` | 创建指定画布大小的空白模型 |
| `open_project`、`save_as_new`、`close_saved` | 原生打开 CMO3 或 CAN3、另存新文件、关闭已保存文档 |
| `create_warp`、`create_rotation` | 原生弯曲/旋转变形器 |
| `reparent` | 在默认参数下保形挂接，完成后恢复预览参数 |
| `add_parameter_keys` | 为现有普通参数建立关键形 |
| `set_form_positions` | 修改一个 Mesh/Warp 关键形的现有顶点 |
| `set_form_properties` | 图层透明度或旋转关节角度、枢轴、缩放 |
| `set_mesh_topology` | 以原生 MeshEditor 方法重建拓扑、映射 UV 和全部关键形 |
| `auto_generate_mesh`、`auto_mesh_status` | 启动原生自动布网并查询任务状态、布网前后的顶点和三角形数量 |
| `evaluate` | 使用独立模型实例执行原生插值和画布顶点求值，保留当前文档预览参数 |
| `render_png` | 原生离屏透明 PNG，使用独立参数副本 |
| `release_renderers` | 释放指定文档的缓存渲染会话 |
| `undo`、`redo` | 原生文档撤销/重做 |
| `create_parameter` | 在根参数组创建普通参数，使用原生参数工厂与撤销 |
| `update_parameter` | 修改普通参数名称、说明、范围和默认值 |
| `move_parameter_keys`、`remove_parameter_keys` | 按对象和参数移动或删除原生关键形键 |
| `move_to_part`、`rename_object` | 在部件树归组和排序，或修改对象显示名 |
| `set_blend_mode`、`set_part_offscreen` | 设置 ArtMesh 或部件的混合模式、部件离屏绘制 |
| `physics` | 读取物理组、输入/输出链接、粒子及归一化设置 |
| `update_physics_group` | 修改单个物理组，索引取自 `physics` 的对应列表 |
| `import_physics3` | 使用编辑器自身的物理 JSON 导入器，记录原生撤销 |
| `simulate_physics` | 在独立副本上运行 Cubism 自身的物理求解器，返回逐帧输出 |
| `list_glues`、`create_glue` | 读取 Glue 配对、权重和强度，或用两张 ArtMesh 的显式顶点 UID 配对创建 Glue |
| `update_glue_weights`、`remove_glue_pairs`、`set_glue_intensity` | 调整配对的权重、解除配对，或设置关键形的 Glue 强度 |
| `set_preview_parameters`、`clear_preview_parameters` | 通过官方本机 WebSocket 暂时设置或清除指定视图的画布参数 |
| `create_part`、`duplicate_objects` | 创建部件或复制对象子树，返回 GUID 映射 |
| `create_parameter_group`、`move_parameter_to_group` | 创建参数组或在参数树中移动现有参数 |
| `add_glue_pairs`、`suggest_glue_pairs` | 向现有 Glue 追加配对，或按画布距离查询候选配对和歧义 |
| `fit_warp_to_children` | 使用 Cubism 5.3 的原生扩展助手包住子对象 |
| `import_psd`、`plan_psd_reimport`、`reimport_psd` | 从 PSD 新建模型、预检图层映射和应用重导入 |
| `edit_texture_atlas` | 检查图集、新建或调整页、自动排布、手动定位与旋转材质 |
| `create_animation_scene`、`set_timeline_keys` | 创建或追加关联模型的场景，写入原生参数曲线 |
| `export_physics3`、`export_runtime_model`、`export_motion3` | 导出物理文件、SDK 模型及纹理、单轨动作 |
| `render_pose_set`、`job_status` | 批量渲染命名姿态，查询批量渲染、原生布网、PSD 和 SDK 导出任务 |
| `inspect_model_dependencies`、`edit_batch` | 反查模型依赖，预检并以一次原生撤销应用一组编辑 |
| `get_timeline_keys`、`remove_timeline_keys`、`sample_timeline_parameters` | 读回、删除和采样原生 CAN3 参数曲线 |
| `preflight_runtime_export`、`export_runtime_package` | 检查目标 SDK 兼容性并组装原生模型、动作、表情和物理 |
| `set_form_colors`、`replace_mesh_texture_resized` | 编辑关键形乘算色和滤色，为指定 ArtMesh 更换异尺寸贴图 |
| `create_blend_shape` | 以普通 ArtMesh 关键形为基准创建原生 Blend Shape |
| `export_expression3` | 按参数 ID、混合模式和淡入淡出时间写入 SDK 表情文件 |
| `edit_animation_scene`、`batch_edit_timeline` | 编辑场景帧率、时长、循环、淡入淡出，或一次撤销中编辑多条参数曲线 |
| `edit_part_rendering` | 设置部件离屏渲染、裁剪、反向遮罩和绘制顺序组 |
| `mirror_keyforms` | 按画布像素轴心把 ArtMesh 或 Warp 关键形镜像到明确指定的目标关键形 |
| `plan_delete_objects`、`delete_objects` | 查看对象删除的影响并按同一预检凭据执行原生撤销组 |
| `inspect_art_paths`、`create_art_path` | 查询路径、笔刷、点和绑定，或从画布坐标创建原生 ArtPath |


## 调用约定

- 编辑操作按文档 UID 执行。另存和导出要求工作区内的新路径。原生撤销也会纳入编辑器里的手工操作。
- 批量渲染和运行包组装需要 `request_id`。`reimport_psd`、`export_runtime_model` 使用 `execution=job` 时遵守同样约定。用 `job_status` 查询。桥接会话保留最近 256 条已完成作业，最长 24 小时。保留期内，相同请求 ID 和参数复用原作业。最近 4,096 条过期标记最多保留 7 天。记录过期后，先检查文档或产物，再为确实需要重新执行的作业生成新 ID。
- 作业状态查询使用独立通道。普通请求满载时会返回可重试的忙碌错误。同步编辑超时后，若响应表明操作已开始，请先读回文档或检查输出再决定是否重试。
- PSD 重导入先用 `plan_psd_reimport` 检查冲突。缺失层保留原对象。图层重新出现且可唯一匹配同一来源的历史层时，会更新原材质。发生冲突时，把候选的 `key` 填入 `layer_map` 并重新预检。历史候选键形如 `来源GUID:图层键`。需要明确创建新层时，映射值可用 `new`。
- `edit_batch` 先用 `mode=plan` 获取凭据，再提交同一组编辑与 `plan_id`。部件移动按顺序预检，计划在相关文档状态改变后失效。
- 5.3.03 的原生 SDK 导出会移除 ArtPath。`export_runtime_package` 会拦截使用可见 ArtPath 或将其用于裁剪的模型。`export_runtime_model` 会返回有关丢失的警告。
- `objects` 可指定 `sections`、`part_guid`、`parameter_id` 及 `object_types`。类型使用返回的名称，例如 `CArtMeshSource`。`parameter_id` 匹配对象的直接普通参数或 Blend Shape 绑定。`object` 可用 `form_guids` 限定关键形，或用 `geometry=false` 省略顶点、网格和 UV。
- `simulate_physics` 默认返回逐帧轨迹。`result_mode=summary` 返回选定输出的范围，`output_parameter_ids` 用来限定输出，`execution=job` 配合 `request_id` 可异步轮询。4K 及以上的渲染作业独占渲染额度，内存不足时会在分配缓冲前报错。
- 表情文件的 `Add` 是增量，`Multiply` 是倍率，`Overwrite` 是绝对值。运行包预检和打包使用相同的附件校验，诊断包含出错文件及字段。
- `edit_animation_scene` 改帧率时保留关键帧的整数帧位。单轨 `export_motion3` 仍按轨道区间导出，场景时长可独立修改。
- 镜像的 `axis=x` 翻转横坐标，`axis=y` 翻转纵坐标。`vertex_indices` 按目标顶点顺序提供源顶点索引。删除预检默认阻止未包含的子对象或存活引用，可用 `cascade` 和 `detach_references` 明确扩展删除范围。存活 ArtPath 的点绑定需要先在编辑器中处理。新模型没有自定义笔刷时，可省略 `brush_guid` 使用原生默认线条。

## 验证

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

测试使用本机临时端口，验证 stdio 工具发现、结构化错误和官方预览协议的重新注册。实机冒烟测试可在空白的独立 Cubism Editor 会话运行。设置 `CUBISM53_EDITOR_PID`、`CUBISM53_WORKSPACE`、`CUBISM53_BRIDGE_STATE` 和专用 `CUBISM53_BRIDGE_PORT`，然后执行 `.\.venv\Scripts\python.exe tests/native_smoke.py`。测试只使用生成的临时图像和模型。便携启动、批量编辑、PSD 重导入和 Glue 性能的覆盖范围见 [验证记录](https://github.com/OranImp/cubism53-native-mcp/blob/main/VALIDATION.md)。

## 许可证

桥接源码采用 [MIT 许可证](LICENSE)。运行所需的 Live2D Cubism Editor 由用户单独安装，适用 Live2D 的许可条款。
