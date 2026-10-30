package example.contentpack.mapv2.marker;

import example.contentpack.mapv2.network.MarkerSyncPayload;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端标记中心。
 *
 * <p>生命周期按类型区分：
 * <ul>
 *   <li>PING：TTL，重复标记（同 owner + type + targetKey）刷新而不是新建；</li>
 *   <li>STICKY：永久；</li>
 *   <li>DEATH：owner 靠近后删除；</li>
 *   <li>ITEM：绑定槽位，槽位物品 ID 变了就删除；</li>
 *   <li>CONTAINER：容器方块没了就删除；</li>
 *   <li>ENTITY：实体 UUID 没了就删除。</li>
 * </ul>
 */
public final class MarkerService {

    /** 与客户端准星射线一致（256 格），否则远处标记会被服务端静默丢弃。 */
    private static final double MAX_CREATE_DISTANCE = 256.0;
    /** 编辑坐标的水平上限（与原版世界边界的绝对值一致）。 */
    private static final double MAX_COORD = 30_000_000.0;
    /** 实体标记发光：每次续 3 秒；标记消失后最多 3 秒自然过期。 */
    private static final int GLOW_DURATION_TICKS = 60;

    private static int tickCounter;

    private MarkerService() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> tick(event.getServer()));
        NeoForge.EVENT_BUS.addListener((LivingDeathEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player && player.getServer() != null) {
                createDeath(player);
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                sendAll(player);
            }
        });
    }

    public static void createFromPlayer(ServerPlayer player, MarkerType type,
                                        double x, double y, double z,
                                        int entityId, int slot, String targetKey,
                                        CompoundTag data, ResourceLocation icon,
                                        boolean sticky) {
        if (!MarkerServerConfig.shareMarkers()) {
            return;
        }
        if (player.distanceToSqr(x, y, z) > MAX_CREATE_DISTANCE * MAX_CREATE_DISTANCE) {
            return;
        }
        MarkerType effective = sticky && type == MarkerType.PING ? MarkerType.STICKY : type;
        if (effective == MarkerType.PING) {
            // 临时标记没有名字、也不参与「名字 / 图标」呈现
            data = data.copy();
            data.remove(MarkerData.KEY_LABEL);
            data.remove(MarkerData.KEY_DISPLAY);
        }
        long now = System.currentTimeMillis();
        MarkerSavedData store = MarkerSavedData.get(player.getServer());

        // 去重：同 owner + 同类型 + 同目标 → 时间型刷新，其余忽略
        if (!targetKey.isEmpty()) {
            for (MarkerDto existing : store.markers()) {
                if (!existing.owner().equals(player.getUUID())
                        || existing.type() != effective
                        || !existing.targetKey().equals(targetKey)) {
                    continue;
                }
                if (effective.timed()) {
                    MarkerDto refreshed = new MarkerDto(existing.id(), existing.owner(), effective,
                            icon, existing.dimension(), x, y, z, entityId, slot,
                            targetKey, data, existing.createdAt(),
                            now + MarkerServerConfig.pingSeconds() * 1000L);
                    store.replace(refreshed);
                    broadcast(player.getServer(), refreshed, now);
                    applyGlow(player.getServer(), refreshed);
                }
                return;
            }
        }

        long expires = effective.timed()
                ? now + MarkerServerConfig.pingSeconds() * 1000L : 0L;
        MarkerDto marker = new MarkerDto(UUID.randomUUID(), player.getUUID(), effective,
                icon, player.level().dimension().location(), x, y, z,
                entityId, slot, targetKey, data, now, expires);
        store.add(marker);
        broadcast(player.getServer(), marker, now);
        applyGlow(player.getServer(), marker);
    }

    private static void createDeath(ServerPlayer player) {
        long now = System.currentTimeMillis();
        MarkerDto marker = new MarkerDto(UUID.randomUUID(), player.getUUID(),
                MarkerType.DEATH,
                ResourceLocation.withDefaultNamespace("skeleton_skull"),
                player.level().dimension().location(),
                player.getX(), player.getY(), player.getZ(), -1, -1, "", new CompoundTag(), now, 0L);
        MarkerSavedData.get(player.getServer()).add(marker);
        broadcast(player.getServer(), marker, now);
    }

    private static void tick(MinecraftServer server) {
        if (++tickCounter % 20 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        MarkerSavedData store = MarkerSavedData.get(server);
        store.removeExpired(now);

        List<MarkerDto> invalid = new ArrayList<>();
        for (MarkerDto marker : store.markers()) {
            if (!stillValid(server, marker)) {
                invalid.add(marker);
            }
        }
        for (MarkerDto marker : invalid) {
            if (store.remove(marker.id())) {
                broadcastRemoval(server, marker.id());
            }
        }
        // 实体标记：续原版发光（标记消失后不再续，最多 3 秒自然过期）
        for (MarkerDto marker : store.markers()) {
            applyGlow(server, marker);
        }
    }

    /**
     * 给被标记的生物续原版 Glowing（光灵箭效果）。
     *
     * <p>不用 {@code setGlowingTag}：1.21.1 客户端版会把 flag 读回旧值写回，实际不生效；
     * 服务端的 Glowing 效果会由 {@code LivingEntity} 自动同步 shared flag 6，所有客户端都能看到。
     * 已经在更久地发光（例如刚中了光灵箭）就不覆盖。
     */
    private static void applyGlow(MinecraftServer server, MarkerDto marker) {
        if (marker.type() != MarkerType.ENTITY) {
            return;
        }
        UUID uuid = MarkerData.entityUuid(marker);
        if (uuid == null) {
            return;
        }
        ServerLevel level = server.getLevel(
                ResourceKey.create(Registries.DIMENSION, marker.dimension()));
        if (level == null) {
            return;
        }
        Entity entity = level.getEntity(uuid);
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        MobEffectInstance current = living.getEffect(MobEffects.GLOWING);
        if (current != null && current.getDuration() > GLOW_DURATION_TICKS) {
            return;
        }
        living.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_DURATION_TICKS, 0,
                false, false, false));
    }

    private static boolean stillValid(MinecraftServer server, MarkerDto marker) {
        if (marker.type() == MarkerType.DEATH) {
            return deathStillThere(server, marker);
        }
        // 其余临时类型由 TTL 处理；STICKY 永久
        return true;
    }

    private static boolean deathStillThere(MinecraftServer server, MarkerDto marker) {
        ServerPlayer owner = server.getPlayerList().getPlayer(marker.owner());
        if (owner == null || !owner.level().dimension().location().equals(marker.dimension())) {
            return true; // 离线/换维度：保留，回来再判断
        }
        double radius = MarkerServerConfig.deathMarkerRadius();
        return owner.distanceToSqr(marker.x(), marker.y(), marker.z()) > radius * radius;
    }

    private static void broadcast(MinecraftServer server, MarkerDto marker, long now) {
        long remaining = marker.expiresAt() > 0
                ? Math.max(0L, marker.expiresAt() - now) : 0L;
        MarkerSyncPayload payload = new MarkerSyncPayload(
                List.of(new MarkerSyncPayload.Entry(marker, remaining)), List.of(), false);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (visibleTo(player, marker)) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    private static void broadcastRemoval(MinecraftServer server, UUID id) {
        MarkerSyncPayload payload = new MarkerSyncPayload(List.of(), List.of(id), false);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    /**
     * 编辑图标/名字/坐标/呈现方式/罗盘开关：owner 或 op。{@code resetIcon} 清除手动图标覆盖。
     *
     * <p>只有永久地点标记（{@link MarkerType#editable()}）可编辑；临时标记会随 TTL 消失，
     * 服务端直接拒绝，避免客户端绕开界面改别人的临时标记。
     */
    public static void updateByRequest(ServerPlayer player, UUID id,
                                       ResourceLocation icon, String label,
                                       boolean resetIcon,
                                       double x, double y, double z,
                                       boolean showName, boolean compass) {
        MarkerSavedData store = MarkerSavedData.get(player.getServer());
        for (MarkerDto marker : store.markers()) {
            if (!marker.id().equals(id)) {
                continue;
            }
            if (!marker.owner().equals(player.getUUID()) && !player.hasPermissions(2)) {
                return;
            }
            if (!marker.type().editable()) {
                return;
            }
            CompoundTag data = marker.data().copy();
            if (label == null || label.isBlank()) {
                data.remove(MarkerData.KEY_LABEL);
            } else {
                data.putString(MarkerData.KEY_LABEL, label.trim());
            }
            if (resetIcon) {
                data.remove(MarkerData.KEY_ICON_OVERRIDE);
            } else if (!icon.equals(marker.icon())) {
                // 只在真的换了图标时锁定，避免「没改图标」也把物品/生物图标顶掉
                data.putBoolean(MarkerData.KEY_ICON_OVERRIDE, true);
            }
            data.putBoolean(MarkerData.KEY_COMPASS, compass);

            double newX = marker.x();
            double newY = marker.y();
            double newZ = marker.z();
            data.putString(MarkerData.KEY_DISPLAY,
                    showName ? MarkerData.DISPLAY_NAME : MarkerData.DISPLAY_ICON);
            if (finiteCoords(x, y, z)) {
                newX = Mth.clamp(x, -MAX_COORD, MAX_COORD);
                newZ = Mth.clamp(z, -MAX_COORD, MAX_COORD);
                // 超出世界高度会被画到虚空/天上，夹回建造范围
                ServerLevel level = player.getServer() == null ? null
                        : player.getServer().getLevel(ResourceKey.create(
                                Registries.DIMENSION, marker.dimension()));
                ServerLevel bounds = level == null ? player.serverLevel() : level;
                newY = Mth.clamp(y, bounds.getMinBuildHeight(), bounds.getMaxBuildHeight());
            }
            MarkerDto updated = new MarkerDto(marker.id(), marker.owner(), marker.type(),
                    icon, marker.dimension(), newX, newY, newZ,
                    marker.entityId(), marker.slot(), marker.targetKey(), data,
                    marker.createdAt(), marker.expiresAt());
            store.replace(updated);
            broadcast(player.getServer(), updated, System.currentTimeMillis());
            return;
        }
    }

    private static boolean finiteCoords(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    /**
     * 把别人分享的永久地点标记复制成自己的（「永久化」）：新 UUID、owner = 自己、永久。
     *
     * <p>只有 STICKY 能永久化，且必须当前可见（防止按 id 越权复制）；复制一份后原标记照旧只读。
     * 同一来源只保留一份，重复点不会堆出一串副本。
     */
    public static void adoptByRequest(ServerPlayer player, UUID id) {
        if (!MarkerServerConfig.shareMarkers()) {
            return;
        }
        MarkerSavedData store = MarkerSavedData.get(player.getServer());
        for (MarkerDto marker : store.markers()) {
            if (!marker.id().equals(id)) {
                continue;
            }
            if (!marker.type().editable() || marker.owner().equals(player.getUUID())) {
                return;
            }
            if (!visibleTo(player, marker)) {
                return;
            }
            String source = id.toString();
            for (MarkerDto existing : store.markers()) {
                if (existing.owner().equals(player.getUUID())
                        && source.equals(existing.data().getString(MarkerData.KEY_SOURCE))) {
                    return;
                }
            }
            CompoundTag data = marker.data().copy();
            data.putString(MarkerData.KEY_SOURCE, source);
            long now = System.currentTimeMillis();
            MarkerDto copy = new MarkerDto(UUID.randomUUID(), player.getUUID(), MarkerType.STICKY,
                    marker.icon(), marker.dimension(), marker.x(), marker.y(), marker.z(),
                    -1, -1, "", data, now, 0L);
            store.add(copy);
            broadcast(player.getServer(), copy, now);
            return;
        }
    }

    /** 手动删除：owner 或 op 才能删。 */
    public static void removeByRequest(ServerPlayer player, UUID id) {
        MarkerSavedData store = MarkerSavedData.get(player.getServer());
        for (MarkerDto marker : store.markers()) {
            if (!marker.id().equals(id)) {
                continue;
            }
            if (marker.owner().equals(player.getUUID()) || player.hasPermissions(2)) {
                if (store.remove(id)) {
                    broadcastRemoval(player.getServer(), id);
                }
            }
            return;
        }
    }

    public static void sendAll(ServerPlayer player) {
        long now = System.currentTimeMillis();
        List<MarkerSyncPayload.Entry> visible = new ArrayList<>();
        for (MarkerDto marker : MarkerSavedData.get(player.getServer()).markers()) {
            if (!visibleTo(player, marker)) {
                continue;
            }
            long remaining = marker.expiresAt() > 0
                    ? Math.max(0L, marker.expiresAt() - now) : 0L;
            if (marker.expiresAt() > 0 && remaining == 0L) {
                continue;
            }
            visible.add(new MarkerSyncPayload.Entry(marker, remaining));
        }
        PacketDistributor.sendToPlayer(player,
                new MarkerSyncPayload(visible, List.of(), true));
    }

    private static boolean visibleTo(ServerPlayer player, MarkerDto marker) {
        return MarkerRules.visibleTo(
                player.level().dimension().location(),
                marker.dimension(),
                player.distanceToSqr(marker.x(), marker.y(), marker.z()),
                MarkerServerConfig.markerRange(),
                MarkerServerConfig.shareAllDimensions());
    }
}
