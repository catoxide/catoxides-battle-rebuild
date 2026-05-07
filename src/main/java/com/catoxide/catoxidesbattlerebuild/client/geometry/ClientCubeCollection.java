package com.catoxide.catoxidesbattlerebuild.client.geometry;

import com.catoxide.catoxidesbattlerebuild.client.models.ClientBoneModelData;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class ClientCubeCollection {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientCubeCollection.class);

    private final long entityId;
    private final String boneName;
    private final String id;
    private final Vec3 pivot;
    private final Vec3 size;
    private final Vec3 rotation;
    private final Vec3 originOffset;
    private final List<Vec3> localVertices;
    private final Vec3 obbHalfSize;
    private Matrix4f worldTransform;

    public ClientCubeCollection(long entityId, String boneName, String id, Vec3 pivot, 
            Vec3 size, Vec3 rotation, Vec3 originOffset) {
        this.entityId = entityId;
        this.boneName = boneName;
        this.id = id;
        this.pivot = pivot != null ? pivot : new Vec3(0, 0, 0);
        this.size = size != null ? size : new Vec3(1, 1, 1);
        this.rotation = rotation != null ? rotation : new Vec3(0, 0, 0);
        this.originOffset = originOffset != null ? originOffset : new Vec3(0, 0, 0);
        
        this.localVertices = calculateLocalVertices(this.pivot, this.size, this.rotation, this.originOffset);
        this.obbHalfSize = new Vec3((size.x / 16.0) / 2.0, (size.y / 16.0) / 2.0, (size.z / 16.0) / 2.0);
        
        LOGGER.debug("[ClientCubeCollection] Created cube: id={}, size={}, pivot={}", 
                id, size, pivot);
    }

    /**
     * 从静态数据创建 ClientCubeCollection
     */
    public static ClientCubeCollection fromStaticData(long entityId, String boneName, 
            ClientBoneModelData.CubeStaticData cubeStaticData) {
        return new ClientCubeCollection(
            entityId,
            boneName,
            cubeStaticData.id(),
            cubeStaticData.pivot(),
            cubeStaticData.size(),
            cubeStaticData.rotation(),
            cubeStaticData.originOffset()
        );
    }

    private static List<Vec3> calculateLocalVertices(Vec3 pivot, Vec3 size, Vec3 rotation, Vec3 originOffset) {
        List<Vec3> vertices = new ArrayList<>();
        
        // 将像素坐标转换为方块坐标（除以16）
        double halfX = (size.x / 16.0) / 2.0;
        double halfY = (size.y / 16.0) / 2.0;
        double halfZ = (size.z / 16.0) / 2.0;

        Vec3[] corners = {
            new Vec3(-halfX, -halfY, -halfZ),
            new Vec3(halfX, -halfY, -halfZ),
            new Vec3(halfX, halfY, -halfZ),
            new Vec3(-halfX, halfY, -halfZ),
            new Vec3(-halfX, -halfY, halfZ),
            new Vec3(halfX, -halfY, halfZ),
            new Vec3(halfX, halfY, halfZ),
            new Vec3(-halfX, halfY, halfZ),
        };

        // 将pivot和originOffset也转换为方块坐标
        Vec3 adjustedPivot = new Vec3(
            (pivot.x + originOffset.x) / 16.0,
            (pivot.y + originOffset.y) / 16.0,
            (pivot.z + originOffset.z) / 16.0
        );

        // 创建旋转矩阵
        Matrix4f rotationMatrix = new Matrix4f()
            .rotationXYZ(
                (float) Math.toRadians(rotation.x),
                (float) Math.toRadians(rotation.y),
                (float) Math.toRadians(rotation.z)
            );

        for (Vec3 corner : corners) {
            // 应用旋转变换
            Vector3f cornerVec = new Vector3f((float)corner.x, (float)corner.y, (float)corner.z);
            Vector3f rotatedCorner = new Vector3f();
            rotationMatrix.transformPosition(cornerVec, rotatedCorner);
            
            // 加上枢轴点偏移
            Vec3 transformedCorner = new Vec3(
                rotatedCorner.x() + adjustedPivot.x,
                rotatedCorner.y() + adjustedPivot.y,
                rotatedCorner.z() + adjustedPivot.z
            );
            vertices.add(transformedCorner);
        }

        return vertices;
    }

    /**
     * 更新世界变换
     */
    public void updateWorldTransform(Matrix4f transform) {
        this.worldTransform = transform;
    }

    /**
     * 获取世界坐标下的顶点
     */
    public List<Vec3> getWorldVertices() {
        if (worldTransform == null) {
            return localVertices;
        }

        List<Vec3> worldVertices = new ArrayList<>();
        for (Vec3 localVertex : localVertices) {
            Vector4f vec = new Vector4f(
                (float) localVertex.x, 
                (float) localVertex.y, 
                (float) localVertex.z, 
                1.0f
            );
            
            // 直接使用 worldTransform（已经是 JOML Matrix4f）
            vec.mul(worldTransform);
            
            worldVertices.add(new Vec3(vec.x(), vec.y(), vec.z()));
        }
        return worldVertices;
    }

    /**
     * 检查点是否在立方体内部
     */
    public boolean containsPoint(Vec3 point) {
        List<Vec3> worldVertices = getWorldVertices();
        if (worldVertices.size() < 8) {
            return false;
        }

        // 简单的AABB检查
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE, maxZ = Double.MIN_VALUE;

        for (Vec3 vertex : worldVertices) {
            minX = Math.min(minX, vertex.x);
            minY = Math.min(minY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxX = Math.max(maxX, vertex.x);
            maxY = Math.max(maxY, vertex.y);
            maxZ = Math.max(maxZ, vertex.z);
        }

        return point.x >= minX && point.x <= maxX &&
               point.y >= minY && point.y <= maxY &&
               point.z >= minZ && point.z <= maxZ;
    }

    /**
     * 获取OBB的半尺寸（方块坐标）
     */
    public Vec3 getObbHalfSize() {
        return obbHalfSize;
    }

    public long getEntityId() {
        return entityId;
    }

    public String getBoneName() {
        return boneName;
    }

    public String getId() {
        return id;
    }

    public Vec3 getPivot() {
        return pivot;
    }

    public Vec3 getSize() {
        return size;
    }

    public Vec3 getRotation() {
        return rotation;
    }

    public Vec3 getOriginOffset() {
        return originOffset;
    }

    public List<Vec3> getLocalVertices() {
        return localVertices;
    }

    public Matrix4f getWorldTransform() {
        return worldTransform;
    }
}
