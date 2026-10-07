package com.catoxide.catoxidesbattlerebuild.core.combat;

import com.catoxide.catoxidesbattlerebuild.core.collision.SwordCollision;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyUnit;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.util.Mth;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 剑近战战斗处理（对标 {@code ArrowHitMixin.processCombat} 的原理）。
 * <p>由 {@code SwordAttackMixin} 在切断原版攻击链后调用（仅服务端）。
 * <p>流程：
 * <ol>
 *   <li>{@link SwordCollision}：距离 + 命中骨骼选择（与弓同源骨骼数据）；</li>
 *   <li>基础伤害 = 原版公式（攻击强度曲线 + 武器加成 + 暴击 1.5×）；</li>
 *   <li>命中骨骼单位进 {@link CombatSystem} 管线（穿甲/贯穿 → 部位模块血量 → 致命部位摧毁 = 死亡）；</li>
 *   <li>总HP伤害 = Σ(部位伤害×{@code transmissionToBase}) 经 {@code target.hurt} 扣基础血量
 *       （hurt mixin 见 flag 跳过，原版护甲计算仍生效）；</li>
 *   <li>耐久/击退/音效/饥饿自管（原版链已被 cancel）。</li>
 * </ol>
 * <p>TODO 数据驱动：剑参数（穿甲/贯穿/基础伤害）一期硬编码，后续走 weapon 配置。
 */
public final class SwordCombat {

    private static final String TAG = "TEST-SwordHit";

    /** 标记：当前正在处理剑攻击，LivingEntityHurtMixin 应跳过（防二次处理，弓的对应 flag 在 ArrowCombatState） */
    private static final AtomicBoolean IS_PROCESSING_SWORD_HIT = new AtomicBoolean(false);

    // 剑近战参数（一期硬编码，可调；数据驱动后做）
    private static final float SWORD_DAMAGE_MULT = 1.5f;      // 剑基础伤害倍率（模组剑比原版武器重，可调）
    private static final float SWORD_ARMOR_PENETRATION = 8.0f; // 穿甲（调高→对高甲部位伤害更高）
    private static final float SWORD_PIERCING = 5.0f;          // 贯穿（弓同款机制：扇形碰撞箱选出的触及部位按距离排序进管线，
                                                               // 首部位 100%，其后按剩余贯穿/初始贯穿逐部位衰减，贯穿耗尽停止）

    private SwordCombat() {
    }

    public static boolean isProcessingSwordHit() {
        return IS_PROCESSING_SWORD_HIT.get();
    }

    /**
     * 剑攻击处理入口（仅服务端，由 SwordAttackMixin 在 ci.cancel() 后调用）。
     */
    public static void processSwordAttack(Player attacker, LivingEntity target) {
        IS_PROCESSING_SWORD_HIT.set(true);
        try {
            // 1. 碰撞检测：距离 + 命中骨骼选择
            SwordCollision.SwordHitResult hit = SwordCollision.checkHit(attacker, target);
            if (!hit.hit()) {
                LogManager.serverDebug(TAG, "[MISS] out of reach: target={}, distance={}", target.getId(), hit.distance());
                return;
            }

            // 2. 基础伤害（原版公式：强度曲线 + 武器加成 + 暴击）
            SwordDamage dmg = calculateDamage(attacker, target);
            if (dmg.baseDamage() <= 0.0F) {
                return;
            }

            // 3. 目标部位（剑/弓共用：CombatExecutor）
            List<BodyUnit> units = CombatExecutor.getTargetUnits(target.getId());

            // 无部位数据（原版生物/玩家）：不走管线，直接原版方式扣基础血量
            if (units.isEmpty()) {
                LogManager.serverDebug(TAG, "[FALLBACK] no body units for target={}, plain damage={}",
                        target.getId(), dmg.baseDamage());
                DamageSource src = target.damageSources().playerAttack(attacker);
                target.hurt(src, dmg.baseDamage());
                postEffects(attacker, target, dmg, true);
                return;
            }

            // 4. 命中单位：碰撞箱选出的触及部位（按深度升序，管线按序贯穿衰减）；
            //    空（骨骼数据缺失）回退整身
            List<BodyUnit> hitUnits = !hit.hitUnits().isEmpty() ? hit.hitUnits() : units;

            AttackProperties attack = AttackProperties.of(SWORD_ARMOR_PENETRATION, SWORD_PIERCING, dmg.baseDamage());
            LogManager.serverInfo(TAG, "[SWING] target={}, hitBones=[{}], baseDamage={}, fullCharge={}, crit={}",
                    target.getId(),
                    hitUnits.stream().map(BodyUnit::getBoneName).reduce((a, b) -> a + "," + b).orElse("(all)"),
                    dmg.baseDamage(), dmg.fullCharge(), dmg.critical());

            // 5–7. 共享执行（CombatExecutor）：管线 → 部位模块血量 → 总HP（TRANSMITTED = Σ部位伤×传递系数）→ 致命 kill
            CombatExecutor.Execution execution = CombatExecutor.execute(target,
                    target.damageSources().playerAttack(attacker),
                    CombatExecutor.BaseHpMode.TRANSMITTED, attack, hitUnits, TAG);
            boolean damaged = execution.damaged();

            // 8. 后处理：耐久/击退/音效/饥饿
            postEffects(attacker, target, dmg, damaged);

        } catch (Exception e) {
            LogManager.serverWarn("SwordCombat", "processSwordAttack failed: {}", e.getMessage());
        } finally {
            IS_PROCESSING_SWORD_HIT.set(false);
        }
    }

    /**
     * 原版攻击伤害公式（1.21.1 Player.attack 同款，不含横扫/附魔部分）。
     */
    private record SwordDamage(float baseDamage, boolean fullCharge, boolean critical) {
    }

    private static SwordDamage calculateDamage(Player attacker, LivingEntity target) {
        float f = (float) attacker.getAttributeValue(Attributes.ATTACK_DAMAGE);
        DamageSource src = target.damageSources().playerAttack(attacker);
        float f2 = attacker.getAttackStrengthScale(0.5F);
        f *= 0.2F + f2 * f2 * 0.8F;
        boolean fullCharge = f2 > 0.9F;
        f += attacker.getWeaponItem().getItem().getAttackDamageBonus(target, f, src);
        boolean crit = fullCharge
                && attacker.fallDistance > 0.0F
                && !attacker.onGround()
                && !attacker.onClimbable()
                && !attacker.isInWater()
                && !attacker.hasEffect(MobEffects.BLINDNESS)
                && !attacker.isPassenger();
        if (crit) {
            f *= 1.5F;
        }
        f *= SWORD_DAMAGE_MULT;
        return new SwordDamage(f, fullCharge, crit);
    }

    /**
     * 攻击后处理（原版链已被 cancel，这些全部自管）：
     * 耐久、击退、音效、饥饿、lastHurtMob。
     */
    private static void postEffects(Player attacker, LivingEntity target, SwordDamage dmg, boolean damaged) {
        if (damaged) {
            // 耐久（原版在 hurt 成功后调用 hurtEnemy；IronSwordWeapon 重写为 hurtAndBreak(1)）
            ItemStack stack = attacker.getWeaponItem();
            if (!stack.isEmpty()) {
                stack.hurtEnemy(target, attacker);
            }

            // 击退（原版公式：ATTACK_KNOCKBACK 属性 × 0.5，方向=玩家偏航；getKnockback 是 protected，
            // 这里直接取属性值——与 LivingEntity.getKnockback 的基础值一致，附魔修正后续再加）
            float kb = (float) attacker.getAttributeValue(Attributes.ATTACK_KNOCKBACK);
            if (kb > 0.0F) {
                target.knockback(kb * 0.5F,
                        Mth.sin(attacker.getYRot() * (float) (Math.PI / 180.0)),
                        -Mth.cos(attacker.getYRot() * (float) (Math.PI / 180.0)));
            }

            // 音效
            if (dmg.critical()) {
                attacker.level().playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(),
                        SoundEvents.PLAYER_ATTACK_CRIT, attacker.getSoundSource(), 1.0F, 1.0F);
                attacker.crit(target);
            } else if (dmg.fullCharge()) {
                attacker.level().playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(),
                        SoundEvents.PLAYER_ATTACK_STRONG, attacker.getSoundSource(), 1.0F, 1.0F);
            } else {
                attacker.level().playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(),
                        SoundEvents.PLAYER_ATTACK_WEAK, attacker.getSoundSource(), 1.0F, 1.0F);
            }

            // 饥饿
            attacker.causeFoodExhaustion(0.1F);
        }
        attacker.setLastHurtMob(target);
    }
}
