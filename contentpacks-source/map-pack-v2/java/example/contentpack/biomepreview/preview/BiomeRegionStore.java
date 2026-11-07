package example.contentpack.biomepreview.preview;

import com.mojang.logging.LogUtils;
import example.contentpack.mapv2.MapStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * 预测区域的磁盘缓存（按世界 UUID + 维度分目录，和地图瓦片同一个世界根）。
 *
 * <p>格式与地图瓦片一致：zlib 压缩的二进制，{@code MPB1} magic。
 * 文件只是数据，不绑定使用者：显示层读它，将来的建筑/结构查询也能直接复用同一份。
 *
 * <p>路径：{@code data/map-pack/worlds/<uuid>/biomes/<ns>/<path>/r.<cx>.<cz>.<r>.<s>.mpb}
 * ——文件名里带圆心/半径/步长，改配置后旧文件仍对应它当年的采样网格。
 */
public final class BiomeRegionStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAGIC = 0x4D504231; // "MPB1"
    private static final int VERSION = 1;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "map-pack-biome-io");
        thread.setDaemon(true);
        return thread;
    });

    private BiomeRegionStore() {
    }

    public static Path dir(UUID worldId, ResourceLocation dimension) {
        return MapStorage.worldDir(worldId)
                .resolve("biomes")
                .resolve(dimension.getNamespace())
                .resolve(dimension.getPath());
    }

    public static Path file(UUID worldId, ResourceLocation dimension,
                            int centerX, int centerZ, int radius, int step) {
        return dir(worldId, dimension).resolve(
                "r." + centerX + "." + centerZ + "." + radius + "." + step + ".mpb");
    }

    public static boolean has(UUID worldId, ResourceLocation dimension,
                              int centerX, int centerZ, int radius, int step) {
        return worldId != null
                && Files.isRegularFile(file(worldId, dimension, centerX, centerZ, radius, step));
    }

    /** 异步读；成功后在主线程回调，失败回调 {@code null}。 */
    public static void load(UUID worldId, ResourceLocation dimension,
                            int centerX, int centerZ, int radius, int step,
                            Consumer<BiomeRegion> onLoaded) {
        if (worldId == null) {
            Minecraft.getInstance().execute(() -> onLoaded.accept(null));
            return;
        }
        Path path = file(worldId, dimension, centerX, centerZ, radius, step);
        IO.execute(() -> {
            BiomeRegion region = null;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                    new InflaterInputStream(Files.newInputStream(path))))) {
                region = read(in, dimension, centerX, centerZ, radius, step);
            } catch (IOException | RuntimeException error) {
                LOGGER.warn("[BiomePreview] cannot read {}: {}", path.getFileName(), error.toString());
            }
            BiomeRegion result = region;
            Minecraft.getInstance().execute(() -> onLoaded.accept(result));
        });
    }

    /** 异步写；写失败只记日志，不影响内存缓存。 */
    public static void save(UUID worldId, BiomeRegion region) {
        if (worldId == null) {
            return;
        }
        Path path = file(worldId, region.dimension(), region.centerX(), region.centerZ(),
                region.radius(), region.step());
        BiomeRegion snapshot = region;
        IO.execute(() -> {
            try {
                Files.createDirectories(path.getParent());
                Path temp = path.resolveSibling(path.getFileName() + ".tmp");
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                        new DeflaterOutputStream(Files.newOutputStream(temp))))) {
                    write(out, snapshot);
                }
                try {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException error) {
                LOGGER.warn("[BiomePreview] cannot write {}: {}", path.getFileName(), error.toString());
            }
        });
    }

    private static void write(DataOutputStream out, BiomeRegion region) throws IOException {
        out.writeInt(MAGIC);
        out.writeByte(VERSION);
        out.writeInt(region.centerX());
        out.writeInt(region.centerZ());
        out.writeInt(region.radius());
        out.writeInt(region.step());
        out.writeInt(region.size());
        out.writeInt(region.colors().length);
        for (int color : region.colors()) {
            out.writeInt(color);
        }
        for (String biome : region.biomeIds()) {
            out.writeUTF(biome);
        }
        for (short cell : region.cells()) {
            out.writeShort(cell);
        }
    }

    private static BiomeRegion read(DataInputStream in, ResourceLocation dimension,
                                    int centerX, int centerZ, int radius, int step)
            throws IOException {
        if (in.readInt() != MAGIC) {
            throw new IOException("bad magic");
        }
        int version = in.readUnsignedByte();
        if (version != VERSION) {
            throw new IOException("unsupported version " + version);
        }
        int storedCenterX = in.readInt();
        int storedCenterZ = in.readInt();
        int storedRadius = in.readInt();
        int storedStep = in.readInt();
        int size = in.readInt();
        if (storedCenterX != centerX || storedCenterZ != centerZ
                || storedRadius != radius || storedStep != step
                || size != BiomeRegionGrid.sizeForRadius(radius, step) || size <= 0 || size > 4096) {
            throw new IOException("header mismatch");
        }
        int paletteSize = in.readInt();
        if (paletteSize < 0 || paletteSize > 4096) {
            throw new IOException("bad palette size " + paletteSize);
        }
        int[] colors = new int[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            colors[i] = in.readInt();
        }
        String[] biomeIds = new String[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            biomeIds[i] = in.readUTF();
        }
        short[] cells = new short[size * size];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = in.readShort();
        }
        return new BiomeRegion(dimension, storedCenterX, storedCenterZ,
                storedRadius, storedStep, size, colors, biomeIds, cells);
    }
}
