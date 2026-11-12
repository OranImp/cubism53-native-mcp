# 验证记录

## v0.2.0 双版本包

同一份 Windows x64 ZIP 在 Cubism 5.3.03 和 5.3.04 中分别通过隔离实机测试。Setup 按编辑器 JAR 的 SHA-256 选择适配器，连接状态报告对应的版本。两版均发现 75 项工具，名称和输入输出定义与基线相同，连接工具说明更新了适用版本。

两个构建均验证了批量编辑、撤销重做、PSD 连续重导入、模型保存重开、离屏渲染、求值、Glue 候选搜索、ArtMesh 网格提交、图集、物理、MOC3 和 Motion3 导出。5.3.03 另外复跑了旧版 PSD 来源推断及多来源歧义。实机用例在新的编辑器进程和临时工作区中运行。

## v0.1.0 优化基线

优化基线为 `2611ec9`，编辑器为官方 Cubism 5.3.03 固定构建。实机测试在单独的编辑器进程、端口、工作区和状态目录中运行，使用生成的 PNG、PSD 和 CMO3。

| 检查 | 结果 |
| --- | --- |
| MCP 兼容性 | 源码、便携入口和基线的 75 项工具定义逐项相同。 |
| 安装 | 用仅保留 Windows 系统目录的 PATH 运行 Setup 和实际便携服务器。测试覆盖路径中的中文和空格、重复配置、错误编辑器版本和端口冲突。 |
| 批量编辑 | 实机验证连续移动的合法顺序、同组重排、越界及小数索引、过期计划、单组撤销与重做。 |
| PSD | 实机验证三版连续重导入、旧版 CMO3 的来源推断、独立多来源歧义、同名冲突、缺层保留、撤销重做、保存重开。ArtMesh GUID、网格、UV 和关键形保持，代表姿态的像素反映了新的贴图且保留缺失层。 |
| 调度 | stdio 在 24 个普通调用下保留状态查询通道，额外普通调用未送到桥接。Java 在编辑器线程阻塞时验证队列容量、重复请求和轮询。 |
| Glue | 500 组固定随机样本及边界样本与逐对算法逐项相同。两侧各 4,096 顶点的稀疏样本从 16,777,216 次距离计算降到 4,096 次。本机单次计时约 61 ms 降到 2 ms。 |
| 客户端接入 | 通用 JSON、CC Switch 条目和 DSH YAML 生成同一个便携命令。Codex 读取配置成功，DSH Web 合成配置成功。 |

便携实机测试在开发机隔离运行。独立干净 Windows 虚拟机和各桌面客户端的交互导入仍需验收。

## 重现

```powershell
python -B -m unittest discover -s tests -v
javac --release 17 -d .state/test-classes java/NativeBridgeGlueSearch.java tests/GlueSearchCheck.java
java -cp .state/test-classes cubism53.mcp.GlueSearchCheck
```

`NativeJobsCheck.java` 使用已编译的桥接 JAR 和 Cubism 安装目录中的 `app/lib/*`、`app/lib/jogl/*` 作为 classpath，运行入口为 `cubism53.mcp.NativeJobsCheck`。

便携实机用例需要测试环境安装 Pillow 与 psd-tools。准备空白的独立测试环境并解压 ZIP，命令会启动一个专用编辑器进程。

```powershell
python -B tests/portable_smoke.py --package 'C:/path/to/Cubism53-Native-MCP' --editor-home 'C:/path/to/Live2D Cubism 5.3'
```

分别用两个受支持的 `--editor-home` 执行同一份便携包。可传入 `--baseline-server` 指向独立解出的 `2611ec9/server.py`，比较旧版工具定义。5.3.03 同时生成旧版 PSD 重导入样本。
