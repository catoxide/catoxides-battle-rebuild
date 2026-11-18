package example.contentpack.containeroverhaul;

import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * 掉落物寿命：45s（900 tick）。
 *
 * <p>原版 {@code ItemEntity#lifespan} 在构造时按物品取值、默认 6000 tick。
 * 实体每次加入世界（含读档重载）都在服务端改写成 900；寿命本身会随实体存档，
 * 所以读档后不需要重复干预。
 */
final class DroppedItemLifetime {

    static final int DESPAWN_TICKS = 45 * 20;

    private DroppedItemLifetime() {}

    static void install() {
        NeoForge.EVENT_BUS.addListener(DroppedItemLifetime::onEntityJoinLevel);
    }

    private static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof ItemEntity item) {
            item.lifespan = DESPAWN_TICKS;
        }
    }
}
