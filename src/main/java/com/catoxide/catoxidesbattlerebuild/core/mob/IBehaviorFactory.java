package com.catoxide.catoxidesbattlerebuild.core.mob;

import com.google.gson.JsonObject;

/**
 * 行为组件工厂
 * <p>按 type 从 JSON 配置创建行为组件实例。
 * 主 mod 内置工厂（attack / ai / raycast / bodypart）由 {@link BehaviorAssembler} 注册；
 * 插件可通过 {@link BehaviorAssembler#registerFactory(IBehaviorFactory)} 注册自定义类型。
 */
public interface IBehaviorFactory {

    /** 行为类型名（对应实体定义 JSON 中 behaviors[].type） */
    String type();

    /** 从 JSON 配置创建行为组件实例 */
    IMobBehavior create(JsonObject config);
}
