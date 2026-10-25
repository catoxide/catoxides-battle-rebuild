package example.contentpack.map;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * 瓦片持久化（客户端本地）。
 *
 * <p>路径：{@code <gameDir>/data/map-pack/<namespace>/<path>/<layer>/r.<tileX>.<tileZ>.mpt}
 * ——维度 → 层（{@code top} 或 {@code y<absoluteY>}）→ 瓦片。
 * 层名用绝对 y，世界高度变化后旧存档仍对应原来的高度。
 *
 * <p>格式（zlib 压缩的二进制，版本 1）：
 * <pre>
 *   int  magic "MPT1"
 *   u8   version
 *   u16  size (128)
 *   u8[] known bitset (size*size/8)
 *   int[] argb   (size*size)
 *   short[] height
 *   byte[] fluid
 *   -- v2 起 --
 *   u16  blockPaletteSize
 *   UTF[] blockPalette
 *   short[] blockIndex (size*size)
 * </pre>
 * v1 文件仍可读：方块信息为空，hover 只能靠实时区块。
 *
 * <p>线程：主线程只做数组快照（几毫秒内），压缩/解压/读写全在单线程池；
 * 读回后用 {@code Minecraft.execute} 回主线程写入 {@link MapStore}。
 */
public final class MapStorage {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAGIC = 0x4D505431; // "MPT1"
    private static final int VERSION = 2;
    private static final int PIXELS = MapTile.SIZE * MapTile.SIZE;
    private static final int KNOWN_BYTES = (PIXELS + 7) / 8;
    private static final int SAVE_BATCH = 8;
    private static final int SAVE_INTERVAL_TICKS = 20;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "map-pack-io");
        thread.setDaemon(true);
        return thread;
    });

    private static final Set<TileKey> DIRTY = new HashSet<>();
    private static final Set<TileKey> LOADING = new HashSet<>();
    private static int tickCounter;
    private static long generation;

    private MapStorage() {
    }

    /** 采集/重采后调用：该瓦片需要落盘。 */
    public static void markDirty(ResourceLocation dimension, String layer, int tileX, int tileZ) {
        DIRTY.add(new TileKey(dimension, layer, tileX, tileZ));
    }

    /** 每客户端 tick 调用。 */
    public static void tick() {
        if (DIRTY.isEmpty()) {
            return;
        }
        if (++tickCounter % SAVE_INTERVAL_TICKS != 0) {
            return;
        }
        flush(SAVE_BATCH);
    }

    /** 换世界/退出前调用：把剩余脏瓦片全部提交（异步，不阻塞）。 */
    public static void flushAll() {
        flush(Integer.MAX_VALUE);
    }

    /** 清空待办集合（不删文件）。 */
    public static void clear() {
        DIRTY.clear();
        LOADING.clear();
        tickCounter = 0;
    }

    /** 每次磁盘加载成功 +1；全屏地图用它决定要不要重绘（避免每帧重采样）。 */
    public static long generation() {
        return generation;
    }

    /** 把任务排到地图 IO 线程（单线程 FIFO，与瓦片读写串行）。 */
    public static void ioExecute(Runnable task) {
        IO.execute(task);
    }

    /** 渲染可见范围时调用：缺的瓦片异步从磁盘读。 */
    public static void requestLoad(ResourceLocation dimension, String layer, int tileX, int tileZ) {
        if (example.contentpack.map.client.MapWorld.id() == null) {
            return; // 还没拿到世界 ID：不知道读写哪个目录
        }
        if (MapStore.get(dimension, layer, tileX, tileZ) != null) {
            return;
        }
        TileKey key = new TileKey(dimension, layer, tileX, tileZ);
        if (!LOADING.add(key)) {
            return;
        }
        IO.execute(() -> load(key));
    }

    private static void flush(int limit) {
        if (example.contentpack.map.client.MapWorld.id() == null) {
            return;
        }
        Iterator<TileKey> iterator = DIRTY.iterator();
        int submitted = 0;
        while (iterator.hasNext() && submitted < limit) {
            TileKey key = iterator.next();
            iterator.remove();
            MapTile tile = MapStore.get(key.dimension(), key.layer(), key.x(), key.z());
            if (tile == null) {
                continue;
            }
            // 主线程只做快照；压缩和写盘在 IO 线程
            SaveJob job = new SaveJob(key, tile.copyArgb(), tile.copyHeight(),
                    tile.copyFluid(), tile.copyKnown(),
                    tile.copyBlockPalette(), tile.copyBlockIndex());
            IO.execute(() -> write(job));
            submitted++;
        }
    }

    private static void write(SaveJob job) {
        if (example.contentpack.map.client.MapWorld.id() == null) {
            return; // 退世界了，写盘任务作废
        }
        Path path = path(job.key());
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(path));
                 DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(file))) {
                out.writeInt(MAGIC);
                out.writeByte(VERSION);
                out.writeShort(MapTile.SIZE);
                out.write(packKnown(job.known()));
                for (int color : job.argb()) {
                    out.writeInt(color);
                }
                for (short y : job.height()) {
                    out.writeShort(y);
                }
                out.write(job.fluid());
                out.writeShort(job.blockPalette().size());
                for (String blockId : job.blockPalette()) {
                    out.writeUTF(blockId);
                }
                for (short index : job.blockIndex()) {
                    out.writeShort(index);
                }
            }
        } catch (IOException ex) {
            LOGGER.warn("[MapPack] save {} failed: {}", path, ex.getMessage());
        }
    }

    private static void load(TileKey key) {
        if (example.contentpack.map.client.MapWorld.id() == null) {
            Minecraft.getInstance().execute(() -> LOADING.remove(key));
            return;
        }
        Path path = path(key);
        if (!Files.isRegularFile(path) && MapLayer.TOP.equals(key.layer())) {
            // 兼容首版（无层目录）的 top 存档
            path = legacyTopPath(key);
        }
        if (!Files.isRegularFile(path)) {
            Minecraft.getInstance().execute(() -> LOADING.remove(key));
            return;
        }
        try (InputStream file = new BufferedInputStream(Files.newInputStream(path));
             DataInputStream in = new DataInputStream(new InflaterInputStream(file))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("bad magic");
            }
            int version = in.readUnsignedByte();
            if (version < 1 || version > VERSION) {
                throw new IOException("unsupported version " + version);
            }
            int size = in.readUnsignedShort();
            if (size != MapTile.SIZE) {
                throw new IOException("unsupported tile size " + size);
            }

            byte[] bits = in.readNBytes(KNOWN_BYTES);
            boolean[] known = unpackKnown(bits);
            int[] argb = new int[PIXELS];
            for (int i = 0; i < PIXELS; i++) {
                argb[i] = in.readInt();
            }
            short[] height = new short[PIXELS];
            for (int i = 0; i < PIXELS; i++) {
                height[i] = in.readShort();
            }
            byte[] fluid = new byte[PIXELS];
            in.readFully(fluid);

            java.util.List<String> blockPalette = new java.util.ArrayList<>();
            short[] blockIndex = new short[PIXELS];
            if (version >= 2) {
                int paletteSize = in.readUnsignedShort();
                for (int i = 0; i < paletteSize; i++) {
                    blockPalette.add(in.readUTF());
                }
                for (int i = 0; i < PIXELS; i++) {
                    blockIndex[i] = in.readShort();
                }
            }

            MapTile tile = MapTile.fromData(argb, height, fluid, known, blockIndex, blockPalette);
            Minecraft.getInstance().execute(() -> {
                LOADING.remove(key);
                MapStore.putIfAbsent(key.dimension(), key.layer(), key.x(), key.z(), tile);
                generation++;
            });
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("[MapPack] load {} failed: {}", path, ex.getMessage());
            Minecraft.getInstance().execute(() -> LOADING.remove(key));
        }
    }

    private static Path path(TileKey key) {
        return dimensionDir(key)
                .resolve(key.layer())
                .resolve("r." + key.x() + "." + key.z() + ".mpt");
    }

    private static Path legacyTopPath(TileKey key) {
        return dimensionDir(key).resolve("r." + key.x() + "." + key.z() + ".mpt");
    }

    private static Path dimensionDir(TileKey key) {
        java.util.UUID worldId = example.contentpack.map.client.MapWorld.id();
        if (worldId == null) {
            throw new IllegalStateException("world id not available");
        }
        return worldDir(worldId)
                .resolve(key.dimension().getNamespace())
                .resolve(key.dimension().getPath());
    }

    /** 所有世界的根目录：`<gameDir>/data/map-pack/worlds/`。 */
    public static Path worldsRoot() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("data").resolve("map-pack").resolve("worlds");
    }

    /** 世界级根目录：`<gameDir>/data/map-pack/worlds/<uuid>/`。 */
    public static Path worldDir(java.util.UUID worldId) {
        return worldsRoot().resolve(worldId.toString());
    }

    /**
     * 把一个世界的地图瓦片复制到另一个世界（继承用，复制后互不依赖）。
     * 目标已存在的文件跳过，不覆盖当前世界新采集的数据。
     */
    public static void copyWorldData(java.util.UUID from, java.util.UUID to, Runnable onDone) {
        IO.execute(() -> {
            Path source = worldDir(from);
            Path target = worldDir(to);
            if (!Files.isDirectory(source)) {
                Minecraft.getInstance().execute(onDone);
                return;
            }
            try (var stream = Files.walk(source)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".mpt"))
                        .forEach(path -> {
                            Path destination = target.resolve(source.relativize(path));
                            if (Files.exists(destination)) {
                                return;
                            }
                            try {
                                Files.createDirectories(destination.getParent());
                                Files.copy(path, destination);
                            } catch (IOException ex) {
                                LOGGER.warn("[MapPack] copy {} failed: {}", path, ex.getMessage());
                            }
                        });
            } catch (IOException ex) {
                LOGGER.warn("[MapPack] copy world failed: {}", ex.getMessage());
            }
            Minecraft.getInstance().execute(onDone);
        });
    }

    public static boolean worldDirExists(java.util.UUID worldId) {
        return Files.isDirectory(worldDir(worldId));
    }

    private static byte[] packKnown(boolean[] known) {
        byte[] bits = new byte[KNOWN_BYTES];
        for (int i = 0; i < PIXELS; i++) {
            if (known[i]) {
                bits[i >> 3] |= (byte) (1 << (i & 7));
            }
        }
        return bits;
    }

    private static boolean[] unpackKnown(byte[] bits) {
        boolean[] known = new boolean[PIXELS];
        for (int i = 0; i < PIXELS; i++) {
            known[i] = (bits[i >> 3] & (1 << (i & 7))) != 0;
        }
        return known;
    }

    private record TileKey(ResourceLocation dimension, String layer, int x, int z) {
    }

    private record SaveJob(TileKey key, int[] argb, short[] height, byte[] fluid, boolean[] known,
                           java.util.List<String> blockPalette, short[] blockIndex) {
    }
}
