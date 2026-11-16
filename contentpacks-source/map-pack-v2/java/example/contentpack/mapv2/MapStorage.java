package example.contentpack.mapv2;

import com.mojang.logging.LogUtils;
import example.contentpack.mapv2.network.MapTileSync;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * 瓦片持久化（客户端本地）。
 *
 * <p>路径：{@code <gameDir>/data/map-pack/<namespace>/<path>/<layer>/r.<tileX>.<tileZ>.mpt}
 * ——维度 → 层（{@code top} 或 {@code y<absoluteY>}）→ 区域（512 格）。
 * 层名用绝对 y，世界高度变化后旧存档仍对应原来的高度。
 *
 * <p>格式（zlib 压缩的二进制，版本 1；v2 起带方块调色板；v3 起带群系调色板；
 * v5 起带表面栈调色板；v6 起表面栈每层带旋转；v8 起旋转按 22.5° 一档、矩形改存
 * 旋转前的本地矩形）：
 * <pre>
 *   int  magic "MPT1"
 *   u8   version
 *   u16  size ({@link MapTile#SIZE})
 *   u8[] known bitset (size*size/8)
 *   int[] argb   (size*size)
 *   short[] height
 *   byte[] fluid
 *   -- v2 起 --
 *   u16  blockPaletteSize
 *   UTF[] blockPalette
 *   short[] blockIndex (size*size)
 *   -- v3 起 --
 *   u16  biomePaletteSize
 *   UTF[] biomePalette
 *   short[] biomeIndex (size*size, -1 = 未记录)
 *   -- v5 起 --
 *   u16  stackPaletteSize
 *   per stack:
 *     u8   layerCount
 *     per layer:
 *       u8   u0, v0, u1, v1                    (0..64，v7 起；v6 及更早是 0..16)
 *       u8   x0, z0, x1, z1                    (0..16，旋转前的本地矩形)
 *       UTF  sprite                            (空 = 平色层，UV 全 0)
 *       UTF  blockId
 *       short y
 *       int   argb
 *       u8    alpha, count
 *       u8    rotation                         (v6 起；v8 起 0..15 = 22.5° 顺时针，
 *                                               v6/v7 是 0..3 = 90°)
 *   u8   hasStackIndex (1 = 紧跟 short[] stackIndex，0 = 整块没有叠层)
 *   short[] stackIndex (size*size, -1 = 该像素没有叠层)
 * </pre>
 * v1–v5 文件仍可读：缺的通道为空（方块信息 / 群系信息 / 叠层信息），v5 的叠层
 * 没有旋转字节，按 0 读。hover 回落实时区块或预测，渲染只画完整表面。
 * v7 把叠层的 u/v 从十六等分改成六十四等分（床贴图是 64×64）；读 v6 及更早的
 * 文件时乘 4 补到新格。v8 把旋转从 90° 档细到 22.5° 档、矩形改存旋转前的本地矩形；
 * 读 v7 及更早的文件时把世界矩形折回本地矩形、档位乘 4。
 *
 * <p>分区从 128 改成 512 之前写下的旧文件不会作废：读盘时把 4×4 块旧文件
 * 折进对应的 512 分区（{@link #foldLegacy}），写回后旧文件改名 {@code .mpt.old} 退役。
 *
 * <p>线程：主线程只做数组快照（几毫秒内），压缩/解压/读写全在单线程池；
 * 读回后用 {@code Minecraft.execute} 回主线程写入 {@link MapStore}。
 */
public final class MapStorage {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAGIC = 0x4D505431; // "MPT1"
    private static final int VERSION = 8;
    /** 单列最多存多少层；采集侧不会超过这个数，超了说明文件坏了。 */
    private static final int MAX_STACK_LAYERS = 16;
    /** 旧版（512 之前）的分区边长：一块 512 分区 = 4×4 块旧文件。 */
    private static final int LEGACY_SIZE = 128;
    private static final int LEGACY_PER_SIDE = MapTile.SIZE / LEGACY_SIZE;
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

    /**
     * 磁盘上确实存在的瓦片（正索引，世界启动时扫一次目录）。渲染侧只在命中索引时才发读请求，
     * 未采集区域不会每帧去戳文件系统。
     */
    private static final Set<TileKey> EXISTING = ConcurrentHashMap.newKeySet();
    private static volatile boolean indexReady;
    private static UUID indexingWorld;
    private static UUID indexedWorld;
    private static long indexEpoch;

    private static int tickCounter;
    private static long generation;
    private static int remoteDiag = 10;

    private MapStorage() {
    }

    /** 采集/重采后调用：该瓦片需要落盘。 */
    public static void markDirty(ResourceLocation dimension, String layer, int tileX, int tileZ) {
        DIRTY.add(new TileKey(dimension, layer, tileX, tileZ));
    }

    /** 每客户端 tick 调用。 */
    public static void tick() {
        ensureIndex();
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
        resetIndex();
    }

    /** 每次磁盘加载成功 +1；全屏地图用它决定要不要重绘（避免每帧重采样）。 */
    public static long generation() {
        return generation;
    }

    /** 瓦片当前所处的阶段；全屏地图用它区分「没采集」「没读回来」「已在内存」。 */
    public enum TileStatus {
        /** 内存里已有像素（本次采集，或已从磁盘读回）。 */
        MEMORY,
        /** 命中磁盘索引，读任务已排队。 */
        LOADING,
        /** 命中磁盘索引，还没排上读任务。 */
        DISK,
        /** 内存和磁盘都没有：这块从没被采集过。 */
        MISSING
    }

    public static TileStatus status(ResourceLocation dimension, String layer,
                                    int tileX, int tileZ) {
        if (MapStore.get(dimension, layer, tileX, tileZ) != null) {
            return TileStatus.MEMORY;
        }
        if (!indexReady) {
            return TileStatus.MISSING; // 索引未就绪，此时判定不代表磁盘真实状态
        }
        TileKey key = new TileKey(dimension, layer, tileX, tileZ);
        if (LOADING.contains(key)) {
            return TileStatus.LOADING;
        }
        return EXISTING.contains(key) ? TileStatus.DISK : TileStatus.MISSING;
    }

    public static boolean indexReady() {
        return indexReady;
    }

    public static int indexedTiles() {
        return EXISTING.size();
    }

    /**
     * 磁盘索引里出现过数据的层。层选择器用它列出「有内容的层」，
     * 而不是把维度里 24 个切片全摆出来让玩家自己撞空层。
     */
    public static Set<String> knownLayers(ResourceLocation dimension) {
        if (!indexReady) {
            return Set.of();
        }
        Set<String> layers = new HashSet<>();
        for (TileKey key : EXISTING) {
            if (key.dimension().equals(dimension)) {
                layers.add(key.layer());
            }
        }
        return layers;
    }

    /** 该层磁盘上有多少块瓦片；只在层选择器打开时调，开销 = 索引大小。 */
    public static int tileCount(ResourceLocation dimension, String layer) {
        if (!indexReady) {
            return 0;
        }
        int count = 0;
        for (TileKey key : EXISTING) {
            if (key.dimension().equals(dimension) && key.layer().equals(layer)) {
                count++;
            }
        }
        return count;
    }

    /** 把任务排到地图 IO 线程（单线程 FIFO，与瓦片读写串行）。 */
    public static void ioExecute(Runnable task) {
        IO.execute(task);
    }

    /**
     * 渲染可见范围时调用：缺的瓦片异步从磁盘读。
     *
     * @return 是否真的排了一个读任务（索引不命中 / 已加载 / 已排队都返回 false）
     */
    public static boolean requestLoad(ResourceLocation dimension, String layer,
                                      int tileX, int tileZ) {
        if (example.contentpack.mapv2.client.MapWorld.id() == null) {
            return false; // 还没拿到世界 ID：不知道读写哪个目录
        }
        if (MapStore.get(dimension, layer, tileX, tileZ) != null) {
            return false;
        }
        TileKey key = new TileKey(dimension, layer, tileX, tileZ);
        if (!indexReady || !EXISTING.contains(key)) {
            // 本地没有这块：问服务端要（多人共享地图）；单机/未开启时为空操作。
            // 索引没扫完时先不问，否则会把磁盘上已有的瓦片也当缺失重下。
            if (indexReady) {
                MapTileSync.requestTile(dimension, layer, tileX, tileZ);
            }
            return false;
        }
        if (!LOADING.add(key)) {
            return false;
        }
        IO.execute(() -> load(key));
        return true;
    }

    /**
     * 采集要往这块瓦片写像素、但内存里还没有它：先把磁盘上的旧数据读上来合并。
     *
     * <p>不依赖正索引 —— 世界 id 就绪后采集立刻开始，那时目录还在扫；
     * 这里直接让 IO 线程探一次文件（每块分区一个会话只探一次）。
     */
    public static void requestMerge(ResourceLocation dimension, String layer,
                                    int tileX, int tileZ) {
        if (example.contentpack.mapv2.client.MapWorld.id() == null) {
            return;
        }
        TileKey key = new TileKey(dimension, layer, tileX, tileZ);
        if (LOADING.add(key)) {
            IO.execute(() -> load(key));
        }
    }

    /**
     * 接收服务端发来的同构瓦片：校验 → 原子落盘 → 主线程合入内存。
     *
     * <p>只在本地缺失时才会请求（{@link #requestLoad}），所以直接写文件；
     * 内存里若恰好已有采集结果，只补未知像素，不覆盖新采集。
     */
    public static void acceptRemoteTile(ResourceLocation dimension, String layer,
                                        int tileX, int tileZ, long revision, byte[] bytes) {
        java.util.UUID worldId = example.contentpack.mapv2.client.MapWorld.id();
        if (worldId == null || bytes == null || bytes.length == 0) {
            return;
        }
        TileKey key = new TileKey(dimension, layer, tileX, tileZ);
        IO.execute(() -> {
            RawTile raw;
            try {
                raw = decodeTile(bytes);
            } catch (IOException | RuntimeException ex) {
                LOGGER.warn("[MapPack] remote tile {}/{} {},{} rejected: {}",
                        dimension, layer, tileX, tileZ, ex.getMessage());
                return;
            }
            if (raw.size() != MapTile.SIZE) {
                return;
            }
            MapTile remote = MapTile.fromData(raw.argb(), raw.height(), raw.fluid(), raw.known(),
                    raw.blockIndex(), raw.blockPalette(),
                    raw.stackIndex(), raw.stackPalette(),
                    raw.biomeIndex(), raw.biomePalette());
            // 下载在途期间玩家可能又采了同一块：磁盘上的本地像素也要保住
            MapTile existing = readDiskTile(worldId, key);
            if (existing != null) {
                remote.fillUnknownFrom(existing);
            }
            Path path = path(worldId, key);
            try {
                Files.createDirectories(path.getParent());
                Path temp = Files.createTempFile(path.getParent(), "remote-", ".tmp");
                try {
                    Files.write(temp, encode(remote));
                    moveReplace(temp, path);
                } finally {
                    Files.deleteIfExists(temp);
                }
                if (revision > 0) {
                    Files.setLastModifiedTime(path, FileTime.fromMillis(revision));
                }
            } catch (IOException ex) {
                LOGGER.warn("[MapPack] save remote tile {}/{} {},{} failed: {}",
                        dimension, layer, tileX, tileZ, ex.getMessage());
                return;
            }
            EXISTING.add(key);
            Minecraft.getInstance().execute(() -> {
                if (!worldId.equals(example.contentpack.mapv2.client.MapWorld.id())) {
                    return;
                }
                MapTile memory = MapStore.get(dimension, layer, tileX, tileZ);
                if (memory == null) {
                    MapStore.putIfAbsent(dimension, layer, tileX, tileZ, remote);
                } else if (memory.fillUnknownFrom(remote) > 0) {
                    memory.bumpRevision();
                }
                generation++;
                if (remoteDiag > 0) {
                    remoteDiag--;
                    LOGGER.info("[MapPack] remote tile {}/{} {},{} accepted ({} bytes)",
                            dimension, layer, tileX, tileZ, bytes.length);
                }
            });
        });
    }

    /** 只读压缩头：能否当作瓦片接受（服务端在上传入库前也用它挡坏数据）。 */
    public static boolean looksLikeTile(byte[] bytes) {
        if (bytes == null || bytes.length < 7) {
            return false;
        }
        try (DataInputStream in = new DataInputStream(
                new InflaterInputStream(new ByteArrayInputStream(bytes)))) {
            if (in.readInt() != MAGIC) {
                return false;
            }
            int version = in.readUnsignedByte();
            if (version < 1 || version > VERSION) {
                return false;
            }
            int size = in.readUnsignedShort();
            return size > 0 && size <= MapTile.SIZE;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    /** 世界身份就绪后扫一次目录，建立「哪些瓦片在磁盘上」的正索引。 */    private static void ensureIndex() {
        UUID worldId = example.contentpack.mapv2.client.MapWorld.id();
        if (worldId == null) {
            if (indexedWorld != null || indexingWorld != null) {
                resetIndex();
            }
            return;
        }
        if (indexReady && worldId.equals(indexedWorld)) {
            return;
        }
        if (worldId.equals(indexingWorld)) {
            return;
        }
        // 同一连接内切到别的世界：旧世界的待办与在途请求不能落到新目录
        if ((indexedWorld != null && !indexedWorld.equals(worldId))
                || (indexingWorld != null && !indexingWorld.equals(worldId))) {
            DIRTY.clear();
            LOADING.clear();
        }
        EXISTING.clear();
        indexReady = false;
        indexingWorld = worldId;
        long epoch = ++indexEpoch;
        IO.execute(() -> {
            Set<TileKey> found = scanDisk(worldId);
            Minecraft.getInstance().execute(() -> {
                if (epoch != indexEpoch || !worldId.equals(example.contentpack.mapv2.client.MapWorld.id())) {
                    return;
                }
                EXISTING.addAll(found);
                indexedWorld = worldId;
                indexingWorld = null;
                indexReady = true;
                LOGGER.info("[MapPack] tile index: {} file(s) in {}", found.size(), worldId);
            });
        });
    }

    private static void resetIndex() {
        indexEpoch++;
        EXISTING.clear();
        indexReady = false;
        indexedWorld = null;
        indexingWorld = null;
    }

    /** IO 线程：扫 {@code worlds/<uuid>/<ns>/<path>/<layer>/r.<x>.<z>.mpt}。 */
    private static Set<TileKey> scanDisk(UUID worldId) {
        Set<TileKey> found = new HashSet<>();
        Path root = worldDir(worldId);
        if (!Files.isDirectory(root)) {
            return found;
        }
        try (DirectoryStream<Path> namespaces = Files.newDirectoryStream(root)) {
            for (Path namespaceDir : namespaces) {
                if (!Files.isDirectory(namespaceDir)) {
                    continue;
                }
                String namespace = namespaceDir.getFileName().toString();
                try (DirectoryStream<Path> paths = Files.newDirectoryStream(namespaceDir)) {
                    for (Path dimensionDir : paths) {
                        if (!Files.isDirectory(dimensionDir)) {
                            continue;
                        }
                        ResourceLocation dimension = ResourceLocation.tryBuild(namespace,
                                dimensionDir.getFileName().toString());
                        if (dimension == null) {
                            continue;
                        }
                        scanDimension(dimension, dimensionDir, found);
                    }
                }
            }
        } catch (IOException ex) {
            LOGGER.warn("[MapPack] index scan failed: {}", ex.getMessage());
        }
        return found;
    }

    private static void scanDimension(ResourceLocation dimension, Path dimensionDir,
                                      Set<TileKey> found) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dimensionDir)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) {
                    scanLayer(dimension, entry.getFileName().toString(), entry, found);
                    continue;
                }
                // 首版把 top 瓦片直接放在维度目录下
                String name = entry.getFileName().toString();
                if (!name.endsWith(".mpt")) {
                    continue;
                }
                long packed = parseTileFileName(name);
                if (packed != Long.MIN_VALUE) {
                    addIndexKey(found, dimension, MapLayer.TOP,
                            (int) (packed >> 32), (int) packed, entry);
                }
            }
        } catch (IOException ex) {
            LOGGER.warn("[MapPack] index scan {} failed: {}", dimensionDir, ex.getMessage());
        }
    }

    private static void scanLayer(ResourceLocation dimension, String layer, Path layerDir,
                                  Set<TileKey> found) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(layerDir, "*.mpt")) {
            for (Path file : files) {
                long packed = parseTileFileName(file.getFileName().toString());
                if (packed != Long.MIN_VALUE) {
                    addIndexKey(found, dimension, layer,
                            (int) (packed >> 32), (int) packed, file);
                }
            }
        } catch (IOException ex) {
            LOGGER.warn("[MapPack] index scan {} failed: {}", layerDir, ex.getMessage());
        }
    }

    /**
     * 索引一个文件：512 文件记自己的坐标；旧 128 文件记到它所属的 512 分区名下，
     * 读取时再按象限拼回去。读不出尺寸的文件（损坏/未知版本）不进索引。
     */
    private static void addIndexKey(Set<TileKey> found, ResourceLocation dimension, String layer,
                                    int x, int z, Path file) {
        int size = peekSize(file);
        if (size == MapTile.SIZE) {
            found.add(new TileKey(dimension, layer, x, z));
        } else if (size == LEGACY_SIZE) {
            found.add(new TileKey(dimension, layer, legacyTargetTile(x), legacyTargetTile(z)));
        }
    }

    /** 旧 128 坐标 → 所属 512 分区坐标（负数也向下取整）。 */
    static int legacyTargetTile(int legacyCoord) {
        return Math.floorDiv(legacyCoord, LEGACY_PER_SIDE);
    }

    /** 旧文件在 512 分区内的像素偏移。 */
    static int legacyOffset(int legacyCoord) {
        return Math.floorMod(legacyCoord, LEGACY_PER_SIDE) * LEGACY_SIZE;
    }

    /** 只读文件头，拿分区边长；读不出返回 -1。 */
    private static int peekSize(Path path) {
        try (InputStream file = new BufferedInputStream(Files.newInputStream(path));
             DataInputStream in = new DataInputStream(new InflaterInputStream(file))) {
            if (in.readInt() != MAGIC) {
                return -1;
            }
            int version = in.readUnsignedByte();
            if (version < 1 || version > VERSION) {
                return -1;
            }
            return in.readUnsignedShort();
        } catch (IOException | RuntimeException ex) {
            return -1;
        }
    }

    static String tileFileName(int tileX, int tileZ) {
        return "r." + tileX + "." + tileZ + ".mpt";
    }

    /**
     * 解析 {@code r.<x>.<z>.mpt}，返回 {@link MapStore} 同款打包键；
     * 非法文件名返回 {@link Long#MIN_VALUE}（瓦片坐标不可能到这个值）。
     */
    static long parseTileFileName(String name) {
        if (!name.startsWith("r.") || !name.endsWith(".mpt")) {
            return Long.MIN_VALUE;
        }
        String body = name.substring(2, name.length() - 4);
        int dot = body.indexOf('.');
        if (dot <= 0 || dot == body.length() - 1) {
            return Long.MIN_VALUE;
        }
        try {
            int x = Integer.parseInt(body.substring(0, dot));
            int z = Integer.parseInt(body.substring(dot + 1));
            return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        } catch (NumberFormatException ex) {
            return Long.MIN_VALUE;
        }
    }

    private static void flush(int limit) {
        java.util.UUID worldId = example.contentpack.mapv2.client.MapWorld.id();
        if (worldId == null) {
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
            // 主线程只做快照；压缩和写盘在 IO 线程。世界 id 一并带走：
            // 退世界时 MapWorld 立刻清空，异步任务不能再去问「当前世界」
            SaveJob job = new SaveJob(key, worldId, MapTile.fromData(
                    tile.copyArgb(), tile.copyHeight(), tile.copyFluid(), tile.copyKnown(),
                    tile.copyBlockIndex(), tile.copyBlockPalette(),
                    tile.copyStackIndex(), tile.copyStackPalette(),
                    tile.copyBiomeIndex(), tile.copyBiomePalette()));
            IO.execute(() -> write(job));
            submitted++;
        }
    }

    /**
     * 写盘前把磁盘上已有的像素补进快照（只补快照里未知的像素）。
     *
     * <p>这是「写入永不减少信息」的兜底：内存瓦片可能是本次刚建的空瓦片，
     * 直接写会把这块分区以前的采集抹掉。
     */
    private static void write(SaveJob job) {
        if (!rescueLegacyAtTarget(job.worldId(), job.key())) {
            return; // 旧瓦片还没救出来，这次不写，别把它盖掉
        }
        MapTile tile = job.tile();
        // 连同旧 128 象限一起读，否则写回时会把迁移前的数据盖掉
        MapTile disk = readDiskTile(job.worldId(), job.key());
        if (disk != null) {
            tile.fillUnknownFrom(disk);
        }
        Path path = path(job.worldId(), job.key());
        byte[] bytes;
        try {
            bytes = serialize(path, tile);
        } catch (IOException ex) {
            LOGGER.warn("[MapPack] save {} failed: {}", path, ex.getMessage());
            return;
        }
        EXISTING.add(job.key());
        retireLegacy(job.worldId(), job.key());
        // 上传排回主线程，并确认还是同一个世界（异步落盘期间可能已退/换服）
        TileKey key = job.key();
        java.util.UUID worldId = job.worldId();
        Minecraft.getInstance().execute(() -> {
            if (worldId.equals(example.contentpack.mapv2.client.MapWorld.id())) {
                MapTileSync.offerUpload(key.dimension(), key.layer(), key.x(), key.z(), bytes);
            }
        });
    }

    /**
     * 目标文件名可能正好是一块**别的分区**的旧 128 瓦片（旧 {@code r.10.10} 属于分区 2,2，
     * 而新分区 10,10 也叫这个名字）。直接写会把它抹掉，所以先折进它真正所属的分区。
     *
     * @return {@code false} = 救回失败，调用方这次不能写
     */
    private static boolean rescueLegacyAtTarget(UUID worldId, TileKey key) {
        Path path = path(worldId, key);
        if (!Files.isRegularFile(path) || peekSize(path) != LEGACY_SIZE) {
            return true;
        }
        int ownerX = legacyTargetTile(key.x());
        int ownerZ = legacyTargetTile(key.z());
        if (ownerX == key.x() && ownerZ == key.z()) {
            return true; // 就是本分区的旧瓦片，已在下面的 pre-merge 里折进来
        }
        TileKey owner = new TileKey(key.dimension(), key.layer(), ownerX, ownerZ);
        // 所属分区自己的文件名也可能撞上更上一层的旧瓦片：链式救回，终点是 0/-1（owner == key）
        if (!rescueLegacyAtTarget(worldId, owner)) {
            return false;
        }
        MapTile disk = readDiskTile(worldId, owner);
        if (disk == null) {
            return true; // 读不出（损坏/IO），写了也是拿垃圾盖，让文件保持原样
        }
        try {
            serialize(path(worldId, owner), disk);
        } catch (IOException ex) {
            LOGGER.warn("[MapPack] rescue {}/{} {},{} failed: {}", owner.dimension(), owner.layer(),
                    owner.x(), owner.z(), ex.getMessage());
            return false;
        }
        EXISTING.add(owner);
        retireLegacy(worldId, owner);
        LOGGER.info("[MapPack] rescued legacy tile {}/{} {},{} into {}/{} {},{}",
                key.dimension(), key.layer(), key.x(), key.z(),
                owner.dimension(), owner.layer(), owner.x(), owner.z());
        return true;
    }

    /** 落盘并返回压缩后的字节：同一份字节既写本地，也用于上传共享。 */
    private static byte[] serialize(Path path, MapTile tile) throws IOException {
        byte[] bytes = encode(tile);
        Path parent = path.getParent();
        Files.createDirectories(parent);
        // 直接写目标文件时被强退/崩溃会留下半截 zlib（读端只能一直报 truncated）；
        // 先写同目录临时文件再原子替换，目标要么是旧文件要么是完整新文件。
        Path temp = Files.createTempFile(parent, "tile-", ".tmp");
        try {
            try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(temp))) {
                file.write(bytes);
            }
            moveReplace(temp, path);
        } finally {
            Files.deleteIfExists(temp);
        }
        return bytes;
    }

    /** 自检用：当前格式编码再解码，验证每个通道都能原样回来。 */
    static MapTile roundtripForTest(MapTile tile) throws IOException {
        RawTile raw = decodeTile(encode(tile));
        return MapTile.fromData(raw.argb(), raw.height(), raw.fluid(), raw.known(),
                raw.blockIndex(), raw.blockPalette(),
                raw.stackIndex(), raw.stackPalette(),
                raw.biomeIndex(), raw.biomePalette());
    }

    /** 压缩成 MPT1 字节（版本 5 格式，见类注释）。 */
    private static byte[] encode(MapTile tile) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(3 * 1024 * 1024);
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(buffer))) {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeShort(MapTile.SIZE);
            out.write(packKnown(tile.copyKnown()));
            for (int color : tile.copyArgb()) {
                out.writeInt(color);
            }
            for (short y : tile.copyHeight()) {
                out.writeShort(y);
            }
            out.write(tile.copyFluid());
            java.util.List<String> palette = tile.copyBlockPalette();
            out.writeShort(palette.size());
            for (String blockId : palette) {
                out.writeUTF(blockId);
            }
            for (short index : tile.copyBlockIndex()) {
                out.writeShort(index);
            }
            java.util.List<String> biomes = tile.copyBiomePalette();
            out.writeShort(biomes.size());
            for (String biomeId : biomes) {
                out.writeUTF(biomeId);
            }
            for (short index : tile.copyBiomeIndex()) {
                out.writeShort(index);
            }
            java.util.List<java.util.List<MapStackLayer>> stacks = tile.copyStackPalette();
            out.writeShort(stacks.size());
            for (java.util.List<MapStackLayer> layers : stacks) {
                out.writeByte(layers.size());
                for (MapStackLayer layer : layers) {
                    out.writeByte(layer.u0());
                    out.writeByte(layer.v0());
                    out.writeByte(layer.u1());
                    out.writeByte(layer.v1());
                    out.writeByte(layer.x0());
                    out.writeByte(layer.z0());
                    out.writeByte(layer.x1());
                    out.writeByte(layer.z1());
                    out.writeUTF(layer.sprite());
                    out.writeUTF(layer.blockId());
                    out.writeShort(layer.y());
                    out.writeInt(layer.argb());
                    out.writeByte(layer.alpha());
                    out.writeByte(layer.count());
                    out.writeByte(layer.rotation());
                }
            }
            short[] stackIndex = tile.copyStackIndex();
            out.writeByte(stackIndex == null ? 0 : 1);
            if (stackIndex != null) {
                for (short index : stackIndex) {
                    out.writeShort(index);
                }
            }
        }
        return buffer.toByteArray();
    }

    private static void load(TileKey key) {
        UUID worldId = example.contentpack.mapv2.client.MapWorld.id();
        if (worldId == null) {
            Minecraft.getInstance().execute(() -> LOADING.remove(key));
            return;
        }
        boolean[] corrupt = new boolean[1];
        MapTile disk = readDiskTile(worldId, key, corrupt);
        if (disk == null) {
            if (corrupt[0]) {
                // 解不出来的文件永远不会自己变好；留在索引里会让渲染每帧重排一次读。
                // 挪到旁边留证据，并从索引摘掉：之后由服务端分发或重新采集补齐。
                Path path = path(worldId, key);
                if (Files.isRegularFile(path)) {
                    quarantine(path);
                }
                EXISTING.remove(key);
            }
            Minecraft.getInstance().execute(() -> LOADING.remove(key));
            return;
        }
        Minecraft.getInstance().execute(() -> {
            LOADING.remove(key);
            if (!worldId.equals(example.contentpack.mapv2.client.MapWorld.id())) {
                return; // 读盘期间换了世界：旧瓦片不能进新世界的内存
            }
            MapTile memory = MapStore.get(key.dimension(), key.layer(), key.x(), key.z());
            if (memory == null) {
                MapStore.putIfAbsent(key.dimension(), key.layer(), key.x(), key.z(), disk);
            } else {
                // 采集先建了空瓦片：旧数据补洞，内存像素优先
                int filled = memory.fillUnknownFrom(disk);
                if (filled > 0) {
                    memory.bumpRevision();
                    LOGGER.info("[MapPack] merge {}/{} {}: {} pixel(s) from disk",
                            key.dimension(), key.layer(), key.x() + "," + key.z(), filled);
                }
            }
            generation++;
        });
    }

    /** 读一个瓦片文件；格式不对直接抛（调用方决定警告还是当作缺失）。 */
    private static RawTile readTile(Path path) throws IOException {
        try (InputStream file = new BufferedInputStream(Files.newInputStream(path));
             DataInputStream in = new DataInputStream(new InflaterInputStream(file))) {
            return decodeTile(in);
        }
    }

    /** 解一段网络收到的压缩字节；与读盘共用同一份格式校验。 */
    private static RawTile decodeTile(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new InflaterInputStream(new ByteArrayInputStream(bytes)))) {
            return decodeTile(in);
        }
    }

    private static RawTile decodeTile(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) {
            throw new IOException("bad magic");
        }
        int version = in.readUnsignedByte();
        if (version < 1 || version > VERSION) {
            throw new IOException("unsupported version " + version);
        }
        int size = in.readUnsignedShort();
        if (size <= 0 || size > MapTile.SIZE) {
            throw new IOException("bad tile size " + size);
        }
        int pixels = size * size;
        boolean[] known = unpackKnown(in.readNBytes((pixels + 7) / 8), pixels);
        int[] argb = new int[pixels];
        for (int i = 0; i < pixels; i++) {
            argb[i] = in.readInt();
        }
        short[] height = new short[pixels];
        for (int i = 0; i < pixels; i++) {
            height[i] = in.readShort();
        }
        byte[] fluid = new byte[pixels];
        in.readFully(fluid);

        java.util.List<String> blockPalette = new java.util.ArrayList<>();
        short[] blockIndex = new short[pixels];
        if (version >= 2) {
            int paletteSize = in.readUnsignedShort();
            for (int i = 0; i < paletteSize; i++) {
                blockPalette.add(in.readUTF());
            }
            for (int i = 0; i < pixels; i++) {
                blockIndex[i] = in.readShort();
            }
        }
        java.util.List<String> biomePalette = new java.util.ArrayList<>();
        short[] biomeIndex = new short[pixels];
        java.util.Arrays.fill(biomeIndex, (short) -1);
        if (version >= 3) {
            int paletteSize = in.readUnsignedShort();
            for (int i = 0; i < paletteSize; i++) {
                biomePalette.add(in.readUTF());
            }
            for (int i = 0; i < pixels; i++) {
                biomeIndex[i] = in.readShort();
            }
        }
        java.util.List<java.util.List<MapStackLayer>> stackPalette = new java.util.ArrayList<>();
        short[] stackIndex = null;
        if (version >= 5) {
            int paletteSize = in.readUnsignedShort();
            for (int i = 0; i < paletteSize; i++) {
                int layerCount = in.readUnsignedByte();
                if (layerCount > MAX_STACK_LAYERS) {
                    throw new IOException("bad stack layer count " + layerCount);
                }
                java.util.List<MapStackLayer> layers = new java.util.ArrayList<>(layerCount);
                for (int j = 0; j < layerCount; j++) {
                    int u0 = in.readUnsignedByte();
                    int v0 = in.readUnsignedByte();
                    int u1 = in.readUnsignedByte();
                    int v1 = in.readUnsignedByte();
                    int x0 = in.readUnsignedByte();
                    int z0 = in.readUnsignedByte();
                    int x1 = in.readUnsignedByte();
                    int z1 = in.readUnsignedByte();
                    if (version < 7) {
                        // v6 的贴图子矩形是十六等分，折到新的六十四等分
                        u0 *= 4;
                        v0 *= 4;
                        u1 *= 4;
                        v1 *= 4;
                    }
                    if (x1 > 16 || z1 > 16 || x1 <= x0 || z1 <= z0) {
                        throw new IOException("bad stack rect");
                    }
                    String sprite = in.readUTF();
                    String blockId = in.readUTF();
                    // 平色层（v8 起空 sprite）没有贴图，UV 全 0；其余层必须是非空子矩形
                    if (!sprite.isEmpty()
                            && (u1 > MapStackLayer.UV_UNITS || v1 > MapStackLayer.UV_UNITS
                            || u1 <= u0 || v1 <= v0)) {
                        throw new IOException("bad stack uv");
                    }
                    short y = in.readShort();
                    int argbValue = in.readInt();
                    int alpha = in.readUnsignedByte();
                    int count = in.readUnsignedByte();
                    if (count < 1) {
                        throw new IOException("bad stack layer count " + count);
                    }
                    // v5 没有旋转字节：老瓦片按 0 读，下一轮重采就补上
                    int rotation = version >= 6 ? in.readUnsignedByte() : 0;
                    if (version < 8) {
                        // v6/v7 存的是世界矩形 + 90° 档位：折回本地矩形，档位细到 22.5°
                        int[] local = MapStackLayer.unrotatedRect(rotation, x0, z0, x1, z1);
                        x0 = local[0];
                        z0 = local[1];
                        x1 = local[2];
                        z1 = local[3];
                        rotation *= 4;
                    }
                    if (rotation > 15) {
                        throw new IOException("bad stack rotation " + rotation);
                    }
                    layers.add(new MapStackLayer(sprite, u0, v0, u1, v1, x0, z0, x1, z1,
                            blockId, y, argbValue, alpha, count, rotation));
                }
                stackPalette.add(java.util.List.copyOf(layers));
            }
            if (in.readUnsignedByte() != 0) {
                stackIndex = new short[pixels];
                for (int i = 0; i < pixels; i++) {
                    stackIndex[i] = in.readShort();
                }
                for (short index : stackIndex) {
                    if (index < -1 || index >= stackPalette.size()) {
                        throw new IOException("bad stack index " + index);
                    }
                }
            }
        }
        return new RawTile(size, argb, height, fluid, known, blockIndex, blockPalette,
                stackIndex, stackPalette, biomeIndex, biomePalette);
    }

    private static void moveReplace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 坏瓦片挪到同目录 {@code <名字>.corrupt} 留证据；已有旧证据直接覆盖。 */
    private static void quarantine(Path path) {
        Path target = path.resolveSibling(path.getFileName() + ".corrupt");
        try {
            moveReplace(path, target);
            LOGGER.warn("[MapPack] quarantined corrupt tile {} -> {}", path, target);
        } catch (IOException ex) {
            LOGGER.warn("[MapPack] quarantine {} failed: {}", path, ex.getMessage());
        }
    }

    /**
     * 读磁盘上这块 512 分区的全部数据：新 512 文件 + 四象限的旧 128 文件（已知像素互补）。
     *
     * @return 磁盘上什么都没有时返回 {@code null}
     */
    private static MapTile readDiskTile(UUID worldId, TileKey key) {
        return readDiskTile(worldId, key, null);
    }

    /**
     * @param corrupt 非空时，任一参与读取的文件解不出来就置 {@code true}
     *                （调用方据此判断「没数据」是缺文件还是文件坏了）
     */
    private static MapTile readDiskTile(UUID worldId, TileKey key, boolean[] corrupt) {
        MapTile tile = null;
        Path path = path(worldId, key);
        if (Files.isRegularFile(path)) {
            try {
                RawTile raw = readTile(path);
                if (raw.size() == MapTile.SIZE) {
                    tile = MapTile.fromData(raw.argb(), raw.height(), raw.fluid(), raw.known(),
                            raw.blockIndex(), raw.blockPalette(),
                            raw.stackIndex(), raw.stackPalette(),
                            raw.biomeIndex(), raw.biomePalette());
                }
            } catch (IOException | RuntimeException ex) {
                if (corrupt != null) {
                    corrupt[0] = true;
                }
                LOGGER.warn("[MapPack] read {} failed: {}", path, ex.getMessage());
            }
        }
        Path dir = dimensionDir(worldId, key);
        for (int quadrantX = 0; quadrantX < LEGACY_PER_SIDE; quadrantX++) {
            for (int quadrantZ = 0; quadrantZ < LEGACY_PER_SIDE; quadrantZ++) {
                int legacyX = key.x() * LEGACY_PER_SIDE + quadrantX;
                int legacyZ = key.z() * LEGACY_PER_SIDE + quadrantZ;
                Path legacy = legacyFile(dir, key.layer(), legacyX, legacyZ);
                if (legacy == null) {
                    continue;
                }
                RawTile raw;
                try {
                    raw = readTile(legacy);
                } catch (IOException | RuntimeException ex) {
                    // 单块旧文件读不动不影响其它象限
                    if (corrupt != null) {
                        corrupt[0] = true;
                    }
                    LOGGER.warn("[MapPack] read {} failed: {}", legacy, ex.getMessage());
                    continue;
                }
                if (raw.size() != LEGACY_SIZE) {
                    continue;
                }
                if (tile == null) {
                    tile = new MapTile();
                }
                foldLegacy(tile, raw, legacyOffset(legacyX), legacyOffset(legacyZ));
            }
        }
        return tile;
    }

    /** 旧 128 文件的已知像素搬进 512 瓦片；目标已有像素的不动。 */
    private static void foldLegacy(MapTile target, RawTile raw, int offsetX, int offsetZ) {
        for (int lz = 0; lz < LEGACY_SIZE; lz++) {
            for (int lx = 0; lx < LEGACY_SIZE; lx++) {
                int index = lz * LEGACY_SIZE + lx;
                int targetX = offsetX + lx;
                int targetZ = offsetZ + lz;
                if (!raw.known()[index] || target.isKnown(targetX, targetZ)) {
                    continue;
                }
                int palette = raw.blockIndex()[index];
                String blockId = palette >= 0 && palette < raw.blockPalette().size()
                        ? raw.blockPalette().get(palette) : null;
                int biomePalette = raw.biomeIndex()[index];
                String biomeId = biomePalette >= 0 && biomePalette < raw.biomePalette().size()
                        ? raw.biomePalette().get(biomePalette) : null;
                short[] stackIndex = raw.stackIndex();
                int stackPalette = stackIndex == null ? -1 : stackIndex[index];
                java.util.List<MapStackLayer> stack = stackPalette >= 0
                        && stackPalette < raw.stackPalette().size()
                        ? raw.stackPalette().get(stackPalette) : java.util.List.of();
                target.set(targetX, targetZ, raw.argb()[index], raw.height()[index],
                        raw.fluid()[index], blockId, stack, biomeId);
            }
        }
    }

    /** 旧 128 瓦片可能在层目录，也可能（首版 top）直接在维度目录。 */
    private static Path legacyFile(Path dimensionDir, String layer, int legacyX, int legacyZ) {
        String name = tileFileName(legacyX, legacyZ);
        Path inLayer = dimensionDir.resolve(layer).resolve(name);
        if (Files.isRegularFile(inLayer)) {
            return inLayer;
        }
        if (MapLayer.TOP.equals(layer)) {
            Path inDimension = dimensionDir.resolve(name);
            if (Files.isRegularFile(inDimension)) {
                return inDimension;
            }
        }
        return null;
    }

    /**
     * 512 文件写成功后，把该分区的旧 128 文件改名成 {@code .mpt.old}：数据已经并进新文件，
     * 留着只会每次读盘再扫一遍。改名不删，可回退。
     */
    private static void retireLegacy(UUID worldId, TileKey key) {
        Path dir = dimensionDir(worldId, key);
        int retired = 0;
        for (int quadrantX = 0; quadrantX < LEGACY_PER_SIDE; quadrantX++) {
            for (int quadrantZ = 0; quadrantZ < LEGACY_PER_SIDE; quadrantZ++) {
                int legacyX = key.x() * LEGACY_PER_SIDE + quadrantX;
                int legacyZ = key.z() * LEGACY_PER_SIDE + quadrantZ;
                Path legacy = legacyFile(dir, key.layer(), legacyX, legacyZ);
                if (legacy == null) {
                    continue;
                }
                // 坐标 (−1/−0 那几块) 上旧文件名与新 512 文件名相同：只退役真的是 128 的文件
                if (peekSize(legacy) != LEGACY_SIZE) {
                    continue;
                }
                try {
                    Files.move(legacy, legacy.resolveSibling(legacy.getFileName() + ".old"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    retired++;
                } catch (IOException ex) {
                    LOGGER.warn("[MapPack] retire {} failed: {}", legacy, ex.getMessage());
                }
            }
        }
        if (retired > 0) {
            LOGGER.info("[MapPack] migrated {} legacy tile(s) into {}/{} {},{}",
                    retired, key.dimension(), key.layer(), key.x(), key.z());
        }
    }

    private static Path path(UUID worldId, TileKey key) {
        return dimensionDir(worldId, key)
                .resolve(key.layer())
                .resolve(tileFileName(key.x(), key.z()));
    }

    private static Path dimensionDir(UUID worldId, TileKey key) {
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
                        .filter(path -> {
                            String name = path.getFileName().toString();
                            // 地图瓦片 + 群系预测区域：继承时两份一起走
                            return name.endsWith(".mpt") || name.endsWith(".mpb");
                        })
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

    private static boolean[] unpackKnown(byte[] bits, int pixels) {
        boolean[] known = new boolean[pixels];
        for (int i = 0; i < pixels; i++) {
            known[i] = (bits[i >> 3] & (1 << (i & 7))) != 0;
        }
        return known;
    }

    /** 读出来的原始像素（可能是 512 也可能是旧的 128），交给调用方决定怎么合。 */
    private record RawTile(int size, int[] argb, short[] height, byte[] fluid, boolean[] known,
                           short[] blockIndex, java.util.List<String> blockPalette,
                           short[] stackIndex, java.util.List<java.util.List<MapStackLayer>> stackPalette,
                           short[] biomeIndex, java.util.List<String> biomePalette) {
    }

    private record TileKey(ResourceLocation dimension, String layer, int x, int z) {
    }

    private record SaveJob(TileKey key, UUID worldId, MapTile tile) {
    }
}
