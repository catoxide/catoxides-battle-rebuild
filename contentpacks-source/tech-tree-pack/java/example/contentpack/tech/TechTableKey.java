package example.contentpack.tech;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 研究台身份 = 维度 + 方块坐标。
 * <p>研究点、已解锁节点、解锁时刻与研究冷却都以它为键；台子被拆除后原地重放会继承这些数据，
 * 换位置则是另一张台子。
 */
public record TechTableKey(ResourceLocation dimension, BlockPos pos) {

    public static TechTableKey of(Level level, BlockPos pos) {
        return new TechTableKey(level.dimension().location(), pos.immutable());
    }
}
