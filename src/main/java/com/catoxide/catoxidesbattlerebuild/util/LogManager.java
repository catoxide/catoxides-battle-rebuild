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
    
    /**
     * 强制启用开发模式 - 开发时可以直接设置为 true
     * 设置为 true 后无需环境变量即可启用开发模式
     */
    public static final boolean FORCE_DEV_MODE = true;
    
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
            LOGGER.info("[*] [LogManager] --- DEBUG FLUSH (empty) ---");
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

    public static long getDefaultMinInterval() {
        return DEFAULT_MIN_INTERVAL;
    }

    public static void flushDebugCacheNow() {
        if (isDevMode()) {
            flushDebugCache();
        }
    }
}