# Cubism 5.3.03 Native MCP

在 Windows 上通过 MCP stdio 操作 Live2D Cubism Editor 5.3.03，共 75 个工具。桥接器在已运行的官方编辑器进程中调用原生建模、动画、物理、渲染和导出接口。两项临时画布预览工具使用 Cubism 官方本机 WebSocket API。

## 安装和连接

需要 Windows、Python 3.11 或更新版本、JDK 17，以及下列精确构建的 Cubism Editor 5.3.03。项目仅含桥接源代码。构建时从本机编辑器安装目录读取依赖。

- 校验文件为安装目录下的 `app/lib/Live2D_Cubism.jar`。
- 支持的 SHA-256 为 `bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166`。其他构建会在加载前被拒绝。

在本仓库目录执行。

```powershell
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt

$env:CUBISM53_EDITOR_HOME = 'C:/Program Files/Live2D Cubism 5.3'
$env:CUBISM53_JDK_HOME = 'C:/path/to/jdk-17'
$env:CUBISM53_WORKSPACE = 'E:/path/to/your-model-project'
.\.venv\Scripts\python.exe -m bridge.build
```

`bridge.build` 可单独验证依赖。首次调用 `cubism_native_connect` 也会按需构建，并缓存与 Java 源码对应的桥接 JAR。先手动打开 Cubism Editor。若有多个匹配进程，设置 `CUBISM53_EDITOR_PID` 为目标 **Java** 进程 PID。连接成功后通过 `cubism_native_status` 取得文档 UID。

### Codex

以下命令使用当前仓库和上述环境变量。

```powershell
$root = (Get-Location).Path
codex mcp add cubism53-native-mcp --env "CUBISM53_EDITOR_HOME=$env:CUBISM53_EDITOR_HOME" --env "CUBISM53_JDK_HOME=$env:CUBISM53_JDK_HOME" --env "CUBISM53_WORKSPACE=$env:CUBISM53_WORKSPACE" -- "$root/.venv/Scripts/python.exe" "$root/server.py"
```

### Claude Code

将 [mcp.example.json](mcp.example.json) 里 `mcpServers` 下的配置放进**模型项目**的 `.mcp.json`，并替换示例绝对路径。原有配置中已经存在的其他服务可以保留。新会话加载配置后调用 `cubism_native_connect`。

### 配置

| 环境变量 | 用途 |
| --- | --- |
| `CUBISM53_EDITOR_HOME` | Editor 安装根目录。未指定时在 Program Files 下查找。 |
| `CUBISM53_JDK_HOME` | JDK 17 安装根目录。未指定时检查 `JAVA_HOME` 和 `PATH`。 |
| `CUBISM53_WORKSPACE` | 文件工具的工作区根目录，默认本仓库。输入和输出支持相对于该目录的路径。 |
| `CUBISM53_BRIDGE_STATE` | 桥接 JAR、连接配置和令牌的目录，默认本仓库的 `.state`。 |
| `CUBISM53_BRIDGE_PORT` | 原生桥接端口，默认 `22055`。 |
| `CUBISM53_EDITOR_PID` | 可选，选择要加载桥接器的编辑器 Java 进程。 |
| `CUBISM53_PORT` | 画布预览所用的官方外部程序集成端口，默认 `22033`。 |
| `CUBISM53_ATTACH_DIR` | Windows JVM Attach 临时目录，默认 `LOCALAPPDATA`。若默认路径包含非 ASCII 字符，设置为现有的可写 ASCII 路径。工作区和编辑器路径可以包含中文。 |

同一编辑器进程内的多个客户端共享打开的文档和原生撤销栈。多个独立配置应使用各自的桥接端口和状态目录。桥接只监听 `127.0.0.1`。

更新桥接源码后，等当前作业结束，再用相同环境变量执行 `.\.venv\Scripts\python.exe -m bridge.client stop`，然后重新调用 `cubism_native_connect` 加载新构建。

首次调用画布预览工具前，在 Cubism 启用「外部应用程序集成」。收到权限提示时，保持 MCP 会话连接，在集成列表批准 **Cubism53 Native MCP** 后重试。

## 工具


| 工具（均以 `cubism_native_` 开头） | 用途 |
| --- | --- |
| `connect` | 发现并连接已经运行的指定版本编辑器 |
| `status`、`document` | 文档路径、UID、对象数量及撤销状态 |
| `objects`、`object` | 部件标识、参数范围、关键形、UV、网格数据 |
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


## 调用约定

- 编辑操作按文档 UID 执行。另存和导出要求工作区内的新路径。原生撤销也会纳入编辑器里的手工操作。
- 批量渲染和运行包组装需要 `request_id`。`reimport_psd`、`export_runtime_model` 使用 `execution=job` 时遵守同样约定。用 `job_status` 查询。当前桥接会话内，相同请求 ID 和参数复用原作业。
- 作业状态查询可以在编辑器线程忙碌时返回。同步编辑超时后，若响应表明操作已开始，请先读回文档或检查输出再决定是否重试。
- PSD 重导入先用 `plan_psd_reimport` 检查冲突。缺失的旧图层在计划中报告，原对象保留。
- `edit_batch` 先用 `mode=plan` 获取凭据，再提交同一组编辑与 `plan_id`。
- 5.3.03 的原生 SDK 导出会移除 ArtPath。`export_runtime_package` 会拦截使用可见 ArtPath 或将其用于裁剪的模型。`export_runtime_model` 会返回有关丢失的警告。

## 验证

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

测试使用本机临时端口，验证 stdio 工具发现、结构化错误和官方预览协议的重新注册。实机冒烟测试可在空白的独立 Cubism Editor 会话运行。设置 `CUBISM53_EDITOR_PID`、`CUBISM53_WORKSPACE`、`CUBISM53_BRIDGE_STATE` 和专用 `CUBISM53_BRIDGE_PORT`，然后执行 `.\.venv\Scripts\python.exe tests/native_smoke.py`。测试只使用生成的临时图像和模型。

## 许可证

桥接源码采用 [MIT 许可证](LICENSE)。运行所需的 Live2D Cubism Editor 由用户单独安装，适用 Live2D 的许可条款。
