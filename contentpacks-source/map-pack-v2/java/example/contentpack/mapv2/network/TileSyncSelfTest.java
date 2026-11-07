package example.contentpack.mapv2.network;

import net.minecraft.resources.ResourceLocation;

/** 瓦片 key 的编解码自检：维度/层/坐标必须原样往返，坏 key 必须拒绝。 */
public final class TileSyncSelfTest {

    private TileSyncSelfTest() {
    }

    public static void run() {
        ResourceLocation nether = ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether");
        String key = MapTileSync.key(nether, "y32", -3, 7);
        MapTileSync.TileRef ref = MapTileSync.parse(key);
        require(ref != null, "key must parse: " + key);
        require(ref.dimension().equals(nether), "dimension must round-trip");
        require("y32".equals(ref.layer()), "layer must round-trip");
        require(ref.x() == -3 && ref.z() == 7, "coordinates must round-trip");

        require(MapTileSync.parse(null) == null, "null key must be rejected");
        require(MapTileSync.parse("garbage") == null, "key without separators must be rejected");
        require(MapTileSync.parse("minecraft:overworld|top|1") == null,
                "key without z must be rejected");
        require(MapTileSync.parse("minecraft:overworld|top|x|1") == null,
                "non-numeric x must be rejected");
        require(MapTileSync.parse("|top|1|2") == null, "empty dimension must be rejected");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
