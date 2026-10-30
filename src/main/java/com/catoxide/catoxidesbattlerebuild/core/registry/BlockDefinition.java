package com.catoxide.catoxidesbattlerebuild.core.registry;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;

/**
 * 方块定义（{@code blocks/<id>.json} 的解析结果）
 * <p>
 * 格式示例：
 * <pre>
 * {
 *   "id": "example_wall",
 *   "namespace": "mypack",
 *   "lang": { "en_us": "Example Wall", "zh_cn": "示例墙" },
 *   "material": {
 *     "hardness": 1.5,            // 挖掘难度（-1 = 不可破坏）
 *     "resistance": 6.0,          // 爆炸抗性
 *     "sound": "minecraft:block.stone",  // 音效组 ResourceLocation，缺省 minecraft:block.stone
 *     "lightLevel": 0,            // 0..15 自发光
 *     "friction": 0.6,
 *     "transparent": false,       // noOcclusion（不遮挡）
 *     "randomTicks": false,
 *     "requiresTool": false,      // 需要正确工具才能掉落
 *     "solid": true               // false = 无碰撞（noCollission）
 *   },
 *   "properties": [               // 方块形态自定义参数（完整 blockstate 状态空间由这些参数决定）
 *     { "name": "facing", "type": "direction",
 *       "values": ["north","south","west","east"], "default": "north" },
 *     { "name": "waterlogged", "type": "boolean", "default": "false" },
 *     { "name": "charge", "type": "enum", "values": ["idle","charging","full"], "default": "idle" }
 *   ],
 *   "blockEntity": null | { "type": "generic" } | { "type": "custom", "class": "com.example.XBlockEntity" },
 *   "redstone": { "property": "lit", "lightLit": 15, "lightUnlit": 1 },  // 可选：红石灯行为（见 RedstoneSpec）
 *   "loot": { "selfDrop": true }, // 缺省 selfDrop=true（生成 data/<ns>/loot_table/blocks/<id>.json）
 *   "tags": ["minecraft:mineable/pickaxe"],  // 原版数据标签（data/minecraft/tags/block/…）
 *   "creative": true,
 *   "blockItem": true             // 缺省 true：自动生成同名方块物品
 * }
 * </pre>
 *
 * <p>blockstate 文件（variants/multipart → 模型映射）<b>不在 JSON 定义里</b>：
 * 它由编辑器「方块形态」页签生成并随原版导出写到
 * {@code assets/<ns>/blockstate/<id>.json}（数据包/资源范畴），
 * 游戏侧状态空间则由本定义的 {@code properties} 在代码侧完整构建 —— 两边必须一致，
 * 编辑器的预检会校验。
 */
public record BlockDefinition(
        String id,
        String namespace,
        Map<String, String> lang,
        BlockMaterialSpec material,
        List<BlockPropertySpec> properties,
        BlockEntitySpec blockEntity,
        RedstoneSpec redstone,
        LootSpec loot,
        List<String> tags,
        boolean creative,
        boolean blockItem
) {
    /** 材质/物理参数（1.21.1 已无 Material 枚举，全部是独立字段） */
    public record BlockMaterialSpec(
            float hardness,
            float resistance,
            String sound,
            int lightLevel,
            float friction,
            boolean transparent,
            boolean randomTicks,
            boolean requiresTool,
            boolean solid
    ) {
        public static BlockMaterialSpec defaults() {
            return new BlockMaterialSpec(1.5f, 6.0f, "minecraft:block.stone", 0, 0.6f,
                    false, false, false, true);
        }
    }

    /**
     * 方块实体声明
     *
     * @param type       {@code generic}（基座通用实体，存 NBT）| {@code custom}（包内类）
     * @param className  custom 时的全限定类名；必须继承 {@code BlockEntity} 且带
     *                   {@code (BlockPos, BlockState)} 构造器。由<b>本包自己的类加载器</b>
     *                   解析（ChildFirst，与包代码同一 Class 对象）。
     */
    public record BlockEntitySpec(String type, String className) {
        public static final String TYPE_GENERIC = "generic";
        public static final String TYPE_CUSTOM = "custom";
    }

    /**
     * 红石灯行为声明（可选，与 {@code blockEntity} 正交）
     * <p>
     * 存在时方块注册为 {@link RedstoneLampBlock}：按邻居红石信号切换
     * {@code property} 指定的布尔参数（点亮立即、熄灭延迟 4 tick，与原版红石灯一致），
     * 亮度随状态 = {@code lightLit} / {@code lightUnlit}。
     *
     * @param property   布尔参数名（必须在 {@code properties} 中声明且 type=boolean，否则注册期告警并退回普通方块）
     * @param lightLit   点亮时亮度（0..15，缺省 15）
     * @param lightUnlit 熄灭时亮度（0..15，缺省 1，原版红石灯为 1）
     */
    public record RedstoneSpec(String property, int lightLit, int lightUnlit) {
        public static final int DEFAULT_LIGHT_LIT = 15;
        public static final int DEFAULT_LIGHT_UNLIT = 1;
    }

    /** 战利品声明（v1 只支持 selfDrop：挖掉掉自己） */
    public record LootSpec(boolean selfDrop) {
        public static LootSpec defaultSelfDrop() {
            return new LootSpec(true);
        }
    }

    public ResourceLocation location() {
        return ResourceLocation.fromNamespaceAndPath(
                namespace == null || namespace.isEmpty() ? "catoxidesbattlerebuild" : namespace, id);
    }

    public BlockMaterialSpec materialOrDefault() {
        return material != null ? material : BlockMaterialSpec.defaults();
    }

    public List<BlockPropertySpec> propertiesOrEmpty() {
        return properties != null ? properties : List.of();
    }

    public LootSpec lootOrDefault() {
        return loot != null ? loot : LootSpec.defaultSelfDrop();
    }
}
