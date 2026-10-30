package example.contentpack.mapv2.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import example.contentpack.mapv2.MapStorage;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 每个世界目录下的 {@code world.json}：身份 + 候选匹配用的地形指纹。
 *
 * <p>正常情况用 {@code worldId} 直接命中；UUID 对不上（复制存档/回档/换启动方式）时，
 * 用 {@code seed + generator} 找地形可能一致的已有地图，由玩家决定是否继承。
 */
public record MapWorldMeta(UUID worldId, long seed, String generator, String name,
                           long createdAt, boolean inheritAsked, List<UUID> inheritedFrom) {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE = "world.json";

    public static Path metaPath(UUID worldId) {
        return MapStorage.worldDir(worldId).resolve(FILE);
    }

    /** 读不到/损坏时按新世界处理。 */
    public static MapWorldMeta loadOrCreate(UUID worldId, long seed, String generator, String name) {
        Path path = metaPath(worldId);
        if (Files.isRegularFile(path)) {
            try {
                JsonObject root = JsonParser.parseString(
                        Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
                List<UUID> inherited = new ArrayList<>();
                if (root.has("inheritedFrom")) {
                    for (JsonElement element : root.getAsJsonArray("inheritedFrom")) {
                        try {
                            inherited.add(UUID.fromString(element.getAsString()));
                        } catch (IllegalArgumentException ignored) {
                            // 坏条目跳过
                        }
                    }
                }
                return new MapWorldMeta(
                        UUID.fromString(root.get("worldId").getAsString()),
                        root.has("seed") ? root.get("seed").getAsLong() : seed,
                        root.has("generator") ? root.get("generator").getAsString() : generator,
                        root.has("name") ? root.get("name").getAsString() : name,
                        root.has("createdAt") ? root.get("createdAt").getAsLong() : System.currentTimeMillis(),
                        root.has("inheritAsked") && root.get("inheritAsked").getAsBoolean(),
                        List.copyOf(inherited));
            } catch (Exception ex) {
                LOGGER.warn("[MapPack] failed to read {}: {}", path, ex.getMessage());
            }
        }
        return new MapWorldMeta(worldId, seed, generator, name,
                System.currentTimeMillis(), false, List.of());
    }

    public void save() {
        Path path = metaPath(worldId);
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("worldId", worldId.toString());
            root.addProperty("seed", seed);
            root.addProperty("generator", generator);
            root.addProperty("name", name);
            root.addProperty("createdAt", createdAt);
            root.addProperty("inheritAsked", inheritAsked);
            JsonArray inherited = new JsonArray();
            for (UUID id : inheritedFrom) {
                inherited.add(id.toString());
            }
            root.add("inheritedFrom", inherited);
            Files.writeString(path, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOGGER.warn("[MapPack] failed to write {}: {}", path, ex.getMessage());
        }
    }

    public MapWorldMeta withInherited(UUID source) {
        List<UUID> inherited = new ArrayList<>(inheritedFrom);
        if (!inherited.contains(source)) {
            inherited.add(source);
        }
        return new MapWorldMeta(worldId, seed, generator, name, createdAt, true, List.copyOf(inherited));
    }

    public MapWorldMeta withAsked() {
        return new MapWorldMeta(worldId, seed, generator, name, createdAt, true, inheritedFrom);
    }

    /** 扫描本地其他世界：seed + generator 相同才算候选（地形可能一致）。 */
    public static List<MapWorldMeta> findCandidates(UUID self, long seed, String generator) {
        List<MapWorldMeta> result = new ArrayList<>();
        Path worlds = MapStorage.worldsRoot();
        if (!Files.isDirectory(worlds)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(worlds)) {
            for (Path dir : stream) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                UUID id;
                try {
                    id = UUID.fromString(dir.getFileName().toString());
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (id.equals(self)) {
                    continue;
                }
                Path metaFile = dir.resolve(FILE);
                if (!Files.isRegularFile(metaFile)) {
                    continue;
                }
                try {
                    JsonObject root = JsonParser.parseString(
                            Files.readString(metaFile, StandardCharsets.UTF_8)).getAsJsonObject();
                    long otherSeed = root.get("seed").getAsLong();
                    String otherGenerator = root.get("generator").getAsString();
                    if (otherSeed != seed || !otherGenerator.equals(generator)) {
                        continue;
                    }
                    List<UUID> inherited = new ArrayList<>();
                    if (root.has("inheritedFrom")) {
                        for (JsonElement element : root.getAsJsonArray("inheritedFrom")) {
                            try {
                                inherited.add(UUID.fromString(element.getAsString()));
                            } catch (IllegalArgumentException ignored) {
                                // 跳过
                            }
                        }
                    }
                    result.add(new MapWorldMeta(id, otherSeed, otherGenerator,
                            root.has("name") ? root.get("name").getAsString() : id.toString(),
                            root.has("createdAt") ? root.get("createdAt").getAsLong() : 0L,
                            true, List.copyOf(inherited)));
                } catch (Exception ex) {
                    LOGGER.warn("[MapPack] skipped candidate {}: {}", metaFile, ex.getMessage());
                }
            }
        } catch (IOException ex) {
            LOGGER.warn("[MapPack] failed to scan worlds: {}", ex.getMessage());
        }
        return result;
    }
}
