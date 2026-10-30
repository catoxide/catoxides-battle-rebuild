package com.catoxide.catoxidesbattlerebuild.core.registry;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * 物品定义（{@code items/<id>.json} 的解析结果）
 * <p>
 * 格式示例：
 * <pre>
 * {
 *   "id": "example_gem",
 *   "namespace": "mypack",          // 可选，缺省挂主 mod 命名空间
 *   "lang": { "en_us": "Example Gem", "zh_cn": "示例宝石" },
 *   "stackSize": 64,                // 1..99（1.21.1 ABSOLUTE_MAX_STACK_SIZE），缺省 64
 *   "rarity": "common",             // common|uncommon|rare|epic，缺省 common
 *   "creative": true,               // 是否进本包创造栏，缺省 true
 *   "fireResistant": false          // 缺省 false
 * }
 * </pre>
 *
 * @param id            注册名（path）。必须 {@code ^[a-z0-9_.\-]+$}
 * @param namespace     命名空间，null/空 = 主 mod
 * @param lang          显示名（lang key 如 {@code en_us}/{@code zh_cn} → 文本）
 * @param stackSize     最大堆叠（1..99）
 * @param rarity        稀有度（common/uncommon/rare/epic）
 * @param creative      进创造栏
 * @param fireResistant 防火
 */
public record ItemDefinition(
        String id,
        String namespace,
        Map<String, String> lang,
        int stackSize,
        String rarity,
        boolean creative,
        boolean fireResistant
) {
    public static final int DEFAULT_STACK_SIZE = 64;
    public static final int MAX_STACK_SIZE = 99; // 1.21.1 Item.ABSOLUTE_MAX_STACK_SIZE

    public ResourceLocation location() {
        return ResourceLocation.fromNamespaceAndPath(
                namespace == null || namespace.isEmpty() ? "catoxidesbattlerebuild" : namespace, id);
    }
}
