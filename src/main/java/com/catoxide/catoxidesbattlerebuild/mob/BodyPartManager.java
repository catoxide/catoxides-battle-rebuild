package com.catoxide.catoxidesbattlerebuild.mob;

import com.catoxide.catoxidesbattlerebuild.registry.ModEntities;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Quaternionf;

import java.util.*;

public class BodyPartManager {
    private final ModularZombie parent;
    private final Map<String, BodyPart> bodyParts = new HashMap<>();
    private final Map<String, HitboxPart> hitboxEntities = new HashMap<>();
    private final Map<String, List<HitboxPart>> preciseHitboxClusters = new HashMap<>();

    public BodyPartManager(ModularZombie parent) {
        this.parent = parent;
        initBodyParts();
    }

    // 初始化身体部位（使用精确碰撞箱）
    private void initBodyParts() {
        // 头部 - 使用精确碰撞箱
        List<AABB> headHitboxes = Arrays.asList(
                new AABB(-0.25, 0.0, -0.25, 0.25, 0.3, 0.25),
                new AABB(-0.2, 0.3, -0.2, 0.2, 0.6, 0.2)
        );
        bodyParts.put("head", new BodyPart("head", "head", headHitboxes, 20.0f, 2.0f));

        // 躯干 - 使用精确碰撞箱
        List<AABB> torsoHitboxes = Arrays.asList(
                new AABB(-0.35, -0.5, -0.2, 0.35, 0.0, 0.2),
                new AABB(-0.3, 0.0, -0.15, 0.3, 0.4, 0.15)
        );
        bodyParts.put("torso", new BodyPart("torso", "body", torsoHitboxes, 50.0f, 1.0f));

        // 左臂 - 分段碰撞箱
        List<AABB> leftArmHitboxes = createLimbHitboxes(0.15, 0.8, 3);
        bodyParts.put("arm_left", new BodyPart("arm_left", "left_arm", leftArmHitboxes, 15.0f, 0.6f));

        // 右臂 - 分段碰撞箱
        List<AABB> rightArmHitboxes = createLimbHitboxes(0.15, 0.8, 3);
        bodyParts.put("arm_right", new BodyPart("arm_right", "right_arm", rightArmHitboxes, 15.0f, 0.6f));

        // 左腿 - 分段碰撞箱
        List<AABB> leftLegHitboxes = createLimbHitboxes(0.18, 1.0, 4);
        bodyParts.put("leg_left", new BodyPart("leg_left", "left_leg", leftLegHitboxes, 25.0f, 0.7f));

        // 右腿 - 分段碰撞箱
        List<AABB> rightLegHitboxes = createLimbHitboxes(0.18, 1.0, 4);
        bodyParts.put("leg_right", new BodyPart("leg_right", "right_leg", rightLegHitboxes, 25.0f, 0.7f));
    }

    // 创建肢体分段碰撞箱
    private List<AABB> createLimbHitboxes(double radius, double length, int segments) {
        List<AABB> hitboxes = new ArrayList<>();
        double segmentLength = length / segments;

        for (int i = 0; i < segments; i++) {
            double yStart = i * segmentLength - length/2;
            double yEnd = (i + 1) * segmentLength - length/2;
            hitboxes.add(new AABB(-radius, yStart, -radius, radius, yEnd, radius));
        }
        return hitboxes;
    }

    // 创建碰撞箱实体
    public void spawnHitboxEntities() {
        // 这里需要你的实体注册
        // 暂时注释掉，等你有实体注册后再启用

        for (BodyPart part : bodyParts.values()) {
            HitboxPart singleHitbox = new HitboxPart(ModEntities.HITBOX_PART.get(), parent.level());
            singleHitbox.initialize(parent, part.getPartName());
            parent.level().addFreshEntity(singleHitbox);
            hitboxEntities.put(part.getPartName(), singleHitbox);

            List<HitboxPart> cluster = new ArrayList<>();
            List<AABB> preciseHitboxes = part.getPreciseHitboxes();

            for (int i = 0; i < preciseHitboxes.size(); i++) {
                HitboxPart preciseHitbox = new HitboxPart(ModEntities.HITBOX_PART.get(), parent.level());
                preciseHitbox.initialize(parent, part.getPartName() + "_precise_" + i);
                parent.level().addFreshEntity(preciseHitbox);
                cluster.add(preciseHitbox);
            }
            preciseHitboxClusters.put(part.getPartName(), cluster);
        }
    }

    // 更新碰撞箱位置
    public void updateHitboxPositions() {
        // 暂时简化实现
        for (Map.Entry<String, BodyPart> entry : bodyParts.entrySet()) {
            String partName = entry.getKey();
            BodyPart part = entry.getValue();

            if (part.isDestroyed()) continue;

            updateHitboxPositionsSimple(partName, part);
        }
    }

    // 简化版本的位置更新
    private void updateHitboxPositionsSimple(String partName, BodyPart part) {
        Vec3 entityPos = parent.position();

        // 更新单个碰撞箱
        HitboxPart singleHitbox = hitboxEntities.get(partName);
        if (singleHitbox != null) {
            AABB worldHitbox = part.getBaseHitbox().move(entityPos);
            singleHitbox.setPos(entityPos.x, entityPos.y, entityPos.z);
            singleHitbox.setBoundingBox(worldHitbox);
        }

        // 更新精确碰撞箱集群
        List<HitboxPart> cluster = preciseHitboxClusters.get(partName);
        if (cluster != null) {
            List<AABB> preciseHitboxes = part.getPreciseHitboxes();
            for (int i = 0; i < preciseHitboxes.size() && i < cluster.size(); i++) {
                HitboxPart preciseHitbox = cluster.get(i);
                AABB localHitbox = preciseHitboxes.get(i);
                AABB worldHitbox = localHitbox.move(entityPos);
                Vec3 center = getAABBCenter(worldHitbox);

                preciseHitbox.setPos(center.x, center.y, center.z);
                preciseHitbox.setBoundingBox(worldHitbox);
            }
        }
    }

    // 简化的变换方法
    private AABB transformHitboxSimple(AABB localHitbox, BoneTransform transform) {
        return localHitbox.move(transform.position);
    }

    // 获取AABB中心点
    private Vec3 getAABBCenter(AABB aabb) {
        return new Vec3(
                (aabb.minX + aabb.maxX) / 2,
                (aabb.minY + aabb.maxY) / 2,
                (aabb.minZ + aabb.maxZ) / 2
        );
    }

    // 获取部位数据
    public BodyPart getBodyPart(String partName) {
        return bodyParts.get(partName);
    }

    // 获取所有部位
    public Map<String, BodyPart> getBodyParts() {
        return bodyParts;
    }

    // 获取单个碰撞箱实体
    public HitboxPart getHitboxEntity(String partName) {
        return hitboxEntities.get(partName);
    }

    // 获取精确碰撞箱集群
    public List<HitboxPart> getPreciseHitboxCluster(String partName) {
        return preciseHitboxClusters.get(partName);
    }

    // 精确射线检测
    public String rayTracePreciseParts(Vec3 start, Vec3 end) {
        double closestDistance = Double.MAX_VALUE;
        String hitPart = null;

        for (Map.Entry<String, BodyPart> entry : bodyParts.entrySet()) {
            String partName = entry.getKey();
            BodyPart part = entry.getValue();

            if (part.isDestroyed()) continue;

            try {
                BoneTransform boneTransform = parent.getAnimationController().getBoneWorldTransform(part.getBoneName());
                List<AABB> hitboxes = part.getPreciseHitboxes();

                for (AABB hitbox : hitboxes) {
                    AABB worldHitbox = transformHitboxSimple(hitbox, boneTransform);
                    Optional<Vec3> hitPos = worldHitbox.clip(start, end);
                    if (hitPos.isPresent()) {
                        double distance = start.distanceTo(hitPos.get());
                        if (distance < closestDistance) {
                            closestDistance = distance;
                            hitPart = partName;
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("射线检测失败，跳过部位 " + partName + ": " + e.getMessage());
            }
        }

        return hitPart;
    }

    // 清理所有碰撞箱
    public void discardAllHitboxes() {
        hitboxEntities.values().forEach(hitbox -> {
            if (hitbox != null) {
                hitbox.discard();
            }
        });
        hitboxEntities.clear();

        preciseHitboxClusters.values().forEach(cluster -> {
            if (cluster != null) {
                cluster.forEach(hitbox -> {
                    if (hitbox != null) {
                        hitbox.discard();
                    }
                });
            }
        });
        preciseHitboxClusters.clear();
    }

    // 调试信息
    public String getDebugInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Body Parts: ").append(bodyParts.size()).append("\n");

        for (Map.Entry<String, BodyPart> entry : bodyParts.entrySet()) {
            BodyPart part = entry.getValue();
            sb.append("  ").append(entry.getKey())
                    .append(": health=").append(part.getCurrentHealth())
                    .append(", destroyed=").append(part.isDestroyed())
                    .append(", hitboxes=").append(part.getHitboxCount())
                    .append("\n");
        }

        return sb.toString();
    }
}