package example.contentpack.map.network;

import example.contentpack.map.client.MarkerClient;
import example.contentpack.map.marker.MarkerService;
import example.contentpack.map.marker.MarkerType;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 标记网络：创建（C2S）+ 同步（S2C）。 */
public final class MarkerNetwork {

    private MarkerNetwork() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            var registrar = event.registrar("1").optional();
            registrar.playToServer(MarkerCreatePayload.TYPE, MarkerCreatePayload.CODEC,
                    MarkerNetwork::handleCreate);
            registrar.playToClient(MarkerSyncPayload.TYPE, MarkerSyncPayload.CODEC,
                    MarkerNetwork::handleSync);
            registrar.playToServer(MarkerRemovePayload.TYPE, MarkerRemovePayload.CODEC,
                    MarkerNetwork::handleRemove);
            registrar.playToServer(MarkerUpdatePayload.TYPE, MarkerUpdatePayload.CODEC,
                    MarkerNetwork::handleUpdate);
        });
    }

    private static void handleUpdate(MarkerUpdatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                MarkerService.updateByRequest(player, payload.id(), payload.icon(),
                        payload.label(), payload.resetIcon());
            }
        });
    }

    private static void handleRemove(MarkerRemovePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                MarkerService.removeByRequest(player, payload.id());
            }
        });
    }

    private static void handleCreate(MarkerCreatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            MarkerType type = MarkerType.byName(payload.kind());
            MarkerService.createFromPlayer(player, type,
                    payload.x(), payload.y(), payload.z(),
                    payload.entityId(), payload.slot(), payload.targetKey(),
                    payload.data(), payload.icon(), payload.sticky());
        });
    }

    private static void handleSync(MarkerSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> MarkerClient.receive(payload));
    }
}
