package com.catoxide.catoxidesbattlerebuild.registry;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, CatoxidesBattleRebuildConstants.MODID);

    // 修正点：使用 DeferredHolder，泛型参数依次为：注册表类型，实际注册类型
    public static final DeferredHolder<EntityType<?>, EntityType<ModularZombie>> MODULAR_ZOMBIE =
            ENTITIES.register("modular_zombie",
                    () -> EntityType.Builder.<ModularZombie>of(ModularZombie::new, MobCategory.MONSTER)
                            .sized(0.6f, 1.95f)
                            .build("modular_zombie"));
}