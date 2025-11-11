// TransformContext.java - 变换上下文
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 变换上下文 - 传递变换过程中的所有数据
 */
public class TransformContext {
    public final Vertex originalVertex;
    public final BoneTransform boneTransform;
    public final float[] pivot;
    public Vec3 currentPosition;

    public TransformContext(Vertex vertex, BoneTransform transform, float[] pivot) {
        this.originalVertex = vertex;
        this.boneTransform = transform;
        this.pivot = pivot;
        this.currentPosition = new Vec3(vertex.x, vertex.y, vertex.z);
    }
}