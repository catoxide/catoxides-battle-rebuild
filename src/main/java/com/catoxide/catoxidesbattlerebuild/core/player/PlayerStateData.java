package com.catoxide.catoxidesbattlerebuild.core.player;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 玩家属性统合系统 —— 核心数据类（Attachment 持久化）。
 * <p>承载所有"人和世界交互"产生的玩家状态：
 * <ul>
 *   <li>{@code stamina} 精力（0~100）：行动（挖掘/战斗/冲刺）消耗，睡觉回复</li>
 *   <li>{@code awakeDays} 连续清醒天数：不睡觉累计 → 虚弱</li>
 *   <li>{@code lastSleepQuality} 最近睡眠质量（0~1）：睡不好 → 精力不回复</li>
 *   <li>{@code lastSleepStartTick} 上次入睡 tick：判定睡眠时长/质量</li>
 *   <li>{@code currentWeight} 当前负重（服务端计算缓存）</li>
 *   <li>{@code temperature} / {@code targetTemperature} 体温（向目标环境温度趋近）</li>
 * </ul>
 * <p>通过 {@link PlayerStateAttachments#PLAYER_STATE} 挂到 {@link net.minecraft.world.entity.player.Player}，
 * 配 {@link Codec} 自动持久化（死亡保留 {@code copyOnDeath}）。
 */
public class PlayerStateData {

    /** 精力上限（百分比制） */
    public static final float MAX_STAMINA = 100f;
    /** 舒适温度（与 targetTemperature 相同的基准值，无环境修正时保持） */
    public static final float NEUTRAL_TEMPERATURE = 22f;

    // ===== 精力（P1）=====
    private float stamina = MAX_STAMINA;
    // ===== 睡眠（P1）=====
    private int awakeDays = 0;
    private float lastSleepQuality = 1f;
    private long lastSleepStartTick = -1L;
    // ===== 负重（P2）=====
    private float currentWeight = 0f;
    // ===== 温度（P3）=====
    private float temperature = NEUTRAL_TEMPERATURE;
    private float targetTemperature = NEUTRAL_TEMPERATURE;

    public PlayerStateData() {
    }

    /** Codec 全参构造（字段顺序与 CODEC 的 fieldOf 顺序一致） */
    public PlayerStateData(float stamina, int awakeDays, float lastSleepQuality, long lastSleepStartTick,
                           float currentWeight, float temperature, float targetTemperature) {
        this.stamina = stamina;
        this.awakeDays = awakeDays;
        this.lastSleepQuality = lastSleepQuality;
        this.lastSleepStartTick = lastSleepStartTick;
        this.currentWeight = currentWeight;
        this.temperature = temperature;
        this.targetTemperature = targetTemperature;
    }

    /** NBT 持久化 + 同步编解码 */
    public static final Codec<PlayerStateData> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.FLOAT.fieldOf("stamina").orElse(MAX_STAMINA).forGetter(d -> d.stamina),
            Codec.INT.fieldOf("awake_days").orElse(0).forGetter(d -> d.awakeDays),
            Codec.FLOAT.fieldOf("sleep_quality").orElse(1f).forGetter(d -> d.lastSleepQuality),
            Codec.LONG.fieldOf("last_sleep_tick").orElse(-1L).forGetter(d -> d.lastSleepStartTick),
            Codec.FLOAT.fieldOf("current_weight").orElse(0f).forGetter(d -> d.currentWeight),
            Codec.FLOAT.fieldOf("temperature").orElse(NEUTRAL_TEMPERATURE).forGetter(d -> d.temperature),
            Codec.FLOAT.fieldOf("target_temperature").orElse(NEUTRAL_TEMPERATURE).forGetter(d -> d.targetTemperature)
    ).apply(inst, PlayerStateData::new));

    // ==================== 精力 ====================

    public float getStamina() {
        return stamina;
    }

    public void setStamina(float stamina) {
        this.stamina = Math.max(0f, Math.min(MAX_STAMINA, stamina));
    }

    /** 消耗精力（下限 0） */
    public void consumeStamina(float amount) {
        setStamina(this.stamina - amount);
    }

    /** 回复精力（上限 MAX_STAMINA） */
    public void restoreStamina(float amount) {
        setStamina(this.stamina + amount);
    }

    public boolean isExhausted() {
        return stamina <= 0f;
    }

    // ==================== 睡眠 ====================

    public int getAwakeDays() {
        return awakeDays;
    }

    public void setAwakeDays(int awakeDays) {
        this.awakeDays = Math.max(0, awakeDays);
    }

    public float getLastSleepQuality() {
        return lastSleepQuality;
    }

    public void setLastSleepQuality(float lastSleepQuality) {
        this.lastSleepQuality = Math.max(0f, Math.min(1f, lastSleepQuality));
    }

    public long getLastSleepStartTick() {
        return lastSleepStartTick;
    }

    public void setLastSleepStartTick(long tick) {
        this.lastSleepStartTick = tick;
    }

    /**
     * 记录一次入睡（床躺下时调用）。
     *
     * @param gameTime 当前游戏时间（level.getGameTime()）
     */
    public void recordSleepStart(long gameTime) {
        this.lastSleepStartTick = gameTime;
    }

    /**
     * 记录一次醒来，根据是否被打断 + 实际睡眠时长计算睡眠质量。
     *
     * @param gameTime      当前游戏时间
     * @param wakeImmediately 是否立即醒来（床被破坏/被攻击打断 = 差）
     * @param sleepTicks    原版睡眠所需的完整时长（约 101 tick；被打断时可能不足）
     */
    public void recordWakeUp(long gameTime, boolean wakeImmediately, int sleepTicks) {
        float quality;
        if (wakeImmediately) {
            // 被打断：睡眠严重不足
            quality = 0.2f;
            // 没睡好 → 清醒天数不清零（继续累计）
            this.awakeDays++;
        } else {
            // 正常睡醒：睡眠时长足够 → 质量 1.0，清醒天数清零
            long slept = gameTime - this.lastSleepStartTick;
            quality = slept >= sleepTicks ? 1.0f : Math.max(0.2f, (float) slept / sleepTicks);
            this.awakeDays = 0;
        }
        this.lastSleepQuality = quality;
        this.lastSleepStartTick = -1L;
    }

    /** 是否处于"睡眠不足"状态（清醒 ≥ 2 天，由 P1 虚弱规则使用） */
    public boolean isSleepDeprived() {
        return awakeDays >= 2;
    }

    // ==================== 负重（P2 使用）====================

    public float getCurrentWeight() {
        return currentWeight;
    }

    public void setCurrentWeight(float currentWeight) {
        this.currentWeight = Math.max(0f, currentWeight);
    }

    // ==================== 温度（P3 使用）====================

    public float getTemperature() {
        return temperature;
    }

    public void setTemperature(float temperature) {
        this.temperature = temperature;
    }

    public float getTargetTemperature() {
        return targetTemperature;
    }

    public void setTargetTemperature(float targetTemperature) {
        this.targetTemperature = targetTemperature;
    }
}
