package com.catoxide.catoxidesbattlerebuild.client.models;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端模型集合类
 * 存储模型位置、名称和骨骼模型数据
 * 对齐服务端ModelCollection
 */
public record ClientModelCollection(
        ResourceLocation modelLocation,                           // 模型位置
        String modelName,                                        // 模型名称
        ClientBoneModelData boneModelData                        // 骨骼模型数据
) {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientModelCollection.class);

    /**
     * 创建ClientModelCollection实例
     */
    public static ClientModelCollection create(
            ResourceLocation modelLocation,
            String modelName,
            ClientBoneModelData boneModelData) {
        LOGGER.info("[ClientModelCollection] Creating model collection: {} at {}", modelName, modelLocation);
        return new ClientModelCollection(modelLocation, modelName, boneModelData);
    }

    /**
     * 获取骨骼模型数据
     */
    public ClientBoneModelData getBoneModelData() {
        LOGGER.debug("[ClientModelCollection] Getting bone model data for model: {}", modelName);
        return boneModelData;
    }
}
