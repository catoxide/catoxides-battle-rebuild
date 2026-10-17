# ContentPack 编辑器 · 整体审阅（0.2.0 / 2026-10-14 11:11 构建）

> **2026-10-18 第二轮更新**：§2 里的 P0 已全部落进源码；「example-structure 包唯一建筑打不开编辑界面」的根因已定位并修复；新增「一键导入原版 / 一键导出原版（整包）」并做了 1.21.1 往返一致性验证（1046/1046 片段全等）。详见 **§6**。
> 注意：本轮 CDP 验证时真的点了一次「导出原版（全部）」，`contentpacks-source/structure-example-pack/data/structure_example/**` 已被新导出器重写（语义等价，差异见 §6.6）。

审阅时间：2026-10-18
审阅对象：
- 代码库 `tools/contentpack-editor`（HEAD `51c6230`）
- 产物 `dist/ContentPack Editor 0.2.0.exe`（2026-10-14 11:11:35）
- 测试报告 `C:\Users\catoxide\Downloads\feedback1017.txt`（w胡德w ↔ 千代田ゆきは，10-17 17:30–17:53）
- 原版参照数据 `D:\Software\MC develop pack\数据集\1.21.1\client`（Mojang 1.21.1 client 数据，1180 个结构模板 + 全部 worldgen JSON）

---

## 0. 先说结论（TL;DR）

| # | 问题 | 级别 | 状态 |
|---|---|---|---|
| 1 | 「+ 新建点了没反应」= 未解析项目根时 `init()` 提前 return，整个界面（含两个「+新建」）根本没绑定事件 | **P0** | 已用产物 exe + CDP 复现 |
| 2 | 导出的原版 worldgen JSON 不符合 1.21.1 codec（缺 `size/start_height/use_expansion_hack`、`spawn_overrides` 形状错误、`processors: []` 非法、`fallback` 用了元素 id、默认 `biomes` 是「数组里放 #tag」）→ 游戏加载注册表失败，**世界打不开** | **P0** | 已对照原版数据 + 重跑导出器验证 |
| 3 | NBT 解码器缺 `TAG_Byte_Array/Int_Array/Long_Array`（7/11/12），且不支持 `palettes`（复数）→ 原版 1180 个模板里 **60 个完全无法导入、22 个导入失败** | **P0** | 已批量实测 |
| 4 | 结构模板 `DataVersion` 写 3465（= 1.20.1），1.21.1 是 **3955** | P1 | 原版 1118 个模板均为 3955；仓库示例包也是 3465 |
| 5 | 模板往返丢 `entities`（村民/掠夺者笼子/物品展示框等实体全部丢失） | P1 | 代码路径确认 |
| 6 | 无法把「已有的原版数据包 / 模组 / zip」当项目打开；只能单个 `.nbt` 圈地读取 + 自己的一套 `structures/<id>/blueprint.json` | P0（功能缺口，正对胡德原话） | 代码路径确认 |
| 7 | 资源包/数据包兼容只覆盖「目录」，不读 zip、不解析 `pack.mcmeta` | P1 | `mcAssets.findAsset` 确认 |
| 8 | 「解体」（窗口透明/内容消失）本机硬件渲染无法复现；现有自愈只监听 renderer 崩溃，不监听 GPU 进程崩溃 | P1（需真机 A/B） | 现状代码确认 + 本机截图正常 |
| 9 | 构建无版本戳，反馈无法锚定到构建 | P2 | 产物 exe 只是 0.2.0 |

关于「是不是有一部分功能被拆分进 melee 了」：**没有**。见 §1.0。

最关键的一条：**当前导出器会把仓库里已经手工修好、可以正常进游戏的 `structure-example-pack` 数据覆盖成"世界打不开"的坏数据**——只要在编辑器里点一次「导出原版数据」。审阅过程中我**没有**写盘（只跑了纯函数 `exportBlueprint()` 在内存里比对）。

---

## 1. 测试报告逐条归因

### 1.0 先回答：melee 拆分？

**结论：没有任何编辑器功能被拆分进 melee / 任何内容包。** 证据：

- 内容包对编辑器只提供**数据**，不提供可执行 UI：唯一被读取的包声明是 `MANIFEST.MF` 的 `Module-Editor-Schema`（`src/core/editorApi.js:94`、`editorContract.js:148`、`main.js:856`），只用于「动画契约」页签的只读盘点/校验。
- `melee-combat-pack` 提供的是：`editor/animation-contract.json`（动画契约数据）、`spark_models/`、`spark_animations/`、`assets/`（贴图）。这些是**被编辑的内容**，不是编辑器功能。
- 该契约文件是 `622d614`（2026-10-14 21:05）才加入 melee 包的，**晚于**胡德测试的 11:11 构建；但它属于数据，和 exe 功能无关。
- `stateMachine.js` 里的 `melee / ranged / boss` 是**实体状态机模板枚举**（硬编码在编辑器里），不是 melee 包贡献的。
- `contentpacks-source/editor/index.html` 是另一个**独立的单文件网页版生成器**（不在 Electron 里加载，exe 不会执行包内代码）。
- Electron 主进程从不加载内容包里的 JS/HTML：`main.js` 里只有文件读写 IPC，无 `require`/`loadFile` 指向包目录。

所以胡德看到的「新建没反应」与 melee 无关，见 §1.1。

### 1.1 「新建点了没反应（both）」——P0，已复现

**根因（`src/app.js:244-247`）**：

```js
const pathsRes = await window.api.getPaths();
if (pathsRes.ok) {
    const p = pathsRes.data;
    if (p.needProjectRoot) {
        await setupProjectRoot();
        return;            // ← 这里直接结束了整个 init()
    }
    ...
}
// 后面这些永远不会执行：headerPaths / initPackPathTools /
// PackList.init(绑定 btnNewPack + npConfirm) / EntityList.init(绑定 btnNewEntity) /
// StructureList.init / contentTypeSelect change / 契约按钮 / loadPacks()
```

`setupProjectRoot()`（`app.js:379-398`）在用户**取消**或**选错目录**时只做两件事：
1. `window.showError('...')` → 写到 `#statusBar`（`src/index.html:222`，位于右栏编辑器的**底部**，很容易被当成滚动区域忽略）；
2. 把状态栏设成可点（点一下 `location.reload()` 重试）。

而主进程 `app:pickProjectRoot`（`main.js:1181-1194`）会校验所选目录必须含 `contentpacks-source`，不符合直接返回错误——**没有循环重选，也没有应用内重试按钮**。

**产物 exe 复现**（`D:\OpenCodeTemp\portable\ContentPack Editor 0.2.0.exe`，独立 `--user-data-dir` + `--remote-debugging-port=9335`，启动时用 Win32 `PostMessage(ESC)` 取消目录对话框）：

```
statusBar      = "未选择项目目录，编辑器不可用。点击此处重试 →" (class="status err")
#packList      = 空 (innerHTML "")
点击 #btnNewPack → #newPackForm 仍然是 hidden（事件根本没绑定）
#btnNewEntity  = disabled（HTML 静态 disabled，没有代码解除）
contentTypeSelect 触发 change → 无任何响应
window.api.getPaths()  → { needProjectRoot: true }
window.api.listPacks() → { ok:false, error:"尚未设置项目根目录" }
```

这与胡德 17:49:57 截图完全一致：**左边内容包列表空、中间实体列表空、两个「+新建」都没反应**。他那个 DevTools 里的 1 个 error 很可能就是某次 `window.api.*` 在无根状态下失败的调用。

**修复建议**：
1. `init()` 不要早退：把「需要项目根」当成一种**页面内状态**，其余 UI 照常初始化（列表空、按钮禁用但**有提示**）。
2. 做一个应用内项目根选择层（顶部横幅或模态）：显示「未找到项目目录」+「选择目录…」按钮 + 上次选择失败原因，可反复重试，而不是只写状态栏。
3. 未就绪时给所有需要根的操作统一拦截，返回可读错误（现在 `listPacks` 的 `尚未设置项目根目录` 只会显示在状态栏）。
4. 顺带：顶部路径条 `headerPaths` 在无根时是空的，用户没有任何"当前在哪个项目"的反馈。

### 1.2 「解体了——」窗口透明/内容消失——P1，硬件相关

- 截图（`b6c3e336...png`）里模型编辑器窗口只显示 3D 画面（3D 区域透出桌面），主窗口只剩标题栏、内容区整体消失——这是 **GPU 合成/DWM 表面失效**的典型形态（Chromium 的窗口内容层没画出来），不是 CSS/JS 崩溃（崩溃会白屏或 renderer 重载，不会透出桌面）。
- 本机（Ryzen 9 + RTX 4070 Ti SUPER，Win11 26200）用同一产物、**硬件渲染**启动主窗口 + 打开模型编辑器，渲染完全正常（见 `probe1-main.png`），无法本地复现 → 与胡德的显卡驱动/组合强相关。
- 本机 `%APPDATA%\ContentPack Editor\config.json` 里 `softwareRendering: true`（10-14 06:47 起）——说明本机历史上也触发过"10 分钟 2 次崩溃 → 自动降级"（`main.js:295-314`）。
- 现有防护缺口：
  - `guardRendererReload`（`main.js:316-331`）只处理 **renderer** 崩溃；
  - `maybeAutoFallbackToSoftware` 只在 renderer `crashed/oom` 时降级；
  - **没有** `app.on('child-process-gone')` / GPU 进程崩溃监听 —— 而"窗口透明但进程还活着"恰恰是 GPU 进程挂掉的形态，此时既不重载也不降级。
- 修复建议：监听 `app.on('child-process-gone')`（type=gpu）→ 记录 + 自动重载窗口；连续 GPU 崩溃 → 走现有软件渲染降级；给 `BrowserWindow` 设 `backgroundColor`（避免透明时直接看到桌面，至少能看出是"内容没画"而不是"窗口碎了"）；把 `chrome://gpu` 摘要写进日志，方便下次拿到胡德机器的一手数据。

### 1.3 「配置切换不自动保存」（用户自己的历史经验，另一个编辑器）

本仓库现状：只有**片段编辑器**有 dirty 跟踪 + 关窗三选一（`pieceEditor.js:40-48`、`main.js` 的 `pieceEditorWin.on('close')`）；TimeTape 有 `tape.dirty` 标记但只影响标题上的 ●；**切换内容类型 / 切换包 / 关主窗口**没有统一的未保存拦截。作为 P2 建议：抽一个统一 `DirtyRegistry`，在「切包 / 切内容类型 / 关窗 / 重启 / 切渲染模式」前统一询问。这条与胡德报的 bug 无直接关系（他说的是枪包编辑器）。

### 1.4 「项目目录是哪一层」——P1 引导缺口

- 胡德问的是目录层级，说明**首次运行引导完全不够**。当前只认「含 `contentpacks-source` 的目录」这一种根（`main.js:111`、`main.js:1188`）。
- 对内容创作者来说，更自然的根是：**一个模组工程目录**（含 `src/main/resources` + `contentpacks-source`）、**一个数据包/资源包目录**（含 `data/` 或 `assets/` + `pack.mcmeta`）、**一个模组 jar**。现在这些都不认。
- 打包产物是 portable 单文件 exe，用户随便放个位置就"找不到项目根"，然后进入 §1.1 的假死状态——两个问题叠加就是这个反馈的完整链条。
- 建议：根解析支持多形态 + 首次运行向导（选目录 → 即时校验 → 显示识别结果：「已识别 contentpacks-source: N 个包」）。

### 1.5 构建与反馈不可追溯——P2

- exe 只显示 `0.2.0`，没有构建时间/git hash/日志入口。建议标题栏或「关于」里显示 `0.2.0+<shorthash> 2026-10-14 11:11`，并写进 `editor.log` 头部。
- 另：本机当前同时跑着 portable stub + win-unpacked 多份实例（`D:\OpenCodeTemp\3KfTCTS...` 解压目录被复用），`main.js:2297` 的单实例锁只在同一 `userData` 下生效。旧窗口残留会被误点成"功能消失"（代码注释 W4.8 也记过这个坑）。建议启动时把当前实例的 pid/构建写进标题，并清理/校验 portable 解压目录。

---

## 2. 原版数据包兼容性（重点）

### 2.0 构建对照：反馈针对的构建 ≈ 当前源码

从 exe 里解出 `app.asar` 与 HEAD 逐文件哈希比对：

- **55 个文件字节完全相同**（`src/views/*`、`src/core/*`、`model-editor/*` 全部在内）
- 8 个文件不同：`main.js / preload.js / src/app.js / src/i18n.js / src/index.html / src/styles.css / src/views/contractView.js / package.json`
- 3 个新文件：`src/core/tapeEngine.js / tapeDoc.js / views/tapeEditor.js`

即：自 11:11 构建以来**只加了 TimeTape（W2/W3）**，其余代码没有任何修复。所以反馈里的问题在 HEAD 上全部原样存在（本报告直接按 HEAD 检查）。`npm test` 全绿（exit 0）——因为**测试断言的正是错误的原版形状**（见 §2.2）。

### 2.1 结构模板 NBT（`.nbt`）

**格式核对**（原版参照 `数据集/1.21.1/client/data/minecraft/structure/**`，1180 个）：

| 项 | 原版 1.21.1 | 编辑器 | 结论 |
|---|---|---|---|
| 根结构 | `size: List<Int>`, `palette`, `blocks:[{pos:[I], state:Int, nbt?}]`, `entities:[]`, `DataVersion` | 同（`vanillaExport.js:163-169`） | ✅ 形状正确 |
| `palette` 元素 | `{Name, Properties?: compound}`（大写 P、compound） | 同（`vanillaExport.js:143-152`） | ✅（注释里"对照 1180 个实测"是对的） |
| 字节序 | 原版 jar 内模板 = **BE**；游戏内存档 = **LE** | 导出 **LE** | ✅ 游戏 `NbtIo` 两种都认 |
| `DataVersion` | **3955**（1118 个模板全部 3955） | **3465**（`structureSchema.js:47`，=1.20.1） | ❌ P1 |
| 多调色板 `palettes`（复数，1.20.5+） | 原版数据里 **20 个**文件用它 | 只读 `palette`（`regionImport.js:64`） | ❌ 20 个模板无法导入 |
| 实体 `entities` | `[{pos:[D], blockPos:[I], nbt:{...}}]`，实体 NBT 里 `UUID: [I;...]` | 只统计数量（`regionImport.js:202`）；导出恒 `[]`（`vanillaExport.js:168`） | ❌ 往返丢实体 |
| NBT 数组类型 7/11/12 | 实体 UUID 等大量使用 | `nbt.js` 只实现 1/2/3/4/5/6/8/9/10（`nbt.js:26`），`readValueOf` 遇 11 直接抛 `未实现的解码类型`（`nbt.js:154-156`） | ❌ 60 个模板完全解不开 |
| 空气方块 | 原版 blocks 包含 air（如 base_plate 7680 条含 air） | 只写非 air | ⚠️ 语义等价（缺省即 air），但和原版产物不一致；若做 diff 会全量差异 |
| 模板路径 | 可嵌套 `data/<ns>/structure/<子目录>/<名>.nbt` | piece 名限制 `^[a-z0-9_.-]+$`（`structureSchema.js:167`）、导出扁平 `data/<ns>/structure/<piece>.nbt`（`vanillaExport.js:189`） | ⚠️ 无法复刻原版嵌套路径；多个结构共包时还会重名 |

**批量实测**（脚本：`D:\OpenCodeTemp\cdp\vanilla-batch.js`，直接跑编辑器自己的 `NBT.decodeAuto` + `RegionImport.parseRegionNbt`）：

```
total = 1180
ok                = 1098
decodeFail        = 60    ← "NBT: 未实现的解码类型 11"（实体 UUID 的 IntArray）
importFail        = 22    ← "缺 palette 且 blocks 非旧格式"（其中 20 个是 palettes 复数格式）
```

失败样本：
- 解码失败：`pillager_outpost/feature_cage1.nbt`、`bastion/mobs/{hoglin,melee_piglin,sword_piglin}.nbt`、`village/common/animals/*.nbt`、`ancient_city/structures/camp_*.nbt`、`trail_ruins/buildings/large_room_2.nbt` …（全部是含实体的模板）
- 导入失败：`igloo/bottom.nbt`、`shipwreck/*`（20 个 `palettes` 格式）、另 2 个空布局文件

结论：**圈地读取对原版模板的覆盖率约 93%（1098/1180），但对"带实体"和"多调色板"的模板 100% 失败**。而这两类恰恰是内容作者最常从存档里圈的东西。

### 2.2 worldgen JSON（导出）——P0，会让世界打不开

参考原版（`数据集/1.21.1/client`）：
- `data/minecraft/worldgen/structure/**` 34 个：`type/biomes/step/spawn_overrides` **34/34 全部存在**；其中 jigsaw 结构 **10/10** 都有 `start_pool/size/start_height/use_expansion_hack/max_distance_from_center`。
- `data/minecraft/worldgen/template_pool/**` 186 个：`fallback` **186/186 全部存在**，取值全是**池 id**（`minecraft:empty` 163 个、`minecraft:village/*/terminators` 等），**从没有** `minecraft:empty_pool_element`。
- 1177 个池元素里 `processors`：字符串 747、内联对象 360、**裸数组 0**。
- `data/minecraft/worldgen/structure_set/**` 20 个：`random_spread` 19 + `concentric_rings` 1。

重跑 `vanillaExport.exportBlueprint()`（示例包蓝图，内存执行、未写盘），逐字段对照：

| 字段 | 编辑器输出 | 原版要求 | 结论 |
|---|---|---|---|
| `type` | `minecraft:jigsaw` | 同 | ✅ |
| `biomes` | `["#minecraft:is_overworld"]`（数组里放 tag） | 字符串 `"#minecraft:is_overworld"` 或 **id 数组**（原版 34/34 全是字符串） | ❌ 默认蓝图直接产出非法值 |
| `spawn_overrides` | `{bonus_chests:0, spawns:[]}` | `{}` 或 `{<mob_category>:{bounding_box,spawns:[...]}}` | ❌ 形状非法（必填字段） |
| `step` | `surface_structures` | 同 | ✅ |
| `terrain_adaptation` | `beard_thin` 等（枚举正确） | `none/beard_thin/beard_box/bury/encapsulate` | ✅ |
| `start_pool` | `ns:start` | 同 | ✅ |
| `size` | **缺失** | 必填（jigsaw 10/10 都有） | ❌ |
| `start_height` | **缺失** | 必填（10/10 都有，`{"absolute":0}`） | ❌ |
| `use_expansion_hack` | **缺失** | 10/10 都有 | ❌ |
| `max_distance_from_center` | 原样输出（未 clamp 1..128） | `Codec.intRange(1,128)` | ⚠️ 越界即崩 |
| `project_to_surface` | 输出（自造字段） | 不存在；原版是 `project_start_to_heightmap` | ❌ 语义错位 |
| `max_surface_level` | 输出（自造字段） | 不存在 | ❌ |
| template_pool `processors` | `[]`（裸数组） | 处理器 id 字符串 或 `{"processors":[...]}` | ❌ 裸数组非法 |
| template_pool `fallback` | 默认 `minecraft:empty_pool_element`（`structureSchema.js:44`） | 必须是**池 id**，默认 `minecraft:empty` | ❌ 未绑定注册表值 → 世界加载失败 |
| structure_set `placement` | `random_spread` + `spacing/separation/salt/frequency/exclusion_zone{chunk_count,other_set}` | 同形状；另有可选的 `spread_type/frequency_reduction_method/locate_offset/preferred_biomes` | ⚠️ 基本合规；不支持 `concentric_rings` |

**最直接的证据**：仓库里 `contentpacks-source/structure-example-pack/data/structure_example/worldgen/**` 的 5 个文件是**正确的原版形状**（结构含 `size:7 / start_height{absolute:0} / use_expansion_hack:true / spawn_overrides:{}`；池含 `fallback:"minecraft:empty"`、`processors:{"processors":[]}`）——和导出器现在的输出**逐文件不同**。也就是说：

> 现在在编辑器里对示例包点「导出原版数据」，会把能用的数据覆盖成**注册表加载失败**的数据。

这与用户工作区里**未提交的 Java 修复**（`StructureJSONGenerator`/`StructureSetGenerator`/`TemplatePoolGenerator`/`TerrainAdaptation`/`StructureBlueprint.fallbackPool`）是同一个坑——Java 侧已经意识到并修了，**JS 侧（编辑器）还是旧的**。两边必须同步，否则编辑器写坏、mod 侧修好，作者仍然会被坑。

**测试在固化 bug**：`test/structure.test.js:276` 断言 `pool.fallback === 'minecraft:empty_pool_element'`；`:292` 断言 `spawn_overrides` 等于 `{bonus_chests:0, spawns:[]}`。修导出器时必须同步改测试，并**加一个"原版形状对照"用例**（见 §4）。

### 2.3 数据包入口/往返缺口

| 能力 | 现状 | 需要的 |
|---|---|---|
| 打开已有数据包/模组（文件夹） | ❌ 无入口（项目根必须是 contentpacks-source 工程） | 选目录 → 识别 `data/`+`assets/` → 列出结构/资源 |
| 打开 zip/jar 数据包、资源包 | ❌（只有 `run/mods/*.jar` 用于素材预览） | zip 中央目录读取已具备（`jarReader.js`/`mcAssets.readZipEntry`），缺"作为项目打开"的入口 |
| 扫描已有 `data/<ns>/structure/*.nbt` | ❌（只有单个文件对话框，`main.js:1940`） | 递归扫描 + 批量导入 |
| 反向解析 `worldgen/template_pool|structure|structure_set` → 蓝图 | ❌ | 至少只读预览 + 引用关系校验 |
| `pack.mcmeta` / `pack_format` | ❌ 完全不读 | 识别 1.21.1(48) / 其他版本并警告 |
| `processor_list` | ❌ 不支持（pool 里只能输出空处理器） | 引用已有 processor_list id + 简易编辑器 |
| `structure_set.placement` 类型 | 仅 `random_spread` | `concentric_rings` 等 |
| template_pool 元素类型 | 仅 `single_pool_element` | `legacy_single_pool_element`（原版用得最多：747/1177）、`feature_pool_element`、`list_pool_element`、`empty_pool_element` |
| 多命名空间 | 单 `bp.namespace` | 一包多 ns（原版/模组数据包常态） |
| 旧版路径转换 | 无 | 1.20.x `assets/<ns>/structures/`（复数）→ 1.21 `data/<ns>/structure/`（单数）迁移/警告 |

> 胡德那句「原版是这个结构 模组也是这个结构 … 单独改一种结构…太不方便维护」正是这一节：编辑器的 `structures/<id>/blueprint.json` 是**自己发明的源格式**，而游戏只认 `data/` 四件套。要"能编辑 mc 模组"，必须能**直接读写 `data/`**（包括反向解析），而不是只单向导出。

### 2.4 资源包兼容

- `mcAssets.findAsset`（`src/core/mcAssets.js:421-465`）查找顺序：vanilla 数据集 → 当前包 → `run/resourcepacks/*`（**只扫目录**）→ 每个存档 `saves/*/datapacks/*`（只扫目录）→ `run/data` → `run/mods/*.jar`（zip 条目）。
- 缺口：
  1. `.zip` 资源包/数据包完全不读（而原版最常见的分发形态就是 zip）；
  2. 不解析 `pack.mcmeta`（无法知道 `pack_format`，1.21.4+ 的 `items/`、`models/item` 变化会误判）；
  3. 不扫 `run/datapacks`（只扫世界内 datapacks）；
  4. zip 内再嵌套包（`packs/`）不支持；`jarReader` 只支持 store/deflate、无 zip64、上限 64MB（对资源包够用，但要在 UI 上报清楚）。
- 方块列表（`src/core/mcBlocks.js:25-45`）扫的是 `data/<ns>/blockstate/`——1.21.1 **没有**这个注册表目录（方块 blockstate 在 `assets/<ns>/blockstates/`，数据集里也没有 `data/minecraft/blockstate`）。所以模组方块大概率扫不到（现在只有 vanilla 数据集 + 搜索框手输）。建议改成扫 `assets/<ns>/blockstates/*.json`（目录和 jar 两条路径都有现成 zip 读取代码）。

---

## 3. 代码级问题清单（按文件）

| 文件:行 | 问题 | 影响 | 修法 |
|---|---|---|---|
| `src/app.js:244-247` | 无项目根时 `return`，跳过全部 init | 整个 UI 假死（P0） | 不早退；根引导做成应用内状态机 |
| `src/app.js:379-398` | 失败只写状态栏、仅靠点状态栏 reload | 用户找不到重试入口 | 应用内横幅/模态 + 重试按钮 |
| `src/app.js:300-301` | 契约按钮在 init 中绑定（同受影响） | 无根时按钮也不可用 | 与上同 |
| `main.js:1181-1194` | 选根只校验一次，无循环 | 选错即死路 | 返回结构化错误，渲染端可重试 |
| `src/core/vanillaExport.js:198-217` | pool 元素 `processors: []`、固定 `single_pool_element` | 原版 codec 拒绝 | `{"processors":[]}` 或处理器 id；补 4 种元素类型 |
| `src/core/vanillaExport.js:213` + `structureSchema.js:44` | `fallback` 默认 `minecraft:empty_pool_element` | 注册表未绑定 → 世界打不开 | 常量改 `minecraft:empty`，校验必须是池 id |
| `src/core/vanillaExport.js:220-234` | 结构 JSON 缺 `size/start_height/use_expansion_hack`、`spawn_overrides` 错、自造字段 | **世界打不开**（P0） | 按原版形状重写（参考 §2.2 表） |
| `src/core/vanillaExport.js:224` | `biomes` 直接输出数组 | 默认蓝图即非法 | 单 tag 输出字符串；数组时校验每项是 id |
| `structureSchema.js:47` | `DATA_VERSION_1_21_1 = 3465` | 模板被错误 DataFixer 处理 | 改 3955（+测试） |
| `src/core/nbt.js:26,131-157` | 缺 tag 7/11/12 | 60 个原版模板打不开、实体 NBT 无法往返 | 增加编解码 + 保类型表示（`{$t:11,v:[...]}`） |
| `src/core/nbt.js:292-316` | `plainToTagged` 无法表达数组 | `UUID` 等往返变 `List<Int>` | 解码带类型（`decodeTagged`），导入直接用 tagged |
| `src/core/regionImport.js:64,70,95` | 只认 `palette`（单数） | 20 个原版模板导入失败 | 支持 `palettes`（取第 1 个/提供选择） |
| `src/core/regionImport.js:202`、`vanillaExport.js:168` | entities 只计数/恒空 | 往返丢实体（P1） | 解析/写出 entities（pos/blockPos/nbt） |
| `vanillaExport.js:189`、`structureSchema.js:167` | piece 名不可含 `/`、导出扁平 | 无法复刻原版嵌套模板路径 | 允许子路径（校验 `..`/绝对路径） |
| `mcAssets.js:421-465` | 只扫目录、无 zip/pack.mcmeta、不扫 run/datapacks | 资源包/数据包兼容缺口 | 统一 ReadSource：目录 + zip(jar) + pack_format |
| `mcBlocks.js:25-45` | 扫 `data/<ns>/blockstate/`（不存在的注册表目录） | 模组方块列表可能永远为空 | 改扫 `assets/<ns>/blockstates/` |
| `main.js:316-331` | 只监听 renderer 崩溃 | GPU 挂 → 窗口透明且无人管 | `app.on('child-process-gone')` + backgroundColor |
| `main.js:346-357` | `BrowserWindow` 无 `backgroundColor` | 内容没画时直接透出桌面/白/黑 | 设深色底色 |
| `src/views/entityList.js:112-117`、`structureList.js` | `init` 里绑定按钮，受 init 早退影响 | 无根时点了没反应 | 见 §1.1 |
| `test/structure.test.js:276,292` | 断言错误的原版形状 | 测试全绿但导出坏数据 | 改期望 + 加原版形状对照用例 |

---

## 4. 建议的修复顺序（含验收方式）

### 第一批（P0，1–2 天量级）

1. **worldgen JSON 导出对齐原版 codec**
   - `vanillaExport.exportBlueprint` 重写 structure/pool/set 的形状；蓝图模型补 `structureSize`（原版 `size`，1..128）与 `start_height`。
   - `EMPTY_POOL_ELEMENT` → `minecraft:empty`；fallback 校验必须为池 id 或 `minecraft:empty`。
   - processors：空处理器输出 `{"processors":[]}`；保留扩展位支持处理器 id。
   - 更新 `test/structure.test.js`，并新增「原版形状对照」测试：
     - jigsaw 结构必须含 `type/biomes/step/spawn_overrides/start_pool/size/start_height/use_expansion_hack/max_distance_from_center`
     - `spawn_overrides` 必须是对象且值为 `{bounding_box,spawns[]}`
     - `fallback` 必须匹配 `^[a-z0-9_.-]+:[a-z0-9_./-]+$` 且不得以 `_pool_element` 结尾
     - pool 元素 `processors` 必须是 string 或 `{processors:[]}`
     - 模板 `DataVersion === 3955`
2. **无项目根不再假死**：init 不早退 + 应用内根选择横幅 + 失败可重试。
3. **NBT 7/11/12 + `palettes` + entities**：decodeTagged 保真往返；批量回归脚本（用原版数据集跑 1180 个模板，目标 0 失败；脚本模板见 §附录）。

### 第二批（P1）

4. DataVersion → 3955。
5. 模组/数据包「打开为项目」：目录或 zip → 扫描 `data/<ns>/structure/**/*.nbt` + `worldgen/**` + `assets/**`；至少只读展示 + 单结构导入。
6. GPU 崩溃监听 + 窗口底色 + `chrome://gpu` 入日志（给胡德机器 A/B 用）。
7. 资源包 zip/pack.mcmeta 支持；`mcBlocks` 扫 `assets/<ns>/blockstates`。

### 第三批（P2）

8. piece 子路径、多命名空间、`processor_list`、`concentric_rings`、`legacy_single_pool_element` 等元素类型。
9. 构建版本戳（hash+时间）写标题栏/日志；统一 dirty 与自动保存；portable 解压目录清理。

---

## 附录 A：本次审阅用到的可复现脚本

均放在 `D:\OpenCodeTemp\cdp\`（可搬进 `tools/contentpack-editor/test/browser/` 做长期回归）：

| 脚本 | 作用 |
|---|---|
| `cdp.mjs` | 无依赖 CDP 客户端（Node ≥22 全局 WebSocket）；`targets/eval/shot` 子命令 |
| `p1.mjs` | 在产物 exe 上跑「新建包 → 新建实体」正确路径（用 fixture 项目，不碰真实仓库） |
| `p3.mjs` | 复现「无项目根」假死状态（需先用 Win32 `PostMessage(ESC)` 取消目录框） |
| `vanilla-batch.js` | 原版 1180 个结构模板批量导入统计（解码/导入/DataVersion/布局） |
| `export-compare.js` | 重跑导出器 vs 仓库里已修好的示例包数据（逐文件对比） |
| `nbt-inspect.js` | 单个 `.nbt` 顶层结构/tag 形状打印 |

## 附录 B：关键原版参照文件

- `数据集/1.21.1/client/data/minecraft/structure/pillager_outpost/base_plate.nbt` —— 原版模板形状（`palette` 单数、blocks 含 air、DataVersion 3955）
- `数据集/1.21.1/client/data/minecraft/worldgen/structure/pillager_outpost.json` —— jigsaw 结构完整字段
- `数据集/1.21.1/client/data/minecraft/worldgen/template_pool/village/plains/town_centers.json` —— `fallback` 池 id、`processors` 字符串/对象
- `数据集/1.21.1/client/data/minecraft/worldgen/structure_set/pillager_outposts.json` —— `placement.exclusion_zone/frequency_reduction_method`
- 仓库内正确样本：`contentpacks-source/structure-example-pack/data/structure_example/worldgen/**`（当前导出器输出与它不一致）

---

# 6. 第二轮：结构编辑器打不开的根因 + 一键导入/导出原版（2026-10-18）

## 6.1 「example_structure 唯一建筑打不开编辑界面」——根因与修复

**现象**：中栏能列出 `example_structure`（3 片段 / 4 池），点进去编辑界面不出现，状态栏无提示。

**根因**（P0，功能整体不可用）：

- `src/core/structureSchema.js` 定义了 `resolveStructureFile()`，但 `module.exports` 里**漏掉了它**；
- `main.js` 的 `structure:read` / `structure:delete` 都调用 `StructureSchema.resolveStructureFile(...)` → 抛 `StructureSchema.resolveStructureFile is not a function`；
- IPC 返回 `{ok:false}`，渲染层只走 `showError`/静默分支，于是「点了没反应」。
- 这是**所有**结构蓝图共有的问题，不止示例包——10-14 的产物 exe 同样是坏的。

**修复**：

- `structureSchema.js`：导出 `resolveStructureFile`（并在注释里标明这是 0.2.0 的回归点）；
- `test/structure.test.js` 新增 O3 用例（含越界路径拒绝），防止再次漏导出。

**复验**（CDP 驱动源码版，`D:\OpenCodeTemp\cdp\p5.json`）：点击列表项后编辑器可见，`sId=example_structure`、`size=7`、3 张片段卡、4 张池卡、无异常、无报错状态栏。

## 6.2 同批的 1.21.1 现代化（导出/校验）

| 项 | 之前 | 现在 |
|---|---|---|
| `size` | 缺失或自造字段 | 必填 1–128（默认 7，可按原版文件覆盖） |
| `start_height` | 无 | `{absolute:n}` / `{above_bottom:n}` |
| `use_expansion_hack` | 无 | 布尔，默认 true |
| `project_start_to_heightmap` | 自造 `projectToSurface` | 原版枚举白名单（WORLD_SURFACE_WG 等） |
| `terrain_adaptation` | 空值也写 | `none` 视为默认、省略不写 |
| 模板 `DataVersion` | 3465（1.20.1） | 3955（1.21.1） |
| 空 `processors` | 写 `{"processors":[]}` | 省略（与原版一致） |
| 旧蓝图 | 报错/字段残留 | `migrateBlueprint()` 读入即迁移 |

## 6.3 一键导入原版

入口：中栏「内容类型 = 建筑结构」时出现的工具条

- `⬇ 导入原版`：选 `.nbt` 单模板，或原版数据包/模组 `.jar`·`.zip`；
- `🗂 导入文件夹`：选解包后的数据包目录（含 `data/<ns>/worldgen`）；
- IPC：`structure:importVanilla` / `structure:importVanillaDir`（另有 `structure:importVanillaPath` 供自动化直接传路径）。

行为要点：

1. 扫描 `data/<ns>/worldgen/structure/*.json`，**只导入 `type: minecraft:jigsaw`**；其余（mineshaft / none / ruined_portal…）计入「跳过」，不伪造蓝图。
2. 从 `start_pool` 递归展开模板池依赖（含 `pool_aliases` 透传、`dimension_padding`/`liquid_settings` 原样保留）。
3. 逐个模板解析 NBT：多调色板（`palettes`）、tagged 方块实体 NBT、jigsaw 方块、`entities`（实体当前丢弃并计数）。
4. 产物直接写成正式蓝图 `structures/<id>/blueprint.json`（不是草稿），并带 `importFrom` 回溯字段与 `importInfo`。
5. **命名空间默认取包目录名**（`structure-example-pack` → `structure_example_pack`），避免覆盖 `data/minecraft`。
6. 目标 `structureId` 已存在时自动 `_2` / `_3` 后缀，绝不覆盖已有蓝图。

原版 1.21.1 client 数据的实测（`p5.json`）：

- 命中 **10 个 jigsaw 结构** → 1046 片段 / 190 模板池 / 1563 个 jigsaw 块；
- 跳过 24 个非 jigsaw 类型；2 条警告；
- 无错误，全部通过 `validateBlueprint` 后落盘。

元素类型覆盖（此前只有 single/legacy_single）：

- `feature_pool_element`（村庄树木/花）：现在会写进蓝图并在导出时还原（`feature` 字段，UI 里类型选「特征」）；
- `list_pool_element`（掠夺者前哨的塔组合）：展开为普通元素，**权重按原版比例分摊**（分数 → 分母 LCM 放大成整数，再按 150 上限整体缩放）；
- `empty_pool_element`：跳过（等价于无元素）。

## 6.4 一键导出原版（整包）

- 工具条 `⬆ 导出原版（全部）` → `structure:exportVanillaAll`；
- 扫描包内**所有** `structures/*/blueprint.json`，逐个产出 4 类文件到包源码目录：
  `data/<ns>/structure/<piece>.nbt`、`worldgen/template_pool/<pool>.json`、`worldgen/structure/<id>.json`、`worldgen/structure_set/<set>.json`；
- 单个结构仍可用编辑页里的「导出原版数据」；
- 实测：示例包 1 结构 9 文件；临时包 10 结构 **1256 文件、0 错误**。

## 6.5 往返一致性（导入 → 导出 vs 原版文件）

用 `D:\OpenCodeTemp\cdp\roundtrip.js` 做语义对比（不是字节对比）：

| 对象 | 结果 |
|---|---|
| 模板片段 | **1046 / 1046 全等**：size、非空气方块状态、方块实体 tagged NBT（精确类型/内容）、jigsaw 位置+name+target+joint+orientation+final_state |
| 模板池 | 189 / 190 元素集合全等 |
| 结构定义 JSON | **10 / 10 关键字段全等**（step/size/start_height/use_expansion_hack/max_distance_from_center/terrain_adaptation/project_start_to_heightmap/spawn_overrides） |

两处「不等」都不是缺陷：

1. `minecraft:ancient_city/walls/no_corners` 池引用了 `minecraft:ancient_city/walls/intact_horizontal_wall_stairs_5`，而**原版数据里这个模板文件根本不存在**（空引用）——导入器给警告后跳过，属正确行为；
2. 29 条导出路径被 5 个村庄变体共享（`village/common/**` 的公共模板与池），后写覆盖先写——**同名同源、内容相同**，不丢信息。

> 提示：这也解释了为什么整包导出后磁盘上 nbt 文件数（954）少于片段总数（1046）：共享模板只落一份文件。

## 6.6 示例包数据被重写（语义等价）

点「导出原版（全部）」后 `structure-example-pack/data/structure_example/**` 的差异只有：

- `processors: {"processors": []}` 被省略（等价、更干净）；
- `structure_set.placement.frequency: 1` 显式写出（原版默认值）；
- JSON 键顺序与行尾（无尾换行）。

方块内容、jigsaw、结构字段与原文件一致（已用 `roundtrip.js` 对原版数据验证同类输出）。

## 6.7 本轮新增/修改的文件

- `src/core/structureSchema.js`：导出 `resolveStructureFile`；`size/start_height/use_expansion_hack/project_start_to_heightmap` 校验与迁移；DataVersion 3955；元素 `template|feature` 二选一校验。
- `src/core/vanillaExport.js`：`feature_pool_element` 导出；`terrain_adaptation: none` 省略；现代字段输出。
- `src/core/vanillaImport.js`：jigsaw/数据包导入；feature/list 元素处理与权重归一；`importFrom` 回溯。
- `src/core/structureGrowth.js`：生长/诊断跳过 feature 元素（不再误报「片段不存在」死端）。
- `main.js` / `preload.js`：4 个新 IPC（导入文件/导入文件夹/导入路径/整包导出）。
- `src/index.html`、`src/app.js`、`src/views/structureEditor.js`、`src/i18n.js`：工具条、报告面板、size/start_height/heightmap/expansionHack 表单项、池元素「类型：模板/特征」。
- `test/structure.test.js`：O1–O4 四个回归用例（feature 元素、二选一、resolveStructureFile、现代字段）。

测试现状：`npm test` **全部通过**（31 个测试文件；结构模块 37 项，含上述 4 项新增）。

## 6.8 仍然存在的限制（下一轮）

1. 导入丢弃模板里的 `entities`（村民/猫/马等已计数提示）；要 1:1 还原需在 blueprint 片段里增 `entities` 段并让导出写回。
2. 多调色板模板会合并调色板并给警告（原版 1180 里只有个别）。
3. `processor_list`（40 个原版文件）目前只透传引用 id，编辑器内不可视化/编辑。
4. 非 jigsaw 结构（mineshaft/none/ruined_portal/stronghold 等 24 个）蓝图无法表达，导入直接跳过。
5. `structure_set` 的 `placement` 支持 `random_spread`；`concentric_rings` 只透传不编辑。
6. 导入的模板命名是「路径扁平化」（`village.common.iron_golem`），文件系统层面可能有 29 条共享路径覆盖（内容相同，见 §6.5）。

