package com.catoxide.catoxidesbattlerebuild.core.registry;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * 通用内容方块实体（批量注册 {@code blockEntity.type = "generic"} 时的默认载体）
 * <p>
 * 职责只有一个：<b>为方块挂一个可读写、可存档、可同步的 NBT 槽位</b>。
 * 内容包需要行为时写自己的 {@code BlockEntity} 子类
 * （{@code blockEntity.type = "custom"}），本类不承载任何机制（R2：机制住包）。
 *
 * <p>存档格式：自身 NBT 下的 {@code Data} compound（包内结构由包自定义）。
 * {@link #getUpdateTag} 会把 Data 一并同步给客户端（客户端 BE 可直接读）。
 */
public class GenericContentBlockEntity extends BlockEntity {

    public static final String TAG_DATA = "Data";

    @Nullable
    private CompoundTag extraData;

    public GenericContentBlockEntity(
            BlockEntityType<? extends GenericContentBlockEntity> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Nullable
    public CompoundTag getExtraData() {
        return extraData;
    }

    /** 设置数据槽（null = 清空）。修改后需自行安排同步（setChanged 等由使用方决定）。 */
    public void setExtraData(@Nullable CompoundTag data) {
        this.extraData = data;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (extraData != null) {
            tag.put(TAG_DATA, extraData.copy());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        extraData = tag.get(TAG_DATA) instanceof CompoundTag c ? c : null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        if (extraData != null) {
            tag.put(TAG_DATA, extraData.copy());
        }
        return tag;
    }
}
