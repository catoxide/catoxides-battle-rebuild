package com.catoxide.catoxidesbattlerebuild.server.temp;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import com.catoxide.catoxidesbattlerebuild.server.temp.components.CubeCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 骨骼受击盒组件 - 管理多个Cube的OBB受击盒
 */
public class BoneHitboxComponent {
    private final UUID entityId;
    private final String boneName;
    private final int boneHash;

    // 管理多个Cube受击盒
    private final Map<String, CubeCollection> cubeHitboxes = new ConcurrentHashMap<>();

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
     * 构造器 - 从骨骼的cubes创建受击盒
     */
    public BoneHitboxComponent(UUID entityId, String boneName, GeoBone bone) {
        this.entityId = entityId;
        this.boneName = boneName;
        this.boneHash = boneName.hashCode();

        // 为每个cube创建受击盒
        List<GeoCube> cubes = bone.getCubes();
        if (cubes != null && !cubes.isEmpty()) {
            for (int i = 0; i < cubes.size(); i++) {
                GeoCube cube = cubes.get(i);
                String cubeId = "cube_" + i;
                
                // 获取cube的pivot和size
                var origin = cube.pivot();
                var size = cube.size();
                
                // 创建cube受击盒
                CubeCollection cubeHitbox = new CubeCollection(
                        entityId,
                        boneName,
                        cubeId,
                        new Vector3f((float)origin.x(), (float)origin.y(), (float)origin.z()),
                        new Vector3f((float)size.x(), (float)size.y(), (float)size.z()),
                        new Quaternionf(0.0f, 0.0f, 0.0f, 1.0f) // 默认无旋转
                );
                
                // 设置属性
                cubeHitbox.setDamageMultiplier(damageMultiplier);
                cubeHitbox.setCritical(isCritical);
                cubeHitbox.setArmored(isArmored);
                
                cubeHitboxes.put(cubeId, cubeHitbox);
            }
        }
    }

    /**
     * 更新世界变换
     * @param boneWorldMatrix 骨骼的世界变换矩阵
     */
    public void updateWorldTransform(Matrix4f boneWorldMatrix) {
        // 更新所有cube的世界变换
        for (CubeCollection cubeHitbox : cubeHitboxes.values()) {
            if (cubeHitbox.isActive()) {
                cubeHitbox.updateWorldTransform(boneWorldMatrix);
            }
        }
    }

    /**
     * 检查点是否在任何cube内
     * @param point 世界空间中的点
     * @return 是否在任何cube内
     */
    public boolean containsPoint(Vector3f point) {
        if (!active) return false;

        for (CubeCollection cubeHitbox : cubeHitboxes.values()) {
            if (cubeHitbox.containsPoint(point)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取所有cube的OBB顶点
     * @return 世界空间中的顶点列表
     */
    public List<Vector3f> getAllOBBVertices() {
        List<Vector3f> allVertices = new ArrayList<>();

        for (CubeCollection cubeHitbox : cubeHitboxes.values()) {
            if (cubeHitbox.isActive()) {
                Vector3f[] vertices = cubeHitbox.getOBBVertices();
                for (Vector3f vertex : vertices) {
                    allVertices.add(vertex);
                }
            }
        }
        return allVertices;
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
    public int getBoneHash() { return boneHash; }
    public Map<String, CubeCollection> getCubeHitboxes() { return cubeHitboxes; }

    public float getDamageMultiplier() { return damageMultiplier; }
    public void setDamageMultiplier(float damageMultiplier) {
        this.damageMultiplier = damageMultiplier;
        // 同步到所有cube
        for (CubeCollection cubeHitbox : cubeHitboxes.values()) {
            cubeHitbox.setDamageMultiplier(damageMultiplier);
        }
    }

    public boolean isCritical() { return isCritical; }
    public void setCritical(boolean critical) {
        isCritical = critical;
        // 同步到所有cube
        for (CubeCollection cubeHitbox : cubeHitboxes.values()) {
            cubeHitbox.setCritical(critical);
        }
    }

    public boolean isArmored() { return isArmored; }
    public void setArmored(boolean armored) {
        isArmored = armored;
        // 同步到所有cube
        for (CubeCollection cubeHitbox : cubeHitboxes.values()) {
            cubeHitbox.setArmored(armored);
        }
    }

    public boolean isActive() { return active; }
    public void setActive(boolean active) {
        this.active = active;
        // 同步到所有cube
        for (CubeCollection cubeHitbox : cubeHitboxes.values()) {
            cubeHitbox.setActive(active);
        }
    }

    public int getHitCount() { return hitCount; }
    public float getLastHitTime() { return lastHitTime; }

    // 以下方法为网络同步添加，返回第一个cube的信息作为代表
    public Vector3f getWorldCenter() {
        if (cubeHitboxes.isEmpty()) {
            return new Vector3f(0, 0, 0);
        }
        return cubeHitboxes.values().iterator().next().getWorldCenter();
    }

    public Vector3f getHalfExtents() {
        if (cubeHitboxes.isEmpty()) {
            return new Vector3f(0, 0, 0);
        }
        return cubeHitboxes.values().iterator().next().getHalfExtents();
    }

    public Quaternionf getWorldOrientation() {
        if (cubeHitboxes.isEmpty()) {
            return new Quaternionf();
        }
        return cubeHitboxes.values().iterator().next().getWorldOrientation();
    }

    @Override
    public String toString() {
        return String.format("BoneHitbox[entity=%s, bone=%s, cubes=%d, hits=%d]",
                entityId.toString().substring(0, 8), boneName, cubeHitboxes.size(), hitCount);
    }

    /**
     * 客户端构造器 - 直接使用世界坐标（网络同步专用）
     * 注意：此构造器用于网络同步，不包含cube级别的详细信息
     */
    public BoneHitboxComponent(UUID entityId, String boneName,
                               Vector3f worldCenter, Vector3f halfExtents,
                               Quaternionf worldOrientation,
                               float damageMultiplier, boolean isCritical,
                               boolean isArmored, boolean isActive) {
        this.entityId = entityId;
        this.boneName = boneName;
        this.boneHash = boneName.hashCode();

        // 配置
        this.damageMultiplier = damageMultiplier;
        this.isCritical = isCritical;
        this.isArmored = isArmored;

        // 状态
        this.active = isActive;
    }

    /**
     * 快速创建副本（用于网络传输）
     */
    public BoneHitboxComponent copy() {
        // 创建一个新的BoneHitboxComponent
        BoneHitboxComponent copy = new BoneHitboxComponent(
                entityId, boneName,
                getWorldCenter(),
                getHalfExtents(),
                getWorldOrientation(),
                damageMultiplier, isCritical, isArmored, active
        );

        // 复制cube受击盒
        for (Map.Entry<String, CubeCollection> entry : cubeHitboxes.entrySet()) {
            CubeCollection originalCube = entry.getValue();
            CubeCollection copiedCube = originalCube.copy();
            copy.cubeHitboxes.put(entry.getKey(), copiedCube);
        }

        return copy;
    }
}