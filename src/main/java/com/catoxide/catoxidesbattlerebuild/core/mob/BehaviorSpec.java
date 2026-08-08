package com.catoxide.catoxidesbattlerebuild.core.mob;

import com.google.gson.JsonObject;

/**
 * 行为组件配置（数据驱动）
 * <p>实体定义 JSON 中 {@code behaviors} 数组的每一项：
 * <pre>{ "type": "attack", "damage": 6.0, "cooldown": 20 }</pre>
 *
 * @param type   行为组件类型（对应 {@link com.catoxide.catoxidesbattlerebuild.core.mob.IBehaviorFactory#type()}）
 * @param config 该组件的 JSON 配置
 */
public record BehaviorSpec(
        String type,
        JsonObject config
) {}
