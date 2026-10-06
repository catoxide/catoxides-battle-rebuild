package com.catoxide.catoxidesbattlerebuild.core.contentpack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * ContentPack <b>数据面预检</b>：在 {@code data/} 被挂载之前，静态判定一个包的数据是否会让
 * {@code RegistryDataLoader} 中止。
 *
 * <h3>为什么需要它</h3>
 * 代码面失败（类加载/register/init）已由 {@link ContentPackGraph} 隔离。但数据面失败是另一回事：
 * 一个键名写错的 worldgen JSON、或一个不是 gzip 的 {@code .nbt}，会让注册表加载直接 abort ——
 * {@code Failed to load registries} ⇒ <b>世界根本打不开，只能进安全模式</b>。这类错误
 * 不抛异常、不经过任何 Java 代码路径，所以光靠 catch 是拦不住的，必须在挂载前静态查。
 *
 * <h3>它在流程里的位置</h3>
 * <pre>
 * mod 构造函数        → loadAll()：扫描 → 依赖图 → <b>本类预检</b> → 隔离 → 加载/注册
 *    ↓
 * AddPackFindersEvent → ContentPackRepositorySource.loadPacks()   ← 这里才挂 data/
 *    ↓
 * RegistryDataLoader  → 读 data/**&#47;worldgen/**                ← 坏数据在这里致命
 * </pre>
 * 预检失败的包走与代码失败<b>完全相同</b>的隔离路径（{@link ContentPackGraph#fail}）：
 * 本包 FAILED、硬依赖它的包连带取消、{@code data/} 不挂载、其余包与游戏不受影响。
 *
 * <h3>保守原则（很重要）</h3>
 * 误判 = 一个好包被静默取消，比漏判更难排查。因此本类<b>只</b>报告「确定性致命」的问题：
 * <ul>
 *   <li>{@code data/} 下 JSON 解析失败（MC 用非 lenient 解析器，这里保持一致）</li>
 *   <li>worldgen 必填字段缺失 / 枚举值非法 / 类型错误 / 数值越界</li>
 *   <li>引用了<b>内容包命名空间</b>里不存在的 id（悬空引用 = unbound value = 致命）</li>
 *   <li>{@code .nbt} 不是合法的 gzip + NBT 结构（见 {@link #validateNbt}）</li>
 * </ul>
 * 明确<b>不</b>报告（无法在运行期核实，或核实会误伤）：
 * <ul>
 *   <li>未知字段（需要 vanilla 的字段画像；离线脚本 {@code .scratch/data-lint} 仍覆盖这一项）</li>
 *   <li>{@code minecraft:} 或任何非内容包命名空间的引用（假定 vanilla 存在）</li>
 *   <li>包自定义的类型（自定义 structure type / pool element type 的字段集只有包自己知道）</li>
 *   <li>{@code assets/} 的 JSON（坏模型只会让资源重载报错，不会 brick 世界 —— 不因此取消整个包）</li>
 *   <li>{@code data/<ns>/structure/} 之外的 {@code .nbt}（属主自己解释，MC 的结构系统不会读它）</li>
 *   <li>「值被字节序交换」的 {@code .nbt}（结构上完全合法，静态查不出来）</li>
 * </ul>
 *
 * <h3>纯粹性</h3>
 * 除 Gson 外不依赖任何东西（连 Minecraft 类都不用），不写日志、不碰全局状态，返回问题列表而非抛异常，
 * 因此可以脱离游戏单独跑（见 {@code .scratch/pack-data-check}）。
 */
public final class ContentPackDataValidator {

    private ContentPackDataValidator() {}

    /** NBT 解析配额（字节）：畸形包可以用极高压缩比或超大长度声明把堆吃光，这里给个上限。 */
    private static final long NBT_QUOTA = 16L * 1024 * 1024;

    /** NBT 标签类型范围（1 = TAG_Byte … 12 = TAG_LongArray；0 = TAG_End 只在载荷内部合法）。 */
    private static final int TAG_MIN = 1;
    private static final int TAG_MAX = 12;

    private static final int MAX_NBT_DEPTH = 512;

    private static final String DATA_PREFIX = "data/";
    private static final String TAGS_PREFIX = "tags/";
    private static final String JSON_SUFFIX = ".json";
    private static final String NBT_SUFFIX = ".nbt";

    /** 合法的资源定位符：{@code namespace:path}。 */
    private static final Pattern RESOURCE_LOCATION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private static final String JIGSAW = "minecraft:jigsaw";
    private static final String RANDOM_SPREAD = "minecraft:random_spread";
    private static final String CONCENTRIC_RINGS = "minecraft:concentric_rings";

    private static final Set<String> STEP_NAMES = Set.of(
            "raw_generation", "lakes", "underground_structures", "surface_structures", "strongholds",
            "underground_ores", "underground_decoration", "fluid_springs", "vegetal_decoration",
            "top_layer_modification");

    private static final Set<String> TERRAIN_ADAPTATIONS = Set.of(
            "none", "beard_thin", "beard_box", "bury", "encapsulate");

    private static final Set<String> HEIGHTMAPS = Set.of(
            "WORLD_SURFACE_WG", "WORLD_SURFACE", "OCEAN_FLOOR_WG", "OCEAN_FLOOR",
            "MOTION_BLOCKING", "MOTION_BLOCKING_NO_LEAVES");

    /** HeightProvider 的变体键（1.21.1）。命中任意一个即视为合法的 provider 形态。 */
    private static final Set<String> HEIGHT_PROVIDER_KEYS = Set.of(
            "absolute", "constant", "uniform", "biased_to_bottom", "very_biased_to_bottom",
            "trapezoid", "weighted_list");

    private static final Set<String> POOL_ELEMENT_TYPES = Set.of(
            "minecraft:single_pool_element", "minecraft:legacy_single_pool_element",
            "minecraft:list_pool_element", "minecraft:feature_pool_element",
            "minecraft:empty_pool_element");

    private static final Set<String> PROJECTIONS = Set.of("rigid", "terrain_matching");

    /** 一个数据问题。{@code entry} 是 jar 内路径，便于直接定位。 */
    public record Issue(String entry, String message) {
        @Override
        public String toString() {
            return entry + " : " + message;
        }
    }

    // ------------------------------------------------------------------
    // 跨包索引：解析「引用是否存在」需要知道所有包提供了什么
    // ------------------------------------------------------------------

    /**
     * 所有已发现包的数据条目索引（只有路径，不读内容）。
     *
     * <p>键的构造规则刻意与资源定位符对齐，使「引用 → 条目」是一次直接查找：
     * <ul>
     *   <li>{@code data/<ns>/worldgen/structure/foo.json} → json {@code <ns>:worldgen/structure/foo}</li>
     *   <li>{@code data/<ns>/structure/foo.nbt} → nbt {@code <ns>:structure/foo}</li>
     *   <li>{@code data/<ns>/tags/worldgen/biome/bar.json} → tag {@code <ns>:worldgen/biome/bar}</li>
     * </ul>
     */
    public static final class Index {

        private final Map<String, String> json = new HashMap<>();
        private final Map<String, String> nbt = new HashMap<>();
        private final Map<String, String> tags = new HashMap<>();
        private final Set<String> namespaces = new HashSet<>();

        /** 内容包提供数据的全部命名空间：用来区分「内容包自己的 id」与「vanilla/别的 mod 的 id」。 */
        public Set<String> namespaces() {
            return namespaces;
        }

        public boolean hasJson(String kind, String id) {
            return lookup(json, kind, id);
        }

        public boolean hasNbt(String kind, String id) {
            return lookup(nbt, kind, id);
        }

        public boolean hasTag(String kind, String id) {
            return lookup(tags, kind, id);
        }

        private static boolean lookup(Map<String, String> map, String kind, String id) {
            String ns = namespaceOf(id);
            String path = pathOf(id);
            return ns != null && path != null && map.containsKey(ns + ":" + kind + "/" + path);
        }
    }

    /** 建立跨包索引。不需要读文件内容，因此可以先于预检覆盖所有包。 */
    public static Index indexAll(List<File> jars) {
        Index index = new Index();
        for (File jar : jars) {
            if (jar == null || !jar.isFile()) {
                continue;
            }
            try (JarFile jf = new JarFile(jar)) {
                Enumeration<JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String name = entry.getName();
                    if (!name.startsWith(DATA_PREFIX)) {
                        continue;
                    }
                    // jar 路径用 '/' 分隔（不是资源定位符的 ':'），所以这里按第一个 '/' 切命名空间
                    String rel = name.substring(DATA_PREFIX.length());
                    int slash = rel.indexOf('/');
                    if (slash <= 0 || slash == rel.length() - 1) {
                        continue;
                    }
                    String ns = rel.substring(0, slash);
                    String rest = rel.substring(slash + 1);
                    index.namespaces.add(ns);
                    String where = jar.getName() + "!" + name;
                    if (rest.startsWith(TAGS_PREFIX) && rest.endsWith(JSON_SUFFIX)) {
                        index.tags.put(ns + ":" + strip(rest, TAGS_PREFIX.length(), JSON_SUFFIX.length()), where);
                    } else if (rest.endsWith(JSON_SUFFIX)) {
                        index.json.put(ns + ":" + strip(rest, 0, JSON_SUFFIX.length()), where);
                    } else if (rest.endsWith(NBT_SUFFIX)) {
                        index.nbt.put(ns + ":" + strip(rest, 0, NBT_SUFFIX.length()), where);
                    }
                }
            } catch (IOException ignored) {
                // 打不开的 jar 会在加载阶段以更明确的方式失败，这里不重复报
            }
        }
        return index;
    }

    // ------------------------------------------------------------------
    // 预检
    // ------------------------------------------------------------------

    /**
     * 预检一个包的数据载荷。
     *
     * @param jar   包 jar
     * @param index 跨包索引（{@link #indexAll}）
     * @return 问题列表；空列表 = 通过。调用方负责把非空结果转成一次隔离。
     */
    public static List<Issue> validate(File jar, Index index) {
        List<Issue> issues = new ArrayList<>();
        try (JarFile jf = new JarFile(jar)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!name.startsWith(DATA_PREFIX)) {
                    continue;
                }
                if (name.endsWith(JSON_SUFFIX)) {
                    JsonObject root = parseObject(jf, entry, issues);
                    if (root != null) {
                        validateDataJson(name, root, index, issues);
                    }
                } else if (name.endsWith(NBT_SUFFIX) && isStructureTemplatePath(name)) {
                    validateNbt(jf, entry, issues);
                }
            }
        } catch (IOException ex) {
            issues.add(new Issue(jar.getName(), "cannot read content pack jar: " + ex.getMessage()));
        }
        return issues;
    }

    /** {@code data/<ns>/structure/...nbt}：MC 结构模板系统唯一会读的路径。 */
    private static boolean isStructureTemplatePath(String name) {
        String rel = name.substring(DATA_PREFIX.length());
        int slash = rel.indexOf('/');
        return slash > 0 && rel.substring(slash + 1).startsWith("structure/");
    }

    /** 一句话总结，给隔离日志用。 */
    public static String summarize(List<Issue> issues) {
        if (issues.isEmpty()) {
            return "no data problems";
        }
        String first = issues.get(0).toString();
        return issues.size() == 1
                ? "1 data problem: " + first
                : issues.size() + " data problems, first: " + first;
    }

    // ------------------------------------------------------------------
    // JSON：解析 + 按类型分派
    // ------------------------------------------------------------------

    /**
     * 解析一个 {@code data/} 下的 JSON 对象。
     *
     * <p>用 Gson 的默认（非 lenient）解析器：MC 的 {@code RegistryDataLoader} 同样不接受
     * 尾逗号之类的宽松写法，所以这里判失败 = MC 也判失败。开头 BOM 会被剥掉（编辑器常见产物）。
     */
    private static JsonObject parseObject(JarFile jf, JarEntry entry, List<Issue> issues) {
        String text;
        try (InputStream in = jf.getInputStream(entry)) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            issues.add(new Issue(entry.getName(), "unreadable entry: " + ex.getMessage()));
            return null;
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        JsonElement element;
        try {
            element = JsonParser.parseString(text);
        } catch (JsonParseException | IllegalStateException ex) {
            issues.add(new Issue(entry.getName(),
                    "malformed JSON - the registry loader aborts on this (world would be unopenable): " + ex.getMessage()));
            return null;
        }
        if (!element.isJsonObject()) {
            issues.add(new Issue(entry.getName(), "top level must be a JSON object, got " + kindOf(element)));
            return null;
        }
        return element.getAsJsonObject();
    }

    private static void validateDataJson(String name, JsonObject root, Index index, List<Issue> issues) {
        String rel = name.substring(DATA_PREFIX.length());
        int slash = rel.indexOf('/');
        if (slash <= 0) {
            return;
        }
        String rest = rel.substring(slash + 1);
        if (rest.startsWith("worldgen/structure/") && !rest.substring("worldgen/structure/".length()).contains("/")) {
            validateStructure(name, root, index, issues);
        } else if (rest.startsWith("worldgen/structure_set/") && !rest.substring("worldgen/structure_set/".length()).contains("/")) {
            validateStructureSet(name, root, index, issues);
        } else if (rest.startsWith("worldgen/template_pool/")) {
            validateTemplatePool(name, root, index, issues);
        }
        // 其余 data/**.json 只做了上面的「可解析性」检查：
        // 配方/战利品表/进度/标签的 schema 由各自的 codec 掌管，写坏它们同样是致命的，
        // 但字段规则太杂，硬编码一份只会带来误判 —— 交给 JSON 解析这一道统拦。
    }

    /** {@code worldgen/structure/*.json}（jigsaw）。 */
    private static void validateStructure(String where, JsonObject root, Index index, List<Issue> issues) {
        requireKeys(root, where, issues, "type");
        String type = stringField(root, "type", where, issues);
        if (type == null) {
            return;
        }
        if (!JIGSAW.equals(type)) {
            // 非 jigsaw（含包自定义 structure type）：字段集未知，只保留可解析性检查
            return;
        }

        requireKeys(root, where, issues,
                "biomes", "step", "spawn_overrides", "start_pool", "size", "start_height", "use_expansion_hack");
        requireEnum(root, "step", STEP_NAMES, where, issues);
        requireEnum(root, "terrain_adaptation", TERRAIN_ADAPTATIONS, where, issues);
        requireEnum(root, "project_start_to_heightmap", HEIGHTMAPS, where, issues);
        requireBoolean(root, "use_expansion_hack", where, issues);

        JsonElement spawnOverrides = root.get("spawn_overrides");
        if (isPresent(spawnOverrides) && !spawnOverrides.isJsonObject()) {
            issues.add(new Issue(where, "'spawn_overrides' must be an object keyed by mob category (use {} for none), got " + kindOf(spawnOverrides)));
        }

        JsonElement biomes = root.get("biomes");
        if (isPresent(biomes)) {
            if (biomes.isJsonArray()) {
                for (JsonElement biome : biomes.getAsJsonArray()) {
                    checkBiomeRef(where, asString(biome), index, issues);
                }
            } else if (biomes.isJsonPrimitive() && biomes.getAsJsonPrimitive().isString()) {
                checkBiomeRef(where, biomes.getAsString(), index, issues);
            } else {
                issues.add(new Issue(where, "'biomes' must be a biome tag string or a list of biome ids, got " + kindOf(biomes)));
            }
        }

        String startPool = stringField(root, "start_pool", where, issues);
        if (startPool != null) {
            checkRef(where, "start_pool", startPool, "worldgen/template_pool", index, issues, false);
        }

        Integer size = intField(root, "size", where, issues);
        if (size != null && (size < 1 || size > 128)) {
            issues.add(new Issue(where, "'size' must be in 1..128, got " + size));
        }
        Integer maxDistance = intField(root, "max_distance_from_center", where, issues);
        if (maxDistance != null && (maxDistance < 1 || maxDistance > 128)) {
            issues.add(new Issue(where, "'max_distance_from_center' must be in 1..128, got " + maxDistance));
        }

        JsonElement startHeight = root.get("start_height");
        if (isPresent(startHeight)) {
            if (!startHeight.isJsonObject()) {
                issues.add(new Issue(where, "'start_height' must be a HeightProvider object, got " + kindOf(startHeight)));
            } else if (startHeight.getAsJsonObject().keySet().stream().noneMatch(HEIGHT_PROVIDER_KEYS::contains)) {
                issues.add(new Issue(where, "'start_height' is not a HeightProvider ({absolute:n} / {uniform:{...}}); got keys: "
                        + startHeight.getAsJsonObject().keySet()));
            }
        }
    }

    /** {@code worldgen/structure_set/*.json}。 */
    private static void validateStructureSet(String where, JsonObject root, Index index, List<Issue> issues) {
        requireKeys(root, where, issues, "structures", "placement");

        JsonElement structures = root.get("structures");
        if (isPresent(structures)) {
            if (!structures.isJsonArray()) {
                issues.add(new Issue(where, "'structures' must be an array, got " + kindOf(structures)));
            } else {
                int i = 0;
                for (JsonElement element : structures.getAsJsonArray()) {
                    String whereEntry = where + " .structures[" + i + "]";
                    i++;
                    if (!element.isJsonObject()) {
                        issues.add(new Issue(whereEntry, "must be an object, got " + kindOf(element)));
                        continue;
                    }
                    JsonObject entry = element.getAsJsonObject();
                    requireKeys(entry, whereEntry, issues, "structure");
                    String structure = stringField(entry, "structure", whereEntry, issues);
                    if (structure != null) {
                        checkRef(where, "structures[].structure", structure, "worldgen/structure", index, issues, false);
                    }
                }
            }
        }

        JsonElement placement = root.get("placement");
        if (!isPresent(placement)) {
            return;
        }
        if (!placement.isJsonObject()) {
            issues.add(new Issue(where, "'placement' must be an object, got " + kindOf(placement)));
            return;
        }
        JsonObject p = placement.getAsJsonObject();
        String wherePlacement = where + " .placement";
        String placementType = stringField(p, "type", wherePlacement, issues);
        if (RANDOM_SPREAD.equals(placementType)) {
            requireKeys(p, wherePlacement, issues, "spacing", "separation", "salt");
            Integer spacing = intField(p, "spacing", wherePlacement, issues);
            Integer separation = intField(p, "separation", wherePlacement, issues);
            if (spacing != null && separation != null && separation >= spacing) {
                issues.add(new Issue(wherePlacement,
                        "'separation' must be strictly less than 'spacing', got " + separation + " >= " + spacing));
            }
        } else if (CONCENTRIC_RINGS.equals(placementType)) {
            requireKeys(p, wherePlacement, issues, "distance", "spread", "count", "preferred_biomes");
        }

        JsonElement exclusionZone = p.get("exclusion_zone");
        if (!isPresent(exclusionZone)) {
            return;
        }
        if (!exclusionZone.isJsonObject()) {
            issues.add(new Issue(wherePlacement, "'exclusion_zone' must be an object, got " + kindOf(exclusionZone)));
            return;
        }
        JsonObject zone = exclusionZone.getAsJsonObject();
        String whereZone = wherePlacement + " .exclusion_zone";
        requireKeys(zone, whereZone, issues, "other_set", "chunk_count");
        rejectUnknown(zone, whereZone, issues, Set.of("other_set", "chunk_count"));
        String otherSet = stringField(zone, "other_set", whereZone, issues);
        if (otherSet != null) {
            checkRef(where, "placement.exclusion_zone.other_set", otherSet, "worldgen/structure_set", index, issues, false);
        }
    }

    /** {@code worldgen/template_pool/**.json}。 */
    private static void validateTemplatePool(String where, JsonObject root, Index index, List<Issue> issues) {
        requireKeys(root, where, issues, "elements", "fallback");

        String fallback = stringField(root, "fallback", where, issues);
        if (fallback != null) {
            checkRef(where, "fallback", fallback, "worldgen/template_pool", index, issues, false);
        }

        JsonElement elements = root.get("elements");
        if (!isPresent(elements)) {
            return;
        }
        if (!elements.isJsonArray()) {
            issues.add(new Issue(where, "'elements' must be an array, got " + kindOf(elements)));
            return;
        }

        int i = 0;
        for (JsonElement element : elements.getAsJsonArray()) {
            String whereEntry = where + " .elements[" + i + "]";
            i++;
            if (!element.isJsonObject()) {
                issues.add(new Issue(whereEntry, "must be an object, got " + kindOf(element)));
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            requireKeys(entry, whereEntry, issues, "weight", "element");

            JsonElement weight = entry.get("weight");
            if (isPresent(weight)) {
                Integer value = asInt(weight);
                if (value == null) {
                    issues.add(new Issue(whereEntry, "'weight' must be an integer, got " + kindOf(weight)));
                } else if (value < 1) {
                    issues.add(new Issue(whereEntry, "'weight' must be >= 1, got " + value));
                }
            }

            JsonElement inner = entry.get("element");
            if (!isPresent(inner)) {
                continue;
            }
            if (!inner.isJsonObject()) {
                issues.add(new Issue(whereEntry, "'element' must be an object, got " + kindOf(inner)));
                continue;
            }
            validatePoolElement(where, inner.getAsJsonObject(), whereEntry + ".element", index, issues);
        }
    }

    private static void validatePoolElement(String where, JsonObject element, String whereElement,
                                            Index index, List<Issue> issues) {
        requireKeys(element, whereElement, issues, "element_type");
        String elementType = stringField(element, "element_type", whereElement, issues);
        if (elementType == null) {
            return;
        }
        if (!RESOURCE_LOCATION.matcher(elementType).matches()) {
            issues.add(new Issue(whereElement, "'element_type' is not a valid resource location: '" + elementType
                    + "' (e.g. minecraft:single_pool_element)"));
            return;
        }
        if (elementType.startsWith("minecraft:") && !POOL_ELEMENT_TYPES.contains(elementType)) {
            issues.add(new Issue(whereElement, "'element_type' '" + elementType
                    + "' is not a known vanilla pool element type"));
            return;
        }
        if (!POOL_ELEMENT_TYPES.contains(elementType)) {
            // 包自定义的 pool element type：字段集只有包自己知道，这里不做判断
            return;
        }

        switch (elementType) {
            case "minecraft:single_pool_element", "minecraft:legacy_single_pool_element" -> {
                requireKeys(element, whereElement, issues, "location");
                String location = stringField(element, "location", whereElement, issues);
                if (location != null) {
                    checkRef(where, "elements[].element.location", location, "structure", index, issues, true);
                }
            }
            case "minecraft:feature_pool_element" -> requireKeys(element, whereElement, issues, "feature");
            case "minecraft:list_pool_element" -> requireKeys(element, whereElement, issues, "elements");
            default -> {
                // minecraft:empty_pool_element —— 除 element_type 外无字段
            }
        }

        // projection 在 vanilla 元素里是可选的，因此只在出现时校验取值
        JsonElement projection = element.get("projection");
        if (isPresent(projection)) {
            if (!projection.isJsonPrimitive() || !projection.getAsJsonPrimitive().isString()) {
                issues.add(new Issue(whereElement, "'projection' must be a string, got " + kindOf(projection)));
            } else if (!PROJECTIONS.contains(projection.getAsString())) {
                issues.add(new Issue(whereElement, "'projection' '" + projection.getAsString()
                        + "' is invalid (expected rigid / terrain_matching)"));
            }
        }

        // processors 可选，但一旦出现必须是对象（{processors: [...]}）或处理器列表 id，不能是裸数组
        JsonElement processors = element.get("processors");
        if (isPresent(processors) && !processors.isJsonObject()
                && !(processors.isJsonPrimitive() && processors.getAsJsonPrimitive().isString())) {
            issues.add(new Issue(whereElement, "'processors' must be a processor-list object or id, got " + kindOf(processors)
                    + " (a bare array is invalid)"));
        }
    }

    // ------------------------------------------------------------------
    // NBT
    // ------------------------------------------------------------------

    /**
     * {@code data/**.nbt} 必须是合法的 gzip 容器 + 结构完好的 NBT 标签树。
     *
     * <p>只检查 {@code data/<ns>/structure/**} —— MC 的结构模板系统（{@code StructureTemplateManager}）
     * 只从这条路径读 {@code .nbt}，且写死要求 gzip。其他位置的 {@code .nbt} 由各自的属主解释
     * （可能是未压缩或 zlib），拿 MC 的规矩去判它们只会误伤。
     *
     * <p><b>为什么不用 MC 自己的 {@code NbtIo.readCompressed}：</b>那条路径在解析失败时会去构造
     * {@code CrashReport}，而 {@code CrashReport} 需要已初始化的游戏版本
     * （{@code SharedConstants.getCurrentVersion()}）。在游戏外（开发工具/离线校验）它会抛出
     * {@code IllegalStateException: Game version not set}，把真正的解析错误<b>完全掩盖</b>掉 ——
     * 实测就是这样：一个字节序完全正确、结构合法的结构文件也报「Game version not set」，
     * 于是任何带 {@code .nbt} 的健康包都会被误判。自带解析器则与环境无关，且能给出精确原因。
     *
     * <p>这里只判定<b>结构性合法</b>（容器、标签类型、长度、嵌套深度）。不校验 DataVersion 可迁移性，
     * 也<b>不可能</b>发现「值被字节序交换」这类损坏 —— 那种文件在结构上完全合法。
     */
    private static void validateNbt(JarFile jf, JarEntry entry, List<Issue> issues) {
        try (InputStream raw = jf.getInputStream(entry);
             InputStream gzipped = new GZIPInputStream(new BufferedInputStream(raw));
             DataInputStream in = new DataInputStream(gzipped)) {
            readRootTag(in, new Budget());
        } catch (ZipException ex) {
            issues.add(new Issue(entry.getName(), "not a gzip container (" + ex.getMessage()
                    + "); structure files must be gzipped NBT"));
        } catch (EOFException ex) {
            issues.add(new Issue(entry.getName(), "truncated NBT payload: " + ex.getMessage()));
        } catch (IOException ex) {
            issues.add(new Issue(entry.getName(), "not a valid NBT tag tree: " + ex.getMessage()));
        }
    }

    /** 读取根标签：类型字节 + 名称 + 载荷。 */
    private static void readRootTag(DataInput in, Budget budget) throws IOException {
        int type = in.readByte();
        if (type < TAG_MIN || type > TAG_MAX) {
            throw new IOException("root tag type " + type + " is not a valid NBT tag type");
        }
        readName(in, budget);
        readPayload(in, type, 1, budget);
    }

    /** 名称：无符号 short 长度 + UTF-8 字节（内容不参与判定）。 */
    private static void readName(DataInput in, Budget budget) throws IOException {
        skipFully(in, in.readUnsignedShort(), budget);
    }

    private static void readPayload(DataInput in, int type, int depth, Budget budget) throws IOException {
        if (depth > MAX_NBT_DEPTH) {
            throw new IOException("NBT nesting deeper than " + MAX_NBT_DEPTH + " levels");
        }
        budget.spend(1);
        switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> skipFully(in, in.readInt(), budget);
            case 8 -> skipFully(in, in.readUnsignedShort(), budget);
            case 9 -> {
                int elementType = in.readByte();
                int length = in.readInt();
                if (length < 0) {
                    throw new IOException("negative list length " + length);
                }
                if (elementType == 0) {
                    if (length != 0) {
                        throw new IOException("TAG_End list type with non-zero length " + length);
                    }
                    return;
                }
                if (elementType < TAG_MIN || elementType > TAG_MAX) {
                    throw new IOException("list element type " + elementType + " is not a valid NBT tag type");
                }
                for (int i = 0; i < length; i++) {
                    readPayload(in, elementType, depth + 1, budget);
                }
            }
            case 10 -> {
                while (true) {
                    int childType = in.readByte();
                    if (childType == 0) {
                        return;
                    }
                    if (childType < TAG_MIN || childType > TAG_MAX) {
                        throw new IOException("compound member type " + childType + " is not a valid NBT tag type");
                    }
                    readName(in, budget);
                    readPayload(in, childType, depth + 1, budget);
                }
            }
            case 11 -> skipFully(in, 4L * in.readInt(), budget);
            case 12 -> skipFully(in, 8L * in.readInt(), budget);
            default -> throw new IOException("tag type " + type + " is not a valid NBT tag type");
        }
    }

    /** 有界跳过：一次性 read 可能短读，同时防止畸形长度声明把内存吃光。 */
    private static void skipFully(DataInput in, long bytes, Budget budget) throws IOException {
        if (bytes < 0) {
            throw new IOException("negative length " + bytes);
        }
        budget.spend(bytes);
        if (bytes == 0) {
            return;
        }
        byte[] scratch = new byte[(int) Math.min(bytes, 8192)];
        long remaining = bytes;
        while (remaining > 0) {
            int chunk = (int) Math.min(remaining, scratch.length);
            in.readFully(scratch, 0, chunk);
            remaining -= chunk;
        }
    }

    /** 读取配额：畸形包可以声明极大的数组长度，这里给总字节数封顶。 */
    private static final class Budget {
        private long remaining = NBT_QUOTA;

        void spend(long bytes) throws IOException {
            remaining -= bytes;
            if (remaining < 0) {
                throw new IOException("NBT payload exceeds the " + NBT_QUOTA + " byte budget");
            }
        }
    }

    // ------------------------------------------------------------------
    // 引用检查
    // ------------------------------------------------------------------

    /**
     * 检查一个引用是否可解析。
     *
     * <p>规则：只有引用落在<b>内容包自己的命名空间</b>里时才判定「必须存在」——
     * 因为那意味着某个包承诺提供它。{@code minecraft:} 以及任何内容包都没用到的命名空间
     * 一律假定「vanilla 或别的 mod 提供」，不做判断（否则会误伤大量合法引用）。
     *
     * @param nbtTarget true 表示目标是 {@code structure/*.nbt}，false 表示是 JSON 条目
     */
    private static void checkRef(String where, String label, String id, String kind,
                                 Index index, List<Issue> issues, boolean nbtTarget) {
        if (id == null || id.isBlank()) {
            return;
        }
        if (id.startsWith("#")) {
            String tag = id.substring(1);
            // 注意用去掉 '#' 的 tag 去判断命名空间：否则 '#a:x' 会被解析成命名空间 '#a'，
            // 于是一律落到「外部引用」分支 —— 所有 tag 引用都会被静默跳过。
            if (!isExternal(tag, index) && !index.hasTag(kind, tag)) {
                issues.add(new Issue(where, label + " '" + id
                        + "' is not provided by any loaded content pack"));
            }
            return;
        }
        if (!RESOURCE_LOCATION.matcher(id).matches()) {
            issues.add(new Issue(where, label + " '" + id + "' is not a valid resource location"));
            return;
        }
        if (isExternal(id, index)) {
            return;
        }
        boolean found = nbtTarget ? index.hasNbt(kind, id) : index.hasJson(kind, id);
        if (!found) {
            String target = nbtTarget ? "data/<ns>/" + kind + "/<name>.nbt" : "data/<ns>/" + kind + "/<name>.json";
            issues.add(new Issue(where, label + " '" + id + "' does not exist in any loaded content pack"
                    + " (expected " + target + "); an unbound value is fatal at registry load"));
        }
    }

    private static void checkBiomeRef(String where, String value, Index index, List<Issue> issues) {
        if (value == null || value.isBlank()) {
            return;
        }
        checkRef(where, "biomes", value, "worldgen/biome", index, issues, false);
    }

    /** 该引用是否落在内容包之外（{@code minecraft:}，或没有任何内容包使用的命名空间）。 */
    private static boolean isExternal(String id, Index index) {
        String ns = namespaceOf(id);
        return ns == null || "minecraft".equals(ns) || !index.namespaces().contains(ns);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static void requireKeys(JsonObject object, String where, List<Issue> issues, String... keys) {
        for (String key : keys) {
            if (!isPresent(object.get(key))) {
                issues.add(new Issue(where, "missing required key '" + key + "'"));
            }
        }
    }

    private static void rejectUnknown(JsonObject object, String where, List<Issue> issues, Set<String> allowed) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) {
                issues.add(new Issue(where, "unknown key '" + key + "' (the codec rejects unknown input)"));
            }
        }
    }

    private static void requireEnum(JsonObject object, String key, Set<String> allowed, String where, List<Issue> issues) {
        JsonElement element = object.get(key);
        if (!isPresent(element)) {
            return; // 缺字段由 requireKeys 负责（若它是必填的）
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            issues.add(new Issue(where, "'" + key + "' must be a string, got " + kindOf(element)));
            return;
        }
        String value = element.getAsString();
        if (!allowed.contains(value)) {
            issues.add(new Issue(where, "'" + key + "' '" + value + "' is invalid; expected one of " + allowed));
        }
    }

    private static void requireBoolean(JsonObject object, String key, String where, List<Issue> issues) {
        JsonElement element = object.get(key);
        if (isPresent(element) && (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean())) {
            issues.add(new Issue(where, "'" + key + "' must be a boolean, got " + kindOf(element)));
        }
    }

    /** 取字符串字段；类型不对时报错并返回 null。字段缺失不报（由 requireKeys 负责）。 */
    private static String stringField(JsonObject object, String key, String where, List<Issue> issues) {
        JsonElement element = object.get(key);
        if (!isPresent(element)) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            issues.add(new Issue(where, "'" + key + "' must be a string, got " + kindOf(element)));
            return null;
        }
        return element.getAsString();
    }

    /** 取整数字段；非数字时报错并返回 null。 */
    private static Integer intField(JsonObject object, String key, String where, List<Issue> issues) {
        JsonElement element = object.get(key);
        if (!isPresent(element)) {
            return null;
        }
        Integer value = asInt(element);
        if (value == null) {
            issues.add(new Issue(where, "'" + key + "' must be an integer, got " + kindOf(element)));
        }
        return value;
    }

    private static Integer asInt(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String asString(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString()
                : null;
    }

    private static boolean isPresent(JsonElement element) {
        return element != null && !element.isJsonNull();
    }

    private static String kindOf(JsonElement element) {
        if (element == null) {
            return "nothing";
        }
        if (element.isJsonObject()) {
            return "an object";
        }
        if (element.isJsonArray()) {
            return "an array";
        }
        if (element.isJsonNull()) {
            return "null";
        }
        if (element.getAsJsonPrimitive().isString()) {
            return "a string";
        }
        return element.getAsJsonPrimitive().isBoolean() ? "a boolean" : "a number";
    }

    /** {@code "ns:path"} → {@code "ns"}；没有冒号或命名空间为空则返回 null。 */
    private static String namespaceOf(String id) {
        if (id == null) {
            return null;
        }
        int colon = id.indexOf(':');
        return colon > 0 ? id.substring(0, colon) : null;
    }

    /** {@code "ns:path"} → {@code "path"}。 */
    private static String pathOf(String id) {
        if (id == null) {
            return null;
        }
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) {
            return null;
        }
        return id.substring(colon + 1);
    }

    private static String strip(String value, int from, int fromEnd) {
        return value.substring(from, value.length() - fromEnd);
    }
}
