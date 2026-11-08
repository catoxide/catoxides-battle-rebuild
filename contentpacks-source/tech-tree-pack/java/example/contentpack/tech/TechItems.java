package example.contentpack.tech;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/** 物品 id 解析（数据加载时注册表尚未填充，只能运行期解析）。 */
public final class TechItems {

    private TechItems() {
    }

    public static Item resolve(ResourceLocation id) {
        return id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }
}
