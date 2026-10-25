package example.contentpack.biomepreview.client;

import com.catoxide.catoxidesbattlerebuild.core.map.IMapBiomeSource;
import com.mojang.logging.LogUtils;
import example.contentpack.biomepreview.network.BiomePreviewRequestPayload;
import example.contentpack.biomepreview.network.BiomePreviewResultPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 地图群系预测图层的客户端数据源。
 *
 * <p>自己只做三件事：判断视口缺不缺数据 → 发一个请求 → 缓存服务端回来的图。
 * 采样、调度、worldgen 全部在服务端（{@code /biomepreview} 那套）。
 *
 * <p>缓存保留最近 {@link #MAX_REGIONS} 块，视口来回移动时不用重复请求。
 */
public final class MapBiomeClientSource implements IMapBiomeSource {

    public static final MapBiomeClientSource INSTANCE = new MapBiomeClientSource();
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int MAX_REGIONS = 8;
    private static final int STEP = 4;
    private static final long REQUEST_TIMEOUT_NANOS = 10_000_000_000L;

    private record Region(ResourceLocation dimension, int originX, int originZ,
                          int step, int size, int[] colors, String[] biomeIds, short[] cells) {
        int span() {
            return size * step;
        }

        boolean covers(int minX, int minZ, int span) {
            return minX >= originX && minZ >= originZ
                    && minX + span <= originX + span() && minZ + span <= originZ + span();
        }

        int paletteIndexAt(int blockX, int blockZ) {
            int dx = blockX - originX;
            int dz = blockZ - originZ;
            if (dx < 0 || dz < 0 || dx >= span() || dz >= span()) {
                return -1;
            }
            int index = (dz / step) * size + (dx / step);
            if (index < 0 || index >= cells.length) {
                return -1;
            }
            return cells[index];
        }
    }

    private static final List<Region> REGIONS = new ArrayList<>();

    private static long version;
    private static boolean inFlight;
    private static long inFlightAt;
    private static int lastCenterX = Integer.MIN_VALUE;
    private static int lastCenterZ = Integer.MIN_VALUE;
    private static int lastRadius;
    private static ResourceLocation lastDimension;
    private static Object lastConnection;
    /** 重采样时绝大多数像素落在同一个 region，避免每次都遍历列表。 */
    private static Region cachedRegion;

    private MapBiomeClientSource() {
    }

    @Override
    public void ensureArea(ResourceLocation dimension, int minBlockX, int minBlockZ, int sizeBlocks) {
        if (sizeBlocks <= 0) {
            return;
        }
        checkWorld(dimension);
        if (covered(dimension, minBlockX, minBlockZ, sizeBlocks)) {
            return;
        }
        long now = System.nanoTime();
        if (inFlight && now - inFlightAt < REQUEST_TIMEOUT_NANOS) {
            return;
        }

        int centerX = minBlockX + sizeBlocks / 2;
        int centerZ = minBlockZ + sizeBlocks / 2;
        int radius = Math.max(256, sizeBlocks / 2 + 64);
        if (Math.abs(centerX - lastCenterX) < radius / 2
                && Math.abs(centerZ - lastCenterZ) < radius / 2
                && radius == lastRadius) {
            return; // 视口几乎没动
        }

        inFlight = true;
        inFlightAt = now;
        lastCenterX = centerX;
        lastCenterZ = centerZ;
        lastRadius = radius;
        var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
        LOGGER.info("[BiomePreview] request dim={} center=({},{}) radius={} step={} connection={}",
                dimension, centerX, centerZ, radius, STEP,
                connection == null ? "null" : "ok");
        if (connection == null) {
            inFlight = false;
            return;
        }
        PacketDistributor.sendToServer(
                new BiomePreviewRequestPayload(dimension, centerX, centerZ, radius, STEP));
    }

    @Override
    public int colorAt(ResourceLocation dimension, int blockX, int blockZ) {
        Region region = findRegion(dimension, blockX, blockZ);
        if (region == null) {
            return 0;
        }
        int paletteIndex = region.paletteIndexAt(blockX, blockZ);
        if (paletteIndex < 0 || paletteIndex >= region.colors().length) {
            return 0;
        }
        return region.colors()[paletteIndex];
    }

    @Override
    public String biomeIdAt(ResourceLocation dimension, int blockX, int blockZ) {
        Region region = findRegion(dimension, blockX, blockZ);
        if (region == null) {
            return null;
        }
        int paletteIndex = region.paletteIndexAt(blockX, blockZ);
        if (paletteIndex < 0 || paletteIndex >= region.biomeIds().length) {
            return null;
        }
        return region.biomeIds()[paletteIndex];
    }

    private static Region findRegion(ResourceLocation dimension, int blockX, int blockZ) {
        Region cached = cachedRegion;
        if (cached != null && cached.dimension().equals(dimension)
                && cached.paletteIndexAt(blockX, blockZ) >= 0) {
            return cached;
        }
        for (int i = REGIONS.size() - 1; i >= 0; i--) {
            Region region = REGIONS.get(i);
            if (!region.dimension().equals(dimension)) {
                continue;
            }
            if (region.paletteIndexAt(blockX, blockZ) >= 0) {
                cachedRegion = region;
                return region;
            }
        }
        return null;
    }

    @Override
    public long version() {
        return version;
    }

    /** 由网络层在主线程调用。 */
    public static void receive(BiomePreviewResultPayload payload) {
        int step = Math.max(1, payload.step());
        int originX = payload.centerX() - payload.radius();
        int originZ = payload.centerZ() - payload.radius();
        REGIONS.add(new Region(payload.dimension(), originX, originZ, step,
                payload.size(), payload.paletteColors(), payload.paletteBiomes(), payload.cells()));
        while (REGIONS.size() > MAX_REGIONS) {
            REGIONS.remove(0);
        }
        cachedRegion = null;
        version++;
        inFlight = false;
        LOGGER.info("[BiomePreview] result center=({},{}) size={} step={} palette={} regionCount={}",
                payload.centerX(), payload.centerZ(), payload.size(), step,
                payload.paletteColors().length, REGIONS.size());
    }

    private static boolean covered(ResourceLocation dimension, int minX, int minZ, int span) {
        for (Region region : REGIONS) {
            if (region.dimension().equals(dimension) && region.covers(minX, minZ, span)) {
                return true;
            }
        }
        return false;
    }

    /** 换连接（进出世界/换存档）或换维度时丢掉旧缓存，预测图绝不跨世界复用。 */
    private static void checkWorld(ResourceLocation dimension) {
        Object connection = net.minecraft.client.Minecraft.getInstance().getConnection();
        boolean newConnection = connection != lastConnection;
        boolean newDimension = !dimension.equals(lastDimension);
        if (newConnection || newDimension) {
            REGIONS.clear();
            cachedRegion = null;
            lastConnection = connection;
            lastDimension = dimension;
            inFlight = false;
            version++;
        }
    }
}
