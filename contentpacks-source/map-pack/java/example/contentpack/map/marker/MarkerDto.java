package example.contentpack.map.marker;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 一个标记。
 *
 * <p>核心字段固定；{@link #data} 是扩展载荷（键见 {@link MarkerData}），按 {@link #type} 解释：
 * <ul>
 *   <li>ITEM：{@code item} = 完整 ItemStack NBT、{@code itemId}；{@link #slot} = 槽位</li>
 *   <li>ENTITY：{@code entityType} = EntityType id（画生物头像）、{@code entityUuid}</li>
 *   <li>CONTAINER：{@code pos} = 容器方块坐标（x/y/z 已存核心字段）</li>
 *   <li>通用：{@code label}、{@code iconOverride}</li>
 * </ul>
 *
 * <p>{@code targetKey} 用于去重：同 owner、同 type、同 targetKey 重复标记时刷新而不是新建。
 * {@code expiresAt = 0} 表示不走时间过期（按类型条件清理）。
 */
public record MarkerDto(UUID id, UUID owner, MarkerType type, ResourceLocation icon,
                        ResourceLocation dimension, double x, double y, double z,
                        int entityId, int slot, String targetKey, CompoundTag data,
                        long createdAt, long expiresAt) {

    public MarkerDto {
        data = data == null ? new CompoundTag() : data;
        targetKey = targetKey == null ? "" : targetKey;
    }

    public boolean timed() {
        return type.timed();
    }

    public boolean expired(long now) {
        return expiresAt > 0 && now > expiresAt;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("owner", owner);
        tag.putString("type", type.name());
        tag.putString("icon", icon.toString());
        tag.putString("dimension", dimension.toString());
        tag.putDouble("x", x);
        tag.putDouble("y", y);
        tag.putDouble("z", z);
        tag.putInt("entityId", entityId);
        tag.putInt("slot", slot);
        tag.putString("targetKey", targetKey);
        tag.put("data", data.copy());
        tag.putLong("createdAt", createdAt);
        tag.putLong("expiresAt", expiresAt);
        return tag;
    }

    public static MarkerDto load(CompoundTag tag, HolderLookup.Provider registries) {
        return new MarkerDto(
                tag.getUUID("id"),
                tag.getUUID("owner"),
                MarkerType.byName(tag.getString("type")),
                ResourceLocation.parse(tag.getString("icon")),
                ResourceLocation.parse(tag.getString("dimension")),
                tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"),
                tag.getInt("entityId"),
                tag.getInt("slot"),
                tag.getString("targetKey"),
                tag.getCompound("data"),
                tag.getLong("createdAt"), tag.getLong("expiresAt"));
    }
}
