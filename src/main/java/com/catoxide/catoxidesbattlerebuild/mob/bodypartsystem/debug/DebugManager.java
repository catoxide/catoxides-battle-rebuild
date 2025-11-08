// DebugManager.java
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem.debug;

/**
 * 调试管理器 - 集中管理所有调试工具
 */
public class DebugManager {
    private static final SkipSystem skipSystem = new SkipSystem();

    /**
     * 获取跳过系统实例
     */
    public static SkipSystem getSkipSystem() {
        return skipSystem;
    }

    /**
     * 快速启用位置跳过（便捷方法）
     */
    public static void debugPositionIssue() {
        skipSystem.setEnabled(true);
        skipSystem.reset();
        skipSystem.skipPosition(true);
        System.out.println("调试位置问题：跳过位置变换");
    }

    /**
     * 快速启用枢轴点跳过（便捷方法）
     */
    public static void debugPivotIssue() {
        skipSystem.setEnabled(true);
        skipSystem.reset();
        skipSystem.skipPivot(true);
        System.out.println("调试枢轴点问题：跳过枢轴点变换");
    }

    /**
     * 快速启用所有跳过（便捷方法）
     */
    public static void debugAllTransforms() {
        skipSystem.setEnabled(true);
        skipSystem.reset();
        skipSystem.skipAnimation(true);
        System.out.println("调试所有变换：跳过所有动画变换");
    }

    /**
     * 禁用所有调试
     */
    public static void disableAll() {
        skipSystem.setEnabled(false);
        skipSystem.reset();
        System.out.println("所有调试已禁用");
    }

    /**
     * 获取调试状态信息
     */
    public static String getDebugStatus() {
        return skipSystem.getDebugInfo();
    }
}