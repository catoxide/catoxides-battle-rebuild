// ModularTransformPipeline.java - 修改为单例模式
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 模块化变换管道 - 管理所有变换模块的执行顺序（单例模式）
 */
public class ModularTransformPipeline {
    private static ModularTransformPipeline instance;

    private final List<TransformModule> modules = new ArrayList<>();
    private boolean pipelineEnabled = true;

    public ModularTransformPipeline() {
        // 使用新的变换顺序
        modules.add(new ScaleModule());      // 第一步：缩放
        modules.add(new PivotModule());      // 第二步：枢轴点平移
        modules.add(new BoneRotationModule());   // 第三步：旋转（骨骼动画 + 实体旋转）
        modules.add(new PositionModule());   // 第四步：位置和坐标转换
    }

    /**
     * 获取单例实例
     */
    public static ModularTransformPipeline getInstance() {
        if (instance == null) {
            instance = new ModularTransformPipeline();
        }
        return instance;
    }

    // === 新增调试控制方法 ===

    /**
     * 一键跳过位置变换
     */
    public void skipPosition() {
        setModuleEnabled("Position", false);
        System.out.println("🔧 跳过位置变换");
    }

    /**
     * 一键跳过旋转变换
     */
    public void skipRotation() {
        setModuleEnabled("Rotation", false);
        System.out.println("🔧 跳过旋转变换");
    }

    /**
     * 一键跳过枢轴点变换
     */
    public void skipPivot() {
        setModuleEnabled("Pivot", false);
        System.out.println("🔧 跳过枢轴点变换");
    }

    /**
     * 一键跳过缩放变换
     */
    public void skipScale() {
        setModuleEnabled("Scale", false);
        System.out.println("🔧 跳过缩放变换");
    }

    /**
     * 跳过所有变换（返回原始坐标）
     */
    public void skipAll() {
        setPipelineEnabled(false);
        System.out.println("🔧 跳过所有变换 - 返回原始坐标");
    }

    /**
     * 启用所有变换
     */
    public void enableAll() {
        setPipelineEnabled(true);
        modules.forEach(module -> module.setEnabled(true));
        System.out.println("🔧 启用所有变换");
    }

    /**
     * 重置为默认状态（启用所有变换）
     */
    public void reset() {
        enableAll();
    }

    /**
     * 获取当前变换状态摘要
     */
    public String getStatusSummary() {
        if (!pipelineEnabled) {
            return "🚫 所有变换已跳过 - 使用原始坐标";
        }

        List<String> enabledModules = new ArrayList<>();
        List<String> disabledModules = new ArrayList<>();

        for (TransformModule module : modules) {
            if (module.isEnabled()) {
                enabledModules.add(module.getModuleName());
            } else {
                disabledModules.add(module.getModuleName());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("✅ 启用: ").append(String.join(", ", enabledModules));
        if (!disabledModules.isEmpty()) {
            sb.append(" | ❌ 禁用: ").append(String.join(", ", disabledModules));
        }

        return sb.toString();
    }

    // === 原有方法保持不变 ===

    /**
     * 执行完整的变换管道
     */
    public Vec3 transformVertex(Vertex vertex, BoneTransform transform, float[] pivot) {
        if (!pipelineEnabled) {
            return new Vec3(vertex.x / 16.0, vertex.y / 16.0, vertex.z / 16.0);
        }

        TransformContext context = new TransformContext(vertex, transform, pivot);

        for (TransformModule module : modules) {
            if (module.isEnabled()) {
                context.currentPosition = module.process(context);
            }
        }

        return context.currentPosition;
    }

    /**
     * 获取指定模块
     */
    public TransformModule getModule(String moduleName) {
        return modules.stream()
                .filter(module -> module.getModuleName().equals(moduleName))
                .findFirst()
                .orElse(null);
    }

    /**
     * 启用/禁用整个管道
     */
    public void setPipelineEnabled(boolean enabled) {
        this.pipelineEnabled = enabled;
    }

    /**
     * 启用/禁用特定模块
     */
    public void setModuleEnabled(String moduleName, boolean enabled) {
        TransformModule module = getModule(moduleName);
        if (module != null) {
            module.setEnabled(enabled);
        }
    }

    /**
     * 一键跳过所有变换
     */
    public void skipAllTransforms() {
        setPipelineEnabled(false);
    }

    /**
     * 启用所有变换
     */
    public void enableAllTransforms() {
        setPipelineEnabled(true);
        modules.forEach(module -> module.setEnabled(true));
    }

    /**
     * 获取调试信息
     */
    public String getDebugInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Transform Pipeline: ").append(pipelineEnabled ? "ENABLED" : "DISABLED").append("\n");

        for (TransformModule module : modules) {
            sb.append("  ").append(module.getModuleName())
                    .append(": ").append(module.isEnabled() ? "ON" : "OFF")
                    .append("\n");
        }

        return sb.toString();
    }

}