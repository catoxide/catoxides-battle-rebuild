package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import com.catoxide.catoxidesbattlerebuild.mob.server.HitboxSyncPacket;
import com.catoxide.catoxidesbattlerebuild.network.NetworkHandler;
import com.catoxide.catoxidesbattlerebuild.registry.ModEntities;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

public class HitboxManager {
    private final ModularZombie parent;
    private final BodyPartManager partManager;
    private final Map<String, List<HitboxPart>> preciseHitboxClusters = new HashMap<>();

    public HitboxManager(ModularZombie parent, BodyPartManager partManager) {
        this.parent = parent;
        this.partManager = partManager;
    }

    public void spawnHitboxEntities() {
        System.out.println("=== 开始生成碰撞箱实体 ===");

        for (BodyPart part : partManager.getBodyParts().values()) {
            try {
                BoneTransform boneTransform = parent.getServerAnimationSystem().getBoneTransform(part.getBoneName());
                System.out.println("生成部位: " + part.getPartName() + " | 骨骼: " + part.getBoneName());

                List<HitboxPart> cluster = new ArrayList<>();
                List<GeometryModel.Cube> cubes = part.getCubes();

                for (int i = 0; i < cubes.size(); i++) {
                    GeometryModel.Cube cube = cubes.get(i);
                    List<Vec3> worldVertices = transformCubeVertices(cube, boneTransform, part.getPivot());
                    AABB worldOBB = createOBBFromVertices(worldVertices);
                    Vec3 center = getAABBCenter(worldOBB);

                    HitboxPart preciseHitbox = new HitboxPart(ModEntities.HITBOX_PART.get(), parent.level());
                    preciseHitbox.initialize(parent, part.getPartName() + "_precise_" + i);
                    preciseHitbox.setPos(center.x, center.y, center.z);
                    preciseHitbox.setBoundingBox(worldOBB);

                    parent.level().addFreshEntity(preciseHitbox);
                    cluster.add(preciseHitbox);
                    System.out.println("  立方体 " + i + " 世界OBB: " + worldOBB);
                }

                preciseHitboxClusters.put(part.getPartName(), cluster);

            } catch (Exception e) {
                System.err.println("生成部位 " + part.getPartName() + " 的碰撞箱失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void updateHitboxPositions() {
        for (Map.Entry<String, List<HitboxPart>> entry : preciseHitboxClusters.entrySet()) {
            String partName = entry.getKey();
            List<HitboxPart> cluster = entry.getValue();
            BodyPart part = partManager.getBodyPart(partName);
            if (part == null) continue;

            String boneName = part.getBoneName();
            BoneTransform boneTransform = parent.getServerAnimationSystem().calculateBoneTransform(boneName);

            if (boneTransform != null) {
                List<GeometryModel.Cube> cubes = part.getCubes();
                for (int i = 0; i < cubes.size() && i < cluster.size(); i++) {
                    HitboxPart hitbox = cluster.get(i);
                    GeometryModel.Cube cube = cubes.get(i);

                    List<Vec3> worldVertices = new ArrayList<>();
                    for (Vertex vertex : cube.getVertices()) {
                        Vec3 worldVertex = transformVertexWithPivot(vertex, boneTransform, part.getPivot());
                        worldVertices.add(worldVertex);
                    }

                    AABB worldOBB = createOBBFromVertices(worldVertices);
                    Vec3 center = getAABBCenter(worldOBB);

                    hitbox.setPos(center.x, center.y, center.z);
                    hitbox.setBoundingBox(worldOBB);
                }
            }
        }
    }

    public void discardAllHitboxes() {
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

    public void onPartDestroyed(String partName) {
        List<HitboxPart> cluster = preciseHitboxClusters.get(partName);
        if (cluster != null) {
            cluster.forEach(hitbox -> {
                if (hitbox != null) {
                    hitbox.discard();
                }
            });
            preciseHitboxClusters.remove(partName);
        }
    }

    public String rayTracePreciseParts(Vec3 start, Vec3 end) {
        double closestDistance = Double.MAX_VALUE;
        String hitPart = null;

        for (Map.Entry<String, BodyPart> entry : partManager.getBodyParts().entrySet()) {
            String partName = entry.getKey();
            BodyPart part = entry.getValue();

            if (part.isDestroyed()) continue;

            try {
                BoneTransform boneTransform = parent.getServerAnimationSystem().getBoneTransform(part.getBoneName());
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

    public void syncHitboxesToClient() {
        if (parent.level().isClientSide) return;

        List<HitboxSyncPacket.HitboxData> hitboxDataList = new ArrayList<>();

        for (BodyPart part : partManager.getBodyParts().values()) {
            if (part.isDestroyed()) continue;

            BoneTransform boneTransform = parent.getServerAnimationSystem().getBoneTransform(part.getBoneName());
            if (boneTransform != null) {
                Vec3 boneWorldPos = boneTransform.position;
                AABB baseHitbox = part.getBaseHitbox();
                AABB worldHitbox = baseHitbox.move(boneWorldPos);

                hitboxDataList.add(new HitboxSyncPacket.HitboxData(
                        part.getPartName(),
                        boneWorldPos,
                        boneTransform.rotation,
                        worldHitbox.minX, worldHitbox.minY, worldHitbox.minZ,
                        worldHitbox.maxX, worldHitbox.maxY, worldHitbox.maxZ
                ));

                System.out.println("同步部位: " + part.getPartName());
                System.out.println("  位置: " + boneWorldPos);
                System.out.println("  旋转: " + boneTransform.rotation);
                System.out.println("  AABB: " + worldHitbox);
            }
        }

        if (!hitboxDataList.isEmpty()) {
            HitboxSyncPacket packet = new HitboxSyncPacket(parent.getId(), hitboxDataList);
            NetworkHandler.sendToAllTracking(packet, parent);
        }
    }

    public List<HitboxPart> getPreciseHitboxCluster(String partName) {
        return preciseHitboxClusters.get(partName);
    }

    public String getDebugInfo() {
        return "Precise Hitbox Clusters: " + preciseHitboxClusters.size();
    }

    // 工具方法
    private Vec3 transformVertexWithPivot(Vertex vertex, BoneTransform animationTransform, float[] pivot) {
        float scaleFactor = 1.0f / 16.0f;
        Vector3f standardPos = new Vector3f(
                (vertex.x - pivot[0]) * scaleFactor,
                (vertex.y - pivot[1]) * scaleFactor,
                (vertex.z - pivot[2]) * scaleFactor
        );

        Vector3f rotatedPos = animationTransform.rotation.transform(standardPos);
        rotatedPos.mul(animationTransform.scale);

        return new Vec3(
                animationTransform.position.x + rotatedPos.x + pivot[0] * scaleFactor,
                animationTransform.position.y + rotatedPos.y + pivot[1] * scaleFactor,
                animationTransform.position.z + rotatedPos.z + pivot[2] * scaleFactor
        );
    }

    private List<Vec3> transformCubeVertices(GeometryModel.Cube cube, BoneTransform transform, float[] pivot) {
        List<Vec3> worldVertices = new ArrayList<>();
        for (Vertex vertex : cube.getVertices()) {
            Vec3 worldVertex = transformVertexWithPivot(vertex, transform, pivot);
            worldVertices.add(worldVertex);
        }
        return worldVertices;
    }

    private AABB createOBBFromVertices(List<Vec3> vertices) {
        if (vertices.isEmpty()) return new AABB(0, 0, 0, 0, 0, 0);

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        for (Vec3 vertex : vertices) {
            minX = Math.min(minX, vertex.x);
            minY = Math.min(minY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxX = Math.max(maxX, vertex.x);
            maxY = Math.max(maxY, vertex.y);
            maxZ = Math.max(maxZ, vertex.z);
        }

        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private Vec3 getAABBCenter(AABB aabb) {
        return new Vec3(
                (aabb.minX + aabb.maxX) / 2,
                (aabb.minY + aabb.maxY) / 2,
                (aabb.minZ + aabb.maxZ) / 2
        );
    }

    private AABB transformHitboxSimple(AABB localHitbox, BoneTransform transform) {
        return localHitbox.move(transform.position);
    }
}