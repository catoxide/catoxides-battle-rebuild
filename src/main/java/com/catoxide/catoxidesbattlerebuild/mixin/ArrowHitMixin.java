package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.combat.AttackProperties;
import com.catoxide.catoxidesbattlerebuild.core.combat.CombatResult;
import com.catoxide.catoxidesbattlerebuild.core.combat.CombatSystem;
import com.catoxide.catoxidesbattlerebuild.core.collision.ArrowBoneCollision;
import com.catoxide.catoxidesbattlerebuild.core.physics.ArrowPhysics;
import com.catoxide.catoxidesbattlerebuild.core.physics.ArrowTrajectory;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyPart;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyUnit;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.EntityBoneSystem;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 弓箭/投射物 mixin：将射箭伤害纳入战斗系统。
 * <p>弓射箭默认参数：5 穿甲值，5 贯穿值，基础伤害由箭矢携带。</p>
 * <p>使用 isProcessingArrowHit 标记通知 LivingEntityHurtMixin 跳过处理。</p>
 * <p>在 onHitEntity HEAD 注入中 ci.cancel() 完全切断原版 AABB 伤害链路。</p>
 * <p>同时注入自定义物理系统更新箭矢轨迹。</p>
 */
@Mixin(AbstractArrow.class)
public abstract class ArrowHitMixin {

    // 标记：当前正在处理箭矢命中，LivingEntityHurtMixin 应跳过
    // Mixin 要求所有静态字段必须 private
    private static final AtomicBoolean IS_PROCESSING_ARROW_HIT = new AtomicBoolean(false);

    // 锁定的目标实体映射（箭矢 UUID -> 被锁定的 LivingEntity）
    // 防止箭矢在同一飞行周期内命中多个目标
    private static final java.util.Map<String, LivingEntity> ARROW_LOCKED_ENTITIES = new java.util.WeakHashMap<>();

    static {
        LogManager.serverInfo("TEST-Mixin", "✅ ArrowHitMixin loaded into JVM");
    }

    // ==================== 锁定实体追踪 ====================

    /**
     * 获取箭矢当前锁定的目标实体。
     * 当箭矢检测到骨骼碰撞后锁定目标，防止同一飞行周期内命中多个实体。
     */
    private static LivingEntity getLockedEntity(AbstractArrow arrow) {
        return ARROW_LOCKED_ENTITIES.get(arrow.getStringUUID());
    }

    /**
     * 锁定箭矢的目标实体。
     */
    private static void lockEntity(AbstractArrow arrow, LivingEntity target) {
        ARROW_LOCKED_ENTITIES.put(arrow.getStringUUID(), target);
    }

    /**
     * 清除箭矢的锁定目标。
     */
    private static void unlockEntity(AbstractArrow arrow) {
        ARROW_LOCKED_ENTITIES.remove(arrow.getStringUUID());
    }

    /**
     * 清理已删除实体/箭矢的锁定记录。
     */
    private static void cleanupLocks(AbstractArrow arrow) {
        unlockEntity(arrow);
    }

    // ==================== 物理更新注入 ====================

    /**
     * 在箭矢每刻更新时注入自定义物理轨迹计算。
     * <p>在原版 tick() 之前应用重力、空气阻力和目标预测。</p>
     * <p>同时执行骨骼碰撞检测，命中时处理战斗逻辑。</p>
     */
    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void onArrowTick(CallbackInfo ci) {
        try {
            AbstractArrow arrow = (AbstractArrow) (Object) this;

            // 仅在非客户端侧运行物理模拟
            if (arrow.level().isClientSide) {
                return;
            }

            // 如果箭矢已嵌入/固定，跳过物理更新并清理锁定
            if (arrow.isNoGravity()) {
                cleanupLocks(arrow);
                return;
            }

            // 应用自定义物理轨迹
            ArrowPhysics.updateTrajectory(arrow, ArrowTrajectory.DEFAULT);

            // 骨骼碰撞检测
            processBoneCollision(arrow);

        } catch (Exception e) {
            LogManager.serverWarn("ArrowHitMixin", "Physics tick failed: {}", e.getMessage());
        }
    }

    // ==================== 骨骼碰撞检测 ====================

    /**
     * 每刻检测箭矢与周围实体的骨骼碰撞。
     * 如果箭矢已锁定目标，则只检测该目标；否则检测所有附近实体。
     */
    private void processBoneCollision(AbstractArrow arrow) {
        try {
            LivingEntity shooter = arrow.getOwner() instanceof LivingEntity ? (LivingEntity) arrow.getOwner() : null;
            if (shooter == null) {
                return;
            }

            // 获取锁定的目标（如果已锁定）
            LivingEntity lockedTarget = getLockedEntity(arrow);

            // 如果锁定的目标已死亡/移除，清除锁定
            if (lockedTarget != null && (!lockedTarget.isAlive() || lockedTarget.isRemoved())) {
                LogManager.serverDebug("TEST-ArrowHit", "  [UNLOCK] Target removed: entity={}", lockedTarget.getId());
                unlockEntity(arrow);
                lockedTarget = null;
            }

            // 确定要检测的实体列表
            java.util.List<LivingEntity> candidates;
            if (lockedTarget != null) {
                candidates = java.util.List.of(lockedTarget);
                LogManager.serverDebug("TEST-ArrowHit", "  [CHECK] Locked target: entity={}", lockedTarget.getId());
            } else {
                // 检测箭矢附近的所有实体
                double hitboxRadius = 1.5; // 碰撞检测半径
                java.util.List<Entity> nearby = arrow.level().getEntities(
                    arrow, arrow.getBoundingBox().inflate(hitboxRadius),
                    e -> e instanceof LivingEntity
                );
                candidates = nearby.stream()
                    .filter(e -> !(e instanceof AbstractArrow)) // 排除其他箭矢
                    .map(e -> (LivingEntity) e)
                    .toList();
                LogManager.serverDebug("TEST-ArrowHit", "  [CHECK] Found {} nearby entities", candidates.size());

                if (candidates.isEmpty()) {
                    return;
                }
            }

            // 对每个候选实体进行骨骼碰撞检测
            for (LivingEntity target : candidates) {
                // 跳过射手自身
                if (target == shooter) {
                    continue;
                }

                // 检查骨骼碰撞
                ArrowBoneCollision.BoneCollisionResult hitResult = ArrowBoneCollision.checkCollision(arrow, target);

                if (hitResult != null && hitResult.hit()) {
                    LogManager.serverInfo("TEST-ArrowHit", "  ✅ BONE HIT! Entity={}, Part={}, Unit={}, WorldPos={}, HitPos={}",
                        target.getId(), hitResult.partName(), hitResult.unitName(),
                        hitResult.partWorldPos(), hitResult.hitPosition());

                    // 锁定目标
                    lockEntity(arrow, target);

                    // 处理战斗（复用现有的战斗逻辑）
                    processCombat(arrow, target, hitResult);

                    return; // 命中一个目标后停止
                }
            }

        } catch (Exception e) {
            LogManager.serverWarn("ArrowHitMixin", "Bone collision detection failed: {}", e.getMessage());
        }
    }

    /**
     * 处理箭矢命中后的战斗逻辑。
     * 设置 noGravity 标记，通知 onArrowHit 跳过嵌入/粒子（已由 combat 处理）。
     */
    private void processCombat(AbstractArrow arrow, LivingEntity target, ArrowBoneCollision.BoneCollisionResult hitResult) {
        try {
            // 设置处理标记，防止 LivingEntityHurtMixin 重复处理
            IS_PROCESSING_ARROW_HIT.set(true);

            LivingEntity shooter = arrow.getOwner() instanceof LivingEntity ? (LivingEntity) arrow.getOwner() : null;
            if (shooter == null) {
                return;
            }

            float baseDamage = (float) arrow.getBaseDamage();
            float armorPen = 5.0f;
            float piercing = 5.0f;

            LogManager.serverInfo("TEST-ArrowHit", "  [COMBAT] Processing hit: target={}, damage={}, part={}",
                target.getId(), baseDamage, hitResult.partName());

            AttackProperties attack = AttackProperties.of(armorPen, piercing, baseDamage);

            // 获取目标的骨骼单位
            EntityBoneSystem boneSystem = EntityBoneSystem.getInstance();
            java.util.List<BodyUnit> units = new java.util.ArrayList<>();

            for (BodyPart part : boneSystem.getBodyParts(target.getId())) {
                units.addAll(part.getUnits());
            }

            if (units.isEmpty()) {
                units = boneSystem.getDefaultBodyUnits(target.getId());
            }

            if (units.isEmpty()) {
                LogManager.serverWarn("TEST-ArrowHit", "  [SKIP] No body units for target={}", target.getId());
                IS_PROCESSING_ARROW_HIT.set(false);
                return;
            }

            // 只保留命中的骨骼单位进行战斗计算
            java.util.List<BodyUnit> hitUnits = units.stream()
                .filter(u -> u.getBoneName().equals(hitResult.unitName()))
                .toList();

            if (hitUnits.isEmpty()) {
                // 回退到所有单位
                hitUnits = units;
                LogManager.serverDebug("TEST-ArrowHit", "  [FALLBACK] Using all units for target={}", target.getId());
            }

            // 收集 BodyPart 模块血量
            float[] healths = new float[hitUnits.size()];
            for (int i = 0; i < hitUnits.size(); i++) {
                healths[i] = hitUnits.get(i).getParentPart().getModuleHealth();
            }

            // 执行战斗计算
            CombatResult combatResult = CombatSystem.getInstance().processAttack(attack, hitUnits, healths);

            if (combatResult.totalDamage() > 0) {
                // 按 BodyPart 聚合伤害
                Map<BodyPart, Float> partDamageMap = new HashMap<>();
                for (CombatResult.HitResult hit : combatResult.hitResults()) {
                    BodyPart part = hit.bodyUnit().getParentPart();
                    partDamageMap.merge(part, hit.actualDamage(), Float::sum);
                }

                // 扣除血量
                boolean hasFatalDestroyed = false;
                for (Map.Entry<BodyPart, Float> entry : partDamageMap.entrySet()) {
                    BodyPart part = entry.getKey();
                    float damageToPart = entry.getValue();
                    float before = part.getModuleHealth();
                    part.applyDamage(damageToPart);
                    float after = part.getModuleHealth();

                    LogManager.serverInfo("TEST-ArrowHit", "  [DAMAGE] part={}, before={}, damage={}, after={}, destroyed={}",
                        part.getPartName(), before, damageToPart, after, part.isDestroyed());

                    if (part.isDestroyed() && part.isFatal()) {
                        hasFatalDestroyed = true;
                    }
                }

                // 应用总伤害到实体
                target.hurt(target.damageSources().mobAttack(shooter), combatResult.totalDamage());

                if (hasFatalDestroyed) {
                    LogManager.serverInfo("TEST-ArrowHit", "  💀 Fatal part destroyed, killing target");
                    target.kill();
                }

                // 销毁箭矢，防止下一tick重复判定
                LogManager.serverInfo("TEST-ArrowHit", "  [DISCARD] Discarding arrow after bone hit");
                arrow.discard();

            } else {
                LogManager.serverDebug("TEST-ArrowHit", "  [BLOCKED] Attack blocked by armor/piercing");
            }

        } catch (Exception e) {
            LogManager.serverWarn("ArrowHitMixin", "Combat processing failed: {}", e.getMessage());
        } finally {
            IS_PROCESSING_ARROW_HIT.set(false);
        }
    }

    // ==================== 命中处理 ====================

    /**
     * 在 onHitEntity HEAD 注入中 cancel 原版伤害链路。
     * 骨骼碰撞伤害由 processCombat 处理，原版 AABB 伤害被完全切断。
     * 箭矢嵌入/粒子/音效由原版 onHitEntity 内部逻辑处理（cancel 前不触发）。
     */
    @Inject(method = "onHitEntity", at = @At("HEAD"), cancellable = true)
    private void onArrowHit(EntityHitResult result, CallbackInfo ci) {
        LogManager.serverInfo("TEST-ArrowHit", "========== [TEST] onHitEntity TRIGGERED (HEAD) ==========");

        Entity target = result.getEntity();
        if (!(target instanceof LivingEntity livingTarget)) {
            LogManager.serverDebug("TEST-ArrowHit", "  [SKIP] Target is not LivingEntity");
            return;
        }

        AbstractArrow arrow = (AbstractArrow) (Object) this;

        // 判断箭矢是否已被骨骼碰撞处理过（discard 后 isRemoved=true）
        if (arrow.isRemoved()) {
            LogManager.serverInfo("TEST-ArrowHit", "  [CANCEL] Arrow already discarded by bone collision. Cancelling vanilla onHitEntity.");
            ci.cancel();
            return;
        }

        // 所有情况都取消原版 onHitEntity：
        // - 骨骼碰撞: processCombat 已处理伤害 + arrow.discard()
        // - 原版AABB: 完全切断原版伤害链路
        LogManager.serverInfo("TEST-ArrowHit", "  [CANCEL] Cancelling vanilla AABB damage for entity={}", livingTarget.getId());
        LogManager.serverInfo("TEST-ArrowHit", "  Target entity={}, type={}",
            livingTarget.getId(), livingTarget.getType().getDescriptionId());

        ci.cancel();
        LogManager.serverInfo("TEST-ArrowHit", "========== [TEST] onHitEntity END (CANCELLED) ==========");
    }
}
