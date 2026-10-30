package example.contentpack.mapv2.client;

import com.catoxide.catoxidesbattlerebuild.core.event.BlockChangeHooks;
import com.mojang.blaze3d.platform.InputConstants;
import example.contentpack.mapv2.MapCapture;
import example.contentpack.mapv2.MapHooks;
import example.contentpack.mapv2.MapStorage;
import example.contentpack.mapv2.marker.MarkerData;
import example.contentpack.mapv2.marker.MarkerType;
import example.contentpack.mapv2.network.MarkerCreatePayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

/**
 * 客户端事件接线（A7：内容包不能用 {@code @EventBusSubscriber}，全部显式挂载）。
 *
 * <p>世界地图（M）、小地图（RenderGuiEvent.Post）、罗盘（Pre）、标记（准星/Mouse4/容器界面）都在这里挂。
 */
public final class MapV2ClientEvents {

    public static final KeyMapping OPEN_MAP = new KeyMapping(
            "key.catoxidesbattlerebuild.open_map",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M,
            "key.categories.catoxidesbattlerebuild.map");

    public static final KeyMapping MARK = new KeyMapping(
            "key.catoxidesbattlerebuild.mark",
            InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_4,
            "key.categories.catoxidesbattlerebuild.map");

    public static final KeyMapping TOGGLE_COMPASS = new KeyMapping(
            "key.skyrimcompasshud.toggle",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H,
            "key.categories.skyrimcompasshud");

    /** 罗盘显示状态（H 键切换）。 */
    public static volatile boolean compassVisible = true;

    private MapV2ClientEvents() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> {
            event.register(OPEN_MAP);
            event.register(MARK);
            event.register(TOGGLE_COMPASS);
        });

        // 方块顶面贴图缓存指向旧图集，资源重载后必须清掉
        modBus.addListener(RegisterClientReloadListenersEvent.class, event ->
                event.registerReloadListener(BlockTopTextures.reloadListener()));

        BlockChangeHooks.register((level, pos, oldState, newState) -> {
            if (level.isClientSide()) {
                MapCapture.markDirty(level, pos);
            }
        });

        // 区块一加载就排进采集队列：地图跟着视距长，而不是等周期扫描
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Load event) -> {
            if (event.getLevel() instanceof ClientLevel level && event.getChunk() != null) {
                MapCapture.onChunkLoaded(level, event.getChunk().getPos());
            }
        });

        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            Minecraft minecraft = Minecraft.getInstance();
            while (OPEN_MAP.consumeClick()) {
                if (minecraft.level != null && minecraft.player != null && minecraft.screen == null) {
                    minecraft.setScreen(new MapV2Screen());
                }
            }
            while (TOGGLE_COMPASS.consumeClick()) {
                compassVisible = !compassVisible;
            }
            while (MARK.consumeClick()) {
                createMarker(minecraft);
            }
            MapCapture.tick(minecraft);
            MapWorld.tickScreen();
            MarkerClient.tick();
            SlotFlash.tick();
        });

        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Pre event) ->
                CompassOverlay.INSTANCE.render(event.getGuiGraphics(), event.getPartialTick()));

        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) ->
                MapMinimap.render(event.getGuiGraphics()));

        // 标记固定在世界坐标：物品模型 + 名字在 3D 世界空间渲染
        NeoForge.EVENT_BUS.addListener(MarkerWorldRenderer::render);

        // 背包/箱子等容器界面：悬停物品按 Mouse4 → 标记该物品
        NeoForge.EVENT_BUS.addListener((ScreenEvent.MouseButtonPressed.Pre event) -> {
            if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_4) {
                return;
            }
            if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
                return;
            }
            Slot slot = screen.getSlotUnderMouse();
            if (slot == null || !slot.hasItem()) {
                return;
            }
            event.setCanceled(true);
            SlotFlash.flash(screen, slot);
            markSlotItem(slot);
        });

        // 容器界面标记物品后，对应槽位闪一下（JEI 式反馈，画在物品下面）
        NeoForge.EVENT_BUS.addListener((ContainerScreenEvent.Render.Background event) ->
                SlotFlash.render(event));

        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            MapStorage.flushAll();
            MapStorage.clear();
            MarkerClient.reset();
        });
    }

    /** 容器界面里标记悬停槽位的物品。 */
    private static void markSlotItem(Slot slot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) {
            return;
        }

        ResourceLocation icon = BuiltInRegistries.ITEM.getKey(stack.getItem());
        CompoundTag data = new CompoundTag();
        data.putString(MarkerData.KEY_ITEM_ID, icon.toString());
        data.put(MarkerData.KEY_ITEM, stack.save(minecraft.level.registryAccess()));
        Vec3 anchor = MapHooks.INSTANCE.resolveCreate(minecraft.level,
                new Vec3(minecraft.player.getX(), minecraft.player.getY() + 1.0,
                        minecraft.player.getZ()), null, data);
        PacketDistributor.sendToServer(new MarkerCreatePayload(
                MarkerType.ITEM.name(), icon,
                anchor.x, anchor.y, anchor.z,
                -1, slot.index, "item:ui:" + slot.index, data, false));
    }

    private static final double MAX_MARK_DISTANCE = 256.0;

    /**
     * 准星标记：沿视线自己打 256 格射线（不受玩家 reach 限制）。
     * 实体优先于方块；都没命中（天空盒）则不标记。
     */
    private static void createMarker(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null || minecraft.screen != null) {
            return;
        }
        var player = minecraft.player;
        var level = minecraft.level;
        boolean sticky = player.isShiftKeyDown();
        ItemStack main = player.getMainHandItem();
        ResourceLocation icon = main.isEmpty()
                ? ResourceLocation.withDefaultNamespace("nether_star")
                : BuiltInRegistries.ITEM.getKey(main.getItem());

        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(MAX_MARK_DISTANCE));

        EntityHitResult entityHit = findEntity(level, player, eye, end);
        // 扩展包（如 Sable sub-level）的方块不在主世界 Level 里，普通 clip 会穿过去
        HitResult subLevelHit = MapHooks.INSTANCE.raycast(level, eye, end, player);
        BlockHitResult blockHit = level.clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));

        double entityDistance = entityHit == null
                ? Double.MAX_VALUE : entityHit.getLocation().distanceToSqr(eye);
        double blockDistance = blockHit.getType() == HitResult.Type.MISS
                ? Double.MAX_VALUE : blockHit.getLocation().distanceToSqr(eye);

        String type;
        double x;
        double y;
        double z;
        int entityId = -1;
        String targetKey;
        CompoundTag data = new CompoundTag();
        HitResult hit;

        if (entityHit != null && entityDistance <= blockDistance) {
            Entity entity = entityHit.getEntity();
            entityId = entity.getId();
            data.putUUID(MarkerData.KEY_ENTITY_UUID, entity.getUUID());
            hit = entityHit;
            if (entity instanceof ItemEntity itemEntity && !itemEntity.getItem().isEmpty()) {
                // 地上的掉落物：按物品标，带完整 ItemStack（tooltip / 图标 / 数量）
                ItemStack stack = itemEntity.getItem();
                type = MarkerType.ITEM.name();
                x = entity.getX();
                y = entity.getY() + 0.25;
                z = entity.getZ();
                targetKey = "itementity:" + entity.getUUID();
                icon = BuiltInRegistries.ITEM.getKey(stack.getItem());
                data.putString(MarkerData.KEY_ITEM_ID, icon.toString());
                data.put(MarkerData.KEY_ITEM, stack.save(level.registryAccess()));
            } else {
                type = MarkerType.ENTITY.name();
                targetKey = "entity:" + entity.getUUID();
                x = entity.getX();
                y = entity.getY() + entity.getBbHeight() * 0.5;
                z = entity.getZ();
                ResourceLocation typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
                if (typeKey != null) {
                    data.putString(MarkerData.KEY_ENTITY_TYPE, typeKey.toString());
                }
                if (entity instanceof Player playerEntity) {
                    data.putString(MarkerData.KEY_PLAYER_NAME,
                            playerEntity.getGameProfile().getName());
                }
                icon = fallbackEntityIcon(entity.getType());
            }
        } else if (subLevelHit instanceof BlockHitResult subBlock) {
            // 局部坐标；交给 MapHooks.resolveCreate 转世界坐标
            BlockPos pos = subBlock.getBlockPos();
            x = pos.getX() + 0.5;
            y = pos.getY() + 0.5;
            z = pos.getZ() + 0.5;
            targetKey = "block:" + pos.asLong();
            type = MarkerType.PING.name();
            hit = subBlock;
        } else if (blockHit.getType() != HitResult.Type.MISS) {
            BlockPos pos = blockHit.getBlockPos();
            x = pos.getX() + 0.5;
            y = pos.getY() + 0.5;
            z = pos.getZ() + 0.5;
            targetKey = "block:" + pos.asLong();
            if (level.getBlockEntity(pos) instanceof Container) {
                type = MarkerType.CONTAINER.name();
                Block block = level.getBlockState(pos).getBlock();
                icon = BuiltInRegistries.ITEM.getKey(block.asItem());
            } else {
                type = MarkerType.PING.name();
            }
            hit = blockHit;
        } else {
            return; // 天空盒：不标记
        }

        Vec3 anchor = MapHooks.INSTANCE.resolveCreate(level, new Vec3(x, y, z), hit, data);
        PacketDistributor.sendToServer(new MarkerCreatePayload(type, icon,
                anchor.x, anchor.y, anchor.z,
                entityId, -1, targetKey, data, sticky));
    }

    /** 头像画不出来时的兜底图标：刷怪蛋 → 玩家头 → 下界之星。 */
    private static ResourceLocation fallbackEntityIcon(EntityType<?> type) {
        SpawnEggItem egg = SpawnEggItem.byId(type);
        if (egg != null) {
            return BuiltInRegistries.ITEM.getKey(egg);
        }
        if (type == EntityType.PLAYER) {
            return ResourceLocation.withDefaultNamespace("player_head");
        }
        return ResourceLocation.withDefaultNamespace("nether_star");
    }

    /**
     * 射线与实体 AABB 相交，取最近的一个。
     * <p>1.21.1 里 {@code ItemEntity.isPickable()} 是 false（掉落物不能被打），
     * 但标记要能选中它，所以这里单独放行。
     */
    private static EntityHitResult findEntity(ClientLevel level, Entity source, Vec3 from, Vec3 to) {
        AABB box = new AABB(from, to).inflate(1.0);
        EntityHitResult best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : level.getEntities(source, box,
                candidate -> candidate != source
                        && (candidate.isPickable() || candidate instanceof ItemEntity))) {
            Optional<Vec3> hit = entity.getBoundingBox().inflate(0.3).clip(from, to);
            if (hit.isEmpty()) {
                continue;
            }
            double distance = hit.get().distanceToSqr(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new EntityHitResult(entity, hit.get());
            }
        }
        return best;
    }
}
