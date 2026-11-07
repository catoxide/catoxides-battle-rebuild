package example.contentpack.biomepreview.client;

import com.catoxide.catoxidesbattlerebuild.core.map.IMapBiomeSource;
import com.mojang.logging.LogUtils;
import example.contentpack.biomepreview.network.BiomePreviewRequestPayload;
import example.contentpack.biomepreview.network.BiomePreviewResultPayload;
import example.contentpack.biomepreview.preview.BiomeRegion;
import example.contentpack.biomepreview.preview.BiomeRegionGrid;
import example.contentpack.biomepreview.preview.BiomeRegionStore;
import example.contentpack.biomepreview.preview.PreviewSettings;
import example.contentpack.mapv2.client.MapWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 地图群系预测图层的客户端数据源。
 *
 * <p>服务端按圆采样，客户端按半径分格请求：视口中心附近 3×3 个节点即可覆盖
 * 半径范围内的整片区域（见 {@link BiomeRegionGrid}）。同一节点先查内存，
 * 再查按世界 UUID 存盘的 {@link BiomeRegionStore}，都没有才向服务端要。
 *
 * <p>这样「预先计算」是一份可复用数据：显示层只画配置半径圆内的部分，
 * 建筑/结构查询也可以直接复用同一批 {@link BiomeRegion}，不必重新采样。
 */
public final class MapBiomeClientSource implements IMapBiomeSource {

    public static final MapBiomeClientSource INSTANCE = new MapBiomeClientSource();
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int MAX_REGIONS = 32;
    private static final long REQUEST_TIMEOUT_NANOS = 10_000_000_000L;
    private static final int NODE_RADIUS = 1;

    private record RegionKey(ResourceLocation dimension, int centerX, int centerZ,
                             int radius, int step) {
    }

    /** 访问顺序 = LRU：视口来回移动时留最近看过的区域。 */
    private static final Map<RegionKey, BiomeRegion> REGIONS =
            new LinkedHashMap<>(16, 0.75f, true);
    private static final Set<RegionKey> DISK_LOADING = new LinkedHashSet<>();
    private static final Set<RegionKey> DISK_FAILED = new LinkedHashSet<>();

    private static long version;
    private static RegionKey inFlight;
    private static long inFlightAt;
    private static ResourceLocation lastDimension;
    private static Object lastConnection;
    private static UUID lastWorldId;
    /** 服务端实际允许的半径；比本地配置小时，后续请求跟着收缩，避免无限重试。 */
    private static int serverRadius;
    /** 服务端实际采用的步长（会按服务端预算抬高）；请求键必须跟它一致，否则会来回重试。 */
    private static int serverStep;
    /** 重采样时绝大多数像素落在同一个 region，避免每次都遍历列表。 */
    private static BiomeRegion cachedRegion;

    private MapBiomeClientSource() {
    }

    @Override
    public void ensureArea(ResourceLocation dimension, int minBlockX, int minBlockZ, int sizeBlocks) {
        if (sizeBlocks <= 0 || !PreviewSettings.enabled()) {
            return;
        }
        checkWorld(dimension);
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }

        int radius = PreviewSettings.radiusBlocks();
        if (serverRadius > 0) {
            radius = Math.min(radius, serverRadius);
        }
        int step = PreviewSettings.stepForRadius(radius);
        if (serverStep > 0) {
            step = Math.max(step, serverStep);
        }
        int spacing = Math.max(1, radius);
        int centerX = minBlockX + sizeBlocks / 2;
        int centerZ = minBlockZ + sizeBlocks / 2;
        int baseX = BiomeRegionGrid.gridIndex(centerX + spacing / 2, spacing);
        int baseZ = BiomeRegionGrid.gridIndex(centerZ + spacing / 2, spacing);

        RegionKey requestKey = null;
        int requestCenterX = 0;
        int requestCenterZ = 0;
        long nearest = Long.MAX_VALUE;
        for (int dz = -NODE_RADIUS; dz <= NODE_RADIUS; dz++) {
            for (int dx = -NODE_RADIUS; dx <= NODE_RADIUS; dx++) {
                int nodeX = BiomeRegionGrid.gridCenter(baseX + dx, spacing);
                int nodeZ = BiomeRegionGrid.gridCenter(baseZ + dz, spacing);
                RegionKey key = new RegionKey(dimension, nodeX, nodeZ, radius, step);
                if (REGIONS.containsKey(key) || DISK_LOADING.contains(key)) {
                    continue;
                }
                UUID worldId = MapWorld.id();
                if (worldId != null && PreviewSettings.persist()
                        && !DISK_FAILED.contains(key)
                        && BiomeRegionStore.has(worldId, dimension, nodeX, nodeZ, radius, step)) {
                    DISK_LOADING.add(key);
                    BiomeRegionStore.load(worldId, dimension, nodeX, nodeZ, radius, step, region -> {
                        DISK_LOADING.remove(key);
                        if (region != null) {
                            remember(key, region);
                        } else {
                            DISK_FAILED.add(key);
                        }
                    });
                    continue;
                }
                // 一次只发一个请求：先要视口中心那块，边缘随后补
                long distance = (long) dx * dx + (long) dz * dz;
                if (distance < nearest) {
                    nearest = distance;
                    requestKey = key;
                    requestCenterX = nodeX;
                    requestCenterZ = nodeZ;
                }
            }
        }
        if (requestKey == null) {
            return;
        }

        long now = System.nanoTime();
        if (inFlight != null && now - inFlightAt < REQUEST_TIMEOUT_NANOS) {
            return;
        }
        inFlight = requestKey;
        inFlightAt = now;
        LOGGER.info("[BiomePreview] request dim={} center=({},{}) radius={} step={}",
                dimension, requestCenterX, requestCenterZ, radius, step);
        PacketDistributor.sendToServer(
                new BiomePreviewRequestPayload(dimension, requestCenterX, requestCenterZ, radius, step));
    }

    @Override
    public int colorAt(ResourceLocation dimension, int blockX, int blockZ) {
        BiomeRegion region = findRegion(dimension, blockX, blockZ);
        return region == null ? 0 : region.colorAt(blockX, blockZ);
    }

    @Override
    public String biomeIdAt(ResourceLocation dimension, int blockX, int blockZ) {
        BiomeRegion region = findRegion(dimension, blockX, blockZ);
        return region == null ? null : region.biomeIdAt(blockX, blockZ);
    }

    /** 供建筑/结构查询直接使用：命中内存缓存时返回原始区域，不复制数组。 */
    public static BiomeRegion regionAt(ResourceLocation dimension, int blockX, int blockZ) {
        return findRegion(dimension, blockX, blockZ);
    }

    @Override
    public long version() {
        return version;
    }

    /** 由网络层在主线程调用。 */
    public static void receive(BiomePreviewResultPayload payload) {
        if (payload.size() <= 0 || payload.cells().length != payload.size() * payload.size()) {
            LOGGER.warn("[BiomePreview] dropping malformed result center=({},{}) size={} cells={}",
                    payload.centerX(), payload.centerZ(), payload.size(), payload.cells().length);
            return;
        }
        BiomeRegion region;
        try {
            region = new BiomeRegion(payload.dimension(), payload.centerX(), payload.centerZ(),
                    payload.radius(), payload.step(), payload.size(),
                    payload.paletteColors(), payload.paletteBiomes(), payload.cells());
        } catch (IllegalArgumentException error) {
            LOGGER.warn("[BiomePreview] dropping invalid result: {}", error.getMessage());
            return;
        }
        RegionKey key = keyOf(region);
        remember(key, region);
        serverRadius = region.radius();
        serverStep = region.step();
        inFlight = null;
        if (PreviewSettings.persist() && MapWorld.id() != null) {
            BiomeRegionStore.save(MapWorld.id(), region);
        }
        LOGGER.info("[BiomePreview] result center=({},{}) radius={} step={} size={} cache={}",
                region.centerX(), region.centerZ(), region.radius(), region.step(),
                region.size(), REGIONS.size());
    }

    private static RegionKey keyOf(BiomeRegion region) {
        return new RegionKey(region.dimension(), region.centerX(), region.centerZ(),
                region.radius(), region.step());
    }

    private static void remember(RegionKey key, BiomeRegion region) {
        REGIONS.put(key, region);
        cachedRegion = null;
        version++;
        Iterator<RegionKey> iterator = REGIONS.keySet().iterator();
        while (REGIONS.size() > MAX_REGIONS && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static BiomeRegion findRegion(ResourceLocation dimension, int blockX, int blockZ) {
        BiomeRegion cached = cachedRegion;
        if (cached != null && cached.dimension().equals(dimension)
                && cached.paletteIndexAt(blockX, blockZ) >= 0) {
            return cached;
        }
        for (BiomeRegion region : REGIONS.values()) {
            if (!region.dimension().equals(dimension) || !region.contains(blockX, blockZ)) {
                continue;
            }
            if (region.paletteIndexAt(blockX, blockZ) >= 0) {
                cachedRegion = region;
                return region;
            }
        }
        return null;
    }

    /** 换连接（进出世界/换存档）、换维度或换世界 UUID 时丢掉内存缓存；磁盘按world分开，不复用。 */
    private static void checkWorld(ResourceLocation dimension) {
        Object connection = Minecraft.getInstance().getConnection();
        UUID worldId = MapWorld.id();
        boolean changed = connection != lastConnection
                || !dimension.equals(lastDimension)
                || (worldId != null && !worldId.equals(lastWorldId));
        if (!changed) {
            return;
        }
        REGIONS.clear();
        DISK_LOADING.clear();
        DISK_FAILED.clear();
        cachedRegion = null;
        inFlight = null;
        serverRadius = 0;
        serverStep = 0;
        lastConnection = connection;
        lastDimension = dimension;
        lastWorldId = worldId;
        version++;
    }
}
