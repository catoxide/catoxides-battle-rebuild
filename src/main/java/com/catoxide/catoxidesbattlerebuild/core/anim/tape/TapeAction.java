package com.catoxide.catoxidesbattlerebuild.core.anim.tape;

import com.google.gson.JsonObject;

/**
 * 动作词汇（注册表风格：JSON "do" 字段按名派发；确定性、可单测）。
 * <p>JSON 形：{"do":"play_sound","sound":"entity/zombie/attack","vol":1.0,"pitch":0.85}
 * 动作只通过 {@link TapeOwner} 触碰实体——引擎本体保持纯 Java。
 */
public sealed interface TapeAction permits
        TapeAction.PlaySound, TapeAction.ApplyDamage, TapeAction.Swing,
        TapeAction.SetTag, TapeAction.SpawnParticle, TapeAction.Noop {

    String type();

    void execute(TapeContext ctx);

    record PlaySound(String sound, float vol, float pitch) implements TapeAction {
        @Override
        public String type() { return "play_sound"; }

        @Override
        public void execute(TapeContext ctx) {
            ctx.owner().playSound(sound, vol, pitch);
        }
    }

    record ApplyDamage(float amount) implements TapeAction {
        public ApplyDamage() { this(0f); }

        @Override
        public String type() { return "apply_damage"; }

        @Override
        public void execute(TapeContext ctx) {
            ctx.owner().applyDamage(amount);
        }
    }

    record Swing() implements TapeAction {
        @Override
        public String type() { return "swing"; }

        @Override
        public void execute(TapeContext ctx) {
            ctx.owner().swing();
        }
    }

    /** set_tag：写跨轨共享 tag（轨间唯一通信通道）；value 省略/空 = 清除 */
    record SetTag(String name, String value) implements TapeAction {
        @Override
        public String type() { return "set_tag"; }

        @Override
        public void execute(TapeContext ctx) {
            if (value == null || value.isEmpty()) {
                ctx.tags().remove(name);
            } else {
                ctx.tags().put(name, value);
            }
        }
    }

    record SpawnParticle(String id, float x, float y, float z, float vx, float vy, float vz) implements TapeAction {
        @Override
        public String type() { return "spawn_particle"; }

        @Override
        public void execute(TapeContext ctx) {
            ctx.owner().spawnParticle(id, x, y, z, vx, vy, vz);
        }
    }

    record Noop() implements TapeAction {
        @Override
        public String type() { return "none"; }

        @Override
        public void execute(TapeContext ctx) {}
    }

    /** 从 JSON 对象解析（"do" 字段派发） */
    static TapeAction parse(JsonObject o) {
        if (o == null || !o.has("do")) throw new TapeParseException("动作缺少 \"do\" 字段: " + o);
        String type = o.get("do").getAsString();
        return switch (type) {
            case "none" -> new Noop();
            case "swing" -> new Swing();
            case "apply_damage" -> new ApplyDamage(o.has("amount") ? o.get("amount").getAsFloat() : 0f);
            case "play_sound" -> new PlaySound(
                    str(o, "sound", ""),
                    o.has("vol") ? o.get("vol").getAsFloat() : 1.0f,
                    o.has("pitch") ? o.get("pitch").getAsFloat() : 1.0f);
            case "set_tag" -> new SetTag(str(o, "name", ""), o.has("value") ? o.get("value").getAsString() : null);
            case "spawn_particle" -> new SpawnParticle(
                    str(o, "id", ""),
                    f(o, "x"), f(o, "y"), f(o, "z"), f(o, "vx"), f(o, "vy"), f(o, "vz"));
            default -> throw new TapeParseException("未注册的动作类型: " + type);
        };
    }

    private static String str(JsonObject o, String key, String dflt) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : dflt;
    }

    private static float f(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsFloat() : 0f;
    }
}
