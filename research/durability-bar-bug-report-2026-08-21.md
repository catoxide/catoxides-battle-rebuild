# 耐久条与可变耐久不兼容 — Bug 报告与修正结论

- 日期: 2026-08-21
- 来源: 同工作区 `Neoforge\耐久\Durability_overhaul_Neoforge` 项目排查所得(已修复并验证编译/字节码)
- 状态: 耐久项目已修复(commit `52e21ac`),游戏内验证进行中;本项目参照下文做同样的修正
- 约束: 用户要求两个项目的修正**不要同时进行**(避免交叉污染/状态混乱),本项目可在耐久项目游戏内验证通过后动手

## 1. 症状

带可变耐久的物品(最大耐久被降过的),原版耐久显示条仍按**硬编码的原始最大耐久**计算:

- 实际(变量)耐久快耗尽时,条还显示"还剩许多"(甚至接近满条)
- 条可能不空,但实际耐久已经没了,再使用一次直接断

## 2. 1.21.1 耐久条的完整来源链(mojang 命名,已逐文件核实)

**所有条(槽位/快捷栏/拖拽浮动)都汇聚到 `ItemStack.getMaxDamage()` 这一个方法:**

```
① 容器槽位(背包/铁砧/合成/创造菜单等)
   AbstractContainerScreen.renderSlot ──────────────┐
② 底部快捷栏                                         │
   Gui(注: 1.21.1 中 InGameHud 已改名为              ├─→ GuiGraphics.renderItemDecorations(font, stack, x, y)
      net.minecraft.client.gui.Gui)                  │
③ 拖拽中的浮动物品                                    │
   AbstractContainerScreen.renderFloatingItem ──────┘

GuiGraphics.renderItemDecorations 内:
   if (stack.isBarVisible()) {
      条宽 = stack.getBarWidth()
      条色 = stack.getBarColor()
      fill(黑底) + fill(条色, 长度=条宽)
   }

Item.isBarVisible(stack)  = stack.isDamaged()
Item.getBarWidth(stack)   = clamp(round(13 - damage × 13 / stack.getMaxDamage()), 0, 13)   ← 关键
Item.getBarColor(stack)   = hsvToRgb(max(0, (stack.getMaxDamage() - damage) / stack.getMaxDamage()) / 3, 1, 1)  ← 关键
```

高级 tooltip 的 `Durability: X / Y` 行(`item.durability`)同样读 `stack.getMaxDamage()`。

**结论: 只要 mixin 链(mixin → capability → 同步组件)生效,所有条自动按变量耐久显示,不存在另一条独立的"硬编码"渲染路径。** 本项目现有的 `mixin/ItemStackMixin.java`(注入 `ItemStack.getMaxDamage()I`)设计上是覆盖全部条路径的,无需新增 mixin 目标。

## 3. 真正的根因(两层)

### 3.1 表层: mixin 链未生效时条退回硬编码
条宽/条色里的 `stack.getMaxDamage()` 默认实现是 `getOrDefault(DataComponents.MAX_DAMAGE, 0)` —— 即物品定义里的硬编码上限(如钻石镐 1561)。只要 mixin 注入或 capability 返回失败,条就退回硬编码比例 → 即观察到的症状。

### 3.2 深层(本项目同样存在,必须修): 兜底行调用 1.21.1 已删除的方法
`core/durability/VariableDurabilityCapability.java` 第 18 行:

```java
return stack.getItem().getMaxDamage(stack);   // ← Item.getMaxDamage(ItemStack) 在 1.21.1 不存在!
```

**MC 1.21.1 数据组件重构移除了 `Item` 上所有 `getMaxDamage` 方法**(max damage 迁移到 `DataComponents.MAX_DAMAGE` 数据组件)。证据(耐久项目三重验证):

1. 反编译 1.21.1 `net.minecraft.world.item.Item` 源码全文: 无 `getMaxDamage`,只剩 `isBarVisible/getBarWidth/getBarColor(ItemStack)`
2. `javap -p` 对实际运行用的 merged jar: `Item` 无 `getMaxDamage`
3. 符号存在性查询: not found

后果(二选一,都糟):
- 若干净重建: **编译直接失败**
- 若 build/classes 里留着旧版本编译的 class(增量编译不会重编未改动的文件):**运行时地雷** —— 任何没有 `custom_max_durability` 组件的耐久物品(即绝大多数物品)调用 `getMaxDamage()` 都会抛 `NoSuchMethodError`(这是 Error 不是 Exception,mixin 里没 try/catch 就会直接炸游戏;条渲染/tooltip/损坏判定全是高频调用点)

本项目 `mixin/ItemStackMixin.java` 的注入体同样**没有异常保护**,一旦链上任何环节抛错,`getMaxDamage()` 的所有调用方全部崩溃。

## 4. 修正方案(照抄即可,已在耐久项目验证)

### 4.1 `VariableDurabilityCapability.getMaxDamage` 兜底行

```java
// 修改前
return stack.getItem().getMaxDamage(stack);

// 修改后 (需要 import net.minecraft.core.component.DataComponents)
return stack.getOrDefault(DataComponents.MAX_DAMAGE, 0);
```

行为与旧版本等价(读物品原型定义的最大耐久),但只依赖 1.21.1 存在的 API。
**注意不要改成 `return stack.getMaxDamage()`** —— 那会走回 mixin → capability → 本方法,无限递归。

### 4.2 `ItemStackMixin` 注入体加异常保护

```java
private static final Logger LOGGER = LoggerFactory.getLogger("catoxides_battle_rebuild");
// 异常只记录一次, 避免刷屏(此注入点调用极其频繁)
private static final AtomicBoolean ERROR_LOGGED = new AtomicBoolean(false);

@Inject(method = "getMaxDamage()I", at = @At("HEAD"), cancellable = true)
private void catoxides$onGetMaxDamage(CallbackInfoReturnable<Integer> cir) {
    try {
        ItemStack self = (ItemStack) (Object) this;
        IVariableDurability cap = self.getCapability(DurabilityCapabilities.VAR_DURABILITY_CAP, null);
        if (cap != null) {
            int customMax = cap.getMaxDamage(self);
            if (customMax > 0) {
                cir.setReturnValue(customMax);
            }
        }
    } catch (Throwable t) {
        // 绝不向调用方抛错: getMaxDamage 被条渲染/tooltip/损坏判定等高频路径调用
        if (ERROR_LOGGED.compareAndSet(false, true)) {
            LOGGER.error("[CBR] 读取可变耐久失败, 已降级为原版行为", t);
        }
    }
}
```

异常时降级为原版行为(条按硬编码显示),游戏不崩,且日志留痕。

### 4.3 必须做干净全量重建

```
gradlew clean build
```

然后**用 javap 验证**新 class 不再含幽灵调用(这是耐久项目踩过的坑: 旧 class 混在运行构建里,编译"成功"但运行时炸):

```
javap -c -p build\classes\java\main\com\catoxide\catoxidesbattlerebuild\core\durability\VariableDurabilityCapability.class
# 确认兜底处是 ItemStack.getOrDefault(DataComponents.MAX_DAMAGE, 0), 而不是 Item.getMaxDamage(ItemStack)
```

## 5. 游戏内验证清单(修完后逐项过)

1. **条与实际耐久同步**: 拿一个被降过最大耐久的物品,磨到快断 → 条应接近空/全红,断的瞬间条正好空
2. **tooltip 一致**: F3+H 高级 tooltip `Durability: X / Y` 的 Y 是变量上限,条长/色与 X/Y 吻合
3. **混合背包不炸**: 背包里同时有"已降级物品(带组件)"+"普通耐久物品(不带组件)"+"方块食物",反复开关背包、划过每个物品 → 零崩溃
4. **修理后条更新**: 铁砧/合成台/经验修补修理后,条反映新耐久
5. 日志里不应出现 `读取可变耐久失败`(出现=链上还有问题,把日志拿出来看)

## 6. 参考实现(可直接对照)

- 项目: `Neoforge\耐久\Durability_overhaul_Neoforge`(git master)
- 修复 commit: `52e21ac` "fix: 修复 1.21.1 下可变耐久编译失败与显示链路隐患 (durability bar fix)"
- 文件:
  - `src/main/java/com/catoxide/durability_overhaul/capability/VariableDurabilityCapability.java`
  - `src/main/java/com/catoxide/durability_overhaul/mixin/ItemStackMixin.java`
- 1.21.1 原版条源码核实所用的文件(如需复核):
  - `net/minecraft/client/gui/GuiGraphics.java` → `renderItemDecorations`
  - `net/minecraft/world/item/Item.java` → `isBarVisible/getBarWidth/getBarColor`
  - `net/minecraft/world/item/ItemStack.java` → `getMaxDamage/getDamageValue/hurtAndBreak`
  - `net/minecraft/client/gui/Gui.java` → `renderHotbarAndDecorations`(快捷栏)
  - `net/minecraft/client/gui/screens/inventory/AbstractContainerScreen.java` → `renderSlot/renderFloatingItem`
