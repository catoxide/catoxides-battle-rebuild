package example.contentpack.mapv2.network;

import com.catoxide.catoxidesbattlerebuild.core.world.WorldIdentity;
import example.contentpack.mapv2.MapCapture;
import example.contentpack.mapv2.client.MapWorld;
import example.contentpack.mapv2.client.StructureClient;
import example.contentpack.mapv2.structure.StructureService;
import example.contentpack.mapv2.transfer.ClientBlobService;
import example.contentpack.mapv2.transfer.ServerBlobService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 地图网络包注册（双端）+ 客户端处理。
 *
 * <p>payload id 用主 mod 命名空间（B6：命名空间不随包走，协议稳定性优先）。
 */
public final class MapNetwork {

    private MapNetwork() {
    }

    public static void register(IEventBus modBus) {
        // optional：客户端没装 map-pack 时，服务端发脏区块包不会导致连接被拒
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            var registrar = event.registrar("1").optional();
            registrar.playToClient(MapDirtyPayload.TYPE, MapDirtyPayload.CODEC,
                    MapNetwork::handleDirtyOnClient);
            registrar.playToServer(MapWorldIdRequestPayload.TYPE, MapWorldIdRequestPayload.CODEC,
                    MapNetwork::handleWorldIdRequest);
            registrar.playToClient(MapWorldIdPayload.TYPE, MapWorldIdPayload.CODEC,
                    MapNetwork::handleWorldId);
            registrar.playToServer(StructureQueryPayload.TYPE, StructureQueryPayload.CODEC,
                    MapNetwork::handleStructureQuery);
            registrar.playToClient(StructureResultPayload.TYPE, StructureResultPayload.CODEC,
                    MapNetwork::handleStructureResult);
            // 通用分块传输：双端同 id（下载/上传共用），请求单独走 toServer
            registrar.playToServer(BlobRequestPayload.TYPE, BlobRequestPayload.CODEC,
                    MapNetwork::handleBlobRequest);
            registrar.playBidirectional(BlobChunkPayload.TYPE, BlobChunkPayload.CODEC,
                    new DirectionalPayloadHandler<>(
                            MapNetwork::handleBlobChunkOnClient,
                            MapNetwork::handleBlobChunkOnServer));
            registrar.playToServer(MapRevealRequestPayload.TYPE, MapRevealRequestPayload.CODEC,
                    MapNetwork::handleRevealRequest);
            registrar.playToClient(MapRevealPayload.TYPE, MapRevealPayload.CODEC,
                    MapNetwork::handleReveal);
        });
    }

    /** 服务端：把存档级世界 ID 发给客户端。 */
    private static void handleWorldIdRequest(MapWorldIdRequestPayload payload,
                                             IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && player.getServer() != null) {
                PacketDistributor.sendToPlayer(player, worldIdPayload(player.getServer()));
            }
        });
    }

    private static MapWorldIdPayload worldIdPayload(MinecraftServer server) {
        var overworld = server.overworld();
        return new MapWorldIdPayload(WorldIdentity.get(server), overworld.getSeed(),
                overworld.getChunkSource().getGenerator().getClass().getName(),
                server.getWorldData().getLevelName(), MapTileSync.sharing(server));
    }

    /** 共享开关翻转时重播世界信息：主机客户端据此开始上传。 */
    public static void broadcastWorldId(MinecraftServer server) {
        MapWorldIdPayload payload = worldIdPayload(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    /** 客户端：拿到世界 ID 后才开始采集/渲染。 */
    private static void handleWorldId(MapWorldIdPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> MapWorld.receive(payload.worldId(), payload.seed(),
                payload.generator(), payload.worldName(), payload.tileSharing()));
    }

    private static void handleBlobRequest(BlobRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ServerBlobService.handleRequest(player, payload);
            }
        });
    }

    private static void handleBlobChunkOnClient(BlobChunkPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientBlobService.receive(payload));
    }

    private static void handleBlobChunkOnServer(BlobChunkPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ServerBlobService.handleChunk(player, payload);
            }
        });
    }

    private static void handleRevealRequest(MapRevealRequestPayload payload,
                                            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                RevealServerService.handleRequest(player, payload);
            }
        });
    }

    private static void handleReveal(MapRevealPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> example.contentpack.mapv2.client.MapRevealClient.receive(payload));
    }

    /** 服务端：按单元复算结构候选点（纯计算，不生成区块）。 */
    private static void handleStructureQuery(StructureQueryPayload payload,
                                             IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                StructureService.query(player, payload);
            }
        });
    }

    private static void handleStructureResult(StructureResultPayload payload,
                                              IPayloadContext context) {
        context.enqueueWork(() -> StructureClient.receive(payload));
    }

    private static void handleDirtyOnClient(MapDirtyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null || !level.dimension().location().equals(payload.dimension())) {
                return;
            }
            for (long chunk : payload.chunks()) {
                MapCapture.markDirtyChunk(level, new ChunkPos(chunk));
            }
        });
    }
}
