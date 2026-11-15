# ContentPack Editor — 使用手册

> Catoxide's Battle Rebuild 的 ContentPack 可视化编辑器（当前 **v0.2.2**，发布说明见 [RELEASE-0.2.2.md](RELEASE-0.2.2.md)）。
> 面向**不会写 Java 的内容创作者**：可视化编辑实体/物品/方块 → 生成 JSON → 一键打包，全程不碰代码。
>
> **v0.2 起，它是内容包「生成器」**：新建包会一次产出 **MANIFEST 全属性 + `build.gradle`（Gradle 子项目骨架）
> + 入口类骨架**，并在打包前跑一次**预检**（把「构建成功但加载不了」的坑拦在构建之前）。
> 打包走 `gradlew :packs:&lt;目录&gt;:contentpackJar`，失败自动回退 `build-pack.ps1`。
> 详见 [§3c 包骨架与预检](#3c-包骨架与预检v02-新增)。

---

## 1. 一键启动

**方式一（推荐）：双击 exe（打包版）**

`dist/ContentPack Editor 0.2.0.exe` 单文件便携版，双击即用，无需安装。

- **放到项目目录里双击**（项目根或任意子目录）：自动找到 `contentpacks-source/`，零配置
- 放别处双击：首次弹一次目录选择（选项目根），之后记住，也是零配置
- 打包命令：`npm run pack`（产出 `dist/ContentPack Editor.exe`）

**方式二（开发版）：双击** `start.bat`

- 首次运行会自动安装依赖（约 1-2 分钟，需要网络）
- 之后双击直接打开编辑器窗口

**方式三：命令行**

```powershell
cd tools\contentpack-editor
npm start
```

> 依赖缺失时手动装一次：`npm install`（国内网络建议先设 `ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/`）

---

## 2. 界面布局（三栏）

```
┌────────────┬────────────┬─────────────────────────────────┐
│  内容包列表  │  实体列表   │        实体编辑器（4 个页签）        │
│  (左栏)     │  (中栏)    │  基本信息/模型资源/部位配置/动画状态机  │
│            │            │                                 │
│ · 选择包    │ · 选择实体  │ 底部: [保存实体] [打包]            │
│ · 新建包    │ · 新建实体  │                                 │
└────────────┴────────────┴─────────────────────────────────┘
```

---

## 3. 完整工作流（5 步）

### 第 1 步：选择/新建内容包（左栏）
- 左栏列出 `contentpacks-source/` 下的所有包
- 点 **+ 新建** → 填包名（Module-Name）、版本、依赖 → 创建

### 第 2 步：新建实体（中栏）
- 选中包后，点 **+ 新建实体** → 右栏打开编辑器

### 第 3 步：编辑实体（右栏 4 个页签）

**① 基本信息**
- `id`（必填，实体注册名）、`namespace`（缺省挂主 mod）、`displayName`
- 碰撞箱：width / height
- 属性：血量 / 攻击 / 速度 / 索敌范围 / 护甲
- 行为组件：attack（攻击）/ ai（AI 目标）/ raycast（视线检测）

**② 模型与资源**
- 点 **选择本地模型文件** → 选 spark 模型 json（如 `contentpacks-source/modular-zombie-3/spark_models/entity/zombie3pack/modular_zombie_3.json`）
- 编辑器自动解析骨骼树 + 建议 model.id
- 也可 **从 jar 导入** 已有包的模型
- 填贴图路径 + 5 个音效槽

**③ 部位配置**（核心）
- 先解析模型（页签②）→ 点 **生成默认部位** → 自动按 head/body/arm/leg 分组
- 左侧骨骼树 → 点骨骼旁的 **+** → 添加到当前选中的部位
- 每个部位可设：**部位名 / 血量 / 致死性 / 传导到基础血量倍率**
- 每个骨骼单元可设：**传导系数 / 装甲值 / 抗贯穿 / 碰撞标签**

**④ 动画状态机**
- 模板下拉：`melee`（近战）/ `ranged`（远程）/ `boss`（预留）
- 8 行固定状态表（待机/行走/奔跑/攻击/警觉/正面受击/背面受击/蓄力）
- 每行填动画名（对应 `spark_animations/.../<动画名>.json` 的文件名）
- 点 **应用模板动画名** 一键填默认值

> 状态判定逻辑由主 mod 写死（determineAnimationState），作者**只需填动画名**，无需设计状态机。

### 第 4 步：保存实体
点 **保存实体** → 生成两个文件（自动写入包目录）：
- `entities/<id>.json` — 实体定义（主 mod `MobDefinitionParser` 解析）
- `assets/<ns>/health_config/<id>.json` — 部位配置（主 mod `EntityBoneHealthConfig` 读取）

### 第 5 步：打包
点 **打包** 按钮 → 依次做四件事：

1. **补齐骨架**：若包目录少了 `build.gradle`（老包），自动补上（`settings.gradle` 的自动发现只认有它的目录）；
2. **预检**：有 `error` 直接中止，不做「构建成功但加载不了」的无用功；
3. **构建**：`gradlew :packs:<目录名>:contentpackJar` → 产物 `contentpacks/<Module-Name>.jar`
   （Gradle 不可用时自动回退 `contentpacks-source/build-pack.ps1`）；
4. **产物校验**：读产物 jar 的 MANIFEST（必须是 `Module-Type: contentpack`、`Module-Name` 一致）、
   检查 `entities/*.json` 是否真的进了包，并扫描 `contentpacks/`、`run/*/contentpacks/` 下
   **同 Module-Name 的重复 jar**（重复会导致「加载哪个取决于扫描顺序」）。

- 纯数据包（无 Java 代码）也能打包 ✅
- 产物自动同步到 `run/client/contentpacks/`（游戏运行目录）
  ⚠️ 该同步是 `Copy` 不是 `Sync`：**改名/弃用的旧 jar 必须手工删**（预检会替你查出来）

---

## 3c. 包骨架与预检（v0.2 新增）

### 新建包时产出什么

点左栏 **+ 新建**，填完表单后落盘的文件（`contentpacks-source/<Module-Name>/`）：

| 文件 | 内容 | 为什么 |
|---|---|---|
| `MANIFEST.MF` | **全属性**：Module-Name/Version/Requires/Type/Entry/Dependencies/Dependencies-Optional/Lib-Requires/Mod-Requires/Mod-Optional | 规范 §2；属性按规范顺序排列 |
| `build.gradle` | 5 行 Gradle 子项目样板（`apply from: '../contentpack.gradle'` + 可选 `dependencies {}`） | 让包**天生就是 Gradle 包**，自动发现无需改 `settings.gradle` |
| `java/<入口类>.java` | 入口类骨架（实现 `ContentPack`），**含 entities 的数据驱动注册代码** | 加载器**不会**自动注册 `entities/*.json`；没有这段代码，JSON 就是死数据 |
| `entities/` | 目录（实体由编辑器写入） | — |

表单字段：

- **Module-Entry**：有实体/代码时必填；留空 = 纯数据包（**合法**用法，此时不要留实体）
- **额外编译依赖**：整条 Groovy 语句，`;` 分隔，例如
  `compileOnly "dev.ryanhcode.sable:sable-neoforge-1.21.1:2.0.3"` 或 `compileOnly rootProject.files('libs/spark-core.jar')`
  —— **必须 compileOnly**（包的类加载闭包只有「父环境前缀 + 自己 jar + `_libs`」，写 `implementation` 运行期必然 `NoClassDefFoundError`）
- **Module-Lib-Requires**：声明后需存在 `contentpacks-source/_libs/<库名>/*.jar`，否则 Gradle 构建告警且编译失败

### 预检拦什么（点「预检」按钮只读检查，不写文件）

| 类别 | 例子 |
|---|---|
| MANIFEST | Module-Name 为空/含非法字符、Module-Type 不是 `contentpack`、包依赖写了版本区间（包间禁止）、依赖自己、硬+软重复声明 |
| 入口类 | 不是合法全限定类名；落在 `com.catoxide.catoxidesbattlerebuild.*`（parent-first 前缀 ⇒ 父加载器看不到包内类） |
| 实体 JSON | 缺 `id`、id 含大写/空格、id 重复（同名文件互相覆盖）、数值字段是 `null`（编辑器留空 → JSON 变 null → 解析器抛异常）、`animations` 键不是整数、namespace/model/texture/sounds 不是合法 ResourceLocation |
| 图形一致性 | 有 `entities/` 但无 Module-Entry（JSON 是死数据）、声明了 Module-Entry 但没有对应源码、缺 `build.gradle`、`entities/<x>.json` 文件名与 `id` 不一致 |
| 产物 | 同 Module-Name 的重复 jar（旧文件名残留）——这条是真实事故来源 |

每条都标注了它在 core 里的**权威来源**（如 `MobDefinitionParser.java:41`、`ChildFirstClassLoader.java:55`），
因为规则不是编辑器的意见，而是加载器的真实行为。

> 规则与骨架的单一真源是 `src/core/packScaffold.js`（纯函数）。
> `contentpacks-source/editor/index.html`（零安装网页版）直接 `&lt;script src&gt;` 复用它，不存在第二份规则。

---

## 3b. 建筑结构（建筑拼图）工作流

中栏顶部下拉切换到 **建筑结构**（与实体共用中栏/右栏，页签独立）：

1. **选包 → 选结构 / + 新建**（`structures/<structureId>/blueprint.json`）
2. **五个页签**：
   - **基本信息**：structureId、namespace、step（11 个原版生成步骤）、terrainAdaptation（Java 取值）、startPool、maxDistanceFromCenter、biomes、projectToSurface、maxSurfaceLevel
   - **片段与拼图**：每个片段 = 一个原版结构模板（尺寸 + boxFills 整块填充 + fillBlocks 单方块 + jigsaw 块表：x/y/z、name、pool、target、joint、orientation 12 值、finalState、优先级）
   - **模板池**：池 → 元素（引用片段 + weight 1-150 + projection rigid/terrain_matching）+ fallback
   - **放置规则**：random_spread（spacing/separation/salt/frequency）+ 可选互斥区
   - **3D 预览（双模式，方块级体素 + 真实贴图）**：
      - **拼合生长（默认）**：按原版 jigsaw 规则把多个片段**自由拼合成整栋建筑**——从 startPool 随机抽起点，每个 jigsaw 按其 pool 抽模板，按 name/反向朝向/旋转匹配后放置，直到达到「最大片段」上限（`structureGrowth.js` 纯函数模拟，rng 可注入）。看**多片段自由组合的对准**是否合理；**🎲 重新生长** = 用新随机数再长一遍；图例显示每类片段实际放置次数 ×N，状态行「生长：N 片段 · M 个死端」（死端 = 无匹配目标 / 位置重叠）
      - **并排查看**：各片段并排摆放，看单个片段本体
       - 渲染：**方块级体素**（boxFills/fillBlocks 展开为逐方块体素，`voxel.js`）+ **真实方块贴图**（从本地 `数据集/1.21.1/client/assets` 读取，不随包分发；缺失回退灰盒）
       - **体素编辑**：左键点选方块（显示 id）→ 编辑栏换块 / **笔刷** 3×3 刷 / **切片** 隐藏 y>N / **清除编辑** 恢复定义
       - **拼合诊断**（🩺 检查可生成性）：随机生长 N 次（默认 50）统计成功率 / 片段数分布 / 死端原因 / **体素重叠** / 占地尺寸；死端位置以**红色幽灵盒**标在 3D 中（图层可开关）
      - 交互：左键拖拽 = 轨道旋转、右键或 Shift+左键拖拽 = 平移、滚轮 = 缩放、**重置视角 = 固定角落**；图层开关（方块 / jigsaw 标记 / 网格 / 幽灵盒）随时可关；右侧图例 = 每个片段单独显隐；自动旋转开关
    - **结构导航**（片段与拼图页签内）：结构 → 片段 → jigsaw（契约徽标 ✓/⚠/✗）/ 池 → 元素 的多级折叠树，一眼看清整栋建筑的连接关系与问题点；jigsaw 行点击看详情，片段行内直接圈地
    - **圈地读取**：「⛏ 圈地读取 (.nbt)」把游戏/工具导出的结构模板 .nbt 直接导入为新片段（或片段卡片上的「⛏ 圈地」替换该片段）——自动判别小端/大端，兼容 1.20.x 旧格式，jigsaw 方块实体数据完整保留
3. **保存草稿 → 导出（暂存→包）→ 打包**（与实体同一套暂存体系）
4. **导出原版数据**（结构编辑器独有按钮）：把当前蓝图**机械映射**为原版 4 类文件写入包目录：
   - `data/<ns>/structure/<piece>.nbt`（模板，**1.21.1 路径：data 目录 + structure 单数**，已对照原版 jar 验证；jigsaw 数据写 blocks[].nbt）
   - `data/<ns>/worldgen/template_pool/<pool>.json`
   - `data/<ns>/worldgen/structure/<structureId>.json`
   - `data/<ns>/worldgen/structure_set/<structureId>_set.json`

   导出后游戏侧**零自定义 worldgen 代码**——原版 jigsaw 引擎直接生成。
   示例包：`contentpacks-source/fortress-pack/`（从原版结构包导入的 20 片段要塞）。

> 契约字段与取值严格对齐原版 1.21.1（`src/core/structureSchema.js`，保存/导出时全字段校验，
> 非法值如基岩版 terrainAdaptation 会直接报错）。NBT 为 type-first 条目 + gzip + **大端** + DataVersion 3955，
> 与 nbtlib 字节级 oracle 交叉验证（`test/fixtures/oracle-*.nbt`）；读取侧容错（小端 / 1.20.x 旧目录自动识别）。
> 模板路径常量集中在 `vanillaExport.js` 一处。

---

## 3d. 物品/方块工作流（items/ + blocks/，0.5 新增）

中栏顶部下拉切换到 **物品** 或 **方块**（与实体/结构共用中栏/右栏，页签独立）：

1. **选包 → 选条目 / + 新建**（`items/<id>.json` / `blocks/<id>.json`）
2. **右栏表单**：
    - **基本信息**：id / namespace / 中英显示名；物品 = 堆叠上限(1-99)/稀有度/创造栏/防火；
      方块 = 材质物理（硬度/抗爆/声音/自发光 0-15/摩擦 + 透明/随机刻/需工具/碰撞 开关）、
      **方块实体**（无 / generic / custom 类名）、掉落 selfDrop、原版数据标签、创造栏、blockItem
    - **形态与参数**（方块）：**自定义参数**编辑（direction/axis/boolean/int/enum，
      可「从目录添加」——目录 = 主 mod `block_properties.json`，Java 与编辑器共读同一份；
      也可自定义新参数）+ 状态组合计数（>4096 拦截）+ **完整方块形态定义**：
      variants（全部状态组合 → 模型/旋转，表格自动生成，漏组合报错）或
      multipart（基础层 + when 条件层）
    - **模型**：cube_all / cube（上侧下可分）/ generated / raw（手写 JSON）+ 实时预览
    - **预览**：全部导出文件的 JSON 实时预览 + **与基座注册器（BatchContentRegistrar）一致的校验结果**
3. **保存**（前端同规则校验 → 暂存草稿）→ **导出**（草稿并入正式包 + 该条目原版文件）
   → **导出原版（全部）**（整包，中栏工具条按钮，含创造栏语言）→ **打包**
4. **「导出原版」产物**（1.21.1 原版文件，写入包目录，由主 mod 随 jar 挂进资源树）：
    - `assets/<ns>/models/item/<id>.json`（物品模型 / 方块物品模型）
    - `assets/<ns>/models/block/<file>.json`（方块模型；variants/multipart 引用的附加模型自动兜底生成）
    - **`assets/<ns>/blockstates/<id>.json`（完整方块形态：全部状态组合 → 模型，含自定义参数；目录名是复数，1.21.1 只读这个路径）**
    - `assets/<ns>/lang/en_us.json` / `zh_cn.json`（**合并写**）
    - `data/<ns>/loot_table/blocks/<id>.json`（挖掉掉自己）
    - `data/<ns|minecraft>/tags/block/...json`（原版数据标签，同包合并写）
5. 注册在 Java 侧：入口类 `register()` 调 `context.registerItemsAndBlocksFromJar()`
   （「补齐骨架」自动生成/修复该调用；有 items/blocks 但入口类缺调用 → 预检 `E-IB-NO-REGISTER` 拦截）。

> 契约单一真源：主 mod `core/registry/BatchContentRegistrar.java` + `ItemBlockParser.java`；
> 前端镜像 `src/core/itemBlockSchema.js`（单测 `test/itemBlock.test.js` 32 项覆盖）。
> 完整规范：`design/ITEM_BLOCK_REGISTRATION.md`。示例包：`contentpacks-source/item-block-test-pack/`
>（12 组合 variants / multipart / 发光 + generic 方块实体 / 附加模型引用）。

---

## 3e. 任务工作流（quests/，0.2.2 新增）

中栏顶部下拉切换到 **任务**（与实体/结构/物品共用中栏/右栏，页签独立）：

1. **选包 → 选任务 / + 新建**（`quests/<id>.json`，一个文件 = 一个任务；子任务嵌在 `children` 里）
2. **右栏三个页签**：
   - **基本信息**：id（保存后锁定——文件名按 id 生成）/ title / description / 可选任务坐标 location / priority /
     **发放节奏**（`repeatable` 可重复 + `cooldownTicks` 冷却：一次性任务收到过就永不再发；可重复任务进入终态
     且冷却结束后才能再发，**每次都是新实例 id**，旧完成记录只保留最近 10 条）
   - **发放与目标**：givers（**进入区域发放** proximity_giver：中心坐标 + 半径）与 goals（**持有物品** item_present 只检测 / **提交物品** item_submit 完成时扣除）逐行增删；
     字段表单由 `questSchema.js` 的类型描述驱动；**包 Java 工厂提供的自定义类型**（如 `kill` / `craft`）只读显示原 JSON 并可删除，保存时原样保留
   - **预览与校验**：校验错误/警告 + 即将落盘的 JSON 预览（键序与 Java 解析顺序一致）
3. **保存**（前端校验 → 暂存草稿，徽标变「草稿」）→ **⬆ 写入正式包**（中栏工具条，整包草稿一起落盘并回收）
   → **打包 jar**（与实体同一套打包流程）
4. 注册在 Java 侧：入口类 `register()` 调 `context.registerQuestsFromJar()`
   （任务工具条的 **🩹 补齐骨架** 在入口类**只插这一行**，不重生成整类——老入口类里手写的工厂/命令/网络包原样保留；
   补完会顺手跑预检并列出剩余的 `E-Q-*`；有 quests 但入口类缺调用 → 预检 `E-Q-NO-REGISTER` 拦截）

任务专用预检规则（`E-Q-*`，都在 `src/core/packPreflight.js`）：

| 代码 | 拦什么 | 为什么 |
|---|---|---|
| `E-Q-NO-REGISTER` | 有 quests/*.json 但入口类没调 `registerQuestsFromJar()` | 加载器不会自动扫描，JSON 是死数据 |
| `E-Q-JSON` | 任务文件不是合法 JSON | 加载期被隔离，该任务不存在 |
| `E-Q-VALID` | 缺 item/count、id 大写或带命名空间、location 不是 3 元组、既无 goals 也无 children（发放即完成）、`cooldownTicks` 非 JSON 整数或配在一次性任务上… | 镜像 `QuestDefinitionParser` 的严格性；游戏里该文件会注册失败 |
| `E-Q-ID-DUP` | 包内两个文件用同一 id | `QuestSystem` 以 id 为键，后注册覆盖先注册（只有一条 warn） |
| `E-Q-NESTED` | `quests/` 子目录里有 JSON | 注册器只扫**第一层**，子目录里的任务永远不会加载 |
| `W-Q-VALID` | 用了包 Java 工厂提供的 goal/giver 类型 | 字段由包决定，编辑器不校验（保存时原样保留） |

> 契约单一真源：主 mod `core/quest/QuestDefinitionParser.java` + `QuestBuiltinComponents.java`
> + `QuestGivePolicy.java`（repeatable / cooldownTicks 的自动发放判定，独立 `javac` 单测）；
> 前端镜像 `src/core/questSchema.js`（单测 `test/quest.test.js` 30 项 + 视图 `test/questView.test.js` 5 项）。
> 示例包：`contentpacks-source/quest-test-pack/`（`item_present.json`（可重复样例）/ `item_submit.json` / 历史 `quests.json`）。

---

## 3f. UI 贴图工作流（texture-editor，0.2.2 新增）

独立子窗口：菜单 **工具 → UI 贴图编辑器**（`Ctrl+U`）。把「画一张 MC 风格面板」变成可复现纯函数
`spec.json + 原版元素库 → render() → PNG`；**零覆盖输出与原版逐字节相同**，改 `tokens` 只重着色 chrome、槽位不动。

> 完整 spec 契约 / 四原语 / 无头 CLI（AI 驱动）/ 元素库格式 → [`texture-editor/README.md`](texture-editor/README.md)（指针，不在此重复）。
> 日常只碰 spec 两个字段：`tokens`（换色）与 `layers`（几何/结构）；导出落盘到包 `assets/<ns>/textures/gui/<name>.png`。

---

## 4. 文件产出对照（编辑器 → 游戏侧）

| 编辑器产出 | 路径 | 游戏侧解析 |
|-----------|------|-----------|
| 实体定义 | `entities/<id>.json` | `MobDefinitionParser` |
| 部位配置 | `assets/<ns>/health_config/<id>.json` | `EntityBoneHealthConfig.load()` |
| 结构蓝图（源格式） | `structures/<structureId>/blueprint.json` | 编辑器「导出原版数据」的输入 |
| 结构模板 | `data/<ns>/structure/<piece>.nbt` | 原版结构模板加载器 |
| 模板池 / 结构 / 结构集合 | `data/<ns>/worldgen/...` | 原版 worldgen 注册表（jigsaw 引擎） |
| 物品/方块定义（源格式） | `items/<id>.json`、`blocks/<id>.json` | `BatchContentRegistrar`（`registerItemsAndBlocksFromJar()`） |
| 物品/方块原版文件 | `assets/<ns>/models/...`、`assets/<ns>/blockstates/<id>.json`、`lang`、`loot_table`、`tags` | 原版模型/blockstate/语言/掉落/标签加载器 |
| 任务定义（源格式） | `quests/<id>.json` | `QuestDefinitionParser`（`registerQuestsFromJar()`，只扫第一层） |
| UI 面板贴图（spec 派生） | `assets/<ns>/textures/gui/<name>.png` | 原版 GUI 贴图加载（九宫格面板，1×） |
| 打包产物 | `contentpacks/<Module-Name>.jar` | `ContentPackLoader` |
| 包声明 + 构建骨架 | `MANIFEST.MF`、`build.gradle`、`java/<入口类>.java` | 前者被 `ContentPackManifest` 解析；后两者供 `gradlew :packs:<目录>:contentpackJar` 构建 |

---

## 5. 常见问题

| 问题 | 解决 |
|------|------|
| 双击 start.bat 闪退 | 依赖未装完/网络问题，看命令行输出；或手动 `npm install` |
| 打包报"jar 命令找不到" | 回退路径 `build-pack.ps1` 需要 JDK 在 PATH 上；首选路径（Gradle）不需要 |
| 打包报 `Could not find dev.ryanhcode.sable:...` | 第三方 Maven 仓库必须写在**包项目**里（共享的 `contentpacks-source/contentpack.gradle` 已含），见 `design/GRADLE_MULTIPROJECT.md` §4.3 |
| 预检报「有实体但没填 Module-Entry」 | 填上 Module-Entry（编辑器会生成入口类；它会注册 `entities/*.json`）。只写 JSON 而没有注册代码，包在游戏里等于空的 |
| 预检报「同 Module-Name 的重复 jar」 | 产物按 Module-Name 命名，改名/弃用后的旧 jar 必须删（`contentpacks/` 与 `run/*/contentpacks/` 都要看） |
| 一个包构建失败会影响别的包吗 | 不会。包级故障隔离：失败包与其硬依赖者被取消加载，游戏照常启动（`design/CONTENTPACK_FORMAT.md` §12） |
| 部位配置页签没有骨骼树 | 先在"模型与资源"页签解析模型 |
| 游戏里实体白块/无动画 | 检查 model.id、贴图路径、动画名是否与 spark_animations 文件一致 |
| 改了实体不生效 | 重新打包 jar（游戏加载的是 jar，不是源 JSON） |

---

## 6. 技术信息

- 技术栈：Electron（Node + 原生 JS，无框架）+ Three.js（3D 预览，本地捆绑 `src/lib/three.module.js`）
- 与主 mod 通信：纯文件契约（JSON/NBT），语言完全解耦
- 已内置内容类型：实体（4 页签）、建筑结构（5 页签，含 3D 体素预览 / 体素编辑 / 拼合诊断 / 结构导航 / 圈地读取）、
  物品 / 方块（基本信息 / 形态与参数（自定义参数 + blockstate）/ 模型 / 预览，含原版文件导出）、
  任务（基本信息 / 发放与目标 / 预览与校验）
- 后续规划：拖拽分配骨骼、schema 驱动（内容包贡献编辑页面）、loottable 模块、游戏内圈地桥接（见 `design/STRUCTURE_BRIDGE_FEASIBILITY.md`）

---

## 7. 目录结构

```
tools/contentpack-editor/
├── start.bat              ← 一键启动（双击）
├── package.json           # npm start / test / pack
├── main.js                # Electron 主进程（IPC + 文件操作）
├── preload.js             # 安全桥接（window.api）
├── src/
│   ├── index.html         # 主界面
│   ├── styles.css         # 深色主题
│   ├── app.js             # 全局状态 + 启动
│   ├── core/              # 纯逻辑库（可独立测试）
│   │   ├── packScaffold.js      # ★ 包骨架单一真源：MANIFEST 全属性/规范化 + build.gradle + 入口类骨架 + 预检规则（纯函数）
│   │   ├── packPreflight.js     # ★ 预检（fs 侧）：扫包目录 + 重复 Module-Name 扫描 + scaffoldFiles
│   │   ├── jarReader.js         # ★ 极简 zip 读取器（读产物 MANIFEST/条目，用于打包后校验）
│   │   ├── structureSchema.js   # 建筑结构契约（原版对齐）+ 校验
│   │   ├── nbt.js               # 小端 gzip NBT 读写（type-first 条目，LE/BE 可选）
│   │   ├── vanillaExport.js     # blueprint → 原版 4 类 jigsaw 数据文件（1.21.1 路径）
│   │   ├── structureGrowth.js   # 原版 jigsaw 生长模拟（纯函数，拼合预览用）
│   │   ├── regionImport.js      # 圈地读取：.nbt 结构模板 → 片段（LE/BE 自适应 + 旧格式）
│   │   ├── structureDiagnostics.js # 拼合诊断（生长统计/重叠/契约检查，纯函数）
│   │   ├── voxel.js             # boxFills/fillBlocks → 方块级体素（含 r0-r3 旋转）
│   │   └── mcAssets.js          # 方块状态/贴图索引（读本地数据集，不重分发）
│   ├── lib/
│   │   ├── three.module.js      # Three.js（捆绑，3D 预览用）
│   │   └── structurePreview.js  # 建筑结构 3D 预览（轨道相机/平移/拼合生长/图层/显隐）
│   └── views/             # 视图组件（packList/entityList/entityEditor/structureList/structureEditor）
├── model-editor/          # 3D 模型/动画编辑器（独立子窗口）
├── texture-editor/        # UI 贴图生产链（独立子窗口；core 纯逻辑 + 元素库 + 无头 CLI，见其 README）
├── .scratch/              # 临时脚本（gitignore，不进版本库）
└── test/                  # 测试（npm test = 11 个文件 / 301 个断言，0 失败）
                           #   其中 packScaffold.test.js(30) + packPreflight.test.js(20) 覆盖包骨架与预检
```
