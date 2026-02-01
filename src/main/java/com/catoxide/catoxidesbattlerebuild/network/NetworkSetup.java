package com.catoxide.catoxidesbattlerebuild.network;


import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import software.bernie.geckolib.GeckoLib;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class NetworkSetup{

@SubscribeEvent
public static void onCommonSetup(FMLCommonSetupEvent event) {
    event.enqueueWork(() -> {
        // 注册网络包
        NetworkHandler.register();
        GeckoLib.LOGGER.info("Network system registered");
    });
}
}
