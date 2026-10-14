# ContentPack Editor — AI API 与 CLI

ContentPack Editor 内置一个**仅本机**的 HTTP JSON API，应用启动（项目根就绪）后自动监听；
`editor-cli.js` 是同一套命令的命令行客户端。两者执行**同一份核心代码**（`src/core/editorApi.js`），
与 UI 的 IPC 处理器共享 `src/core/*` 模块，结果一致。

## 何时用哪种方式

| 场景 | 方式 |
|---|---|
| 应用开着，AI 需要读写内容包 | HTTP API（与 UI 同进程） |
| 应用没开，脚本化批量操作 | CLI（自动直跑核心模块，无需 Electron） |
| CLI 调用时应用在开 | CLI 自动走 HTTP（无需关心） |

**性能**：空闲 = 一个监听 socket，0 CPU / 0 轮询 / 无定时器。每个请求 = 与 UI 点击相同的文件读写。

## 端口发现

应用启动后写状态文件：

```
<项目根>/logs/contentpack-editor/api.json
→ { "port": 48900, "pid": 12345, "root": "D:\\...", "startedAt": "..." }
```

默认 48900，被占用时自动 +1（最多 +10）。应用退出时删除该文件。

## HTTP 协议

```
GET  http://127.0.0.1:<port>/status
→ { "ok": true, "data": { "name", "version", "port", "pid", "root", "uptimeSec" } }

POST http://127.0.0.1:<port>/invoke
body: { "cmd": "entity.get", "args": { "pack": "test-mob-pack", "id": "modular_zombie_test" } }
→ { "ok": true, "data": ... }        HTTP 200
→ { "ok": false, "error": "..." }    HTTP 400（业务错误）/ 500（意外）
```

## CLI 用法

```
node tools/contentpack-editor/cli/editor-cli.js [--root <项目根>] <命令> [参数...]
```

```
包:
  packs list                       列出所有包（实体/模型/动画/契约/jar 摘要）
  pack show <name>                 包详情（MANIFEST + 摘要）

实体（contentpacks-source/<pack>/entities/<id>.json）:
  entity list <pack>               实体清单
  entity get <pack> <id>           读取实体
  entity new <pack> <id> [ns]      创建默认实体
  entity set <pack> <file|->       写入实体（JSON 文件/stdin，自动校验后落盘）
  entity rm <pack> <id>            删除实体

部位健康配置（health/<ns>/<id>.json）:
  health get <pack> <ns> <id>      读取（不存在 data=null）
  health set <pack> <ns> <id> <file|->   写入（自动校验）
  health set <pack> <id> <file|->  缺省 ns：从实体文件 namespace 自动推断

资产与契约:
  contract report <pack>           契约全量盘点（模型/动画/缺失/错误/警告）
  model bones <pack> <rel>         模型骨骼名（rel = 包内相对路径）
  anim show <pack> <rel>           动画摘要（loop/duration/bones）

其他:
  states                           8 状态机定义 + 模板
  build <pack>                     gradlew :packs:<pack>:contentpackJar
                                   产物 → contentpacks/<Module-Name>.jar（游戏加载它）
```

文件参数解析顺序：`'-'` = stdin → CWD/绝对路径 → 包内相对路径。
退出码：0 = ok，1 = 业务错误，2 = 项目根未定位。

## 典型 AI 工作流（改一个实体并让游戏生效）

```bash
node cli/editor-cli.js entity get test-mob-pack modular_zombie_test > /tmp/e.json
# …AI 修改 /tmp/e.json…
node cli/editor-cli.js entity set test-mob-pack /tmp/e.json
node cli/editor-cli.js build test-mob-pack     # 重建 jar（~15s，缓存命中时）
# 游戏 /summon testmob:modular_zombie_test 验证
```

## 约定与注意

- **写命令直接写正式包目录**（`contentpacks-source/`），不走 UI 的暂存区
  （暂存区是 UI 的草稿机制，API/CLI 场景无意义）。
- 游戏加载的是 **`contentpacks/*.jar`**，不是源码——改完 JSON 必须 `build` 才生效。
- 实体 JSON 格式 = 主 mod `MobDefinitionParser` 的输入（`src/core/mob/MobDefinitionParser.java`），
  `entity set` 会用同一套校验规则预检。
- 包内路径参数有越界防护（`safeJoin`），不会写出包目录。
- 仅绑定 127.0.0.1，无鉴权——本机 AI/脚本信任边界内使用。

## 扩展命令

新命令 = 在 `src/core/editorApi.js` 的 `commands` 对象加一个 handler（`(args, ctx) => data`，
抛错自动转 `{ok:false,error}`）+ CLI `parseCommand` 加一条映射。HTTP 与 CLI 同时生效。
