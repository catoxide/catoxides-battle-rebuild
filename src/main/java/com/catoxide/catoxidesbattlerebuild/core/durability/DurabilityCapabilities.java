package com.catoxide.catoxidesbattlerebuild.core.durability;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.capabilities.ItemCapability;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 耐久能力与数据组件注册（主 mod 框架层）
 * <p>{@link IVariableDurability} 通过 ItemCapability 暴露（NeoForge 1.21.1 纯接口方式）；
 * 自定义最大耐久存储在 DataComponent（持久化 + 网络同步），无需自研 Capability 存储。
 */
public final class DurabilityCapabilities {

    /** 可变耐久 ItemCapability（物品通过 getCapability 查询） */
    public static final ItemCapability<IVariableDurability, Void> VAR_DURABILITY_CAP =
            ItemCapability.createVoid(
                    ResourceLocation.fromNamespaceAndPath(CatoxidesBattleRebuildConstants.MODID, "var_dur"),
                    IVariableDurability.class);

    /** 自定义最大耐久数据组件注册表 */
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENT_TYPES =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, CatoxidesBattleRebuildConstants.MODID);

    /** 自定义最大耐久组件（存储自定义耐久上限，下限 1） */
    public static final Supplier<DataComponentType<Integer>> CUSTOM_MAX_DURABILITY =
            DATA_COMPONENT_TYPES.register("custom_max_durability",
                    () -> new DataComponentType.Builder<Integer>()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.INT)
                            .build());

    private DurabilityCapabilities() {}
}
