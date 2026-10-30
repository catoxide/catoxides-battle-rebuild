package example.contentpack.mapv2.client;

import example.contentpack.mapv2.MapLayer;
import example.contentpack.mapv2.MapStore;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
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
        if (!(level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false)
                instanceof LevelChunk)) {
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

    private static String blockName(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null) {
            return blockId;
        }
        return BuiltInRegistries.BLOCK.get(id).getName().getString();
    }

    /** 群系的本地化名（原版键：{@code biome.<namespace>.<path>}）。 */
    static String biomeName(ClientLevel level, BlockPos pos) {
        Holder<Biome> biome = level.getBiome(pos);
        return biome.unwrapKey()
                .map(key -> Component.translatable(
                        "biome." + key.location().getNamespace() + "." + key.location().getPath())
                        .getString())
                .orElse("?");
    }
}
