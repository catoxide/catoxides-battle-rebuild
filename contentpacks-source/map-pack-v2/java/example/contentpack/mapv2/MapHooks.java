package example.contentpack.mapv2;

import com.catoxide.catoxidesbattlerebuild.core.map.IMapPlayerSkinSource;
import com.catoxide.catoxidesbattlerebuild.core.map.MapApi;
import com.catoxide.catoxidesbattlerebuild.core.map.MapOverlay;
import com.catoxide.catoxidesbattlerebuild.core.map.MapPointResolver;
import com.catoxide.catoxidesbattlerebuild.core.map.MapRenderContext;
import com.mojang.authlib.GameProfile;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.mapv2.marker.MarkerDto;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 地图扩展点：overlay 渲染 + 标点/坐标解析。
 *
 * <p>由 map-pack 在 register 阶段通过 {@code MapCaps.API} 提供给其他内容包。
 * 所有实现都在客户端使用；map-pack 自身不认识任何具体扩展包。
 */
public final class MapHooks implements MapApi {

    private static final String TAG = "MapHooks";

    public static final MapHooks INSTANCE = new MapHooks();

    private final List<MapOverlay> overlays = new CopyOnWriteArrayList<>();
    private final List<MapPointResolver> resolvers = new CopyOnWriteArrayList<>();
    private final List<IMapPlayerSkinSource> skinSources = new CopyOnWriteArrayList<>();

    private MapHooks() {
    }

    @Override
    public void addOverlay(MapOverlay overlay) {
        if (overlay == null || overlays.contains(overlay)) {
            return;
        }
        overlays.add(overlay);
        overlays.sort(Comparator.comparingInt(MapOverlay::priority));
        LogManager.clientInfo(TAG, "overlay registered: {} (priority {})",
                overlay.getClass().getName(), overlay.priority());
    }

    @Override
    public void addPointResolver(MapPointResolver resolver) {
        if (resolver == null || resolvers.contains(resolver)) {
            return;
        }
        resolvers.add(resolver);
        LogManager.clientInfo(TAG, "point resolver registered: {}", resolver.getClass().getName());
    }

    @Override
    public void addPlayerSkinSource(IMapPlayerSkinSource source) {
        if (source == null || skinSources.contains(source)) {
            return;
        }
        skinSources.add(source);
        LogManager.clientInfo(TAG, "player skin source registered: {}",
                source.getClass().getName());
    }

    /**
     * 玩家皮肤：按注册顺序问每个来源；都没有返回 null（调用方走在线连接/离线拉取/默认）。
     */
    @Nullable
    public GameProfile playerProfile(UUID uuid, String name) {
        if (uuid == null) {
            return null;
        }
        for (IMapPlayerSkinSource source : skinSources) {
            try {
                GameProfile profile = source.profile(uuid, name);
                if (profile != null) {
                    return profile;
                }
            } catch (Throwable t) {
                LogManager.clientWarn(TAG, "skin source failed: {}", t.getMessage());
            }
        }
        return null;
    }

    public boolean hasOverlays() {
        return !overlays.isEmpty();
    }

    /** 基础地形之后、标记之前调用；scissor 已由调用方设置。 */
    public void renderOverlays(MapRenderContext context) {
        for (MapOverlay overlay : overlays) {
            try {
                overlay.render(context);
            } catch (Throwable t) {
                LogManager.clientError(TAG,
                        "overlay " + overlay.getClass().getName() + " failed: " + t.getMessage());
            }
        }
    }

    /** 非普通空间（Sable sub-level）射线；第一个非 null 命中生效。 */
    @Nullable
    public HitResult raycast(Level level, Vec3 from, Vec3 to, net.minecraft.world.entity.Entity source) {
        if (level == null || from == null || to == null) {
            return null;
        }
        for (MapPointResolver resolver : resolvers) {
            try {
                HitResult hit = resolver.raycast(level, from, to, source);
                if (hit != null) {
                    return hit;
                }
            } catch (Throwable t) {
                LogManager.clientWarn(TAG, "raycast failed: {}", t.getMessage());
            }
        }
        return null;
    }

    /**
     * 标点创建：把原始点交给扩展解析；第一个返回非 null 的解析器生效。
     */
    public Vec3 resolveCreate(Level level, Vec3 raw, @Nullable HitResult hit, CompoundTag anchorData) {
        if (level == null || raw == null) {
            return raw;
        }
        for (MapPointResolver resolver : resolvers) {
            try {
                Vec3 world = resolver.toWorld(level, raw, hit, anchorData);
                if (world != null) {
                    return world;
                }
            } catch (Throwable t) {
                LogManager.clientWarn(TAG, "toWorld failed: {}", t.getMessage());
            }
        }
        return raw;
    }

    /**
     * 标点渲染：锚点解析；失败时回退存储的世界坐标。
     */
    public Vec3 resolveMarker(Level level, MarkerDto marker, float partialTick) {
        if (level == null || marker == null) {
            return null;
        }
        for (MapPointResolver resolver : resolvers) {
            try {
                Vec3 world = resolver.resolve(level, marker.x(), marker.y(), marker.z(),
                        marker.data(), partialTick);
                if (world != null) {
                    return world;
                }
            } catch (Throwable t) {
                LogManager.clientWarn(TAG, "resolve failed: {}", t.getMessage());
            }
        }
        return new Vec3(marker.x(), marker.y(), marker.z());
    }

    /** 地图点击处的可站立表面高度；没有扩展覆盖时返回 null。 */
    @Nullable
    public Double surfaceY(Level level, int x, int z) {
        if (level == null) {
            return null;
        }
        for (MapPointResolver resolver : resolvers) {
            try {
                Double y = resolver.surfaceY(level, x, z);
                if (y != null) {
                    return y;
                }
            } catch (Throwable t) {
                LogManager.clientWarn(TAG, "surfaceY failed: {}", t.getMessage());
            }
        }
        return null;
    }

    /** 世界列在扩展空间（如 Sable 甲板）上的锚点；第一个非 null 解析器生效。 */
    @Nullable
    public Vec3 surfaceAnchor(Level level, double x, double z, CompoundTag anchorData) {
        if (level == null) {
            return null;
        }
        for (MapPointResolver resolver : resolvers) {
            try {
                Vec3 world = resolver.surfaceAnchor(level, x, z, anchorData);
                if (world != null) {
                    return world;
                }
            } catch (Throwable t) {
                LogManager.clientWarn(TAG, "surfaceAnchor failed: {}", t.getMessage());
            }
        }
        return null;
    }

    /** 是否是普通地图不应采集的分离空间（plot 里的船体等）。 */
    public boolean isDetached(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        for (MapPointResolver resolver : resolvers) {
            try {
                if (resolver.isDetached(level, pos)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 扩展失败不能影响采集
            }
        }
        return false;
    }
}
