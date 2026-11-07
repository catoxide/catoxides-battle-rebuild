package example.contentpack.mapv2.client;

import example.contentpack.mapv2.network.MapRevealPayload;
import example.contentpack.mapv2.network.MapRevealRequestPayload;
import example.contentpack.mapv2.reveal.RevealChunkSet;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端侧「探明范围」：服务端说是范围外的，群系/结构预测一律不画。
 *
 * <p>进入维度时请求一次快照；之后按服务端增量做位 OR。服务端关闭探明功能时
 * 永远收不到包，{@link #isRevealed} 保持「全部可见」，与旧行为一致。
 */
public final class MapRevealClient {

    private static final Map<ResourceLocation, RevealChunkSet> SETS = new HashMap<>();

    private static boolean enabled;
    private static long version;
    private static ResourceLocation requestedDimension;

    private MapRevealClient() {
    }

    public static void reset() {
        SETS.clear();
        enabled = false;
        requestedDimension = null;
        version++;
    }

    /** 每客户端 tick：换维度就重新要一次快照。 */
    public static void tick(Minecraft minecraft) {
        if (MapWorld.id() == null || minecraft.level == null
                || minecraft.getConnection() == null) {
            return;
        }
        ResourceLocation dimension = minecraft.level.dimension().location();
        if (dimension.equals(requestedDimension)) {
            return;
        }
        requestedDimension = dimension;
        PacketDistributor.sendToServer(new MapRevealRequestPayload(dimension));
    }

    public static void receive(MapRevealPayload payload) {
        RevealChunkSet set = SETS.computeIfAbsent(payload.dimension(),
                ignored -> new RevealChunkSet());
        long added = 0L;
        for (int i = 0; i < payload.cells().length; i++) {
            added |= set.add(payload.cells()[i], payload.masks()[i]);
        }
        boolean wasEnabled = enabled;
        enabled = true;
        if (added != 0L || !wasEnabled) {
            version++;
        }
    }

    /** 未启用限制时恒为 true（旧行为：预测铺满视口）。 */
    public static boolean isRevealed(ResourceLocation dimension, int blockX, int blockZ) {
        if (!enabled) {
            return true;
        }
        RevealChunkSet set = SETS.get(dimension);
        return set != null && set.isRevealed(blockX >> 4, blockZ >> 4);
    }

    /** 结构查询单元里至少有一个区块被探明；未启用限制时恒为 true。 */
    public static boolean anyInStructureCell(ResourceLocation dimension, int cellX, int cellZ) {
        if (!enabled) {
            return true;
        }
        RevealChunkSet set = SETS.get(dimension);
        return set != null && set.hasAnyInStructureCell(cellX, cellZ);
    }

    /** 预测纹理缓存的版本：范围一变就要重画。 */
    public static long version() {
        return version;
    }
}