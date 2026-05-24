package com.catoxide.catoxidesbattlerebuild.registry;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

@EventBusSubscriber(modid = CatoxidesBattleRebuildConstants.MODID)
public class ModEvents {
    @SubscribeEvent
    public static void entityAttributeCreation(EntityAttributeCreationEvent event) {
        LogManager.serverDebug("ModEvents", "Registering entity attributes...");
        event.put(ModEntities.MODULAR_ZOMBIE.get(), net.minecraft.world.entity.monster.Zombie.createAttributes().build());
        LogManager.serverDebug("ModEvents", "Entity attributes registered successfully");
    }
}