package com.catoxide.catoxidesbattlerebuild.registry;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuild;
import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CatoxidesBattleRebuild.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModEvents {

    @SubscribeEvent
    public static void entityAttributeCreation(EntityAttributeCreationEvent event) {
        // 为 ModularZombie 注册属性，使用原版僵尸的属性
        event.put(ModEntities.MODULAR_ZOMBIE.get(), Zombie.createAttributes().build());

        // 为 HitboxPart 注册基础属性（如果需要）
        // event.put(ModEntities.HITBOX_PART.get(), Monster.createMonsterAttributes().build());
    }
}