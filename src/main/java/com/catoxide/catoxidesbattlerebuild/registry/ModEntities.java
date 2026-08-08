package com.catoxide.catoxidesbattlerebuild.registry;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.core.projectile.CustomProjectileEntity;
import com.catoxide.catoxidesbattlerebuild.mob.zombie1.ModularZombie;
import com.catoxide.catoxidesbattlerebuild.mob.zombie2.ModularZombie2;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, CatoxidesBattleRebuildConstants.MODID);

    // Original ModularZombie with GeckoLib
    public static final DeferredHolder<EntityType<?>, EntityType<ModularZombie>> MODULAR_ZOMBIE =
            ENTITIES.register("modular_zombie",
                    () -> EntityType.Builder.<ModularZombie>of(ModularZombie::new, MobCategory.MONSTER)
                            .sized(0.6f, 1.95f)
                            .build("modular_zombie"));

    // ModularZombie2 with Spark-Core for server-side bone data access
    public static final DeferredHolder<EntityType<?>, EntityType<ModularZombie2>> MODULAR_ZOMBIE_2 =
            ENTITIES.register("modular_zombie_2",
                    () -> EntityType.Builder.<ModularZombie2>of(ModularZombie2::new, MobCategory.MONSTER)
                            .sized(0.6f, 1.95f)
                            .build("modular_zombie_2"));

    // Custom lightweight projectile entity (no AABB, Spark-synced trajectory)
    public static final DeferredHolder<EntityType<?>, EntityType<CustomProjectileEntity>> CUSTOM_PROJECTILE =
            ENTITIES.register("custom_projectile",
                    () -> EntityType.Builder.<CustomProjectileEntity>of(CustomProjectileEntity::new, MobCategory.MISC)
                            .sized(0.05f, 0.05f)
                            .build("custom_projectile"));
}