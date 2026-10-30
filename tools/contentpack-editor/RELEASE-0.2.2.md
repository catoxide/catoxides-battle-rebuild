# ContentPack Editor 0.2.2 发布说明

> 本轮主题：**物品/方块批量注册**（与基座 0.2.0-alpha 的 `BatchContentRegistrar` 对齐）
> + **任务定义编辑**（quests/，与基座 `registerQuestsFromJar` 对齐）
> + **状态机图拖动修复**（连线不跟随的回归）。

## 1. 物品/方块编辑（新内容类型）

中栏内容类型下拉新增 **物品 / 方块** 两个选项：

- **列表**：`items/<id>.json` / `blocks/<id>.json`（正式包 + 暂存草稿合并显示，草稿带角标）
- **编辑器四页签**：
  - 基本信息（物品：堆叠/稀有度/创造栏/防火；方块：材质物理/方块实体/掉落标签/创造栏/blockItem）
  - **形态与参数**（方块）：自定义参数（direction/axis/boolean/int/enum，从目录添加或自定义）
    + 状态组合计数（>4096 拦截）+ 完整方块形态（variants 组合表 / multipart 层表）+ 实时预览
  - 模型（cube_all/cube/generated/raw + 实时 JSON 预览）
  - 预览（全部导出文件 + 与基座注册器一致的校验结果）
- **保存** = 暂存草稿；**导出** = 并入正式包 + 写该条目原版文件；
  **导出原版（全部）** = 整包（模型/blockstate/lang 合并/loot/标签同包合并）
- **补齐骨架**：入口类自动生成 `context.registerItemsAndBlocksFromJar()` 调用（已有入口类缺该调用时自动重生成）
- **预检**：新增 `E-IB-*` / `W-IB-*`（JSON/文件名/契约校验 + 有 items/blocks 但入口类未注册的死数据拦截）

> ⚠️ 如果你之前装过 0.2.1 的 exe，看不到「物品/方块」选项是正常的 —— 本版本起才有。

## 1b. 任务编辑（新内容类型）

中栏内容类型下拉新增 **任务**：`quests/<id>.json`（一文件一任务，子任务嵌在 `children`）。

- **列表**：正式包 + 暂存草稿合并显示，行内显示「发放 N · 目标 N · 子任务 N」
- **编辑器三页签**：
  - 基本信息（id 保存后锁定 / title / description / 可选 location / priority / **发放节奏**：
    `repeatable` 可重复 + `cooldownTicks` 冷却）
  - 发放与目标（givers：`proximity_giver`；goals：`item_present` 只检测 / `item_submit` 完成扣除；
    自定义类型如 `kill`/`craft` 只读保留，包私有顶层字段如 `rewards` 保存时不丢）
  - 预览与校验（错误/警告 + 落盘 JSON 预览，键序 = Java 解析顺序）
- **保存** = 暂存草稿；**⬆ 写入正式包** = 整包草稿落盘并回收（与物品/方块同一个按钮位置）
- **🩹 补齐骨架**（任务工具条）：入口类**只插一行** `context.registerQuestsFromJar()`（外科式补丁，不重生成整类，
  老入口类里手写的工厂/命令/网络包原样保留）；补完顺手跑预检并列出剩余 `E-Q-*`
- **预检**：新增 `E-Q-NO-REGISTER`（有 quests 但入口类没注册）、`E-Q-JSON`、`E-Q-VALID`（镜像严格解析）、
  `E-Q-ID-DUP`（包内 id 重复 = 后者覆盖前者）、`E-Q-NESTED`（`quests/` 子目录里的 JSON 永不加载）、
  `W-Q-VALID`（包 Java 工厂提供的自定义类型）
- 基座同步：内置 `item_present` / `item_submit` 两个通用目标（`QuestBuiltinComponents`，
  纯 JSON 任务包不用写 Java），`item_submit` 只在任务完成时扣物品（`isComplete` 保持幂等）
- 基座同步（重复发放）：`repeatable` + `cooldownTicks` 走 `QuestGivePolicy` —— 一次性任务收到过永不再发；
  可重复任务在终态（完成/失败）且冷却结束后再发，**每次发放都是新实例 id（新 UUID）**，
  终态实例按每个玩家每定义保留最近 10 条剪枝（不会无限增长）；`startedAt`/`endedAt` 进存档，
  重启后冷却不会重置。样例 `item_present.json` 已开 `repeatable: true, cooldownTicks: 1200`

## 2. 状态机图（TimeTape）拖动修复

**症状**：拖动状态节点后，节点动了但**连线停留在旧位置**，要再点一下别处才刷新。

**根因**：`wireNode` 的 pointerup 处理里，拖动分支只调 `persistLayout()`（存布局）
**没有 `renderAll()`** —— 边层（SVG `<g>`）只在整页重绘时重建，所以连线永远停在拖前位置。

**修复**（`src/views/tapeEditor.js`）：
- 边绘制抽成 `drawEdges()`，`renderCanvas` 每次重建时注册为 `liveRedrawEdges` 钩子；
  **拖动过程中** pointermove 实时重绘边层 → 连线跟随节点移动
- pointerup（拖动结束）补 `renderAll()` 全量兜底，保证边/标签/选中态完全同步

## 3. 一并带上（0.2.1 之后已合入源码、随本 exe 首次打包）

- 结构编辑器 0.2.1 之后的各项修复（见 AUDIT/RELEASE 记录）
- `test/itemBlock.test.js`（32 项契约测试）正式加入 `npm test`
- 主 mod 侧契约文档：`design/ITEM_BLOCK_REGISTRATION.md`、`design/CONTENTPACK_FORMAT.md` v0.5

## 4. 验证清单（0.2.2）

- [x] `npm test` 全绿（含 32 项 itemBlock + 30 项 quest 契约 + 5 项 questView 视图测试 + tapeDoc 21 项）
- [x] 主 mod `gradlew compileJava` BUILD SUCCESSFUL（BatchContentRegistrar API）
- [x] 样例包 `contentpacks-source/item-block-test-pack` 预检 ok + `contentpackJar` 构建成功
- [x] 样例包 `contentpacks-source/quest-test-pack` 预检 ok + `contentpackJar` 构建成功；
      `scripts/validate-game.ps1 -Pack quest-test-pack` data + models 双 phase PASS，
      服务端日志 `Scanned pack jar quest-test-pack.jar for quests: registered=3 failed=0`，
      `Parsed quest '...:test_item_present': ... repeatable=true, cooldownTicks=1200`
- [x] `QuestGivePolicyTest`（无游戏依赖，`javac` 直接编跑）：11 项通过（一次性/可重复 × 无实例/ACTIVE/终态、冷却边界、缺时间戳、负冷却）
- [ ] 游戏内验收：`contentpacks/item-block-test-pack.jar` 进游戏，创造栏「物品方块测试包」
      页签 4 方块渲染/形态切换 + 日志 `Batch registration complete (pack=item_block_test_pack)`
- [ ] 游戏内验收（任务）：走进 `proximity_giver` 半径 → 日志出现 JSON 任务发放；
      背包凑齐 `item_present` 数量 → 任务完成；`item_submit` 完成时物品被扣除
- [ ] 状态机图拖动：新建 tape → 加 2 个状态连一条线 → 拖节点，连线应实时跟随

## 5. 拿到 0.2.2 后怎么验证

1. 双击 exe → 顶栏版本号 `0.2.2`（🩺 诊断 里也带版本号）
2. 选中任意包 → 中栏下拉切到 **方块** → 点 **+ 新建** → 填 id（如 `demo_wall`）
   → 形态与参数 页签加一个 `facing`（从目录）→ 预览页应看到 12 行 blockstate JSON
   → 保存 → 导出 → 包目录出现 `blocks/demo_wall.json` + `assets/<ns>/blockstate/demo_wall.json`
3. 中栏下拉切到 **任务** → **+ 新建** → 填 id（如 `demo_apple`）与 title
   → 发放与目标页签保留默认「进入区域发放」+ 目标改成**提交物品** `minecraft:apple` ×8
   → 预览页无错误 → 保存（徽标变「草稿」）→ **⬆ 写入正式包**
   → 点「预检」应无 `E-Q-*`；若入口类原先没有任务注册，「补齐骨架」会只插一行
4. 打开任意 tape 文件 → 拖状态节点 → 连线跟随移动，松手后不再「错位」
