package example.contentpack.mapv2.client;

import example.contentpack.mapv2.MapLayer;
import example.contentpack.mapv2.MapStore;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 地图上的「这个位置是什么」查询。
 *
 * <p>只查客户端**已加载**的区块：未加载区域返回 {@code null}，界面不显示信息
 * （不从磁盘/服务端推断，避免给出过期或错误的名字）。
 *
 * <p>地表层取 {@code WORLD_SURFACE}；切片层取该 16 格窗口内最高的非空气方块——
 * 和采集时的语义一致，所以显示的就是地图上画的那个方块。
 */
final class MapProbe {

    /** {@code fromMap=true} 表示来自瓦片快照（y 未知，方块名/群系可能为 null）。 */
    record Info(int x, int y, int z, String block, String biome, boolean fromMap) {
    }

    private MapProbe() {
    }

    static Info probe(ClientLevel level, int x, int z, String layer) {
        ResourceLocation dimension = level.dimension().location();
        if (!isLoaded(level, x, z)) {
            // 未加载：方块名来自瓦片快照（采集时记下的），群系用预测补
            String mapBlock = blockName(MapStore.sampleBlock(dimension, layer, x, z));
            String predicted = MapLayer.TOP.equals(layer)
                    ? MapBiomeLayer.biomeNameAt(dimension, x, z) : null;
            if (mapBlock == null && predicted == null) {
                return null;
            }
            return new Info(x, Integer.MIN_VALUE, z, mapBlock, predicted, true);
        }
        int y;
        if (MapLayer.TOP.equals(layer)) {
            y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            if (y < level.getMinBuildHeight()) {
                return null;
            }
        } else {
            int minY = MapLayer.absoluteY(layer);
            if (minY == Integer.MIN_VALUE) {
                return null;
            }
            int maxY = Math.min(level.getMaxBuildHeight() - 1,
                    minY + MapLayer.SECTION_HEIGHT - 1);
            y = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, maxY);
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            while (y >= minY) {
                cursor.set(x, y, z);
                if (!level.getBlockState(cursor).isAir()) {
                    break;
                }
                y--;
            }
            if (y < minY) {
                return null;
            }
        }

        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            // 实时数据没有方块：回退到瓦片快照
            String mapBlock = blockName(MapStore.sampleBlock(dimension, layer, x, z));
            return mapBlock == null ? null
                    : new Info(x, y, z, mapBlock, biomeName(level, pos), true);
        }
        return new Info(x, y, z,
                state.getBlock().getName().getString(),
                biomeName(level, pos), false);
    }

    /** 客户端此刻是否加载着该坐标所在区块。 */
    static boolean isLoaded(ClientLevel level, int x, int z) {
        return level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false)
                instanceof LevelChunk;
    }

    /**
     * 已加载区块内该坐标的真实群系名（本地化）；未加载返回 {@code null}。
     *
     * <p>y 只影响 3D 群系（洞穴、山峰），调用方给地图上那一列的地表高度即可。
     */
    static String loadedBiomeName(ClientLevel level, int x, int y, int z) {
        if (!isLoaded(level, x, z)) {
            return null;
        }
        int clamped = Math.max(level.getMinBuildHeight(),
                Math.min(level.getMaxBuildHeight() - 1, y));
        return biomeName(level, new BlockPos(x, clamped, z));
    }

    /**
     * 已加载区块地表那一格的真实群系 ID（如 {@code minecraft:plains}）；未加载返回 {@code null}。
     *
     * <p>给群系叠加层用：预测只该补未加载区域，已加载区域照世界真实群系上色。
     */
    static String loadedSurfaceBiomeId(ClientLevel level, int x, int z) {
        if (!isLoaded(level, x, z)) {
            return null;
        }
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        return loadedBiomeId(level, x, y, z);
    }

    /**
     * 已加载区块里的真实群系 ID；区块不在客户端缓存里返回 {@code null}。
     *
     * <p>不走 {@code level.getBiome(BlockPos)}：它经 {@code BiomeManager} 做 8 角模糊采样，
     * 会采样到相邻区块；相邻区块还没进客户端缓存时 {@code ClientLevel.getUncachedNoiseBiome}
     * 恒返回平原（1.21.1 {@code ClientLevel.java:762}），于是传送后的第一帧、区块边缘都会
     * 读出「平原」。这里直接读该区块自己的群系存储（同一 quart），不模糊、不回退。
     */
    private static String loadedBiomeId(ClientLevel level, int x, int y, int z) {
        if (!isLoaded(level, x, z)) {
            return null;
        }
        ChunkAccess chunk = level.getChunk(x >> 4, z >> 4, ChunkStatus.BIOMES, false);
        if (chunk == null) {
            return null;
        }
        Holder<Biome> biome = chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y),
                QuartPos.fromBlock(z));
        return biome.unwrapKey().map(key -> key.location().toString()).orElse(null);
    }

    /** 瓦片快照里的方块 id → 当前语言显示名（悬停行与 {@link #probe} 共用）。 */
    static String blockName(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null) {
            return blockId;
        }
        return BuiltInRegistries.BLOCK.get(id).getName().getString();
    }

    /**
     * 群系的本地化名（原版键：{@code biome.<namespace>.<path>}）。
     *
     * <p>同样只读本区块的群系存储（{@link #loadedBiomeId}）；区块没加载时返回 {@code "?"}，
     * 不拿模糊采样出来的假群系充数。
     */
    static String biomeName(ClientLevel level, BlockPos pos) {
        String id = loadedBiomeId(level, pos.getX(), pos.getY(), pos.getZ());
        ResourceLocation location = id == null ? null : ResourceLocation.tryParse(id);
        if (location == null) {
            return "?";
        }
        return Component.translatable(
                "biome." + location.getNamespace() + "." + location.getPath()).getString();
    }
}
