//package com.catoxide.catoxidesbattlerebuild.server.examples;
//
//import com.catoxide.catoxidesbattlerebuild.server.entities.*;
//import com.catoxide.catoxidesbattlerebuild.server.collision.AdvancedCollisionDetector;
//import com.catoxide.catoxidesbattlerebuild.server.integration.BoneHitboxIntegration;
//import net.minecraft.resources.ResourceLocation;
//import net.minecraft.world.phys.Vec3;
//
//import java.util.HashMap;
//import java.util.Map;
//
///**
// * 完整系统使用示例
// * 演示如何在实际项目中使用新的骨骼受击盒系统
// */
//public class CompleteSystemUsageExample {
//
//    public static void main(String[] args) {
//        // 初始化系统
//        initializeSystems();
//
//        // 创建示例实体
//        createExampleEntity();
//
//        // 演示碰撞检测
//        demonstrateCollisionDetection();
//
//        // 演示击中处理
//        demonstrateHitHandling();
//    }
//
//    /**
//     * 初始化系统
//     */
//    public static void initializeSystems() {
//        System.out.println("=== 初始化骨骼受击盒系统 ===");
//
//        // 系统会自动初始化，这里仅作演示
//        EntityBoneHitboxSystem hitboxSystem = EntityBoneHitboxSystem.getInstance();
//        BoneCollectionFactory factory = new BoneCollectionFactory();
//        AdvancedCollisionDetector collisionDetector = AdvancedCollisionDetector.getInstance();
//
//        // 注册一些默认的受击盒配置模板
//        registerHitboxTemplates();
//
//        System.out.println("系统初始化完成");
//    }
//
//    /**
//     * 注册受击盒配置模板
//     */
//    private static void registerHitboxTemplates() {
//        // 头部模板 - 高伤害倍率，暴击区域
//        HitboxComponent.registerConfigTemplate("head", new HitboxConfigTemplate(
//            "head",
//            2.0f,  // 2倍伤害
//            true,  // 暴击区域
//            0.0f,  // 无护甲
//            ResourceLocation.fromNamespaceAndPath("minecraft", "entity.player.hurt"),
//            "head",
//            java.util.List.of("knockback")
//        ));
//
//        // 躯干模板 - 标准伤害
//        HitboxComponent.registerConfigTemplate("torso", new HitboxConfigTemplate(
//            "torso",
//            1.0f,  // 标准伤害
//            false, // 非暴击区域
//            0.2f,  // 20%伤害减免
//            ResourceLocation.fromNamespaceAndPath("minecraft", "entity.generic.hurt"),
//            "torso",
//            java.util.List.of()
//        ));
//
//        // 四肢模板 - 低伤害
//        HitboxComponent.registerConfigTemplate("limb", new HitboxConfigTemplate(
//            "limb",
//            0.5f,  // 50%伤害
//            false, // 非暴击区域
//            0.1f,  // 10%伤害减免
//            ResourceLocation.fromNamespaceAndPath("minecraft", "entity.generic.hurt"),
//            "limb",
//            java.util.List.of()
//        ));
//
//        System.out.println("受击盒配置模板注册完成");
//    }
//
//    /**
//     * 创建示例实体
//     */
//    public static void createExampleEntity() {
//        System.out.println("\n=== 创建示例实体 ===");
//
//        long entityId = 1001;
//
//        // 这里假设我们已经有了BoneCollection数据
//        // 在实际情况下，这些数据会通过BoneCollectionFactory从模型文件创建
//        Map<String, BoneCollection> boneCollections = createMockBoneCollections(entityId);
//
//        // 使用模板批量注册实体受击盒组件
//        Map<String, String> boneTemplateMappings = new HashMap<>();
//        boneTemplateMappings.put("head", "head");
//        boneTemplateMappings.put("body", "torso");
//        boneTemplateMappings.put("arm_left", "limb");
//        boneTemplateMappings.put("arm_right", "limb");
//        boneTemplateMappings.put("leg_left", "limb");
//        boneTemplateMappings.put("leg_right", "limb");
//
//        EntityBoneHitboxSystem.getInstance()
//            .registerEntityWithTemplates(entityId, boneCollections, boneTemplateMappings);
//
//        System.out.println("示例实体创建完成，ID: " + entityId);
//    }
//
//    /**
//     * 创建模拟的骨骼集合数据
//     */
//    private static Map<String, BoneCollection> createMockBoneCollections(long entityId) {
//        Map<String, BoneCollection> boneCollections = new HashMap<>();
//
//        // 这里创建一些模拟的BoneCollection数据
//        // 在实际情况下，这些数据会从模型文件解析而来
//        // 为了演示，我们直接创建一些示例数据
//
//        // 注意：在真实实现中，这些会通过BoneCollectionFactory创建
//        // 这里仅作概念演示
//
//        System.out.println("创建模拟骨骼数据...");
//
//        return boneCollections;
//    }
//
//    /**
//     * 演示碰撞检测
//     */
//    public static void demonstrateCollisionDetection() {
//        System.out.println("\n=== 演示碰撞检测 ===");
//
//        long entityId = 1001;
//        Vec3 rayStart = new Vec3(0, 5, 0);
//        Vec3 rayDirection = new Vec3(1, 0, 0);
//        double maxDistance = 10.0;
//
//        // 射线检测
//        var hitResult = BoneHitboxIntegration.raycastEntity(entityId, rayStart, rayDirection, maxDistance);
//
//        if (hitResult.isPresent()) {
//            var result = hitResult.get();
//            System.out.println("检测到碰撞:");
//            System.out.println("  实体ID: " + result.entityId());
//            System.out.println("  骨骼名称: " + result.boneName());
//            System.out.println("  立方体ID: " + result.cubeId());
//            System.out.println("  击中点: " + result.hitPoint());
//            System.out.println("  距离: " + result.distance());
//
//            if (result.hitboxComponent() != null) {
//                System.out.println("  伤害倍率: " + result.hitboxComponent().getDamageMultiplier());
//                System.out.println("  是否暴击: " + result.hitboxComponent().isCritical());
//            }
//        } else {
//            System.out.println("未检测到碰撞");
//        }
//    }
//
//    /**
//     * 演示击中处理
//     */
//    public static void demonstrateHitHandling() {
//        System.out.println("\n=== 演示击中处理 ===");
//
//        long entityId = 1001;
//        Vec3 hitPoint = new Vec3(1, 2, 0);
//        float incomingDamage = 10.0f;
//
//        // 处理击中
//        var hitResult = BoneHitboxIntegration.processEntityHit(entityId, hitPoint, incomingDamage);
//
//        if (hitResult != null) {
//            System.out.println("击中处理结果:");
//            System.out.println("  实体ID: " + hitResult.entityId());
//            System.out.println("  骨骼名称: " + hitResult.boneName());
//            System.out.println("  立方体ID: " + hitResult.cubeId());
//            System.out.println("  击中点: " + hitResult.hitPoint());
//            System.out.println("  实际伤害: " + hitResult.getActualDamage());
//
//            if (hitResult.hitboxComponent() != null) {
//                System.out.println("  受击盒激活状态: " + hitResult.hitboxComponent().isActive());
//                System.out.println("  击中次数: " + hitResult.hitboxComponent().getHitCount());
//            }
//        } else {
//            System.out.println("未找到击中位置");
//        }
//    }
//
//    /**
//     * 演示系统清理
//     */
//    public static void cleanupEntity() {
//        System.out.println("\n=== 清理实体 ===");
//
//        long entityId = 1001;
//        EntityBoneHitboxSystem.getInstance().unregisterEntity(entityId);
//        System.out.println("实体 " + entityId + " 已注销");
//    }
//
//    /**
//     * 获取实体边界框示例
//     */
//    public static void demonstrateBoundingBox() {
//        System.out.println("\n=== 演示边界框计算 ===");
//
//        long entityId = 1001;
//        var boundingBox = EntityBoneHitboxSystem.getInstance().getEntityBoundingBox(entityId);
//
//        if (boundingBox.isPresent()) {
//            var box = boundingBox.get();
//            System.out.println("实体边界框:");
//            System.out.println("  最小点: " + box[0]);
//            System.out.println("  最大点: " + box[1]);
//        } else {
//            System.out.println("无法计算边界框");
//        }
//    }
//}