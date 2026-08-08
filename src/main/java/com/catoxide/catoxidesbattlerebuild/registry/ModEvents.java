package com.catoxide.catoxidesbattlerebuild.registry;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackLoader;
import com.catoxide.catoxidesbattlerebuild.core.structure.manager.StructureManager;
import com.catoxide.catoxidesbattlerebuild.mob.zombie2.ModularZombie2;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

import java.nio.file.Path;

@EventBusSubscriber(modid = CatoxidesBattleRebuildConstants.MODID)
public class ModEvents {
    private static ContentPackContext contentPackContext;

    public static void setContentPackContext(ContentPackContext context) {
        contentPackContext = context;
        LogManager.serverInfo("ModEvents", "ContentPackContext set");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        LogManager.serverInfo("ModEvents", "Loading pending Spark-Core packages on server (LOWEST priority, after Spark-Core)...");
        ContentPackLoader.loadPendingSparkPackages(false);
        LogManager.serverInfo("ModEvents", "Spark-Core packages loaded on server");

        // Initialize structure system
        try {
            // Use the run directory as the data output location
            Path dataDir = Path.of("data");
            StructureManager.init(dataDir);

            // Convert all registered structures
            StructureManager.convertAll();
        } catch (Exception e) {
            LogManager.serverError("ModEvents", "Failed to initialize structure system: %s", e.getMessage(), e);
        }
    }

    @SubscribeEvent
    public static void entityAttributeCreation(EntityAttributeCreationEvent event) {
        LogManager.serverDebug("ModEvents", "Registering entity attributes...");
        event.put(ModEntities.MODULAR_ZOMBIE.get(), net.minecraft.world.entity.monster.Zombie.createAttributes().build());
        event.put(ModEntities.MODULAR_ZOMBIE_2.get(), ModularZombie2.createAttributes().build());

        if (contentPackContext != null) {
            LogManager.serverDebug("ModEvents", "Registering ContentPack entity attributes...");
            contentPackContext.registerEntityAttributes(event);
        }

        LogManager.serverDebug("ModEvents", "Entity attributes registered successfully");
    }
}