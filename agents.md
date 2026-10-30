# AGENTS.md

给在本仓库工作的 AI 的常读约束。**本文件只放指针，不抄内容** —— 每条规则的正本在别处；要改规则就改正本，不要改这里。

---

## 1. 权威分工（谁说了算）

| 问题 | 权威 | 怎么读 |
|---|---|---|
| 需求 / 进度 / 计划 | **ShareTree 计划树**（外部服务） | `GET https://aitree.catoxide.cn/api/tree?outline`（LLM 优化版）。`GET /api/help` 无需鉴权，自描述全部端点 |
| 代码实际形状 | **仓库代码 + git 历史** | 读 `src/`、`contentpacks-source/`；`git log` / `git blame` |
| 设计理念 / 规则正本 | `design/*.md`（见 §3） | 读正本，不凭 ID 猜含义 |

三条硬纪律：

- **ShareTree 是 Yjs CRDT，实时同步给所有在线用户 ⇒ AI 永不写入**（不调 `POST /api/apply`）。只能产出 ops 草稿交人审、人工提交。
- **计划侧与实践侧不一致时，以代码为准出证据，不自行改权威文档。** 状态符号（✅/🔄/⬜）由用户判定。
- **token 不进任何文件**：仓库内、memory 内、commit message 内都不得出现 ShareTree token。

> ⚠️ `design/`、`docs/`、`TASKS.md`、`.scratch/` 全被 `.gitignore` 排除（`.gitignore:53`、`:88-90`、`:57`）。
> 即：规则正本与可跑断言**都没有版本历史**，只存在于这台机器。本文件是唯一 tracked 的规则相关文件。

---

## 2. 禁读 / 禁改

**禁读**（第三方解包与历史垃圾；上下文再大也别读，读了只会改错地方）：
`Deprecated&References/`、`tools/_spark_src/`、`tools/_spark_core/`、`tools/_spark_core_src/`、`tools/spark-core-fork/`、`1/`（含 `com/jme3/bullet`）、`logs/`、`tmp/`、`scratch/`、`runclient_decomp/`、`META-INF/`、`example/`、`contentpacks_staging/`、`.omo/`、任何 `node_modules/`、`build/`、`run/`。

**禁改**：
- `git status` 里已存在的未提交改动 = 用户自己在推进的工作。不顺手改、不 revert、不 stash。
- `contentpacks/`（jar 产物）与 `libs/` 由构建脚本生成 —— 改源，不改产物。
- 内容侧（包里的资源 / JSON）走内容包编辑器（CPE）流程，不手改。

**tracked 但不是权威**：`PROJECT_GUIDE.md`（1088 行）是骨骼/部位系统的**历史设计稿**，含大量代码样例。实际形状以 `src/main/java/.../client/`、`.../server/bodypart/` 的代码为准。

---

## 3. 规则 ID → 正本

代码注释里的规则 ID 正本都在下面两处。**改行为前先读正本。**

| ID | 正本位置 | 管什么 |
|---|---|---|
| R1–R14 | `design/CONTENTPACK_FORMAT.md` 附录 A（可机械检查的规则清单）；附录 B 是证据索引 | 内容包全部纪律 |
| 规则 A / 规则 B | 同上 = R4 / R5 | core 不得引包类 |
| F1–F4 | `design/CONTENTPACK_FORMAT.md` §5.1 | 从第一行就写成包的最终形状 |
| S0–S4 | `design/CONTENTPACK_FORMAT.md` §5.2 | 壳子化（core → 包）流程 |
| 版本区间 / 星型依赖 | `design/NATIVE_CONTENTPACK_DEV.md` §4 | `Module-Requires` 语义、包间禁直接版本约束 |
| 包间依赖 / 联动 | `design/PACK_DEPS_AND_LINKAGE.md`（操作手册，2026-10-26） | 硬/软依赖 MANIFEST 键、联动规范 = capability 单路线（注册表/定位器为存量 `todo:deprecated`）、外部 mod 两轴与缺失处置（shutdown+响亮，强制归 FML/CI）、禁令与配方 |
| A7、M0–M7 | `design/CORE_TO_PACK_MIGRATION_GUIDE.md`（A7 在 `:412`，M0–M7 表在 `:348-356`） | 显式注册；迁移阶段 |
| REVIEW-L6 | `tools/contentpack-editor/src/core/structureSchema.js`（拒绝 `..` 作目录段） | 路径逃逸 |

**当前不可执行的规则**：R7（`Module-Requires` 必须带上界）。`ContentPackManifest.verifyCompatibility()` 只 `split(">=")` 且要求 `parts.length == 2`，不支持区间 —— 是 core 能力缺失，不是包违规。别给 R7 加门禁：11 个有 MANIFEST 的包全写裸 `>=`，加了会全红（2026-10-25 实测）。

---

## 4. core ↔ 包 的桥（生产实际用的形态）

**静态注册表 + 接口**，不是 `capability`。

- core 侧：`src/main/java/.../core/combat/SwordAttackHandlers.java`（`register()` `:22` / `isAvailable()` `:28` / `getAll()` `:33`）；同形还有 `ArrowCombatHandlers`、`HurtInterceptors`。接口（`ISwordAttackHandler` 等）住 core。
- 包侧：`contentpacks-source/melee-combat-pack/java/example/contentpack/meleecombat/MeleeCombatPack.java:89-91` —— 三行注册。
- mixin 侧：`src/main/java/.../mixin/SwordAttackMixin.java:25` 遍历 `getAll()`，命中才 `ci.cancel()`（`:27`）；注册表为空 = 原版行为（**R3**，见 `:18` 注释）。

三条 mixin 硬纪律（正本 `CONTENTPACK_FORMAT.md:22`、`:193`、`:229-234`）：

1. **mixin 方法体不得引包类** —— 已实崩 `IllegalClassLoadError`，且不是时序问题。
2. **mixin 只做「查询 + 委托 + 回退」**；机制逻辑（业务分支 / 数值 / 状态机）住包内普通类。
3. **跨 mixin 共享状态住包内普通类**，mixin 只读它。

`capability`（`core/contentpack/Capability.java`）是**包 → 包**机制，别把它当 core↔包 的桥推荐。自 map-pack/sable-base 联动起已有真实使用者（provide `map-pack/.../MapPack.java:67`，consume `sablebase/map/SableMapIntegration.java:34`）；用法口径见 `design/PACK_DEPS_AND_LINKAGE.md`。

类可见性：包由 `core/contentpack/ChildFirstClassLoader.java` 加载，`PARENT_FIRST_PREFIXES`（`:32` 起，主 mod 前缀在 `:55`）决定哪些走父加载器 —— 所以包能看见 core，反向不行。

---

## 5. 内容 vs 机制

- **内容增减**（结构 / 物品 / 方块 / 模型 / 动画）→ 走 **CPE**（`tools/contentpack-editor/`）。
- **机制**（combat / durability / structure / quest 行为）→ 写代码，但**从第一行就按 §3 的形状纪律写成包的最终形状**。别先内联进 mixin 再想着搬：`CONTENTPACK_FORMAT.md:328` 有实证 —— 普通类形态 6 天搬完，mixin 内联那个至今搬不动（搬 = 重写）。

---

## 6. 改完必须跑

| 改了什么 | 跑什么 |
|---|---|
| `src/main/` Java、mixin、包源 | `scripts/validate-game.ps1`（无头启服 + 客户端，扫 log 错误标记）；指定包用 `-Pack <名>` |
| `tools/contentpack-editor/` | `cd tools/contentpack-editor && npm test`（42 个契约测试文件） |
| 包加载 / 依赖图 / data 预检 | `.scratch/pack-graph-check/`、`.scratch/pack-data-check/`、`.scratch/nesting-check/`（各有 `run-check.ps1`） |

**只读校验可以用脚本；写文件一律用 Edit / Write**（要有可见 diff）。不要用 `sed` / `node -e` 改文件。

---

## 7. 证据与数字

- 不编数字。估算标「推算」。每条数据要能给出 `文件:行号`，或命令 + 日期 + 逐字摘录。
- **机械事实归脚本，判断归 AI（带 `file:line`），状态符号归用户。**
- 每个提议标注「一次性 / 永久」。一次性修复要升级成能自动抓同类问题的永久检查。
- review 只产出证据报告（`design/REVIEW_<日期>_<主题>.md`），不批量改权威文档。

---

## 8. commit

前缀用 **ShareTree 节点 id**（形如 `[wd-dgn]`、`[w-mele]`），不是计划书 §号 —— ShareTree 的 id 是独立语义名，与 §号无派生关系。id 从 `GET /api/tree?outline` 取。
