# Cubism 5.3 Native MCP

[简体中文](README.md) · [English](README.en.md) · **日本語**

Windows x64 で MCP stdio を通じて Live2D Cubism Editor **5.3.03** と **5.3.04** を操作できます。93 個のツールから、起動中のエディタのモデリング、アニメーション、物理演算、レンダリング、書き出しの各機能を呼び出せます。キャンバスのプレビューには Cubism 公式のローカル WebSocket API を使用します。

**v0.4.1** では、大規模な PSD の再読み込み前チェック、一括リネーム、長い物理演算トレースのページ取得を高速化しました。両バージョンで 93 個すべてのツールの回帰テストに合格しています。[検証記録](VALIDATION.md)を参照してください。

## ダウンロード

- [Windows x64 ポータブル版 ZIP](https://github.com/OranImp/cubism53-native-mcp/releases/latest/download/Cubism53-Native-MCP-win-x64.zip)
- [SHA-256 チェックサムファイル](https://github.com/OranImp/cubism53-native-mcp/releases/latest/download/Cubism53-Native-MCP-win-x64.zip.sha256)
- [リリースノートとサードパーティのソースへのリンク](https://github.com/OranImp/cubism53-native-mcp/releases/latest)

ポータブル版 ZIP には、両バージョン用のビルド済みアダプター、Python、Java Attach ランタイムが含まれます。公式の Cubism Editor 5.3.03 または 5.3.04 を別途インストールしてください。ダウンロード先のディレクトリで次のコマンドを実行し、ZIP を検証できます。

```powershell
$archive = 'Cubism53-Native-MCP-win-x64.zip'
$expected = ((Get-Content -LiteralPath "$archive.sha256" -Raw) -split '\s+')[0]
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ine $expected) {
    throw 'ポータブル版 ZIP の SHA-256 検証に失敗しました'
}
```

## インストールと接続

### Windows x64 ポータブル版

1. 下表に記載された公式 Cubism Editor をインストールし、起動できることを確認します。セットアップはインストール先の `app/lib/Live2D_Cubism.jar` を照合します。接続時には対応するアダプターが読み込まれます。
2. ZIP を今後も使用するディレクトリに展開し、`Setup.cmd` を実行します。セットアップは起動中のエディタ、Windows のインストール情報、一般的なインストール先を調べます。モデルの作業ディレクトリを選び、必要に応じてエディタのインストール先を指定します。
3. セットアップに表示された `client-configs` ディレクトリから、クライアントに対応する設定を選びます。既存の Cubism 設定がある場合は、起動コマンドと引数を更新します。Cubism を起動して `cubism_native_connect` を呼び出し、`cubism_native_status` で現在のドキュメント UID を取得します。

ポータブル版には CPython、MCP の依存パッケージ、ビルド済みのブリッジ JAR、Java Attach ランタイムが含まれます。セットアップと起動はオフラインで行えます。MCP クライアントと Cubism は同じ Windows ユーザーセッションで起動してください。

| 対応する Windows x64 ビルド | `Live2D_Cubism.jar` の SHA-256 |
| --- | --- |
| 5.3.03 | `bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166` |
| 5.3.04 | `c9bb3e9960bf49908a531b7010421c393a8dd5f9376745eb6fdb273f41a1cf5d` |

対話なしでセットアップするには、展開先で次のコマンドを実行します。

```powershell
.\Setup.cmd --non-interactive --editor-home 'C:/Program Files/Live2D Cubism 5.3' --workspace 'E:/Models'
```

既定の設定ファイルは `%LOCALAPPDATA%\Cubism53MCP\profiles\default\settings.json` に保存されます。別の作業ディレクトリには、`--profile` と `--bridge-port` を指定して個別の設定を作成できます。ポータブル版を移動した場合は、セットアップを再実行して新しいパスを反映します。

両方のバージョンをインストールしている場合は、`--editor-home` で使用するインストール先を選びます。両方に同時接続するには、それぞれに異なるプロファイルとブリッジポートを指定します。使用するエディタのバージョンは、セットアップ、`doctor`、`cubism_native_status` で確認できます。

| クライアント | 設定方法 |
| --- | --- |
| CC Switch | MCP 管理で `cubism53-native-mcp` という stdio サーバーを追加します。`cc-switch.json` のコマンドと引数を設定し、同期先のクライアントを選びます。既存の設定がある場合は、コマンドと引数を更新します。 |
| Claude Code およびその他の stdio クライアント | `mcp.json` のサーバー設定をクライアントの設定に追加します。Claude Code ではモデルプロジェクトの `.mcp.json` も使用できます。 |
| Codex | 生成された `codex.ps1` を実行するか、CC Switch で同じサーバーを Codex に対して有効にします。 |
| DeepSeek Harness | `dsh.patch.yml` をローカルプロファイルへの追加設定として適用します。コマンドの例は `dsh web --patch <file>` です。デスクトップ版では、同じファイルの `insert` にある `@deepseek-ai/dsh-mcp-client` プラグイン設定を使用します。 |

設定ファイルはプロファイルの `client-configs` ディレクトリに作成されます。設定の取り込みと同期は各クライアントで行います。どのクライアントも同じ標準 MCP stdio エントリーポイントを使用します。

診断コマンドでは、エディタのバージョン、作業ディレクトリ、Attach ランタイム、ポートの状態を確認できます。プロファイルのパスは、セットアップで表示された実際のパスに置き換えてください。

```powershell
.\runtime\python\python.exe -B .\app\launcher.py doctor --profile-file "$env:LOCALAPPDATA/Cubism53MCP/profiles/default/settings.json"
```

#### 更新とトラブルシューティング

- 更新前に、実行中のジョブが完了するまで待ちます。旧ブリッジが起動中の場合は、旧版の診断コマンドで `doctor` を `stop` に置き換えて実行します。新版を展開し、新しいディレクトリから同じプロファイルでセットアップを実行した後、クライアントの設定を更新してセッションを開き直します。
- `doctor` は、エディタの PID とビルド、ブリッジプロトコル、アダプターのビルド ID を照合します。旧ブリッジやアダプターの不一致が表示された場合は、使用中のパッケージでブリッジを停止し、新版で接続し直します。
- `Unsupported Cubism editor JAR` が表示された場合は、対応表にあるインストール先を `--editor-home` に指定して、セットアップを再実行します。
- `Expected one running Cubism` が表示された場合、条件に合うプロセスが存在しないか、複数存在します。対象のエディタを起動するか、`CUBISM53_EDITOR_PID` に対象の Java プロセスの PID を指定します。
- ポートが使用中の場合は、`doctor` でそのポートが現在のプロファイルに属するか確認します。複数のプロファイルを同時に使う場合は、それぞれに異なる `--bridge-port` を指定します。
- v0.2.0 から更新した場合、各プレビューポートの初回使用時に「外部アプリケーション連携」で **Cubism53 Native MCP** を承認します。プレビューを要求した MCP セッションを接続したまま、承認後に再試行します。同じプロファイルで同じプレビューポートを使う場合は、登録済みのトークンが再利用されます。

### ソースからインストール

Windows で Python 3.11 以降と JDK 17 が必要です。ソースから使用する場合も、`launcher.py setup` でクライアント設定を生成できます。

```powershell
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe .\launcher.py setup --editor-home 'C:/Program Files/Live2D Cubism 5.3' --workspace 'E:/Models'
```

`python server.py` でサーバーを直接起動することもできます。ソースからの起動や接続先の指定には、次の環境変数を使います。

| 環境変数 | 用途 |
| --- | --- |
| `CUBISM53_EDITOR_HOME` | Cubism Editor のインストール先。 |
| `CUBISM53_JDK_HOME` | ソースからのビルドに使用する JDK 17。省略時は `JAVA_HOME` と `PATH` を確認します。 |
| `CUBISM53_WORKSPACE` | ファイル操作の基準となる作業ディレクトリ。ソースから直接起動した場合の既定値はリポジトリです。 |
| `CUBISM53_BRIDGE_STATE` | ブリッジの状態とトークンを保存するディレクトリ。ソースから直接起動した場合の既定値は `.state` です。 |
| `CUBISM53_BRIDGE_PORT` | ネイティブブリッジのポート。既定値は `22055` です。 |
| `CUBISM53_EDITOR_PID` | 複数のエディタが起動している場合に指定する対象 Java プロセスの PID。 |
| `CUBISM53_PORT` | キャンバスプレビュー用の外部アプリケーション連携ポート。既定値は `22033` です。 |
| `CUBISM53_ATTACH_DIR` | Attach の一時設定ディレクトリ。ソースから直接起動した場合の既定値は `LOCALAPPDATA` です。 |

同じエディタプロセスに接続したクライアントは、開いているドキュメントとエディタの取り消し履歴を共有します。独立した設定には異なるブリッジポートと状態保存ディレクトリを使用します。ブリッジの待ち受けアドレスは `127.0.0.1` です。キャンバスプレビューを使うには「外部アプリケーション連携」を有効にし、MCP セッションを接続したまま **Cubism53 Native MCP** を承認します。

### ポータブル版をビルド

Windows x64 で、公式の CPython 3.13.17 Windows x64 組み込み用 ZIP、Eclipse Temurin JDK 17.0.19+10、対応する両バージョンの Cubism Editor を用意します。`portable/wheels-win-x64.txt` に記載された、バージョンとハッシュが固定された CPython 3.13 Windows x64 用 wheel を取得します。ビルドはこれらのローカルファイルを使用します。

```powershell
python -m pip download --require-hashes --no-deps --only-binary=:all: --platform win_amd64 --implementation cp --python-version 3.13 --abi cp313 -r portable/wheels-win-x64.txt -d wheelhouse
python portable/build.py --python-zip python-3.13.17-embed-amd64.zip --wheelhouse wheelhouse --editor-home 'C:/Cubism/5.3.03' --editor-home 'C:/Cubism/5.3.04' --output dist/Cubism53-Native-MCP-win-x64.zip
```

ZIP と SHA-256 チェックサムファイルが生成されます。サードパーティのランタイムとライセンスは、ポータブル版のルート、およびソースの `portable/` ディレクトリにある `THIRD_PARTY.md` を参照してください。

## ツール

| ツール（共通の接頭辞は `cubism_native_`） | 用途 |
| --- | --- |
| `connect` | 起動中の対応エディタを検出して接続します。 |
| `status`、`document` | ドキュメントのパス、UID、オブジェクト数、取り消し履歴の状態を取得します。 |
| `objects`、`object` | パーツ ID、パラメータ範囲、キーフォーム、UV、メッシュを取得します。絞り込みにも対応します。 |
| `import_png` | ピクセルからキャンバスへのアフィン変換を指定して、PNG をマテリアルとアートメッシュとして読み込みます。 |
| `export_mesh_texture` | アートメッシュの埋め込みマテリアルを可逆 PNG として書き出し、画像から元のメッシュへの変換を返します。 |
| `replace_mesh_texture` | 同じサイズの PNG でマテリアルの画像を差し替えます。そのマテリアルを共有するアートメッシュにも反映されます。 |
| `set_visibility`、`set_clipping` | 表示状態やクリッピングを変更します。取り消しに対応します。 |
| `create_blank_project` | 指定したキャンバスサイズで空のモデルを作成します。 |
| `open_project`、`save_as_new`、`close_saved` | CMO3 や CAN3 を開く、新しいファイルに保存する、保存済みドキュメントを閉じる、の各操作を行います。 |
| `create_warp`、`create_rotation` | ワープデフォーマや回転デフォーマを作成します。 |
| `reparent` | 既定のパラメータ値で形状を保ったまま親を変更し、操作後にプレビューのパラメータ値を復元します。 |
| `add_parameter_keys` | 既存の通常パラメータにキーフォームを追加します。 |
| `set_form_positions` | アートメッシュやワープデフォーマのキーフォーム内にある既存の頂点を移動します。 |
| `set_form_properties` | 描画順、不透明度、回転デフォーマの角度・中心・拡大率を設定します。 |
| `set_mesh_topology` | エディタのメッシュ編集機能でトポロジーを再構築し、UV とキーフォームを再割り当てします。 |
| `auto_generate_mesh`、`auto_mesh_status` | メッシュを自動生成し、ジョブの状態や生成前後の頂点数・三角形数を取得します。 |
| `evaluate` | 独立したモデルインスタンスで補間とキャンバス座標を計算し、元のドキュメントのプレビュー値を保持します。 |
| `render_png` | 独立したパラメータのコピーを使い、オフスクリーンで透過 PNG を描画します。 |
| `release_renderers` | 指定したドキュメントのレンダリングキャッシュを解放します。 |
| `undo`、`redo` | エディタのドキュメント操作を取り消す、またはやり直します。 |
| `create_parameter` | 通常パラメータをルートグループに作成します。取り消しに対応します。 |
| `update_parameter` | 通常パラメータの名前、説明、範囲、既定値を変更します。 |
| `move_parameter_keys`、`remove_parameter_keys` | オブジェクトとパラメータを指定し、キーフォームのキーを移動または削除します。 |
| `move_to_part`、`rename_object` | パーツツリー内でオブジェクトを移動・並べ替えたり、表示名を変更したりします。 |
| `set_blend_mode`、`set_part_offscreen` | アートメッシュやパーツのブレンドモード、パーツのオフスクリーン描画を設定します。 |
| `physics` | 物理演算グループ、入出力、粒子、正規化設定を取得します。 |
| `update_physics_group` | `physics` が返すリストのインデックスを使って、単一の物理演算グループを編集します。 |
| `import_physics3` | エディタのインポーターで物理演算 JSON を読み込みます。取り消しに対応します。 |
| `simulate_physics` | 独立したコピー上で Cubism の物理演算を実行し、フレームごとの出力を返します。 |
| `list_glues`、`create_glue` | グルーの頂点ペア・ウェイト・強度を調べるか、2 つのアートメッシュの頂点 UID ペアでグルーを作成します。 |
| `update_glue_weights`、`remove_glue_pairs`、`set_glue_intensity` | 頂点ペアのウェイトを変更する、ペアを解除する、キーフォームのグルー強度を設定する、の各操作を行います。 |
| `set_preview_parameters`、`clear_preview_parameters` | 公式のローカル WebSocket API を使い、モデルビューのパラメータを一時的に設定または解除します。 |
| `create_part`、`duplicate_objects` | パーツを作成するか、オブジェクトとその子孫を複製して GUID の対応表を返します。 |
| `create_parameter_group`、`move_parameter_to_group` | パラメータグループを作成するか、既存のパラメータをグループ間で移動します。 |
| `add_glue_pairs`、`suggest_glue_pairs` | 既存のグルーに頂点ペアを追加するか、キャンバス上の距離から対応候補と重複候補を調べます。 |
| `fit_warp_to_children` | Cubism 5.3 の機能を使用し、子オブジェクトを囲むようにワープデフォーマを拡張します。 |
| `import_psd`、`plan_psd_reimport`、`reimport_psd` | PSD からモデルを作成し、レイヤーの対応を事前確認して再読み込みします。 |
| `edit_texture_atlas` | テクスチャアトラスの確認、ページの追加やサイズ変更、マテリアルの自動配置や手動配置・回転を行います。 |
| `create_animation_scene`、`set_timeline_keys` | モデルをリンクしたシーンの作成・追加や、パラメータカーブへのキーの書き込みを行います。 |
| `export_physics3`、`export_runtime_model`、`export_motion3` | 物理演算ファイル、SDK 用モデルとテクスチャ、単一トラックのモーションを書き出します。 |
| `render_pose_set`、`job_status` | 名前付きポーズをまとめて描画し、レンダリング、メッシュ自動生成、PSD、SDK 書き出しのジョブ状態を確認します。 |
| `inspect_model_dependencies`、`edit_batch` | モデルの依存関係を調べ、複数の編集を事前検証して、1 回で取り消せる単位で適用します。 |
| `get_timeline_keys`、`remove_timeline_keys`、`sample_timeline_parameters` | CAN3 のパラメータカーブのキーを取得・削除するか、フレームごとの値を取得します。 |
| `edit_animation_tracks`、`edit_animation_events` | 既存のシーンにモデルトラックを追加し、トラックの移動やトリミング、シーンフレーム上のイベント編集を行います。 |
| `preflight_model_edit` | 通常パラメータのバインド追加、キーフォーム数、共有マテリアルへの影響を事前確認します。 |
| `edit_physics_groups` | 物理演算グループの追加・削除・並べ替えと、入出力の割り当て、重力、風、FPS の編集を行います。 |
| `edit_blend_shape` | 既存のブレンドシェイプパラメータを再利用し、対象の形状を編集したり、オブジェクトのバインドを解除したりします。 |
| `edit_art_path` | アートパスのブラシ、開閉状態、制御点、アートメッシュとのバインドを編集します。 |
| `list_preview_views`、`get_preview_parameters` | モデルビューの UID を一覧表示し、承認済みの公式 API でパラメータを取得します。 |
| `list_jobs` | 現在のブリッジセッションのジョブを、ドキュメント、種類、状態で絞り込んでページ単位で取得します。 |
| `preflight_runtime_export`、`export_runtime_package` | SDK との互換性を確認し、モデル、モーション、表情、物理演算をまとめて書き出します。 |
| `set_form_colors`、`replace_mesh_texture_resized` | キーフォームの乗算色やスクリーン色を変更するか、異なるサイズのテクスチャに差し替えます。 |
| `create_blend_shape` | 通常のアートメッシュのキーフォームを基に、ブレンドシェイプを作成します。 |
| `export_expression3` | パラメータ ID、合成方法、フェードイン・フェードアウト時間を指定して SDK 用の表情ファイルを書き出します。 |
| `edit_animation_scene`、`batch_edit_timeline` | シーンの FPS、長さ、ループ、フェードを設定するか、複数のパラメータカーブを 1 回で取り消せる単位で編集します。 |
| `edit_part_rendering` | パーツのオフスクリーン描画、クリッピング、マスク反転、描画順グループを設定します。 |
| `mirror_keyforms` | キャンバス上の基準位置を指定し、アートメッシュやワープデフォーマのキーフォームを対象のキーフォームに反転コピーします。 |
| `plan_delete_objects`、`delete_objects` | オブジェクト削除の影響を確認し、その結果に基づいて 1 回で取り消せる単位で削除します。 |
| `inspect_art_paths`、`create_art_path` | アートパス、ブラシ、点、バインドを確認するか、キャンバス座標を指定してアートパスを作成します。 |

## 呼び出し時の仕様

- 編集対象はドキュメント UID で指定します。別名保存と書き出しには、作業ディレクトリ内の新しいパスを指定します。取り消しの対象にはエディタ上の手動操作も含まれます。
- 一括レンダリングとランタイムパッケージの書き出しには `request_id` が必要です。`reimport_psd` と `export_runtime_model` で `execution=job` を指定する場合も同様です。進捗は `job_status` で確認できます。完了したジョブは直近 256 件まで、最長 24 時間保持されます。保持期間内に同じ引数とリクエスト ID を指定すると、既存のジョブが返ります。有効期限切れの記録は直近 4,096 件まで、最長 7 日間保持されます。ジョブの記録が期限切れになった場合は、ドキュメントや成果物を確認し、再実行が必要なジョブに新しい ID を指定してください。
- ジョブの状態確認には専用チャネルを使用します。通常のリクエストが混み合うと、再試行可能なビジーエラーが返る場合があります。処理開始後に同期編集がタイムアウトした場合は、ドキュメントや成果物の状態を確認してから再試行します。
- PSD の再読み込み前に `plan_psd_reimport` で競合を確認します。見つからないレイヤーに対応する既存のオブジェクトは保持されます。復帰したレイヤーが同じ PSD の履歴と一意に対応する場合は、元のマテリアルを更新します。競合を解消するには候補の `key` を `layer_map` に入れてもう一度事前確認します。履歴のキーは `ソースGUID:レイヤーキー` の形式です。新しいレイヤーとして作成する場合は、対応先に `new` を指定します。
- `edit_batch` では `mode=plan` で計画を作り、同じ編集内容と `plan_id` を送信して適用します。パーツの移動は指定した順番で検証されます。関連するドキュメントの状態が変わると計画は無効になります。
- 5.3.03 のエディタの SDK 書き出しではアートパスが省略されます。表示中のアートパスやクリッピングに使われるアートパスがあるモデルは、`export_runtime_package` で書き出しが中断されます。`export_runtime_model` では省略に関する警告が返ります。
- `objects` では `sections`、`part_guid`、`parameter_id`、`object_types` を指定できます。型には `CArtMeshSource` のように、ツールが返した名前を使います。`parameter_id` は通常パラメータやブレンドシェイプへの直接のバインドを対象とします。`object` では `form_guids` でキーフォームを絞り込み、`geometry=false` で頂点、トポロジー、UV を省略できます。
- `simulate_physics` は既定でフレームごとの出力を返します。`result_mode=summary` で出力の範囲を取得でき、`output_parameter_ids` で出力を絞り込めます。`execution=job` と `request_id` を指定すると、状態を非同期で確認できます。4K 以上のレンダリングはレンダリング枠を専有します。メモリが不足する場合は、描画用バッファを確保する前にエラーが返ります。
- 表情データの `Add` は加算、`Multiply` は乗算、`Overwrite` は絶対値での上書きです。ランタイムパッケージの事前確認と書き出しでは同じ添付ファイル検証が使われ、診断結果には対象ファイルとフィールドが含まれます。
- `edit_animation_scene` で FPS を変更しても、キーフレームの整数フレーム位置は保持されます。カーブ操作の `frame_space` の既定値は `track` です。`scene` を指定するとシーンフレームで読み書きできます。イベント操作にはシーンフレームを使用します。Motion3 では、エディタの書き出し機能を使ってトラックイベントを含めます。
- `document` は並行編集の検出に使う `revision` を返します。`undo`、`redo`、`edit_batch` の `expected_revision` に指定できます。`open_project` と `close_saved` は既定で直ちに応答します。完了を待つには `wait_seconds` を指定します。
- `objects(offset, limit)` で一覧をページ単位で取得できます。`evaluate(poses=...)` で複数のポーズをまとめて計算し、`status(lightweight=true)` でドキュメント UID を確認できます。物理演算の出力を新しい JSON に保存するには `trace_path`、結果をページ単位で取得するには `job_status(frame_offset=..., frame_limit=...)` を使います。
- `edit_texture_atlas` の `place` と `resize_page` では、`preflight=true` でページ外にはみ出すマテリアルや重なり合う組み合わせを確認できます。メッシュのトポロジー変更と物理演算の読み込みでも `preflight=true` を使えます。自動メッシュ生成は、対象のグルーやアートパスのバインドを報告します。
- ミラー操作では `axis=x` が X 座標、`axis=y` が Y 座標を反転します。`vertex_indices` には反転先の頂点順に元の頂点インデックスを指定します。削除の事前確認では、削除対象に含まれていない子孫や削除後も残るオブジェクトからの参照がある場合に処理を中断します。`cascade` と `detach_references` で削除や参照解除の範囲を明示できます。残すアートパスの点のバインドは、先に `edit_art_path` で解除してください。新しいモデルにカスタムブラシがない場合は、`brush_guid` を省略して既定の線を使用できます。

## 検証

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

テストでは一時ローカルポートを使い、stdio のツール検出、構造化されたエラー、公式プレビュープロトコルによる再登録を確認します。実機のスモークテストには、分離した空のエディタセッションを使います。`CUBISM53_EDITOR_PID`、`CUBISM53_WORKSPACE`、`CUBISM53_BRIDGE_STATE`、専用の `CUBISM53_BRIDGE_PORT` を設定して `.\.venv\Scripts\python.exe tests/native_smoke.py` を実行してください。テストでは一時生成した画像とモデルを使用します。ポータブル版の起動、一括編集、PSD 再読み込み、グルー検索の性能については検証記録を参照してください。 [検証記録（中国語）](https://github.com/OranImp/cubism53-native-mcp/blob/main/VALIDATION.md)

## ライセンス

ブリッジのソースコードには MIT ライセンスを適用します。Live2D Cubism Editor は Live2D の利用規約に従って別途インストールしてください。 [MIT ライセンス](LICENSE)
