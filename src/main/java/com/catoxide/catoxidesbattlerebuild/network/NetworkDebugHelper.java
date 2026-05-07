package com.catoxide.catoxidesbattlerebuild.network;

import com.catoxide.catoxidesbattlerebuild.server.geometry.ServerEntityManager;
import com.catoxide.catoxidesbattlerebuild.client.manager.ClientHitboxManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import software.bernie.geckolib.GeckoLib;

import java.util.*;

/**
 * 网络调试助手
 * 提供网络通路测试和断点功能
 */
public class NetworkDebugHelper {
    
    private static final NetworkDebugHelper INSTANCE = new NetworkDebugHelper();
    
    // 调试模式开关
    private boolean debugMode = false;
    
    // 断点列表
    private final Set<Breakpoint> breakpoints = new HashSet<>();
    
    // 测试数据包缓存
    private final Map<Integer, AnimationSyncPacket> testPacketCache = new HashMap<>();
    
    private NetworkDebugHelper() {}
    
    public static NetworkDebugHelper getInstance() {
        return INSTANCE;
    }
    
    /**
     * 启用/禁用调试模式
     */
    public void setDebugMode(boolean enabled) {
        this.debugMode = enabled;
        GeckoLib.LOGGER.info("[NetworkDebug] Debug mode {}", enabled ? "enabled" : "disabled");
    }
    
    public boolean isDebugMode() {
        return debugMode;
    }
    
    /**
     * 添加断点
     * @param type 断点类型
     * @param entityId 实体ID（可选，为0时匹配所有实体）
     * @param description 断点描述
     */
    public void addBreakpoint(BreakpointType type, int entityId, String description) {
        breakpoints.add(new Breakpoint(type, entityId, description));
        GeckoLib.LOGGER.info("[NetworkDebug] Added breakpoint: {} for entity {} - {}", type, entityId, description);
    }
    
    /**
     * 移除断点
     */
    public void removeBreakpoint(BreakpointType type, int entityId) {
        breakpoints.removeIf(b -> b.type == type && b.entityId == entityId);
        GeckoLib.LOGGER.info("[NetworkDebug] Removed breakpoint: {} for entity {}", type, entityId);
    }
    
    /**
     * 清除所有断点
     */
    public void clearAllBreakpoints() {
        breakpoints.clear();
        GeckoLib.LOGGER.info("[NetworkDebug] All breakpoints cleared");
    }
    
    /**
     * 检查并触发断点
     * @return true 如果断点被触发并暂停
     */
    public boolean checkBreakpoint(BreakpointType type, int entityId) {
        if (!debugMode) return false;
        
        for (Breakpoint breakpoint : breakpoints) {
            if (breakpoint.type == type && (breakpoint.entityId == 0 || breakpoint.entityId == entityId)) {
                triggerBreakpoint(breakpoint, entityId);
                return true;
            }
        }
        return false;
    }
    
    /**
     * 触发断点
     */
    private void triggerBreakpoint(Breakpoint breakpoint, int entityId) {
        GeckoLib.LOGGER.warn("\n========================================");
        GeckoLib.LOGGER.warn("[NETWORK BREAKPOINT TRIGGERED]");
        GeckoLib.LOGGER.warn("Type: {}", breakpoint.type);
        GeckoLib.LOGGER.warn("Entity ID: {}", entityId);
        GeckoLib.LOGGER.warn("Description: {}", breakpoint.description);
        GeckoLib.LOGGER.warn("========================================\n");
        
        // 输出堆栈跟踪（用于定位调用位置）
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        StringBuilder stackTraceStr = new StringBuilder("Call stack:\n");
        for (int i = 3; i < Math.min(10, stackTrace.length); i++) {
            stackTraceStr.append("  at ").append(stackTrace[i].toString()).append("\n");
        }
        GeckoLib.LOGGER.debug(stackTraceStr.toString());
    }
    
    /**
     * 发送测试数据包（用于手动测试网络通路）
     */
    public void sendTestPacket(ServerPlayer player, int entityId) {
        if (!debugMode) {
            GeckoLib.LOGGER.warn("[NetworkDebug] Debug mode not enabled, cannot send test packet");
            return;
        }
        
        try {
            Map<String, CompressedBoneTransform> testTransforms = new HashMap<>();
            testTransforms.put("test_bone", new CompressedBoneTransform("test_bone", createTestMatrix(), (byte) 2));
            
            EntityBoneSyncData entityData = EntityBoneSyncData.createDeltaSync(entityId, testTransforms, 
                    EntityBoneSyncData.AnimationState.getDefault());
            
            Map<Integer, EntityBoneSyncData> entityDataMap = new HashMap<>();
            entityDataMap.put(entityId, entityData);
            
            AnimationSyncPacket packet = AnimationSyncPacket.createDeltaSync(entityDataMap);
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
            
            // 缓存测试包用于验证
            testPacketCache.put(entityId, packet);
            
            GeckoLib.LOGGER.info("[NetworkDebug] Sent test packet to player {} for entity {}", 
                    player.getName().getString(), entityId);
            
        } catch (Exception e) {
            GeckoLib.LOGGER.error("[NetworkDebug] Failed to send test packet", e);
        }
    }
    
    /**
     * 创建测试用的4x4变换矩阵
     */
    private org.joml.Matrix4f createTestMatrix() {
        org.joml.Matrix4f matrix = new org.joml.Matrix4f();
        matrix.identity();
        matrix.translate(0.5f, 1.0f, 0.0f);
        return matrix;
    }
    
    /**
     * 验证服务器端实体注册状态
     */
    public String verifyServerEntityRegistration(UUID entityUuid) {
        StringBuilder result = new StringBuilder();
        result.append("=== 服务器端实体注册验证 ===\n");
        
        ServerEntityManager manager = ServerEntityManager.getInstance();
        
        result.append("1. 检查 ServerEntityManager:\n");
        boolean exists = manager.getEntity(entityUuid) != null;
        result.append("   - 实体存在: ").append(exists ? "✅" : "❌").append("\n");
        
        if (exists) {
            result.append("   - 骨骼矩阵数量: ").append(manager.getEntityBoneMatrices(entityUuid).size()).append("\n");
            result.append("   - 所有实体UUID数量: ").append(manager.getAllEntityUuids().size()).append("\n");
        }
        
        return result.toString();
    }
    
    /**
     * 验证客户端实体注册状态
     */
    public String verifyClientEntityRegistration(UUID entityUuid) {
        StringBuilder result = new StringBuilder();
        result.append("=== 客户端实体注册验证 ===\n");
        
        ClientHitboxManager manager = ClientHitboxManager.getInstance();
        
        result.append("1. 检查 ClientHitboxManager:\n");
        boolean exists = manager.getEntityCollection(entityUuid) != null;
        result.append("   - 实体集合存在: ").append(exists ? "✅" : "❌").append("\n");
        
        if (exists) {
            Map<String, com.catoxide.catoxidesbattlerebuild.client.geometry.ClientBoneCollection> bones = 
                    manager.getBoneCollections(entityUuid);
            result.append("   - 骨骼集合数量: ").append(bones.size()).append("\n");
            
            if (!bones.isEmpty()) {
                result.append("   - 骨骼名称: ").append(String.join(", ", bones.keySet())).append("\n");
            }
        }
        
        return result.toString();
    }
    
    /**
     * 测试网络通路是否完整
     */
    public NetworkPathTestResult testNetworkPath() {
        NetworkPathTestResult result = new NetworkPathTestResult();
        
        // 测试1: 检查网络通道
        try {
            if (NetworkHandler.CHANNEL != null) {
                result.channelOk = true;
                result.addSuccess("网络通道已初始化");
            } else {
                result.addError("网络通道未初始化");
            }
        } catch (Exception e) {
            result.channelOk = false;
            result.addError("网络通道异常: " + e.getMessage());
        }
        
        // 测试2: 检查服务器端管理器
        try {
            ServerEntityManager.getInstance();
            result.serverManagerOk = true;
            result.addSuccess("ServerEntityManager 可用");
        } catch (Exception e) {
            result.serverManagerOk = false;
            result.addError("ServerEntityManager 不可用: " + e.getMessage());
        }
        
        // 测试3: 检查客户端管理器
        try {
            ClientHitboxManager.getInstance();
            result.clientManagerOk = true;
            result.addSuccess("ClientHitboxManager 可用");
        } catch (Exception e) {
            result.clientManagerOk = false;
            result.addError("ClientHitboxManager 不可用: " + e.getMessage());
        }
        
        // 测试4: 检查同步管理器
        try {
            AnimationSyncManager.getInstance();
            result.syncManagerOk = true;
            result.addSuccess("AnimationSyncManager 可用");
        } catch (Exception e) {
            result.syncManagerOk = false;
            result.addError("AnimationSyncManager 不可用: " + e.getMessage());
        }
        
        result.allOk = result.channelOk && result.serverManagerOk && 
                       result.clientManagerOk && result.syncManagerOk;
        
        return result;
    }
    
    /**
     * 输出网络通路测试报告
     */
    public String getNetworkPathTestReport() {
        NetworkPathTestResult result = testNetworkPath();
        return result.toString();
    }
    
    /**
     * 输出当前状态摘要
     */
    public String getStatusSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 网络调试助手状态 ===\n");
        sb.append("调试模式: ").append(debugMode ? "✅ 启用" : "❌ 禁用").append("\n");
        sb.append("断点数量: ").append(breakpoints.size()).append("\n");
        sb.append("缓存测试包数量: ").append(testPacketCache.size()).append("\n");
        
        if (!breakpoints.isEmpty()) {
            sb.append("\n活跃断点:\n");
            for (Breakpoint bp : breakpoints) {
                sb.append("  - [").append(bp.type).append("] ").append(bp.description).append("\n");
            }
        }
        
        return sb.toString();
    }
    
    /**
     * 断点类型枚举
     */
    public enum BreakpointType {
        /** 在数据包发送前触发 */
        BEFORE_SEND,
        /** 在数据包发送后触发 */
        AFTER_SEND,
        /** 在数据包接收后触发 */
        AFTER_RECEIVE,
        /** 在实体注册时触发 */
        ENTITY_REGISTER,
        /** 在骨骼变换更新时触发 */
        BONE_TRANSFORM_UPDATE,
        /** 在差异检测时触发 */
        DELTA_DETECTION
    }
    
    /**
     * 断点数据结构
     */
    public record Breakpoint(BreakpointType type, int entityId, String description) {}
    
    /**
     * 网络通路测试结果
     */
    public static class NetworkPathTestResult {
        public boolean allOk = false;
        public boolean channelOk = false;
        public boolean serverManagerOk = false;
        public boolean clientManagerOk = false;
        public boolean syncManagerOk = false;
        
        private final List<String> successes = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        
        public void addSuccess(String message) {
            successes.add(message);
        }
        
        public void addError(String message) {
            errors.add(message);
        }
        
        public List<String> getSuccesses() {
            return successes;
        }
        
        public List<String> getErrors() {
            return errors;
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== 网络通路测试报告 ===\n");
            sb.append("整体状态: ").append(allOk ? "✅ 完整" : "❌ 存在问题").append("\n\n");
            
            sb.append("【成功项】\n");
            for (String success : successes) {
                sb.append("  ✓ ").append(success).append("\n");
            }
            
            if (!errors.isEmpty()) {
                sb.append("\n【问题项】\n");
                for (String error : errors) {
                    sb.append("  ✗ ").append(error).append("\n");
                }
            }
            
            return sb.toString();
        }
    }
}
