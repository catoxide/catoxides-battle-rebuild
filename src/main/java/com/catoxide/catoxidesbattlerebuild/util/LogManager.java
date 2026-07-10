package com.catoxide.catoxidesbattlerebuild.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class LogManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CatoxidesBattleRebuild");

    private static final ConcurrentHashMap<String, Long> lastLogTime = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<String>> debugCache = new ConcurrentHashMap<>();

    private static final long DEFAULT_MIN_INTERVAL = 100;

    private static final long DEV_MODE_FLUSH_INTERVAL = 500;

    public static final boolean FORCE_DEV_MODE = false;

    private static final AtomicBoolean devMode = new AtomicBoolean(false);

    private static ScheduledExecutorService scheduler;
    private static final AtomicBoolean schedulerRunning = new AtomicBoolean(false);

    public enum LogLevel {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    public enum LogSource {
        SERVER("[S]"),
        CLIENT("[C]"),
        COMMON("[*]");

        private final String prefix;

        LogSource(String prefix) {
            this.prefix = prefix;
        }

        public String getPrefix() {
            return prefix;
        }
    }

    public enum DevModule {
        ANIMATION("Animation", false),
        RENDER("Render", false),
        MOB("Mob", true),
        AI("AI", true),
        COMBAT("Combat", true),
        ALL("All", true);

        private final String name;
        private final AtomicBoolean enabled;

        DevModule(String name, boolean defaultEnabled) {
            this.name = name;
            this.enabled = new AtomicBoolean(defaultEnabled);
        }

        public String getName() {
            return name;
        }

        public boolean isEnabled() {
            return enabled.get();
        }

        public void setEnabled(boolean enabled) {
            this.enabled.set(enabled);
        }

        public void toggle() {
            this.enabled.set(!this.enabled.get());
        }
    }

    static {
        String devModeEnv = System.getProperty("catoxide.devmode", System.getenv("CATOXIDE_DEVMODE"));
        if (FORCE_DEV_MODE || "true".equalsIgnoreCase(devModeEnv) || "1".equals(devModeEnv)) {
            enableDevMode();
        }
    }

    public static void enableDevMode() {
        if (devMode.compareAndSet(false, true)) {
            LOGGER.info("[*] [LogManager] ========== DEVELOPMENT MODE ENABLED ==========");
            LOGGER.info("[*] [LogManager] Debug logs will be flushed to INFO every {}ms", DEV_MODE_FLUSH_INTERVAL);
            logDevModulesStatus();
            startDevModeScheduler();
        }
    }

    public static void disableDevMode() {
        if (devMode.compareAndSet(true, false)) {
            LOGGER.info("[*] [LogManager] ========== DEVELOPMENT MODE DISABLED ==========");
            stopDevModeScheduler();
        }
    }

    public static void shutdown() {
        disableDevMode();
        clearRateLimitCache();
        LOGGER.info("[*] [LogManager] Shutdown complete");
    }

    public static boolean isDevMode() {
        return devMode.get();
    }

    public static boolean isModuleEnabled(DevModule module) {
        return isDevMode() && module.isEnabled();
    }

    public static void setModuleEnabled(DevModule module, boolean enabled) {
        module.setEnabled(enabled);
        LOGGER.info("[*] [LogManager] Module '{}' {}", module.getName(), enabled ? "ENABLED" : "DISABLED");
    }

    public static void toggleModule(DevModule module) {
        module.toggle();
        LOGGER.info("[*] [LogManager] Module '{}' {}", module.getName(), module.isEnabled() ? "ENABLED" : "DISABLED");
    }

    public static void logDevModulesStatus() {
        LOGGER.info("[*] [LogManager] ========== DEV MODULES STATUS ==========");
        for (DevModule module : DevModule.values()) {
            LOGGER.info("[*] [LogManager]   {}: {}", module.getName(), module.isEnabled() ? "ON" : "OFF");
        }
        LOGGER.info("[*] [LogManager] =========================================");
    }

    private static void startDevModeScheduler() {
        if (schedulerRunning.compareAndSet(false, true)) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "LogManager-DevMode");
                t.setDaemon(true);
                return t;
            });

            scheduler.scheduleAtFixedRate(() -> {
                try {
                    flushDebugCache();
                } catch (Exception e) {
                    LOGGER.error("[*] [LogManager] Error flushing debug cache: " + e.getMessage(), e);
                }
            }, DEV_MODE_FLUSH_INTERVAL, DEV_MODE_FLUSH_INTERVAL, TimeUnit.MILLISECONDS);
        }
    }

    private static void stopDevModeScheduler() {
        if (schedulerRunning.compareAndSet(true, false) && scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
        }
    }

    private static synchronized void flushDebugCache() {
        int totalEntries = debugCache.values().stream().mapToInt(java.util.concurrent.ConcurrentLinkedQueue::size).sum();

        if (totalEntries == 0) {
            return;
        }

        LOGGER.info("[*] [LogManager] ========== DEBUG FLUSH ({} entries) ==========", totalEntries);

        debugCache.keySet().stream().sorted().forEach(key -> {
            java.util.concurrent.ConcurrentLinkedQueue<String> messages = debugCache.remove(key);
            if (messages != null) {
                while (!messages.isEmpty()) {
                    String message = messages.poll();
                    LOGGER.info("[*] [LogManager] [DEBUG-INFO] {}: {}", key, message);
                }
            }
        });
    }

    private static void cacheDebugMessage(String key, String message) {
        if (isDevMode()) {
            debugCache.computeIfAbsent(key, k -> new java.util.concurrent.ConcurrentLinkedQueue<>()).offer(message);
        }
    }

    public static void serverDebug(String tag, String message) {
        cacheDebugMessage("S:" + tag, message);
        log(LogLevel.DEBUG, LogSource.SERVER, tag, message);
    }

    public static void serverDebug(String tag, String format, Object... args) {
        String message = String.format(format, args);
        cacheDebugMessage("S:" + tag, message);
        log(LogLevel.DEBUG, LogSource.SERVER, tag, message);
    }

    public static void serverInfo(String tag, String message) {
        log(LogLevel.INFO, LogSource.SERVER, tag, message);
    }

    public static void serverInfo(String tag, String format, Object... args) {
        log(LogLevel.INFO, LogSource.SERVER, tag, String.format(format, args));
    }

    public static void serverWarn(String tag, String message) {
        log(LogLevel.WARN, LogSource.SERVER, tag, message);
    }

    public static void serverWarn(String tag, String format, Object... args) {
        log(LogLevel.WARN, LogSource.SERVER, tag, String.format(format, args));
    }

    public static void serverError(String tag, String message) {
        log(LogLevel.ERROR, LogSource.SERVER, tag, message);
    }

    public static void serverError(String tag, String message, Throwable t) {
        log(LogLevel.ERROR, LogSource.SERVER, tag, message, t);
    }

    public static void clientDebug(String tag, String message) {
        cacheDebugMessage("C:" + tag, message);
        log(LogLevel.DEBUG, LogSource.CLIENT, tag, message);
    }

    public static void clientDebug(String tag, String format, Object... args) {
        String message = String.format(format, args);
        cacheDebugMessage("C:" + tag, message);
        log(LogLevel.DEBUG, LogSource.CLIENT, tag, message);
    }

    public static void clientInfo(String tag, String message) {
        log(LogLevel.INFO, LogSource.CLIENT, tag, message);
    }

    public static void clientInfo(String tag, String format, Object... args) {
        log(LogLevel.INFO, LogSource.CLIENT, tag, String.format(format, args));
    }

    public static void clientWarn(String tag, String message) {
        log(LogLevel.WARN, LogSource.CLIENT, tag, message);
    }

    public static void clientWarn(String tag, String format, Object... args) {
        log(LogLevel.WARN, LogSource.CLIENT, tag, String.format(format, args));
    }

    public static void clientError(String tag, String message) {
        log(LogLevel.ERROR, LogSource.CLIENT, tag, message);
    }

    public static void clientError(String tag, String message, Throwable t) {
        log(LogLevel.ERROR, LogSource.CLIENT, tag, message, t);
    }

    public static void info(String tag, String message) {
        log(LogLevel.INFO, LogSource.COMMON, tag, message);
    }

    public static void info(String tag, String format, Object... args) {
        log(LogLevel.INFO, LogSource.COMMON, tag, String.format(format, args));
    }

    public static void serverStartup(String tag, String message) {
        if (isDevMode()) {
            serverInfo(tag, "[STARTUP] " + message);
        } else {
            serverDebug(tag, "[STARTUP] " + message);
        }
    }

    public static void serverShutdown(String tag, String message) {
        if (isDevMode()) {
            serverInfo(tag, "[SHUTDOWN] " + message);
        } else {
            serverDebug(tag, "[SHUTDOWN] " + message);
        }
    }

    public static void clientStartup(String tag, String message) {
        if (isDevMode()) {
            clientInfo(tag, "[STARTUP] " + message);
        } else {
            clientDebug(tag, "[STARTUP] " + message);
        }
    }

    public static void clientShutdown(String tag, String message) {
        if (isDevMode()) {
            clientInfo(tag, "[SHUTDOWN] " + message);
        } else {
            clientDebug(tag, "[SHUTDOWN] " + message);
        }
    }

    public static void serverDebugThrottled(String tag, String message, long minIntervalMs) {
        if (checkRateLimit(tag, minIntervalMs)) {
            serverDebug(tag, message);
        }
    }

    public static void serverInfoThrottled(String tag, String message, long minIntervalMs) {
        if (checkRateLimit(tag, minIntervalMs)) {
            serverInfo(tag, message);
        }
    }

    public static void clientDebugThrottled(String tag, String message, long minIntervalMs) {
        if (checkRateLimit(tag, minIntervalMs)) {
            clientDebug(tag, message);
        }
    }

    public static void clientInfoThrottled(String tag, String message, long minIntervalMs) {
        if (checkRateLimit(tag, minIntervalMs)) {
            clientInfo(tag, message);
        }
    }

    public static void serverTransformDebug(String tag, String transformInfo) {
        serverDebugThrottled(tag, transformInfo, 500);
    }

    public static void clientTransformDebug(String tag, String transformInfo) {
        clientDebugThrottled(tag, transformInfo, 500);
    }

    public static void animationDebug(String entityId, String animationName, String additionalInfo) {
        if (isModuleEnabled(DevModule.ANIMATION)) {
            String message = String.format("[Entity:%s] Anim:%s | %s", entityId, animationName, additionalInfo);
            clientDebug("Animation", message);
        }
    }

    public static void animationDebug(String entityId, String animationName) {
        animationDebug(entityId, animationName, "");
    }

    public static void animationDebug(String entityId, String animationName, String additionalInfo, long minIntervalMs) {
        if (isModuleEnabled(DevModule.ANIMATION)) {
            String tag = "Animation:" + entityId;
            String message = String.format("[Entity:%s] Anim:%s | %s", entityId, animationName, additionalInfo);
            clientDebugThrottled(tag, message, minIntervalMs);
        }
    }

    public static void mobDebug(String entityId, String message) {
        if (isModuleEnabled(DevModule.MOB)) {
            clientDebug("Mob", String.format("[Entity:%s] %s", entityId, message));
        }
    }

    public static void renderDebug(String message) {
        if (isModuleEnabled(DevModule.RENDER)) {
            clientDebug("Render", message);
        }
    }

    public static void renderDebug(String format, Object... args) {
        if (isModuleEnabled(DevModule.RENDER)) {
            clientDebug("Render", String.format(format, args));
        }
    }

    public static void aiDebug(String entityId, String message) {
        if (isModuleEnabled(DevModule.AI)) {
            serverDebug("AI", String.format("[Entity:%s] %s", entityId, message));
        }
    }

    public static void combatDebug(String entityId, String message) {
        if (isModuleEnabled(DevModule.COMBAT)) {
            serverDebug("Combat", String.format("[Entity:%s] %s", entityId, message));
        }
    }

    private static boolean checkRateLimit(String tag, long minIntervalMs) {
        long now = System.currentTimeMillis();
        Long lastTime = lastLogTime.get(tag);

        if (lastTime == null || now - lastTime >= minIntervalMs) {
            lastLogTime.put(tag, now);
            return true;
        }
        return false;
    }

    private static void log(LogLevel level, LogSource source, String tag, String message) {
        String formattedMessage = String.format("%s [%s] %s", source.getPrefix(), tag, message);

        switch (level) {
            case DEBUG:
                LOGGER.debug(formattedMessage);
                break;
            case INFO:
                LOGGER.info(formattedMessage);
                break;
            case WARN:
                LOGGER.warn(formattedMessage);
                break;
            case ERROR:
                LOGGER.error(formattedMessage);
                break;
        }
    }

    private static void log(LogLevel level, LogSource source, String tag, String message, Throwable t) {
        String formattedMessage = String.format("%s [%s] %s", source.getPrefix(), tag, message);

        switch (level) {
            case DEBUG:
                LOGGER.debug(formattedMessage, t);
                break;
            case INFO:
                LOGGER.info(formattedMessage, t);
                break;
            case WARN:
                LOGGER.warn(formattedMessage, t);
                break;
            case ERROR:
                LOGGER.error(formattedMessage, t);
                break;
        }
    }

    public static void clearRateLimitCache() {
        lastLogTime.clear();
    }

    // ========== ModularZombie2 专用日志 ==========

    private static final String TAG_ZOMBIE2 = "Zombie2";
    private static final String TAG_BONE = "BoneSync";

    /** 实体初始化（保留 info） */
    public static void zombie2Init(int entityId) {
        serverInfo(TAG_ZOMBIE2, "Entity {} initialized with Spark-Core", entityId);
    }

    /** 动画状态切换（降为 debug） */
    public static void zombie2StateChanged(int entityId, String from, String to) {
        serverDebug(TAG_ZOMBIE2, "Entity {} State changed: {} -> {}", entityId, from, to);
    }

    /** 客户端动画同步（降为 debug） */
    public static void zombie2ClientSync(int entityId, String animName) {
        clientDebug(TAG_ZOMBIE2, "Entity {} Client animation sync: {}", entityId, animName);
    }

    /** 动画播放成功（降为 debug） */
    public static void zombie2AnimStarted(int entityId, String animName, String animState) {
        serverDebug(TAG_ZOMBIE2, "Entity {} Playing animation: {} (state: {})", entityId, animName, animState);
    }

    /** 动画创建失败（保留 error） */
    public static void zombie2AnimFailed(int entityId, String animName) {
        serverError(TAG_ZOMBIE2, String.format("Entity %d FAILED to create AnimInstance for: %s", entityId, animName));
    }

    /** 动画播放异常（保留 error） */
    public static void zombie2AnimError(int entityId, String animName, String errorMsg, Throwable t) {
        serverError(TAG_ZOMBIE2, String.format("Entity %d Exception playing animation %s: %s", entityId, animName, errorMsg), t);
    }

    /** 零姿态恢复（降为 debug） */
    public static void zombie2ZeroPoseRecover(boolean clientSide, int entityId, String animName) {
        if (clientSide) {
            clientDebug(TAG_ZOMBIE2, "Entity {} [CLIENT] Zero pose detected, recovering: {}", entityId, animName);
        } else {
            serverDebug(TAG_ZOMBIE2, "Entity {} [SERVER] Zero pose detected, recovering: {}", entityId, animName);
        }
    }

    /** 客户端骨骼数据同步（降为 debug） */
    public static void boneSyncClient(int entityId, int boneCount) {
        clientDebug(TAG_BONE, "Entity {} synced {} bones", entityId, boneCount);
    }

    /** 骨骼调试渲染错误（保留 warn） */
    public static void boneDebugWarn(String format, Object... args) {
        clientWarn(TAG_BONE, format, args);
    }
}