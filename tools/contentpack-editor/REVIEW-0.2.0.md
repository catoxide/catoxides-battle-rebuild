# ContentPack Editor 0.2.0 代码审阅报告

> 审阅对象：`dist/ContentPack Editor 0.2.0.exe`（electron-builder win portable，73.7 MB，2026-10-10 21:15 构建）
>
> **一致性结论**：已从 `dist/win-unpacked/resources/app.asar` 解包并与源码树逐文件哈希比对，**完全一致（0 diff）**。本报告全部发现同样适用于该 EXE。
>
> 测试基线：`npm test` 全绿（28 个测试文件全通过）。按审阅要求，**不以测试通过与否为依据**，聚焦测试未覆盖的边界 bug。
> 下文标注了每个发现被现有测试覆盖的情况（均为"未覆盖"或"仅覆盖函数本身、不覆盖 IPC 接线/数据流"）。

---

## 严重（数据丢失 / 任意文件读写 / 命令注入）

### S1. 实体"保存草稿"静默丢失编辑 —— `health:delete` 的连带删除副作用
**严重度：严重（最常见保存路径的数据丢失）**
- 位置：`main.js:1392-1400`（`health:delete` 处理器）+ `src/core/staging.js:230-244`（`removeStagedEntity`）+ `src/views/entityEditor.js:597-622`（`save()`）
- 机制：`health:delete` 无条件执行 `Staging.removeStagedEntity(packName, entityId)` —— 它删除 **entities/<id>.json 实体草稿** + **所有 namespace 下**该 id 的 health 草稿。而 `entityEditor.save()` 的流程是：
  1. `saveEntity` → 实体草稿写入暂存区（staging.js:38-44）；
  2. 若实体无部位配置（`cfg.parts` 为空 —— **大多数实体的常态**，`readHealthFor` 对不存在的配置返回 `{parts:[]}`，见 entityEditor.js:184-189 与 main.js:1363-1375），走 `else` 分支调用 `deleteHealth`；
  3. `health:delete` → `removeStagedEntity` → **把第 1 步刚写入的实体草稿删掉**。
- 触发：打开任一没有部位配置的实体 → 修改任意字段 → 点"保存" → UI 提示"已保存为草稿"并刷新列表（草稿标记消失）→ **重开实体发现改动全部丢失**。用户完全无感知。
- 次生场景：用户在部位页签里主动清空全部部位后保存（本意只删 health 草稿），实体草稿同样被误删。
- 测试覆盖：无（staging.test.js:90 只测 `removeStagedEntity` 函数本身，不测 handler 接线与 save 流程）。
- 修复：
  1. `health:delete` 只删对应 (namespace, entityId) 的暂存 health 草稿（需新增 `Staging.removeStagedHealthConfig(packName, ns, id)`，staging.js 目前没有该函数）；
  2. **联动注意**：`entity:delete`（main.js:1252-1256）目前只调 `EntitySchema.deleteEntity`，**清理暂存草稿完全依赖上述副作用**（删除"仅暂存"的新实体之所以能成功就是靠它）。修 S1 时必须同时给 `entity:delete` 显式补上 `Staging.removeStagedEntity`，否则会产生"新实体删不掉草稿"的回归。

### S2. 任意文件写入 —— `health.set`（AI API/CLI）ns/id 零校验
**严重度：严重（本机任意 .json 文件写入原语）**
- 位置：`src/core/healthConfig.js:103-137`（`healthConfigFilePath`/`saveHealthConfig`）+ `src/core/editorApi.js:154-158`（`'health.set'`）+ `editorApi.js:29-36`（`resolvePackDir`）
- 机制：`saveHealthConfig` 只检查 `if (!namespace)`（真值判断），`namespace`/`entityId` 直接拼入 `path.join(packDir, 'assets', namespace, 'health_config', entityId + '.json')`，随后 `mkdirSync(recursive: true)` + `writeFileSync`。`ns = '../../../../..'` 即可写出 staging/包目录边界之外。`'health.set'` 命令对 `args.ns`/`args.id` 零校验直接透传；`resolvePackDir` 也不校验 `args.pack` 含分隔符（`pack='..'` 解析到 `contentpacks-source` 上级）。
- 触发：AI API HTTP 端点 **127.0.0.1:48900（无鉴权**，main.js:1836-1864，端口 48900-48909 回退）——本机任意进程发送 `{"cmd":"health.set","args":{"pack":"x","ns":"../../..","id":"y","config":{...}}}` 即可在磁盘任意位置写入一个内容可控的 JSON 文件（如覆盖项目内 `package.json` 等）；`editor-cli.js` 走同一 `invoke` 路径。
- 同源读原语：`'health.get'`（editorApi.js:149-152）→ `readHealthConfig` 同一路径 → **任意 .json 读取**（内容经 HTTP 返回）。
- 修复：ns/id 统一过 `^[a-z0-9_.-]+$` 字符集 + join 后 `startsWith(base + sep)` 边界校验（`editorApi.safeJoin` 已有现成模式，editorApi.js:39-45）；`resolvePackDir` 拒绝含 `\\ /` 的包名。

### S3. 任意文件读 —— mcAssets 资源链路无 `..` 防护（已复现）
**严重度：严重（恶意内容包 → 本地文件内容进渲染端）**
- 位置：`src/core/mcAssets.js`
  - `blockFaces`（L120 起）：`name = id.split(':').pop()` 直接拼入 blockstate 路径，**无** `blockState`（L300）/`L429` 那种 `name.includes('/')` 拦截（L305）；
  - `texturePath`（L84）、`findAsset`（L370）、`textureDataUrl`（L636）：对 `..` 段零防护。
- 已复现：`blockFaces(root, 'x:../../evil/blockstates/door')` → 读越界 blockstate JSON → 其 `variant.model` 指向越界 model JSON → `textures` 指向任意 `.png` → **PNG 字节以 dataURL 返回给渲染端**（PoC MATCH=true）。
- 可达性（均为"数据驱动、自动触发"）：
  1. **圈地导入的恶意 .nbt**：palette `Name` 字段 → 蓝图片段 `fillBlocks/boxFills` 的 block 串 → 片段 3D 编辑器槽位图标 `ensureSlotIcon` → `window.api.mcBlockFaces(blockId)`（pieceEditor.js:863-890）；
  2. **3D 预览贴图烘焙**：`structurePreview.js:190-192`（`mcBlockFaces`）、`292-294`（`mcBlockAppearance`），blockId 同样来自蓝图体素数据；
  3. 恶意包的 blockstate/model JSON 自身构造变体链（合法 block id 触发，纹理路径越界）。
- 修复：`findAsset`/`texturePath`/`blockFaces` 统一加 resolveRepoFile 式校验（`main.js:586-597` 已有实现：`..`/`.`/空段拒绝 + `startsWith` 前缀 + 扩展名白名单），与 `modelEditor:*` 一族对齐。

### S4. 任意文件读（无扩展名/大小过滤）—— `model:readTexture`
**严重度：严重**
- 位置：`main.js:1518-1553`
- 机制：`textureId = "ns:rest"` → `rel = path.join('assets', ns, rest)` 无越界校验、无扩展名过滤、`fs.readFileSync` 无大小上限，读出的任意文件 base64 进渲染端。
- 可达性：`entityList.js:33` `api.readTexture(entity.texture)` —— `entity.texture` 来自实体 JSON；实体校验 `model.id` 的正则 `^[a-z0-9_.-]+:[a-z0-9_./-]+$`（entitySchema.js:86）**允许路径段中的 `/` 与 `.`**，`texture` 字段本身连字符集校验都没有。`model.type='item'` 的实体在列表中自动触发缩略图加载（entityList.js:30-36）。
- 后果：恶意内容包 → 用户打开实体列表 → 主进程读取任意本地文件（指向大文件还可 OOM/冻结主进程）→ 内容进渲染端。
- 修复：同 S3 的路径校验 + 扩展名白名单（.png）+ 大小上限。

### S5. 任意 .json 读 —— `model:resolveFromId`
**严重度：高**
- 位置：`main.js:1484-1512`
- 机制：`modelType`（= `entity.model.type`，**无白名单**）与 `name`（model.id 冒号后段，正则允许 `/`）直接 `path.join(sourceDir, entry.name, 'spark_models', type, ns, name + '.json')`。已验证 `path.join(..., 'spark_models', '../../../..', ns, name+'.json')` 逃逸出包目录；候选文件存在即被解析为 spark 模型返回渲染端。
- 修复：`type` 白名单 `entity|item`；`name` 拒绝 `..` 段 + 前缀校验。

### S6. 任意 .json 读 + 路径存在性预言机 —— 契约资产盘点
**严重度：高（恶意包 → 读任意 .json 文件内容片段）**
- 位置：`src/core/editorContract.js:192-245`（`listContractAssets`）、`270`（`modelBoneNames` 读文件）、`169-183`（`readAnimFile` 读文件）
- 机制：`set.model.path`、`set.model.texture`、`set.animations.dir` 直接 `path.resolve(packDir, …)`，**无** `loadContract`（L131-135）对 `Module-Editor-Schema` 已有的边界检查。`animations.dir = '../../../任意目录/'` 时，对契约声明的每个 required 动画名执行 `fs.existsSync` + `readAnimFile`（读文件、解析、把动画名/骨骼名/时长回显给渲染端）；`model.path` 越界时 `modelBoneNames` 读任意 JSON 并返回骨骼名。
- 触发：用户打开带恶意契约（MANIFEST `Module-Editor-Schema` + 契约 JSON 均由包作者控制）的内容包 → 中栏契约列表/右栏详情 → 自动触发。
- 修复：`listContractAssets` 内对三个路径复用 loadContract 的边界校验；越界路径按"不存在"处理。

### S7. 命令注入面 —— `pack:build` Windows `shell:true`（机制已实证）
**严重度：高（恶意包目录名 + 用户点打包 → 任意命令执行）**
- 位置：`main.js:1710-1723`（`runCommand`，`shell: process.platform === 'win32'`）+ `main.js:1613-1666`（`pack:build` 组装 `:packs:<path.basename(packDir)>:contentpackJar`）
- 机制：Windows 下 `spawn(cmd, [task], {shell:true})` 经 `cmd.exe` 解析，参数中的 `& | ^ < >` 均可注入。已实证：`spawn('echo', ['safe & echo INJECTED_OK'], {shell:true})` 在 Windows 下执行了注入命令（Node 同时报 DEP0190 弃用警告）。
- 可达性：`contentpacks-source/` 下存在含 shell 元字符的包目录名（从外部 zip/仓库拷入的恶意目录名，如 `x & calc`）+ 用户在任意实体/结构/契约页签点"打包" → 以当前用户执行任意命令。`packDir` 来自渲染端且 `dirName` 无字符集校验。
- 对照：AI API 的 `build` 命令（editorApi.js:205-229）用参数数组直调 gradlew、**无 shell**，不受影响 —— 注入面仅限 UI `pack:build` 路径。
- 修复：去掉 `shell:true`（直接 spawn `gradlew.bat`/`build-pack.ps1` 无需 shell），并对 dirName 做 `MODULE_NAME_RE` 级字符集校验。

---

## 中危（功能错误 / 一致性 / 可触发的挂起）

### M1. `validateBlueprint` size 长度校验死代码 + size 无上限 → 主进程/渲染端挂起
**严重度：中（用户可自触发；恶意蓝图可放大为主进程级挂起）**
- 位置：`src/core/structureSchema.js:165`（`if (!size.length === 3 || ...)` —— `(!size.length) === 3` 恒 false）；`vanillaExport.js:57-69`（`expandFills` 三重循环无上界）；`voxel.js:43-51`（`expandPiece` 同）
- 机制：
  1. `size: [5]`（长度≠3）通过校验；此时 boxFills 边界检查 `bf.to[d] >= size[d]`（structureSchema.js:175）对 `d=1,2` 变成 `n >= undefined` 恒 false —— **y/z 方向坐标完全不受限**；
  2. size 本身**没有上限**（只要正整数）：UI 尺寸输入框（structureEditor.js:227-229）无 `max` 属性，用户输入 100000 → `expandPiece/expandFills` 迭代 1e15 次 → **渲染端冻结**（3D 页签，250ms 防抖后触发）/ **主进程冻结**（"导出原版数据"，整个应用无响应）；
  3. `buildTemplateNbt` 对 `size=[5]` 执行 `NBT.int(undefined)` → NaN → 写出 `size=[5,0,0]` 的**损坏模板 NBT**（vanillaExport.js:159）。
- 修复：`size.length !== 3`；给 size 加上限（如 ≤512，vanilla 模板远小于此）；`expandFills/expandPiece` 内按 size 钳制循环边界作纵深防御。

### M2. `mcAssets.readZipEntry` 中央目录步进错误 → mod jar 查找链整体失效（已复现）
**严重度：中（P13/P14 宣称的 mod-jar data 文件查找对真实 mod 基本无效）**
- 位置：`src/core/mcAssets.js:321-354`
- 机制：条目步进 `off += 46 + nlen + elen + clen`，其中 `clen` 读的是 `off+20` 的 **uint32 压缩大小**，正确应加 `off+32` 的 **uint16 注释长度**；另 `start + csize > buf.length` 边界检查与 `slice(start, start+csize)` 误用未压缩大小（deflate 条目靠 inflate 忽略尾字节侥幸正确，stored 条目会错）。
- 已复现：两条目 zip 查第二个条目恒返回 `null` —— **每个 jar 只有第一个条目可命中**。mod 的 blockstate 条目几乎从不排在第一位 → `blockStateAny/blockAppearance` 第 4 级（mod jar）静默失效。
- 测试覆盖：零（`grep readZipEntry test/` 无命中）。
- 修复：`off += 46 + nlen + elen + readUInt16LE(off+32)`；切片/边界改用压缩大小。

### M3. W4.2 状态映射直写把"暂存草稿"整体提升到正式包（数据一致性）
**严重度：中**
- 位置：`main.js:532-545`（`modelEditor:saveEntityDirect`）+ `main.js` entity:read（暂存优先）+ `model-editor/app.js:1035-1120`（`applyEntityContext`/`saveStateMap`）
- 机制：`readEntity` 暂存优先返回草稿（带 `_staged`）；模型编辑器 `saveStateMap` 把**整个实体对象**（不只是 animations 字段）经 `saveEntityDirect` 直写正式包 → 主窗口的未导出草稿被**静默导出**；且主窗口内存里的 entity 对象未同步，用户随后在主窗口"保存→导出"会用**旧草稿覆盖**刚直写的正式文件 → 状态映射改动回退。
- 修复：`saveStateDirect` 只做字段级合并（读正式包 → 只改 `animations` → 写回）；或在 `ent._staged` 为真时明确提示"将连同草稿一起写入正式包"。

### M4. `before-quit` 取消草稿提示后 AI API 永久停止
**严重度：中**
- 位置：`main.js:1967-2000`（`stopEditorApi()` 在草稿确认弹窗**之前**无条件执行，main.js:1968）
- 触发：有暂存草稿时点退出 → 弹窗选"取消"留在应用 → apiServer 已关、api.json 已删，而 `startEditorApi` 只在启动/项目根就绪时调用（main.js:138）→ 本次会话 AI API/CLI 永久失联（状态栏/日志无提示）。
- 修复：`stopEditorApi` 移到确认退出分支；或取消后重新 `startEditorApi()`。

### M5. `renderEntryClass` 生成 Java 不做字符串转义
**严重度：中**
- 位置：`src/core/packScaffold.js:154-258`
- 机制：`displayName`（用户输入，无字符集校验）与 `Module-Version`（无字符集校验）直接内插进 `return "…";` 字面量。displayName 含 `"`、`\`、换行 → `pack:create`/`ensureScaffold` 落盘的入口类**编译失败**（打包时才暴露）。
- 修复：Java 字符串转义（`\"` `\\` `\n` 等）或对两字段做字符集校验。

### M6. `structureGrowth.addPlacement` 碰撞检查未按旋转交换 w/d
**严重度：中**
- 位置：`src/core/structureGrowth.js:143-159`
- 机制：90°/270° 旋转后片段足迹是 d×w，重叠检查仍按 w×d 扫 → 非方阵片段旋转放置时**漏检重叠**（生长过程基于错误碰撞继续扩张；最终诊断的 voxel 级检测能抓到，但预览与生长决策已失真）。
- 修复：按 `pl.rotation` 对 footprint 的 w/d 交换后再扫。

### M7. 实体删除的 namespace 猜测不一致 → 孤儿 health 配置
**严重度：低-中**
- 位置：`src/views/entityList.js:96`（`ns = ent.namespace || currentPackDir.split(/[\\/]/).pop()`）vs `entityEditor.js:173-176`（`packNamespace` 做 `-`→`_` 转换）
- 机制：包目录名含 `-`（如 `my-pack`）且实体未声明 namespace 时，保存用 `my_pack`、删除用 `my-pack` → 删除实体后正式 `assets/my_pack/health_config/<id>.json` **永远残留**（孤儿文件，游戏侧仍加载）。
- 修复：删除路径复用 `packNamespace` 转换逻辑；更彻底的做法是删除实体时遍历包内全部 `health_config` 目录按 id 匹配删除。

### M8. legacy 圈地导入 O(blocks×jigsaws) 线性查找
**严重度：低-中（大模板卡死分钟级）**
- 位置：`src/core/regionImport.js:161`（每个 legacy jigsaw 对 `blocksRaw` 线性 `find`）
- 实测：20 万 blocks × 200 jigsaws = 269ms；外推 128³(209 万) × 2000 jigsaws ≈ **28s** 主线程卡死。
- 修复：pos→state 哈希表（`Map` 以 "x,y,z" 为键）。

---

## 低危 / 加固项

| # | 位置 | 问题 |
|---|------|------|
| L1 | `nbt.js` `decode`（`zlib.gunzipSync` 无上限） | gzip 炸弹：实测 256KB 零数据 → 256MB（~1029:1）。恶意 .nbt（圈地导入对话框）可 OOM 主进程。NBT list count 炸弹已排除（Buffer 读越界即抛 ERR_OUT_OF_RANGE）。修复：解压上限/流式解压。 |
| L2 | `structurePreview.js:1520` | 500ms 看门狗 `setInterval` 与 L1527 `visibilitychange` 监听器**永不清理**（`dispose()` L1551-1569 未处理）→ 每个预览实例泄漏一个 interval（当前每窗口 1 个，测试环境重复创建会累积）。 |
| L3 | `model-editor/index.html`、`src/piece-editor.html` | 两个子窗口**无 CSP**（仅主窗口 `src/index.html` 有）；piece-editor 也未挂 `errorCapture.js`。配合 L4 的注入点，缺少纵深防御。 |
| L4 | `entityEditor.js:502`、`contractView.js:121` | `a.duration`（来自动画 JSON 的 `animation_length`，可为任意字符串）**未转义**拼入 innerHTML（`esc()` 在同函数其他字段都在用，唯独漏它）。CSP 拦脚本，但恶意包可构造 DOM 注入/布局破坏。 |
| L5 | `main.js` `pack:*/entity:*/structure:*/health:*/staging:*` 一族 | `packDir` 仅校验存在性、不校验位于 `PACKS_SOURCE_DIR` 内（对比 `modelEditor:*` 一族有严格 `resolveRepoFile`，main.js:586-597）。`structure:read/delete` 的 `relFile` 无 `..` 防护（structureSchema.js:329/340 直接 `path.join`）；`staging` 的 `packName` 含 `..` 时 `sanitize`（staging.js:28-30 只替换 `[\\/:*?"<>|]`）不中和。当前渲染端受 contextIsolation 保护，风险低，但同一进程两套安全标准不一致。 |
| L6 | `staging.js:66-72` + `structureSchema.js:129` | 结构 id 正则 `^[a-z0-9_.-]+$` **允许 `..`**；`stageStructure` 用 `sanitize(structureId)` 拼目录（不中和点）→ structureId=`..` 时草稿写到 `staging/<包>/blueprint.json`（`structures/` 的上级），而 `listStaged`（只扫 `structures/*` 子目录）**看不到它** → 草稿静默丢失（导出/列表均不可见，文件留在盘上）。 |
| L7 | `modelParser.js:111-126` | `parseModelFromJar` 用 `jar xf <jar> <modelPathInJar>` 解到 tmpDir；`listModelsInJar` 只过滤 `startsWith('spark_models/')` → 恶意 jar 条目 `spark_models/../../evil.json` 可过过滤，是否落盘取决于 JDK jar 工具对 `..` 的行为（版本相关）。修复：basename 校验 + 解后校验落点。 |
| L8 | `manifest.js` / `jarReader.js` `parseManifest` | 不处理 72 字节续行：长 MANIFEST 属性值（>70 字符）会被截断/误拆键。 |
| L9 | `pieceEditor.js:171-174`（`inferTag`） | 整数值超 int32（如 3000000000）推断为 NBT int 后 `writeInt32LE` 静默截断 → 写出的模板 NBT 数值错误（无告警）。 |
| L10 | `main.js:864-872`（`modelEditor:saveModelAs` packName 分支） | 一键落盘硬编码 `spark_models/entity/`：item 类型模型会被写进 entity 目录，与 `resolveFromId(type='item')` 的查找路径（`spark_models/item/…`）不一致 → 物品模型游戏内丢失。 |
| L11 | `editorApi.js`（AI API 127.0.0.1:48900，无鉴权） | 本机任意进程可执行全部写命令（entity.set/health.set/build）。单机工具可接受，但叠加 S2 后成为任意文件写入口；建议至少加 token（写入 userData，CLI 从文件读）。 |
| L12 | `main.js` `relaunchAppProcess` | 自动重启依赖 stub 文件名精确等于 `app.getName() + ' ' + app.getVersion() + '.exe'`：用户重命名 portable exe 后静默失效（回退 `app.relaunch` 在 portable 下有单实例锁竞态，代码注释自述）。 |
| L13 | `logger.js` | 轮转只保留一份 `*.old.log`，长期运行旧日志被覆盖（Windows rename 覆盖行为已实测可用，非 bug）。 |
| L14 | `entity:delete`（main.js:1252-1256） | 不触碰暂存区 —— 当前靠 S1 的副作用"凑巧"能清理草稿（删"仅暂存"新实体的唯一途径）。**修 S1 时必须联动**（见 S1 修复说明），否则产生回归。 |
| L15 | `main.js` `structure:read` | id 从 relFile 父目录名推导：手工嵌套的 `structures/a/b/blueprint.json` 会与暂存键（bp.structureId）错位 → 显示正式版而非草稿。 |

---

## 已验证排除的疑点（避免误报）

- **NBT list count 炸弹**：Buffer `readUInt8` 等越界读直接抛 `ERR_OUT_OF_RANGE`，list 循环无法跑超剩余字节（实测 13 字节 buffer + count=1e8 → 立即抛错）。
- **`modelEditor:*` 仓库 API 族**（readModel/readTexture/listModels/listTextures/writeTexture）：`resolveRepoFile` 严格校验（`..`/绝对路径/前缀/扩展名），一致且正确。
- **`i18n.tf`**：用 `split/join` 非正则替换，无 `$` 注入；参数值只流向 `textContent`/`confirm`。
- **`askModal.js`**：全 `textContent` 构建，无注入面。
- **`pieceEditor.js` / `structureEditor.js` / `contractView.js` 的 innerHTML**：除 L4 的 `a.duration` 外全部字段经 `esc()`。
- **方块搜索自由文本**：搜索框输入不直接进槽位（Enter 只取列表首项，pieceEditor.js:730-740），槽位 block 串仅来自主进程扫描结果/蓝图数据。
- **editorApi `build` 命令**：参数数组直调 gradlew、无 shell，无注入面（与 S7 的 UI 路径不同）。
- **`vanillaExport.exportToPack` 文件名注入**：`piece.name`/`pool.name`/`structureId`/`namespace` 均被 `validateBlueprint` 字符集校验（`^[a-z0-9_.-]+$`，无分隔符）挡住，无法拼出越界路径 —— 但 size 维度问题见 M1。
- **logger 轮转**：Windows `fs.renameSync` 覆盖已存在目标实测可用。

## 修复优先级建议

1. **S1**（最常见保存路径丢数据，用户已可能多次踩到且无感知）——连同 L14 的 `entity:delete` 联动一起修；
2. **S2/S3/S4/S5/S6**（统一引入 resolveRepoFile 式路径校验，一处工具函数覆盖全部资源链路）；
3. **S7**（去掉 `shell:true`）；
4. **M1**（`size.length !== 3` + 上限）——顺手消除主进程挂起面；
5. **M2**（zip 步进修复 + 补测试，目前零覆盖）；
6. 其余按表推进。
