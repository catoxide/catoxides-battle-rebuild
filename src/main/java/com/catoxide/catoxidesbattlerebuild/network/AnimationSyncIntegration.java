package com.catoxide.catoxidesbattlerebuild.network;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import software.bernie.geckolib.GeckoLib;

/**
 * 动画同步集成
 * 负责在服务器tick时触发动画同步
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public class AnimationSyncIntegration {

    /**
     * 服务器tick时同步动画
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            try {
                // 同步动画到所有客户端
                AnimationSyncManager.getInstance().onServerTick();

            } catch (Exception e) {
                GeckoLib.LOGGER.error("Error in server tick animation sync: {}", e.getMessage(), e);
            }
        }
    }
}
