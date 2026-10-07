package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.combat.SwordCombat;
import com.catoxide.catoxidesbattlerebuild.weapon.IronSwordWeapon;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 剑近战 mixin（对标 ArrowHitMixin 的原理）。
 * <p>主手是模组剑时，在 {@code Player.attack} HEAD {@code ci.cancel()} 切断整条原版近战链
 * （伤害/击退/横扫/耐久/音效），由 {@link SwordCombat} 接管：
 * <ol>
 *   <li>{@code SwordCollision}：距离 + 命中骨骼选择（与弓同源骨骼数据）；</li>
 *   <li>CombatSystem 管线：命中骨骼单位 → 部位模块血量 → 致命部位摧毁 = 死亡；</li>
 *   <li>总伤害经 target.hurt 回基础血量（hurt mixin 见 flag 跳过，防二次处理）；</li>
 *   <li>耐久/击退/音效/饥饿自管。</li>
 * </ol>
 * <p>不影响挥砍动画：1.21.1 的 swing 标记由客户端输入路径（doAttack → swing(MAIN_HAND)）设置，
 * 与 attack() 内部无关，HEAD cancel 不杀动画。
 * <p>不影响交互路由：点实体 = 攻击、点方块 = 挖掘，挥剑不会破坏方块。
 * <p>取代旧 PlayerAttackMixin（07-18 丢失 cancel 后与 WeaponCollisionListener 双重伤害，均已移除）。
 */
@Mixin(Player.class)
public abstract class SwordAttackMixin {

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void onSwordAttack(Entity target, CallbackInfo ci) {
        Player player = (Player) (Object) this;
        if (!(target instanceof LivingEntity)) {
            return;
        }
        if (!(player.getMainHandItem().getItem() instanceof IronSwordWeapon)) {
            return;
        }

        // 保留原版豁免：对原版不会造成伤害的目标（创意模式/非 PvP 玩家等）不接管，走原版链
        if (target.isInvulnerableTo(target.damageSources().playerAttack(player))) {
            return;
        }

        // 切断整条原版链（同弓 mixin 的 onHitEntity cancel 原理）
        ci.cancel();

        // 客户端：不做本地预测（伤害全由服务端判定；挥砍动画由 swing 驱动，不受影响）
        if (player.level().isClientSide) {
            return;
        }

        SwordCombat.processSwordAttack(player, (LivingEntity) target);
    }
}
