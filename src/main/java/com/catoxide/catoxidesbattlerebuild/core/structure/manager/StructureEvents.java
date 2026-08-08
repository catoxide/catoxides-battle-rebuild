package com.catoxide.catoxidesbattlerebuild.core.structure.manager;

import com.catoxide.catoxidesbattlerebuild.core.structure.command.StructurePreviewCommand;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Event handlers for the structure system.
 */
@EventBusSubscriber(modid = "catoxidesbattlerebuild", bus = EventBusSubscriber.Bus.MOD)
final class StructureBusEvents {

    @SubscribeEvent
    public static void onModEvent(FMLCommonSetupEvent event) {
        // Register game bus events
        IEventBus gameBus = NeoForge.EVENT_BUS;
        gameBus.register(StructureCommandEvents.class);
        LogManager.serverInfo("StructureBusEvents", "Structure system game bus registered");
    }
}

/**
 * Game bus event handler for command registration.
 */
final class StructureCommandEvents {

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        StructurePreviewCommand.register(event.getDispatcher());
        LogManager.serverInfo("StructureCommandEvents", "Registered structure commands");
    }
}
