package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.debug.DebugWorldBlockHider;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DebugLevelSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 调试世界里隐藏 {@link DebugWorldBlockHider} 列出的方块（返回空气，格子位置不变）。
 */
@Mixin(DebugLevelSource.class)
public abstract class DebugLevelSourceMixin {

    @Inject(method = "getBlockStateFor", at = @At("RETURN"), cancellable = true)
    private static void catoxides$hideBrokenStates(int x, int z, CallbackInfoReturnable<BlockState> cir) {
        if (DebugWorldBlockHider.isHidden(cir.getReturnValue())) {
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
        }
    }
}
