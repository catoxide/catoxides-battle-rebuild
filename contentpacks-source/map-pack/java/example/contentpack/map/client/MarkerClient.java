package example.contentpack.map.client;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.mojang.authlib.GameProfile;
import example.contentpack.map.MapConfig;
import example.contentpack.map.MapHooks;
import example.contentpack.map.marker.MarkerData;
import example.contentpack.map.marker.MarkerDto;
import example.contentpack.map.network.MarkerSyncPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 客户端标记缓存：同步、本地到期、声音、闪烁、物品图标/生物头像。
 *
 * <p>到期时间用接收时的「剩余毫秒」换算成本地时钟，避免客户端/服务器时钟偏差。
 * ITEM 标记同样是短时展示（和服务端 TTL 一致）。
 */
public final class MarkerClient {

    private static final String TAG = "MarkerClient";
    private static final long FLASH_MILLIS = 5_000L;

    private static final Map<UUID, MarkerDto> MARKERS = new HashMap<>();
    private static final Map<UUID, Long> LOCAL_EXPIRE = new HashMap<>();
    private static final Map<UUID, Long> FLASH_UNTIL = new HashMap<>();
    /** 生物头像用「未加入世界」的临时实体；按标记缓存，避免每帧 new。 */
    private static final Map<UUID, Entity> PORTRAITS = new HashMap<>();
    private static final Map<UUID, String> PORTRAIT_KEYS = new HashMap<>();
    /** 玩家皮肤：在线连接优先，离线按名字拉取后缓存。 */
    private static final Map<UUID, GameProfile> PROFILES = new HashMap<>();
    private static final Map<UUID, ItemStack> PLAYER_HEADS = new HashMap<>();
    private static final Set<UUID> PROFILE_LOOKUPS = new HashSet<>();
    private static long lastSoundMillis;

    private MarkerClient() {
    }

    public static void receive(MarkerSyncPayload payload) {
        if (payload.full()) {
            reset();
        }
        for (UUID id : payload.removals()) {
            forget(id);
        }

        long now = System.currentTimeMillis();
        UUID self = Minecraft.getInstance().player == null
                ? null : Minecraft.getInstance().player.getUUID();
        boolean soundPlayed = false;
        for (MarkerSyncPayload.Entry entry : payload.upserts()) {
            MarkerDto marker = entry.marker();
            boolean isNew = MARKERS.put(marker.id(), marker) == null;
            if (entry.remainingMillis() > 0) {
                LOCAL_EXPIRE.put(marker.id(), now + entry.remainingMillis());
            } else {
                LOCAL_EXPIRE.remove(marker.id());
            }
            if (!isNew || (self != null && self.equals(marker.owner()))) {
                continue;
            }
            FLASH_UNTIL.put(marker.id(), now + FLASH_MILLIS);
            if (!soundPlayed && now - lastSoundMillis > 250L) {
                lastSoundMillis = now;
                soundPlayed = true;
                playPing();
            }
        }
        cleanup(now);
    }

    /** 每客户端 tick 调用，清理本地到期的标记。 */
    public static void tick() {
        cleanup(System.currentTimeMillis());
    }

    public static List<MarkerDto> visibleMarkers(ResourceLocation dimension) {
        List<MarkerDto> result = new ArrayList<>();
        for (MarkerDto marker : MARKERS.values()) {
            if (marker.dimension().equals(dimension)) {
                result.add(marker);
            }
        }
        return result;
    }

    public static List<MarkerDto> allMarkers() {
        return new ArrayList<>(MARKERS.values());
    }

    /** 能否改/删：owner 或 op。客户端只是预判，服务端仍会再校验。 */
    public static boolean canModify(MarkerDto marker) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null
                && (minecraft.player.getUUID().equals(marker.owner())
                || minecraft.player.hasPermissions(2));
    }

    /**
     * 能否打开编辑屏：只有自己的永久地点标记。
     * <p>临时标记没有名字也没有图标选择；别人分享来的要等「永久化」成自己的副本才能改。
     */
    public static boolean canEdit(MarkerDto marker) {
        return marker.type().editable() && canModify(marker);
    }

    /** 剩余 TTL；0 = 永久或已到期。 */
    public static long remainingMillis(UUID id) {
        Long expire = LOCAL_EXPIRE.get(id);
        return expire == null ? 0L : Math.max(0L, expire - System.currentTimeMillis());
    }

    public static boolean isFlashing(UUID id) {
        Long until = FLASH_UNTIL.get(id);
        return until != null && System.currentTimeMillis() < until;
    }

    /** 物品标记的完整物品栈（带组件）；其余标记返回 {@link ItemStack#EMPTY}。 */
    public static ItemStack itemStack(MarkerDto marker) {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? ItemStack.EMPTY : MarkerData.readItem(marker, level.registryAccess());
    }

    /** 标记图标：手动换过图标 → 用图标；玩家 → 皮肤头；有完整物品 → 用物品本体；否则用 icon。 */
    public static ItemStack iconStack(MarkerDto marker) {
        if (MarkerData.isPlayerPortrait(marker)) {
            return playerHead(marker);
        }
        if (!MarkerData.iconOverride(marker)) {
            ItemStack item = itemStack(marker);
            if (!item.isEmpty()) {
                return item;
            }
        }
        Item fallback = BuiltInRegistries.ITEM.get(marker.icon());
        return fallback == null ? ItemStack.EMPTY : new ItemStack(fallback);
    }

    /**
     * 实体标记的生物头像：按 EntityType 造一个不入世界的临时实体。
     *
     * <p>不能依赖客户端已加载实体（共享标记的 entityId 在别人那里没有意义，
     * 区块未加载时也拿不到），所以只用落盘的 entityType。
     */
    @Nullable
    public static Entity portrait(MarkerDto marker) {
        if (MarkerData.isPlayerPortrait(marker)) {
            return null; // 玩家不造 dummy，走皮肤头（物品路径）
        }
        ResourceLocation typeId = MarkerData.portraitType(marker);
        ClientLevel level = Minecraft.getInstance().level;
        if (typeId == null || level == null) {
            return null;
        }
        String key = typeId + "@" + level.dimension().location();
        // key 命中时连「创建失败」一起缓存，避免每帧重试刷屏
        if (key.equals(PORTRAIT_KEYS.get(marker.id()))) {
            return PORTRAITS.get(marker.id());
        }
        PORTRAITS.remove(marker.id());
        PORTRAIT_KEYS.put(marker.id(), key);

        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(typeId);
        if (type == null) {
            return null;
        }
        Entity created;
        try {
            created = type.create(level);
        } catch (Throwable error) {
            LogManager.clientWarn(TAG, "portrait create failed for {}: {}",
                    typeId, error.getMessage());
            return null;
        }
        if (!(created instanceof LivingEntity living)) {
            return null; // 玩家/特殊实体没有可渲染的工厂
        }
        // 统一摆成正脸：GUI 和世界 billboard 都直接用这个姿态
        living.setYRot(180.0f);
        living.yRotO = 180.0f;
        living.setXRot(0.0f);
        living.xRotO = 0.0f;
        living.yHeadRot = 180.0f;
        living.yHeadRotO = 180.0f;
        living.yBodyRot = 180.0f;
        living.yBodyRotO = 180.0f;
        living.tickCount = 0;
        PORTRAITS.put(marker.id(), living);
        return living;
    }

    /**
     * 玩家皮肤档案：先问内容包注册的皮肤来源，再用在线玩家连接数据；
     * 都没有时按名字异步拉一次（Mojang profile），失败就回退默认头。
     */
    @Nullable
    public static GameProfile playerProfile(MarkerDto marker) {
        UUID uuid = MarkerData.entityUuid(marker);
        if (uuid == null) {
            return null;
        }
        String name = MarkerData.playerName(marker);
        GameProfile external = MapHooks.INSTANCE.playerProfile(uuid, name);
        if (external != null) {
            return external;
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            PlayerInfo info = connection.getPlayerInfo(uuid);
            if (info != null) {
                return info.getProfile();
            }
        }
        GameProfile cached = PROFILES.get(uuid);
        if (cached != null) {
            return cached;
        }
        if (!name.isEmpty() && PROFILE_LOOKUPS.add(uuid)) {
            SkullBlockEntity.fetchGameProfile(name).thenAccept(optional ->
                    optional.ifPresent(profile -> Minecraft.getInstance().execute(() -> {
                        PROFILES.put(uuid, profile);
                        PLAYER_HEADS.remove(uuid);
                    })));
        }
        return null;
    }

    /** 玩家皮肤纹理（64×64）；拿不到档案时用默认皮肤。 */
    public static ResourceLocation playerSkin(MarkerDto marker) {
        SkinManager skins = Minecraft.getInstance().getSkinManager();
        GameProfile profile = playerProfile(marker);
        if (profile != null) {
            return skins.getInsecureSkin(profile).texture();
        }
        UUID uuid = MarkerData.entityUuid(marker);
        return skins.getInsecureSkin(new GameProfile(uuid == null ? new UUID(0L, 0L) : uuid,
                MarkerData.playerName(marker))).texture();
    }

    /** 玩家头像：带皮肤档案的玩家头（渲染成 3D 头）；拿不到皮肤时是默认头。 */
    public static ItemStack playerHead(MarkerDto marker) {
        UUID uuid = MarkerData.entityUuid(marker);
        if (uuid == null) {
            return new ItemStack(Items.PLAYER_HEAD);
        }
        ItemStack cached = PLAYER_HEADS.get(uuid);
        if (cached != null) {
            return cached;
        }
        GameProfile profile = playerProfile(marker);
        if (profile == null) {
            return new ItemStack(Items.PLAYER_HEAD);
        }
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponents.PROFILE, new ResolvableProfile(profile));
        PLAYER_HEADS.put(uuid, head);
        return head;
    }

    /** 断开连接/换服时清空，重新同步会重建。 */
    public static void reset() {
        MARKERS.clear();
        LOCAL_EXPIRE.clear();
        FLASH_UNTIL.clear();
        PORTRAITS.clear();
        PORTRAIT_KEYS.clear();
        PROFILES.clear();
        PLAYER_HEADS.clear();
        PROFILE_LOOKUPS.clear();
    }

    private static void forget(UUID id) {
        MARKERS.remove(id);
        LOCAL_EXPIRE.remove(id);
        FLASH_UNTIL.remove(id);
        PORTRAITS.remove(id);
        PORTRAIT_KEYS.remove(id);
    }

    private static void cleanup(long now) {
        var iterator = LOCAL_EXPIRE.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (now > entry.getValue()) {
                PORTRAITS.remove(entry.getKey());
                PORTRAIT_KEYS.remove(entry.getKey());
                MARKERS.remove(entry.getKey());
                FLASH_UNTIL.remove(entry.getKey());
                iterator.remove();
            }
        }
    }

    private static void playPing() {
        var player = Minecraft.getInstance().player;
        if (player == null || !MapConfig.markerSound()) {
            return;
        }
        player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 0.8f, 1.6f);
    }
}
