# ContentPack Editor 0.2.1 发布说明

> 本轮只有一个主题：**"反馈机能跑起来"**。上一版（0.2.0）本机一切正常，但反馈同学
> 拿到 exe 后「包库 / 新建包 / 左侧 + 新建」三个按钮全部无响应，顶栏也没有"源码: …"那一小串。
> 0.2.1 把这个问题连根修掉，并补上"下次反馈能直接定位"的诊断能力。

## 1. 反馈机三按钮无响应（根因：没有 mod 工程 → 初始化被提前 return）

### 复现
在**没有** `catoxides-battle-rebuild` 工程的机器上启动 exe：`tryResolveProjectRoot()` 返回 `null`
→ 渲染端收到 `needProjectRoot:true` → `setupProjectRoot()` 弹目录选择，然后 **`return`**。
后果全部来自这一个 `return`：

| 现象 | 原因 |
|---|---|
| 顶栏没有「源码: …」 | 那行文本写在 `return` 之后 |
| 包库 / 新建包 点了没反应 | `initPackPathTools()` 在 `return` 之后，`onclick` 从未绑定 |
| 左侧「+ 新建」点了没反应 | `PackList.init()` 在 `return` 之后，按钮监听从未绑定 |
| 包列表空白 | `PackList.loadPacks()` 在 `return` 之后 |

用 CDP 在"无项目根"环境实测（0.2.0）：`btnPickPacksRoot.onclick === null`、`btnNewPack` 无监听、
`headerPaths.textContent === ''`，与反馈现象逐条对应。

### 修复
- 新增 `src/core/projectRoot.js`（纯逻辑 + fs 注入，可单测）：
  `配置 → 向上探测 → 独立工作区 → 兜底`。
  - 独立工作区：`<文档>/Content Pack Editor/`，内含 `contentpacks-source/`、`contentpacks/`；
    文档目录不可写则回退 `<userData>/Content Pack Editor/`，再不行回退临时目录。
  - 工作区**不写入** `config.json`：每次启动仍会先找真工程，找到就用真工程。
- `main.js` 启动改为 `resolveRootWithFallback()`，**保证有项目根**，编辑器在任何机器上可用。
- 渲染端 `init()` 去掉 early return：路径读取、包路径工具、包列表初始化全部**无条件执行**；
  即使主进程异常，界面也不会"看起来正常但点不动"。

### 新增 UI
- 顶栏 `🌱 独立工作区（未连接工程）` 标记 + `📁 连接工程…` 按钮（选择含 `contentpacks-source` 的项目根后重载）。
- 独立工作区下建包会提示"无 gradlew，构建需先连接工程"，避免误以为能直接出 jar。

## 2. 反馈闭环：启动即落日志 + 「🩺 诊断」

### 启动日志
日志原先在 `Logger.init(ROOT)` 后才写，而反馈机恰好死在项目根判定这一步 → **一行日志都没有**。
0.2.1 启动第一秒就写 `<userData>/logs/contentpack-editor/editor.log`
（Windows：`%APPDATA%\ContentPack Editor\logs\contentpack-editor\editor.log`），项目根确定后切换到
`<项目根>/logs/contentpack-editor/editor.log`，切换前后路径都记一条。

### 诊断按钮
顶栏新增 `🩺 诊断`：一键把版本 / 打包与否 / Electron 版本 / 项目根与来源 / 包库目录 / 配置文件 /
用户数据 / 当前与启动日志路径 / exe 与 cwd / portable 目录复制到剪贴板，反馈同学直接粘贴即可。

## 3. 一并带上 0.2.0 之后的 bbmodel 修复（已在 0.2.0-fixed 中验证，本版为正式版本号）

`.bbmodel` 的 X 镜像此前对 box 用了"点"的公式（`flip(from)`），每个 cube 整体偏移自身宽度，
导入 sword.bbmodel 时左右手各偏 4 像素 → 看起来"散架"。现按 Blockbench
`parseCube`/`compileCube` 的盒子公式换算（`from.x = -(origin.x+size.x)`），
9/9 cube 包围盒与 Blockbench 逐值一致、回导 Blockbench 10/10 数值相同；
同时补了内嵌贴图（`textures[].source` dataURL）读写。

## 4. 验证清单（0.2.1）

- [x] `npm test` 全绿（新增 `test/projectRoot.test.js` 8 项：配置优先 / 探测 / 工作区创建 / 幂等 /
      文档不可写回退 / 全失败 / 探测抛异常 / 判定口径）
- [x] 无工程模拟环境：顶栏出现「源码: <工作区>」、`🌱 独立工作区` 标记、三个按钮全部可点
- [x] 工作区里走完真实建包流程：`+ 新建 → probe-pack → 创建` → 包列表出现 `probe-pack`
- [x] `🩺 诊断` 一键复制成功，日志落盘含启动/切换/工作区来源记录
- [x] 有工程环境（本机）行为不变：`项目根来源: config`，包列表与之前一致

## 5. 反馈同学拿到 0.2.1 后怎么验证

1. 双击 exe → 顶栏应出现 `源码: C:\Users\<你>\Documents\Content Pack Editor\contentpacks-source`
   以及 `🌱 独立工作区（未连接工程）`。
2. 点「📦 包库」「➕ 新建包」→ 应弹出目录选择框（不再是"点了没反应"）。
3. 点左侧「+ 新建」→ 应展开建包表单；填包名点创建 → 左侧列表出现新包。
4. 点「🩺 诊断」→ 状态栏提示"已复制"，把剪贴板内容发回来即可远程定位。
5. 若想编辑 mod 里的内容包：点「📁 连接工程…」，选 `catoxides-battle-rebuild` 项目根目录。
