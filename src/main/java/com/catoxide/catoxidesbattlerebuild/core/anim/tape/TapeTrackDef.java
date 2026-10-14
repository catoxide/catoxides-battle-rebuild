package com.catoxide.catoxidesbattlerebuild.core.anim.tape;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 轨（并行状态机）：实体 = 多条轨（body/weapon/…），共享同一时钟与 tag 存储。
 *
 * @param id      轨 id
 * @param initial 初始状态名
 * @param states  状态表（保持插入序）
 */
public record TapeTrackDef(String id, String initial, Map<String, TapeStateDef> states) {

    public TapeTrackDef {
        states = states == null ? new LinkedHashMap<>() : states;
    }

    public TapeStateDef state(String name) {
        TapeStateDef s = states.get(name);
        if (s == null) throw new TapeParseException("轨 " + id + " 引用了不存在的状态: " + name);
        return s;
    }
}
