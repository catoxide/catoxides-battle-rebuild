package com.catoxide.catoxidesbattlerebuild.network;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
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

    public static void init() {}
}
