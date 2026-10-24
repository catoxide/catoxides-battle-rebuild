package com.catoxide.catoxidesbattlerebuild.core.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 配置声明层契约检查（纯 main()，无 JUnit；不启动游戏）。
 *
 * <p>运行：{@code gradlew packConfigCheck}
 *
 * <p>覆盖：
 * <ul>
 *   <li>default 解析：类型/默认值/注释/隐式 COLD/分区与键唯一性</li>
 *   <li>编译映射：路径、默认值、翻译键、RestartType、int 范围的 ceil/floor</li>
 *   <li>判死：SERVER+COLD、重复键、越界默认值、坏类型、坏 schema、非法 file</li>
 *   <li>current 覆盖：稀疏合并、只允许可编辑字段、非法整条作废、未知路径、hash 不匹配</li>
 *   <li>编译产物：bool→BooleanValue（可编辑）、int/double 专用类型、分区翻译键</li>
 *   <li>显示名：en_us 缺选项/分区/文件标题一律拒绝，resources/ 下的 lang 不算</li>
 *   <li>真实包：{@code contentpacks/compass-pack.jar} 的 4 个 HOT 选项与默认值</li>
 * </ul>
 *
 * <p>负向用例会在控制台打印 ERROR/WARN 日志，那是被测代码的预期输出，不是测试失败。
 */
public final class PackConfigDeclarationsCheck {

    private static int pass = 0;
    private static int fail = 0;
    private static Path tempDir;

    public static void main(String[] args) throws Exception {
        tempDir = Files.createTempDirectory("pack-config-check");

        basicDeclaration();
        compilerMapping();
        langCoverageCases();
        rejectionCases();
        overrideCases();
        realCompassPack();

        System.out.printf("%npack config declaration check: %d passed / %d failed%n", pass, fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ==================== default 解析 ====================

    private static void basicDeclaration() throws IOException {
        section("default declaration parsing");

        ConfigDecl decl = only(load("""
                {"schemaVersion":1,"configs":[{"id":"hud","scope":"CLIENT","file":"check-client","sections":[
                  {"key":"style","values":[
                    {"key":"flag","type":"bool","default":true},
                    {"key":"count","type":"int","default":3},
                    {"key":"ratio","type":"double","default":0.5},
                    {"key":"name","type":"string","default":"x"},
                    {"key":"list","type":"string_list","default":["a","b"]}
                  ]}
                ]}]}
                """, null));

        check("config id", "hud".equals(decl.id()));
        check("scope", decl.scope() == ConfigScope.CLIENT);
        check("file without extension", "check-client".equals(decl.file()));
        check("defaultTier defaults to COLD", decl.defaultTier() == ConfigTier.COLD);
        check("five values survive", countValues(decl) == 5);
        check("implicit COLD per value", value(decl, "flag").tier() == ConfigTier.COLD);
        check("bool default", Boolean.TRUE.equals(value(decl, "flag").defaultValue()));
        check("int default", Integer.valueOf(3).equals(value(decl, "count").defaultValue()));
        check("double default", Double.valueOf(0.5).equals(value(decl, "ratio").defaultValue()));
        check("string default", "x".equals(value(decl, "name").defaultValue()));
        check("string_list default", List.of("a", "b").equals(value(decl, "list").defaultValue()));
        check("comment absent -> empty", value(decl, "flag").comment().isEmpty());

        ConfigDecl comments = only(load("""
                {"schemaVersion":1,"configs":[{"id":"hud","scope":"CLIENT","file":"check-client-2","sections":[
                  {"key":"s","values":[
                    {"key":"multi","type":"bool","default":false,"comment":"line one\\nline two"},
                    {"key":"blank","type":"bool","default":false,"comment":"   \\n\\t "},
                    {"key":"array","type":"bool","default":false,"comment":["a","b"]}
                  ]}
                ]}]}
                """, null));
        check("comment string splits on newline", value(comments, "multi").comment().equals(List.of("line one", "line two")));
        check("all-blank comment dropped", value(comments, "blank").comment().isEmpty());
        check("comment array kept", value(comments, "array").comment().equals(List.of("a", "b")));
    }

    // ==================== 编译映射 ====================

    private static void compilerMapping() throws IOException {
        section("declaration -> ModConfigSpec");

        ConfigDecl decl = only(load("""
                {"schemaVersion":1,"configs":[{"id":"hud","scope":"CLIENT","file":"check-client","sections":[
                  {"key":"style","values":[
                    {"key":"flag","type":"bool","default":true,"tier":"HOT","comment":"flag help"},
                    {"key":"count","type":"int","default":2,"tier":"WARM","min":0.5,"max":10.5},
                    {"key":"ratio","type":"double","default":0.25,"tier":"HOT"},
                    {"key":"name","type":"string","default":"x","tier":"COLD"},
                    {"key":"list","type":"string_list","default":["a"],"tier":"HOT"}
                  ]}
                ]}]}
                """, null));

        ConfigSpecCompiler.Compiled compiled = ConfigSpecCompiler.compile(decl);
        Map<String, ModConfigSpec.ConfigValue<?>> values = compiled.values();
        check("all values compiled", values.size() == 5);
        check("path includes section", values.get("flag").getPath().equals(List.of("style", "flag")));
        check("compiled default", Boolean.TRUE.equals(values.get("flag").getDefault()));
        check("HOT -> NONE", values.get("flag").getSpec().restartType() == ModConfigSpec.RestartType.NONE);
        check("WARM -> WORLD", values.get("count").getSpec().restartType() == ModConfigSpec.RestartType.WORLD);
        check("COLD -> GAME", values.get("name").getSpec().restartType() == ModConfigSpec.RestartType.GAME);
        check("translation key",
                "catoxidesbattlerebuild.configuration.check.hud.style.flag"
                        .equals(values.get("flag").getSpec().getTranslationKey()));
        check("comment reaches spec", "flag help".equals(values.get("flag").getSpec().getComment()));
        check("string_list compiled default", List.of("a").equals(values.get("list").getDefault()));
        check("section translation key recorded for push",
                "catoxidesbattlerebuild.configuration.check.hud.style"
                        .equals(compiled.spec().getLevelTranslationKey(List.of("style"))));

        // 类型必须能被内置配置屏识别：裸 ConfigValue 会走 createOtherValue →「此值不能在 UI 中编辑」。
        check("bool compiles to BooleanValue (editable)", values.get("flag") instanceof ModConfigSpec.BooleanValue);
        check("int compiles to IntValue", values.get("count") instanceof ModConfigSpec.IntValue);
        check("double compiles to DoubleValue", values.get("ratio") instanceof ModConfigSpec.DoubleValue);
        check("string compiles to plain ConfigValue",
                values.get("name").getClass() == ModConfigSpec.ConfigValue.class);
        check("string_list compiles to ListValueSpec",
                values.get("list").getSpec() instanceof ModConfigSpec.ListValueSpec);

        @SuppressWarnings("unchecked")
        ModConfigSpec.Range<Integer> range =
                (ModConfigSpec.Range<Integer>) values.get("count").getSpec().getRange();
        check("int min ceils 0.5 -> 1", range.getMin() == 1);
        check("int max floors 10.5 -> 10", range.getMax() == 10);
    }

    // ==================== 判死 ====================

    private static void rejectionCases() throws IOException {
        section("rejections (each case must drop the config)");

        ConfigDecl serverSibling = only(load("""
                {"schemaVersion":1,"configs":[
                  {"id":"good","scope":"SERVER","file":"check-server-a","sections":[{"key":"s","values":[
                    {"key":"a","type":"bool","default":true,"tier":"HOT"}]}]},
                  {"id":"bad","scope":"SERVER","file":"check-server-b","sections":[{"key":"s","values":[
                    {"key":"b","type":"bool","default":true,"tier":"COLD"}]}]}
                ]}
                """, null));
        check("SERVER+COLD drops only that config", "good".equals(serverSibling.id()));
        check("SERVER+COLD config not registered", load("""
                {"schemaVersion":1,"configs":[{"id":"bad","scope":"SERVER","file":"check-server-c","sections":[
                  {"key":"s","values":[{"key":"b","type":"bool","default":true}]}]}]}
                """, null).isEmpty());

        expectDropped("duplicate value key across sections", """
                {"schemaVersion":1,"configs":[{"id":"a","scope":"CLIENT","file":"dup-key","sections":[
                  {"key":"s1","values":[{"key":"x","type":"bool","default":true}]},
                  {"key":"s2","values":[{"key":"x","type":"bool","default":true}]}]}]}
                """);
        ConfigDecl duplicateIds = only(load("""
                {"schemaVersion":1,"configs":[
                  {"id":"a","scope":"CLIENT","file":"dup-a","sections":[{"key":"s","values":[{"key":"x","type":"bool","default":true}]}]},
                  {"id":"a","scope":"CLIENT","file":"dup-b","sections":[{"key":"s","values":[{"key":"y","type":"bool","default":true}]}]}]}
                """, null));
        check("duplicate config id drops only the offender",
                duplicateIds != null && "dup-a".equals(duplicateIds.file()));
        expectDropped("non-integral int default", """
                {"schemaVersion":1,"configs":[{"id":"a","scope":"CLIENT","file":"bad-int","sections":[
                  {"key":"s","values":[{"key":"x","type":"int","default":1.5}]}]}]}
                """);
        expectDropped("default below min", """
                {"schemaVersion":1,"configs":[{"id":"a","scope":"CLIENT","file":"bad-range","sections":[
                  {"key":"s","values":[{"key":"x","type":"int","default":3,"min":5}]}]}]}
                """);
        expectDropped("wrong default type", """
                {"schemaVersion":1,"configs":[{"id":"a","scope":"CLIENT","file":"bad-type","sections":[
                  {"key":"s","values":[{"key":"x","type":"bool","default":1}]}]}]}
                """);
        expectDropped("unknown type", """
                {"schemaVersion":1,"configs":[{"id":"a","scope":"CLIENT","file":"bad-type2","sections":[
                  {"key":"s","values":[{"key":"x","type":"long","default":1}]}]}]}
                """);
        expectDropped("empty sections", """
                {"schemaVersion":1,"configs":[{"id":"a","scope":"CLIENT","file":"empty","sections":[]}]}
                """);
        expectDropped("path traversal in file", """
                {"schemaVersion":1,"configs":[{"id":"a","scope":"CLIENT","file":"../evil","sections":[
                  {"key":"s","values":[{"key":"x","type":"bool","default":true}]}]}]}
                """);
        expectDropped("unknown scope", """
                {"schemaVersion":1,"configs":[{"id":"a","scope":"WORLD","file":"bad-scope","sections":[
                  {"key":"s","values":[{"key":"x","type":"bool","default":true}]}]}]}
                """);
        expectDropped("unsupported schemaVersion", """
                {"schemaVersion":2,"configs":[{"id":"a","scope":"CLIENT","file":"schema","sections":[
                  {"key":"s","values":[{"key":"x","type":"bool","default":true}]}]}]}
                """);
        expectDropped("comment array with non-string", """
                {"schemaVersion":1,"configs":[{"id":"a","scope":"CLIENT","file":"bad-comment","sections":[
                  {"key":"s","values":[{"key":"x","type":"bool","default":true,"comment":["ok",1]}]}]}]}
                """);

        ConfigDecl unknownField = only(load("""
                {"schemaVersion":1,"configs":[{"id":"a","scope":"CLIENT","file":"unknown-field","sections":[
                  {"key":"s","values":[{"key":"x","type":"bool","default":true,"typo":"ignored"}]}]}]}
                """, null));
        check("unknown field only warns", unknownField != null);
    }

    // ==================== 强制显示名 ====================

    private static void langCoverageCases() throws IOException {
        section("forced English display names (en_us)");

        String declaration = """
                {"schemaVersion":1,"configs":[{"id":"hud","scope":"CLIENT","file":"lang-check-client","sections":[
                  {"key":"style","values":[
                    {"key":"flag","type":"bool","default":true},
                    {"key":"count","type":"int","default":1}
                  ]}
                ]}]}
                """;
        ConfigDecl decl = only(PackConfigDeclarations.load(jar(declaration), "check", null));
        if (decl == null) {
            check("declaration accepted for lang checks", false);
            return;
        }

        Set<String> required = ConfigLangCoverage.requiredKeys(decl);
        check("required = file + title + section + button + 2 options", required.size() == 6);

        String enUs = "assets/checkpack/lang/en_us.json";
        ConfigLangCoverage.Result complete = ConfigLangCoverage.check(
                jarWith(declaration, Map.of(enUs, langJson(required))), decl);
        check("complete en_us accepted", complete.complete() && complete.langFound());

        String optionKey = decl.translationKey("style", "flag");
        Set<String> withoutOption = new LinkedHashSet<>(required);
        withoutOption.remove(optionKey);
        ConfigLangCoverage.Result noOption = ConfigLangCoverage.check(
                jarWith(declaration, Map.of(enUs, langJson(withoutOption))), decl);
        check("missing option name detected",
                !noOption.complete() && noOption.missing().equals(List.of(optionKey)));

        String titleKey = decl.fileTranslationKey() + ".title";
        Set<String> withoutTitle = new LinkedHashSet<>(required);
        withoutTitle.remove(titleKey);
        ConfigLangCoverage.Result noTitle = ConfigLangCoverage.check(
                jarWith(declaration, Map.of(enUs, langJson(withoutTitle))), decl);
        check("missing page title detected",
                !noTitle.complete() && noTitle.missing().equals(List.of(titleKey)));

        String sectionButton = decl.sectionTranslationKey("style") + ".button";
        Set<String> withoutButton = new LinkedHashSet<>(required);
        withoutButton.remove(sectionButton);
        ConfigLangCoverage.Result noButton = ConfigLangCoverage.check(
                jarWith(declaration, Map.of(enUs, langJson(withoutButton))), decl);
        check("missing section button name detected",
                !noButton.complete() && noButton.missing().equals(List.of(sectionButton)));

        boolean correctFileKey = ConfigLangCoverage.requiredKeys(decl)
                .contains("catoxidesbattlerebuild.configuration.section.lang.check.client.toml");
        check("file key matches NeoForge file-name sanitization", correctFileKey);

        ConfigLangCoverage.Result noLang = ConfigLangCoverage.check(jar(declaration), decl);
        check("no en_us rejects the config",
                !noLang.langFound() && noLang.missing().size() == required.size());

        ConfigLangCoverage.Result underResources = ConfigLangCoverage.check(
                jarWith(declaration, Map.of("resources/" + enUs, langJson(required))), decl);
        check("lang under resources/ does not count", !underResources.langFound());

        com.google.gson.JsonObject bad = new com.google.gson.JsonObject();
        for (String key : required) {
            bad.addProperty(key, "Name for " + key);
        }
        bad.addProperty(optionKey, "   ");
        bad.addProperty(decl.sectionTranslationKey("style"), 42);
        ConfigLangCoverage.Result blank = ConfigLangCoverage.check(
                jarWith(declaration, Map.of(enUs, bad.toString())), decl);
        check("blank / non-string names count as missing",
                blank.missing().equals(List.of(decl.sectionTranslationKey("style"), optionKey)));

        // 语言表是全局的：名字可以分散在同一 jar 的不同命名空间
        Set<String> firstHalf = new LinkedHashSet<>();
        Set<String> secondHalf = new LinkedHashSet<>();
        int index = 0;
        for (String key : required) {
            (index++ % 2 == 0 ? firstHalf : secondHalf).add(key);
        }
        ConfigLangCoverage.Result split = ConfigLangCoverage.check(jarWith(declaration, Map.of(
                "assets/a/lang/en_us.json", langJson(firstHalf),
                "assets/b/lang/en_us.json", langJson(secondHalf))), decl);
        check("names merge across namespaces", split.complete());

        ConfigLangCoverage.Result malformed = ConfigLangCoverage.check(
                jarWith(declaration, Map.of(enUs, "{not json")), decl);
        check("malformed en_us counts as missing", !malformed.complete());
    }

    private static void expectDropped(String name, String json) throws IOException {
        check(name, load(json, null).isEmpty());
    }

    // ==================== current 覆盖 ====================

    private static void overrideCases() throws IOException {
        section("current overrides");

        String base = """
                {"schemaVersion":1,"configs":[{"id":"hud","scope":"CLIENT","file":"check-client","sections":[
                  {"key":"style","values":[
                    {"key":"flag","type":"bool","default":true},
                    {"key":"count","type":"int","default":3},
                    {"key":"other","type":"string","default":"keep"}
                  ]}
                ]}]}
                """;

        ConfigDecl sparse = only(load(base, current("""
                {"schemaVersion":1,"overrides":{
                  "hud/style/flag":{"default":false},
                  "hud/style/count":{"default":7,"comment":"changed"}
                }}
                """)));
        check("sparse override applies", Boolean.FALSE.equals(value(sparse, "flag").defaultValue()));
        check("sparse override int", Integer.valueOf(7).equals(value(sparse, "count").defaultValue()));
        check("unmentioned key keeps default", "keep".equals(value(sparse, "other").defaultValue()));
        check("override comment", value(sparse, "count").comment().equals(List.of("changed")));

        ConfigDecl commentTier = only(load(base, current("""
                {"schemaVersion":1,"overrides":{"hud/style/flag":{"comment":"a\\nb","tier":"HOT"}}}
                """)));
        check("override comment splits", value(commentTier, "flag").comment().equals(List.of("a", "b")));
        check("override tier", value(commentTier, "flag").tier() == ConfigTier.HOT);

        ConfigDecl invalid = only(load(base, current("""
                {"schemaVersion":1,"overrides":{"hud/style/count":{"default":5,"min":10}}}
                """)));
        check("invalid override ignored entirely", Integer.valueOf(3).equals(value(invalid, "count").defaultValue()));
        check("invalid override keeps original bounds", value(invalid, "count").min() == null);

        ConfigDecl wrongType = only(load(base, current("""
                {"schemaVersion":1,"overrides":{"hud/style/count":{"default":"nope"}}}
                """)));
        check("wrong override type ignored", Integer.valueOf(3).equals(value(wrongType, "count").defaultValue()));

        ConfigDecl structural = only(load(base, current("""
                {"schemaVersion":1,"overrides":{"hud/style/flag":{"type":"int"}}}
                """)));
        check("structure field cannot be overridden", value(structural, "flag").type() == ValueType.BOOL);

        ConfigDecl unknownPath = only(load(base, current("""
                {"schemaVersion":1,"overrides":{"hud/style/missing":{"default":1},"hud/nope/x":{"default":1}}}
                """)));
        check("unknown override path ignored", unknownPath != null && countValues(unknownPath) == 3);

        ConfigDecl hashMismatch = only(load(base, current("""
                {"schemaVersion":1,"basedOnDefaultHash":"deadbeef","overrides":{"hud/style/flag":{"default":false}}}
                """)));
        check("hash mismatch still applies overrides", Boolean.FALSE.equals(value(hashMismatch, "flag").defaultValue()));

        ConfigDecl badSchema = only(load(base, current("""
                {"schemaVersion":2,"overrides":{"hud/style/flag":{"default":false}}}
                """)));
        check("unsupported current schema ignored", Boolean.TRUE.equals(value(badSchema, "flag").defaultValue()));

        ConfigDecl noOverrides = only(load(base, current("""
                {"schemaVersion":1}
                """)));
        check("missing overrides ignored", Boolean.TRUE.equals(value(noOverrides, "flag").defaultValue()));

        ConfigDecl serverCold = only(load("""
                {"schemaVersion":1,"configs":[{"id":"srv","scope":"SERVER","file":"check-server-d","sections":[
                  {"key":"s","values":[{"key":"x","type":"bool","default":true,"tier":"HOT"}]}]}]}
                """, current("""
                {"schemaVersion":1,"overrides":{"srv/s/x":{"tier":"COLD"}}}
                """)));
        check("SERVER+COLD override rejected", value(serverCold, "x").tier() == ConfigTier.HOT);
    }

    // ==================== 真实包 ====================

    private static void realCompassPack() throws IOException {
        section("real pack: compass-pack.jar");

        File jar = new File("contentpacks/compass-pack.jar");
        if (!jar.isFile()) {
            check("compass-pack.jar present (run contentpackJar first)", false);
            return;
        }

        List<ConfigDecl> decls = PackConfigDeclarations.load(jar, "compass-pack", null);
        check("one declared config", decls.size() == 1);
        if (decls.isEmpty()) {
            return;
        }
        ConfigDecl decl = decls.get(0);
        check("id hud", "hud".equals(decl.id()));
        check("scope CLIENT", decl.scope() == ConfigScope.CLIENT);
        check("file compass-pack-client", "compass-pack-client".equals(decl.file()));
        check("four options", countValues(decl) == 4);
        check("fovTicks default false", Boolean.FALSE.equals(value(decl, "fovTicks").defaultValue()));
        check("numberOnly default false", Boolean.FALSE.equals(value(decl, "numberOnly").defaultValue()));
        check("thickBar default false", Boolean.FALSE.equals(value(decl, "thickBar").defaultValue()));
        check("frostedBackground default true", Boolean.TRUE.equals(value(decl, "frostedBackground").defaultValue()));
        check("all options HOT", decl.sections().stream()
                .flatMap(s -> s.values().stream())
                .allMatch(v -> v.tier() == ConfigTier.HOT));

        ConfigSpecCompiler.Compiled compiled = ConfigSpecCompiler.compile(decl);
        check("compass translation key",
                "catoxidesbattlerebuild.configuration.compass-pack.hud.style.fovTicks"
                        .equals(compiled.values().get("fovTicks").getSpec().getTranslationKey()));
        check("compass options are UI-editable BooleanValue",
                compiled.values().values().stream().allMatch(v -> v instanceof ModConfigSpec.BooleanValue));

        ConfigLangCoverage.Result coverage = ConfigLangCoverage.check(jar, decl);
        check("compass en_us covers every display name (" + coverage.missing().size() + " missing)",
                coverage.complete());

        // 面板显示的是翻译键；缺 lang 不崩溃但会露出原始键名，这里把「键名与 lang 同步」变成断言。
        Path lang = Path.of("contentpacks-source/compass-pack/assets/skyrimcompasshud/lang/en_us.json");
        check("compass lang file present", Files.isRegularFile(lang));
        if (Files.isRegularFile(lang)) {
            com.google.gson.JsonObject translations = com.google.gson.JsonParser
                    .parseString(Files.readString(lang, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            boolean allTranslated = compiled.values().values().stream()
                    .allMatch(v -> translations.has(v.getSpec().getTranslationKey()));
            check("every option has a lang entry", allTranslated);
            check("section button lang entry",
                    translations.has("catoxidesbattlerebuild.configuration.section.compass.pack.client.toml"));
            check("section title lang entry",
                    translations.has("catoxidesbattlerebuild.configuration.section.compass.pack.client.toml.title"));
            check("option section header lang entry",
                    translations.has("catoxidesbattlerebuild.configuration.compass-pack.hud.style"));
            check("option section button lang entry",
                    translations.has("catoxidesbattlerebuild.configuration.compass-pack.hud.style.button"));
        }
    }

    // ==================== helpers ====================

    private static List<ConfigDecl> load(String declaration, Path current) throws IOException {
        return PackConfigDeclarations.load(jar(declaration), "check", current);
    }

    private static File jar(String declaration) throws IOException {
        return jarWith(declaration, Map.of());
    }

    private static File jarWith(String declaration, Map<String, String> entries) throws IOException {
        Path jar = Files.createTempFile(tempDir, "pack", ".jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry(PackConfigDeclarations.JAR_ENTRY));
            out.write(declaration.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar.toFile();
    }

    private static String langJson(Set<String> keys) {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        for (String key : keys) {
            json.addProperty(key, "Name for " + key);
        }
        return json.toString();
    }

    private static Path current(String json) throws IOException {
        Path file = Files.createTempFile(tempDir, "current", ".json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private static ConfigDecl only(List<ConfigDecl> decls) {
        return decls.size() == 1 ? decls.get(0) : null;
    }

    private static ValueDecl value(ConfigDecl decl, String key) {
        if (decl == null) {
            throw new AssertionError("declaration missing (expected it to be accepted)");
        }
        Map<String, ValueDecl> byKey = new LinkedHashMap<>();
        for (SectionDecl section : decl.sections()) {
            for (ValueDecl value : section.values()) {
                byKey.put(value.key(), value);
            }
        }
        ValueDecl value = byKey.get(key);
        if (value == null) {
            throw new AssertionError("missing value '" + key + "'");
        }
        return value;
    }

    private static int countValues(ConfigDecl decl) {
        int count = 0;
        for (SectionDecl section : decl.sections()) {
            count += section.values().size();
        }
        return count;
    }

    private static void section(String name) {
        System.out.println("\n== " + name + " ==");
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }
}
