package com.catoxide.catoxidesbattlerebuild.server.temp;

import com.catoxide.catoxidesbattlerebuild.network.HitboxSyncManager;
import com.catoxide.catoxidesbattlerebuild.server.ServerEntityManager;
import com.catoxide.catoxidesbattlerebuild.server.ServerSideExecutor;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import software.bernie.geckolib.GeckoLib;

import static com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuild.MODID;

/**
 * 受击系统集成器 - 将HitboxSystem集成到现有架构
 */
@Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class HitboxSystemIntegration {

    /**
     * 服务器tick时更新受击系统
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            try {
                // 1. 先更新所有实体的动画（确保boneMatrices已更新）
                System.out.println("[HitboxSystemIntegration] Starting server tick update...");
                com.catoxide.catoxidesbattlerebuild.server.ServerEntityManager.getInstance().updateAll(1.0f);
                System.out.println("[HitboxSystemIntegration] ServerEntityManager.updateAll() completed");

                // 2. 更新所有实体的受击盒（现在boneMatrices应该已经可用）
                ServerEntityManager.getInstance().updateAll(1.0f);
                System.out.println("[HitboxSystemIntegration] HitboxSystem.updateHitboxes() completed");

                // 3. 同步到客户端
                HitboxSyncManager.getInstance().onServerTick(HitboxSystem.getInstance());
                System.out.println("[HitboxSystemIntegration] HitboxSyncManager.onServerTick() completed");

            } catch (Exception e) {
                System.out.println("[HitboxSystemIntegration] Error updating hitbox system: " + e.getMessage());
                e.printStackTrace();
                GeckoLib.LOGGER.error("Error updating hitbox system: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 实体加入世界时自动注册到受击系统
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() &&
                ServerSideExecutor.isServerSide(event.getLevel())) {

            // 使用现有系统的模型推断逻辑
            net.minecraft.resources.ResourceLocation modelLocation =
                    ServerSideExecutor.determineModelForEntity(event.getEntity());

            if (modelLocation != null) {
                HitboxSystem.getInstance().registerEntity(event.getEntity(), modelLocation);
            }
        }
    }

    /**
     * 实体离开世界时从受击系统移除
     */
    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            HitboxSystem.getInstance().unregisterEntity(event.getEntity());
        }
    }

    /**
     * 手动注册实体到受击系统
     */
    public static void registerEntityWithHitboxSystem(
            net.minecraft.world.entity.Entity entity,
            net.minecraft.resources.ResourceLocation modelLocation) {

        HitboxSystem.getInstance().registerEntity(entity, modelLocation);
    }

//    /**
//     * 执行射线检测
//     */
//    public static java.util.Optional<HitResult> raycastHitboxes(org.joml.Vector3f origin, org.joml.Vector3f direction, float maxDistance) {
//
//        return HitboxSystem.getInstance().raycastAll(origin, direction, maxDistance);
//    }
//
//    /**
//     * 获取系统统计信息
//     */
//    public static HitboxSystem.SystemStats getHitboxSystemStats() {
//
//        return HitboxSystem.getInstance().getStats();
//    }
}