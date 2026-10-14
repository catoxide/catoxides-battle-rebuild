package com.catoxide.catoxidesbattlerebuild.core.anim.tape;

import java.util.Map;

/**
 * 引擎执行上下文（每 tick 每轨一个；动作/条件经它触碰实体与 tag）。
 *
 * @param owner   实体能力
 * @param tags    跨轨共享 tag 存储（同一 Map 实例，轨间通信通道）
 * @param track   当前轨 id
 * @param state   当前状态名
 * @param t       当前状态本地时钟（秒，只增）
 */
public record TapeContext(TapeOwner owner, Map<String, String> tags, String track, String state, double t) {}
