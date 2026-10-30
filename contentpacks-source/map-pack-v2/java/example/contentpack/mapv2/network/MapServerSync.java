package example.contentpack.mapv2.network;

import com.catoxide.catoxidesbattlerebuild.core.event.BlockChangeHooks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 服务端侧：把方块变化聚合成本 tick 的「脏区块」，广播给附近玩家。
 *
 * <p>只在专用服务器分支初始化；单人游戏的 integrated server 不需要它
 * （客户端自己会经 {@code BlockChangeHooks} 收到方块更新）。
 *
 * <p>节流：每 tick 每个玩家最多发 {@link #MAX_CHUNKS_PER_PACKET} 个区块/包，
 * 只发 {@link #BROADCAST_RADIUS_BLOCKS} 范围内的区块——玩家视距外的地图本来也不会被采集。
 */
public final class MapServerSync {

    private static final int MAX_CHUNKS_PER_PACKET = 64;
    private static final int BROADCAST_RADIUS_BLOCKS = 256;
    private static final int BROADCAST_RADIUS_CHUNKS = BROADCAST_RADIUS_BLOCKS >> 4;

    private static final Map<ResourceLocation, Set<Long>> DIRTY = new HashMap<>();

    private MapServerSync() {
    }

    public static void init() {
        BlockChangeHooks.register((level, pos, oldState, newState) -> {
            if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
                return;
            }
            DIRTY.computeIfAbsent(serverLevel.dimension().location(), ignored -> new HashSet<>())
                    .add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        });
        NeoForge.EVENT_BUS.addListener(
                (ServerTickEvent.Post event) -> flush(event.getServer()));
    }

    private static void flush(MinecraftServer server) {
        if (DIRTY.isEmpty()) {
            return;
        }
        // 先摘走本 tick 的批次，避免广播过程中的新变化被一起清掉
        Map<ResourceLocation, Set<Long>> batch = new HashMap<>(DIRTY);
        DIRTY.clear();

        for (Map.Entry<ResourceLocation, Set<Long>> entry : batch.entrySet()) {
            ResourceLocation dimension = entry.getKey();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!player.level().dimension().location().equals(dimension)) {
                    continue;
                }
                List<Long> near = filterNear(player, entry.getValue());
                for (int from = 0; from < near.size(); from += MAX_CHUNKS_PER_PACKET) {
                    int to = Math.min(near.size(), from + MAX_CHUNKS_PER_PACKET);
                    PacketDistributor.sendToPlayer(player,
                            new MapDirtyPayload(dimension, List.copyOf(near.subList(from, to))));
                }
            }
        }
    }

    private static List<Long> filterNear(ServerPlayer player, Set<Long> chunks) {
        ChunkPos center = player.chunkPosition();
        List<Long> near = new ArrayList<>();
        for (long key : chunks) {
            ChunkPos pos = new ChunkPos(key);
            if (Math.abs(pos.x - center.x) <= BROADCAST_RADIUS_CHUNKS
                    && Math.abs(pos.z - center.z) <= BROADCAST_RADIUS_CHUNKS) {
                near.add(key);
            }
        }
        return near;
    }
}
