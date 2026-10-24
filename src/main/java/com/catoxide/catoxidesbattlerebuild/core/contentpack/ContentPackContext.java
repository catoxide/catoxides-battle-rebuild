package com.catoxide.catoxidesbattlerebuild.core.contentpack;

import com.catoxide.catoxidesbattlerebuild.core.mob.DataDrivenMob;
import com.catoxide.catoxidesbattlerebuild.core.mob.MobDefinition;
import com.catoxide.catoxidesbattlerebuild.core.mob.MobDefinitionRegistry;
import com.catoxide.catoxidesbattlerebuild.core.registry.BatchContentRegistrar;
import com.catoxide.catoxidesbattlerebuild.core.registry.BlockDefinition;
import com.catoxide.catoxidesbattlerebuild.core.registry.BlockPropertySpec;
import com.catoxide.catoxidesbattlerebuild.core.registry.ItemBlockParser;
import com.catoxide.catoxidesbattlerebuild.core.registry.ItemDefinition;
import com.catoxide.catoxidesbattlerebuild.core.sound.MobSoundProfile;
import com.catoxide.catoxidesbattlerebuild.core.sound.MobSoundRegistry;
import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.StructureBlueprint;
import com.catoxide.catoxidesbattlerebuild.core.structure.blueprint.BlueprintParser;
import com.catoxide.catoxidesbattlerebuild.core.structure.manager.StructureManager;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestDefinition;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * ContentPack 注册上下文
 * <p>提供 ContentPack 在 {@link ContentPack#register} 阶段所需的注册器访问。
 * 持有主 mod 的 DeferredRegister 引用，ContentPack 通过此上下文注册实体、声音等内容。
 */
public final class ContentPackContext {

    private final String modId;
    private final DeferredRegister<SoundEvent> soundRegister;
    private final DeferredRegister<EntityType<?>> entityRegister;
    private final net.neoforged.bus.api.IEventBus modEventBus;
    private final net.neoforged.fml.ModContainer modContainer;

    /** 按命名空间动态创建的实体注册表（DLC 独立 namespace 用） */
    private final Map<String, DeferredRegister<EntityType<?>>> entityRegistries = new HashMap<>();

    /**
     * 收集在此 context 下注册的所有实体 factory，供外部在 AttributeCreationEvent 中注册属性。
     */
    final List<RegisteredEntity<?>> registeredEntities = new ArrayList<>();

    /** 本 context 下注册的所有数据驱动实体（供客户端通用渲染器注册） */
    private final List<DeferredHolder<EntityType<?>, EntityType<DataDrivenMob>>> dataDrivenEntities = new ArrayList<>();

    /**
     * 当前正在执行 {@code register()} 的包 id（由 {@link ContentPackLoader} 设置）。
     * <p>只有一个 context 实例被所有包共用，所以需要这个作用域来给
     * {@link #provide} 记录正确的提供者。
     */
    private String currentPackId;

    public ContentPackContext(String modId,
                              DeferredRegister<SoundEvent> soundRegister,
                              DeferredRegister<EntityType<?>> entityRegister,
                              net.neoforged.bus.api.IEventBus modEventBus,
                              net.neoforged.fml.ModContainer modContainer) {
        this.modId = modId;
        this.soundRegister = soundRegister;
        this.entityRegister = entityRegister;
        this.modEventBus = modEventBus;
        this.modContainer = modContainer;
        LogManager.serverInfo("ContentPackContext", "Created for modId: %s", modId);
    }

    /**
     * 注册 contentpack 的配置（ModConfigSpec → toml，游戏内可编辑 + /reload 生效）。
     * <p>contentpack 不是 mod，无法直接注册配置——通过主 mod 的 ModContainer 注册。
     * 文件名需唯一（如 {@code durability-pack-common.toml}）避免与其他包冲突。
     *
     * @param type     配置类型（COMMON/SERVER/CLIENT）
     * @param spec     ModConfigSpec（contentpack 用 ModConfigSpec.Builder 构建）
     * @param fileName 配置文件唯一名（不含扩展名，如 "durability-pack-common"）
     */
    public void registerConfig(net.neoforged.fml.config.ModConfig.Type type,
                               net.neoforged.neoforge.common.ModConfigSpec spec,
                               String fileName) {
        modContainer.registerConfig(type, spec, fileName + ".toml");
        LogManager.serverInfo("ContentPackContext", "Registered config '{}' (type {}) for contentpack",
                fileName, type);
    }

    /**
     * 注册服务端配置（ModConfig.Type.SERVER 糖）。
     * <p>供 pack 代码使用：FML 类（ModConfig.Type）不在包编译类路，pack 用此重载、不直接引用 FML 类型。
     */
    public void registerServerConfig(net.neoforged.neoforge.common.ModConfigSpec spec, String fileName) {
        registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, spec, fileName);
    }

    /**
     * 注册配置并绑定「只属于本配置」的加载/重载回调（推荐用法）。
     * <p>为什么需要它：内容包不是 mod，所有包的配置都挂在主 mod 的 ModContainer 上，
     * 因此 {@code ModConfigEvent} 会广播给主 mod 总线上的<b>每一个</b>监听器。
     * 包若自己写 {@code addListener((ModConfigEvent.Loading e) -> 自己的Config.load())}
     * 而不看事件里的配置是谁的，就会在别人（尤其是加载得更早）的配置触发 Loading 时
     * 读自己尚未加载的 spec —— 直接 {@code IllegalStateException:
     * Cannot get config value before config is loaded}，整个 mod 启动失败。
     * <p>本方法按 spec 过滤，回调只在本包配置 Loading/Reloading 时执行。
     *
     * @param type     配置类型（COMMON/SERVER/CLIENT）
     * @param spec     本包的 ModConfigSpec
     * @param fileName 配置文件唯一名（不含扩展名）
     * @param onLoad   加载/重载回调（可为 null，等价于三参重载）
     */
    public void registerConfig(net.neoforged.fml.config.ModConfig.Type type,
                               net.neoforged.neoforge.common.ModConfigSpec spec,
                               String fileName,
                               Runnable onLoad) {
        registerConfig(type, spec, fileName);
        if (onLoad == null) {
            return;
        }
        modEventBus.addListener((net.neoforged.fml.event.config.ModConfigEvent event) -> {
            if (event.getConfig().getSpec() != spec) {
                return; // 不是本包的配置：绝不碰本包的 ConfigValue
            }
            onLoad.run();
        });
    }

    /** {@link #registerConfig(ModConfig.Type, ModConfigSpec, String, Runnable)} 的 SERVER 糖。 */
    public void registerServerConfig(net.neoforged.neoforge.common.ModConfigSpec spec,
                                     String fileName,
                                     Runnable onLoad) {
        registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, spec, fileName, onLoad);
    }

    /**
     * 取本包某个配置的读值句柄（声明式配置，见 {@code config-declarations.json}）。
     *
     * <p>只能在 {@code register()} 阶段调用（那时「当前包」作用域有效）；
     * 拿到的句柄可以缓存到静态字段，之后任意时刻读值——不要自己再复制一份数值，
     * 否则 HOT 档位会被降级成 COLD。
     *
     * <pre>{@code
     * private static PackConfig config;
     * public void register(ContentPackContext ctx) { config = ctx.config("hud"); }
     * public void render(...) { if (config.getBoolean("thickBar")) { ... } }
     * }</pre>
     *
     * @throws IllegalStateException 不在 register() 阶段，或该配置没有成功注册
     */
    public com.catoxide.catoxidesbattlerebuild.core.config.PackConfig config(String configId) {
        String packId = currentPackId;
        if (packId == null) {
            throw new IllegalStateException("ctx.config(...) 只能在 register() 阶段调用（当前无包作用域）");
        }
        return com.catoxide.catoxidesbattlerebuild.core.config.PackConfigRegistry.get(packId, configId)
                .orElseThrow(() -> new IllegalStateException(
                        "ContentPack '" + packId + "' has no registered config '" + configId
                                + "' (declare it in "
                                + com.catoxide.catoxidesbattlerebuild.core.config.PackConfigDeclarations.JAR_ENTRY
                                + "; check earlier PackConfig errors)"));
    }

    public String getModId() {
        return modId;
    }

    /**
     * 获取 mod 总线（供 contentpack 注册能力/监听 mod 事件，如 RegisterCapabilitiesEvent）。
     * <p>能力注册需在 mod 加载阶段：contentpack 的 register() 中调用
     * {@code getModEventBus().addListener(...)} 即可赶上 RegisterCapabilitiesEvent。
     */
    public net.neoforged.bus.api.IEventBus getModEventBus() {
        return modEventBus;
    }

    /**
     * 创建模块专属的 ResourceLocation
     */
    public ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(modId, path);
    }

    // ==================== EntityType 注册 ====================

    /**
     * 获取（或创建）指定命名空间的实体注册表。
     * <p>主 mod 命名空间 → 用主 DeferredRegister；独立 namespace → 动态创建并注册到 modEventBus
     * （NeoForge 注册表不绑定 mod id，任意命名空间可注册）。
     */
    private DeferredRegister<EntityType<?>> getOrCreateEntityRegister(String namespace) {
        if (namespace == null || namespace.isEmpty() || namespace.equals(modId)) {
            return entityRegister;
        }
        return entityRegistries.computeIfAbsent(namespace, ns -> {
            DeferredRegister<EntityType<?>> reg = DeferredRegister.create(
                    net.minecraft.core.registries.Registries.ENTITY_TYPE, ns);
            reg.register(modEventBus);
            LogManager.serverInfo("ContentPackContext", "Created entity registry for namespace: %s", ns);
            return reg;
        });
    }

    /**
     * 注册一个 EntityType（生物）
     * <p>调用此方法后，返回的 DeferredHolder 可在需要时获取 EntityType 实例。
     * 同时会自动记录到 registeredEntities 列表中，便于后续注册 AttributeSupplier。
     *
     * @param entityPath 实体注册名（如 "modular_zombie_3"）
     * @param builder    EntityType.Builder
     * @param <T>        实体类型（必须是 AnimatedMob 子类）
     * @return DeferredHolder
     */
    @SuppressWarnings("unchecked")
    public <T extends LivingEntity> DeferredHolder<EntityType<?>, EntityType<T>> registerEntity(
            String entityPath,
            EntityType.Builder<T> builder) {
        return registerEntity(entityPath, builder, null);
    }

    /**
     * 注册一个 EntityType（生物）并直接绑定属性
     * <p>这是推荐方式：ContentPack 在 register() 时提供 Supplier<AttributeSupplier.Builder>，
     * 由主项目在 EntityAttributeCreationEvent 中统一注册。
     * 使用 Supplier 是因为 NeoForge 自定义属性（如 swim_speed）在构造函数中尚未注册。
     *
     * @param entityPath 实体注册名
     * @param builder    EntityType.Builder
     * @param attributeSupplierFactory 属性构造器工厂（可为 null，后续手动注册）
     * @param <T>        实体类型
     * @return DeferredHolder
     */
    @SuppressWarnings("unchecked")
    public <T extends LivingEntity> DeferredHolder<EntityType<?>, EntityType<T>> registerEntity(
            String entityPath,
            EntityType.Builder<T> builder,
            java.util.function.Supplier<net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder> attributeSupplierFactory) {
        return registerEntityIn(entityRegister, entityPath, builder, attributeSupplierFactory);
    }

    /** 在指定命名空间的注册表中注册实体（支持 DLC 独立 namespace） */
    @SuppressWarnings("unchecked")
    private <T extends LivingEntity> DeferredHolder<EntityType<?>, EntityType<T>> registerEntityIn(
            DeferredRegister<EntityType<?>> registry,
            String entityPath,
            EntityType.Builder<T> builder,
            java.util.function.Supplier<net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder> attributeSupplierFactory) {
        DeferredHolder<EntityType<?>, EntityType<T>> holder =
                (DeferredHolder<EntityType<?>, EntityType<T>>) (Object) registry.register(
                        entityPath, () -> builder.build(entityPath));
        registeredEntities.add(new RegisteredEntity<>(entityPath, holder, builder, attributeSupplierFactory));
        LogManager.serverInfo("ContentPackContext", "Registered entity: %s (%s)", entityPath, modId);
        return holder;
    }

    // ==================== 数据驱动实体注册 ====================

    /**
     * 注册一个数据驱动实体（{@link DataDrivenMob}）
     * <p>从 {@link MobDefinition} 注册 EntityType + 属性，并把定义写入
     * {@link MobDefinitionRegistry}（DataDrivenMob 构造时按 EntityType key 查询）。
     * namespace 缺省挂主 mod 命名空间。
     *
     * @param definition 实体定义（由实体定义 JSON 解析而来）
     * @return EntityType DeferredHolder
     */
    public DeferredHolder<EntityType<?>, EntityType<DataDrivenMob>> registerDataDrivenMob(MobDefinition definition) {
        String ns = (definition.namespace() == null || definition.namespace().isEmpty())
                ? modId : definition.namespace();

        // 独立 namespace → 动态创建 DeferredRegister（实体注册在 ns:path）
        DeferredHolder<EntityType<?>, EntityType<DataDrivenMob>> holder = registerEntityIn(
                getOrCreateEntityRegister(ns),
                definition.id(),
                EntityType.Builder.<DataDrivenMob>of(DataDrivenMob::new, MobCategory.MONSTER)
                        .sized(definition.width(), definition.height()),
                buildAttributes(definition)
        );

        // 定义 key = 实体实际注册的 EntityType key（与 DataDrivenMob 构造时 getKey(type) 一致）
        MobDefinitionRegistry.register(holder.getKey().location(), definition);
        dataDrivenEntities.add(holder);
        LogManager.serverInfo("ContentPackContext", "Registered data-driven mob: %s (behaviors: %d)",
                holder.getKey().location(), definition.behaviors().size());
        return holder;
    }

    /** 本 context 下注册的所有数据驱动实体（供客户端通用渲染器注册） */
    public List<DeferredHolder<EntityType<?>, EntityType<DataDrivenMob>>> getDataDrivenEntities() {
        return dataDrivenEntities;
    }

    /**
     * 从定义属性表构建 AttributeSupplier（延迟执行，NeoForge 自定义属性注册后）。
     * <p>设计原则：主 mod 只在机制层打洞，参数走数据——JSON 里任意原版属性 key
     * 直接查属性注册表生效，主 mod 无需为每个属性预定义字段。
     */
    private static Supplier<AttributeSupplier.Builder> buildAttributes(MobDefinition definition) {
        return () -> {
            AttributeSupplier.Builder b = Mob.createMobAttributes();
            // 兜底默认值（JSON 显式声明会覆盖）
            b.add(Attributes.MAX_HEALTH, 20.0f);
            b.add(Attributes.ATTACK_DAMAGE, 4.0f);
            b.add(Attributes.MOVEMENT_SPEED, 0.25f);
            b.add(Attributes.FOLLOW_RANGE, 16.0f);
            b.add(Attributes.ARMOR, 0.0f);
            b.add(Attributes.ATTACK_SPEED, 4.0f); // 缺失会导致 swinging 永不重置 -> 动画卡死

            // JSON 显式声明的属性：按 key 查原版属性注册表（任意属性可配，无需主 mod 打洞）
            // 防御：单个属性解析异常不影响其他属性（默认值含 ATTACK_SPEED 保留）
            for (var entry : definition.attributes().entrySet()) {
                try {
                    var attr = lookupAttribute(entry.getKey());
                    if (attr != null) {
                        b.add(attr, entry.getValue());
                    } else {
                        LogManager.serverWarn("ContentPackContext",
                                "Unknown attribute '{}' in entity definition {}, skipped",
                                entry.getKey(), definition.id());
                    }
                } catch (Exception e) {
                    LogManager.serverWarn("ContentPackContext",
                            "Failed to apply attribute '{}' for entity {}: {}",
                            entry.getKey(), definition.id(), e.getMessage());
                }
            }
            return b;
        };
    }

    /**
     * 按 key 查原版属性注册表（返回 Holder 供 AttributeSupplier.Builder.add 使用）。
     * 支持两种写法：
     * <ul>
     *   <li>camelCase：{@code maxHealth} / {@code knockbackResistance}（自动转 snake_case）</li>
     *   <li>原版注册名：{@code minecraft:max_health}（直接解析）</li>
     * </ul>
     */
    private static net.minecraft.core.Holder.Reference<net.minecraft.world.entity.ai.attributes.Attribute> lookupAttribute(String key) {
        String snake = key.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
        var holder = net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE
                .getHolder(net.minecraft.resources.ResourceLocation.withDefaultNamespace(snake)).orElse(null);
        if (holder == null) {
            var parsed = net.minecraft.resources.ResourceLocation.tryParse(key);
            if (parsed != null) {
                holder = net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.getHolder(parsed).orElse(null);
            }
        }
        return holder;
    }

    // ==================== SoundEvent 注册 ====================

    /**
     * 为本 ContentPack 注册一个自定义 SoundEvent
     * <p>对应的 .ogg 资源文件应放在
     * {@code assets/<modId>/sounds/<mob_id>/<event_name>.ogg}。
     *
     * @param soundId 声音事件 ResourceLocation
     * @return SoundEvent DeferredHolder
     */
    public DeferredHolder<SoundEvent, SoundEvent> registerSound(ResourceLocation soundId) {
        return soundRegister.register(soundId.getPath(),
                () -> SoundEvent.createVariableRangeEvent(soundId));
    }

    /**
     * 快捷方法：自动使用本 pack 的 modId 作为命名空间
     */
    public DeferredHolder<SoundEvent, SoundEvent> registerSound(String path) {
        return registerSound(id(path));
    }

    // ==================== MobSoundProfile 注册 ====================

    /**
     * 为指定 mob 注册完整的声音配置
     *
     * @param mobId   生物 ID（如 "zombie3"）
     * @param profile 声音配置
     */
    public void registerMobSoundProfile(String mobId, MobSoundProfile profile) {
        MobSoundRegistry.register(mobId, profile);
    }

    /**
     * 为已注册的实体注册 AttributeSupplier
     * <p>在 EntityAttributeCreationEvent 中调用。ContentPack 在 registerEntity() 时提供的
     * Supplier 在这里被调用来延迟创建 AttributeSupplier.Builder（此时 NeoForge 自定义属性已注册）。
     */
    public void registerEntityAttributes(net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) {
        for (RegisteredEntity<?> entity : registeredEntities) {
            if (entity.attributeSupplierFactory() != null) {
                try {
                    event.put(entity.holder().get(), entity.attributeSupplierFactory().get().build());
                    LogManager.serverInfo("ContentPackContext", "Registered attributes for entity: %s", entity.entityPath());
                } catch (Exception e) {
                    LogManager.serverError("ContentPackContext", "Failed to register attributes for entity %s: %s",
                            entity.entityPath(), e.getMessage(), e);
                }
            }
        }
    }

    /**
     * 获取在此 context 下注册的所有实体信息
     */
    public List<RegisteredEntity<?>> getRegisteredEntities() {
        return registeredEntities;
    }

    // ==================== Structure 注册 ====================

    /**
     * 注册一个建筑结构
     * <p>将建筑蓝图注册到 StructureManager，在 world 加载时自动生成。
     *
     * @param blueprint 建筑蓝图定义
     */
    public void registerStructure(StructureBlueprint blueprint) {
        if (blueprint == null) {
            LogManager.serverError("ContentPackContext", "Cannot register null structure");
            return;
        }
        if (blueprint.structureId == null || blueprint.structureId.isBlank()) {
            LogManager.serverError("ContentPackContext", "Cannot register structure with null/empty ID");
            return;
        }

        // Set namespace if not set
        if (blueprint.namespace == null || blueprint.namespace.isEmpty()) {
            blueprint.namespace = modId;
        }

        StructureManager.registerStructure(blueprint);
        LogManager.serverInfo("ContentPackContext", "Registered structure: %s (namespace: %s, pieces: %d)",
            blueprint.structureId, blueprint.namespace, blueprint.pieces.size());
    }

    /**
     * 注册一个任务定义（内容包挂接点）。
     * <p>任务框架在 QuestSystem；具体 giver/goal/condition 由内容包实现并注册到 QuestSystem。
     *
     * @param definition 任务定义（含子任务树拓扑）
     */
    public void registerQuest(QuestDefinition definition) {
        if (definition == null) {
            LogManager.serverError("ContentPackContext", "Cannot register null quest definition");
            return;
        }
        QuestSystem.getInstance().registerDefinition(definition);
        LogManager.serverInfo("ContentPackContext", "Registered quest definition: %s (children: %d)",
                definition.id(), definition.children().size());
    }

    /**
     * 从 ContentPack JAR 中的蓝图文件注册建筑
     * <p>自动查找 structures/ 目录下的所有 blueprint.json 文件并注册。
     *
     * @param structuresDir ContentPack JAR 中的 structures 目录路径
     */
    public void registerStructuresFromDirectory(Path structuresDir) {
        if (structuresDir == null || !Files.exists(structuresDir)) {
            return;
        }

        try {
            Files.walk(structuresDir).filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith("blueprint.json"))
                .forEach(path -> {
                    try {
                        StructureBlueprint blueprint = BlueprintParser.parseFile(path);
                        if (blueprint.namespace == null || blueprint.namespace.isEmpty()) {
                            blueprint.namespace = modId;
                        }
                        StructureManager.registerStructure(blueprint);
                        LogManager.serverInfo("ContentPackContext", "Loaded structure from: %s", path.getFileName());
                    } catch (Exception e) {
                        LogManager.serverError("ContentPackContext", "Failed to load structure from %s: %s",
                            path.getFileName(), e.getMessage());
                    }
                });
        } catch (IOException e) {
            LogManager.serverError("ContentPackContext", "Failed to scan structures directory: %s", e.getMessage());
        }
    }

    // ==================== 物品/方块批量注册（基座接口）====================

    /**
     * 注册一个方块状态参数（自定义参数）。
     * <p>
     * 注册后参数名进入「已知参数」全集（{@code BlockPropertyRegistry.knownNames()}），
     * 本包及拓扑序上后续包的 {@code blocks/*.json} 都可以引用它。
     * <p>
     * 要让该参数出现在编辑器的「可选参数」下拉里，请把它加进目录文件
     * {@code assets/catoxidesbattlerebuild/editor/block_properties.json}
     * （Java 与编辑器共读的单一真源）。
     *
     * @param spec 参数声明（name/type/values/min/max/default）
     * @return 构建出的 {@code Property} 实例（包代码可直接用于自己的 Block）
     */
    public net.minecraft.world.level.block.state.properties.Property<?> registerBlockProperty(BlockPropertySpec spec) {
        return com.catoxide.catoxidesbattlerebuild.core.registry.BlockPropertyRegistry.registerCustom(spec);
    }

    /**
     * 批量注册物品 + 方块（含完整 blockstate 状态空间与自定义参数、方块物品、
     * 方块实体（generic/custom）、按包创造栏）。
     * <p>
     * 必须在 {@code register()} 阶段调用（DeferredRegister 要求）。
     * 单个定义非法只隔离该定义（见返回的 problems），不拖垮整包。
     *
     * @param items  物品定义列表（可空）
     * @param blocks 方块定义列表（可空）
     * @return 注册结果摘要
     */
    public BatchContentRegistrar.Result registerItemsAndBlocks(List<ItemDefinition> items, List<BlockDefinition> blocks) {
        return BatchContentRegistrar.registerAll(this, items, blocks);
    }

    /**
     * 扫描<b>当前包 jar</b> 里的 {@code items/*.json} + {@code blocks/*.json}（仅一级目录）
     * 并批量注册 —— 编辑器生成的入口类就调这一行。
     * <p>
     * 纯数据包（无 Module-Entry）没有 register() 阶段，因此纯 JSON 物品/方块
     * 仍需一个入口类调用本方法（与 entities/*.json 同构：JSON 是数据，注册是代码的事）。
     *
     * @return 注册结果摘要
     */
    public BatchContentRegistrar.Result registerItemsAndBlocksFromJar() {
        String packId = this.currentPackId;
        if (packId == null) {
            throw new IllegalStateException("registerItemsAndBlocksFromJar 必须在 register() 阶段调用（当前无包作用域）");
        }
        Optional<ContentPackRegistry.LoadedPack> loaded = ContentPackRegistry.getPack(packId);
        if (loaded.isEmpty()) {
            throw new IllegalStateException("找不到已加载包 " + packId + "（registerItemsAndBlocksFromJar）");
        }
        List<ItemDefinition> items = new ArrayList<>();
        List<BlockDefinition> blocks = new ArrayList<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(loaded.get().jarFile())) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                String kind = null;
                if (name.startsWith("items/") && name.endsWith(".json")
                        && name.indexOf('/', "items/".length()) == -1) {
                    kind = "item";
                } else if (name.startsWith("blocks/") && name.endsWith(".json")
                        && name.indexOf('/', "blocks/".length()) == -1) {
                    kind = "block";
                }
                if (kind == null) {
                    continue;
                }
                try (java.io.InputStream in = zip.getInputStream(entry)) {
                    String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    if ("item".equals(kind)) {
                        items.add(ItemBlockParser.parseItem(json));
                    } else {
                        blocks.add(ItemBlockParser.parseBlock(json));
                    }
                } catch (Exception ex) {
                    LogManager.serverError("ContentPackContext", "Failed to parse %s in %s: %s",
                            name, loaded.get().sourceJar(), ex.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法扫描包 jar " + loaded.get().sourceJar() + ": " + e.getMessage(), e);
        }
        LogManager.serverInfo("ContentPackContext",
                "Scanned pack jar %s for items/blocks: items=%d blocks=%d",
                loaded.get().sourceJar(), items.size(), blocks.size());
        return BatchContentRegistrar.registerAll(this, items, blocks);
    }

    // ==================== 跨包能力（Capability）====================

    /**
     * 由 {@link ContentPackLoader} 在调用 {@code register()} 前设置「当前包」作用域。
     * <p>包代码不需要、也不应该调用。
     */
    void beginPack(String packId) {
        this.currentPackId = packId;
    }

    /** 由加载器在 {@code register()} 返回后清除作用域。 */
    void endPack() {
        this.currentPackId = null;
    }

    /** 当前正在 {@code register()} 的包 id；不在注册阶段则为 null。 */
    public String currentPackId() {
        return currentPackId;
    }

    /**
     * 提供一个跨包能力实现（本包实现 {@code capability.type()} 所声明的接口）。
     *
     * <h4>前置条件（必须满足，否则会静默拿不到）</h4>
     * <ul>
     *   <li>接口与 token <b>必须定义在主 mod 包名下</b>（parent-only），
     *       这样所有包看到同一个 Class 对象，跨包转换才成立。</li>
     *   <li>实现类可以是本包私有的 —— 它只在消费者通过接口调用时被用到，
     *       消费者从不 import 它，因此不会产生第二份 Class 副本。</li>
     * </ul>
     *
     * <p>只能在 {@code register()} 阶段调用；注册表锁定后调用会被拒绝并报错。
     * 同一 token 重复提供时，<b>第一个提供者生效</b>，后续提供者报错。
     *
     * @param capability 能力 token（建议放在 core 的 {@code *Caps} 常量类里）
     * @param impl       实现实例（通常是匿名类或本包私有类）
     * @param <T>        能力接口类型
     */
    public <T> void provide(Capability<T> capability, T impl) {
        CapabilityRegistry.provide(capability, impl, currentPackId != null ? currentPackId : "<unknown>");
    }

    /**
     * 获取一个跨包能力；<b>无人提供时返回 {@link Optional#empty()}</b>。
     *
     * <p>这正是「包 A 的部分内容在包 B 存在时启用」的写法 —— 空值不是错误，而是
     * 预期分支：
     * <pre>{@code
     * ctx.capability(SomeCaps.FOO).ifPresent(impl -> registerCompat(ctx, impl));
     * }</pre>
     *
     * <p>顺序保证：若 A 在 MANIFEST 中用 {@code Module-Dependencies} 声明依赖 B，
     * 则 B 的 {@code register()} 一定先执行，A 在这里必然拿得到 B 提供的能力。
     * 若用的是 {@code Module-Dependencies-Optional}，则<b>不保证顺序</b>，拿不到就是拿不到。
     */
    public <T> Optional<T> capability(Capability<T> capability) {
        return CapabilityRegistry.get(capability);
    }

    /** 某个能力是否已被提供（不关心类型是否匹配调用方预期）。 */
    public boolean hasCapability(Capability<?> capability) {
        return CapabilityRegistry.has(capability);
    }

    /**
     * 某个内容包是否已加载（存在性探测，不涉及类空间）。
     * <p>仅在 {@code register()} 阶段可靠：那时所有通过校验的包都已进入注册表。
     */
    public boolean hasPack(String packId) {
        // 被隔离的包一律视为「不存在」：这正是「包 B 在的时候才启用 A 的兼容内容」该有的语义 ——
        // 隔离后的 B 不能再触发 A 的兼容分支，否则 A 会去调用一个半死包的实现。
        return ContentPackRegistry.getPack(packId).isPresent() && !ContentPackGraph.isQuarantined(packId);
    }

    /** 已加载的全部包 id，<b>按拓扑序</b>（被依赖者在前）。 */
    public List<String> loadedPackIds() {
        List<String> ids = new ArrayList<>();
        for (ContentPackRegistry.LoadedPack loaded : ContentPackRegistry.getAllPacks()) {
            ids.add(loaded.id());
        }
        return ids;
    }

    /**
     * 实体注册信息包装
     */
    public record RegisteredEntity<T extends LivingEntity>(
            String entityPath,
            DeferredHolder<EntityType<?>, EntityType<T>> holder,
            EntityType.Builder<T> builder,
            java.util.function.Supplier<net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder> attributeSupplierFactory
    ) {}
}
