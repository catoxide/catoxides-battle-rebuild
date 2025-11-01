package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import com.catoxide.catoxidesbattlerebuild.registry.ModEntities;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public class HitboxFactory {
    private final ModularZombie parent;
    private final HitboxConfigurator configurator;

    public HitboxFactory(ModularZombie parent) {
        this.parent = parent;
        this.configurator = new HitboxConfigurator();
    }

    public List<HitboxPart> createHitboxCluster(BodyPart bodyPart, BoneTransform transform) {
        List<HitboxPart> cluster = new ArrayList<>();
        List<GeometryModel.Cube> cubes = bodyPart.getCubes();
        HitboxConfigurator.HitboxConfiguration config = configurator.getConfiguration(bodyPart.getPartName());

        for (int i = 0; i < cubes.size(); i++) {
            GeometryModel.Cube cube = cubes.get(i);
            HitboxPart hitbox = createSingleHitbox(bodyPart, cube, i, transform, config);
            if (hitbox != null) {
                cluster.add(hitbox);
            }
        }

        return cluster;
    }

    private HitboxPart createSingleHitbox(BodyPart bodyPart, GeometryModel.Cube cube, int index,
                                          BoneTransform transform, HitboxConfigurator.HitboxConfiguration config) {
        try {
            // 计算世界坐标下的OBB
            List<Vec3> worldVertices = calculateWorldVertices(cube, transform, bodyPart.getPivot());
            AABB worldOBB = createOBBFromVertices(worldVertices);
            Vec3 center = getAABBCenter(worldOBB);

            // 创建碰撞箱实体
            HitboxPart hitbox = new HitboxPart(ModEntities.HITBOX_PART.get(), parent.level());
            hitbox.initialize(parent, bodyPart.getPartName() + "_precise_" + index);

            // 应用配置
            applyConfiguration(hitbox, config);

            // 设置位置和边界
            hitbox.setPos(center.x, center.y, center.z);
            hitbox.setBoundingBox(worldOBB);

            // 添加到世界
            parent.level().addFreshEntity(hitbox);

            return hitbox;

        } catch (Exception e) {
            System.err.println("创建碰撞箱失败: " + bodyPart.getPartName() + "_" + index + " - " + e.getMessage());
            return null;
        }
    }

    private List<Vec3> calculateWorldVertices(GeometryModel.Cube cube, BoneTransform transform, float[] pivot) {
        List<Vec3> worldVertices = new ArrayList<>();
        float scaleFactor = 1.0f / 16.0f;

        for (Vertex vertex : cube.getVertices()) {
            // 相对于枢轴点的位置
            Vector3f standardPos = new Vector3f(
                    (vertex.x - pivot[0]) * scaleFactor,
                    (vertex.y - pivot[1]) * scaleFactor,
                    (vertex.z - pivot[2]) * scaleFactor
            );

            // 应用旋转和缩放
            Vector3f rotatedPos = transform.rotation.transform(standardPos);
            rotatedPos.mul(transform.scale);

            // 计算世界坐标
            Vec3 worldVertex = new Vec3(
                    transform.position.x + rotatedPos.x + pivot[0] * scaleFactor,
                    transform.position.y + rotatedPos.y + pivot[1] * scaleFactor,
                    transform.position.z + rotatedPos.z + pivot[2] * scaleFactor
            );

            worldVertices.add(worldVertex);
        }

        return worldVertices;
    }

    private void applyConfiguration(HitboxPart hitbox, HitboxConfigurator.HitboxConfiguration config) {
        // 应用自定义旋转
        if (config.getCustomRotation() != null) {
            hitbox.setRotation(config.getCustomRotation());
        }

        // 可以在这里添加更多配置应用逻辑
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
}
