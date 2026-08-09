package com.catoxide.catoxidesbattlerebuild.jade;

import cn.solarmoon.spark_core.animation.IEntityAnimatable;
import cn.solarmoon.spark_core.animation.model.BonePose;
import cn.solarmoon.spark_core.animation.model.ModelInstance;
import com.catoxide.catoxidesbattlerebuild.core.anim.AnimatedMob;
import com.catoxide.catoxidesbattlerebuild.core.hitbox.HitboxConfig;
import com.catoxide.catoxidesbattlerebuild.core.hitbox.HitboxResolver;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyPart;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyUnit;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.EntityBoneSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

import java.util.List;
import java.util.Map;

/**
 * Jade 部位血量显示 provider（标准显示接入点）。
 * <p>显示内容：
 * <ul>
 *   <li>实体整体血量（客户端实体属性直接可得）</li>
 *   <li>玩家视线（眼睛射线）命中的第一个骨骼部位的血量——射线 vs 骨骼球体检测</li>
 * </ul>
 * <p>服务端：同步部位血量 + 每个骨骼的碰撞半径（客户端射线检测需要）。
 * <p>仅对已初始化骨骼系统的实体（{@link AnimatedMob} 子类）显示。
 */
public enum BoneHealthProvider implements IEntityComponentProvider, IServerDataProvider<EntityAccessor> {
    INSTANCE;

    public static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath("catoxidesbattlerebuild", "bone_health");

    private static final String KEY_PARTS = "BoneParts";
    private static final String KEY_RADII = "BoneRadii";
    /** 骨骼碰撞半径默认值（与 ProjectileConfig.DEFAULT_ARROW.collisionRadius 一致） */
    private static final float DEFAULT_RADIUS = 0.35f;

    // ========== 客户端显示 ==========

    @Override
    public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
        if (!(accessor.getEntity() instanceof LivingEntity living)) {
            return;
        }

        // 1. 整体血量
        tooltip.add(Component.literal(String.format("HP: %.0f/%.0f", living.getHealth(), living.getMaxHealth())));

        // 2. 视线命中的第一个部位血量
        CompoundTag parts = accessor.getServerData().getCompound(KEY_PARTS);
        if (parts.isEmpty()) {
            return;
        }
        CompoundTag radii = accessor.getServerData().getCompound(KEY_RADII);
        String hitBone = raycastFocusedBone(living);
        if (hitBone == null) {
            return;
        }
        // 命中骨骼 → 找所属部位 → 显示血量
        CompoundTag boneInfo = radii.getCompound(hitBone);
        String partName = boneInfo.getString("Part");
        if (partName.isEmpty()) {
            return;
        }
        CompoundTag part = parts.getCompound(partName);
        if (part.isEmpty()) {
            return;
        }
        float hp = part.getFloat("Health");
        float max = part.getFloat("MaxHealth");
        boolean fatal = part.getBoolean("Fatal");
        String line = String.format("%s: %.0f/%.0f", partName, hp, max);
        if (fatal) {
            line += " (致命)";
        }
        tooltip.add(Component.literal("→ " + line));
    }

    /**
     * 客户端射线检测（OBB）：对目标实体所有骨骼的真实模型立方体做射线命中，
     * 返回最近命中的骨骼名。
     * <p>用渲染模型的实时世界矩阵（{@code BonePose.getWorldBonePivotMatrix}）+ 模型提取的
     * OBB 配置（{@link HitboxResolver}）——精确贴合动画外形，左右肢体不会混淆。
     *
     * @return 命中的骨骼名，未命中返回 null
     */
    private String raycastFocusedBone(LivingEntity target) {
        if (!(target instanceof AnimatedMob<?> mob)) {
            return null;
        }
        return raycastBoneOBB(mob, currentEye(), currentLook()).boneName();
    }

    private static Vec3 currentEye() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player.getEyePosition(mc.getTimer().getGameTimeDeltaPartialTick(true));
    }

    private static Vec3 currentLook() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player.getViewVector(mc.getTimer().getGameTimeDeltaPartialTick(true));
    }

    // ========== 骨骼 OBB 射线检测（动画外形精确拾取）==========

    /** 单骨骼命中结果：骨骼名 + 世界空间命中距离 t */
    public record BoneT(String boneName, double t) {
    }

    /** 实体拾取结果：命中的实体 + 骨骼 + 命中点 */
    public record BoneOBBHit(AnimatedMob<?> mob, String boneName, Vec3 hitPoint) {
    }

    /**
     * 玩家视线 vs 附近所有 AnimatedMob 的真实模型 OBB，返回最近命中。
     * <p>拾取范围 = 模型真实外形（手臂/头伸出碰撞箱外也能拾取），
     * 且左右肢体用真实几何区分（不会互相混淆）。
     *
     * @param player 客户端玩家
     * @return 最近命中 (实体, 骨骼, 命中点)，未命中返回 null
     */
    public static BoneOBBHit raycastNearestOBB(net.minecraft.world.entity.player.Player player) {
        net.minecraft.world.level.Level level = player.level();
        if (level == null) {
            return null;
        }
        net.minecraft.world.phys.AABB box = player.getBoundingBox().inflate(8.0);
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getViewVector(1.0f);

        double bestT = Double.MAX_VALUE;
        AnimatedMob<?> bestMob = null;
        String bestBone = null;
        for (net.minecraft.world.entity.Entity e : level.getEntities(player, box, ent -> ent instanceof AnimatedMob<?>)) {
            AnimatedMob<?> mob = (AnimatedMob<?>) e;
            BoneT hit = raycastBoneOBB(mob, eye, look);
            if (hit != null && hit.t() >= 0 && hit.t() < bestT) {
                bestT = hit.t();
                bestMob = mob;
                bestBone = hit.boneName();
            }
        }
        if (bestMob == null) {
            return null;
        }
        Vec3 hitPoint = eye.add(look.x * bestT, look.y * bestT, look.z * bestT);
        return new BoneOBBHit(bestMob, bestBone, hitPoint);
    }

    /**
     * 对单个实体的所有骨骼 OBB 做射线检测，返回最近命中。
     * <p>数据来源：渲染模型的实时姿态（{@code ModelPose.getBonePoses()}）
     * + 从 geo.json 提取的每骨骼 OBB（{@link HitboxResolver#resolveFromModel}）。
     */
    private static BoneT raycastBoneOBB(AnimatedMob<?> mob, Vec3 eye, Vec3 look) {
        if (!(mob instanceof IEntityAnimatable<?> animatable)) {
            return null;
        }
        ModelInstance model = animatable.getModelController().getModel();
        if (model == null || model.getPose() == null) {
            return null;
        }
        Map<String, BonePose> poses = model.getPose().getBonePoses();
        if (poses.isEmpty()) {
            return null;
        }
        Map<String, HitboxConfig> configs = HitboxResolver.resolveFromModel(model);
        float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);

        double bestT = Double.MAX_VALUE;
        String bestBone = null;
        for (Map.Entry<String, BonePose> entry : poses.entrySet()) {
            HitboxConfig cfg = configs.get(entry.getKey());
            if (cfg == null) {
                continue;
            }
            try {
                Matrix4f worldMat = entry.getValue().getWorldBonePivotMatrix(partialTick);
                // OBB 中心 = 矩阵平移 + R * localOffset（一次 transformPosition 完成）
                Vector3f obbCenter = worldMat.transformPosition(cfg.localOffset(), new Vector3f());
                Quaternionf rot = worldMat.getUnnormalizedRotation(new Quaternionf());
                double t = rayOBB(eye, look,
                        new Vec3(obbCenter.x(), obbCenter.y(), obbCenter.z()),
                        cfg.halfExtents(), rot);
                if (t >= 0 && t < bestT) {
                    bestT = t;
                    bestBone = entry.getKey();
                }
            } catch (Exception ignored) {
            }
        }
        return bestBone != null ? new BoneT(bestBone, bestT) : null;
    }

    /**
     * 射线 vs OBB（slab 法，世界空间）。
     * <p>把射线投影到 OBB 的三个正交轴（旋转四元数生成），对每个轴做 slab 裁剪，
     * 得到射线进入/离开参数区间 [tmin, tmax]。dir 必须为单位向量，返回的 t 是世界空间距离。
     *
     * @param origin      射线起点
     * @param dir         射线方向（单位向量）
     * @param center      OBB 中心
     * @param halfExtents 半尺寸（米）
     * @param rot         OBB 旋转（骨骼世界旋转）
     * @return 最近命中距离 t（≥0），未命中返回 -1
     */
    private static double rayOBB(Vec3 origin, Vec3 dir, Vec3 center, Vector3f halfExtents, Quaternionf rot) {
        // OBB 局部轴（旋转后的三个正交方向）
        Vector3f axisX = rot.transform(new Vector3f(1, 0, 0));
        Vector3f axisY = rot.transform(new Vector3f(0, 1, 0));
        Vector3f axisZ = rot.transform(new Vector3f(0, 0, 1));

        double px = origin.x - center.x, py = origin.y - center.y, pz = origin.z - center.z;
        double hx = halfExtents.x(), hy = halfExtents.y(), hz = halfExtents.z();

        double tmin = -Double.MAX_VALUE, tmax = Double.MAX_VALUE;

        // 对每个轴做 slab 裁剪（axis 为 OBB 局部轴，e = p·axis, f = d·axis）
        double eX = px * axisX.x() + py * axisX.y() + pz * axisX.z();
        double fX = dir.x * axisX.x() + dir.y * axisX.y() + dir.z * axisX.z();
        if (Math.abs(fX) < 1e-8) {
            if (eX < -hx || eX > hx) return -1;
        } else {
            double t1 = (-hx - eX) / fX, t2 = (hx - eX) / fX;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return -1;
        }

        double eY = px * axisY.x() + py * axisY.y() + pz * axisY.z();
        double fY = dir.x * axisY.x() + dir.y * axisY.y() + dir.z * axisY.z();
        if (Math.abs(fY) < 1e-8) {
            if (eY < -hy || eY > hy) return -1;
        } else {
            double t1 = (-hy - eY) / fY, t2 = (hy - eY) / fY;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return -1;
        }

        double eZ = px * axisZ.x() + py * axisZ.y() + pz * axisZ.z();
        double fZ = dir.x * axisZ.x() + dir.y * axisZ.y() + dir.z * axisZ.z();
        if (Math.abs(fZ) < 1e-8) {
            if (eZ < -hz || eZ > hz) return -1;
        } else {
            double t1 = (-hz - eZ) / fZ, t2 = (hz - eZ) / fZ;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return -1;
        }

        if (tmax < 0) return -1;
        return Math.max(tmin, 0.0);
    }

    // ========== 服务端数据 ==========

    @Override
    public void appendServerData(CompoundTag data, EntityAccessor accessor) {
        if (!(accessor.getEntity() instanceof AnimatedMob<?>)) {
            return;
        }
        int entityId = accessor.getEntity().getId();
        EntityBoneSystem boneSystem = EntityBoneSystem.getInstance();

        // 部位血量
        List<BodyPart> parts = boneSystem.getBodyParts(entityId);
        if (parts.isEmpty()) {
            return;
        }
        CompoundTag partsTag = new CompoundTag();
        for (BodyPart part : parts) {
            CompoundTag pt = new CompoundTag();
            pt.putString("Name", part.getPartName());
            pt.putFloat("Health", part.getModuleHealth());
            pt.putFloat("MaxHealth", part.getMaxModuleHealth());
            pt.putBoolean("Fatal", part.isFatal());
            partsTag.put(part.getPartName(), pt);
        }
        data.put(KEY_PARTS, partsTag);

        // 骨骼碰撞半径 + 骨骼→部位映射（客户端视线射线检测用）
        CompoundTag radiiTag = new CompoundTag();
        for (BodyPart part : parts) {
            for (BodyUnit unit : part.getUnits()) {
                CompoundTag ut = new CompoundTag();
                ut.putString("Part", part.getPartName());
                ut.putFloat("R", parseRadius(unit.getCollisionTag()));
                radiiTag.put(unit.getBoneName(), ut);
            }
        }
        data.put(KEY_RADII, radiiTag);
    }

    private static float parseRadius(String collisionTag) {
        if (collisionTag != null && !collisionTag.isEmpty()) {
            try {
                return Float.parseFloat(collisionTag);
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_RADIUS;
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }
}
