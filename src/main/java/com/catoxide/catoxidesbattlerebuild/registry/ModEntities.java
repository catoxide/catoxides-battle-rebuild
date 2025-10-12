package com.catoxide.catoxidesbattlerebuild.registry;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuild;
import com.catoxide.catoxidesbattlerebuild.mob.HitboxPart;
import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEntities {
    // 创建延迟注册器
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, CatoxidesBattleRebuild.MODID);

    // 注册 ModularZombie
    public static final RegistryObject<EntityType<ModularZombie>> MODULAR_ZOMBIE =
            ENTITIES.register("modular_zombie",
                    () -> EntityType.Builder.<ModularZombie>of(ModularZombie::new, MobCategory.MONSTER)
                            .sized(0.6f, 1.95f)
                            .build("modular_zombie"));

    // 注册 HitboxPart (用于多碰撞箱系统)
    public static final RegistryObject<EntityType<HitboxPart>> HITBOX_PART =
            ENTITIES.register("hitbox_part",
                    () -> EntityType.Builder.<HitboxPart>of(HitboxPart::new, MobCategory.MISC)
                            .sized(0.3f, 0.3f)
                            .noSummon()
                            .fireImmune()
                            .build("hitbox_part"));
}
