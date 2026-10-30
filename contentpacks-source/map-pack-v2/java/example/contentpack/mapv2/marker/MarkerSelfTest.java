package example.contentpack.mapv2.marker;

import com.mojang.logging.LogUtils;
import example.contentpack.mapv2.network.MarkerAdoptPayload;
import example.contentpack.mapv2.network.MarkerCreatePayload;
import example.contentpack.mapv2.network.MarkerSyncPayload;
import example.contentpack.mapv2.network.MarkerUpdatePayload;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * 标记纯逻辑自检：启动时跑一次，结果写日志。
 *
 * <p>失败用 ERROR，`scripts/validate-game.ps1` 会把它当作 FAIL 抓出来。
 * 覆盖：类型生命周期、NBT 往返、两个 payload 编解码往返、过期边界、共享过滤。
 */
public final class MarkerSelfTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private MarkerSelfTest() {
    }

    public static void run() {
        int passed = 0;
        int failed = 0;
        if (run("type-timed", MarkerSelfTest::testTypeTimed)) {
            passed++;
        } else {
            failed++;
        }
        if (run("dto-nbt-roundtrip", MarkerSelfTest::testDtoRoundTrip)) {
            passed++;
        } else {
            failed++;
        }
        if (run("expired-boundaries", MarkerSelfTest::testExpired)) {
            passed++;
        } else {
            failed++;
        }
        if (run("create-payload-codec", MarkerSelfTest::testCreateCodec)) {
            passed++;
        } else {
            failed++;
        }
        if (run("sync-payload-codec", MarkerSelfTest::testSyncCodec)) {
            passed++;
        } else {
            failed++;
        }
        if (run("share-filter", MarkerSelfTest::testShareFilter)) {
            passed++;
        } else {
            failed++;
        }
        if (run("marker-data-presentation", MarkerSelfTest::testPresentation)) {
            passed++;
        } else {
            failed++;
        }
        if (run("marker-display-keys", MarkerSelfTest::testDisplayKeys)) {
            passed++;
        } else {
            failed++;
        }
        if (run("update-payload-codec", MarkerSelfTest::testUpdateCodec)) {
            passed++;
        } else {
            failed++;
        }
        if (run("adopt-payload-codec", MarkerSelfTest::testAdoptCodec)) {
            passed++;
        } else {
            failed++;
        }

        LOGGER.info("[MapMarkerTest] passed={} failed={}", passed, failed);
        if (failed > 0) {
            LOGGER.error("[MapMarkerTest] FAILURES={}", failed);
        }
    }

    private static boolean run(String name, Runnable test) {
        try {
            test.run();
            return true;
        } catch (Throwable error) {
            LOGGER.error("[MapMarkerTest] FAIL {}: {}", name, error.toString());
            return false;
        }
    }

    private static void testTypeTimed() {
        for (MarkerType type : MarkerType.values()) {
            boolean expected = switch (type) {
                case PING, ITEM, ENTITY, CONTAINER -> true;
                case STICKY, DEATH -> false;
            };
            require(type.timed() == expected, "timed mismatch: " + type);
        }
        require(MarkerType.byName("ITEM") == MarkerType.ITEM, "byName");
        require(MarkerType.byName("nope") == MarkerType.PING, "byName fallback");
        for (MarkerType type : MarkerType.values()) {
            boolean expectedWaypoint = switch (type) {
                case PING, STICKY -> true;
                case DEATH, ENTITY, ITEM, CONTAINER -> false;
            };
            require(type.waypoint() == expectedWaypoint, "waypoint mismatch: " + type);
            // 三角形只给临时地点标记；永久地点标记必须绑定真实图标
            require(type.temporaryWaypoint() == (type == MarkerType.PING),
                    "temporaryWaypoint mismatch: " + type);
            require(type.editable() == (type == MarkerType.STICKY),
                    "editable mismatch: " + type);
        }
    }

    private static void testDtoRoundTrip() {
        CompoundTag data = new CompoundTag();
        data.putString("itemId", "minecraft:diamond_sword");
        CompoundTag item = new CompoundTag();
        item.putString("id", "minecraft:diamond_sword");
        data.put("item", item);

        MarkerDto original = new MarkerDto(UUID.randomUUID(), UUID.randomUUID(),
                MarkerType.ITEM, ResourceLocation.parse("minecraft:diamond_sword"),
                ResourceLocation.parse("minecraft:overworld"),
                12.5, 64.0, -30.25, 42, 3, "item:mainhand", data,
                1_000L, 2_000L);
        MarkerDto restored = MarkerDto.load(original.save(), null);

        require(original.id().equals(restored.id()), "id");
        require(original.owner().equals(restored.owner()), "owner");
        require(original.type() == restored.type(), "type");
        require(original.icon().equals(restored.icon()), "icon");
        require(original.dimension().equals(restored.dimension()), "dimension");
        require(original.x() == restored.x(), "x");
        require(original.y() == restored.y(), "y");
        require(original.z() == restored.z(), "z");
        require(original.entityId() == restored.entityId(), "entityId");
        require(original.slot() == restored.slot(), "slot");
        require(original.targetKey().equals(restored.targetKey()), "targetKey");
        require(original.expiresAt() == restored.expiresAt(), "expiresAt");
        require("minecraft:diamond_sword".equals(
                restored.data().getString("itemId")), "data.itemId");
        require(restored.data().getCompound("item").getString("id")
                .equals("minecraft:diamond_sword"), "data.item");
    }

    private static void testExpired() {
        require(!MarkerRules.expired(1_000L, 0L), "permanent never expires");
        require(!MarkerRules.expired(1_000L, 1_000L), "equal is not expired");
        require(MarkerRules.expired(1_001L, 1_000L), "past expiry");
        require(!MarkerRules.expired(999L, 1_000L), "before expiry");
    }

    private static void testCreateCodec() {
        CompoundTag data = new CompoundTag();
        data.putString("itemId", "minecraft:apple");
        MarkerCreatePayload original = new MarkerCreatePayload(
                MarkerType.CONTAINER.name(), ResourceLocation.parse("minecraft:chest"),
                1.25, 64.0, -8.5, -1, -1, "block:123", data, false);

        ByteBuf buffer = Unpooled.buffer();
        try {
            MarkerCreatePayload.CODEC.encode(buffer, original);
            MarkerCreatePayload decoded = MarkerCreatePayload.CODEC.decode(buffer);
            require(decoded.kind().equals(original.kind()), "kind");
            require(decoded.icon().equals(original.icon()), "icon");
            require(decoded.x() == original.x(), "x");
            require(decoded.z() == original.z(), "z");
            require(decoded.targetKey().equals(original.targetKey()), "targetKey");
            require("minecraft:apple".equals(decoded.data().getString("itemId")), "data");
            require(!decoded.sticky(), "sticky");
        } finally {
            buffer.release();
        }
    }

    private static void testSyncCodec() {
        MarkerDto marker = new MarkerDto(UUID.randomUUID(), UUID.randomUUID(),
                MarkerType.PING, ResourceLocation.parse("minecraft:target"),
                ResourceLocation.parse("minecraft:overworld"),
                0, 64, 0, -1, -1, "block:0", new CompoundTag(), 100L, 200L);
        UUID removed = UUID.randomUUID();
        MarkerSyncPayload original = new MarkerSyncPayload(
                List.of(new MarkerSyncPayload.Entry(marker, 5_000L)),
                List.of(removed), true);

        ByteBuf buffer = Unpooled.buffer();
        try {
            MarkerSyncPayload.CODEC.encode(buffer, original);
            MarkerSyncPayload decoded = MarkerSyncPayload.CODEC.decode(buffer);
            require(decoded.full(), "full");
            require(decoded.upserts().size() == 1, "upsert count");
            require(decoded.upserts().get(0).remainingMillis() == 5_000L, "remaining");
            require(decoded.upserts().get(0).marker().id().equals(marker.id()), "marker id");
            require(decoded.removals().size() == 1
                    && decoded.removals().get(0).equals(removed), "removal");
        } finally {
            buffer.release();
        }
    }

    private static void testShareFilter() {
        ResourceLocation overworld = ResourceLocation.parse("minecraft:overworld");
        ResourceLocation nether = ResourceLocation.parse("minecraft:the_nether");

        require(MarkerRules.visibleTo(overworld, overworld, 100, 0, false), "same dim unlimited");
        require(!MarkerRules.visibleTo(overworld, nether, 100, 0, false), "other dim blocked");
        require(MarkerRules.visibleTo(overworld, nether, 100, 0, true), "other dim allowed");
        require(MarkerRules.visibleTo(overworld, overworld, 1024, 32, true), "in range (edge)");
        require(!MarkerRules.visibleTo(overworld, overworld, 1025, 32, true), "out of range");
        require(!MarkerRules.visibleTo(overworld, nether, 1, 32, true), "range + other dim");
    }

    /** 图标/头像的取用规则：物品优先于头像；玩家手动换过图标后两者都不覆盖。 */
    private static void testPresentation() {
        CompoundTag itemData = new CompoundTag();
        itemData.putString(MarkerData.KEY_ITEM_ID, "minecraft:diamond_sword");
        CompoundTag item = new CompoundTag();
        item.putString("id", "minecraft:diamond_sword");
        item.putInt("count", 1);
        itemData.put(MarkerData.KEY_ITEM, item);
        MarkerDto itemMarker = new MarkerDto(UUID.randomUUID(), UUID.randomUUID(),
                MarkerType.ITEM, ResourceLocation.parse("minecraft:diamond_sword"),
                ResourceLocation.parse("minecraft:overworld"),
                0, 64, 0, -1, 0, "item:test", itemData, 1L, 2L);
        require(MarkerData.hasItem(itemMarker), "item marker has stack");
        require(MarkerData.portraitType(itemMarker) == null, "item marker has no portrait");

        CompoundTag entityData = new CompoundTag();
        entityData.putString(MarkerData.KEY_ENTITY_TYPE, "minecraft:zombie");
        MarkerDto entityMarker = new MarkerDto(UUID.randomUUID(), UUID.randomUUID(),
                MarkerType.ENTITY, ResourceLocation.parse("minecraft:zombie_spawn_egg"),
                ResourceLocation.parse("minecraft:overworld"),
                0, 64, 0, 42, -1, "entity:test", entityData, 1L, 2L);
        ResourceLocation portrait = MarkerData.portraitType(entityMarker);
        require(portrait != null && "minecraft:zombie".equals(portrait.toString()),
                "entity portrait type");
        require(!MarkerData.hasItem(entityMarker), "entity marker has no item");

        CompoundTag overridden = entityData.copy();
        overridden.putBoolean(MarkerData.KEY_ICON_OVERRIDE, true);
        MarkerDto withIcon = new MarkerDto(entityMarker.id(), entityMarker.owner(),
                entityMarker.type(), ResourceLocation.parse("minecraft:compass"),
                entityMarker.dimension(), 0, 64, 0, 42, -1, "entity:test",
                overridden, 1L, 2L);
        require(MarkerData.portraitType(withIcon) == null, "icon override beats portrait");
        require(MarkerData.iconOverride(withIcon), "icon override flag");

        CompoundTag emptyItem = new CompoundTag();
        emptyItem.put(MarkerData.KEY_ITEM, new CompoundTag());
        require(!MarkerData.hasItem(emptyItem), "empty item tag is not a stack");
    }

    /** 地图呈现（名字/图标）与罗盘的缺省规则：老标记没有键时保持旧行为。 */
    private static void testDisplayKeys() {
        CompoundTag blank = new CompoundTag();
        require(!MarkerData.showsName(markerWith(blank)), "no label never shows a name");
        require(MarkerData.showsInCompass(markerWith(blank)), "no compass key means visible");

        CompoundTag labelled = blank.copy();
        labelled.putString(MarkerData.KEY_LABEL, "Home");
        require(MarkerData.showsName(markerWith(labelled)), "legacy label shows as name");

        CompoundTag iconMode = labelled.copy();
        iconMode.putString(MarkerData.KEY_DISPLAY, MarkerData.DISPLAY_ICON);
        require(!MarkerData.showsName(markerWith(iconMode)), "display=icon hides the name");

        CompoundTag nameMode = labelled.copy();
        nameMode.putString(MarkerData.KEY_DISPLAY, MarkerData.DISPLAY_NAME);
        require(MarkerData.showsName(markerWith(nameMode)), "display=name shows the name");

        CompoundTag compassOff = blank.copy();
        compassOff.putBoolean(MarkerData.KEY_COMPASS, false);
        require(!MarkerData.showsInCompass(markerWith(compassOff)), "compass=false hides");
        compassOff.putBoolean(MarkerData.KEY_COMPASS, true);
        require(MarkerData.showsInCompass(markerWith(compassOff)), "compass=true shows");
    }

    private static MarkerDto markerWith(CompoundTag data) {
        return new MarkerDto(UUID.randomUUID(), UUID.randomUUID(), MarkerType.STICKY,
                ResourceLocation.parse("minecraft:target"),
                ResourceLocation.parse("minecraft:overworld"),
                0, 64, 0, -1, -1, "", data, 1L, 0L);
    }

    private static void testUpdateCodec() {
        MarkerUpdatePayload original = new MarkerUpdatePayload(
                UUID.randomUUID(), ResourceLocation.parse("minecraft:compass"),
                "Base", false, 12.5, 71.0, -30.25, true, false);

        ByteBuf buffer = Unpooled.buffer();
        try {
            MarkerUpdatePayload.CODEC.encode(buffer, original);
            MarkerUpdatePayload decoded = MarkerUpdatePayload.CODEC.decode(buffer);
            require(decoded.id().equals(original.id()), "id");
            require(decoded.icon().equals(original.icon()), "icon");
            require(decoded.label().equals(original.label()), "label");
            require(!decoded.resetIcon(), "resetIcon");
            require(decoded.x() == original.x() && decoded.y() == original.y()
                    && decoded.z() == original.z(), "coords");
            require(decoded.showName(), "showName");
            require(!decoded.compass(), "compass");
        } finally {
            buffer.release();
        }
    }

    private static void testAdoptCodec() {
        MarkerAdoptPayload original = new MarkerAdoptPayload(UUID.randomUUID());
        ByteBuf buffer = Unpooled.buffer();
        try {
            MarkerAdoptPayload.CODEC.encode(buffer, original);
            MarkerAdoptPayload decoded = MarkerAdoptPayload.CODEC.decode(buffer);
            require(decoded.id().equals(original.id()), "adopt id");
        } finally {
            buffer.release();
        }
    }

    private static void require(boolean condition, String name) {
        if (!condition) {
            throw new IllegalStateException(name);
        }
    }
}
