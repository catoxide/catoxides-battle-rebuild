# 整体玩家动作（全身/自定义动作）可行性调查

> 调查对象：Spark-Core 1.21.1（`Deprecated&References\Spark-Core-HotReload`）
> 问题：能否定义"整体玩家动作"（全身动画 / 自定义动作，含手位置变化）？怎么定义？
> 结论：**能，完全可行。** 下面是机制、定义方式、以及对本剑 mod 的落地建议。

---

## 1. 结论（TL;DR）

Spark 把 `Player` 实体 **Mixin patch 成动画体（`IEntityAnimatable`）**，并自带一套
**默认玩家模型 + 全身动画 + 动画状态机**（`spark_modules/spark_core.default`）。
所以"整体玩家动作"不是 hack，而是 Spark 的一等公民能力：

- 可以**替换玩家模型**（全身骨骼模型）；
- 可以**定义全身动画**（walk/run/attack/自定义招式/emote…）；
- 可以用**状态机**或**代码（AnimController API）**触发这些动作；
- 动作可以**任意移动骨骼**（包括手/手臂的位置变化）。

---

## 2. 机制拆解（源码依据）

### 2.1 玩家实体被 patch 成动画体
`mixin/animation/animatable/PlayerMixin.java`：
```java
@Mixin(Player.class)
public abstract class PlayerMixin extends LivingEntity implements IEntityAnimatable<Player> {
    private final AnimController animController = new AnimController(player);
    private final ModelController modelController = new ModelController(player);
    // getAnimatable() / getAnimController() / getModelController() / getAnimLevel()
}
```
→ 每个 `Player` 都持有 `AnimController`（驱动动画）+ `ModelController`（选择模型）。

`PlayerModelMixin.java` 进一步把原版 `PlayerModel` 的部件（leftSleeve/rightSleeve/jacket/hat/cloak）
暴露成可变换部件（`VanillaModelHelper.setRoot` / `ITransformModelPart.setPivot`），
即**不改模型也能动原版玩家模型的各部件**。

### 2.2 动画体接口
`animation/IAnimatable.kt`：
```kotlin
interface IAnimatable<T> {
    val animatable: T          // 持有者（实体）
    val animLevel: Level?
    val defaultModelIndex: ModelIndex
    val animController: AnimController   // 动画控制器
    val modelController: ModelController // 模型控制器
    fun getWorldPositionMatrix(...): Matrix4f
    fun onBoneUpdate(event: BoneUpdateEvent) {}  // 每帧骨骼更新后可二次调整
}
```

### 2.3 动画控制器（触发 API）
`animation/anim/AnimController.kt` 关键方法：
- `playAnimation(anim: AnimInstance)` — 播放一个动画（加入某 layer）；
- `stopAnimation(group)` / `stopAllAnimation()` — 停止；
- `changeSpeed(time, speed)` — 限时改变整体播放速度；
- `blendBone(boneName)` — 跨 layer 混合某骨骼的 pos/rot/scale（支持 OVERRIDE / ADDITIVE）；
- `stateMachines` — 状态机集合（自动驱动，见 2.4）。

`AnimInstance` 的构造（见 `OAnimStateMachine`）：`animInstance(animatable, animName)`。
→ **代码触发自定义动作**的写法（伪代码，客户端）：
```kotlin
val player = /* LocalPlayer */
val anim = animInstance(player, "my_custom_action")!!   // 包里的动画名
player.animController.playAnimation(anim)
```

### 2.4 动画状态机（"动作"的形式化定义）
`animation/state/origin/OAnimStateMachine.kt`，JSON 结构：
```jsonc
{
  "initial_state": "idle",
  "states": {
    "idle":   { "animations": { "anim.idle": 1.0 }, "blend_transition": 0.2,
                "transitions": { "move": "<条件表达式>" } },
    "move":   { "animations": { "anim.move": 1.0 }, "blend_transition": 0.15,
                "transitions": { "idle": "<条件表达式>" } }
  }
}
```
- 每个**状态** = 一组动画 + 权重 + 混合时长；
- **转移** = 条件表达式（满足则切状态）；
- 状态机每 tick 自动评估（`AnimStateMachine.tick()`）。

> 注：参考副本里 transition 条件的求值是**注释掉的桩**
> （`OAnimStateMachine.build` 第 70 行 `//if (condition.eval(exp)) ...`），
> 说明"条件驱动"在当前版本可能不完整；**代码触发（2.3）是最稳的路径**。

### 2.5 默认玩家包（现成范例）
`spark_modules/spark_core.default/`：
- `models/minecraft/entity/player.json`、`steve.json` — 玩家模型；
- `animations/minecraft/entity/player/base_state.json`（3883 行，含 `state.land.idle`、
  `state.land.move` 等**全身**动画：waist/head/leftArm/rightArm/leftLeg/rightLeg）、
  `use_anim.json`；
- `animations/minecraft/entity/empty/empty.json`。

→ 直接照抄这套结构，就能给玩家加全身动作。

---

## 3. 怎么定义一个自定义玩家动作（步骤）

1. **模型**：准备一个全身骨骼模型（或直接用默认 `player.json`/原版模型部件）。
   放到包 `models/minecraft/entity/player.json`（或自定义 identifier）。
2. **动画**：在 Blockbench 里做全身关键帧，导出为 Spark 动画 JSON，
   放到 `animations/minecraft/entity/player/<name>.json`（注意 Spark 加载期变换约定：
   位置 ×(−1,1,1)/16、旋转 ×(−1,−1,1)→弧度，见 `convert_sword_bbmodel.js` 的 `--flip-x`）。
3. **触发**：
   - **代码触发（推荐、最稳）**：监听按键/事件 → `animController.playAnimation(animInstance(player, "<name>"))`；
   - **状态机（若条件求值可用）**：在 `anim_state` 里写状态+转移条件。
4. **手位置变化**：动画里直接对 `rightArm`/`leftArm`/手部骨骼做关键帧即可——
   状态机/AnimController 会对**任意骨骼**做 pos/rot/scale 混合，手随便动。

---

## 4. 对"剑 mod"的落地建议（关键难点）

你的场景是**全身动作 + 手持剑**要协调。难点在于**身体动画（玩家模型）与剑动画
（物品模型）是两套独立的 `AnimController`，需要时间同步**。可选方案：

- **方案 A（推荐起步）**：身体用 Spark 默认/自定义玩家动画，剑用现有物品动画；
  用**代码**在同一个事件里同时 `playAnimation` 两套动画（同 t0 起播），
  并在 `onBoneUpdate` 里把剑的手部骨骼对齐到玩家手部骨骼（程序化跟随），
  这样"手"始终贴合，剑绕手挥。
- **方案 B（进阶）**：把剑**并入玩家模型**（剑作为玩家模型的一个骨骼/子骨），
  这样一套动画同时驱动身体+剑，天然同步；代价是要维护一个"带剑的全身模型"。
- **方案 C（emote 类）**：纯展示动作（不攻击），直接代码触发全身动画即可，
  不涉及碰撞/同步，最简单。

> 与当前 TP 剑对齐的关系：TP 里"手"的位置变化，正是方案 A 里
> `onBoneUpdate` 程序化跟随要解决的问题（见 `IronSwordWeapon` 的逐帧 offset 思路）。

---

## 5. 参考资料索引

| 资料 | 位置 | 用途 |
|---|---|---|
| 玩家 Mixin | `mixin/animation/animatable/PlayerMixin.java` / `PlayerModelMixin.java` | 玩家如何变成动画体 |
| 动画体接口 | `animation/IAnimatable.kt` | 接口定义 |
| 动画控制器 | `animation/anim/AnimController.kt` | 触发 API |
| 状态机 | `animation/state/origin/OAnimStateMachine.kt` | 动作形式化 |
| 默认玩家包 | `spark_modules/spark_core.default/` | 现成玩家模型+动画+状态机 |
| 实体转换范例 | `D&R/zombie-1 - Converted.geo.json` + `zombie.animation (1).json` | 全身实体模型+动画转换样例 |
| GeckoLib 源码 | `D&R/geckolib-1.21.1` | 同类动画库参考（实体动画/事件） |
| 物品动画 | `animation/ItemAnimatable.kt` | 手持物动画（剑走这条） |
