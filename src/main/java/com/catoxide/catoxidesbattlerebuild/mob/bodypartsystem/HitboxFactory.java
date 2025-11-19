package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import com.catoxide.catoxidesbattlerebuild.registry.ModEntities;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class HitboxFactory {
    private final ModularZombie parent;

    public HitboxFactory(ModularZombie parent) {
        this.parent = parent;
    }

    public List<HitboxPart> createHitboxCluster(BodyPart bodyPart, BoneTransform transform) {
        List<HitboxPart> cluster = new ArrayList<>();
        List<GeometryModel.Cube> cubes = bodyPart.getCubes();

        for (int i = 0; i < cubes.size(); i++) {
            GeometryModel.Cube cube = cubes.get(i);
            HitboxPart hitbox = createSingleHitbox(bodyPart, cube, i, transform);
            if (hitbox != null) {
                cluster.add(hitbox);
            }
        }

        return cluster;
    }

    private HitboxPart createSingleHitbox(BodyPart bodyPart, GeometryModel.Cube cube, int index, BoneTransform transform) {
        try {
            // 使用变换管道计算世界坐标
            ModularTransformPipeline pipeline = parent.getBodyPartManager().getTransformPipeline();
            List<Vec3> worldVertices = new ArrayList<>();

            for (Vertex vertex : cube.getVertices()) {
                Vec3 worldVertex = pipeline.transformVertex(vertex, transform, bodyPart.getPivot());
                worldVertices.add(worldVertex);
            }

            AABB worldOBB = createOBBFromVertices(worldVertices);
            Vec3 center = getAABBCenter(worldOBB);

            // 创建碰撞箱实体
            HitboxPart hitbox = new HitboxPart(ModEntities.HITBOX_PART.get(), parent.level());
            hitbox.initialize(parent, bodyPart.getPartName() + "_precise_" + index);
            hitbox.setPos(center.x, center.y, center.z);
            hitbox.setBoundingBox(worldOBB);

            parent.level().addFreshEntity(hitbox);
            return hitbox;

        } catch (Exception e) {
            System.err.println("创建碰撞箱失败: " + bodyPart.getPartName() + "_" + index + " - " + e.getMessage());
            return null;
        }
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