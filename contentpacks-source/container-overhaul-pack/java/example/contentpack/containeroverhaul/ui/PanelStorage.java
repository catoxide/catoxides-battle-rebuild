package example.contentpack.containeroverhaul.ui;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 侧面板内容的世界存档（服务端权威，按玩家 UUID 存）。
 *
 * <p>现在只承担两件事：升级槽的落盘（45–48，由 {@link Upgrades} 的监听器写入），
 * 以及旧 45 格储物（0–44）的迁移来源 —— 悬浮物品栏本身用的是玩家真实的 {@code Inventory}，
 * 不再有独立储物空间。旧存档读进来后由 {@link InventoryMigration} 尽量搬空；搬不动的留在这里，
 * 等 {@code /containeroverhaul sidepanel recover}。
 */
public final class PanelStorage extends SavedData {

    private static final String DATA_NAME = "container_overhaul_side_panels";
    private static final String KEY = "panels";

    private final Map<UUID, SimpleContainer> containers = new HashMap<>();

    private PanelStorage() {}

    public static PanelStorage get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PanelStorage::new, PanelStorage::load, DataFixTypes.LEVEL),
                DATA_NAME);
    }

    public static SimpleContainer containerFor(Player player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            throw new IllegalStateException("side panel storage requested without a server");
        }
        PanelStorage data = get(server);
        return data.containers.computeIfAbsent(player.getUUID(), id -> {
            SimpleContainer container = new SimpleContainer(SidePanel.COUNT);
            container.addListener(ignored -> data.setDirty());
            return container;
        });
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag all = new CompoundTag();
        for (Map.Entry<UUID, SimpleContainer> entry : this.containers.entrySet()) {
            all.put(entry.getKey().toString(),
                    ContainerHelper.saveAllItems(new CompoundTag(), entry.getValue().getItems(), registries));
        }
        tag.put(KEY, all);
        return tag;
    }

    private static PanelStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        PanelStorage data = new PanelStorage();
        CompoundTag all = tag.getCompound(KEY);
        for (String key : all.getAllKeys()) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            SimpleContainer container = new SimpleContainer(SidePanel.COUNT);
            ContainerHelper.loadAllItems(all.getCompound(key), container.getItems(), registries);
            container.addListener(ignored -> data.setDirty());
            data.containers.put(id, container);
        }
        return data;
    }
}
