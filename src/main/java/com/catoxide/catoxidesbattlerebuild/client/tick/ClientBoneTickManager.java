package com.catoxide.catoxidesbattlerebuild.client.tick;

import com.catoxide.catoxidesbattlerebuild.client.resolver.SparkBoneResolver;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import cn.solarmoon.spark_core.animation.IEntityAnimatable;
import cn.solarmoon.spark_core.animation.anim.AnimController;
import cn.solarmoon.spark_core.animation.model.ModelInstance;
import cn.solarmoon.spark_core.animation.model.ModelPose;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;

public class ClientBoneTickManager {
    private static final ClientBoneTickManager INSTANCE = new ClientBoneTickManager();

    private ClientBoneTickManager() {}

    public static ClientBoneTickManager getInstance() {
        return INSTANCE;
    }

    /**
     * 使用 Spark-Core 更新实体骨骼
     */
    public void tickEntity(Entity entity) {
        if (!(entity instanceof IEntityAnimatable<?> animatable)) {
            LogManager.clientDebug("ClientBoneTickManager", "Entity is not IEntityAnimatable: {}", entity.getType());
            return;
        }

        // 获取 Spark-Core 的动画控制器和模型控制器
        AnimController animController = animatable.getAnimController();
        cn.solarmoon.spark_core.animation.model.ModelController modelController = animatable.getModelController();

        ModelInstance model = modelController.getModel();
        if (model == null) {
            LogManager.clientDebug("ClientBoneTickManager", "Model is null for entity: {}", entity.getType());
            return;
        }

        ModelPose pose = model.getPose();
        
        // 示例：获取特定骨骼的变换矩阵
        Matrix4f headMatrix = SparkBoneResolver.resolveBoneWorldMatrix(entity, "head", 1.0f);
        LogManager.clientDebug("ClientBoneTickManager", 
            "Ticked entity: entityId={}, model={}, hasPose={}",
            entity.getId(),
            model.getIndex(),
            pose != null
        );
    }
}
