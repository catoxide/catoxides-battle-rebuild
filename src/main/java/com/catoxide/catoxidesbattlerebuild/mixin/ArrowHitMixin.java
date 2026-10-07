package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.combat.ArrowCombatHandlers;
import com.catoxide.catoxidesbattlerebuild.core.combat.IArrowCombatHandler;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.entity.projectile.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 弓箭门（R1：查询 + 委托 + 回退，无任何机制逻辑）。
 * <p>自定义轨迹物理/骨骼碰撞/伤害处理全部在 {@link IArrowCombatHandler} 实现里
 * （M5 为 core 的 {@code ArrowCombatHandler}，M6 起由战斗内容包提供）。
 * <p>R3：{@link ArrowCombatHandlers} 为空时不介入 —— 箭矢 = 原版行为
 * （原版物理 + AABB 伤害）。
 */
@Mixin(AbstractArrow.class)
public abstract class ArrowHitMixin {

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void onArrowTick(CallbackInfo ci) {
        for (IArrowCombatHandler handler : ArrowCombatHandlers.getAll()) {
            if (handler.onTick((AbstractArrow) (Object) this)) {
                return;
            }
        }
    }

    @Inject(method = "onHitEntity", at = @At("HEAD"), cancellable = true)
    private void onArrowHit(EntityHitResult result, CallbackInfo ci) {
        for (IArrowCombatHandler handler : ArrowCombatHandlers.getAll()) {
            if (handler.onHitEntity((AbstractArrow) (Object) this, result.getEntity())) {
                ci.cancel();
                return;
            }
        }
    }
}
