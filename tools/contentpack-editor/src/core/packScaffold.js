// packScaffold.js - 内容包骨架约定（单一真源，纯函数）
//
// 本模块只做「字符串/规则」，不碰 fs、不碰 DOM，因此：
//   - main.js 用它生成包骨架与预检报告
//   - test/packScaffold.test.js 直接 require 它做单测
//
// 对应文档：
//   design/GRADLE_MULTIPROJECT.md §2.3   —— 每个包的 build.gradle 样板
//   contentpacks-source/contentpack.gradle —— 共享构建约定（真正的构建逻辑）
//   design/CONTENTPACK_FORMAT.md §2      —— MANIFEST 属性表
//
// 所有校验规则都标注了它在 core 里的权威来源：规则不是编辑器的「意见」，
// 而是加载器/解析器的真实行为。

// 注意：经典 <script> 的顶层 const 共享【同一个全局词法环境】，两个脚本重名即 SyntaxError
// （整个脚本失效）。所以本文件整体包在 IIFE 里，对外只暴露 self.PackScaffold。
(function () {

// ============================================================
// MANIFEST 属性
// ============================================================

/** 规范属性顺序（只列非空值；ContentPackManifest 识别这些键） */
const MODULE_ATTR_ORDER = [
    'Module-Name',
    'Module-Version',
    'Module-Requires',
    'Module-Type',
    'Module-Entry',
    'Module-Dependencies',
    'Module-Dependencies-Optional',
    'Module-Lib-Requires',
    'Module-Mod-Requires',
    'Module-Mod-Optional',
];

const MODULE_TYPE_VALUE = 'contentpack';

/** 未列入 MODULE_ATTR_ORDER 的自定义键排在规范键之后（保持用户顺序） */
function canonicalizeManifest(attrs) {
    const out = {};
    out['Module-Name'] = (attrs['Module-Name'] || '').trim();
    const known = new Set(MODULE_ATTR_ORDER);
    for (const key of MODULE_ATTR_ORDER) {
        if (key === 'Module-Name') continue;
        const v = attrs[key];
        if (v !== undefined && v !== null && String(v).trim() !== '') out[key] = String(v).trim();
    }
    if (!out['Module-Type']) out['Module-Type'] = MODULE_TYPE_VALUE;
    for (const [k, v] of Object.entries(attrs)) {
        if (known.has(k)) continue;
        if (v !== undefined && v !== null && String(v).trim() !== '') out[k] = String(v).trim();
    }
    return out;
}

// ============================================================
// 标识符规则（与 Minecraft ResourceLocation / Java 一致）
// ============================================================

/** ResourceLocation 的 namespace：小写字母/数字/._- */
const RL_NAMESPACE_RE = /^[a-z0-9_.\-]+$/;
/** ResourceLocation 的 path：额外允许 '/' */
const RL_PATH_RE = /^[a-z0-9/._\-]+$/;
/** 实体 id 既做文件名又做 ResourceLocation path ⇒ 不允许 '/' */
const ENTITY_ID_RE = /^[a-z0-9_.\-]+$/;
/** 带可选 namespace 的完整 ResourceLocation */
const RL_FULL_RE = /^([a-z0-9_.\-]+:)?[a-z0-9/._\-]+$/;
/** Module-Name 用作 jar 文件名、依赖图键、「Module-Dependencies」引用名 */
const MODULE_NAME_RE = /^[A-Za-z0-9._\-]+$/;
const JAVA_IDENT_RE = /^[A-Za-z_$][A-Za-z0-9_$]*$/;
/** 包依赖之间禁止版本区间（见 validateManifest） */
const VERSION_RANGE_RE = /(>=|<=|>|<|\*|\[|\()/;

// ============================================================
// build.gradle（每个包 5 行样板 + 可选额外编译依赖）
// ============================================================

/**
 * 生成包的 build.gradle。
 * 注意：共享约定在 ../contentpack.gradle，所以包必须放在 contentpacks-source/<包名>/ 下
 * （编辑器的导出目标就是这个位置）。
 *
 * @param {string} moduleName Module-Name
 * @param {{extraDeps?: string[]}} [opts] extraDeps = 整条依赖语句（原样写入 dependencies 块）
 */
function renderBuildGradle(moduleName, opts = {}) {
    const extraDeps = (opts.extraDeps || []).map((s) => String(s).trim()).filter(Boolean);
    const L = [];
    L.push('// ============================================================');
    L.push(`// ContentPack: ${moduleName || '<Module-Name>'}`);
    L.push('// 引用方向：本包 ──▶ core（根项目），永不反向。');
    L.push('// 构建约定全部在 ../contentpack.gradle（共享单一真源）：');
    L.push('//   Java 21 toolchain / 仓库 / sourceSets / contentpackJar 任务 / MANIFEST 派生');
    L.push('// 规范：design/GRADLE_MULTIPROJECT.md §2.3、design/CONTENTPACK_FORMAT.md §9.3');
    L.push('// ============================================================');
    L.push('plugins {');
    L.push("    id 'java-library'");
    L.push('    // 必须自己应用 userdev：NeoGradle 的 MC/NeoForge 依赖是内部 dummy 记号');
    L.push('    // （ng_dummy_ng.net.neoforged:neoforge），只在应用了 userdev 的项目里可解析，');
    L.push('    // 因此「消费根项目的 compileClasspath」这条路走不通。');
    L.push("    id 'net.neoforged.gradle.userdev'");
    L.push('}');
    L.push("apply from: '../contentpack.gradle'");
    L.push('');
    if (extraDeps.length) {
        L.push('dependencies {');
        L.push('    // 本包特有的额外编译依赖（compileOnly：运行期由父环境或 _libs 提供）');
        for (const d of extraDeps) L.push(`    ${d}`);
        L.push('}');
    } else {
        L.push('// 本包无额外编译依赖（只用 core + MC + NeoForge）');
    }
    return L.join('\n') + '\n';
}

// ============================================================
// 入口类骨架
// ============================================================

/**
 * 解析 Module-Entry 为 {pkg, cls, path}；非法返回 null
 * @param {string} moduleEntry 全限定类名
 */
function entryLocation(moduleEntry) {
    const full = String(moduleEntry || '').trim();
    if (!full) return null;
    const parts = full.split('.');
    if (parts.length < 2) return null;
    const cls = parts[parts.length - 1];
    const pkgParts = parts.slice(0, -1);
    if (!JAVA_IDENT_RE.test(cls)) return null;
    // 包名段允许大写（Java 合法），但每段必须是合法标识符
    if (!pkgParts.every((p) => /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(p))) return null;
    const pkg = pkgParts.join('.');
    return {
        pkg,
        cls,
        path: 'java/' + pkgParts.join('/') + '/' + cls + '.java',
    };
}

/**
 * 生成入口类骨架（实现 core 的 ContentPack 接口）。
 *
 * 关键：加载器【不会】自动注册 entities/*.json —— 只有包的 register() 调用
 * ContentPackContext.registerDataDrivenMob 才会注册（参考已跑通的
 * contentpacks-source/test-mob-pack/.../TestMobPack.java）。
 * 所以有实体时骨架必须生成这段注册代码，否则包的 JSON 是「死数据」。
 *
 * @param {{moduleEntry:string, packId?:string, displayName?:string, version?:string, entityIds?:string[]}} cfg
 * @returns {string|null}
 */
/** Java 字符串字面量转义（REVIEW-M5：displayName/Module-Version 是用户输入，
 *  含 "、\、换行时直接内插会生成编译失败的入口类 —— 打包时才暴露） */
function javaStr(s) {
    return String(s == null ? '' : s)
        .replace(/\\/g, '\\\\')
        .replace(/"/g, '\\"')
        .replace(/\r/g, '\\r')
        .replace(/\n/g, '\\n')
        .replace(/\t/g, '\\t');
}

function renderEntryClass(cfg) {
    const loc = entryLocation(cfg.moduleEntry);
    if (!loc) return null;
    const packId = cfg.packId || 'my_pack';
    const displayName = cfg.displayName || packId;
    const version = cfg.version || '1.0.0';
    const entityIds = (cfg.entityIds || []).filter((id) => ENTITY_ID_RE.test(id));
    const hasMobs = entityIds.length > 0;
    const L = [];

    L.push(`package ${loc.pkg};`);
    L.push('');
    L.push('import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPack;');
    L.push('import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;');
    if (hasMobs) {
        L.push('import com.catoxide.catoxidesbattlerebuild.core.mob.MobDefinition;');
        L.push('import com.catoxide.catoxidesbattlerebuild.core.mob.MobDefinitionParser;');
    }
    L.push('import com.catoxide.catoxidesbattlerebuild.util.LogManager;');
    if (hasMobs) {
        L.push('');
        L.push('import java.io.InputStream;');
        L.push('import java.nio.charset.StandardCharsets;');
    }
    L.push('');
    L.push('/**');
    L.push(` * ${loc.cls} —— 内容包入口类（骨架由 ContentPack Editor 生成）`);
    L.push(' *');
    L.push(' * <p>生命周期（两阶段，勿颠倒）：');
    L.push(' * <ol>');
    L.push(' *   <li>所有已加载包的 {@code register()} 按依赖拓扑序全部执行完；</li>');
    L.push(' *   <li>注册表锁（ContentPackRegistry / CapabilityRegistry / BehaviorRouter）；</li>');
    L.push(' *   <li>最后才轮到所有包的 {@code init()}。</li>');
    L.push(' * </ol>');
    L.push(' * 因此：注册内容放 {@code register()}，消费别的包提供的能力放 {@code init()}。');
    L.push(' *');
    L.push(' * <p>注意：{@code getId()} 是【包内标识】（供日志/能力查询），与 MANIFEST 的');
    L.push(` * {@code Module-Name}（依赖图键）是两个不同的东西。`);
    L.push(' *');
    L.push(' * <p>数据驱动实体（entities/*.json）不会自动注册，必须在这里显式注册；');
    L.push(' * 通用渲染器由主 mod 经 {@code getDataDrivenEntities()} 统一挂载，本包无需实现');
    L.push(' * {@code registerClientRenderers}。');
    L.push(' */');
    L.push(`public class ${loc.cls} implements ContentPack {`);
    L.push('');
    L.push('    @Override');
    L.push(`    public String getId() {`);
    L.push(`        return "${javaStr(packId)}";`);
    L.push('    }');
    L.push('');
    L.push('    @Override');
    L.push(`    public String getDisplayName() {`);
    L.push(`        return "${javaStr(displayName)}";`);
    L.push('    }');
    L.push('');
    L.push('    @Override');
    L.push(`    public String getVersion() {`);
    L.push(`        return "${javaStr(version)}";`);
    L.push('    }');
    L.push('');
    L.push('    /** 注册阶段：只做注册（此时其它包的 register() 可能还没跑完）。 */');
    L.push('    @Override');
    L.push('    public void register(ContentPackContext context) {');
    if (hasMobs) {
        L.push('        // 注册本包 entities/*.json 定义的数据驱动实体');
        for (const id of entityIds) L.push(`        registerDataDriven(context, "${id}");`);
    } else {
        L.push('        // TODO 在这里注册内容，例如：');
        L.push('        // context.registerDataDrivenMob(MobDefinitionParser.parse(json));');
        L.push('        // context.registerStructure(blueprint);');
        L.push('        // context.provide(MyCapability.INSTANCE, impl);   // 供其它包消费');
    }
    L.push('    }');
    L.push('');
    L.push('    /** 初始化阶段：所有包的 register() 完成、注册表已锁之后才调用。 */');
    L.push('    @Override');
    L.push('    public void init() {');
    L.push('        // TODO 事件监听 / 跨包能力消费放这里，例如：');
    L.push('        // context.capability(OtherCapability.INSTANCE).ifPresent(impl -> ...);');
    L.push(`        LogManager.serverInfo(getId(), "initialized");`);
    L.push('    }');
    if (hasMobs) {
        L.push('');
        L.push('    /** 从本包 jar 读取 entities/<id>.json 并注册为数据驱动实体。 */');
        L.push('    private void registerDataDriven(ContentPackContext context, String entityId) {');
        L.push('        String path = "/entities/" + entityId + ".json";');
        L.push('        try (InputStream in = getClass().getResourceAsStream(path)) {');
        L.push('            if (in == null) {');
        L.push('                LogManager.serverError(getId(), path + " not found in jar");');
        L.push('                return;');
        L.push('            }');
        L.push('            MobDefinition definition = MobDefinitionParser.parse(');
        L.push('                    new String(in.readAllBytes(), StandardCharsets.UTF_8));');
        L.push('            var holder = context.registerDataDrivenMob(definition);');
        L.push('            // 用真实的注册键（namespace 缺省时挂主 mod；MobDefinition.entityKey() 在');
        L.push('            // namespace 为空时会打印 "null:<id>"，不适合当日志）');
        L.push('            LogManager.serverInfo(getId(), "registered data-driven mob: " + holder.getKey().location());');
        L.push('        } catch (Exception e) {');
        L.push('            LogManager.serverError(getId(), "failed to register " + path + ": " + e, e);');
        L.push('        }');
        L.push('    }');
    }
    L.push('}');
    return L.join('\n') + '\n';
}

// ============================================================
// 预检
// ============================================================

function finding(level, code, msg, src) {
    return { level, code, msg, src };
}

/**
 * 校验 MANIFEST 属性表。
 * @param {Object<string,string>} attrs
 * @returns {Array<{level:string, code:string, msg:string, src:string}>}
 */
function validateManifest(attrs) {
    const f = [];
    const name = (attrs['Module-Name'] || '').trim();
    const type = (attrs['Module-Type'] || '').trim();

    if (!name) {
        f.push(finding('error', 'E-NAME-EMPTY',
            'Module-Name 为空：它是唯一强制属性，缺失会导致整个 jar 被静默跳过（游戏里看不到任何报错）。',
            'ContentPackManifest.parseAttributes'));
    } else if (!MODULE_NAME_RE.test(name)) {
        f.push(finding('error', 'E-NAME-CHARSET',
            `Module-Name "${name}" 含非法字符：只能用字母/数字/._-（它同时是 jar 文件名与依赖图键）。`,
            'contentpack.gradle: archiveFileName'));
    } else if (/[A-Z]/.test(name)) {
        f.push(finding('warn', 'W-NAME-UPPER',
            `Module-Name "${name}" 含大写：合法，但 Module-Dependencies 引用名大小写敏感，建议全小写。`,
            'CONTENTPACK_FORMAT §2'));
    }

    if (type && type !== MODULE_TYPE_VALUE) {
        f.push(finding('error', 'E-TYPE-VALUE',
            `Module-Type 必须是 "${MODULE_TYPE_VALUE}"（当前 "${type}"），否则 jar 不会被识别为内容包。`,
            'ContentPackManifest.parseAttributes'));
    }

    if (!(attrs['Module-Version'] || '').trim()) {
        f.push(finding('warn', 'W-VERSION-EMPTY',
            'Module-Version 为空：加载器会默认成 0.0.0。',
            'ContentPackManifest.parseAttributes'));
    }

    if (!(attrs['Module-Requires'] || '').trim()) {
        f.push(finding('warn', 'W-REQUIRES-EMPTY',
            'Module-Requires 为空：本包不校验主 mod 版本，契约变更后会以 NoSuchMethodError 之类的形式在运行期炸。',
            'ContentPackManifest.verifyCompatibility'));
    }

    const entry = (attrs['Module-Entry'] || '').trim();
    if (entry) {
        if (!entryLocation(entry)) {
            f.push(finding('error', 'E-ENTRY-INVALID',
                `Module-Entry "${entry}" 不是合法的全限定类名，生成骨架/加载都会失败。`,
                'ContentPack Manifests 规范'));
        } else if (entry.startsWith('com.catoxide.catoxidesbattlerebuild.')) {
            f.push(finding('error', 'E-ENTRY-PARENTFIRST',
                `Module-Entry 不能放在 com.catoxide.catoxidesbattlerebuild.* 下：该前缀在 ChildFirstClassLoader 的 ` +
                `PARENT_FIRST_PREFIXES 里，永远交给父加载器，而父加载器看不到包内的类 ⇒ NoClassDefFoundError。`,
                'ChildFirstClassLoader.java:55'));
        }
    }

    const deps = splitList(attrs['Module-Dependencies']);
    const depsOpt = splitList(attrs['Module-Dependencies-Optional']);
    for (const [key, list] of [['Module-Dependencies', deps], ['Module-Dependencies-Optional', depsOpt]]) {
        for (const d of list) {
            if (VERSION_RANGE_RE.test(d)) {
                f.push(finding('error', 'E-DEP-VERSION-RANGE',
                    `${key} 的 "${d}" 带版本区间：包与包之间禁止版本约束（会被忽略并告警），` +
                    `包间契约随主 mod 的契约版本走。`,
                    'ContentPackManifest 类文档（包间禁止版本约束）'));
            } else if (!MODULE_NAME_RE.test(d)) {
                f.push(finding('error', 'E-DEP-CHARSET',
                    `${key} 的 "${d}" 含非法字符。`, '内容包依赖图键'));
            }
            if (name && d === name) {
                f.push(finding('error', 'E-DEP-SELF',
                    `${key} 引用了自己（${d}）⇒ 依赖图自环。`, 'ContentPackLoader 拓扑排序'));
            }
        }
    }
    for (const d of deps) {
        if (depsOpt.includes(d)) {
            f.push(finding('warn', 'W-DEP-BOTH',
                `"${d}" 同时出现在硬依赖与软依赖里：语义矛盾，建议只留硬依赖。`, 'CONTENTPACK_FORMAT §2'));
        }
    }

    const libs = splitList(attrs['Module-Lib-Requires']);
    for (const lib of libs) {
        if (!MODULE_NAME_RE.test(lib)) {
            f.push(finding('error', 'E-LIB-CHARSET', `Module-Lib-Requires 的 "${lib}" 含非法字符。`, 'CONTENTPACK_FORMAT §2'));
        }
    }
    if (libs.length) {
        f.push(finding('warn', 'W-LIB-NEED-JARS',
            `Module-Lib-Requires 声明了 ${libs.length} 个库：必须存在 contentpacks-source/_libs/<库名>/*.jar，` +
            `否则 Gradle 构建会告警且编译失败。`,
            'contentpack.gradle（_libs 编译闭包）'));
    }

    for (const key of ['Module-Mod-Requires', 'Module-Mod-Optional']) {
        for (const m of splitList(attrs[key])) {
            if (!/^[a-z0-9_.\-]+(\s*(>=|<=|>|<|=)\s*[A-Za-z0-9.\-+]+)?$/.test(m)) {
                f.push(finding('warn', 'W-MODREQ-FORMAT',
                    `${key} 的 "${m}" 不是 "<modid>" 或 "<modid>>=x.y.z" 形式。`, 'ContentPackManifest.ModRequirement'));
            }
        }
    }

    return f;
}

/** 逗号分隔列表 → 数组（去空、去重保持顺序） */
function splitList(value) {
    if (!value) return [];
    return String(value)
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean);
}

/**
 * 校验实体定义数组（镜像 MobDefinitionParser 的真实解析行为）。
 * @param {Array<Object>} entities 已解析的实体 JSON 对象
 */
function validateEntities(entities) {
    const f = [];
    const seen = new Map();
    (entities || []).forEach((e, i) => {
        const at = `entities[${i}]`;
        const id = e && e.id !== undefined && e.id !== null ? String(e.id) : '';
        const label = id ? `${at} (${id})` : at;

        if (!id) {
            f.push(finding('error', 'E-MOB-ID-EMPTY',
                `${label} 缺少 id：解析器直接 root.get("id").getAsString() ⇒ NPE，整个包加载失败。`,
                'MobDefinitionParser.java:41'));
            return;
        }
        if (!ENTITY_ID_RE.test(id)) {
            f.push(finding('error', 'E-MOB-ID-CHARSET',
                `${label} 的 id 含非法字符：id 既做文件名，又做 <namespace>:<id> 的 ResourceLocation path，` +
                `大写/空格会抛 ResourceLocationException。`,
                'MobDefinitionParser.java:71-73'));
        }
        if (seen.has(id)) {
            f.push(finding('error', 'E-MOB-ID-DUP',
                `实体 id "${id}" 重复（第 ${seen.get(id) + 1} 与第 ${i + 1} 个）：文件名 entities/${id}.json 相同，` +
                `会互相覆盖，最终只剩一个。`,
                'ContentPackLoader 实体扫描'));
        } else {
            seen.set(id, i);
        }

        if (e.namespace !== undefined && e.namespace !== null && String(e.namespace) !== '' && !RL_NAMESPACE_RE.test(String(e.namespace))) {
            f.push(finding('error', 'E-MOB-NS', `${label} 的 namespace 非法（只能小写字母/数字/._-）。`,
                'ContentPackContext.registerDataDrivenMob'));
        }

        // 数值字段：空输入 → parseFloat → NaN → JSON.stringify(NaN) === null → getAsFloat 抛异常
        checkNumbers(f, label, e.size, ['width', 'height'], 'size');
        if (e.attributes && typeof e.attributes === 'object') {
            checkNumbers(f, label, e.attributes, Object.keys(e.attributes), 'attributes');
        }

        if (e.model && e.model.id !== undefined && String(e.model.id) !== '' && !RL_FULL_RE.test(String(e.model.id))) {
            f.push(finding('error', 'E-MOB-MODEL-ID',
                `${label} 的 model.id "${e.model.id}" 不是合法 ResourceLocation（namespace:path，小写）。`,
                'MobDefinitionParser.java:66'));
        }
        if (e.texture !== undefined && String(e.texture) !== '' && !RL_FULL_RE.test(String(e.texture))) {
            f.push(finding('error', 'E-MOB-TEXTURE',
                `${label} 的 texture "${e.texture}" 不是合法 ResourceLocation。`,
                'MobDefinitionParser.java:75-77'));
        }
        if (e.sounds && typeof e.sounds === 'object') {
            for (const [k, v] of Object.entries(e.sounds)) {
                if (!RL_FULL_RE.test(String(v))) {
                    f.push(finding('error', 'E-MOB-SOUND',
                        `${label} 的 sounds.${k} "${v}" 不是合法 ResourceLocation。`,
                        'MobDefinitionParser.parseLoc'));
                }
            }
        }
        if (e.animations && typeof e.animations === 'object') {
            for (const [k, v] of Object.entries(e.animations)) {
                if (!/^\d+$/.test(String(k))) {
                    f.push(finding('error', 'E-MOB-ANIM-KEY',
                        `${label} 的 animations 键 "${k}" 必须是整数 state（解析器 Integer.parseInt）。`,
                        'MobDefinitionParser.java:90'));
                }
                if (!String(v).trim()) {
                    f.push(finding('warn', 'W-MOB-ANIM-VAL', `${label} 的 animations["${k}"] 为空。`, 'CONTENTPACK_FORMAT §6'));
                }
            }
        }
        if (Array.isArray(e.behaviors)) {
            e.behaviors.forEach((b, bi) => {
                const type = b && b.type !== undefined ? String(b.type) : '';
                if (!type) {
                    f.push(finding('warn', 'W-MOB-BEH-NO-TYPE',
                        `${label} 的 behaviors[${bi}] 没有 type：解析器会静默丢弃它（if (b.has("type"))）。`,
                        'MobDefinitionParser.java:98'));
                } else if (!BUILTIN_BEHAVIORS.includes(type)) {
                    f.push(finding('warn', 'W-MOB-BEH-UNKNOWN',
                        `${label} 的 behaviors[${bi}].type "${type}" 不是内置行为：装配时告警并跳过` +
                        `（除非已有包通过 BehaviorAssembler.registerFactory 注册了这个类型）。`,
                        'BehaviorAssembler.java:41-43'));
                } else if (type === 'ai' && Array.isArray(b.goals)) {
                    for (const g of b.goals) {
                        if (!AI_GOALS.includes(String(g))) {
                            f.push(finding('warn', 'W-MOB-AI-GOAL',
                                `${label} 的 ai goal "${g}" 不是内置 goal（${AI_GOALS.join('/')}）。`,
                                'AiBehavior.java:17'));
                        }
                    }
                }
            });
        }
    });
    return f;
}

function checkNumbers(f, label, obj, keys, where) {
    if (!obj || typeof obj !== 'object') return;
    for (const k of keys) {
        const v = obj[k];
        // 注意：只跳过「没写这个字段」。显式 null / 空串 / 字符串都要报错——
        // 编辑器里数值输入留空 → parseFloat('') = NaN → JSON.stringify(NaN) = null，
        // 这正是最常见的踩坑路径，解析器 getAsFloat(null) 直接抛异常。
        if (v === undefined) continue;
        if (typeof v !== 'number' || !Number.isFinite(v)) {
            f.push(finding('error', 'E-MOB-NUM',
                `${label} 的 ${where}.${k} = ${JSON.stringify(v)} 不是数字：` +
                `JSON 里会是 null，解析器 getAsFloat 直接抛异常 ⇒ 整个包加载失败。`,
                'MobDefinitionParser.java:49-57'));
        }
    }
}

const BUILTIN_BEHAVIORS = ['attack', 'ai', 'raycast'];
const AI_GOALS = ['hunt_player', 'random_stroll', 'look_around'];

// ============================================================
// Gradle 命令
// ============================================================

/** 包目录名 → Gradle 子项目路径（settings.gradle 自动发现 contentpacks-source/<dir>） */
function gradleProjectPath(packDirName) {
    return ':packs:' + packDirName;
}

/** 单包构建命令（人读用；实际执行见 main.js） */
function gradleBuildCommand(packDirName) {
    return `gradlew ${gradleProjectPath(packDirName)}:contentpackJar`;
}

/** 产物路径（Gradle 按 Module-Name 命名产物） */
function expectedJarName(moduleName) {
    return `${moduleName}.jar`;
}

const PackScaffoldAPI = {
    MODULE_ATTR_ORDER,
    MODULE_TYPE_VALUE,
    canonicalizeManifest,
    renderBuildGradle,
    renderEntryClass,
    entryLocation,
    validateManifest,
    validateEntities,
    splitList,
    gradleProjectPath,
    gradleBuildCommand,
    expectedJarName,
    BUILTIN_BEHAVIORS,
    AI_GOALS,
    ENTITY_ID_RE,
    RL_NAMESPACE_RE,
    RL_PATH_RE,
    RL_FULL_RE,
    MODULE_NAME_RE,
    JAVA_IDENT_RE,
};

if (typeof module === 'object' && module.exports) module.exports = PackScaffoldAPI;
if (typeof self !== 'undefined') self.PackScaffold = PackScaffoldAPI;

})();
