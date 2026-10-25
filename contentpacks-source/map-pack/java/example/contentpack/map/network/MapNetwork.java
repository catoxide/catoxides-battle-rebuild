package example.contentpack.map.network;

import com.catoxide.catoxidesbattlerebuild.core.world.WorldIdentity;
import example.contentpack.map.MapCapture;
import example.contentpack.map.client.MapWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

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
        });
    }

    /** 服务端：把存档级世界 ID 发给客户端。 */
    private static void handleWorldIdRequest(MapWorldIdRequestPayload payload,
                                             IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && player.getServer() != null) {
                var server = player.getServer();
                var overworld = server.overworld();
                UUID worldId = WorldIdentity.get(server);
                long seed = overworld.getSeed();
                String generator = overworld.getChunkSource().getGenerator().getClass().getName();
                String name = server.getWorldData().getLevelName();
                PacketDistributor.sendToPlayer(player,
                        new MapWorldIdPayload(worldId, seed, generator, name));
            }
        });
    }

    /** 客户端：拿到世界 ID 后才开始采集/渲染。 */
    private static void handleWorldId(MapWorldIdPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> MapWorld.receive(payload.worldId(), payload.seed(),
                payload.generator(), payload.worldName()));
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
