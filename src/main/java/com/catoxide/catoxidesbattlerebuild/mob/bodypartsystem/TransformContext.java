// TransformContext.java - 新版本
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import net.minecraft.world.phys.Vec3;

public class TransformContext {
    public final Vertex originalVertex;
    public final BoneTransform transform; // 使用新的 BoneTransform
    public final float[] pivot;
    public Vec3 currentPosition;

    public TransformContext(Vertex vertex, BoneTransform transform, float[] pivot) {
        this.originalVertex = vertex;
        this.transform = transform;
        this.pivot = pivot;
        this.currentPosition = new Vec3(vertex.x, vertex.y, vertex.z);
    }

    // 便捷方法
    public boolean hasEntityRotation() {
        return transform.entityRotation != null && !isIdentityQuaternion(transform.entityRotation);
    }

    private boolean isIdentityQuaternion(org.joml.Quaternionf rotation) {
        return rotation.x == 0 && rotation.y == 0 && rotation.z == 0 && rotation.w == 1;
    }
}