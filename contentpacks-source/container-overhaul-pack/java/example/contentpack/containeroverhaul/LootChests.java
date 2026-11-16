package example.contentpack.containeroverhaul;

import com.catoxide.catoxidesbattlerebuild.core.container.ICompoundContainerAccess;
import com.catoxide.catoxidesbattlerebuild.core.container.IContainerPolicy;
import com.mojang.serialization.Codec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import javax.annotation.Nullable;

/**
 * 战利品箱（箱子/木桶）的锁定规则。
 *
 * <p>「战利品箱」= 生成时带 {@code LootTable} 的箱子/木桶。原版在第一次打开时
 * 会把战利品表清空（{@code RandomizableContainer#unpackLootTable}），之后就无法再从
 * 战利品表判断身份；所以玩家右键时给方块实体打一个持久标记，标记随方块实体存档。
 *
 * <p>锁定内容：
 * <ul>
 *   <li>玩家/爆炸不能拆掉；</li>
 *   <li>任何路径（GUI/漏斗/管道）不能放入，但仍可取出（战利品可被取走）。</li>
 * </ul>
 *
 * <p>标记只落在箱子/木桶上；结构里的漏斗/发射器等带战利品表的容器不受影响。
 */
public final class LootChests implements IContainerPolicy {

    public static final IContainerPolicy POLICY = new LootChests();

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, ContainerOverhaulPack.ID);

    private static final DeferredHolder<AttachmentType<?>, AttachmentType<Boolean>> LOOT_CHEST =
            ATTACHMENTS.register("loot_chest", LootChests::createMarkerType);

    private LootChests() {}

    private static AttachmentType<Boolean> createMarkerType() {
        return AttachmentType.<Boolean>builder(() -> Boolean.FALSE)
                .serialize(Codec.BOOL)
                .sync(ByteBufCodecs.BOOL)
                .build();
    }

    public static void registerAttachmentType(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
    }

    public static void installEventListeners() {
        NeoForge.EVENT_BUS.addListener(LootChests::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(LootChests::onBreak);
        NeoForge.EVENT_BUS.addListener(LootChests::onExplode);
    }

    @Override
    public boolean blocksInsertion(Container container, int slot, ItemStack stack) {
        return isLootChest(container);
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide) {
            return;
        }
        markIfStillLootChest(level.getBlockEntity(event.getPos()));
    }

    private static void onBreak(BlockEvent.BreakEvent event) {
        if (isLootChestBlock(event.getLevel().getBlockEntity(event.getPos()))) {
            event.setCanceled(true);
        }
    }

    private static void onExplode(ExplosionEvent.Detonate event) {
        event.getAffectedBlocks().removeIf(pos -> isLootChestBlock(event.getLevel().getBlockEntity(pos)));
    }

    private static void markIfStillLootChest(@Nullable BlockEntity be) {
        if (!isLootChestBlock(be)) {
            return;
        }
        if (((RandomizableContainer) be).getLootTable() == null) {
            return;
        }
        be.setData(LOOT_CHEST.get(), Boolean.TRUE);
        be.setChanged();
    }

    private static boolean isLootChest(@Nullable Container container) {
        if (container instanceof ICompoundContainerAccess compound) {
            return isLootChestContainer(compound.catoxides$firstHalf())
                    || isLootChestContainer(compound.catoxides$secondHalf());
        }
        return isLootChestContainer(container);
    }

    private static boolean isLootChestContainer(@Nullable Container container) {
        return container instanceof BlockEntity be && isLootChestBlock(be);
    }

    private static boolean isLootChestBlock(@Nullable BlockEntity be) {
        if (!(be instanceof ChestBlockEntity) && !(be instanceof BarrelBlockEntity)) {
            return false;
        }
        if (Boolean.TRUE.equals(be.getExistingDataOrNull(LOOT_CHEST.get()))) {
            return true;
        }
        return be instanceof RandomizableContainer randomizable && randomizable.getLootTable() != null;
    }
}
