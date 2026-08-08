package com.catoxide.catoxidesbattlerebuild.core.mob;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;

/**
 * 数据驱动实体定义
 * <p>对应实体定义 JSON（entities/*.json），描述一个实体的全部配置：
 * 模型/尺寸/属性/音效/动画状态映射/行为组件。
 *
 * @param id            实体注册名（如 "modular_zombie_3"）
 * @param namespace     命名空间（缺省挂主 mod）
 * @param displayName   显示名
 * @param width         碰撞箱宽
 * @param height        碰撞箱高
 * @param attributes    属性表（maxHealth / attackDamage / movementSpeed / armor / followRange...）
 * @param modelType     Spark 模型类型（通常 "entity"）
 * @param modelId       Spark 模型 id（namespace:path）
 * @param texture       贴图路径（namespace:path）
 * @param sounds        音效配置
 * @param stateAnimations 动画状态映射：state id → 动画名（如 {"0":"still","1":"walking","3":"attack"}）
 * @param behaviors     行为组件配置列表
 */
public record MobDefinition(
        String id,
        String namespace,
        String displayName,
        float width,
        float height,
        Map<String, Float> attributes,
        String modelType,
        ResourceLocation modelId,
        ResourceLocation texture,
        MobSoundConfig sounds,
        Map<Integer, String> stateAnimations,
        List<BehaviorSpec> behaviors
) {

    /** 完整实体 key（namespace:id） */
    public String entityKey() {
        return namespace + ":" + id;
    }
}
