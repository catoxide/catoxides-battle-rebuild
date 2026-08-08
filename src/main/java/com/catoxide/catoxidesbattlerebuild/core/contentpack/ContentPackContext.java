package com.catoxide.catoxidesbattlerebuild.core.contentpack;

import com.catoxide.catoxidesbattlerebuild.core.mob.DataDrivenMob;
import com.catoxide.catoxidesbattlerebuild.core.mob.MobDefinition;
import com.catoxide.catoxidesbattlerebuild.core.mob.MobDefinitionRegistry;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
