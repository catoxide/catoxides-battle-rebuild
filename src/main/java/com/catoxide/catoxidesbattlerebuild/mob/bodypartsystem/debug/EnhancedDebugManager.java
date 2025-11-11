// EnhancedDebugManager.java - 增强版调试管理器
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem.debug;

import com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem.ModularTransformPipeline;
import com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem.Vertex;
import com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem.BoneTransform;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 增强版调试管理器 - 支持模块化跳过（适配单例模式）
 */
public class EnhancedDebugManager {

    // === 核心调试方法（使用单例模式）===

    public static void skipPosition() {
        ModularTransformPipeline.getInstance().skipPosition();
        printStatus();
    }

    public static void skipRotation() {
        ModularTransformPipeline.getInstance().skipRotation();
        printStatus();
    }

    public static void skipPivot() {
        ModularTransformPipeline.getInstance().skipPivot();
        printStatus();
    }

    public static void skipScale() {
        ModularTransformPipeline.getInstance().skipScale();
        printStatus();
    }

    public static void skipAll() {
        ModularTransformPipeline.getInstance().skipAll();
        printStatus();
    }

    public static void enableAll() {
        ModularTransformPipeline.getInstance().enableAll();
        printStatus();
    }

    public static void reset() {
        ModularTransformPipeline.getInstance().reset();
        printStatus();
    }

    // === 快捷调试预设 ===

    public static void debugPositionIssues() {
        enableAll();
        skipPosition();
    }

    public static void debugRotationIssues() {
        enableAll();
        skipRotation();
    }

    public static void debugPivotIssues() {
        enableAll();
        skipPivot();
    }

    public static void debugScaleIssues() {
        enableAll();
        skipScale();
    }

    public static void debugBasicPosition() {
        // 只保留最基本的位置计算
        skipAll();
        ModularTransformPipeline.getInstance().setPipelineEnabled(true);
        ModularTransformPipeline.getInstance().setModuleEnabled("Position", true);
        printStatus();
    }

    // === 状态查询 ===

    public static String getStatus() {
        return ModularTransformPipeline.getInstance().getStatusSummary();
    }

    public static void printStatus() {
        System.out.println("🔧 变换状态: " + getStatus());
    }

    public static void printDetailedStatus() {
        System.out.println(ModularTransformPipeline.getInstance().getDebugInfo());
    }

    // === 新增：管道测试功能 ===

    public static void testPipeline() {
        ModularTransformPipeline pipeline = ModularTransformPipeline.getInstance();

        System.out.println("🧪 开始测试变换管道...");

        // 创建一个测试顶点
        Vertex testVertex = new Vertex(8, 16, 0);
        BoneTransform testTransform = new BoneTransform(
                new Vec3(10, 20, 30),
                new Quaternionf(),
                new Vector3f(1, 1, 1)
        );
        float[] testPivot = new float[]{0, 8, 0};

        // 执行测试变换
        Vec3 result = pipeline.transformVertex(testVertex, testTransform, testPivot);

        System.out.println("🧪 测试结果:");
        System.out.println("   输入顶点: " + testVertex);
        System.out.println("   输出位置: " + result);
        System.out.println("   管道状态: " + pipeline.getStatusSummary());
    }

    // === 新增：模块状态控制 ===

    public static void enableModule(String moduleName) {
        ModularTransformPipeline.getInstance().setModuleEnabled(moduleName, true);
        System.out.println("✅ 启用模块: " + moduleName);
        printStatus();
    }

    public static void disableModule(String moduleName) {
        ModularTransformPipeline.getInstance().setModuleEnabled(moduleName, false);
        System.out.println("❌ 禁用模块: " + moduleName);
        printStatus();
    }

    public static void toggleModule(String moduleName) {
        ModularTransformPipeline pipeline = ModularTransformPipeline.getInstance();
        boolean currentState = pipeline.getModule(moduleName).isEnabled();
        pipeline.setModuleEnabled(moduleName, !currentState);
        System.out.println("🔄 " + moduleName + "模块: " + (!currentState ? "启用" : "禁用"));
        printStatus();
    }
}