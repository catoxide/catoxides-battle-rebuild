package com.catoxide.catoxidesbattlerebuild.client.geometry;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端立方体集合类
 * 存储实体特定的立方体数据，用于碰撞检测和渲染
 * 对齐服务端CubeCollection
 */
public class ClientCubeCollection {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientCubeCollection.class);
    
    // 实体ID
    private final long entityId;
    
    // 骨骼名称
    private final String boneName;
    
    // 立方体ID
    private final String id;
    
    // 枢轴点
    private final Vec3 pivot;
    
    // 立方体大小
    private final Vec3 size;
    
    // 旋转角度
    private final Vec3 rotation;
    
    // 原点偏移量
    private final Vec3 originOffset;
    
    // 世界变换矩阵
    private Matrix4f worldTransform = new Matrix4f().identity();
    
    // OBB参数
    private Matrix4f obbOrientation = new Matrix4f().identity();
    private Vec3 obbHalfSize = new Vec3(0, 0, 0);
    
    // 本地顶点
    private List<Vec3> localVertices = new ArrayList<>();
    
    // 世界顶点
    private List<Vec3> worldVertices = new ArrayList<>();

    /**
     * 从静态数据创建ClientCubeCollection
     */
    public static ClientCubeCollection fromStaticData(
            long entityId,
            String boneName,
            com.catoxide.catoxidesbattlerebuild.client.models.ClientBoneModelData.CubeStaticData cubeStaticData) {
        LOGGER.debug("[ClientCubeCollection] Creating cube from static data: entityId={}, boneName={}, cubeId={}", 
                entityId, boneName, cubeStaticData.id());
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
        this.obbHalfSize = new Vec3(size.x / 2.0, size.y / 2.0, size.z / 2.0);
        
        LOGGER.debug("[ClientCubeCollection] Created cube: id={}, size={}, pivot={}", 
                id, size, pivot);
    }

    private static List<Vec3> calculateLocalVertices(Vec3 pivot, Vec3 size, Vec3 rotation, Vec3 originOffset) {
        List<Vec3> vertices = new ArrayList<>();
        
        double halfX = size.x / 2.0;
        double halfY = size.y / 2.0;
        double halfZ = size.z / 2.0;

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

        Vec3 adjustedPivot = new Vec3(pivot.x + originOffset.x, pivot.y + originOffset.y, pivot.z + originOffset.z);

        // 创建旋转矩阵
        org.joml.Matrix4f rotationMatrix = new org.joml.Matrix4f()
            .rotationXYZ(
                (float) Math.toRadians(rotation.x),
                (float) Math.toRadians(rotation.y),
                (float) Math.toRadians(rotation.z)
            );

        for (Vec3 corner : corners) {
            // 应用旋转变换
            org.joml.Vector3f cornerVec = new org.joml.Vector3f((float)corner.x, (float)corner.y, (float)corner.z);
            org.joml.Vector3f rotatedCorner = rotationMatrix.transformPosition(cornerVec);
            
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
        this.worldVertices.clear();
        
        for (Vec3 localVertex : localVertices) {
            org.joml.Vector3f vertex = new org.joml.Vector3f((float)localVertex.x, (float)localVertex.y, (float)localVertex.z);
            org.joml.Vector4f transformed = transform.transform(new org.joml.Vector4f(vertex, 1.0f));
            Vec3 worldVertex = new Vec3(
                transformed.x() / transformed.w(),
                transformed.y() / transformed.w(),
                transformed.z() / transformed.w()
            );
            this.worldVertices.add(worldVertex);
        }
        
        LOGGER.debug("[ClientCubeCollection] Updated world transform for cube: id={}, worldVertices={}", 
                id, worldVertices.size());
    }

    /**
     * 检查点是否在立方体内
     */
    public boolean containsPoint(Vec3 point) {
        Matrix4f inverseTransform = new Matrix4f(worldTransform).invert();
        org.joml.Vector3f localPoint = new org.joml.Vector3f((float) point.x, (float) point.y, (float) point.z);
        org.joml.Vector4f homogeneousPoint = new org.joml.Vector4f(localPoint, 1.0f);
        org.joml.Vector4f localHomogeneous = inverseTransform.transform(homogeneousPoint);
        Vec3 localCoord = new Vec3(
            localHomogeneous.x() / localHomogeneous.w(),
            localHomogeneous.y() / localHomogeneous.w(),
            localHomogeneous.z() / localHomogeneous.w()
        );

        Vec3 center = new Vec3(pivot.x + originOffset.x, pivot.y + originOffset.y, pivot.z + originOffset.z);
        double halfX = size.x / 2.0;
        double halfY = size.y / 2.0;
        double halfZ = size.z / 2.0;

        return localCoord.x >= center.x - halfX && localCoord.x <= center.x + halfX &&
               localCoord.y >= center.y - halfY && localCoord.y <= center.y + halfY &&
               localCoord.z >= center.z - halfZ && localCoord.z <= center.z + halfZ;
    }

    public long getEntityId() { return entityId; }
    public String getBoneName() { return boneName; }
    public String getId() { return id; }
    public Vec3 getPivot() { return pivot; }
    public Vec3 getSize() { return size; }
    public Vec3 getRotation() { return rotation; }
    public Vec3 getOriginOffset() { return originOffset; }
    public Matrix4f getWorldTransform() { return worldTransform; }
    public Matrix4f getObbOrientation() { return obbOrientation; }
    public Vec3 getObbHalfSize() { return obbHalfSize; }
    public List<Vec3> getLocalVertices() { return new ArrayList<>(localVertices); }
    public List<Vec3> getWorldVertices() { return new ArrayList<>(worldVertices); }
    
    /**
     * 获取旋转四元数（从Vec3欧拉角转换）
     */
    public org.joml.Quaternionf getRotationQuaternion() {
        org.joml.Quaternionf quaternion = new org.joml.Quaternionf();
        quaternion.rotationXYZ(
            (float) Math.toRadians(rotation.x),
            (float) Math.toRadians(rotation.y),
            (float) Math.toRadians(rotation.z)
        );
        return quaternion;
    }
}