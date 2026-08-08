package com.catoxide.catoxidesbattlerebuild.core.sound;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生物声音配置注册表
 * <p>按 mobId 存储 {@link MobSoundProfile}。由
 * {@link com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext#registerMobSoundProfile}
 * 写入；{@code AnimatedMob} 子类可在运行时按 mobId 查询（如用于 DataDrivenMob 之外的实体）。
 */
public final class MobSoundRegistry {

    private static final String TAG = "MobSoundRegistry";
    private static final Map<String, MobSoundProfile> PROFILES = new ConcurrentHashMap<>();

    private MobSoundRegistry() {}

    /** 注册声音配置（重复注册时覆盖） */
    public static void register(String mobId, MobSoundProfile profile) {
        PROFILES.put(mobId, profile);
        LogManager.serverInfo(TAG, "Registered sound profile for '%s': ambient=%s, hurt=%s, death=%s, volume=%.2f",
                mobId, profile.ambient(), profile.hurt(), profile.death(), profile.volume());
    }

    /** 查询声音配置（无则返回 null） */
    public static MobSoundProfile get(String mobId) {
        return PROFILES.get(mobId);
    }

    /** 是否已注册 */
    public static boolean contains(String mobId) {
        return PROFILES.containsKey(mobId);
    }

    /** 已注册数量（诊断用） */
    public static int size() {
        return PROFILES.size();
    }
}
