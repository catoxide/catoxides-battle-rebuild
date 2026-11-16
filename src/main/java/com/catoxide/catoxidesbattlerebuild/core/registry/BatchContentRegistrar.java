package com.catoxide.catoxidesbattlerebuild.core.registry;

import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackRegistry;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品/方块批量注册器（基座核心交付物）
 * <p>
 * 一个类完成：物品注册、方块注册（<b>完整 blockstate 状态空间，含自定义参数</b>）、
 * 方块物品（BlockItem）自动生成、<b>方块实体注册</b>（generic / custom 两种）、
 * 按包自动创建创造栏。所有 contentpack 经 {@link ContentPackContext} 的接口使用本类，
 * 包自己不需要碰 DeferredRegister。
 *
 * <h3>注册时序（为什么方块实体两段式）</h3>
 * {@code DeferredRegister} 的实例在 mod 总线 {@code RegisterEvent} 时才物化。
 * 原版注册表顺序保证 BLOCK → ITEM → BLOCK_ENTITY_TYPE → CREATIVE_MODE_TAB
 * （BlockItem 依赖 Block、创造栏依赖 Item），因此：
 * <ul>
 *   <li>BE 类型先以<b>空 validBlocks</b> 注册（Builder 不要求预填方块）；</li>
 *   <li>再借 NeoForge {@link BlockEntityTypeAddBlocksEvent}
 *       （RegisterEvent 全部完成之后、注册表锁定之前触发）把方块补进 validBlocks。
 *       事件对空集合无类层级限制（见其 {@code addValidBlock}：baseClass == null 放行），
 *       正是为这种「注册期拿不到 Block 实例」的场景设计的。</li>
 * </ul>
 *
 * <h3>问题处理策略</h3>
 * 单个定义非法（参数不合法 / 自定义类加载失败等）只隔离该定义并记入
 * {@link Result#problems()}，不拖垮整包 —— 与加载器的包级隔离同构。
 */
public final class BatchContentRegistrar {

    public static final String TAG = "BatchContentRegistrar";

    /** id 字符集：ResourceLocation path（同时是文件名与 lang key） */
    private static final java.util.regex.Pattern ID_PATTERN = java.util.regex.Pattern.compile("^[a-z0-9_.\\-]+$");
    private static final java.util.regex.Pattern NS_PATTERN = java.util.regex.Pattern.compile("^[a-z0-9_.\\-]+$");
    private static final java.util.regex.Pattern FQCN_PATTERN = java.util.regex.Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$.]*$");

    /** 注册结果摘要（问题列表非空 = 部分定义被跳过） */
    public record Result(int items, int blocks, int blockEntities, int creativeTabs, List<String> problems) {
        public boolean hasProblems() {
            return !problems.isEmpty();
        }
    }

    // 每 (注册表, namespace) 一个 DeferredRegister，懒建并挂 mod 总线
    private static final Map<String, DeferredRegister<Item>> ITEM_REGS = new ConcurrentHashMap<>();
    private static final Map<String, DeferredRegister<Block>> BLOCK_REGS = new ConcurrentHashMap<>();
    private static final Map<String, DeferredRegister<BlockEntityType<?>>> BE_REGS = new ConcurrentHashMap<>();
    private static final Map<String, DeferredRegister<CreativeModeTab>> TAB_REGS = new ConcurrentHashMap<>();

    // 原版 SoundType 静态常量表（1.21.1 里 SoundType 已不是注册表，反射取一次）
    private static volatile Map<String, SoundType> vanillaSounds;

    private BatchContentRegistrar() {
    }

    /**
     * 批量注册物品 + 方块（含方块物品、方块实体、创造栏）。
     *
     * @param ctx    注册上下文（提供 mod 总线；当前包作用域由加载器设置）
     * @param items  物品定义（可空）
     * @param blocks 方块定义（可空）
     * @return 注册结果摘要
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Result registerAll(ContentPackContext ctx, List<ItemDefinition> items, List<BlockDefinition> blocks) {
        return registerAll(ctx, items, blocks, null);
    }

    /**
     * {@link #registerAll(ContentPackContext, List, List)} 的重载，额外校验包 jar 内的客户端资源齐全。
     *
     * @param jarEntries 当前包 jar 的条目名集合；{@code null} = 不检查（直接传定义列表的调用方）
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Result registerAll(ContentPackContext ctx, List<ItemDefinition> items, List<BlockDefinition> blocks,
                                     Set<String> jarEntries) {
        BlockPropertyRegistry.ensureStandardLoaded();
        IEventBus bus = ctx.getModEventBus();
        String packId = ctx.currentPackId();
        List<String> problems = new ArrayList<>();
        int itemsOk = 0, blocksOk = 0, besOk = 0, tabsOk = 0;

        List<ItemDefinition> itemList = items != null ? items : List.of();
        List<BlockDefinition> blockList = blocks != null ? blocks : List.of();

        // ---- 客户端资源齐全（注册成功 ≠ 能显示：缺文件时客户端只打日志，problems 仍为 0）----
        if (jarEntries != null) {
            problems.addAll(verifyClientResources(jarEntries, itemList, blockList));
        }

        // ---- 重名预检（同批内 + 已注册）----
        Set<String> seen = new HashSet<>();
        Set<String> rejected = new HashSet<>();
        for (ItemDefinition it : itemList) {
            ResourceLocation id = it.location();
            if (!seen.add("i:" + id) || ContentHolders.hasItem(id)) {
                rejected.add("i:" + id);
                problems.add("物品 id 重复或已注册: " + id + "（跳过）");
            }
        }
        for (BlockDefinition b : blockList) {
            ResourceLocation id = b.location();
            if (!seen.add("b:" + id) || ContentHolders.hasBlock(id)) {
                rejected.add("b:" + id);
                problems.add("方块 id 重复或已注册: " + id + "（跳过）");
            }
        }

        // ---- 物品 ----
        List<DeferredHolder<Item, Item>> creativeItems = new ArrayList<>();
        String creativeNs = null;
        for (ItemDefinition def : itemList) {
            if (rejected.contains("i:" + def.location())) {
                continue;
            }
            List<String> p = validateItem(def);
            if (!p.isEmpty()) {
                problems.addAll(p.stream().map(s -> "物品 " + def.location() + ": " + s).toList());
                continue;
            }
            Item.Properties props = new Item.Properties()
                    .stacksTo(def.stackSize())
                    .rarity(parseRarity(def.rarity()));
            if (def.fireResistant()) {
                props.fireResistant(); // setter 原地改并返回 this，不重赋值（lambda 要求 effectively final）
            }
            DeferredHolder<Item, Item> holder = itemReg(def.location().getNamespace(), bus)
                    .register(def.id(), () -> new Item(props));
            ContentHolders.putItem(def.location(), holder);
            itemsOk++;
            if (def.creative()) {
                creativeItems.add(holder);
                creativeNs = def.location().getNamespace();
            }
        }

        // ---- 方块（含 BlockItem / 状态空间 / 方块实体）----
        // (BE 类型 holder, 方块 holder) 对：RegisterEvent 后补 validBlocks
        List<DeferredHolder<Block, Block>> blockHolders = new ArrayList<>();
        List<Map.Entry<DeferredHolder<?, ?>, DeferredHolder<?, ?>>> bePairs = new ArrayList<>();

        for (BlockDefinition def : blockList) {
            if (rejected.contains("b:" + def.location())) {
                continue;
            }
            List<String> p = validateBlock(def);
            if (!p.isEmpty()) {
                problems.addAll(p.stream().map(s -> "方块 " + def.location() + ": " + s).toList());
                continue;
            }
            String ns = def.location().getNamespace();

            // 状态参数（完整 blockstate 状态空间 = 各参数取值笛卡尔积）
            List<Property<?>> stateProps = new ArrayList<>();
            for (BlockPropertySpec spec : def.propertiesOrEmpty()) {
                try {
                    stateProps.add(BlockPropertyCatalog.buildProperty(spec));
                } catch (IllegalArgumentException e) {
                    problems.add("方块 " + def.location() + ": 参数构建失败 " + e.getMessage());
                }
            }
            if (stateProps.size() != def.propertiesOrEmpty().size()) {
                continue; // 参数问题已记录，整块跳过
            }

            // 声明默认值（参数名 → 包 JSON default 字段）：修正 1.21.1 默认状态 = 笛卡尔积首项
            // 的陷阱（boolean 首值 true，原版惯例是显式 false，见 DataDrivenBlock#registerDefaultState）
            Map<String, String> declaredDefaults = new HashMap<>();
            for (BlockPropertySpec spec : def.propertiesOrEmpty()) {
                if (spec.defaultValue() != null) {
                    declaredDefaults.put(spec.name(), spec.defaultValue());
                }
            }

            // 材质/物理（setter 原地改并返回 this，不重赋值 ⇒ props 对 lambda effectively final）
            BlockDefinition.BlockMaterialSpec m = def.materialOrDefault();
            BlockBehaviour.Properties props = BlockBehaviour.Properties.of()
                    .strength(m.hardness(), m.resistance())
                    .friction(m.friction())
                    .lightLevel(state -> m.lightLevel());
            SoundType sound = resolveSound(m.sound(), problems, def.location());
            if (sound != null) {
                props.sound(sound);
            }
            if (m.transparent()) {
                props.noOcclusion();
            }
            if (m.randomTicks()) {
                props.randomTicks();
            }
            if (m.requiresTool()) {
                props.requiresCorrectToolForDrops();
            }
            if (!m.solid()) {
                props.noCollission();
            }

            DeferredHolder<Block, Block> blockHolder;
            BlockDefinition.BlockEntitySpec be = def.blockEntity();
            if (be != null) {
                // ===== 方块实体（两段式：BE 类型先注册，validBlocks 由事件补）=====
                ResourceLocation beId = beTypeLocation(def.location());
                DeferredRegister<BlockEntityType<?>> beReg = beReg(ns, bus);

                BlockEntityType.BlockEntitySupplier<? extends BlockEntity> supplier;
                DeferredHolder<BlockEntityType<?>, BlockEntityType<?>> beHolder;
                if (BlockDefinition.BlockEntitySpec.TYPE_CUSTOM.equals(be.type())) {
                    Optional<BlockEntityType.BlockEntitySupplier<? extends BlockEntity>> custom =
                            resolveCustomBeSupplier(packId, be.className(), problems, def.location());
                    if (custom.isEmpty()) {
                        continue; // 问题已记录，整块跳过
                    }
                    supplier = custom.get();
                    beHolder = registerBeType(beReg, beId.getPath(), supplier);
                } else {
                    // generic：基座通用实体（NBT 数据槽）。
                    // 供应器运行时才实例化 BE，而 BE 构造需要 BE 类型 holder 自身
                    // —— 用数组持有注册结果，避开注册期循环引用。
                    DeferredHolder<BlockEntityType<?>, BlockEntityType<GenericContentBlockEntity>>[] ref =
                            new DeferredHolder[1];
                    @SuppressWarnings("unchecked")
                    BlockEntityType.BlockEntitySupplier<? extends BlockEntity> genericSupplier =
                            (pos, state) -> new GenericContentBlockEntity(ref[0].get(), pos, state);
                    supplier = genericSupplier;
                    beHolder = registerBeType(beReg, beId.getPath(), genericSupplier);
                    ref[0] = (DeferredHolder<BlockEntityType<?>, BlockEntityType<GenericContentBlockEntity>>)
                            (DeferredHolder<?, ?>) beHolder;
                }

                blockHolder = blockReg(ns, bus).register(def.id(),
                        () -> DataDrivenEntityBlock.of(props, stateProps, supplier, declaredDefaults));
                ContentHolders.putBlockEntityType(beId, beHolder);
                bePairs.add(Map.entry(beHolder, blockHolder));
                besOk++;
            } else {
                // 红石灯行为（可选）：参数必须是指向已声明 boolean 参数的名字
                BlockDefinition.RedstoneSpec redstone = def.redstone();
                BooleanProperty lampProp = null;
                if (redstone != null) {
                    if (redstone.property() == null) {
                        problems.add("方块 " + def.location() + ": redstone 行为缺 property（按普通方块注册）");
                    } else {
                        for (Property<?> sp : stateProps) {
                            if (sp.getName().equals(redstone.property()) && sp instanceof BooleanProperty bp) {
                                lampProp = bp;
                                break;
                            }
                        }
                        if (lampProp == null) {
                            problems.add("方块 " + def.location() + ": redstone 行为参数 '" + redstone.property()
                                    + "' 不是已声明的 boolean 参数（红石行为失效，按普通方块注册）");
                        }
                    }
                }
                final BooleanProperty finalLampProp = lampProp;
                if (finalLampProp != null) {
                    // 动态亮度覆盖材质静态亮度（lightLevel 第二次调用生效；缺省 15/1 = 原版红石灯）
                    BlockBehaviour.Properties lampProps = props.lightLevel(state ->
                            Boolean.TRUE.equals(state.getValue(finalLampProp))
                                    ? redstone.lightLit() : redstone.lightUnlit());
                    blockHolder = blockReg(ns, bus).register(def.id(),
                            () -> RedstoneLampBlock.of(lampProps, finalLampProp, stateProps, declaredDefaults));
                } else {
                    blockHolder = blockReg(ns, bus).register(def.id(),
                            () -> DataDrivenBlock.of(props, stateProps, declaredDefaults));
                }
            }

            ContentHolders.putBlock(def.location(), blockHolder);
            blockHolders.add(blockHolder);
            blocksOk++;

            // 方块物品（同 id；与纯物品共用 ITEM 注册表，冲突时跳过并警告）
            boolean blockItemClash = def.blockItem()
                    && (seen.contains("i:" + def.location()) || ContentHolders.hasItem(def.location()));
            if (def.blockItem() && !blockItemClash) {
                DeferredHolder<Item, Item> blockItemHolder = itemReg(ns, bus).register(def.id(),
                        () -> new BlockItem(blockHolder.get(), new Item.Properties()));
                ContentHolders.putItem(def.location(), blockItemHolder);
                seen.add("i:" + def.location());
                if (def.creative()) {
                    creativeItems.add(blockItemHolder);
                    if (creativeNs == null) {
                        creativeNs = ns;
                    }
                }
            } else if (blockItemClash) {
                problems.add("方块 " + def.location() + " 的方块物品与已有物品 id 冲突（方块物品已跳过）");
            }
        }

        // ---- 创造栏（按包一个）----
        if (!creativeItems.isEmpty() && packId != null && creativeNs != null) {
            ResourceLocation tabId = ResourceLocation.fromNamespaceAndPath(
                    creativeNs, tabPathForPack(packId));
            DeferredHolder<Item, Item> iconHolder = creativeItems.get(0);
            List<DeferredHolder<Item, Item>> toShow = List.copyOf(creativeItems);
            DeferredHolder<CreativeModeTab, CreativeModeTab> tab = tabReg(creativeNs, bus)
                    .register(tabId.getPath(), () -> CreativeModeTab.builder()
                            .title(Component.translatable("itemGroup." + tabId))
                            .icon(() -> iconHolder.get().getDefaultInstance())
                            .displayItems((params, out) -> {
                                for (DeferredHolder<Item, Item> h : toShow) {
                                    out.accept(h.get());
                                }
                            })
                            .build());
            ContentHolders.putCreativeTab(tabId, tab);
            tabsOk++;
        }

        // ---- RegisterEvent 后：补 BE 类型的 validBlocks ----
        if (!bePairs.isEmpty()) {
            List<Map.Entry<DeferredHolder<?, ?>, DeferredHolder<?, ?>>> pairs = List.copyOf(bePairs);
            bus.addListener(BlockEntityTypeAddBlocksEvent.class, event -> {
                for (Map.Entry<DeferredHolder<?, ?>, DeferredHolder<?, ?>> pair : pairs) {
                    DeferredHolder<?, ?> beH = pair.getKey();
                    DeferredHolder<?, ?> blockH = pair.getValue();
                    if (beH == null || blockH == null) {
                        continue;
                    }
                    try {
                        ((BlockEntityTypeAddBlocksEvent) event).modify(
                                (BlockEntityType<?>) beH.get(), (Block) blockH.get());
                    } catch (Exception e) {
                        LogManager.serverError(TAG, "Failed to add valid block to BE type: %s", e.getMessage());
                    }
                }
            });
        }

        Result result = new Result(itemsOk, blocksOk, besOk, tabsOk, List.copyOf(problems));
        LogManager.serverInfo(TAG,
                "Batch registration complete (pack=%s): items=%d blocks=%d blockEntities=%d creativeTabs=%d problems=%d",
                packId, itemsOk, blocksOk, besOk, tabsOk, result.problems().size());
        for (String problem : result.problems()) {
            LogManager.serverWarn(TAG, "  ⚠ %s", problem);
        }
        return result;
    }

    // ==================== 方块实体 ====================

    /** 方块实体类型 id 约定：<block path>_be（同命名空间） */
    public static ResourceLocation beTypeLocation(ResourceLocation blockId) {
        return ResourceLocation.fromNamespaceAndPath(blockId.getNamespace(), blockId.getPath() + "_be");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DeferredHolder<BlockEntityType<?>, BlockEntityType<?>> registerBeType(
            DeferredRegister<BlockEntityType<?>> reg, String path,
            BlockEntityType.BlockEntitySupplier<? extends BlockEntity> supplier) {
        return (DeferredHolder<BlockEntityType<?>, BlockEntityType<?>>) (DeferredHolder) reg.register(path,
                () -> BlockEntityType.Builder.of(supplier)
                        .build(Util.fetchChoiceType(References.BLOCK_ENTITY, path)));
    }

    /**
     * 解析 custom 方块实体类：用<b>本包自己的类加载器</b>（ChildFirst）加载，
     * 与包代码引用的是同一个 Class 对象（规则 R2 边界，见 CONTENTPACK_FORMAT §4）。
     * 要求继承 {@code BlockEntity} 且有 {@code (BlockPos, BlockState)} 公共构造器
     * （1.21.1 BE 标准构造签名，客户端同用此供应器）。
     */
    private static Optional<BlockEntityType.BlockEntitySupplier<? extends BlockEntity>> resolveCustomBeSupplier(
            String packId, String className, List<String> problems, ResourceLocation blockId) {
        if (className == null || !FQCN_PATTERN.matcher(className).matches()) {
            problems.add("blockEntity.class 非法全限定类名: " + className);
            return Optional.empty();
        }
        ContentPackRegistry.LoadedPack lp = ContentPackRegistry.getPack(packId).orElse(null);
        if (lp == null) {
            problems.add("custom 方块实体找不到所属包 " + packId + "（class=" + className + "）");
            return Optional.empty();
        }
        try {
            ClassLoader packLoader = lp.pack().getClass().getClassLoader();
            Class<?> beClass = Class.forName(className, false, packLoader);
            if (!BlockEntity.class.isAssignableFrom(beClass)) {
                problems.add("custom 方块实体 " + className + " 不是 BlockEntity 子类");
                return Optional.empty();
            }
            Constructor<?> ctor = beClass.getConstructor(BlockPos.class, BlockState.class);
            return Optional.of((pos, state) -> {
                try {
                    return (BlockEntity) ctor.newInstance(pos, state);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException("Failed to instantiate block entity " + className, e);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            problems.add("custom 方块实体类加载失败 " + className + ": " + e);
            return Optional.empty();
        }
    }

    // ==================== 校验 ====================

    static List<String> validateItem(ItemDefinition def) {
        List<String> p = new ArrayList<>();
        if (def.id() == null || !ID_PATTERN.matcher(def.id()).matches()) {
            p.add("id 非法（必须 ^[a-z0-9_.\\-]+$）: " + def.id());
        }
        if (def.namespace() != null && !def.namespace().isEmpty() && !NS_PATTERN.matcher(def.namespace()).matches()) {
            p.add("namespace 非法: " + def.namespace());
        }
        if (def.stackSize() < 1 || def.stackSize() > ItemDefinition.MAX_STACK_SIZE) {
            p.add("stackSize 超出 1.." + ItemDefinition.MAX_STACK_SIZE + "（1.21.1 ABSOLUTE_MAX_STACK_SIZE）: " + def.stackSize());
        }
        if (parseRarity(def.rarity()) == null) {
            p.add("rarity 非法（common|uncommon|rare|epic）: " + def.rarity());
        }
        return p;
    }

    static List<String> validateBlock(BlockDefinition def) {
        List<String> p = new ArrayList<>();
        if (def.id() == null || !ID_PATTERN.matcher(def.id()).matches()) {
            p.add("id 非法（必须 ^[a-z0-9_.\\-]+$）: " + def.id());
        }
        if (def.namespace() != null && !def.namespace().isEmpty() && !NS_PATTERN.matcher(def.namespace()).matches()) {
            p.add("namespace 非法: " + def.namespace());
        }
        BlockDefinition.BlockMaterialSpec m = def.materialOrDefault();
        if (m.lightLevel() < 0 || m.lightLevel() > 15) {
            p.add("lightLevel 超出 0..15: " + m.lightLevel());
        }
        if (m.sound() != null && !m.sound().isEmpty() && !m.sound().matches("^[a-z0-9_.\\-]+(?::[a-z0-9_.\\-]+)?$")) {
            p.add("sound 非法 ResourceLocation: " + m.sound());
        }
        if (m.hardness() < -1) {
            p.add("hardness 不能小于 -1（-1 = 不可破坏）: " + m.hardness());
        }
        for (BlockPropertySpec spec : def.propertiesOrEmpty()) {
            p.addAll(BlockPropertyCatalog.validate(spec).stream()
                    .map(s -> "参数非法 " + s).toList());
        }
        // 状态空间爆炸保护（笛卡尔积上限）
        long states = 1;
        for (BlockPropertySpec spec : def.propertiesOrEmpty()) {
            long v = switch (spec.type()) {
                case BlockPropertySpec.TYPE_BOOLEAN -> 2;
                case BlockPropertySpec.TYPE_INT -> (long) spec.max() - spec.min() + 1;
                default -> spec.hasValues() ? spec.values().size() : 2;
            };
            states *= v;
            if (states > 4096) {
                p.add("状态空间超过 4096 种组合（参数取值过多），请减少参数取值");
                break;
            }
        }
        BlockDefinition.BlockEntitySpec be = def.blockEntity();
        if (be != null) {
            if (!BlockDefinition.BlockEntitySpec.TYPE_GENERIC.equals(be.type())
                    && !BlockDefinition.BlockEntitySpec.TYPE_CUSTOM.equals(be.type())) {
                p.add("blockEntity.type 必须是 generic 或 custom: " + be.type());
            } else if (BlockDefinition.BlockEntitySpec.TYPE_CUSTOM.equals(be.type())
                    && (be.className() == null || be.className().isBlank())) {
                p.add("blockEntity.type=custom 缺少 class 全限定类名");
            }
        }
        // 红石行为会二次设置 lightLevel，完全覆盖 material.lightLevel 的静态值
        if (def.redstone() != null && m.lightLevel() > 0) {
            p.add("material.lightLevel 与 redstone 同时声明：红石行为会覆盖静态亮度，请删掉 lightLevel");
        }
        return p;
    }

    /**
     * 注册期客户端资源齐全检查（只查包 jar 里必须存在的两类文件）。
     *
     * <p>注册成功只保证「内容存在」；客户端渲染还需要
     * {@code blockstates/<id>.json → 模型 → 纹理}。缺文件时游戏只打日志
     * （{@code Exception loading blockstate definition} / {@code Unable to load model} /
     * {@code Missing textures in model}），注册器 problems 仍是 0 —— 紫黑块就这样混过去。
     * 这里把「jar 里有没有 blockstate / 物品模型」并入 problems，让 validate-game 的
     * {@code problems=[1-9]} 闸门直接红。
     *
     * <p>纹理存在性不在注册期查：服务端没有客户端资源，其它 mod/原版纹理也无法在这里可靠判定
     * —— 那条链由编辑器打包前预检（packPreflight）和客户端日志闸门覆盖。
     */
    static List<String> verifyClientResources(Set<String> jarEntries, List<ItemDefinition> items,
                                              List<BlockDefinition> blocks) {
        List<String> p = new ArrayList<>();
        for (ItemDefinition def : items) {
            if (!validId(def.id(), def.namespace())) {
                continue; // 非法定义由 validateItem 报，避免重复噪音
            }
            String ns = def.location().getNamespace();
            String rel = "assets/" + ns + "/models/item/" + def.id() + ".json";
            if (!jarEntries.contains(rel)) {
                p.add("物品 " + def.location() + ": 包内缺少 " + rel
                        + "（已注册但客户端没有物品模型 ⇒ 紫黑块/缺失模型）");
            }
        }
        for (BlockDefinition def : blocks) {
            if (!validId(def.id(), def.namespace())) {
                continue;
            }
            String ns = def.location().getNamespace();
            String rel = "assets/" + ns + "/blockstates/" + def.id() + ".json";
            if (!jarEntries.contains(rel)) {
                String singular = "assets/" + ns + "/blockstate/" + def.id() + ".json";
                if (jarEntries.contains(singular)) {
                    p.add("方块 " + def.location() + ": blockstate 在 " + singular
                            + "（单数）—— 1.21.1 客户端只读 blockstates/（复数），该文件会被忽略 ⇒ 紫黑块");
                } else {
                    p.add("方块 " + def.location() + ": 包内缺少 " + rel
                            + "（已注册但客户端没有形态定义 ⇒ 紫黑块/缺失模型）");
                }
            }
            if (def.blockItem()) {
                String itemRel = "assets/" + ns + "/models/item/" + def.id() + ".json";
                if (!jarEntries.contains(itemRel)) {
                    p.add("方块 " + def.location() + ": 包内缺少 " + itemRel
                            + "（方块物品没有物品模型 ⇒ 物品栏紫黑块）");
                }
            }
        }
        return p;
    }

    private static boolean validId(String id, String namespace) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            return false;
        }
        return namespace == null || namespace.isEmpty() || NS_PATTERN.matcher(namespace).matches();
    }

    private static Rarity parseRarity(String name) {
        if (name == null) {
            return Rarity.COMMON;
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "uncommon" -> Rarity.UNCOMMON;
            case "rare" -> Rarity.RARE;
            case "epic" -> Rarity.EPIC;
            case "common" -> Rarity.COMMON;
            default -> null;
        };
    }

    // ==================== 音效组 ====================

    /**
     * 解析音效组。1.21.1 的 SoundType 已不是注册表，只认原版静态常量名
     * （stone/wood/metal/glass/grass/…，反射自 {@link SoundType} 字段，
     * 编辑器侧的可选列表见 itemBlockSchema.js 的同步约定）。
     */
    private static SoundType resolveSound(String rl, List<String> problems, ResourceLocation blockId) {
        if (rl == null || rl.isEmpty()) {
            return null;
        }
        String name = rl.contains(":") ? rl.substring(rl.indexOf(':') + 1) : rl;
        SoundType st = vanillaSounds().get(name.toLowerCase(Locale.ROOT));
        if (st == null) {
            problems.add("音效组未知 '" + rl + "'（保留默认 stone；用原版名如 stone/wood/metal/glass/grass/sand）");
            return null;
        }
        return st;
    }

    private static Map<String, SoundType> vanillaSounds() {
        Map<String, SoundType> m = vanillaSounds;
        if (m == null) {
            Map<String, SoundType> tmp = new HashMap<>();
            for (Field f : SoundType.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && SoundType.class.isAssignableFrom(f.getType())) {
                    try {
                        tmp.put(f.getName().toLowerCase(Locale.ROOT), (SoundType) f.get(null));
                    } catch (Throwable ignored) {
                        // 忽略个别字段读取失败
                    }
                }
            }
            m = Collections.unmodifiableMap(tmp);
            vanillaSounds = m;
            LogManager.serverInfo(TAG, "Vanilla sound groups indexed: %d", m.size());
        }
        return m;
    }

    // ==================== DeferredRegister 缓存 ====================

    private static DeferredRegister<Item> itemReg(String ns, IEventBus bus) {
        return ITEM_REGS.computeIfAbsent("item:" + ns, k -> {
            DeferredRegister<Item> r = DeferredRegister.create(Registries.ITEM, ns);
            r.register(bus);
            return r;
        });
    }

    private static DeferredRegister<Block> blockReg(String ns, IEventBus bus) {
        return BLOCK_REGS.computeIfAbsent("block:" + ns, k -> {
            DeferredRegister<Block> r = DeferredRegister.create(Registries.BLOCK, ns);
            r.register(bus);
            return r;
        });
    }

    private static DeferredRegister<BlockEntityType<?>> beReg(String ns, IEventBus bus) {
        return BE_REGS.computeIfAbsent("be:" + ns, k -> {
            DeferredRegister<BlockEntityType<?>> r = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ns);
            r.register(bus);
            return r;
        });
    }

    private static DeferredRegister<CreativeModeTab> tabReg(String ns, IEventBus bus) {
        return TAB_REGS.computeIfAbsent("tab:" + ns, k -> {
            DeferredRegister<CreativeModeTab> r = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ns);
            r.register(bus);
            return r;
        });
    }

    /** 创造栏 path：包 id 小写化（包 id 全局唯一 ⇒ 栏不冲突） */
    static String tabPathForPack(String packId) {
        return "pack_" + packId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }
}
