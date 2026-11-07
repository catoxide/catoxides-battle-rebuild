package example.contentpack.mapv2.network;

import com.mojang.logging.LogUtils;
import example.contentpack.mapv2.reveal.RevealChunkSet;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 探明范围的落盘存储：一个维度一个 {@code .reveal} 文件（GZIP）。
 *
 * <p>位置 {@code <world>/map_pack_revealed/}；按维度懒加载，服务端每 60 秒与停服时
 * 把脏数据写回。格式只存「格坐标 + 掩码」，与客户端协议同构。
 */
public final class RevealStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAGIC = 0x4D505256; // MPRV
    private static final int VERSION = 1;
    private static final int MAX_CELLS = 1_000_000;

    private final Path root;
    private final Map<ResourceLocation, RevealChunkSet> sets = new HashMap<>();
    private boolean dirty;

    public RevealStore(Path root) {
        this.root = root;
    }

    public RevealChunkSet get(ResourceLocation dimension) {
        RevealChunkSet existing = sets.get(dimension);
        if (existing != null) {
            return existing;
        }
        RevealChunkSet loaded = load(dimension);
        sets.put(dimension, loaded);
        return loaded;
    }

    public void markDirty() {
        dirty = true;
    }

    public void save() {
        if (!dirty) {
            return;
        }
        dirty = false;
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            LOGGER.warn("[MapPack] reveal root {} unavailable: {}", root, ex.getMessage());
            return;
        }
        for (Map.Entry<ResourceLocation, RevealChunkSet> entry : sets.entrySet()) {
            write(entry.getKey(), entry.getValue());
        }
    }

    private RevealChunkSet load(ResourceLocation dimension) {
        Path path = file(dimension);
        RevealChunkSet set = new RevealChunkSet();
        if (!Files.isRegularFile(path)) {
            return set;
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path))))) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != MAGIC || version != VERSION) {
                LOGGER.warn("[MapPack] reveal file {} has unknown format", path.getFileName());
                return set;
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_CELLS) {
                LOGGER.warn("[MapPack] reveal file {} has bad cell count {}", path.getFileName(), count);
                return set;
            }
            for (int i = 0; i < count; i++) {
                set.add(in.readLong(), in.readLong());
            }
        } catch (IOException ex) {
            LOGGER.warn("[MapPack] reveal file {} unreadable: {}", path.getFileName(), ex.getMessage());
            return new RevealChunkSet();
        }
        return set;
    }

    private void write(ResourceLocation dimension, RevealChunkSet set) {
        Path path = file(dimension);
        Path temp = null;
        try {
            temp = Files.createTempFile(root, "reveal-", ".tmp");
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temp))))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(set.size());
                for (Long2LongMap.Entry entry : set.cells().long2LongEntrySet()) {
                    out.writeLong(entry.getLongKey());
                    out.writeLong(entry.getLongValue());
                }
            }
            move(temp, path);
        } catch (IOException ex) {
            LOGGER.warn("[MapPack] failed to save reveal {}: {}", dimension, ex.getMessage());
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private Path file(ResourceLocation dimension) {
        String raw = dimension.getNamespace() + "__" + dimension.getPath();
        StringBuilder safe = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean keep = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            safe.append(keep ? c : '_');
        }
        return root.resolve(safe + "-" + Integer.toHexString(dimension.toString().hashCode())
                + ".reveal");
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}