package example.contentpack.mapv2.transfer;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 服务端磁盘块存储：一个主题一个目录，key 做 SHA-256 当文件名。
 *
 * <p>文件名不解析 key，所以 key 里出现路径分隔符也不会逃出目录
 * （目录名 topic 由 {@link #validTopic} 限定），这是唯一的路径注入面。
 *
 * <p>版本号 = 文件 mtime 毫秒值；写入时取 {@code max(now, 旧版本 + 1)}，
 * 同一毫秒连写两次也能单调递增。临时文件 + 原子替换保证读方不会看到半个块。
 */
public final class DiskBlobStore implements BlobTopic {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String EXTENSION = ".blob";

    private final String id;
    private final Path directory;

    public DiskBlobStore(String id, Path root) {
        if (!validTopic(id)) {
            throw new IllegalArgumentException("bad topic id: " + id);
        }
        this.id = id;
        this.directory = root.resolve(id);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Blob fetch(String key, long knownRevision) {
        Path path = path(key);
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            long revision = revision(path);
            if (revision <= knownRevision) {
                return new Blob(revision, null);
            }
            byte[] data = Files.readAllBytes(path);
            if (data.length == 0 || data.length > BlobChunker.MAX_BLOB_BYTES) {
                LOGGER.warn("[MapPack] blob {} {} has bad size {}", id, key, data.length);
                return null;
            }
            return new Blob(revision, data);
        } catch (IOException ex) {
            LOGGER.warn("[MapPack] read blob {} {} failed: {}", id, key, ex.getMessage());
            return null;
        }
    }

    @Override
    public long store(String key, byte[] data) {
        if (key == null || data == null || data.length == 0
                || data.length > BlobChunker.MAX_BLOB_BYTES) {
            return -1L;
        }
        Path path = path(key);
        if (path == null) {
            return -1L;
        }
        try {
            Files.createDirectories(directory);
            long revision = Math.max(System.currentTimeMillis(), revision(path) + 1L);
            Path temp = Files.createTempFile(directory, "incoming-", ".tmp");
            try {
                Files.write(temp, data);
                move(temp, path);
            } finally {
                Files.deleteIfExists(temp);
            }
            Files.setLastModifiedTime(path, FileTime.fromMillis(revision));
            return revision;
        } catch (IOException ex) {
            LOGGER.warn("[MapPack] write blob {} {} failed: {}", id, key, ex.getMessage());
            return -1L;
        }
    }

    public Path directory() {
        return directory;
    }

    /** 只允许小写标识符：目录名直接来自它。 */
    public static boolean validTopic(String id) {
        if (id == null || id.isEmpty() || id.length() > 32) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                    || c == '.' || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private Path path(String key) {
        if (key == null || key.isEmpty() || key.length() > 256) {
            return null;
        }
        return directory.resolve(hash(key) + EXTENSION);
    }

    private static long revision(Path path) {
        if (!Files.isRegularFile(path)) {
            return 0L;
        }
        try {
            return Math.max(1L, Files.getLastModifiedTime(path).toMillis());
        } catch (IOException ex) {
            return 0L;
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String hash(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(Character.forDigit((value >> 4) & 0xF, 16));
                builder.append(Character.forDigit(value & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
