package example.contentpack.sablev2.map.client;

import com.catoxide.catoxidesbattlerebuild.core.map.MapOverlay;
import com.catoxide.catoxidesbattlerebuild.core.map.MapRenderContext;
import com.catoxide.catoxidesbattlerebuild.core.sable.ISubLevelService;
import com.catoxide.catoxidesbattlerebuild.core.sable.PoseData;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelInfo;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelServiceRegistry;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import example.contentpack.sablev2.map.SableMapConfig;
import example.contentpack.sablev2.map.TopDownView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 地图上的 Sable 图层：
 * <ol>
 *   <li>船体图解贴图（GPU 离屏渲染的世界正俯视图）：与世界轴对齐贴到地图上，
 *       船体的旋转/倾斜已经烘进纹理；</li>
 *   <li>站在 sub-level 上的生物/玩家：世界位置 + 船体局部朝向 → 世界朝向。</li>
 * </ol>
 * 由 map-pack 在小地图/世界地图的地形之后、标记之前调用。
 */
final class SubLevelMapOverlay implements MapOverlay {

    private final SubLevelGpuCapture capture;

    SubLevelMapOverlay(SubLevelGpuCapture capture) {
        this.capture = capture;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public void render(MapRenderContext context) {
        if (!SableMapConfig.enabled()) {
            return;
        }
        if (context.fullscreen() ? !SableMapConfig.showOnFullmap()
                : !SableMapConfig.showOnMinimap()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || !level.dimension().location().equals(context.dimension())) {
            return;
        }
        ISubLevelService service = SubLevelServiceRegistry.get();
        if (service == null) {
            return;
        }

        List<ShipView> ships = this.snapshot(level, context.partialTick());
        if (SableMapConfig.showShips()) {
            this.renderShips(context, service, ships);
        }
        if (SableMapConfig.showEntities()) {
            this.renderEntities(context, level, service, minecraft, ships);
        }
    }

    /** 取本帧的船体快照（renderPose 插值），贴图重渲与 2D 投影共用同一姿态。 */
    private List<ShipView> snapshot(ClientLevel level, float partialTick) {
        List<ShipView> ships = new ArrayList<>();
        for (SubLevelGpuEntry entry : this.capture.entries()) {
            SubLevelInfo base = entry.info();
            if (entry.view() == null || base.bounds().isEmpty()) {
                continue;
            }
            ClientSubLevel sub = SableClientSubLevels.get(level, entry.id());
            PoseData pose = SableClientSubLevels.renderPose(sub, partialTick);
            SubLevelInfo current = pose == null ? base
                    : new SubLevelInfo(base.id(), base.name(), pose, base.bounds());
            ships.add(new ShipView(entry, current));
        }
        return ships;
    }

    private void renderShips(MapRenderContext context, ISubLevelService service,
                             List<ShipView> ships) {
        ships.sort(Comparator.comparingDouble(view -> view.info().pose().posY()));

        float alpha = (float) SableMapConfig.opacity();
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
        try {
            for (ShipView ship : ships) {
                SubLevelGpuEntry entry = ship.entry();
                TopDownView view = entry.view();
                if (view == null) {
                    continue;
                }
                SubLevelProjection projection = SubLevelProjection.worldAligned(
                        service, ship.info(), context, view);
                if (!visible(context, projection, view.imageWidth(), view.imageHeight())) {
                    continue;
                }
                this.capture.requestRender(entry);
                if (!entry.hasTexture()) {
                    continue;
                }

                Matrix4f matrix = new Matrix4f();
                matrix.m00((float) projection.ux());
                matrix.m01((float) projection.vx());
                matrix.m10((float) projection.uz());
                matrix.m11((float) projection.vz());
                matrix.m30((float) projection.originScreenX());
                matrix.m31((float) projection.originScreenZ());

                var pose = context.graphics().pose();
                pose.pushPose();
                pose.mulPose(matrix);
                context.graphics().blit(entry.textureLocation(),
                        0, 0, 0, 0.0f, 0.0f,
                        entry.imageWidth(), entry.imageHeight(),
                        entry.imageWidth(), entry.imageHeight());
                pose.popPose();
            }
        } finally {
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    private static boolean visible(MapRenderContext context, SubLevelProjection projection,
                                   int width, int height) {
        double x0 = projection.screenX(0, 0);
        double z0 = projection.screenZ(0, 0);
        double x1 = projection.screenX(width, 0);
        double z1 = projection.screenZ(width, 0);
        double x2 = projection.screenX(0, height);
        double z2 = projection.screenZ(0, height);
        double x3 = projection.screenX(width, height);
        double z3 = projection.screenZ(width, height);
        double minX = Math.min(Math.min(x0, x1), Math.min(x2, x3));
        double maxX = Math.max(Math.max(x0, x1), Math.max(x2, x3));
        double minZ = Math.min(Math.min(z0, z1), Math.min(z2, z3));
        double maxZ = Math.max(Math.max(z0, z1), Math.max(z2, z3));
        return context.intersects(minX, minZ, maxX, maxZ);
    }

    private void renderEntities(MapRenderContext context, ClientLevel level,
                                ISubLevelService service, Minecraft minecraft,
                                List<ShipView> ships) {
        LocalPlayer self = minecraft.player;
        int alpha = (int) Math.round(SableMapConfig.opacity() * 255.0);
        if (alpha <= 0) {
            return;
        }

        for (Entity entity : level.entitiesForRendering()) {
            if (entity == self || !(entity instanceof LivingEntity)
                    || entity.isInvisible() || entity.isSpectator()) {
                continue;
            }

            ShipView ship = findShip(service, ships, entity.position());
            if (ship == null) {
                continue;
            }

            int px = (int) Math.round(context.screenX(entity.getX()));
            int py = (int) Math.round(context.screenZ(entity.getZ()));
            if (px < context.left() - 8 || px > context.right() + 8
                    || py < context.top() - 8 || py > context.bottom() + 8) {
                continue;
            }

            Vec3 local = service.worldToLocal(ship.info(), entity.position());
            float worldYaw = SubLevelProjection.worldYaw(service, ship.info(), local.y, entity.getYRot());
            int color = entityColor(entity, alpha);

            // 缩得很远时收成 1px，避免实体点比整艘船还大
            int dot = context.blocksPerPixel() <= 8 ? 1 : 0;
            int lineLength = dot > 0 ? 6 : 3;
            double radians = Math.toRadians(worldYaw);
            double dx = -Math.sin(radians);
            double dz = Math.cos(radians);
            for (int step = Math.max(1, dot + 1); step <= lineLength; step++) {
                int hx = px + (int) Math.round(dx * step);
                int hz = py + (int) Math.round(dz * step);
                context.graphics().fill(hx, hz, hx + 1, hz + 1, color);
            }

            context.graphics().fill(px - dot, py - dot, px + dot + 1, py + dot + 1, color);
            if (dot > 0) {
                // 深色描边提高在船体方块上的可读性
                int outline = (alpha << 24) | 0x101010;
                context.graphics().fill(px - 2, py - 1, px - 1, py + 2, outline);
                context.graphics().fill(px + 2, py - 1, px + 3, py + 2, outline);
                context.graphics().fill(px - 1, py - 2, px + 2, py - 1, outline);
                context.graphics().fill(px - 1, py + 2, px + 2, py + 3, outline);
            }
        }
    }

    private static ShipView findShip(ISubLevelService service, List<ShipView> ships, Vec3 world) {
        for (ShipView ship : ships) {
            SubLevelInfo sub = ship.info();
            Vec3 local = service.worldToLocal(sub, world);
            if (sub.bounds().contains(local.x, local.y, local.z)) {
                return ship;
            }
        }
        return null;
    }

    private static int entityColor(Entity entity, int alpha) {
        int rgb;
        if (entity instanceof Player) {
            rgb = 0xFFFFFF;
        } else if (entity instanceof Enemy) {
            rgb = 0xFF6060;
        } else {
            rgb = 0x80FF80;
        }
        return (alpha << 24) | rgb;
    }

    private record ShipView(SubLevelGpuEntry entry, SubLevelInfo info) {
    }
}
