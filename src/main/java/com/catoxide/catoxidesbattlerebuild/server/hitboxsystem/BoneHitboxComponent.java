package com.catoxide.catoxidesbattlerebuild.server.hitboxsystem;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import software.bernie.geckolib.cache.object.GeoBone;

import java.util.List;
import java.util.UUID;

/**
 * 骨骼受击盒组件 - 表示单个骨骼的OBB受击盒
 */
public class BoneHitboxComponent {
    private final UUID entityId;
    private final String boneName;
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

    public BoneHitboxComponent(UUID entityId, String boneName,
                               Vector3f center, Vector3f size,
                               Quaternionf orientation) {
        this.entityId = entityId;
        this.boneName = boneName;
        this.boneHash = boneName.hashCode();
        this.center = new Vector3f(center);
        this.halfExtents = new Vector3f(size).mul(0.5f);
        this.orientation = new Quaternionf(orientation);
        this.worldCenter = new Vector3f();
        this.worldOrientation = new Quaternionf();
    }

    /**
     * 更新OBB的世界变换
     */
    public void updateWorldTransform(Matrix4f boneWorldMatrix) {
        this.transformMatrix = new Matrix4f(boneWorldMatrix);

        // 变换中心点
        Vector3f transformedCenter = boneWorldMatrix.transformPosition(
                new Vector3f(center), new Vector3f()
        );
        this.worldCenter.set(transformedCenter);

        // 提取旋转 (需要从矩阵中提取旋转部分)
        this.worldOrientation = extractRotation(boneWorldMatrix)
                .mul(orientation, new Quaternionf());
    }

    /**
     * 获取OBB的8个顶点 (用于渲染和调试)
     */
    public List<Vector3f> getOBBVertices() {
        Vector3f[] axes = getAxes();
        List<Vector3f> vertices = new java.util.ArrayList<>(8);

        // 生成8个顶点
        for (int i = 0; i < 8; i++) {
            Vector3f vertex = new Vector3f(worldCenter);

            // 根据二进制位确定方向
            if ((i & 1) != 0) vertex.add(axes[0].mul(halfExtents.x));
            else vertex.sub(axes[0].mul(halfExtents.x));

            if ((i & 2) != 0) vertex.add(axes[1].mul(halfExtents.y));
            else vertex.sub(axes[1].mul(halfExtents.y));

            if ((i & 4) != 0) vertex.add(axes[2].mul(halfExtents.z));
            else vertex.sub(axes[2].mul(halfExtents.z));

            vertices.add(vertex);
        }

        return vertices;
    }

    /**
     * 获取OBB的三个轴向量
     */
    private Vector3f[] getAxes() {
        Vector3f[] axes = new Vector3f[3];
        Matrix4f rotMatrix = new Matrix4f().rotation(worldOrientation);

        axes[0] = rotMatrix.transformDirection(new Vector3f(1, 0, 0));
        axes[1] = rotMatrix.transformDirection(new Vector3f(0, 1, 0));
        axes[2] = rotMatrix.transformDirection(new Vector3f(0, 0, 1));

        return axes;
    }

    /**
     * 检查点是否在OBB内
     */
    public boolean containsPoint(Vector3f point) {
        Vector3f localPoint = point.sub(worldCenter, new Vector3f());
        Vector3f[] axes = getAxes();

        // 在三个轴上的投影
        for (int i = 0; i < 3; i++) {
            float projection = localPoint.dot(axes[i]);
            if (Math.abs(projection) > halfExtents.get(i)) {
                return false;
            }
        }
        return true;
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

    // 工具方法：从矩阵提取旋转
    private Quaternionf extractRotation(Matrix4f matrix) {
        Quaternionf rotation = new Quaternionf();
        matrix.getNormalizedRotation(rotation);
        return rotation;
    }

    // Getters and Setters
    public UUID getEntityId() { return entityId; }
    public String getBoneName() { return boneName; }
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
    public float getLastHitTime() { return lastHitTime; }
    public int getHitCount() { return hitCount; }
    public void resetHitCount() { this.hitCount = 0; }

    @Override
    public String toString() {
        return String.format("BoneHitbox[entity=%s, bone=%s, hits=%d]",
                entityId.toString().substring(0, 8), boneName, hitCount);
    }

    /**
     * 客户端构造器 - 直接使用世界坐标（网络同步专用）
     */
    public BoneHitboxComponent(UUID entityId, String boneName,
                               Vector3f worldCenter, Vector3f halfExtents,
                               Quaternionf worldOrientation,
                               float damageMultiplier, boolean isCritical,
                               boolean isArmored, boolean isActive) {
        this.entityId = entityId;
        this.boneName = boneName;
        this.boneHash = boneName.hashCode();

        // 客户端不需要局部坐标，设为0
        this.center = new Vector3f(0, 0, 0);
        this.halfExtents = new Vector3f(halfExtents);
        this.orientation = new Quaternionf();

        // 直接设置世界坐标
        this.worldCenter = new Vector3f(worldCenter);
        this.worldOrientation = new Quaternionf(worldOrientation);

        // 配置
        this.damageMultiplier = damageMultiplier;
        this.isCritical = isCritical;
        this.isArmored = isArmored;

        // 状态
        this.active = isActive;
        this.transformMatrix = new Matrix4f().identity();
    }

    /**
     * 快速创建副本（用于网络传输）
     */
    public BoneHitboxComponent copy() {
        return new BoneHitboxComponent(
                entityId, boneName,
                new Vector3f(center), halfExtents.mul(2.0f, new Vector3f()),  // 注意：size是halfExtents*2
                new Quaternionf(orientation)
        );
    }
}