package example.contentpack.tech.compat;

import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.Util;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Create 6.0 可选联动的唯一接触点：注册「应力 → 研究点」转换机。
 * <p>本包其它类不 import 任何 Create 类；{@link example.contentpack.tech.TechTreePack} 只在
 * {@code ModList.get().isLoaded("create")} 为真时首次引用本类（软依赖三件套，
 * 见 {@code design/PACK_DEPS_AND_LINKAGE.md} §4）。Create 缺席 ⇒ 本类不加载。
 * <p>注册命名空间固定为 {@code tech_tree_pack}（不是 {@code context.getModId()}），
 * 以便方块/物品/模型/语言的资源路径落在本包自己的 assets 下。
 */
public final class CreateStressCompat {

    private static final String TAG = "TechTreeCreate";
    private static final String NAMESPACE = "tech_tree_pack";
    public static final String BLOCK_PATH = "stress_converter";

    private static DeferredRegister<Block> BLOCKS;
    private static DeferredRegister<Item> ITEMS;
    private static DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES;

    public static DeferredHolder<Block, StressConverterBlock> BLOCK;
    public static DeferredHolder<Item, Item> ITEM;
    public static DeferredHolder<BlockEntityType<?>, BlockEntityType<StressConverterBlockEntity>> BLOCK_ENTITY;

    private CreateStressCompat() {
    }

    /** 只在 Create 已加载时调用；重复调用无副作用（注册器已存在则跳过）。 */
    public static void register(ContentPackContext context) {
        if (BLOCK != null) {
            return;
        }
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(NAMESPACE, BLOCK_PATH);

        BLOCKS = DeferredRegister.create(Registries.BLOCK, NAMESPACE);
        ITEMS = DeferredRegister.create(Registries.ITEM, NAMESPACE);
        BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, NAMESPACE);

        BLOCK = BLOCKS.register(BLOCK_PATH, () -> new StressConverterBlock(
                BlockBehaviour.Properties.of()
                        .mapColor(MapColor.TERRACOTTA_ORANGE)
                        .strength(3.0F, 6.0F)
                        .requiresCorrectToolForDrops()
                        .noOcclusion()
                        .sound(SoundType.METAL)));

        ITEM = ITEMS.register(BLOCK_PATH, () -> new BlockItem(BLOCK.get(), new Item.Properties()));

        BLOCK_ENTITY = BLOCK_ENTITIES.register(BLOCK_PATH, () -> BlockEntityType.Builder
                .of(StressConverterBlockEntity::new, BLOCK.get())
                .build(Util.fetchChoiceType(References.BLOCK_ENTITY, BLOCK_PATH)));

        BLOCKS.register(context.getModEventBus());
        ITEMS.register(context.getModEventBus());
        BLOCK_ENTITIES.register(context.getModEventBus());

        context.getModEventBus().addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
                event.accept(ITEM.get());
            }
        });

        LogManager.serverInfo(TAG, "Create detected: registered stress converter at %s", id);
    }
}
