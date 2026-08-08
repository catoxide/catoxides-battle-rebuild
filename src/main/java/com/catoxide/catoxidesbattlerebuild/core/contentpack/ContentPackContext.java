package com.catoxide.catoxidesbattlerebuild.core.contentpack;

import com.catoxide.catoxidesbattlerebuild.core.mob.DataDrivenMob;
import com.catoxide.catoxidesbattlerebuild.core.mob.MobDefinition;
import com.catoxide.catoxidesbattlerebuild.core.mob.MobDefinitionRegistry;
import com.catoxide.catoxidesbattlerebuild.core.sound.MobSoundProfile;
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

    /**
     * 收集在此 context 下注册的所有实体 factory，供外部在 AttributeCreationEvent 中注册属性。
     */
    final List<RegisteredEntity<?>> registeredEntities = new ArrayList<>();

    /** 本 context 下注册的所有数据驱动实体（供客户端通用渲染器注册） */
    private final List<DeferredHolder<EntityType<?>, EntityType<DataDrivenMob>>> dataDrivenEntities = new ArrayList<>();

    public ContentPackContext(String modId,
                              DeferredRegister<SoundEvent> soundRegister,
                              DeferredRegister<EntityType<?>> entityRegister) {
        this.modId = modId;
        this.soundRegister = soundRegister;
        this.entityRegister = entityRegister;
        LogManager.serverInfo("ContentPackContext", "Created for modId: %s", modId);
    }

    public String getModId() {
        return modId;
    }

    /**
     * 创建模块专属的 ResourceLocation
     */
    public ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(modId, path);
    }

    // ==================== EntityType 注册 ====================

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
        DeferredHolder<EntityType<?>, EntityType<T>> holder =
                (DeferredHolder<EntityType<?>, EntityType<T>>) (Object) entityRegister.register(
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

        DeferredHolder<EntityType<?>, EntityType<DataDrivenMob>> holder = registerEntity(
                definition.id(),
                EntityType.Builder.<DataDrivenMob>of(DataDrivenMob::new, MobCategory.MONSTER)
                        .sized(definition.width(), definition.height()),
                buildAttributes(definition)
        );

        MobDefinitionRegistry.register(ResourceLocation.fromNamespaceAndPath(ns, definition.id()), definition);
        dataDrivenEntities.add(holder);
        LogManager.serverInfo("ContentPackContext", "Registered data-driven mob: %s:%s (behaviors: %d)",
                ns, definition.id(), definition.behaviors().size());
        return holder;
    }

    /** 本 context 下注册的所有数据驱动实体（供客户端通用渲染器注册） */
    public List<DeferredHolder<EntityType<?>, EntityType<DataDrivenMob>>> getDataDrivenEntities() {
        return dataDrivenEntities;
    }

    /**
     * 从定义属性表构建 AttributeSupplier（延迟执行，NeoForge 自定义属性注册后）。
     */
    private static Supplier<AttributeSupplier.Builder> buildAttributes(MobDefinition definition) {
        return () -> {
            AttributeSupplier.Builder b = Mob.createMobAttributes();
            Map<String, Float> attrs = definition.attributes();
            b.add(Attributes.MAX_HEALTH, attrs.getOrDefault("maxHealth", 20.0f));
            b.add(Attributes.ATTACK_DAMAGE, attrs.getOrDefault("attackDamage", 4.0f));
            b.add(Attributes.MOVEMENT_SPEED, attrs.getOrDefault("movementSpeed", 0.25f));
            b.add(Attributes.FOLLOW_RANGE, attrs.getOrDefault("followRange", 16.0f));
            b.add(Attributes.ARMOR, attrs.getOrDefault("armor", 0.0f));
            return b;
        };
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
        LogManager.serverInfo("ContentPackContext", "Registered MobSoundProfile for '%s': ambient=%s, hurt=%s, death=%s",
                mobId, profile.ambient(), profile.hurt(), profile.death());
        // TODO: 存入全局 MobSoundRegistry，供 AnimatedMob 子类在运行时查询
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
