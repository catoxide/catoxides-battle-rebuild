# texture-editor — UI 贴图生产链（panels-only v1）

把「画一张 Minecraft 风格面板贴图」变成**可复现的纯函数**：`spec.json + 元素库 → render() → PNG`。
本目录是生成器（A 侧）+ 规整化元素库；分析/提炼由 `build-library.js` 从原版 jar 完成。

**核心保证：零覆盖输出与原版逐字节相同。** `presets/chest_54.json` 不带任何 `tokens` 渲染出的
176×222 PNG，与 `generic_54.png` 面板区逐字节一致（`test/render.test.js` 断言，mismatch=0）。

---

## 日常只碰两个文件

| 文件 | 改什么 | 效果 |
|---|---|---|
| spec 的 `tokens` | 角色→颜色 | 全图重着色（只改既有角色，不新增） |
| spec 的 `layers` | 几何/结构 | 改 `rect`/`origin`/`pitch`/增删层 |

默认值 = 原版。不写 `tokens` 就是原版色。回退 = 回退 spec 一行，不存 PNG 历史。

---

## CLI（AI 驱动入口，纯 Node，零 Electron）

```bash
node cli.js render   <spec.json> <out.png> [--lib 名=目录]...
node cli.js validate <spec.json>            [--lib 名=目录]...
node cli.js preview  <spec.json> <out.png> [--scale 4] [--lib 名=目录]...
node cli.js diff     <a.png> <b.png> <out.png>
```

- 默认自动加载库 `vanilla` = `library/vanilla-1.21.1`；`spec.refs` 的「名→目录」相对 spec 文件解析；`--lib 名=目录` 覆盖。
- 校验前置：非法 spec 直接 `ERROR: ...` + exit 1，**不产生半成品 PNG**。错误带 `layers[i]("id").字段`，可直接定位。
- `preview` = nearest 整数倍放大（默认 4×），只给人看，不参与打包。
- `diff` = 逐像素差异，差异染品红，打印 count + 前 20 坐标。

AI 工作流：生成粗 spec → `validate` → `render` → `preview`/`diff` 评审 → 改 spec 重来。

---

## spec 契约（schema 1）

```jsonc
{
  "schema": 1,
  "canvas": [176, 222],                 // 1× 逻辑像素，正整数
  "refs": { "vanilla": "相对目录" },     // 可选；缺省用内置 vanilla
  "tokens": { "body": "#D8C8A0" },      // 可选；只覆盖既有角色
  "layers": [ /* 按数组顺序叠加，后写覆盖先写；每层必须有唯一 id */ ]
}
```

### 四原语（只有这四个，REQ §3.2）

| type | 字段 | 语义 / 校验 |
|---|---|---|
| `nine_slice` | `id`, `ref:"库/panel"`, `rect:[x,y,w,h]` | 四角逐像素拷贝 + 四边 nearest 复制拉伸 + 中心填充。`rect` 须在 canvas 内；`cornerSize*2 < min(w,h)` |
| `grid` | `id`, `stamp:"库/精灵"`, `origin:[x,y]`, `pitch`, `cols`, `rows` | 格点批量戳同一精灵。`pitch>=精灵宽高`；`cols/rows>=1`；整区不越界 |
| `rect` | `id`, `rect:[x,y,w,h]`, `color:"角色名"` | 实心矩形。`color` 必须是已知角色；`rect` 在 canvas 内 |
| `stamp` | `id`, `stamp:"库/精灵"`, `at:[x,y]` | 单次戳精灵。不越界 |

不提供：任意路径、渐变、旋转缩放、文字。缺什么加「精灵」，不加原语。
缩放/拉伸一律 **nearest**（整数行/列复制），永不插值。

### tokens 规则

- key 必须是元素库 `roles` 里已有的角色（`transparent/border/bevelLight/bevelDark/body/slotBg/slotDark`）。
- value 为 `#RRGGBB` 或 `null`（透明）。
- 换色只影响引用该角色的像素（`nine_slice` 的 chrome、`rect`）。`grid`/`stamp` 戳的是原始精灵，v1 不重着色。

---

## 元素库格式（`library/vanilla-1.21.1/elements.json`）

由 `build-library.js` 从原版 jar 提炼，**不要手改**；要改就改 `build-library.js` 重跑。

```jsonc
{
  "schema": 1,
  "source": "minecraft-1.21.1",
  "jarSha1": "e13934425b90b23f0be5c780df074dee6d8eca52",
  "roles": { "transparent": null, "border": "#000000", "bevelLight": "#FFFFFF",
             "bevelDark": "#555555", "body": "#C6C6C6", "slotBg": "#8B8B8B", "slotDark": "#373737" },
  "panel": {
    "symbols": { ".": "transparent", "#": "border", "W": "bevelLight", "5": "bevelDark", "B": "body" },
    "cornerSize": 4,
    "corners": {                        // 画布朝向，逐像素，保留原版不对称
      "TL": ["..##", ".#WW", "#WWW", "#WWW"],
      "TR": ["#...", "W#..", "WB#.", "B55#"],
      "BL": ["#WWB", ".#B5", "..#5", "...#"],
      "BR": ["555#", "555#", "55#.", "##.."]
    },
    "edges": { "top": ["#","W","W","B"], "bottom": ["B","5","5","#"],
               "left": ["#","W","W","B"], "right": ["B","5","5","#"] },
    "center": "B"
  },
  "sprites": { "slot": { "file": "sprites/slot.png", "size": [18, 18] } }
}
```

- 角色色由「位置+频次」从原件 ring 分析判定，不硬编码。
- 四角**不对称是原版风格**（调研已证），逐像素保留，不做「统一圆角」。
- `sprites/slot.png` 为原版逐字节拷贝（sha1 `6cc26189…`）。
- 解析链：`symbol → role → (tokens 覆盖) hex → RGBA`。

---

## 模块与测试

| 文件 | 职责 | 纯函数 |
|---|---|---|
| `core/png.js` | PNG 编解码（bitDepth 1/2/4/8，colorType 0/2/3/4/6） | ✔ |
| `core/library.js` | 元素库校验 + 角色色解析 | ✔ |
| `core/spec.js` | spec 解析 + 校验（响亮错误） | ✔ |
| `core/render.js` | 四原语渲染 → RGBA | ✔ |
| `core/imageops.js` | nearest 放大 + diff | ✔ |
| `io.js` | Node 侧文件/jar 读取（core 之外） | ✘ |
| `build-library.js` | 从 jar 提炼元素库 + 内置逐字节自检 | ✘ |
| `cli.js` | 无头入口 | ✘ |

```bash
node test/png.test.js      # 编解码
node test/spec.test.js     # 校验（自包含，无 jar）
node test/render.test.js   # 逐字节复刻 + 换色隔离 + 确定性（需原版 jar）
node build-library.js      # 重新提炼元素库（自检 mismatch 必须 0）
```

`core/` 铁律：零 DOM、零 Electron，双模式（Node `require` / 浏览器 `self.*`），可脱离 Electron 单测。