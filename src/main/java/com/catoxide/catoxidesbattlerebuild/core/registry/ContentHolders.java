package com.catoxide.catoxidesbattlerebuild.core.registry;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 批量注册产物的全局索引（按 ResourceLocation）
 * <p>
 * {@link BatchContentRegistrar} 注册后把 holder 记在这里，供：
 * <ul>
 *   <li>其他包/机制代码按 id 查物品、方块、方块实体类型
 *       （例如 melee-combat-pack 想引用某包的方块做伤害判定）；</li>
 *   <li>重复注册检测（同 id 第二次注册会报错并跳过）。</li>
 * </ul>
 * 只索引经批量注册体系注册的内容；包手写代码直接注册的不在此表
 * （那些走各自的 DeferredRegister 静态字段）。
 */
public final class ContentHolders {

    private static final Map<ResourceLocation, DeferredHolder<Item, Item>> ITEMS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, DeferredHolder<Block, Block>> BLOCKS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, DeferredHolder<BlockEntityType<?>, BlockEntityType<?>>> BLOCK_ENTITY_TYPES =
            new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, DeferredHolder<CreativeModeTab, CreativeModeTab>> CREATIVE_TABS =
            new ConcurrentHashMap<>();

    private ContentHolders() {
    }

    // ==================== 注册侧（仅 BatchContentRegistrar 调用）====================

    static DeferredHolder<Item, Item> putItem(ResourceLocation id, DeferredHolder<Item, Item> holder) {
        return put(ITEMS, id, holder, "item");
    }

    static DeferredHolder<Block, Block> putBlock(ResourceLocation id, DeferredHolder<Block, Block> holder) {
        return put(BLOCKS, id, holder, "block");
    }

    static DeferredHolder<BlockEntityType<?>, BlockEntityType<?>> putBlockEntityType(
            ResourceLocation id, DeferredHolder<BlockEntityType<?>, BlockEntityType<?>> holder) {
        return put(BLOCK_ENTITY_TYPES, id, holder, "block_entity_type");
    }

    static DeferredHolder<CreativeModeTab, CreativeModeTab> putCreativeTab(
            ResourceLocation id, DeferredHolder<CreativeModeTab, CreativeModeTab> holder) {
        return put(CREATIVE_TABS, id, holder, "creative_mode_tab");
    }

    private static <T> DeferredHolder<T, T> put(
            Map<ResourceLocation, DeferredHolder<T, T>> map, ResourceLocation id, DeferredHolder<T, T> holder, String kind) {
        DeferredHolder<T, T> prev = map.putIfAbsent(id, holder);
        if (prev != null && prev != holder) {
            LogManager.serverError("ContentHolders",
                    "Duplicate %s registration: %s (second registration skipped in index)", kind, id);
        }
        return prev != null ? prev : holder;
    }

    // ==================== 查询侧 ====================

    /** 物品 holder（批量注册的物品与方块物品都在此） */
    public static Optional<DeferredHolder<Item, Item>> item(ResourceLocation id) {
        return Optional.ofNullable(ITEMS.get(id));
    }

    public static boolean hasItem(ResourceLocation id) {
        return ITEMS.containsKey(id);
    }

    /** 方块 holder */
    public static Optional<DeferredHolder<Block, Block>> block(ResourceLocation id) {
        return Optional.ofNullable(BLOCKS.get(id));
    }

    public static boolean hasBlock(ResourceLocation id) {
        return BLOCKS.containsKey(id);
    }

    /** 方块实体类型 holder */
    public static Optional<DeferredHolder<BlockEntityType<?>, BlockEntityType<?>>> blockEntityType(ResourceLocation id) {
        return Optional.ofNullable(BLOCK_ENTITY_TYPES.get(id));
    }

    public static boolean hasBlockEntityType(ResourceLocation id) {
        return BLOCK_ENTITY_TYPES.containsKey(id);
    }

    /** 创造栏 holder（批量注册按包创建的） */
    public static Optional<DeferredHolder<CreativeModeTab, CreativeModeTab>> creativeTab(ResourceLocation id) {
        return Optional.ofNullable(CREATIVE_TABS.get(id));
    }

    /** 全部物品 id（日志/命令用） */
    public static Collection<ResourceLocation> itemIds() {
        return ITEMS.keySet();
    }

    public static Collection<ResourceLocation> blockIds() {
        return BLOCKS.keySet();
    }

    /** 方块物品快捷查询：方块 id 对应的 BlockItem（若已注册） */
    public static Optional<DeferredHolder<Item, Item>> blockItem(ResourceLocation blockId) {
        return item(blockId).filter(h -> h.get() instanceof BlockItem);
    }
}
