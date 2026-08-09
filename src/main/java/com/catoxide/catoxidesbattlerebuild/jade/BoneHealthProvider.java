package com.catoxide.catoxidesbattlerebuild.jade;

import com.catoxide.catoxidesbattlerebuild.core.anim.AnimatedMob;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyPart;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyUnit;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.EntityBoneSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
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
        String hitBone = raycastFocusedBone(accessor, living, radii);
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
     * 客户端射线检测：玩家眼睛沿视线方向，对目标实体所有骨骼球体求最近命中。
     * @return 命中的骨骼名，未命中返回 null
     */
    private String raycastFocusedBone(EntityAccessor accessor, LivingEntity target, CompoundTag radii) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return null;
        }
        if (!(target instanceof AnimatedMob<?> mob)) {
            return null;
        }
        Map<String, Vector3f> bones = mob.getAllServerBonePositions();
        if (bones.isEmpty()) {
            return null;
        }
        float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(true);
        Vec3 eyePos = mc.player.getEyePosition(partialTick);
        Vec3 lookDir = mc.player.getViewVector(partialTick);

        double bestT = Double.MAX_VALUE;
        String bestBone = null;
        for (Map.Entry<String, Vector3f> e : bones.entrySet()) {
            CompoundTag info = radii.getCompound(e.getKey());
            float radius = info.contains("R") ? info.getFloat("R") : DEFAULT_RADIUS;
            Vec3 center = new Vec3(e.getValue().x(), e.getValue().y(), e.getValue().z());
            double t = raySphere(eyePos, lookDir, center, radius);
            if (t >= 0 && t < bestT) {
                bestT = t;
                bestBone = e.getKey();
            }
        }
        return bestBone;
    }

    /** 射线-球体相交：返回射线参数 t（≥0 命中），未命中返回 -1 */
    private static double raySphere(Vec3 origin, Vec3 dir, Vec3 center, float radius) {
        double ocX = origin.x - center.x, ocY = origin.y - center.y, ocZ = origin.z - center.z;
        double a = dir.x * dir.x + dir.y * dir.y + dir.z * dir.z;
        if (a < 1e-8) {
            return -1;
        }
        double b = 2.0 * (ocX * dir.x + ocY * dir.y + ocZ * dir.z);
        double c = ocX * ocX + ocY * ocY + ocZ * ocZ - (double) radius * radius;
        double disc = b * b - 4.0 * a * c;
        if (disc < 0.0) {
            return -1;
        }
        double sqrt = Math.sqrt(disc);
        double t1 = (-b - sqrt) / (2.0 * a);
        double t2 = (-b + sqrt) / (2.0 * a);
        if (t1 >= 0) {
            return t1;
        }
        return t2 >= 0 ? t2 : -1;
    }

    // ========== 骨骼外形拾取（射线 vs 骨骼球体，替代原版碰撞箱判定）==========

    /** 拾取结果：命中的实体 + 命中点 */
    public record BonePickResult(AnimatedMob<?> mob, Vec3 hitPoint) {
    }

    /**
     * 玩家视线射线 vs 附近所有 AnimatedMob 的骨骼球体（动画外形），返回最近命中。
     * <p>用于 Jade 拾取覆盖：准星对到模型外形（如手臂伸出碰撞箱外）也能拾取到实体。
     * <p>拾取阶段不查 serverData 半径（可能尚未请求），用统一默认半径即可确定"命中哪个实体"；
     * 精确部位由 tooltip 阶段（{@link #appendTooltip}）的 serverData 半径负责。
     *
     * @param player 客户端玩家
     * @return 最近命中的 (实体, 命中点)，未命中返回 null
     */
    public static BonePickResult raycastNearestMob(net.minecraft.world.entity.player.Player player) {
        net.minecraft.world.level.Level level = player.level();
        if (level == null) {
            return null;
        }
        net.minecraft.world.phys.AABB box = player.getBoundingBox().inflate(8.0);
        Vec3 eyePos = player.getEyePosition(1.0f);
        Vec3 lookDir = player.getViewVector(1.0f);

        double bestT = Double.MAX_VALUE;
        AnimatedMob<?> bestMob = null;
        Vec3 bestHit = null;
        for (net.minecraft.world.entity.Entity e : level.getEntities(player, box, ent -> ent instanceof AnimatedMob<?>)) {
            AnimatedMob<?> mob = (AnimatedMob<?>) e;
            for (Vector3f pos : mob.getAllServerBonePositions().values()) {
                Vec3 center = new Vec3(pos.x(), pos.y(), pos.z());
                double t = raySphere(eyePos, lookDir, center, DEFAULT_RADIUS);
                if (t >= 0 && t < bestT) {
                    bestT = t;
                    bestMob = mob;
                    bestHit = eyePos.add(lookDir.x * t, lookDir.y * t, lookDir.z * t);
                }
            }
        }
        return bestMob != null ? new BonePickResult(bestMob, bestHit) : null;
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
