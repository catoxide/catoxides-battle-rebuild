# ContentPack Editor 0.2.1 自查报告（功能 / 逻辑完备性）

> 范围：`tools/contentpack-editor` 全模块（主进程 IPC、启动/项目根、包管理、实体/结构/状态机/模型/片段编辑器、
> 暂存区、原版导入导出、i18n、测试覆盖）。方法：静态对账脚本（i18n 键 / DOM id / preload API / IPC 通道）
> + 逐模块读码 + 真实数据包往返测试 + 打包版实机冒烟。
> 本报告只列**确认过**的问题；已修项给出修法与测试，未修项给出原因与建议优先级。

## A. 已修复（本次自查，含回归测试）

### A1. 反馈机三按钮失效（0.2.1 主修复，已在 RELEASE-0.2.1.md 详述）
- 根因：无 mod 工程时 `init()` 在 `setupProjectRoot()` 后 `return`，后续绑定全部跳过。
- 修：`src/core/projectRoot.js` 回退链（配置 → 探测 → 文档工作区 → userData 工作区 → 临时目录），
  渲染端去掉 early return；新增 `🌱 独立工作区` / `📁 连接工程…` / `🩺 诊断`。
- 追加：`ensureWorkspace` 增加**写探针**——目录存在但只读/被拦写时换下一个候选（此前要到保存才炸）。
  测试 `test/projectRoot.test.js` 由 8 项增至 9 项。

### A2. i18n 缺失键
- `editor.refresh` 只在 zh 定义 → 英文界面按钮显示原始 key。已补 en。
- 静态对账：423 个 zh 键 / 303 处使用，仅此 1 处缺失（另 1 处 `'key'` 是注释误报）。

### A3. 结构编辑器「打开 → 保存」静默丢字段
`src/views/structureEditor.js` 的 `collectInto()` 用 DOM 重建 `pieces` / `templatePools`：
- `piece.blockNbt`（P12 方块实体 NBT，由片段 3D 编辑器写回）没有 DOM 控件 → 保存即丢。已按片段名保留。
- 池元素的 `elementType`（`legacy_single_pool_element`）与 `processors`（处理器列表）没有 UI →
  保存即丢。已用行 `dataset` 暂存并原样带回。
- 模板池 `fallback` 默认值写成了 `minecraft:empty_pool_element`（那是**元素类型**，不是池 ID）→
  新建池导出后 fallback 非法。已统一归一为 `minecraft:empty`（渲染/收集/新建三处）。
- `piece.importFrom` 保留（导入来源回溯用）。

### A4. 原版导入（`src/core/vanillaImport.js`）
- 起始池被判定为空池丢弃后，`structure.startPool` 仍写成 `namespace:被丢弃池名` → 导出的数据包
  `start_pool` 指向不存在的池（游戏报错）。已改为 `minecraft:empty` 并保留警告。
- 「未识别字段」警告文案自相矛盾（"已保留? 否"）→ 改为"已丢弃"。
- 新增 3 项往返测试（此前 `vanillaImport.js` **零测试**）：
  V1 数据包文件夹 → 蓝图（池/模板/jigsaw/结构集、processors/elementType 保留）；
  V2 导入的蓝图再导出回原版文件（processors/element_type/start_pool/placement/模板 NBT 往返）；
  V3 单 `.nbt` → 单片段蓝图。

### A5. 片段 3D 编辑器的「二次打开丢 payload」（高）
`main.js` `pieceEditor:open` 在"窗口有未写回修改 → 先 flush"分支**从未设置 `pieceEditorPending`**，
而 `deliverEditorPayload()` 依赖它 → 新目标永不投递；标题已换成新结构、画面仍是旧片段。
另外 `win.webContents.once('pieceEditor:flushed')` 监听的是 webContents 事件（IPC 到的是 `ipcMain`），
该回调永远不会触发。已改为：进入分支先存 `pending`，`ipcMain.on('pieceEditor:flushed')` 里立即投递，
保留 1500ms 兜底；关窗"写回并关闭"也补了 1500ms 超时兜底（此前渲染端异常会导致窗口关不掉）。

### A6. 3D 直写与主窗口草稿的互相覆盖（高，三处）
- `modelEditor:saveEntityDirect` 用**正式包实体**当基线，再把结果整份回写暂存草稿 →
  主窗口尚未导出的草稿字段（attributes/behaviors/size…）被覆盖。
  已改为只把 `animations` 合并进现有草稿（存在时），正式包仍按字段级合并。
- `modelEditor:saveHealthDirect` 只写正式包、不同步暂存部位草稿 → 主窗口随后"导出"用旧草稿覆盖回去。
  已补暂存同步。
- 主窗口 `entityEditor.save()` 携带打开时的旧 `animations`，导出时整体覆盖 3D 直写结果。
  已改为保存前 `readEntity` 取磁盘最新 animations。

### A7. 主窗口保存会误删部位配置（中）
`readHealthFor()` 把"读取失败"吞成 `{parts:[]}` → 保存时走 `deleteHealth`，删掉损坏但可修的正式配置。
已区分 `_readFailed`：读取失败时提示并**不删除**。

### A8. 暂存区健壮性
- `stageEntity`/`stageStructure` 会把 `entity:read` 注入的 `_staged` 等内部字段原样落盘 →
  导出后进入正式包。已统一 `stripInternal()` 剥离 `_` 前缀字段。
- 暂存读/删/写路径中的 id、namespace 未 sanitize（IPC 参数含分隔符时可越界）。已全部 sanitize。
- `test/staging.test.js` 已存在但**不在 `npm test` 里**（11 项从没跑过）→ 已加入套件并补 2 项
  （内部字段剥离 / 越界防护）。

### A9. 状态机编辑器崩溃与入口缺失
`src/views/tapeEditor.js`：
- 状态重命名/删除后 `S.sel` 旧值仍指向不存在的状态，`commit()→renderAll()` 里
  `renderStateInspector` 读 `st.terminal` 抛 TypeError → 检查器半渲染、徽标不更新、
  `S.sel` 悬挂（改名/删状态必崩）。已在 `renderInspector()` 入口校验选中有效性；
  改名/删除改为先更新选中再变更文档。
- 「+ 里程碑」按钮渲染在 `if (seg.milestones.length)` 内 → 没有里程碑的段无法添加第一个。已移出。
- `open()` 不 `stopSim()` → 换文件后旧 `setInterval` 变孤儿继续 tick 新文档。已修。
- 状态机 JSON 解析失败只提示不关闭 → 画布留着上一个文件的旧 DOM（"假编辑器"）。已改为真正 `close()`。

### A10. 模型编辑器未保存修改无任何拦截（高）
`doc.dirty` 只写不读：关窗、切换模型、新建模型全部静默丢修改。
- 新增 `modelEditor:dirty` 通道（渲染端 1s 上报，保存后立即上报）+ 主进程 `close` 拦截
  （「取消 / 不保存关闭」）；关窗时顺带清 `modelEditorEntityCtx`（此前会残留上一次的实体上下文）。
- 换模型（新建/仓库打开/文件打开共用的 `applyLoadedModelCore`）前 `confirm` 确认。
- 遗留：没有"保存并关闭"（需要知道保存目标，留待后续）。

### A11. `.bbmodel` 源的「💾 保存」永远失败（高，功能断点）
主进程 `modelEditor:saveModel` 只接受包源目录内 `.json`，而 `.bbmodel` 源在包外 →
报「路径超出包源目录」。已增加白名单分支：**只允许覆写已存在的 `.bbmodel` 文件**
（文件由打开对话框产生，不新增任意路径写入口）。

### A12. 其它小修
- `contractView` 每次渲染都对静态按钮 `addEventListener` → 监听器累积，点一次打开 N 次状态机编辑器。改为 `onclick` 赋值。
- 包列表首次加载被触发两次（`PackList.init` 与 `app.js` 各一次），去掉一处。
- 极端兜底（工作区也建不出来 + 用户取消选择）时顶栏仍为空 → 补"（未设置）"提示（与反馈机症状解耦）。
- 语言切换只翻译静态 DOM → 动态列表停留在旧语言。切换后重拉包列表/内容列表（打开中的编辑面板仍需重开）。
- 独立工作区里点「打包」此前只会得到一句 powershell 报错。`pack:build` 前置检查构建入口
  （无 gradlew 且无 build-pack.ps1）→ 给可操作提示"请先连接工程"。
- `pack:build` 的 `outJarName` 未校验（拼进输出路径/脚本参数）→ 收紧字符集（同 REVIEW-S7 思路）。
- AI API（127.0.0.1:48900）无鉴权为 REVIEW-0.2.0 已知遗留；补浏览器侧两道闸：
  带 `Origin` 头（浏览器）直接 403、非 `application/json` 415 —— 阻断网页 CSRF，CLI 不受影响。
- 调试发现：英文界面 `editor.refresh` 键缺失（A2）；`preload` 键总数 93 → 94（新增 modelEditorDirty）。

## B. 未修复 / 已知风险（按建议优先级）

| # | 位置 | 问题 | 建议 |
|---|---|---|---|
| B1 | `main.js` 全部写文件处 | 非原子写（无 temp+rename、无 .bak）；`modelEditor:saveModel` 不校验 JSON 内容 | 统一 `writeFileSync(tmp)+renameSync`；`.json` 落盘前 `JSON.parse` |
| B2 | `modelEditor:writeTexture/saveTextureDialog/readTexture` | 贴图无 PNG 签名/尺寸/体积校验；读侧无上限（`model:readTexture` 有 16MB，这里没有） | 读写共用 `pngSize` + 字节上限 |
| B3 | `model-editor/app.js` + `ui/pixelEditor.js` | 像素缓冲按模型声明尺寸分配（最大 4096²≈256MB），撤销栈存 30 份全量快照；尺寸不符时把贴图缩放后回写原 PNG（静默降采样） | 尺寸不符禁止像素编辑；history 改增量/环形；写入前比对原图尺寸 |
| B4 | `model-editor/app.js` `persistAnims` | 路径不符合 `spark_models/<type>/<ns>/<name>.json` 时静默 `return null`，保存仍报"已保存"；新增动画不更新契约 `animations.required`（契约视图会报红） | 返回失败明细并改文案；`persistAnims` 后追加 required |
| B5 | `main.js` 模型编辑器一族 | 只认 `PACKS_SOURCE_DIR`，不认 W6.1 的 `NEW_PACK_DIR` → 新建包目录里的包在"另存为/仓库"看不到 | 复用 `safePackDir` 的两个根 |
| B6 | `model-editor/index.html` | 整窗未接 i18n（不加载 `i18n.js`），切英文后主窗口/片段窗口英文、模型编辑器仍中文；`pieceEditor.js` 还有约 15 处硬编码中文 | 接 i18n（key 已在主字典中） |
| B7 | `structureEditor` 3D 写回 | 写回时无条件覆盖 `boxFills/fillBlocks` 并重建 DOM（主窗口该面板未提交的输入丢失）；tab 已关闭时 `if (!tab) return` 静默丢写回 | 按条目合并 / `!tab` 时提示并保留 payload |
| B8 | 主窗口页签 | 结构/实体编辑器关闭页签无"未保存"提示；应用退出只提示**磁盘暂存草稿**，内存里未保存的编辑直接丢 | 页签 dirty 标记 + 关闭确认；`beforeunload` 或主进程提示 |
| B9 | `model-editor/app.js` | 新建模型贴图尺寸上限 4096 与像素缓冲叠加即 OOM（属 B3 一部分） | 同 B3 |
| B10 | `main.js` `saveModelAs` 后 | 不重载动画文档（`doc.animRel/animations` 仍是旧模型）；契约 `animations.dir` / `ref_pack` 跨包复用未支持 | 产品语义确认后再改 |

## C. 静态对账结果（本次脚本）

| 检查 | 结果 |
|---|---|
| i18n：使用但 zh 缺失 | 0（`'key'` 为注释误报） |
| i18n：使用但 en 缺失 | 0（已补 `editor.refresh`） |
| `getElementById` 引用但无对应 id | 0 |
| `window.api.X` 未在 preload 暴露 | 0 |
| preload 调用但 main 无 handler | 0 |
| main handler 但 preload 未包 | 0（片段/模型编辑器走 send/on，不在此列） |
| preload 暴露但渲染端未用 | 11（`readManifest/saveManifest/ensureScaffold/setProjectRoot/stagingAll/stagingDiscard/stagingGetRoot/getLogInfo/mcAssets/mcBlockIcon/importStructureVanillaPath`） |

> 上述 11 个"未使用"多为**有 handler 但 UI 未接**的能力，不是坏代码：`stagingDiscard`（丢弃某包全部草稿）
> 最值得接一个按钮——目前只能靠退出对话框丢弃；`ensureScaffold`（补齐 Gradle 骨架/入口类）也只在
> 预检里报告问题、没有一键修。其余（`readManifest/saveManifest` 的清单编辑器、`stagingAll/getRoot`
> 的草稿总览）属未完成的旧功能面。

## D. 测试覆盖现状

- 套件：`npm test` 33 个 node 文件（含本次新增的 staging 13 项、structure +3 项、projectRoot 9 项）。
- 新增：`vanillaImport` 首次有测试（V1-V3）；`staging` 从"存在但没跑"变为入套件。
- 仍无单测：`editorApi`、`manifest`、`structurePreview`（3D）、各 `views/*` 的交互逻辑
  （靠 CDP 冒烟，见 `p*.mjs` 脚本）、`model-editor/app.js` 的 gizmo/贴图链路（有 core 级测试）。

---

## E. 第二轮：按钮行为逐项核对（用户提问"哪些按钮没按预期行为"）

### E1. 已修（按钮现在符合预期）

| 按钮/入口 | 修复前行为 | 根因 | 修复 |
|---|---|---|---|
| 左栏「创建」（新建包） | 空名校验/创建失败/创建成功消息**全部不可见**，像点了没反应 | `#statusBar` 挂在默认隐藏的 `#editorContent` 里 | 状态条移到全局（`index.html` 顶栏下方），空内容时不占位，加 `pre-wrap` 显示多行 |
| 结构列表「删除」 | 失败提示不可见；成功无反馈；文案错写成"保存失败"；`data/` 里已导出文件残留（游戏里还在生成） | 同上 + `structure:delete` 只删 blueprint/草稿 | 全局状态条 + 删除文案 i18n + 删除时按蓝图清理 `data/<ns>/…`（共用的模板/池保留） |
| 契约「打包」 | 只显示 `ok` / `BUILD OK`（英文）；结果无 jar 路径、无 stdout/stderr、无 verify；打开后切包会打到**上一个包**；可连点并发跑 Gradle | `res.data.output` 字段不存在；`packDir` 在 `open()` 时固化；无 `isBuilding` | 完整渲染 preflight/mode/task/jar/path/stdout/stderr/verify/error；i18n 状态；改以当前选中包为准；构建期间禁用按钮 |
| 契约「刷新」 | 固定跳回第一个集，用户当前集丢失 | `open({set: report.sets[0]})` | 记住 `currentSetId` 恢复；左栏集列表加 active 高亮 |
| 契约「状态机编辑」 | 包建在「新建包目录」（≠包库）时必报「包不存在」 | `tapeResolvePackFile` 只认 `PACKS_SOURCE_DIR` | 允许根改为「包库 + 新建包目录」（与 `safePackDir` 一致）；CDP 实测跨根可读 |
| 结构「打包」 | 不保存当前编辑、不重新生成原版数据 → jar 里 `data/` 是旧文件却报 Build OK | `build()` 只做 staging 导出（可选）+预检+Gradle | 先 `collectInto + applyVoxelEdits + saveStructure`，再 `stagingExport`，再 `exportStructureVanillaAll`，全成功才构建；任一步失败即中止；按钮禁用 |
| 结构「导出原版数据」 | 成功后徽标仍显示"草稿未导出"，列表不刷新 | 未重置 `_staged` | 成功后重置 `_staged`/`isNew`、刷新列表 |
| 结构「+ 添加片段」 | 默认 `boxFills.to=[8,0,0]` 必然越界 → 保存报错 | 默认值写错 | 改为 `[7,0,7]`（尺寸内）；CDP 实测保存成功 |
| 顶栏「⚡ 硬件加速 / 🔍 调试」 | 启动时 GPU 按钮标签与实际模式相反；`getSettings` 失败会**反向切换**；英文模式仍显示中文 | 启动只刷了调试按钮；失败被当成 `false`；文案硬编码 | 启动按设置同时刷新两个按钮；读取失败直接报错不切换；文案走 i18n（切语言即时刷新） |
| 顶栏「📦 包库 / ➕ 新建包目录」 | 选完目录后旧选中包与右侧编辑器不清理 → 后续打包报「非法的包目录」 | `refreshAfterPackPaths` 只重拉列表 | 根变化时清选中/关闭编辑器/禁用新建；新建包目录按钮改名（不再与左栏「+ 新建」混淆） |
| 左栏包列表 | 空列表无提示；加载失败保留旧列表且错误不可见；无草稿标记；创建后不选中新包 | 无空态/无 staged 聚合 | 空态与错误态在列表内渲染；每行显示"草稿 N 项未导出"；创建后自动选中 |
| 切语言（下拉） | 契约正文/结构面板/顶栏开关停在旧语言 | 只重画了静态 DOM 和两个列表 | `ContractEditor.rerender / StructureEditor.rerender / TapeEditor.rerender` + 顶栏按钮刷新 |
| 删除结构（列表） | 文案/错误都走到隐藏的实体状态条 | `window.showError` 只写 `#statusBar` | 全局状态条已解决；删除失败专用文案 `dyn.deleteStructureFail` |

### E2. 顺带修掉的"按钮背后的逻辑缺陷"

- 多结构同名片段/池导出会**静默覆盖**彼此的 `.nbt`/池 JSON：导出前查跨结构冲突并拒绝（提示改名），整包导出同样检查。
- 完整性校验缺口：`startPool` 必填、`maxDistanceFromCenter ≤128`、`fillBlocks` 边界、方块 ID/`finalState` 语法。
- 导入结构的池引用是完整 ID（`ns:name`）而生长/诊断按裸名匹配 → 3D 预览"池不存在"、导航树全 ✗；已归一化（含回归测试）。
- `selection_priority` 字段名笔误（编辑器写 camelCase）→ 优先级恒 0；两种写法都认（含回归测试）。
- 「圈地读取」丢/残留 `blockNbt`（箱子内容、刷怪笼）；已同步替换。
- 导入 `startHeight` 的 `above_bottom`/`{type:…}` 被保存改成 `{absolute:0}`；未修改时原样保留。
- 草稿 JSON 损坏会让整个结构/实体列表失败；读取侧已兜底。
- 「导出原版（全部）」不含暂存草稿却报成功；现在报告里列出未导出的草稿 id。
- 新建包同名（两个根之间）会共用产物名/暂存键；创建前两个根都查重。
- 契约正文两处未转义（`onEnter`、`wiring.state_machine.type`）已统一 `esc()`。

### E3. 仍未修（明确遗留）

| # | 按钮/位置 | 现状 | 说明 |
|---|---|---|---|
| E3-1 | 左栏包列表 | 没有**包级**「打包 / 重命名 / 删除」按钮 | 属功能缺口（打包只在各编辑器内）；需要确认是否要加，删除属破坏性操作 |
| E3-2 | 结构导出 / 打包 | 编辑/导出流程依赖用户按顺序点（保存草稿→导出→导出原版→打包）；`build()` 已自动串联，但「导出原版数据」按钮本身不会先保存 | 单结构导出前建议同一套 collect+save（当前依赖用户先保存） |
| E3-3 | 结构 `exportVanilla` | 先写正式蓝图、删草稿，再生成 `data/`；中途失败会丢草稿且无备份（非原子写） | B1 同族，需统一事务化写盘 |
| E3-4 | 结构重命名 | 改名后旧草稿/旧 `data/` 文件残留（列表会出现两个结构） | 需要"旧 id"跟踪 + 清理事务 |
| E3-5 | 结构 biome tag | 自定义 `#ns:tag` 只校验语法，不生成 tag 文件（游戏里引用不存在） | 需产品决定：仅允许原版 tag，或自动生成 |
| E3-6 | 实体「导出」「打包」等 | 仍用原生 `confirm()`（阻塞式对话框，样式不可控；自动化测试需拦截） | 可换 `lib/askModal.js`（页面内模态框），需改为 async 流程 |
| E3-7 | 主进程 `pack:build` | 无同包并发锁（两个窗口同时打包会争同一 jar） | 需主进程串行化 + UI 防重入（UI 侧已禁用按钮） |

