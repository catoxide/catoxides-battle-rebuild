// DebugManager.java - 增强版
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem.debug;

/**
 * 调试管理器 - 所有调试功能的总入口
 */
public class DebugManager {
    private static final SkipSystem skipSystem = new SkipSystem();

    // === 跳过系统访问 ===
    public static SkipSystem getSkipSystem() { return skipSystem; }

    // === 便捷调试方法 ===

    /**
     * 一键调试位置问题
     */
    public static void debugPosition() {
        skipSystem.setEnabled(true);
        skipSystem.reset();
        skipSystem.skipPosition(true);
        System.out.println("🔧 调试位置问题：跳过位置变换");
    }

    /**
     * 一键调试旋转问题
     */
    public static void debugRotation() {
        skipSystem.setEnabled(true);
        skipSystem.reset();
        skipSystem.skipRotation(true);
        System.out.println("🔧 调试旋转问题：跳过旋转变换");
    }

    /**
     * 一键调试枢轴点问题
     */
    public static void debugPivot() {
        skipSystem.setEnabled(true);
        skipSystem.reset();
        skipSystem.skipPivot(true);
        System.out.println("🔧 调试枢轴点问题：跳过枢轴点变换");
    }

    /**
     * 一键跳过所有动画
     */
    public static void debugAllAnimations() {
        skipSystem.setEnabled(true);
        skipSystem.reset();
        skipSystem.skipAnimation(true);
        System.out.println("🔧 调试动画问题：跳过所有动画变换");
    }

    /**
     * 禁用所有调试
     */
    public static void disableAll() {
        skipSystem.setEnabled(false);
        System.out.println("🔧 所有调试已禁用");
    }

    /**
     * 获取完整调试状态
     */
    public static String getFullDebugStatus() {
        return "=== 调试系统状态 ===\n" +
                skipSystem.getDebugInfo() + "\n" +
                "使用 DebugManager.debugXXX() 快速启用特定调试";
    }

    // === 性能分析（未来扩展）===
    public static void startProfiling(String section) {
        // 未来实现性能分析
    }

    public static void endProfiling(String section) {
        // 未来实现性能分析
    }
}