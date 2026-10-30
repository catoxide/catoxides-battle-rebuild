package example.contentpack.mapv2.marker;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 标记的世界存档（服务端权威，持久化）。 */
public final class MarkerSavedData extends SavedData {

    private static final String DATA_NAME = "catoxidesbattlerebuild_markers";
    private static final String KEY = "markers";

    private final List<MarkerDto> markers = new ArrayList<>();

    private MarkerSavedData() {
    }

    public static MarkerSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(MarkerSavedData::new, MarkerSavedData::load,
                        DataFixTypes.SAVED_DATA_MAP_DATA),
                DATA_NAME);
    }

    public List<MarkerDto> markers() {
        return markers;
    }

    public void add(MarkerDto marker) {
        markers.add(marker);
        setDirty();
    }

    /** 刷新已有标记（去重后延长/更新）。 */
    public void replace(MarkerDto marker) {
        for (int i = 0; i < markers.size(); i++) {
            if (markers.get(i).id().equals(marker.id())) {
                markers.set(i, marker);
                setDirty();
                return;
            }
        }
    }

    public boolean remove(UUID id) {
        boolean removed = markers.removeIf(marker -> marker.id().equals(id));
        if (removed) {
            setDirty();
        }
        return removed;
    }

    public void removeExpired(long now) {
        if (markers.removeIf(marker -> marker.expired(now))) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (MarkerDto marker : markers) {
            list.add(marker.save());
        }
        tag.put(KEY, list);
        return tag;
    }

    private static MarkerSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        MarkerSavedData data = new MarkerSavedData();
        ListTag list = tag.getList(KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            data.markers.add(MarkerDto.load(list.getCompound(i), registries));
        }
        return data;
    }
}
