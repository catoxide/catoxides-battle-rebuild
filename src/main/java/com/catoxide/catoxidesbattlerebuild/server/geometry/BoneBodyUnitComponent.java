package com.catoxide.catoxidesbattlerebuild.server.geometry;

import java.util.UUID;
import org.joml.Vector3f;
import org.joml.Quaternionf;

/**
 * 骨骼BodyUnit组件
 * 用于网络同步和客户端渲染的BodyUnit数据结构
 */
public class BoneBodyUnitComponent {
    
    private final UUID entityUUID;
    private final String boneName;
    private Vector3f worldCenter;
    private Vector3f halfExtents;
    private Quaternionf worldOrientation;
    private final float damageMultiplier;
    private final boolean isCritical;
    private final boolean isArmored;
    private boolean isActive;
    
    /**
     * 构造函数
     */
    public BoneBodyUnitComponent(UUID entityUUID, String boneName, Vector3f worldCenter, Vector3f halfExtents,
                              Quaternionf worldOrientation, float damageMultiplier, boolean isCritical,
                              boolean isArmored, boolean isActive) {
        this.entityUUID = entityUUID;
        this.boneName = boneName;
        this.worldCenter = worldCenter;
        this.halfExtents = halfExtents;
        this.worldOrientation = worldOrientation;
        this.damageMultiplier = damageMultiplier;
        this.isCritical = isCritical;
        this.isArmored = isArmored;
        this.isActive = isActive;
    }
    
    /**
     * 获取实体UUID
     */
    public UUID getEntityUUID() {
        return entityUUID;
    }
    
    /**
     * 获取骨骼名称
     */
    public String getBoneName() {
        return boneName;
    }
    
    /**
     * 获取世界坐标中心
     */
    public Vector3f getWorldCenter() {
        return worldCenter;
    }
    
    /**
     * 设置世界坐标中心
     */
    public void setWorldCenter(Vector3f worldCenter) {
        this.worldCenter = worldCenter;
    }
    
    /**
     * 获取半边长
     */
    public Vector3f getHalfExtents() {
        return halfExtents;
    }
    
    /**
     * 设置半边长
     */
    public void setHalfExtents(Vector3f halfExtents) {
        this.halfExtents = halfExtents;
    }
    
    /**
     * 获取世界方向
     */
    public Quaternionf getWorldOrientation() {
        return worldOrientation;
    }
    
    /**
     * 设置世界方向
     */
    public void setWorldOrientation(Quaternionf worldOrientation) {
        this.worldOrientation = worldOrientation;
    }
    
    /**
     * 获取伤害乘数
     */
    public float getDamageMultiplier() {
        return damageMultiplier;
    }
    
    /**
     * 是否关键部位
     */
    public boolean isCritical() {
        return isCritical;
    }
    
    /**
     * 是否有护甲
     */
    public boolean isArmored() {
        return isArmored;
    }
    
    /**
     * 是否激活
     */
    public boolean isActive() {
        return isActive;
    }
    
    /**
     * 设置激活状态
     */
    public void setActive(boolean active) {
        isActive = active;
    }
    
    /**
     * 更新世界变换
     */
    public void updateWorldTransform(Vector3f newCenter, Quaternionf newOrientation) {
        this.worldCenter = newCenter;
        this.worldOrientation = newOrientation;
    }
}