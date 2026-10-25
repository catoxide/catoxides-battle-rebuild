package com.catoxide.catoxidesbattlerebuild.core.event;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 方块变化广播 —— 补 NeoForge 缺失的「客户端方块变化」事件。
 *
 * <p>纯基底：一个注册表 + 委托，不含任何业务语义。由 {@code LevelChunkMixin} 在
 * {@code setBlockState} 真正改变方块后调用。
 *
 * <p>为什么放 core：mixin 方法体不得引用包类（会实崩 {@code IllegalClassLoadError}），
 * 所以 mixin 只能调用 core 里的这个类，监听者由包注册。
 *
 * <p>R3：没有监听者时 {@link #fire} 立即返回。
 */
public final class BlockChangeHooks {

    @FunctionalInterface
    public interface Listener {
        void onBlockChanged(Level level, BlockPos pos, BlockState oldState, BlockState newState);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private BlockChangeHooks() {
    }

    public static void register(Listener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static boolean isEmpty() {
        return LISTENERS.isEmpty();
    }

    /** 监听器抛异常只吞掉，绝不打断方块更新。 */
    public static void fire(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (LISTENERS.isEmpty()) {
            return;
        }
        for (Listener listener : LISTENERS) {
            try {
                listener.onBlockChanged(level, pos, oldState, newState);
            } catch (Exception ignored) {
                // 旁路功能不能影响世界更新
            }
        }
    }
}
