package com.catoxide.catoxidesbattlerebuild.server.temp.components;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Quaternionf;

import java.util.UUID;

/**
 * 单个Cube的受击盒组件 - 表示单个几何立方体的OBB受击盒
 */
public class CubeCollection {
    private final UUID entityId;
    private final String boneName;
    private final String cubeId;  // 用于标识单个cube
    private final int boneHash;

    // OBB参数 (局部空间，相对于骨骼原点)
    private final Vector3f center;
    private final Vector3f halfExtents;  // 半边长
    private final Quaternionf orientation;

    // 运行时数据 (世界空间)
    private Vector3f worldCenter;
    private Quaternionf worldOrientation;
    private Matrix4f transformMatrix;

    // 配置
    private float damageMultiplier = 1.0f;
    private boolean isCritical = false;
    private boolean isArmored = false;
    private String hitSound = "entity.hit";

    // 状态
    private float lastHitTime = 0;
    private int hitCount = 0;
    private boolean active = true;

    /**
     * 构造器
     * @param entityId 实体ID
     * @param boneName 骨骼名称
     * @param cubeId Cube标识符
     * @param center 局部空间中心点
     * @param size 完整尺寸
     * @param orientation 方向
     */
    public CubeCollection(UUID entityId, String boneName, String cubeId,
                          Vector3f center, Vector3f size,
                          Quaternionf orientation) {
        this.entityId = entityId;
        this.boneName = boneName;
        this.cubeId = cubeId;
        this.boneHash = boneName.hashCode();
        this.center = new Vector3f(center);
        this.halfExtents = new Vector3f(size).mul(0.5f);
        this.orientation = new Quaternionf(orientation);
        this.worldCenter = new Vector3f();
        this.worldOrientation = new Quaternionf();
        this.transformMatrix = new Matrix4f().identity();
    }

    /**
     * 更新世界变换
     * @param boneWorldMatrix 骨骼的世界变换矩阵
     */
    public void updateWorldTransform(Matrix4f boneWorldMatrix) {
        // 计算cube的局部变换 (相对于骨骼)
        Matrix4f localTransform = new Matrix4f()
                .translate(center)
                .rotate(orientation);

        // 计算世界变换
        transformMatrix.set(boneWorldMatrix).mul(localTransform);

        // 提取世界位置和方向
        worldCenter = transformMatrix.getTranslation(new Vector3f());
        worldOrientation = transformMatrix.getNormalizedRotation(new Quaternionf());
    }

    /**
     * 检查点是否在OBB内
     * @param point 世界空间中的点
     * @return 是否在OBB内
     */
    public boolean containsPoint(Vector3f point) {
        if (!active) return false;

        // 转换到OBB局部空间
        Matrix4f inverseTransform = new Matrix4f(transformMatrix).invert();
        Vector3f localPoint = inverseTransform.transformPosition(point, new Vector3f());

        // 检查是否在AABB内 (在局部空间)
        return Math.abs(localPoint.x()) <= halfExtents.x() &&
               Math.abs(localPoint.y()) <= halfExtents.y() &&
               Math.abs(localPoint.z()) <= halfExtents.z();
    }

    /**
     * 获取OBB的8个顶点
     * @return 世界空间中的顶点数组
     */
    public Vector3f[] getOBBVertices() {
        Vector3f[] vertices = new Vector3f[8];
        float[] extents = {halfExtents.x(), halfExtents.y(), halfExtents.z()};

        for (int i = 0; i < 8; i++) {
            Vector3f localVertex = new Vector3f(
                    extents[0] * ((i & 1) == 0 ? -1 : 1),
                    extents[1] * ((i & 2) == 0 ? -1 : 1),
                    extents[2] * ((i & 4) == 0 ? -1 : 1)
            );
            vertices[i] = transformMatrix.transformPosition(localVertex, new Vector3f());
        }

        return vertices;
    }

    /**
     * 获取OBB的三个轴
     * @return 世界空间中的轴向量数组
     */
    public Vector3f[] getAxes() {
        Vector3f[] axes = new Vector3f[3];
        Matrix4f rotMatrix = new Matrix4f().rotation(worldOrientation);
        axes[0] = rotMatrix.transformDirection(new Vector3f(1, 0, 0));
        axes[1] = rotMatrix.transformDirection(new Vector3f(0, 1, 0));
        axes[2] = rotMatrix.transformDirection(new Vector3f(0, 0, 1));
        return axes;
    }

    /**
     * 记录受击
     */
    public void recordHit(float damage, String damageType) {
        this.lastHitTime = System.currentTimeMillis() / 1000.0f;
        this.hitCount++;

        // 触发粒子效果、声音等
        triggerHitEffects(damage, damageType);
    }

    private void triggerHitEffects(float damage, String damageType) {
        // 这里可以触发音效、粒子效果等
        // 实际实现会依赖于你的渲染和音效系统
    }

    // Getter 和 Setter 方法
    public UUID getEntityId() { return entityId; }
    public String getBoneName() { return boneName; }
    public String getCubeId() { return cubeId; }
    public int getBoneHash() { return boneHash; }
    public Vector3f getWorldCenter() { return new Vector3f(worldCenter); }
    public Vector3f getHalfExtents() { return new Vector3f(halfExtents); }
    public Quaternionf getWorldOrientation() { return new Quaternionf(worldOrientation); }
    public float getDamageMultiplier() { return damageMultiplier; }
    public void setDamageMultiplier(float multiplier) { this.damageMultiplier = multiplier; }
    public boolean isCritical() { return isCritical; }
    public void setCritical(boolean critical) { isCritical = critical; }
    public boolean isArmored() { return isArmored; }
    public void setArmored(boolean armored) { isArmored = armored; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public int getHitCount() { return hitCount; }
    public float getLastHitTime() { return lastHitTime; }

    @Override
    public String toString() {
        return String.format("CubeHitbox[entity=%s, bone=%s, cube=%s, hits=%d]",
                entityId.toString().substring(0, 8), boneName, cubeId, hitCount);
    }

    /**
     * 快速创建副本（用于网络传输）
     */
    public CubeCollection copy() {
        return new CubeCollection(
                entityId, boneName, cubeId,
                new Vector3f(center), halfExtents.mul(2.0f, new Vector3f()),  // 注意：size是halfExtents*2
                new Quaternionf(orientation)
        );
    }
}
