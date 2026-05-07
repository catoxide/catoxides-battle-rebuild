package com.catoxide.catoxidesbattlerebuild.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一日志管理类
 * - 区分客户端/服务端日志
 * - 支持日志频率限制，防止刷屏
 * - 统一的日志格式
 * - 开发模式支持：定时将DEBUG日志提升为INFO级别输出
 * - 启动/停止日志在开发模式下自动提升级别
 */
public class LogManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CatoxidesBattleRebuild");

    // 存储最后一次日志输出时间，用于频率限制
    private static final ConcurrentHashMap<String, Long> lastLogTime = new ConcurrentHashMap<>();
    
    // 存储需要定时输出的日志内容（开发模式）
    private static final ConcurrentHashMap<String, String> debugCache = new ConcurrentHashMap<>();
    
    // 默认最小日志间隔（毫秒）
    private static final long DEFAULT_MIN_INTERVAL = 1000;
    
    // 开发模式定时输出间隔（毫秒）- 5秒
    private static final long DEV_MODE_FLUSH_INTERVAL = 5000;
    
    // 是否为开发模式
    private static final AtomicBoolean devMode = new AtomicBoolean(true);
    
    // 定时输出任务
    private static ScheduledExecutorService scheduler;
    private static final AtomicBoolean schedulerRunning = new AtomicBoolean(false);

    // 日志级别枚举
    public enum LogLevel {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    // 日志来源枚举
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

    // 静态初始化
    static {
        // 检查环境变量或启动参数判断是否为开发模式
        String devModeEnv = System.getProperty("catoxide.devmode", System.getenv("CATOXIDE_DEVMODE"));
        if ("true".equalsIgnoreCase(devModeEnv) || "1".equals(devModeEnv)) {
            enableDevMode();
        }
    }

    /**
     * 启用开发模式
     */
    public static void enableDevMode() {
        if (devMode.compareAndSet(false, true)) {
            LOGGER.info("[*] [LogManager] ========== DEVELOPMENT MODE ENABLED ==========");
            LOGGER.info("[*] [LogManager] Debug logs will be flushed to INFO every {}ms", DEV_MODE_FLUSH_INTERVAL);
            startDevModeScheduler();
        }
    }

    /**
     * 禁用开发模式
     */
    public static void disableDevMode() {
        if (devMode.compareAndSet(true, false)) {
            LOGGER.info("[*] [LogManager] ========== DEVELOPMENT MODE DISABLED ==========");
            stopDevModeScheduler();
        }
    }

    /**
     * 检查是否为开发模式
     */
    public static boolean isDevMode() {
        return devMode.get();
    }

    /**
     * 启动开发模式定时任务
     */
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
                    LOGGER.error("[*] [LogManager] Error flushing debug cache: {}", e.getMessage());
                }
            }, DEV_MODE_FLUSH_INTERVAL, DEV_MODE_FLUSH_INTERVAL, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 停止开发模式定时任务
     */
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

    /**
     * 刷新调试缓存（开发模式下提升为INFO级别输出）
     */
    private static synchronized void flushDebugCache() {
        if (debugCache.isEmpty()) {
            LOGGER.info("[*] [LogManager] --- DEBUG FLUSH (empty) ---");
            return;
        }

        LOGGER.info("[*] [LogManager] ========== DEBUG FLUSH ({} entries) ==========", debugCache.size());
        
        // 按key排序输出
        debugCache.keySet().stream().sorted().forEach(key -> {
            String message = debugCache.get(key);
            LOGGER.info("[*] [LogManager] [DEBUG-INFO] {}: {}", key, message);
        });
        
        debugCache.clear();
    }

    /**
     * 将调试信息添加到缓存（开发模式下会定时输出为INFO级别）
     */
    private static void cacheDebugMessage(String key, String message) {
        if (isDevMode()) {
            debugCache.put(key, message);
        }
    }

    /**
     * 服务端DEBUG日志
     */
    public static void serverDebug(String tag, String message) {
        cacheDebugMessage("S:" + tag, message);
        log(LogLevel.DEBUG, LogSource.SERVER, tag, message);
    }

    /**
     * 服务端DEBUG日志（带参数）
     */
    public static void serverDebug(String tag, String format, Object... args) {
        String message = String.format(format, args);
        cacheDebugMessage("S:" + tag, message);
        log(LogLevel.DEBUG, LogSource.SERVER, tag, message);
    }

    /**
     * 服务端INFO日志
     */
    public static void serverInfo(String tag, String message) {
        log(LogLevel.INFO, LogSource.SERVER, tag, message);
    }

    /**
     * 服务端INFO日志（带参数）
     */
    public static void serverInfo(String tag, String format, Object... args) {
        log(LogLevel.INFO, LogSource.SERVER, tag, String.format(format, args));
    }

    /**
     * 服务端WARN日志
     */
    public static void serverWarn(String tag, String message) {
        log(LogLevel.WARN, LogSource.SERVER, tag, message);
    }

    /**
     * 服务端WARN日志（带参数）
     */
    public static void serverWarn(String tag, String format, Object... args) {
        log(LogLevel.WARN, LogSource.SERVER, tag, String.format(format, args));
    }

    /**
     * 服务端ERROR日志
     */
    public static void serverError(String tag, String message) {
        log(LogLevel.ERROR, LogSource.SERVER, tag, message);
    }

    /**
     * 服务端ERROR日志（带异常）
     */
    public static void serverError(String tag, String message, Throwable t) {
        log(LogLevel.ERROR, LogSource.SERVER, tag, message, t);
    }

    /**
     * 客户端DEBUG日志
     */
    public static void clientDebug(String tag, String message) {
        cacheDebugMessage("C:" + tag, message);
        log(LogLevel.DEBUG, LogSource.CLIENT, tag, message);
    }

    /**
     * 客户端DEBUG日志（带参数）
     */
    public static void clientDebug(String tag, String format, Object... args) {
        String message = String.format(format, args);
        cacheDebugMessage("C:" + tag, message);
        log(LogLevel.DEBUG, LogSource.CLIENT, tag, message);
    }

    /**
     * 客户端INFO日志
     */
    public static void clientInfo(String tag, String message) {
        log(LogLevel.INFO, LogSource.CLIENT, tag, message);
    }

    /**
     * 客户端INFO日志（带参数）
     */
    public static void clientInfo(String tag, String format, Object... args) {
        log(LogLevel.INFO, LogSource.CLIENT, tag, String.format(format, args));
    }

    /**
     * 客户端WARN日志
     */
    public static void clientWarn(String tag, String message) {
        log(LogLevel.WARN, LogSource.CLIENT, tag, message);
    }

    /**
     * 客户端WARN日志（带参数）
     */
    public static void clientWarn(String tag, String format, Object... args) {
        log(LogLevel.WARN, LogSource.CLIENT, tag, String.format(format, args));
    }

    /**
     * 客户端ERROR日志
     */
    public static void clientError(String tag, String message) {
        log(LogLevel.ERROR, LogSource.CLIENT, tag, message);
    }

    /**
     * 客户端ERROR日志（带异常）
     */
    public static void clientError(String tag, String message, Throwable t) {
        log(LogLevel.ERROR, LogSource.CLIENT, tag, message, t);
    }

    /**
     * 通用日志（不区分客户端/服务端）
     */
    public static void info(String tag, String message) {
        log(LogLevel.INFO, LogSource.COMMON, tag, message);
    }

    /**
     * 通用日志（带参数）
     */
    public static void info(String tag, String format, Object... args) {
        log(LogLevel.INFO, LogSource.COMMON, tag, String.format(format, args));
    }

    /**
     * 服务端启动日志（开发模式下自动提升为INFO级别）
     */
    public static void serverStartup(String tag, String message) {
        if (isDevMode()) {
            serverInfo(tag, "[STARTUP] " + message);
        } else {
            serverDebug(tag, "[STARTUP] " + message);
        }
    }

    /**
     * 服务端停止日志（开发模式下自动提升为INFO级别）
     */
    public static void serverShutdown(String tag, String message) {
        if (isDevMode()) {
            serverInfo(tag, "[SHUTDOWN] " + message);
        } else {
            serverDebug(tag, "[SHUTDOWN] " + message);
        }
    }

    /**
     * 客户端启动日志（开发模式下自动提升为INFO级别）
     */
    public static void clientStartup(String tag, String message) {
        if (isDevMode()) {
            clientInfo(tag, "[STARTUP] " + message);
        } else {
            clientDebug(tag, "[STARTUP] " + message);
        }
    }

    /**
     * 客户端停止日志（开发模式下自动提升为INFO级别）
     */
    public static void clientShutdown(String tag, String message) {
        if (isDevMode()) {
            clientInfo(tag, "[SHUTDOWN] " + message);
        } else {
            clientDebug(tag, "[SHUTDOWN] " + message);
        }
    }

    /**
     * 带频率限制的服务端DEBUG日志
     * @param minIntervalMs 最小日志间隔（毫秒）
     */
    public static void serverDebugThrottled(String tag, String message, long minIntervalMs) {
        if (checkRateLimit(tag, minIntervalMs)) {
            serverDebug(tag, message);
        }
    }

    /**
     * 带频率限制的服务端INFO日志
     * @param minIntervalMs 最小日志间隔（毫秒）
     */
    public static void serverInfoThrottled(String tag, String message, long minIntervalMs) {
        if (checkRateLimit(tag, minIntervalMs)) {
            serverInfo(tag, message);
        }
    }

    /**
     * 带频率限制的客户端DEBUG日志
     * @param minIntervalMs 最小日志间隔（毫秒）
     */
    public static void clientDebugThrottled(String tag, String message, long minIntervalMs) {
        if (checkRateLimit(tag, minIntervalMs)) {
            clientDebug(tag, message);
        }
    }

    /**
     * 带频率限制的客户端INFO日志
     * @param minIntervalMs 最小日志间隔（毫秒）
     */
    public static void clientInfoThrottled(String tag, String message, long minIntervalMs) {
        if (checkRateLimit(tag, minIntervalMs)) {
            clientInfo(tag, message);
        }
    }

    /**
     * 变换矩阵调试日志（带频率限制）
     */
    public static void serverTransformDebug(String tag, String transformInfo) {
        serverDebugThrottled(tag, transformInfo, 500);
    }

    /**
     * 变换矩阵调试日志（带频率限制）
     */
    public static void clientTransformDebug(String tag, String transformInfo) {
        clientDebugThrottled(tag, transformInfo, 500);
    }

    /**
     * 检查频率限制
     */
    private static boolean checkRateLimit(String tag, long minIntervalMs) {
        long now = System.currentTimeMillis();
        Long lastTime = lastLogTime.get(tag);
        
        if (lastTime == null || now - lastTime >= minIntervalMs) {
            lastLogTime.put(tag, now);
            return true;
        }
        return false;
    }

    /**
     * 核心日志方法
     */
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

    /**
     * 带异常的核心日志方法
     */
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

    /**
     * 清空频率限制缓存（用于调试）
     */
    public static void clearRateLimitCache() {
        lastLogTime.clear();
    }

    /**
     * 获取默认日志间隔
     */
    public static long getDefaultMinInterval() {
        return DEFAULT_MIN_INTERVAL;
    }

    /**
     * 立即刷新调试缓存（用于手动触发）
     */
    public static void flushDebugCacheNow() {
        if (isDevMode()) {
            flushDebugCache();
        }
    }
}
