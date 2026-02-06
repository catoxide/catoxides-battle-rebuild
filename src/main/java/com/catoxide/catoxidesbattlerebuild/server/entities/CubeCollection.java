package com.catoxide.catoxidesbattlerebuild.server.entities;

import com.catoxide.catoxidesbattlerebuild.server.models.BoneModelData;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 表示一个立方体集合，用于碰撞检测和渲染
 * 这是一个实体特定的动态数据类，包含位置变换等实时信息
 * 
 * 主要用途：
 * 1. 存储从原始模型数据解析出的立方体信息
 * 2. 提供基于OBB（定向包围盒）的碰撞检测
 * 3. 支持骨骼动画变换
 */
public class CubeCollection {
    // 实体ID，用于标识属于哪个实体
    private final long entityId;
    
    // 骨骼名称，标识这个CubeCollection属于哪个骨骼
    private final String boneName;
    
    // 立方体ID，用于唯一标识此立方体
    private final String id;
    
    // Pivot Point（枢轴点/原点）相当于JSON中的origin
    // 这是骨骼变换的中心点
    private final Vec3 pivot;
    
    // 立方体大小
    private final Vec3 size;
    
    // 旋转角度（弧度）
    private final Vec3 rotation;
    
    // 相对于pivot的额外偏移量
    // 这是原始模型文件中的origin相对于pivot的偏移
    private final Vec3 originOffset;
    
    // 当前的世界变换矩阵
    private Matrix4f worldTransform = new Matrix4f().identity();
    
    // OBB（定向包围盒）参数
    private Matrix4f obbOrientation = new Matrix4f().identity();
    private Vec3 obbHalfSize = new Vec3();
    
    // 本地顶点（未变换的立方体顶点）
    private List<Vec3> localVertices = new ArrayList<>();
    
    // 世界顶点（经过变换的立方体顶点）
    private List<Vec3> worldVertices = new ArrayList<>();

    // 静态常量：立方体的8个角点
    private static final Vec3[] CUBE_CORNERS = {
        new Vec3(-0.5, -0.5, -0.5),
        new Vec3(0.5, -0.5, -0.5),
        new Vec3(0.5, -0.5, 0.5),
        new Vec3(-0.5, -0.5, 0.5),
        new Vec3(-0.5, 0.5, -0.5),
        new Vec3(0.5, 0.5, -0.5),
        new Vec3(0.5, 0.5, 0.5),
        new Vec3(-0.5, 0.5, 0.5)
    };

    /**
     * 从静态数据创建CubeCollection
     */
    public static CubeCollection fromStaticData(long entityId, String boneName, BoneModelData.CubeStaticData cubeStaticData) {
        return new CubeCollection(
            entityId,
            boneName,
            cubeStaticData.id(),
            cubeStaticData.pivot(),
            cubeStaticData.size(),
            cubeStaticData.rotation(),
            cubeStaticData.originOffset()
        );
    }

    // 完整构造函数
    public CubeCollection(long entityId, String boneName, String id, Vec3 pivot, Vec3 size, Vec3 rotation, Vec3 originOffset) {
        this.entityId = entityId;
        this.boneName = boneName;
        this.id = id;
        this.pivot = pivot != null ? pivot : new Vec3(0, 0, 0);
        this.size = size != null ? size : new Vec3(1, 1, 1);
        this.rotation = rotation != null ? rotation : new Vec3(0, 0, 0);
        this.originOffset = originOffset != null ? originOffset : new Vec3(0, 0, 0);
        
        // 初始化本地顶点
        this.localVertices = calculateLocalVertices(this.pivot, this.size, this.rotation, this.originOffset);
        this.obbHalfSize = new Vec3(size.x / 2.0, size.y / 2.0, size.z / 2.0);
    }

    /**
     * 计算本地顶点
     * 这些顶点是在没有应用任何变换的情况下立方体的8个角点
     */
    private static List<Vec3> calculateLocalVertices(
            Vec3 pivot, Vec3 size, Vec3 rotation, Vec3 originOffset) {
        List<Vec3> vertices = new ArrayList<>();
        
        // 计算半尺寸
        double halfX = size.x / 2.0;
        double halfY = size.y / 2.0;
        double halfZ = size.z / 2.0;

        // 创建8个角点
        Vec3[] corners = {
            new Vec3(-halfX, -halfY, -halfZ), // 左下后
            new Vec3(halfX, -halfY, -halfZ),  // 右下后
            new Vec3(halfX, halfY, -halfZ),   // 右上后
            new Vec3(-halfX, halfY, -halfZ),  // 左上后
            new Vec3(-halfX, -halfY, halfZ),  // 左下前
            new Vec3(halfX, -halfY, halfZ),   // 右下前
            new Vec3(halfX, halfY, halfZ),    // 右上前
            new Vec3(-halfX, halfY, halfZ),   // 左上前
        };

        // 应用原点偏移
        Vec3 adjustedPivot = new Vec3(pivot.x + originOffset.x, pivot.y + originOffset.y, pivot.z + originOffset.z);

        // 对每个角点应用变换
        for (Vec3 corner : corners) {
            Vec3 transformedCorner = new Vec3(corner.x, corner.y, corner.z);
            // 应用旋转
            // 注意：这里需要实现适当的旋转逻辑
            // 简化版本，实际可能需要更复杂的变换
            transformedCorner = new Vec3(
                transformedCorner.x + adjustedPivot.x,
                transformedCorner.y + adjustedPivot.y,
                transformedCorner.z + adjustedPivot.z
            );
            vertices.add(transformedCorner);
        }

        return vertices;
    }

    /**
     * 更新世界变换
     * 这会重新计算世界顶点
     */
    public void updateWorldTransform(Matrix4f transform) {
        this.worldTransform = transform;
        this.worldVertices.clear();
        
        for (Vec3 localVertex : localVertices) {
            // 将Vec3转换为Vector3f以便进行矩阵变换
            org.joml.Vector3f vertex = new org.joml.Vector3f((float)localVertex.x, (float)localVertex.y, (float)localVertex.z);
            org.joml.Vector4f transformed = transform.transform(new org.joml.Vector4f(vertex, 1.0f));
            Vec3 worldVertex = new Vec3(
                transformed.x() / transformed.w(),
                transformed.y() / transformed.w(),
                transformed.z() / transformed.w()
            );
            this.worldVertices.add(worldVertex);
        }
    }

    /**
     * 检查点是否在立方体内
     * 这是基于世界坐标的检测
     */
    public boolean containsPoint(Vec3 point) {
        // 将世界坐标点转换为本地坐标
        Matrix4f inverseTransform = new Matrix4f(worldTransform).invert();
        org.joml.Vector3f localPoint = new org.joml.Vector3f((float) point.x, (float) point.y, (float) point.z);
        org.joml.Vector4f homogeneousPoint = new org.joml.Vector4f(localPoint, 1.0f);
        org.joml.Vector4f localHomogeneous = inverseTransform.transform(homogeneousPoint);
        Vec3 localCoord = new Vec3(
            localHomogeneous.x() / localHomogeneous.w(),
            localHomogeneous.y() / localHomogeneous.w(),
            localHomogeneous.z() / localHomogeneous.w()
        );

        // 检查本地坐标是否在立方体内
        Vec3 center = new Vec3(pivot.x + originOffset.x, pivot.y + originOffset.y, pivot.z + originOffset.z);
        double halfX = size.x / 2.0;
        double halfY = size.y / 2.0;
        double halfZ = size.z / 2.0;

        return localCoord.x >= center.x - halfX && localCoord.x <= center.x + halfX &&
               localCoord.y >= center.y - halfY && localCoord.y <= center.y + halfY &&
               localCoord.z >= center.z - halfZ && localCoord.z <= center.z + halfZ;
    }

    // Getter方法
    public Vec3 getPivot() { return pivot; }
    public Vec3 getSize() { return size; }
    public Vec3 getRotation() { return rotation; }
    public Vec3 getOriginOffset() { return originOffset; }
    public Matrix4f getWorldTransform() { return worldTransform; }
    public Matrix4f getObbOrientation() { return obbOrientation; }
    public Vec3 getObbHalfSize() { return obbHalfSize; }
    public List<Vec3> getLocalVertices() { return new ArrayList<>(localVertices); }
    public List<Vec3> getWorldVertices() { return new ArrayList<>(worldVertices); }
}