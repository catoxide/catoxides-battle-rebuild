package com.catoxide.catoxidesbattlerebuild.server.temp;

import com.catoxide.catoxidesbattlerebuild.server.temp.components.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.util.LazyOptional;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.GeoCube;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 受击盒系统 - 管理实体的受击盒并处理碰撞检测
 */
public class HitboxSystem {
    private static final HitboxSystem INSTANCE = new HitboxSystem();
    private final OBBCollisionDetector detector = OBBCollisionDetector.getInstance();

    // 实体注册映射
    private final Map<UUID, ResourceLocation> entityModelMap = new ConcurrentHashMap<>();
    // 实体实例映射
    private final Map<UUID, Entity> entityMap = new ConcurrentHashMap<>();

    private HitboxSystem() {}

    public static HitboxSystem getInstance() {
        return INSTANCE;
    }

    /**
     * 注册实体到受击盒系统
     */
    public void registerEntity(Entity entity, ResourceLocation modelLocation) {
        if (entity != null && modelLocation != null) {
            entityModelMap.put(entity.getUUID(), modelLocation);
            entityMap.put(entity.getUUID(), entity);
        }
    }

    /**
     * 从受击盒系统中移除实体
     */
    public void unregisterEntity(Entity entity) {
        if (entity != null) {
            entityModelMap.remove(entity.getUUID());
            entityMap.remove(entity.getUUID());
        }
    }

    /**
     * 更新实体的受击盒
     */
    public void updateHitboxes(Entity entity) {
        if (entity == null) return;

        // 获取受击盒能力
        LazyOptional<HitboxCapability.IHitboxCapability> hitboxCap = HitboxCapability.getHitboxCapability(entity);
        hitboxCap.ifPresent(cap -> {
            // 这里可以实现受击盒的更新逻辑
            // 例如：根据实体状态更新受击盒的位置、旋转等
        });
    }

    /**
     * 更新所有实体的受击盒（服务器tick调用）
     * 
     * @param partialTick 部分tick（服务器端通常为1.0f）
     */
    public void updateHitboxes(float partialTick) {
        // 遍历所有注册的实体并更新它们的受击盒
        for (Map.Entry<UUID, Entity> entry : entityMap.entrySet()) {
            Entity entity = entry.getValue();
            if (entity != null && entity.isAlive()) {
                updateHitboxes(entity);
            }
        }
    }


    /**
     * 计算骨骼大小（从模型几何数据中）
     */
    public Vec3 calculateBoneSize(Collection<GeoCube> cubes) {
        if (cubes == null || cubes.isEmpty()) {
            return new Vec3(1, 1, 1); // 默认大小
        }

        if (cubes.size() == 1) {
            // 只有一个cube，直接返回其大小
            GeoCube cube = cubes.iterator().next();
            return new Vec3(
                    cube.size().x(),
                    cube.size().y(),
                    cube.size().z()
            );
        }

        // 多个cubes，计算边界盒
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE, maxZ = Double.MIN_VALUE;

        for (GeoCube cube : cubes) {
            // 获取cube的原点和大小
            Vec3 origin = new Vec3(
                    cube.pivot().x(),
                    cube.pivot().y(),
                    cube.pivot().z()
            );
            Vec3 size = new Vec3(
                    cube.size().x(),
                    cube.size().y(),
                    cube.size().z()
            );

            // 计算cube的边界
            minX = Math.min(minX, origin.x() - size.x() / 2);
            minY = Math.min(minY, origin.y() - size.y() / 2);
            minZ = Math.min(minZ, origin.z() - size.z() / 2);
            maxX = Math.max(maxX, origin.x() + size.x() / 2);
            maxY = Math.max(maxY, origin.y() + size.y() / 2);
            maxZ = Math.max(maxZ, origin.z() + size.z() / 2);
        }

        // 计算整体大小
        return new Vec3(
                maxX - minX,
                maxY - minY,
                maxZ - minZ
        );
    }

    /**
     * 检查射线是否击中实体的受击盒
     */
    public Optional<RaycastResult> raycastEntity(Entity entity, Vec3 rayOrigin, Vec3 rayDirection) {
        if (entity == null) return Optional.empty();

        // 转换到JOML向量
        Vector3f origin = new Vector3f(
                (float) rayOrigin.x(),
                (float) rayOrigin.y(),
                (float) rayOrigin.z()
        );
        Vector3f direction = new Vector3f(
                (float) rayDirection.x(),
                (float) rayDirection.y(),
                (float) rayDirection.z()
        );
        direction.normalize();

        // 获取实体的所有受击盒
        List<BoneHitboxComponent> hitboxes = getEntityHitboxes(entity);
        
        // 遍历实体的所有BoneHitboxComponent
        for (BoneHitboxComponent boneHitbox : hitboxes) {
            if (!boneHitbox.isActive()) continue;

            // 检测射线与每个cube的相交
            for (CubeCollection cubeHitbox : boneHitbox.getCubeHitboxes().values()) {
                if (!cubeHitbox.isActive()) continue;

                Float hitDistance = detector.raycastOBB(origin, direction, boneHitbox);
                if (hitDistance != null && hitDistance > 0) {
                    // 计算命中点
                    Vector3f hitPoint = origin.add(direction.mul(hitDistance, new Vector3f()), new Vector3f());
                    Vec3 mcHitPoint = new Vec3(hitPoint.x(), hitPoint.y(), hitPoint.z());

                    // 检查命中点是否在实体的AABB内（额外安全检查）
                    AABB entityAABB = entity.getBoundingBox();
                    if (entityAABB.contains(mcHitPoint)) {
                        return Optional.of(new RaycastResult(
                                entity,
                                boneHitbox.getBoneName(),
                                cubeHitbox.getCubeId(), // 返回命中的cube ID
                                mcHitPoint,
                                hitDistance
                        ));
                    }
                }
            }
        }

        return Optional.empty();
    }

    /**
     * 检查实体间的碰撞
     */
    public Optional<CollisionResult> checkEntityCollision(Entity entity1, Entity entity2) {
        if (entity1 == null || entity2 == null || entity1 == entity2) {
            return Optional.empty();
        }

        // 获取两个实体的受击盒
        List<BoneHitboxComponent> hitboxes1 = getEntityHitboxes(entity1);
        List<BoneHitboxComponent> hitboxes2 = getEntityHitboxes(entity2);

        // 遍历entity1的所有BoneHitboxComponent
        for (BoneHitboxComponent boneHitbox1 : hitboxes1) {
            if (!boneHitbox1.isActive()) continue;

            // 遍历entity2的所有BoneHitboxComponent
            for (BoneHitboxComponent boneHitbox2 : hitboxes2) {
                if (!boneHitbox2.isActive()) continue;

                // 检测两个BoneHitboxComponent之间的碰撞
                Optional<OBBCollisionDetector.CollisionInfo> collisionInfo = 
                        detector.getCollisionInfo(boneHitbox1, boneHitbox2);

                if (collisionInfo.isPresent()) {
                    OBBCollisionDetector.CollisionInfo info = collisionInfo.get();
                    return Optional.of(new CollisionResult(
                            entity1,
                            entity2,
                            boneHitbox1.getBoneName(),
                            boneHitbox2.getBoneName(),
                            new Vec3(info.normal.x(), info.normal.y(), info.normal.z()),
                            info.penetrationDepth
                    ));
                }
            }
        }

        return Optional.empty();
    }

    /**
     * 检查实体与方块的碰撞
     */
//    public Optional<CollisionResult> checkBlockCollision(Entity entity, BlockState blockState, BlockPos blockPos) {
//        if (entity == null || blockState == null) return Optional.empty();
//
//        Level level = entity.getLevel();
//        VoxelShape blockShape = blockState.getCollisionShape(level, blockPos);
//        if (blockShape.isEmpty()) return Optional.empty();
//
//        // 获取方块的AABB
//        List<AABB> blockAABBs = blockShape.toAabbs();
//        Vec3 blockPosVec = Vec3.atLowerCornerOf(blockPos);
//
//        // 获取实体的受击盒
//        List<BoneHitboxComponent> hitboxes = getEntityHitboxes(entity);
//
//        // 遍历实体的所有BoneHitboxComponent
//        for (BoneHitboxComponent boneHitbox : hitboxes) {
//            if (!boneHitbox.isActive()) continue;
//
//            // 遍历boneHitbox的所有CubeHitboxComponent
//            for (CubeHitboxComponent cubeHitbox : boneHitbox.getCubeHitboxes().values()) {
//                if (!cubeHitbox.isActive()) continue;
//
//                // 获取cube的世界AABB
//                AABB cubeAABB = getCubeAABB(cubeHitbox);
//
//                // 检查与每个方块AABB的碰撞
//                for (AABB blockAABB : blockAABBs) {
//                    AABB translatedBlockAABB = blockAABB.move(blockPosVec);
//                    if (cubeAABB.intersects(translatedBlockAABB)) {
//                        // 计算碰撞法线和穿透深度
//                        Vec3 normal = calculateBlockCollisionNormal(cubeAABB, translatedBlockAABB);
//                        double penetrationDepth = calculatePenetrationDepth(cubeAABB, translatedBlockAABB, normal);
//
//                        return Optional.of(new CollisionResult(
//                                entity,
//                                null,
//                                boneHitbox.getBoneName(),
//                                null,
//                                normal,
//                                (float) penetrationDepth
//                        ));
//                    }
//                }
//            }
//        }
//
//        return Optional.empty();
//    }

    /**
     * 获取实体的所有受击盒
     */
    public List<BoneHitboxComponent> getEntityHitboxes(Entity entity) {
        List<BoneHitboxComponent> hitboxes = new ArrayList<>();
        
        if (entity != null) {
            LazyOptional<HitboxCapability.IHitboxCapability> hitboxCap = HitboxCapability.getHitboxCapability(entity);
            hitboxCap.ifPresent(cap -> {
                hitboxes.addAll(cap.getHitboxes());
            });
        }
        
        return hitboxes;
    }

    /**
     * 获取CubeHitboxComponent的AABB
     */
    private AABB getCubeAABB(CubeCollection cubeHitbox) {
        Vector3f center = cubeHitbox.getWorldCenter();
        Vector3f halfExtents = cubeHitbox.getHalfExtents();

        // 计算AABB的最小和最大点
        float minX = center.x() - halfExtents.x();
        float minY = center.y() - halfExtents.y();
        float minZ = center.z() - halfExtents.z();
        float maxX = center.x() + halfExtents.x();
        float maxY = center.y() + halfExtents.y();
        float maxZ = center.z() + halfExtents.z();

        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * 计算与方块碰撞的法线
     */
    private Vec3 calculateBlockCollisionNormal(AABB entityAABB, AABB blockAABB) {
        // 计算两个AABB的中心
        Vec3 entityCenter = entityAABB.getCenter();
        Vec3 blockCenter = blockAABB.getCenter();

        // 计算中心差
        Vec3 centerDiff = entityCenter.subtract(blockCenter);

        // 计算每个轴上的重叠
        double overlapX = Math.min(entityAABB.maxX, blockAABB.maxX) - Math.max(entityAABB.minX, blockAABB.minX);
        double overlapY = Math.min(entityAABB.maxY, blockAABB.maxY) - Math.max(entityAABB.minY, blockAABB.minY);
        double overlapZ = Math.min(entityAABB.maxZ, blockAABB.maxZ) - Math.max(entityAABB.minZ, blockAABB.minZ);

        // 找到最小重叠的轴（碰撞法线方向）
        double minOverlap = Math.min(Math.min(overlapX, overlapY), overlapZ);
        Vec3 normal = new Vec3(0, 0, 0);

        if (minOverlap == overlapX) {
            normal = new Vec3(centerDiff.x() > 0 ? 1 : -1, 0, 0);
        } else if (minOverlap == overlapY) {
            normal = new Vec3(0, centerDiff.y() > 0 ? 1 : -1, 0);
        } else {
            normal = new Vec3(0, 0, centerDiff.z() > 0 ? 1 : -1);
        }

        return normal.normalize();
    }

    /**
     * 计算穿透深度
     */
    private double calculatePenetrationDepth(AABB entityAABB, AABB blockAABB, Vec3 normal) {
        // 计算每个轴上的重叠
        double overlapX = Math.min(entityAABB.maxX, blockAABB.maxX) - Math.max(entityAABB.minX, blockAABB.minX);
        double overlapY = Math.min(entityAABB.maxY, blockAABB.maxY) - Math.max(entityAABB.minY, blockAABB.minY);
        double overlapZ = Math.min(entityAABB.maxZ, blockAABB.maxZ) - Math.max(entityAABB.minZ, blockAABB.minZ);

        // 找到最小重叠（穿透深度）
        return Math.min(Math.min(overlapX, overlapY), overlapZ);
    }

    /**
     * 射线检测结果
     */
    public record RaycastResult(
            Entity entity,
            String boneName,
            String cubeId, // 命中的cube ID
            Vec3 hitPoint,
            float distance
    ) {}

    /**
     * 碰撞结果
     */
    public record CollisionResult(
            Entity entity1,
            Entity entity2,
            String boneName1,
            String boneName2,
            Vec3 normal,
            float penetrationDepth
    ) {}
    /**
     * 获取所有活跃实体的受击盒数据（供网络同步使用）
     */
    public Map<UUID, Collection<BoneHitboxComponent>> getAllActiveHitboxes() {
        Map<UUID, Collection<BoneHitboxComponent>> result = new HashMap<>();

        // 遍历所有注册的实体
        for (Map.Entry<UUID, Entity> entry : entityMap.entrySet()) {
            UUID entityId = entry.getKey();
            Entity entity = entry.getValue();

            if (entity != null) {
                // 获取实体的所有受击盒
                List<BoneHitboxComponent> hitboxes = getEntityHitboxes(entity);

                // 只收集活跃的受击盒
                Collection<BoneHitboxComponent> activeHitboxes = hitboxes.stream()
                        .filter(BoneHitboxComponent::isActive)
                        .collect(Collectors.toList());

                if (!activeHitboxes.isEmpty()) {
                    result.put(entityId, activeHitboxes);
                }
            }
        }

        return result;
    }
}