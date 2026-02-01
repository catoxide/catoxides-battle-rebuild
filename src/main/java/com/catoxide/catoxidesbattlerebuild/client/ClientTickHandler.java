package com.catoxide.catoxidesbattlerebuild.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import software.bernie.geckolib.GeckoLib;

/**
 * 客户端tick处理器
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class ClientTickHandler {

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            try {
                // 清理过期的客户端数据
                ClientHitboxHandler.cleanup();
                HitboxSystemClient.getInstance().cleanup();

            } catch (Exception e) {
                GeckoLib.LOGGER.error("Error in client tick cleanup: {}", e.getMessage());
            }
        }
    }
}