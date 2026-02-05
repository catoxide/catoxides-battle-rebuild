package com.catoxide.catoxidesbattlerebuild.server.temp;

import org.joml.Vector3f;

/**
 * 受击结果记录
 */
public class HitResult {
    private final BoneHitboxComponent hitbox;
    private final Vector3f hitPoint;
    private final float distance;
    private final Vector3f hitDirection;
    private final String hitType;
    private final long timestamp;

    public HitResult(BoneHitboxComponent hitbox, Vector3f hitPoint,
                     float distance, Vector3f hitDirection, String hitType) {
        this.hitbox = hitbox;
        this.hitPoint = new Vector3f(hitPoint);
        this.distance = distance;
        this.hitDirection = new Vector3f(hitDirection);
        this.hitType = hitType;
        this.timestamp = System.currentTimeMillis();
    }

    // Getters
    public BoneHitboxComponent getHitbox() { return hitbox; }
    public Vector3f getHitPoint() { return new Vector3f(hitPoint); }
    public float getDistance() { return distance; }
    public Vector3f getHitDirection() { return new Vector3f(hitDirection); }
    public String getHitType() { return hitType; }
    public long getTimestamp() { return timestamp; }

    /**
     * 获取命中骨骼名称
     */
    public String getBoneName() {
        return hitbox != null ? hitbox.getBoneName() : "unknown";
    }

    /**
     * 获取实体ID
     */
    public String getEntityId() {
        return hitbox != null ? hitbox.getEntityId().toString() : "unknown";
    }

    @Override
    public String toString() {
        return String.format("HitResult[bone=%s, point=(%.2f,%.2f,%.2f), dist=%.2f]",
                getBoneName(), hitPoint.x, hitPoint.y, hitPoint.z, distance);
    }
}
