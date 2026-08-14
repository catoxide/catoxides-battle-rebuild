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
        registrar.playBidirectional(HitAttemptPacket.TYPE, HitAttemptPacket.STREAM_CODEC,
                new DirectionalPayloadHandler<>(
                        ModNetworkHandler::handleHitAttemptOnClient,
                        ModNetworkHandler::handleHitAttemptOnServer
                ));
        registrar.playBidirectional(SyncDamageResultPacket.TYPE, SyncDamageResultPacket.STREAM_CODEC,
                new DirectionalPayloadHandler<>(
                        ModNetworkHandler::handleSyncDamageResultOnClient,
                        ModNetworkHandler::handleSyncDamageResultOnServer
                ));
        registrar.playBidirectional(SyncBodyPartConfigPacket.TYPE, SyncBodyPartConfigPacket.STREAM_CODEC,
                new DirectionalPayloadHandler<>(
                        ModNetworkHandler::handleSyncBodyPartConfigOnClient,
                        ModNetworkHandler::handleSyncBodyPartConfigOnServer
                ));
        // Server to client only packet
        registrar.playToClient(SyncBoneDataPacket.TYPE, SyncBoneDataPacket.STREAM_CODEC,
                ModNetworkHandler::handleSyncBoneDataOnClient);
        registrar.playToClient(QuestSyncPacket.TYPE, QuestSyncPacket.STREAM_CODEC,
                ModNetworkHandler::handleQuestSyncOnClient);
        // 星标切换（客户端→服务端）
        registrar.playToServer(QuestToggleStarPacket.TYPE, QuestToggleStarPacket.STREAM_CODEC,
                ModNetworkHandler::handleQuestToggleStarOnServer);
    }

    private static void handleHitAttemptOnClient(HitAttemptPacket packet, IPayloadContext context) {
    }

    private static void handleHitAttemptOnServer(HitAttemptPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            CatoxidesBattleRebuildConstants.LOGGER.debug("Received hit attempt: entityId={}, boneName={}", packet.entityId(), packet.boneName());
        });
    }

    private static void handleSyncDamageResultOnClient(SyncDamageResultPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            CatoxidesBattleRebuildConstants.LOGGER.debug("Received damage result: entityId={}, damage={}", packet.entityId(), packet.damage());
        });
    }

    private static void handleSyncDamageResultOnServer(SyncDamageResultPacket packet, IPayloadContext context) {
    }

    private static void handleSyncBodyPartConfigOnClient(SyncBodyPartConfigPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            CatoxidesBattleRebuildConstants.LOGGER.debug("Received body part config: entityId={}", packet.entityId());
        });
    }

    private static void handleSyncBodyPartConfigOnServer(SyncBodyPartConfigPacket packet, IPayloadContext context) {
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

    private static void handleQuestSyncOnClient(QuestSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            com.catoxide.catoxidesbattlerebuild.client.quest.ClientQuestCache.getInstance()
                    .update(context.player().getUUID(), packet.entries());
        });
    }

    private static void handleQuestToggleStarOnServer(QuestToggleStarPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            net.minecraft.server.level.ServerPlayer player = (net.minecraft.server.level.ServerPlayer) context.player();
            com.catoxide.catoxidesbattlerebuild.core.quest.QuestSystem qs =
                    com.catoxide.catoxidesbattlerebuild.core.quest.QuestSystem.getInstance();
            for (com.catoxide.catoxidesbattlerebuild.core.quest.QuestInstance root
                    : qs.getPlayerQuests(player.getUUID())) {
                for (com.catoxide.catoxidesbattlerebuild.core.quest.QuestInstance q : flattenAll(root)) {
                    if (q.getDefinition().id().toString().equals(packet.questId())) {
                        qs.setStarred(q, !q.isStarred());
                        return;
                    }
                }
            }
        });
    }

    /** 递归展开任务树（根 + 所有后代） */
    private static java.util.List<com.catoxide.catoxidesbattlerebuild.core.quest.QuestInstance> flattenAll(
            com.catoxide.catoxidesbattlerebuild.core.quest.QuestInstance quest) {
        java.util.List<com.catoxide.catoxidesbattlerebuild.core.quest.QuestInstance> list = new java.util.ArrayList<>();
        list.add(quest);
        for (com.catoxide.catoxidesbattlerebuild.core.quest.QuestInstance child : quest.getChildInstances()) {
            list.addAll(flattenAll(child));
        }
        return list;
    }

    public static void init() {}
}