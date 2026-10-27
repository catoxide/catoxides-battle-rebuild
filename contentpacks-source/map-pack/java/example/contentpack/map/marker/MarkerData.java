package example.contentpack.map.marker;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * {@code marker.data} 的键与读取规则。
 *
 * <p>纯逻辑、不碰客户端类，所以启动自检可以直接断言；渲染侧只按这里的规则取图标/头像。
 */
public final class MarkerData {

    public static final String KEY_LABEL = "label";
    /** 完整 ItemStack NBT（含组件），用于原版 tooltip 和物品图标。 */
    public static final String KEY_ITEM = "item";
    public static final String KEY_ITEM_ID = "itemId";
    /** 实体标记：EntityType id，用于画生物头像。 */
    public static final String KEY_ENTITY_TYPE = "entityType";
    public static final String KEY_ENTITY_UUID = "entityUuid";
    /** 玩家实体标记：玩家名，离线时按名字拉皮肤。 */
    public static final String KEY_PLAYER_NAME = "playerName";
    /** 玩家在编辑屏手动换过图标后置 true，物品/生物图标不再覆盖。 */
    public static final String KEY_ICON_OVERRIDE = "iconOverride";

    private MarkerData() {
    }

    public static boolean hasItem(MarkerDto marker) {
        return hasItem(marker.data());
    }

    public static boolean hasItem(CompoundTag data) {
        return data != null
                && data.contains(KEY_ITEM, Tag.TAG_COMPOUND)
                && !data.getCompound(KEY_ITEM).isEmpty();
    }

    public static boolean iconOverride(MarkerDto marker) {
        return marker.data().getBoolean(KEY_ICON_OVERRIDE);
    }

    /** 该画什么生物头像；null = 不画（非实体标记，或玩家已手动换图标）。 */
    @Nullable
    public static ResourceLocation portraitType(MarkerDto marker) {
        if (iconOverride(marker)) {
            return null;
        }
        String id = marker.data().getString(KEY_ENTITY_TYPE);
        return id.isEmpty() ? null : ResourceLocation.tryParse(id);
    }

    /** 实体 UUID；没有则 null（老标记 / 非实体标记）。 */
    @Nullable
    public static UUID entityUuid(MarkerDto marker) {
        CompoundTag data = marker.data();
        return data.contains(KEY_ENTITY_UUID) ? data.getUUID(KEY_ENTITY_UUID) : null;
    }

    public static String playerName(MarkerDto marker) {
        return marker.data().getString(KEY_PLAYER_NAME);
    }

    /** 玩家实体标记且没有手动改图标 → 用皮肤头像。 */
    public static boolean isPlayerPortrait(MarkerDto marker) {
        ResourceLocation type = portraitType(marker);
        return type != null && "minecraft".equals(type.getNamespace())
                && "player".equals(type.getPath());
    }

    /** 还原物品栈；没有完整物品数据或解析失败返回 {@link ItemStack#EMPTY}。 */
    public static ItemStack readItem(MarkerDto marker, HolderLookup.Provider registries) {
        if (registries == null || !hasItem(marker)) {
            return ItemStack.EMPTY;
        }
        return ItemStack.parseOptional(registries, marker.data().getCompound(KEY_ITEM));
    }
}
