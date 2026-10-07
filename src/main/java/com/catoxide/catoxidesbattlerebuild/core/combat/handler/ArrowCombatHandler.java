package com.catoxide.catoxidesbattlerebuild.core.combat.handler;

import com.catoxide.catoxidesbattlerebuild.core.combat.ArrowCombatState;
import com.catoxide.catoxidesbattlerebuild.core.combat.AttackProperties;
import com.catoxide.catoxidesbattlerebuild.core.combat.CombatExecutor;
import com.catoxide.catoxidesbattlerebuild.core.combat.IArrowCombatHandler;
import com.catoxide.catoxidesbattlerebuild.core.collision.ArrowBoneCollision;
import com.catoxide.catoxidesbattlerebuild.core.physics.ArrowPhysics;
import com.catoxide.catoxidesbattlerebuild.core.physics.ArrowTrajectory;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyUnit;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 弓箭战斗处理器（core 实现，M5；M6 随 collision/physics 一并移入战斗内容包，
 * 注册点同步移入包的 {@code register()} —— F4 单一注册点）。
 * <p>承载原内联在 {@code ArrowHitMixin} 的全部机制（F1：逻辑与门分离）：
 * <ul>
 *   <li>{@link #onTick}：自定义轨迹物理（重力/空气阻力）+ 骨骼碰撞检测
 *       （命中后锁定目标，防止同一飞行周期命中多个实体）；</li>
 *   <li>{@link #onHitEntity}：骨骼碰撞伤害已由 onTick 处理，
 *       命中实体时接管并切断原版 AABB 伤害链。</li>
 * </ul>
 * <p>R3：未注册时箭矢 = 原版行为（原版物理 + AABB 伤害）。
 */
public class ArrowCombatHandler implements IArrowCombatHandler {

    private static final String TAG = "ArrowCombat";

    /** 弓射箭默认参数：5 穿甲值，5 贯穿值 */
    private static final float BOW_ARMOR_PENETRATION = 5.0f;
    private static final float BOW_PIERCING = 5.0f;

    /** 碰撞检测半径（无锁定目标时的附近实体搜索范围） */
    private static final double HITBOX_RADIUS = 1.5;

    /** 锁定的目标实体映射（箭矢 UUID -> 被锁定的 LivingEntity） */
    private final Map<String, LivingEntity> lockedEntities = new WeakHashMap<>();

    @Override
    public boolean onTick(AbstractArrow arrow) {
        try {
            if (arrow.level().isClientSide) {
                return false;
            }

            // 已嵌入/固定：跳过物理更新并清理锁定
            if (arrow.isNoGravity()) {
                lockedEntities.remove(arrow.getStringUUID());
                return false;
            }

            // 应用自定义物理轨迹
            ArrowPhysics.updateTrajectory(arrow, ArrowTrajectory.DEFAULT);

            // 骨骼碰撞检测
            return processBoneCollision(arrow);
        } catch (Exception e) {
            LogManager.serverWarn(TAG, "Physics tick failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean onHitEntity(AbstractArrow arrow, Entity target) {
        // 骨骼碰撞: processCombat 已处理伤害 + arrow.discard()；
        // 原版 AABB: 完全切断原版伤害链路（与迁移前行为一致：对活体目标一律接管）
        return target instanceof LivingEntity;
    }

    // ==================== 骨骼碰撞检测 ====================

    /**
     * 每刻检测箭矢与周围实体的骨骼碰撞。
     * 已锁定目标时只检测该目标；否则检测半径内所有附近实体。
     *
     * @return true = 命中并已处理（箭矢已销毁），门停止询问后续处理器
     */
    private boolean processBoneCollision(AbstractArrow arrow) {
        try {
            LivingEntity shooter = arrow.getOwner() instanceof LivingEntity ? (LivingEntity) arrow.getOwner() : null;
            if (shooter == null) {
                return false;
            }

            LivingEntity lockedTarget = lockedEntities.get(arrow.getStringUUID());

            // 锁定的目标已死亡/移除 → 清除锁定
            if (lockedTarget != null && (!lockedTarget.isAlive() || lockedTarget.isRemoved())) {
                LogManager.serverDebug(TAG, "[UNLOCK] target removed: entity={}", lockedTarget.getId());
                lockedEntities.remove(arrow.getStringUUID());
                lockedTarget = null;
            }

            List<LivingEntity> candidates;
            if (lockedTarget != null) {
                candidates = List.of(lockedTarget);
            } else {
                List<Entity> nearby = arrow.level().getEntities(
                        arrow, arrow.getBoundingBox().inflate(HITBOX_RADIUS),
                        e -> e instanceof LivingEntity);
                candidates = nearby.stream()
                        .filter(e -> !(e instanceof AbstractArrow)) // 排除其他箭矢
                        .map(e -> (LivingEntity) e)
                        .toList();
                if (candidates.isEmpty()) {
                    return false;
                }
            }

            for (LivingEntity target : candidates) {
                if (target == shooter) {
                    continue;
                }

                ArrowBoneCollision.BoneCollisionResult hitResult = ArrowBoneCollision.checkCollision(arrow, target);
                if (hitResult != null && hitResult.hit()) {
                    LogManager.serverInfo(TAG,
                            "BONE HIT: entity={}, part={}, unit={}, worldPos={}, hitPos={}",
                            target.getId(), hitResult.partName(), hitResult.unitName(),
                            hitResult.partWorldPos(), hitResult.hitPosition());

                    lockedEntities.put(arrow.getStringUUID(), target);
                    processCombat(arrow, target, hitResult);
                    return true; // 命中一个目标后停止
                }
            }
            return false;
        } catch (Exception e) {
            LogManager.serverWarn(TAG, "Bone collision detection failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 处理箭矢命中后的战斗逻辑（共享执行走 {@link CombatExecutor}）。
     */
    private void processCombat(AbstractArrow arrow, LivingEntity target,
                               ArrowBoneCollision.BoneCollisionResult hitResult) {
        // 设置处理标记，防止受击拦截器二次处理（flag 在普通类 ArrowCombatState）
        ArrowCombatState.setProcessing(true);
        try {
            LivingEntity shooter = arrow.getOwner() instanceof LivingEntity ? (LivingEntity) arrow.getOwner() : null;
            if (shooter == null) {
                return;
            }

            float baseDamage = (float) arrow.getBaseDamage();
            LogManager.serverInfo(TAG, "Processing hit: target={}, damage={}, part={}",
                    target.getId(), baseDamage, hitResult.partName());

            // 目标部位（剑/弓共用：CombatExecutor）
            List<BodyUnit> units = CombatExecutor.getTargetUnits(target.getId());
            if (units.isEmpty()) {
                LogManager.serverWarn(TAG, "No body units for target={}", target.getId());
                return;
            }

            // 只保留命中的骨骼单位进行战斗计算；空则回退整身
            List<BodyUnit> hitUnits = units.stream()
                    .filter(u -> u.getBoneName().equals(hitResult.unitName()))
                    .toList();
            if (hitUnits.isEmpty()) {
                hitUnits = units;
                LogManager.serverDebug(TAG, "FALLBACK: using all units for target={}", target.getId());
            }

            // 共享执行（CombatExecutor）：管线 → 部位模块血量 → 总HP（RAW = 管线原始总伤，弓现行行为；
            // 是否切 TRANSMITTED 与剑统一待用户拍板）→ 致命 kill
            CombatExecutor.Execution execution = CombatExecutor.execute(target,
                    target.damageSources().mobAttack(shooter),
                    CombatExecutor.BaseHpMode.RAW,
                    AttackProperties.of(BOW_ARMOR_PENETRATION, BOW_PIERCING, baseDamage),
                    hitUnits, TAG);

            if (execution.damaged()) {
                // 销毁箭矢，防止下一 tick 重复判定
                arrow.discard();
            }
        } catch (Exception e) {
            LogManager.serverWarn(TAG, "Combat processing failed: {}", e.getMessage());
        } finally {
            ArrowCombatState.setProcessing(false);
        }
    }
}
