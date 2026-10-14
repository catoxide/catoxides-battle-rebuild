package com.catoxide.catoxidesbattlerebuild.core.anim.tape;

import java.util.List;

/**
 * 声明式条件词汇（确定性、可单测；不用 JS/MoLang）。
 * <p>字符串形式（JSON 里 "when": "all(has_target, not(is_moving))"）：
 * <ul>
 *   <li>实体布尔：{@code is_moving / is_sprinting / has_target / is_on_ground / is_in_water / is_on_climbable / died / swinging}</li>
 *   <li>tag 读：{@code hit}（tag 存在且为 "true" 或非空）· {@code tag(hit_dir)=front} · {@code tag(hit_dir)!=back}</li>
 *   <li>组合：{@code all(a, b, …)} · {@code any(…)} · {@code not(a)}</li>
 * </ul>
 * 解析失败抛 {@link TapeParseException}（带表达式文本）。
 */
public sealed interface TapeCondition permits
        TapeCondition.Flag, TapeCondition.TagTest, TapeCondition.All, TapeCondition.Any, TapeCondition.Not {

    boolean check(TapeContext ctx);

    record Flag(String name) implements TapeCondition {
        @Override
        public boolean check(TapeContext ctx) {
            return switch (name) {
                case "is_moving" -> ctx.owner().isMoving();
                case "is_sprinting" -> ctx.owner().isSprinting();
                case "has_target" -> ctx.owner().hasTarget();
                case "is_on_ground" -> ctx.owner().isOnGround();
                case "is_in_water" -> ctx.owner().isInWater();
                case "is_on_climbable" -> ctx.owner().isOnClimbable();
                case "died" -> ctx.owner().isDead();
                case "swinging" -> ctx.owner().isSwinging();
                default -> {
                    // 未识别的裸名 = tag 读（存在且非 "false"/空）
                    String v = ctx.tags().get(name);
                    yield v != null && !v.isEmpty() && !"false".equals(v);
                }
            };
        }
    }

    /** tag(name)=value / tag(name)!=value / tag(name)（存在且非 false/空） */
    record TagTest(String tag, String op, String value) implements TapeCondition {
        @Override
        public boolean check(TapeContext ctx) {
            String v = ctx.tags().get(tag);
            return switch (op) {
                case "=" -> value.equals(v);
                case "!=" -> !value.equals(v);
                default -> v != null && !v.isEmpty() && !"false".equals(v);
            };
        }
    }

    record All(List<TapeCondition> parts) implements TapeCondition {
        @Override
        public boolean check(TapeContext ctx) {
            for (TapeCondition c : parts) if (!c.check(ctx)) return false;
            return true;
        }
    }

    record Any(List<TapeCondition> parts) implements TapeCondition {
        @Override
        public boolean check(TapeContext ctx) {
            for (TapeCondition c : parts) if (c.check(ctx)) return true;
            return false;
        }
    }

    record Not(TapeCondition inner) implements TapeCondition {
        @Override
        public boolean check(TapeContext ctx) {
            return !inner.check(ctx);
        }
    }

    // ==================== 解析器（递归下降，括号配平 + 顶层逗号切分） ====================

    /** 解析声明式条件字符串（入口） */
    static TapeCondition parse(String s) {
        try {
            return parseExpr(s.trim());
        } catch (TapeParseException e) {
            throw e;
        } catch (Exception e) {
            throw new TapeParseException("条件解析失败 [" + s + "]: " + e.getMessage());
        }
    }

    private static TapeCondition parseExpr(String s) {
        if (s.isEmpty()) throw new TapeParseException("空条件");
        if (startsWithFn(s, "all(")) return new All(parseList(s, "all"));
        if (startsWithFn(s, "any(")) return new Any(parseList(s, "any"));
        if (startsWithFn(s, "not(")) {
            String inner = stripParens(s, "not");
            return new Not(parseExpr(inner));
        }
        // tag(x) / tag(x)=v / tag(x)!=v
        // 2026-10-14：旧实现 startsWithFn 要求整串以 ) 结尾——文档化的 tag(x)=v / tag(x)!=v
        //（以值结尾）从未被解析（潜在 bug，编辑器 JS 移植测试抓到）。修复：按第一个 ) 切分 name 与操作符。
        if (s.startsWith("tag(")) {
            int close = s.indexOf(')');
            if (close < 0) throw new TapeParseException("tag 条件括号不配平 [" + s + "]");
            String name = s.substring(4, close).trim();
            String rest = s.substring(close + 1).trim();
            if (rest.startsWith("!=")) return new TagTest(name, "!=", rest.substring(2).trim());
            if (rest.startsWith("=")) return new TagTest(name, "=", rest.substring(1).trim());
            if (rest.isEmpty()) return new TagTest(name, "", null);
            throw new TapeParseException("tag 条件语法错误 [" + s + "]");
        }
        // 实体布尔 / 裸 tag
        for (String flag : new String[]{"is_moving", "is_sprinting", "has_target", "is_on_ground",
                "is_in_water", "is_on_climbable", "died", "swinging"}) {
            if (s.equals(flag)) return new Flag(flag);
        }
        if (s.matches("[A-Za-z_][A-Za-z0-9_:.]*")) return new Flag(s); // 裸 tag
        throw new TapeParseException("无法识别的条件 [" + s + "]");
    }

    private static boolean startsWithFn(String s, String fn) {
        return s.startsWith(fn) && s.endsWith(")");
    }

    /** 取 fn(...) 括号内内容（fn 后到末尾最后一个 ')'） */
    private static String stripParens(String s, String fn) {
        int open = s.indexOf('(');
        int close = s.lastIndexOf(')');
        if (open < 0 || close <= open) throw new TapeParseException("括号不配平 [" + s + "]");
        return s.substring(open + 1, close);
    }

    private static List<TapeCondition> parseList(String s, String fn) {
        String body = stripParens(s, fn);
        List<TapeCondition> out = new java.util.ArrayList<>();
        if (body.trim().isEmpty()) return out;
        for (String part : splitTop(body)) {
            out.add(parseExpr(part.trim()));
        }
        return out;
    }

    /** 顶层逗号切分（括号内的逗号不切） */
    private static List<String> splitTop(String s) {
        List<String> out = new java.util.ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }
}
