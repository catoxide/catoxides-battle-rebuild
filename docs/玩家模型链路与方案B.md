# 玩家模型 / 动画链路 + 「Spark 玩家模型 vs player_sword」区别 + 方案B（叠加层）实现

> 本文基于对 `libs/spark-core.jar`（Vineflower 反编译，源码在 `tools/_spark_src/`）和 MOD 源码的完整梳理。
> 目标：搞清现有玩家模型/动画到底用的什么、和我之前生成的 `player_sword` 有什么区别、以及"方案B 叠加层"具体怎么做。

---

## 0. 结论先行（TL;DR）

1. **现有"玩家动画"不是原版 `setupAnim`**，而是：
   **原版 `PlayerModel`（Java）负责渲染 + Spark 动画系统（`base_state.json`）负责驱动骨骼。**
   两者通过 `VanillaModelHelper.applyTransform` 对接：Spark 算出的骨骼姿态被"贴"到原版模型的 ModelPart 上。

2. **"Spark 玩家模型"（`minecraft:player`）和我之前的 `player_sword` 本质区别 = `ModelIndex.isPlayer()`**：
   - `isPlayer()` **只对 `(entity, minecraft:player)` 返回 true**。
   - true → `PlayerRendererMixin` **不取消**原版渲染 → **原版 PlayerModel 渲染**，Spark 只提供骨骼动画。
   - false（player_sword 属于这种）→ `PlayerRendererMixin` **取消原版渲染** → **Spark 几何整体替换**整个玩家（含嵌入的剑手臂链）。

3. **方案B（叠加层）完全可行，Spark 原生支持**：
   `AnimController` 有**多个图层**（`AnimGroups`：`STATE=1` / `DECOR=2` / `MAIN=4`）。
   `AnimController.blendBone(骨名)` **按骨合成所有图层**，每层用自己的 `BlendMode`：
   - `OVERRIDE`：`pos.lerp / rot.slerp / scale.lerp`（按权重向该层姿态靠近，权重高=更主导）
   - `ADD`：`pos.add / rot.mul / scale 加法`（叠加在当前姿态上）
   → 所以**可以给手臂单独开一个图层**：写一组只驱动 `rightArm`（或专用 `swordArm` 骨）的动画，放进独立组，和 base_state 按骨合成。

---

## 1. 链路（完整渲染 / 动画管道）

### 1.1 玩家本体

**渲染端**
- 渲染器是原版 `PlayerRenderer` + `PlayerModel`（Java 模型）。
- Spark-Core 的 `PlayerRendererMixin` 注入 `PlayerRenderer.render` 的 HEAD：
  ```kotlin
  val model = entity.getModelController().getModel()
  if (model != null && !model.getIndex().isPlayer()) {
      // 用 Spark 几何渲染整个实体
      ci.cancel()   // 取消原版渲染
  }
  ```
  - `model == null` 或 `isPlayer()==true`（即 `minecraft:player`）→ **走原版渲染**。
  - 其它 entity 模型（player_sword、zombie…）→ **Spark 几何整体替换**。

**骨骼驱动端（关键：不是原版 setupAnim）**
1. **状态机**：`SparkStateMachineRegister` 给每个 `Player` 注册 `PlayerBaseAnimStateMachine`（id `spark_core:player_base_state`）。
   - 这是一个 kstatemachine 状态机：`none → base → (land / crouch / swim / fly / climb / jump / …)`，`land` 下再分 `idle / move / move_back / sprint`。
   - 进入某个状态时调 `playRelativeAnim(animName)`，播放**相对状态路径**的动画，例如：
     `state.land.idle`、`state.land.move`、`state.land.sprint`、`state.crouch.*` …
   - 这些动画来自 **`base_state.json`**（在 `spark_core.default.zip` 里，`animations/minecraft/entity/player/base_state.json`）。
   - 默认放进 **STATE 组（group 1）**；特殊状态用 `AnimPayload` 指定组（如 jump 用 group 4 = MAIN）。
2. **骨骼姿态合成**：`AnimController.blendBone(boneName)` 遍历**所有图层**，按 `BlendMode` 合成出每根骨最终的 `KeyAnimData`（位置/旋转/缩放）→ 写入 `ModelInstance` 的 `ModelPose`。
3. **映射到原版模型**：`VanillaModelHelper.applyTransform(animatable, boneName, part, partialTicks)` 把 Spark 骨姿态贴到原版 `PlayerModel` 的对应 `ModelPart`：
   ```kotlin
   rot  = bonePose.getLocalPosition(t).mul(16f); rot.x=-rot.x; rot.y=-rot.y   // 位置×16，取反
   rot2 = bonePose.getLocalRotation(t);         rot2.x=-rot2.x; rot2.y=-rot2.y // 旋转取反
   part.offsetPos(rot); part.setRotation(rot2.x, rot2.y, rot2.z); part.xScale/yScale/zScale = ...
   ```
   → **原版 PlayerModel 的骨骼被 Spark 动画（base_state）驱动**，而不是原版 `setupAnim` 的行走/攻击硬编码。

> 所以"现有玩家动画" = **原版 PlayerModel 渲染 + Spark `base_state` 状态机动画驱动骨骼**。
> 走路/站立/冲刺这些动作来自 `base_state.json` 的 `state.land.*`。

### 1.2 手持物品（剑）—— 这就是你说的"单独的模型"

- 剑是**独立模型**：`item/sword.json`（32×32），自带 `hands / left / right / main / shade / blade / handle` 骨骼，其中 `right` 骨带一条**可见手臂 cube**（旋转 -90° 前伸）。
- 剑有**自己的动画体** `ItemAnimatable`（自己的 `ModelInstance` + `AnimController` + 动画集 `3rd_still / 3rd_attack / still / attack`），由 `IronSwordWeapon`（实现 `ICustomModelItem`）提供。
- **锚定**：`ItemInHandLayerMixin.renderArmWithItem`（HEAD，可取消）：
  ```kotlin
  if (entity is IEntityAnimatable && shouldSwitchToAnim(entity) && 原模型有 rightItem/leftItem 骨) {
      val bone = arm.serializedName + "Item"          // "rightItem" / "leftItem"
      val pose = model.pose.getBonePose(bone)
      p.mulPose(pose.getWorldBoneMatrix(t))           // 用该骨的【世界矩阵】定位
      p.translate(pivot.x, pivot.y-0.0625, pivot.z-0.109375)
      p.mulPose(Axis.XP.rotationDegrees(-90f))
      itemInHandRenderer.renderItem(..., p, ...)       // 再走物品渲染
      ci.cancel()
  }
  ```
  → 物品被锚到玩家模型的 **`rightItem`/`leftItem` 骨**（`player.json` 里专门的手部挂点，`rightItem` 是 `rightArm` 的子骨，枢轴 `[-5.5,12.5,0]`）。该骨的姿态由 Spark 动画驱动，所以**剑会跟着玩家的手臂动**。
- **物品渲染**：`GeoItemRenderer.renderByItem` 用 Spark 几何渲染剑，叠加 `ICustomModelItem.getRenderOffset / getRenderRotation / getRenderScale`（剑的骨骼姿态由剑自己的动画驱动）。
- **原版右臂处理**：`PlayerModelSwordArmMixin` 在持铁剑+右手系时，把原版 `rightArm`/`rightSleeve` **隐藏 + 旋转冻结为 0**（因为剑模型自带一条手臂，避免"两条右臂"和锚点漂移）。

> 所以"现在是一个单独的模型" = 剑是**独立物品模型**，锚到 `rightItem` 骨、有自己的手臂和动画，叠加在玩家本体之上。

### 1.3 我之前的 `player_sword`（整体替换路线）

- `SwordPlayerModelHandler`（`@EventBusSubscriber`，`ClientTickEvent`）：持铁剑+右手系时，
  `setModel((entity, catoxidesbattlerebuild:player_sword))` + 合成 96×64 贴图（皮肤 64×64 + 剑 32×32）。
- `player_sword` 的 `isPlayer()==false` → **整个玩家（身体+嵌入的剑手臂链 right/main/shade/blade/handle）由 Spark 几何渲染**，原版渲染被取消。
- 播放**固定动画**：`3rd_still`（MAIN，循环常驻）+ `3rd_attack`（MAIN，挥击 once）+ `state.land.idle/move`（STATE，看门狗按速度切换）。
- 卸剑/切左手 → `setModel(null)` 回原版。

> 这就是你说的"基于固定动画的整个玩家动画"——**整体替换**，不是叠加。

### 1.4 当前代码状态（git 核实）

| 文件 | git 状态 | 说明 |
|---|---|---|
| `weapon/IronSwordWeapon.java` | **M（改动）** | 原系统的手持物品逻辑，本次被改 |
| `spark_models/item/.../iron_sword.json` + `iron_sword/{attack,idle}.json` | **D（删除）** | 原系统的手持剑模型+动画（被改名） |
| `spark_models/item/.../sword.json` + `sword/*.json` | **??（新增）** | 手持剑模型改名后 |
| `client/SwordPlayerModelHandler.java` | **??（新增）** | 整体替换路线（本次新增，**未提交**） |
| `mixin/PlayerModelSwordArmMixin.java` | **??（新增）** | 隐藏+冻结原版右臂（本次新增） |
| `spark_models/entity/.../player_sword.json` + `player_sword/*.json` | **??（新增）** | 整体替换用的玩家实体模型+固定动画（本次新增） |
| `core/player/` | **??（新增）** | 本次新增目录 |
| `catoxidesbattlerebuild.mixins.json` | **M（改动）** | 注册了新 mixin |

**结论**：
- **已提交（稳定）的原系统 = 手持物品模型**（`iron_sword.json`）+ 原版 PlayerModel + Spark base_state 动画。这就是你说的"现在是一个单独的模型"。
- **未提交（本次实验）= `player_sword` 整体替换路线**（`SwordPlayerModelHandler` + `player_sword.json` + `PlayerModelSwordArmMixin`）。这是要**废弃/收敛**的方向。
- 方案B = 回到"叠加"路线并增强：玩家保持 `minecraft:player`，**给右臂加独立动画图层**，剑作为独立模型锚到 `rightItem` 骨。

---

## 2. 区别：Spark 玩家模型 vs 我之前的 player_sword

| 维度 | **Spark 玩家模型** `(entity, minecraft:player)` | **我之前的 player_sword** `(entity, catoxidesbattlerebuild:player_sword)` |
|---|---|---|
| `ModelIndex.isPlayer()` | **true**（硬编码只认 `minecraft:player`） | **false** |
| 谁渲染 | **原版 `PlayerModel`（Java）** | **Spark 几何**（整个玩家） |
| 骨骼由谁驱动 | Spark `base_state` 动画 → `applyTransform` → 原版 ModelPart | Spark 动画直接驱动 Spark 几何骨骼 |
| 皮肤/贴图 | 原版 64×64 玩家皮肤 | 96×64 运行时合成（皮肤+剑） |
| 剑从哪来 | **独立物品模型**，锚到 `rightItem` 骨 | **嵌入**玩家模型的骨骼链 |
| 动画 | `base_state`（走路/站立/冲刺…）+ 物品自己的动画 | 固定 `3rd_still`/`3rd_attack` + `state.*` |
| 本质 | **叠加层**：原版模型 + Spark 动画 + 独立剑模型 | **整体替换**：Spark 几何重写整个玩家 |

**一句话**：`isPlayer()` 是岔路口——
- `minecraft:player` → "保留原版渲染，只换动画驱动"（叠加）；
- 其它任何 entity 模型 → "整个玩家换成 Spark 几何"（替换）。

我之前的 `player_sword` 走的是**替换**路线，所以才会"重写整套玩家动画"。

---

## 3. 方案B（叠加层）怎么做

### 3.1 核心机制（已验证 Spark 原生支持）

- `AnimController.layers: Map<Int, AnimLayer>`，`playAnimation(anim)` 把动画放进 `anim.group` 对应的层。
- `AnimGroups`：`STATE=1`、`DECOR=2`、`MAIN=4`。
- `AnimController.blendBone(bone)` **按骨遍历所有层**合成：
  - 每层先 `layer.blendBone(bone)`（层内多动画按权重加权/slerp），再按该层 `BlendMode`：
    - `OVERRIDE`：`pos.lerp(目标, weight)`、`rot.slerp(目标, weight)`、`scale.lerp(目标, weight)`
    - `ADD`：`pos.add(目标)`、`rot.mul(目标)`、`scale` 加性叠加
  - 最后写回 `ModelPose`，由 `applyTransform` 贴到原版 ModelPart。
- **结论**：给手臂开一个独立图层（独立组），写一组**只驱动手臂骨**的动画，即可在 base_state（STATE 组）之上"叠加"手臂动作，互不干扰（只要不抢同一根骨，或用 OVERRIDE 高权重覆盖）。

### 3.2 具体步骤

1. **玩家保持 Spark 玩家模型**（`(entity, minecraft:player)`）：
   - 原版 `PlayerModel` 渲染 + `base_state` 驱动身体/腿/头/左臂（走路/站立/冲刺照旧）。
   - **停用 `SwordPlayerModelHandler`**（不要再切到 `player_sword` 整体替换）。

2. **新增"剑手臂"动画图层**（独立组，建议 `MAIN=4`）：
   - 写一组**只驱动 `rightArm` 骨**（或专用 `swordArm` 骨）的动画：持剑待机姿势 + 挥击。
   - 播放：待机循环 idle；挥击上升沿 → 挥击 once（播完回 idle）。
   - 与 base_state 的合成方式二选一：
     - `OVERRIDE`（推荐）：持剑时右臂**完全**由剑图层控制，覆盖 base_state 的行走摆动，干净利落。
     - `ADD`：剑动作**叠加**在行走摆动上（手臂会同时有走路摆+挥剑，可能不自然）。

3. **剑作为独立模型锚到手臂**：
   - 剑模型锚到 `rightItem` 骨（手，`rightArm` 的子骨），自动跟随手臂旋转。
   - 剑自己的挥击动画在**物品的 `ItemAnimatable`** 上播放（相对手）。
   - 剑模型建议做成**纯剑**（去掉自带手臂），让**原版右臂显示**并由剑手臂图层驱动——这才是"手臂是玩家的一部分 + 剑握在手里"。

4. **调整现有 hack**：
   - `PlayerModelSwordArmMixin`：现在"隐藏+冻结原版右臂"。方案B 下**不再隐藏右臂**（让右臂显示、由剑图层驱动）；只有当剑模型自带手臂时才需要隐藏。
   - `SwordPlayerModelHandler`：整体替换路线，**停用**。

### 3.3 实现时的关键修正（重要）

> ⚠️ 本节 §3.2 最初的设想是"玩家保持 `minecraft:player`（原版渲染）+ 剑独立模型锚 `rightItem`"。
> **实际不可行**，原因：

- `PlayerRenderer` 把手持物品渲染（`PlayerItemInHandLayer`）注册为**渲染层**（render layer），
  渲染层在 `super.render`（`LivingEntityRenderer.render`）里执行。
- `PlayerRendererMixin` 在玩家用 **Spark 几何**（`isPlayer()==false`）渲染时，在 `render` 的 HEAD
  **取消整个 render**（`ci.cancel()`）→ **所有渲染层（含 `PlayerItemInHandLayer`）都不执行**。
- 所以 `ItemInHandLayerMixin`（物品锚 `rightItem`）**不会被触发** → **独立物品模型不会渲染**。
- 若玩家保持 `minecraft:player`（`isPlayer()==true`），则走原版渲染，`ItemInHandLayerMixin` 能触发，
  但那样玩家就是原版 `PlayerModel`（无手腕/脚踝分节），不符合"要那个有手腕的模型"的要求。

**结论**：要同时满足 ①玩家是带手腕分节的 Spark 几何 ②剑可见，**剑必须嵌入玩家模型**
（作为 `rightItem` 子节点，随手臂动画走）。这正是老 `player_sword` 让剑可见的原因，但**动画改为叠加层**
（身体走 base_state、右臂走独立挥剑图层），不再是固定动画整体替换。

### 3.4 待确认 / 实现时细化

- **剑模型形态**：带手臂（现状）还是纯剑（锚 hand、显示原版右臂）？方案B 倾向**纯剑 + 原版右臂显示**。
- **手臂图层 BlendMode**：OVERRIDE（持剑时右臂全归剑图层）还是 ADD（叠加在行走摆动上）？建议 **OVERRIDE**。
- **锚点**：`rightItem`（手，跟随手臂旋转，适合"握剑"）vs 肩关节。建议 `rightItem`。
- **专用骨 vs 复用 `rightArm`**：
  - 复用 `rightArm`：直接驱动原版右臂 ModelPart（经 applyTransform），最省事。
  - 专用 `swordArm` 骨：需确认该骨能否映射到原版 ModelPart（`applyTransform` 按 `boneName` 查 `ModelPose`，再贴到对应 ModelPart；原版 PlayerModel 没有 `swordArm` 这个 part，可能不会被渲染）→ **优先复用 `rightArm`**。
- **左手系玩家**：现状 v1 不处理（主手在左手走原版渲染）。
- **两套系统并存冲突**：目前 `SwordPlayerModelHandler`（替换）+ 手持物品模型（叠加）+ `PlayerModelSwordArmMixin`（隐藏右臂）三者都在。方案B 需**收敛到叠加路线**，移除/停用替换路线，避免互相打架。

---

## 附：关键源码位置（反编译后）

- 渲染岔路：`tools/_spark_src/cn/solarmoon/spark_core/mixin/animation/PlayerRendererMixin.java`
- `isPlayer()` 定义：`tools/_spark_src/cn/solarmoon/spark_core/animation/model/ModelIndex.java`
- Spark 骨姿态→原版 ModelPart：`tools/_spark_src/cn/solarmoon/spark_core/animation/vanilla/VanillaModelHelper.java`（`applyTransform`）
- 玩家状态机（播放 base_state）：`tools/_spark_src/cn/solarmoon/spark_core/state_machine/presets/PlayerBaseAnimStateMachine.java`
- 状态机注册：`tools/_spark_src/cn/solarmoon/spark_core/registry/common/SparkStateMachineRegister.java`
- 图层合成（blendBone / BlendMode）：`tools/_spark_src/cn/solarmoon/spark_core/animation/anim/AnimController.java` + `AnimLayer.java`
- 组常量：`tools/_spark_src/cn/solarmoon/spark_core/animation/anim/AnimGroups.java`（STATE=1/DECOR=2/MAIN=4）
- 手持物品锚定（rightItem 骨）：`tools/_spark_src/cn/solarmoon/spark_core/mixin/animation/ItemInHandLayerMixin.java`
- 物品渲染：`tools/_spark_src/cn/solarmoon/spark_core/animation/renderer/GeoItemRenderer.java`
- Spark 默认玩家模型（含 rightItem/leftItem 挂点）：`tools/_spark_default/models/minecraft/entity/player.json`
- 玩家基础动画（state.land.idle/move…）：`tools/_spark_default/animations/minecraft/entity/player/base_state.json`

---

## 4. 最终实现（方案B 叠加层，已落地）

> 实际落地的实现（与 §3.2 初设想不同，见 §3.3 修正）。**玩家 = 分节手腕 Spark 几何，剑嵌入，动画分两层。**

### 4.1 玩家模型：`player_wrist.json`（Spark 几何，isPlayer()=false）

- 由 `tools/gen_wrist_player_sword.js` 生成（pack + src 各一份）。
- 骨结构（pivot/cube 均在模型全局空间）：
  - `root → body[0,16,0] → waist[0,12,0]`（躯干 8×12×4）`→ head[0,24,0]`（8³）
  - `waist → leftArm[5,22,0] → leftForeArm[5,18,0] → leftItem[5,12,0]`（左臂分节：上臂+前臂+手腕挂点）
  - `waist → rightArm[-5,22,0] → rightForeArm[-5,18,0] → rightItem[-5,12,0]`（右臂分节）
  - `body → leftLeg[1.9,12,0] → leftForeLeg[1.9,6,0] → leftFoot[1.9,0,0]`（左腿分节：大腿+小腿+脚）
  - `body → rightLeg[-1.9,12,0] → rightForeLeg[-1.9,6,0] → rightFoot[-1.9,0,0]`（右腿分节）
- **标准 64×64 玩家皮肤 UV**：手臂/腿侧面行各拆上下两半（h=12 → 上6+下6），顶/底面复用原区域。
  符号规则（mirror=false，匹配 Java 渲染）见 `gen_wrist_player_model.js`。
- **剑嵌入**：`sword.json` 的 `main` 子树（main/shade/blade/handle）经刚体变换 M' 挂到 `rightItem`：
  - `M'` 的旋转 `A=[[-1,0,0],[0,0,1],[0,1,0]]`（= 老 player_sword 的 M 旋转，剑朝向=原版持剑朝向，轴对齐→cube 精确）。
  - 平移 `b' = 手腕 − A·mainPivot`，**锚 `main` 骨 pivot 到 `rightItem`（手腕 [-5,12,0]）**（老 M 锚肩，这里改锚手腕）。
  - 点 `p'=A·p+b'`；向量 `v'=A·v`；旋转 `R'=A·R·A⁻¹`（共轭）。
  - 结果：`main` pivot 精确落在手腕 [-5,12,0]，剑**水平指前（+Z）**，随 `rightItem`（手腕）走。
- **复合贴图 96×64**：皮肤 64×64 @ (0,0) + 剑 32×32 @ (64,0)；剑 cube 的 face-UV u 已 +64（模型 `texture_width=96`）。

### 4.2 剑物品模型：`sword.json`（纯剑，仅第一人称/GUI 用）

- `tools/gen_pure_sword.js` 生成：去掉 `right` 骨的手臂 cube（`cubes:[]`）、删 `left` 控制器骨，
  保留 `hands/right/main/shade/blade/handle`。32×32。
- 第一人称 + 背包/GUI 走 `sword.json` + `GeoItemRenderer`（`IronSwordRenderer`）+ 物品动画（still/attack/3rd_still/3rd_attack）。
- **第三人称不渲染 sword.json**（见 §3.3），第三人称的剑 = 嵌入在 `player_wrist` 里的那把。

### 4.3 动画（两层，AnimGroups 按骨合成）

| 组 | 动画 | 驱动骨 | 说明 |
|---|---|---|---|
| STATE(1) | `state.land.idle` / `state.land.move` | 身体/腿/头/左臂（**已删 rightArm**） | 来自 `base_state`（`tools/gen_wrist_base_anim.js` 抽取），走路/站立照旧；每 tick 看门狗按速度切换 |
| MAIN(4) | `sword_arm_idle`（循环）/ `sword_arm_attack`（once） | `rightArm` + `rightForeArm`（→ `rightItem`+剑 跟随） | 挥剑图层（`tools/gen_wrist_sword_arm_anim.js`）：idle 抬臂持剑；attack 前抬量 52→72→22→52 = **剑尖先升后降** |

- 图层顺序：MAIN(4) 在 STATE(1) 之后处理 → 对 `rightArm` 用 OVERRIDE 权重 1 覆盖 base_state（base_state 的 rightArm 关键帧已删，双重保险）。
- 剑是 `rightItem` 子节点 → 手臂图层驱动 `rightArm`/`rightForeArm` → `rightItem`+剑 跟随，**剑随手走**。

### 4.4 接入：`SwordPlayerModelHandler.java`（客户端）

- 持铁剑+右手系 → `setModel((entity, player_wrist))` + 构建/注册 96×64 复合贴图（`TextureManager.register(composite_<uuid>, dt)`）+ 播 `sword_arm_idle`(MAIN) + `state.land.*`(STATE)。
- 每 tick：挥击上升沿 → `stopAnimation(MAIN)`+`sword_arm_attack`；MAIN 层空 → 恢复 `sword_arm_idle`；STATE 按速度切 idle/move。
- 换皮肤 → 重建复合贴图；卸剑/左手/离开 → `stopAllAnimation`+`setModel(null)`+`dt.close()`。
- `IronSwordWeapon.getRenderOffset` 归零（第三人称剑已嵌入，不再需要肩锚偏移；第一人称另行微调）。

### 4.5 废弃 / 移除

- `player_sword.json` + `player_sword/` 动画（pack + src）已删除。
- `PlayerModelSwordArmMixin`（隐藏+冻结原版右臂）已删除 + 从 `mixins.json` 移除（玩家走 Spark 几何，原版 PlayerModel 不用）。
- `tools/gen_player_sword_model.js` 保留作参考（M 变换的推导来源）；新嵌入逻辑在 `tools/gen_wrist_player_sword.js`。

### 4.6 姿态要求 → 实现对照

| 要求 | 实现 |
|---|---|
| 剑尖 y 高于剑柄 | 剑水平指前（+Z）嵌腕，抬臂图层（ra X=-52）把整臂+剑抬起 → 剑尖（blade 末端）高于剑柄（handle） |
| 剑尖在剑柄前方 | blade 沿 +Z 延伸到 z≈17.8（剑柄在 z≈0-5）→ 尖更靠前 |
| 挥动剑尖先升高再降低 | `sword_arm_attack` 前抬量 52→72(峰)→22(谷)→52，腕（剑）先上后下 |
| 手臂最低点朝上 | `sword_arm_idle`/`attack` 全程 ra X ≤ -22（手臂抬起，不下垂到最低） |

> **待进游戏验证 + 微调**：`gen_wrist_sword_arm_anim.js` 的 `TUNE` 角度是初估，
> 进游戏看四个姿态是否成立，按需调 `TUNE.idle` / `TUNE.attack`。
