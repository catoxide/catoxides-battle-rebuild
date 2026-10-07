package com.catoxide.catoxidesbattlerebuild.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.core.anim.AnimatedMob;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = CatoxidesBattleRebuildConstants.MODID)
public class ModNetworkHandler {
    public static final String PROTOCOL_VERSION = "1";

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        // Server to client only packet
        registrar.playToClient(SyncBoneDataPacket.TYPE, SyncBoneDataPacket.STREAM_CODEC,
                ModNetworkHandler::handleSyncBoneDataOnClient);
        // M6：HitAttemptPacket / SyncDamageResultPacket / SyncBodyPartConfigPacket 已迁入
        // melee-combat-pack：由 MeleeCombatPack.register() 自注册（V1 先例，registrar("1")）
        // 任务相关 payload（QuestSyncPacket / QuestToggleStarPacket）M3 已迁入
        // quest-test-pack：由 QuestTestPack.register() 自行注册（V1 先例）
    }

    private static void handleSyncBoneDataOnClient(SyncBoneDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            CatoxidesBattleRebuildConstants.LOGGER.debug("Received bone data sync: entityId={}, boneCount={}", 
                    packet.entityId(), packet.bonePositions().size());
            Entity entity = context.player().level().getEntity(packet.entityId());
            if (entity instanceof AnimatedMob<?> mob) {
                mob.updateClientBonePositions(packet.bonePositions());
            }
        });
    }

    // 任务 handler（handleQuestSyncOnClient / handleQuestToggleStarOnServer）M3 已迁入
    // quest-test-pack（随 payload 一起由包侧注册）

    public static void init() {}
}
