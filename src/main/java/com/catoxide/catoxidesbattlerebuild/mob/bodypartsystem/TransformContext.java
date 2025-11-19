// TransformContext.java - 变换上下文
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 变换上下文 - 传递变换过程中的所有数据
 */
// 在TransformContext中添加实体旋转支持
public class TransformContext {
    public final Vertex originalVertex;
    public final BoneTransform boneTransform;
    public final float[] pivot;
    public Vec3 currentPosition;

    // 添加实体旋转的访问方法
    public Quaternionf getEntityRotation() {
        return boneTransform.rotation != null ?
                boneTransform.rotation :
                new Quaternionf(); // 返回单位四元数
    }

    // 构造函数
    public TransformContext(Vertex vertex, BoneTransform transform, float[] pivot) {
        this.originalVertex = vertex;
        this.boneTransform = transform;
        this.pivot = pivot;
        // 初始位置：原始顶点（模型坐标）
        this.currentPosition = new Vec3(vertex.x, vertex.y, vertex.z);
    }
}