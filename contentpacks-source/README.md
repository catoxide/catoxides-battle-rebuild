# ContentPack 源码与工具

本目录是内容包（ContentPack）的**源码源**。每个子目录是一个包的完整源码：
入口类 Java 代码在 `<包>/java/`，资源/JSON/MANIFEST 在包目录下。

## 目录

| 目录 | 说明 |
|------|------|
| `template-pack/` | 标准模板（无源码，供复制；**注意**它的 Module-Entry 没有对应源码，因此不会被构建） |
| `test-mob-pack/` | 数据驱动实体验证包（entities JSON + 独立 Java 入口类） |
| `modular-zombie-3/` | 手写实体类对照（模型/动画/纹理，额外依赖 spark-core） |
| `sable-base-pack/` | 防腐层适配（ISubLevelService + PoseData，额外依赖 sable） |
| `durability-pack/` | 原版物品耐久 + 三机制降级 + toml 配置 |
| `quest-test-pack/` | 任务系统验证包 |
| `structure-example-pack/` | 建筑结构示例（纯数据包）：`structures/*/blueprint.json` 源格式 + 编辑器导出的原版 jigsaw 数据 |
| `item-block-test-pack/` | **物品/方块批量注册示例**（0.5）：`items/` + `blocks/` JSON（含自定义参数 blockstate、generic 方块实体、multipart）+ 导出的原版模型/blockstate/lang/loot/标签；入口类调 `registerItemsAndBlocksFromJar()` |
| `editor/` | **网页版生成器**（零安装，双击打开 `index.html`）：产出 Manifest + build.gradle + 入口类骨架 + 实体 JSON |
| `contentpack.gradle` | **共享构建约定**（单一真源）：toolchain / 仓库 / sourceSets / `contentpackJar` / MANIFEST 派生 |
| `build-pack.ps1` | 回退打包脚本（独立 javac + jar；**与 Gradle 产物同目录，不要混用**） |
| `_libs/` | `Module-Lib-Requires` 声明的外部纯类库（`_libs/<库名>/*.jar`），当前无使用者 |

## 创建新内容包

**推荐：用生成器**（自动产出下面全部骨架，并带预检）

- 可视化版：`tools/contentpack-editor`（Electron，含模型/部位/结构编辑）
- 网页版：双击 `editor/index.html`（零安装，最小生成器）

它会产出：

```
contentpacks-source/<Module-Name>/
├── MANIFEST.MF          # 全属性（按规范顺序）
├── build.gradle         # 5 行 Gradle 子项目样板
├── java/<入口类>.java    # 入口类骨架（含 entities 的数据驱动注册代码）
└── entities/*.json
```

下载的 zip 顶层目录就是包名，**解压到本目录即可**（不需要改 `settings.gradle`：自动发现）。

### 手工创建（3 步）

1. **复制模板**：`template-pack/` → `my-pack/`，改 `MANIFEST.MF` 的 `Module-Name`/`Module-Entry`；
   再写一份 `build.gradle`（5 行样板，见 `design/GRADLE_MULTIPROJECT.md` §2.3）
2. **加内容**：`entities/*.json` 定义生物；`spark_models/` `spark_animations/` `assets/` 放资源；
   需要代码时把入口类放在 `<包>/java/`（包结构对应 `Module-Entry`）
3. **构建**（见下）

## 构建

```powershell
gradlew :packs:my-pack:contentpackJar     # 单个包 → contentpacks/<Module-Name>.jar
gradlew contentpackJar                    # 全部包
```

要点：

- **产物按 `Module-Name` 命名**；改名/弃用的旧 jar 必须手工删除（同 Module-Name 重复时，
  加载器丢弃哪个**取决于扫描顺序**，不可复现）
- 包项目是**条件纳入**的：只有命令里出现 `contentpack` / `:packs…` 时才会进构建图，
  所以主 mod 的 `gradlew compileJava` 不会被 5 套 neoForm 流水线拖慢
- 改了 core API 后**不需要**刷新任何 classpath 文本文件：包对 core 是声明式依赖（`compileOnly project(':')`）
- 回退路径（Gradle 出问题时交叉验证用，**不要与 Gradle 混用**）：

```powershell
powershell -File contentpacks-source/build-pack.ps1 -SourceDir contentpacks-source/my-pack -OutJar contentpacks/my-pack.jar
```

## 游戏实跑校验（开发期测试工具）

原版**没有独立的资源包/数据包校验器**——唯一的"官方检查器"是游戏进程本身
（进世界时 `RegistryDataLoader` 硬校验 data 注册表；客户端启动时 `ModelBakery`
校验模型/blockstate/音效；战利品表与 tags 懒解析）。
`scripts/validate-game.ps1` 把这件事做成一键脱机流程：无头启动游戏 →
扫描 log 错误标记 → 杀进程 → 报 pass/fail。

```powershell
.\scripts\validate-game.ps1                        # data（runServer）+ models（runClient）全跑
.\scripts\validate-game.ps1 -Phase data            # 只校验 data/ 注册表 + 包加载/批量注册
.\scripts\validate-game.ps1 -Phase models          # 只校验模型/blockstate/音效
.\scripts\validate-game.ps1 -Pack item-block-test-pack   # 先重建该包 jar 再校验
```

注意：会杀掉带 `-Dforge.logging.console.level` 的 java 进程（游戏 JVM），
**不要在手动开着游戏时跑**；首次 data 阶段会在 `run/server/saves` 建新世界。

## 格式与规范

| 文档 | 内容 |
|------|------|
| `design/CONTENTPACK_FORMAT.md` | MANIFEST 属性 / 实体 JSON schema / 行为组件 / 加载流程 / 故障隔离 |
| `design/ITEM_BLOCK_REGISTRATION.md` | **物品/方块批量注册契约**（0.5）：items/blocks JSON、自定义参数与完整方块形态、方块实体、原版导出 |
| `design/GRADLE_MULTIPROJECT.md` | 多项目构建配方、必填项、已证伪路径、产物等价性闸门 |
| `design/PARALLEL_DEV_WORKFLOW.md` | 主 mod 会话与内容包会话的并行边界与并发纪律 |
| `design/NATIVE_CONTENTPACK_DEV.md` | 从零用 ContentPack 开发机制的路径 |
