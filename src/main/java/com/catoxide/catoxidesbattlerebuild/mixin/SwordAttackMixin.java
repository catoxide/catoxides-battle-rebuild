package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.combat.ISwordAttackHandler;
import com.catoxide.catoxidesbattlerebuild.core.combat.SwordAttackHandlers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 剑近战门（R1：查询 + 委托 + 回退，无任何机制逻辑）。
 * <p>接管判定（模组剑/豁免/伤害/耐久/击退/音效）全部在 {@link ISwordAttackHandler} 实现里
 * （M5 为 core 的 {@code SwordAttackHandler}，M6 起由战斗内容包提供）。
 * <p>处理器接管时 {@code ci.cancel()} 切断整条原版近战链；
 * 挥砍动画由客户端输入路径的 swing 标记驱动，cancel 不影响动画。
 * <p>R3：{@link SwordAttackHandlers} 为空时不 cancel —— 武器 = 原版行为。
 */
@Mixin(Player.class)
public abstract class SwordAttackMixin {

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void onSwordAttack(Entity target, CallbackInfo ci) {
        for (ISwordAttackHandler handler : SwordAttackHandlers.getAll()) {
            if (handler.tryHandle((Player) (Object) this, target)) {
                ci.cancel();
                return;
            }
        }
    }
}
