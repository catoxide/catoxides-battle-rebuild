# ContentPack Editor — 使用手册

> Catoxide's Battle Rebuild 的 ContentPack 可视化编辑器（实体模块 v0.1）。
> 面向**不会写 Java 的内容创作者**：可视化编辑实体 → 生成 JSON → 一键打包，全程不碰代码。

---

## 1. 一键启动

**方式一（推荐）：双击 exe（打包版）**

`dist/ContentPack Editor.exe` 单文件便携版，双击即用，无需安装。

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
点 **打包** → 调用 `contentpacks-source/build-pack.ps1` → 产出 `contentpacks/<包名>.jar`
- 纯数据包（无 Java 代码）也能打包 ✅
- 产物自动同步到 `run/client/contentpacks/`（游戏运行目录）

---

## 3b. 建筑结构（建筑拼图）工作流

中栏顶部下拉切换到 **建筑结构**（与实体共用中栏/右栏，页签独立）：

1. **选包 → 选结构 / + 新建**（`structures/<structureId>/blueprint.json`）
2. **五个页签**：
   - **基本信息**：structureId、namespace、step（11 个原版生成步骤）、terrainAdaptation（Java 取值）、startPool、maxDistanceFromCenter、biomes、projectToSurface、maxSurfaceLevel
   - **片段与拼图**：每个片段 = 一个原版结构模板（尺寸 + boxFills 整块填充 + fillBlocks 单方块 + jigsaw 块表：x/y/z、name、pool、target、joint、orientation 12 值、finalState、优先级）
   - **模板池**：池 → 元素（引用片段 + weight 1-150 + projection rigid/terrain_matching）+ fallback
   - **放置规则**：random_spread（spacing/separation/salt/frequency）+ 可选互斥区
   - **3D 预览（双模式）**：
      - **拼合生长（默认）**：按原版 jigsaw 规则把多个片段**自由拼合成整栋建筑**——从 startPool 随机抽起点，每个 jigsaw 按其 pool 抽模板，按 name/反向朝向/旋转匹配后放置，直到达到「最大片段」上限（`structureGrowth.js` 纯函数模拟，rng 可注入）。看**多片段自由组合的对准**是否合理；**🎲 重新生长** = 用新随机数再长一遍；图例显示每类片段实际放置次数 ×N，状态行「生长：N 片段 · M 个死端」（死端 = 无匹配目标 / 位置重叠）
      - **并排查看**：各片段并排摆放，看单个片段本体
      - 交互：左键拖拽 = 轨道旋转、右键或 Shift+左键拖拽 = 平移、滚轮 = 缩放、**重置视角 = 固定角落**；图层开关（方块 / jigsaw 标记 / 网格）随时可关；右侧图例 = 每个片段单独显隐；自动旋转开关
3. **保存草稿 → 导出（暂存→包）→ 打包**（与实体同一套暂存体系）
4. **导出原版数据**（结构编辑器独有按钮）：把当前蓝图**机械映射**为原版 4 类文件写入包目录：
   - `assets/<ns>/structures/<piece>.nbt`（模板，jigsaw 写 nbtData 方块实体）
   - `data/<ns>/worldgen/template_pool/<pool>.json`
   - `data/<ns>/worldgen/structure/<structureId>.json`
   - `data/<ns>/worldgen/structure_set/<structureId>_set.json`

   导出后游戏侧**零自定义 worldgen 代码**——原版 jigsaw 引擎直接生成。
   示例包：`contentpacks-source/structure-example-pack/`（大厅+塔+自链走廊）。

> 契约字段与取值严格对齐原版 1.21.1（`src/core/structureSchema.js`，保存/导出时全字段校验，
> 非法值如基岩版 terrainAdaptation 会直接报错）。1.21.2+ 模板路径变化时只需改 `vanillaExport.js` 一处。

---

## 4. 文件产出对照（编辑器 → 游戏侧）

| 编辑器产出 | 路径 | 游戏侧解析 |
|-----------|------|-----------|
| 实体定义 | `entities/<id>.json` | `MobDefinitionParser` |
| 部位配置 | `assets/<ns>/health_config/<id>.json` | `EntityBoneHealthConfig.load()` |
| 结构蓝图（源格式） | `structures/<structureId>/blueprint.json` | 编辑器「导出原版数据」的输入 |
| 结构模板 | `assets/<ns>/structures/<piece>.nbt` | 原版结构模板加载器 |
| 模板池 / 结构 / 结构集合 | `data/<ns>/worldgen/...` | 原版 worldgen 注册表（jigsaw 引擎） |
| 打包产物 | `contentpacks/<包名>.jar` | `ContentPackLoader` |

---

## 5. 常见问题

| 问题 | 解决 |
|------|------|
| 双击 start.bat 闪退 | 依赖未装完/网络问题，看命令行输出；或手动 `npm install` |
| 打包报"jar 命令找不到" | 需要 JDK 在 PATH 上（项目本身需要 JDK） |
| 部位配置页签没有骨骼树 | 先在"模型与资源"页签解析模型 |
| 游戏里实体白块/无动画 | 检查 model.id、贴图路径、动画名是否与 spark_animations 文件一致 |
| 改了实体不生效 | 重新打包 jar（游戏加载的是 jar，不是源 JSON） |

---

## 6. 技术信息

- 技术栈：Electron（Node + 原生 JS，无框架）+ Three.js（3D 预览，本地捆绑 `src/lib/three.module.js`）
- 与主 mod 通信：纯文件契约（JSON/NBT），语言完全解耦
- 已内置内容类型：实体（4 页签）、建筑结构（5 页签，含 3D 预览）
- 后续规划：拖拽分配骨骼、schema 驱动（内容包贡献编辑页面）、loottable 模块、3D 预览增强（片段连接关系图 / 真实方块贴图）

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
│   │   ├── structureSchema.js   # 建筑结构契约（原版对齐）+ 校验
│   │   ├── nbt.js               # 小端 gzip NBT 读写（结构模板）
│   │   ├── vanillaExport.js     # blueprint → 原版 4 类 jigsaw 数据文件
│   │   └── structureGrowth.js   # 原版 jigsaw 生长模拟（纯函数，拼合预览用）
│   ├── lib/
│   │   ├── three.module.js      # Three.js（捆绑，3D 预览用）
│   │   └── structurePreview.js  # 建筑结构 3D 预览（轨道相机/平移/拼合生长/图层/显隐）
│   └── views/             # 视图组件（packList/entityList/entityEditor/structureList/structureEditor）
├── .scratch/              # 临时脚本（gitignore，不进版本库）
└── test/                  # 测试（npm test = core.test.js + structure.test.js + structureGrowth.test.js）
```
