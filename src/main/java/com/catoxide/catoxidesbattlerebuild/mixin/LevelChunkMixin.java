package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.event.BlockChangeHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 方块变化通知（仅查询 + 委托，无机制逻辑）。
 *
 * <p>{@code LevelChunk#setBlockState} 是客户端（方块更新包）和服务端（放置/破坏/活塞/爆炸/流体）
 * 的共同终点。生成期的 {@code ProtoChunk} 不是 {@code LevelChunk}，所以不会在世界生成时刷屏。
 *
 * <p>R3：{@link BlockChangeHooks} 没有监听者时 {@code fire} 直接返回。
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void catoxides$notifyMapHooks(BlockPos pos, BlockState state, boolean isMoving,
                                          CallbackInfoReturnable<BlockState> cir) {
        if (BlockChangeHooks.isEmpty()) {
            return;
        }
        BlockState oldState = cir.getReturnValue();
        if (oldState == state) {
            return; // 没有实际变化
        }
        LevelChunk self = (LevelChunk) (Object) this;
        Level level = self.getLevel();
        if (level != null) {
            BlockChangeHooks.fire(level, pos.immutable(), oldState, state);
        }
    }
}
