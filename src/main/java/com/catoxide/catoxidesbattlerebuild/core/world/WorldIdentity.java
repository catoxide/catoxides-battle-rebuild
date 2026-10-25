package com.catoxide.catoxidesbattlerebuild.core.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.UUID;

/**
 * 世界身份：每个存档生成一次 UUID，永久保存在该存档里。
 *
 * <p>任何「按世界区分数据」的功能（地图、任务、统计、共享地图…）都应该用这个 ID 做命名空间，
 * 而不是用服务器地址或存档名：
 * <ul>
 *   <li>新建世界 → 新 UUID，不会读到别的存档的数据；</li>
 *   <li>服务器换 IP / 换域名 / 迁移机器 → UUID 不变，客户端仍能认出同一份数据；</li>
 *   <li>复制/备份存档 → UUID 跟着走。</li>
 * </ul>
 *
 * <p>纯服务端能力；客户端要拿需要走网络（见各包的 payload）。
 */
public final class WorldIdentity {

    private static final String DATA_NAME = "catoxidesbattlerebuild_world_identity";
    private static final String KEY = "worldId";

    private WorldIdentity() {
    }

    /** 取当前世界的身份；不存在时生成并标记落盘。 */
    public static UUID get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(Data::new, Data::load, DataFixTypes.SAVED_DATA_MAP_DATA),
                DATA_NAME).id;
    }

    private static final class Data extends SavedData {

        private final UUID id;

        private Data() {
            this.id = UUID.randomUUID();
            // 新建必须标脏，否则重启后会重新生成一个 UUID，旧数据就找不回来了
            setDirty();
        }

        private Data(UUID id) {
            this.id = id;
        }

        private static Data load(CompoundTag tag, HolderLookup.Provider registries) {
            return new Data(tag.hasUUID(KEY) ? tag.getUUID(KEY) : UUID.randomUUID());
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putUUID(KEY, id);
            return tag;
        }
    }
}
