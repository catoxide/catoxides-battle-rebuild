# ContentPack Editor 0.2.2 发布说明

> 本轮主题：**物品/方块批量注册**（与基座 0.2.0-alpha 的 `BatchContentRegistrar` 对齐）
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

- [x] `npm test` 全绿（含 32 项 itemBlock 契约测试 + tapeDoc 21 项）
- [x] 主 mod `gradlew compileJava` BUILD SUCCESSFUL（BatchContentRegistrar API）
- [x] 样例包 `contentpacks-source/item-block-test-pack` 预检 ok + `contentpackJar` 构建成功
- [ ] 游戏内验收：`contentpacks/item-block-test-pack.jar` 进游戏，创造栏「物品方块测试包」
      页签 4 方块渲染/形态切换 + 日志 `Batch registration complete (pack=item_block_test_pack)`
- [ ] 状态机图拖动：新建 tape → 加 2 个状态连一条线 → 拖节点，连线应实时跟随

## 5. 拿到 0.2.2 后怎么验证

1. 双击 exe → 顶栏版本号 `0.2.2`（🩺 诊断 里也带版本号）
2. 选中任意包 → 中栏下拉切到 **方块** → 点 **+ 新建** → 填 id（如 `demo_wall`）
   → 形态与参数 页签加一个 `facing`（从目录）→ 预览页应看到 12 行 blockstate JSON
   → 保存 → 导出 → 包目录出现 `blocks/demo_wall.json` + `assets/<ns>/blockstate/demo_wall.json`
3. 打开任意 tape 文件 → 拖状态节点 → 连线跟随移动，松手后不再「错位」
