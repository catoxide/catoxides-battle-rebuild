# 第三人称（TP）剑渲染 —— 整体方案定义

> 范围：铁剑在**第三人称**下"看起来被握在手里"的统一定义。
> 配套：模型/动画转换见 `tools/convert_sword_bbmodel.js`；握把 offset 计算见
> `tools/compute_hand_offset.js`；代码见 `IronSwordWeapon.java`。

---

## 1. 一句话定义

> **TP 下：原版把模型 origin(0,0,0) 放在玩家手上；我们用 `getRenderOffset`
> 把"我们动画里手握住剑的那个点"挪回 origin，于是剑就贴在玩家手上。**
> 模型自带一条（透明的）手臂作为 3rd_* 动画的枢轴，剑挂在手臂末端。

---

## 2. 五个要素

| 要素 | 取值 / 机制 | 说明 |
|---|---|---|
| **模型** | `spark_models/item/<ns>/sword.json` | 含 `hands→(left/right)→main→(blade/handle/shade)`；手臂 cube 用透明 UV |
| **动画** | `spark_animations/item/<ns>/sword/3rd_*.json` | `3rd_still`(idle 循环) / `3rd_attack`(挥击) / `3rd_checkview` |
| **手可见性** | 默认 **arm 透明 + 对齐位置**（fallback） | 优先尝试把 arm 显示为手；若不可行退回透明对齐（当前采用） |
| **offset** | `getRenderOffset(TP) = -p_grip` | p_grip = 握把在模型根空间的位置（脚本算） |
| **触发** | `inventoryTick` 检测挥动上升沿 → 播 `3rd_attack` | idle 用 `3rd_still`（loop） |

---

## 3. offset 的对齐原理与标定

### 3.1 原理
- 渲染矩阵顺序（`GeoItemRenderer`）：
  `A(原版手部变换) · T(getRenderOffset) · R(rot) · S(scale) · ma_bone · vertex`
- 原版把模型 origin 放在玩家手上 → **手 = 模型根空间原点 (0,0,0)**。
- 我们动画里"手"（剑握把 = `main` 骨 pivot 链 `main←right←hands`）在模型根空间有固定偏移 `p_grip`。
- 故 `offset = -p_grip` 把握把挪回 origin（= 玩家手）。

### 3.2 标定方法（脚本，非手算）
```
node tools/compute_hand_offset.js [模型.json] [动画.json] [骨] [时间]
# 默认: sword.json 3rd_still.json main 0
```
脚本严格按 Spark 加载变换（位置 ×(−1,1,1)/16、旋转 ×(−1,−1,1)→弧度）+ 骨骼链连乘，
打印**完整推导**，输出 `p_grip` 与 Java 字面量。

### 3.3 当前值（已修正，手算独立验证）
- 脚本：`p_grip(3rd_still, t=0) = (0.3798, −0.4098, 0.9076)` → `offset = (−0.3798, +0.4098, −0.9076)`。
  （3rd_attack t=0 与其完全相同，起手姿势=静止姿势。）
- **历史 bug（已修）**：脚本旋转矩阵手性写反（左旋），把 right 骨 −60° 当成 +60°，
  旧值 y=−0.6890 差 1.1 格 → 经原版第三人称旋转 A 后表现为"剑跑到手后面"。
  修正后脚本输出与手算完全一致。
- **挥砍中握把漂移**（固定 offset 的已知局限，数据来自修正脚本采样）：

  | t | p_grip (模型根空间) | 相对手(=+offset) |
  |---|---|---|
  | 0.0 | (0.3798, −0.4098, 0.9076) | (0, 0, 0) ← 精确对齐 |
  | 0.1 | (0.0967, +0.7055, 0.7519) | (−0.28, **+1.12**, −0.16) |
  | 0.3 | (0.4297, −0.3096, 1.3241) | (+0.05, +0.10, **+0.42**) |
  | 0.5 | (0.3798, −0.4098, 0.9076) | (0, 0, 0) |

  固定 offset 时：剑+（透明）手臂绕 right 骨 pivot 旋转，该点在模型根空间
  (0.375, 0.125, 1.25)，相对手为 (−0.005, +0.53, +0.34)——**大致在玩家肩的位置**，
  所以挥砍弧线看起来像"绕肩挥"（合理，但手臂不可见、与原版胳膊不完全同步）。
- 两种走法：
  1. **固定 offset（当前）**：静止帧精确对齐；挥砍时剑绕"肩点"挥。先看游戏内效果。
  2. **逐帧 offset**（`getRenderOffset` 按当前 TP 动画时间算 `p_grip(t)`）→ 手恒贴、剑绕手挥。
     需把脚本算法搬进 Java 并跟踪动画时间（AnimInstance.getCurrentTime 等）。

### 3.4 自定义动作约束
- 只要动作里**握把相对模型根位置不变**（只挥剑、手不挪位）→ 一个固定 offset 对所有动作成立。
- 若某动作**真的移动了手**（抬手、换握位）→ 需对该动作重算 offset，或走逐帧方案。

---

## 4. 手可见性（"换成我们的手"）

- **首选**：把模型 arm cube 显示出来当手（替换原版胳膊）。
  - 需要：给 arm cube 真实贴图（当前是透明 UV）+ 让 arm 对齐玩家肩/手 + 盖住原版胳膊。
  - 障碍：原版胳膊是玩家模型的一部分，难单独隐藏；arm 贴图要为 7 材质各做一份。
- **fallback（当前采用）**：arm 保持透明，只用 offset 把剑对齐玩家手。
  - 优点：简单、稳；缺点：看不到"我们的手臂"。
- 用户意见：**做不到首选就退回 fallback**。

---

## 5. 贴图（可按物品单独设置）

- 机制：`ModelController.setTextureLocation(loc)`（默认由 ModelIndex 推导 `textures/item/sword.png`）。
- 钩子：`IronSwordWeapon.getTextureLocation(stack, level, context)` 返回 `ResourceLocation` 或 `null`（默认）。
  - 7 材质共用 `sword` 模型，靠此钩子按物品返回 `copper/golden/..._sword.png`。
- 自定义 2D 贴图本身由后续提供（不在本次范围）；本次只保证"能按物品单独设置"。

---

## 6. 待办 / 迭代点

1. **offset 标定**：游戏内看挥砍，调小 z（或上逐帧方案）。← 当前阻塞点
2. **FP 对齐**：第一人称 offset 暂 (0,0,0)，后续单独迭代。
3. **手可见性**：若要做"我们的手臂"，需 arm 贴图 + 隐藏原版胳膊（方案见 §4）。
4. **清理旧包残留**：`swords*`、`iron_sword_weapon`、`modular_zombie_2` 等（不同 ModelIndex，无害死文件）。
5. **逐帧 offset**（可选）：把 `compute_hand_offset.js` 算法搬进 Java，按 TP 动画时间算 offset。
