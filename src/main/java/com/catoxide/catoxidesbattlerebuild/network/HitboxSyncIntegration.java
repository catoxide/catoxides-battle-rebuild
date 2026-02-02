package com.catoxide.catoxidesbattlerebuild.network;

import com.catoxide.catoxidesbattlerebuild.network.HitboxSyncManager;
import com.catoxide.catoxidesbattlerebuild.server.hitboxsystem.HitboxSystem;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import software.bernie.geckolib.GeckoLib;

/**
 * 受击盒同步集成
 */
@Mod.EventBusSubscriber
public class HitboxSyncIntegration {

    /**
     * 服务器tick时同步受击盒
     */
    // @SubscribeEvent
    // public static void onServerTick(TickEvent.ServerTickEvent event) {
    //     if (event.phase == TickEvent.Phase.END) {
    //         try {
    //             // 更新受击盒系统
    //             HitboxSystem.getInstance().updateHitboxes(1.0f);

    //             // 同步到客户端
    //             HitboxSyncManager.getInstance().onServerTick(HitboxSystem.getInstance());

    //         } catch (Exception e) {
    //             GeckoLib.LOGGER.error("Error in server tick sync: {}", e.getMessage(), e);
    //         }
    //     }
    // }
}