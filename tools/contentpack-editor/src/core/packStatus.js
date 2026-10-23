// packStatus.js - 内容包「四态」核对与 on/off 切换（主进程核心库，纯 Node，可被 node 测试）
//
// 为什么需要它（2026-10-24 事故的直接产物，见 design/BUGREPORT_2026-10-24_*.md）：
//   C 类缺陷 —— 源码里的 assets 已经搬走、jar 却从没重建，而 spark_modules 缓存永不清理，
//   于是「游戏里看得见」和「包里有没有」是两件不同的事，只有把四个状态并排摆出来才看得出裂缝。
// 四态 =
//   ① 源码目录 contentpacks-source/<包>/        （真相来源）
//   ② 产物 jar  contentpacks/<Module-Name>.jar  （构建结果）
//   ③ 运行目录安装副本 run/<侧>/contentpacks/…  （游戏真正读的那份；另含 loader 的第二扫描目录 mods/contentpacks/）
//   ④ spark 缓存 run/<侧>/spark_modules/<modid>/{models,animations}/… （渲染实际用的那份）
// 本模块只做【测量】与【搬运】：所有 md5 / mtime / 条目数都是读盘算出来的，不做任何推算。
//
// on/off 的实现依据（不新增运行期机制，只用 loader 已有的目录规则）：
//   ContentPackLoader.scanDirectory 只列 <扫描目录> 顶层的 *.jar，唯一的特例子目录是 _libs；
//   所以把产物 jar 移进 contentpacks/disabled/ 就等于「关」——根 build.gradle 的 copyContentPacksTo*
//   也是 include '*.jar'（顶层），同样不会把 disabled/ 里的东西拷回运行目录。
//   代价：NeoForge 的 DeferredRegister 没有反注册，已注册的实体/物品要等下次启动才消失；
//   而 spark 缓存是上一次启动写下的，不清缓存的话模型/动画还会显示 —— 面板必须把这两件事说出来。
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const JarReader = require('./jarReader');
const ManifestLib = require('./manifest');
const Scaffold = require('./packScaffold');

const JAR_NAME_RE = /^[A-Za-z0-9._-]+\.jar$/;
const DISABLED_DIR = 'disabled';
const STATE_FILE = '_editor-state.json';

// 与 contentpack.gradle 的 contentpackJar 排除表保持一致（源→jar 的映射就按这份判定）
const SOURCE_EXCLUDE_DIRS = new Set(['java', 'build', '.gradle', 'runs', 'bin', 'out', 'disabled']);
const SOURCE_EXCLUDE_FILES = new Set(['MANIFEST.MF', 'build.gradle', 'settings.gradle']);
const MAX_WALK_FILES = 4000;      // 目录树异常时的保险丝（真实包：非源码文件 ≤ 几十）
const MAX_DIFF_BYTES = 8 * 1024 * 1024; // 单文件超过就不比对内容（只说「未比对」）
const MAX_MD5_BYTES = JarReader.MAX_JAR_BYTES; // 与 JarReader 同量级：超过只报大小不报 md5

// ==================== 小工具 ====================

function md5Hex(buf) {
    return crypto.createHash('md5').update(buf).digest('hex');
}

function nowIso() {
    return new Date().toISOString();
}

function isoOf(ms) {
    return ms ? new Date(ms).toISOString().replace('T', ' ').slice(0, 19) : '—';
}

/** statSync 失败一律返回 null（四态里「不存在」是正常状态，不是错误）；目录也返回 stat */
function statOrNull(p) {
    if (!p) return null;
    try { return fs.statSync(p); } catch { return null; }
}

/** 文件测量值：{exists, path, rel, size, mtimeMs, mtimeIso, md5}（md5 仅对上限内的普通文件计算） */
function measureFile(abs, relForUi, withMd5 = true) {
    const st = statOrNull(abs);
    if (!st || !st.isFile()) return { exists: false, path: abs, rel: relForUi, size: 0, mtimeMs: 0, mtimeIso: null, md5: null };
    let md5 = null;
    if (withMd5 && st.size <= MAX_MD5_BYTES) {
        try { md5 = md5Hex(fs.readFileSync(abs)); } catch { md5 = null; }
    }
    return {
        exists: true, path: abs, rel: relForUi,
        size: st.size, mtimeMs: st.mtimeMs, mtimeIso: new Date(st.mtimeMs).toISOString(),
        md5,
    };
}

function safeReaddirAbs(dir) {
    try { return fs.readdirSync(dir, { withFileTypes: true }); } catch { return []; }
}

/** 校验 jar 文件名（用户/渲染端可控字符串 → 绝不拼路径） */
function checkJarName(jarName) {
    if (typeof jarName !== 'string' || !JAR_NAME_RE.test(jarName)) {
        throw new Error(`jar 名非法（仅允许字母/数字/._- 且以 .jar 结尾）: ${String(jarName)}`);
    }
    return jarName;
}

/** ctx = { root, packsSourceDir, packsOutDir } —— 由主进程注入，本模块不猜项目根 */
function requireCtx(ctx) {
    if (!ctx || !ctx.root || !ctx.packsOutDir) throw new Error('缺少项目根 / 产物目录上下文');
    return ctx;
}

function disabledDirOf(ctx) {
    return path.join(ctx.packsOutDir, DISABLED_DIR);
}

/** 停用标记文件：<产物目录>/disabled/_editor-state.json —— 「重建后又冒出 jar」的判定依据 */
function readDisabledState(ctx) {
    const f = path.join(disabledDirOf(ctx), STATE_FILE);
    try {
        const j = JSON.parse(fs.readFileSync(f, 'utf-8'));
        return (j && typeof j === 'object' && j.disabled && typeof j.disabled === 'object') ? j : { disabled: {} };
    } catch { return { disabled: {} }; }
}

function writeDisabledState(ctx, state) {
    const dir = disabledDirOf(ctx);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, STATE_FILE), JSON.stringify(state, null, 2), 'utf-8');
}

// ==================== ④ spark 缓存路径映射 ====================

/**
 * 复刻 ContentPackLoader.bridgeSparkResources（:594）的路径换算：
 *   spark_models/<type>/<ns>/<file>.json          → models/<ns>/<type>/<file>.json
 *   spark_animations/<type>/<ns>/<model>/<f>.json → animations/<ns>/<type>/<model>/<f>.json
 * 只有这两类可归属到某个 jar；缓存目录里的 textures/ 由 Spark 自身写入、jar 里没有对应物，
 * 所以本模块不声称能核对贴图 —— 面板如实显示「贴图缓存无法归属」。
 */
function sparkTargetsFromEntries(entryNames) {
    const models = [];
    const animations = [];
    for (const name of entryNames) {
        if (!name.endsWith('.json')) continue;
        if (name.startsWith('spark_models/')) {
            const parts = name.slice('spark_models/'.length).split('/');
            if (parts.length >= 3) {
                models.push({
                    src: name,
                    target: `${parts[1]}/${parts[0]}/${parts[parts.length - 1]}`,
                    kind: 'models',
                });
            }
        } else if (name.startsWith('spark_animations/')) {
            const parts = name.slice('spark_animations/'.length).split('/');
            if (parts.length >= 4) {
                animations.push({
                    src: name,
                    target: `${parts[1]}/${parts[0]}/${parts[2]}/${parts[parts.length - 1]}`,
                    kind: 'animations',
                });
            }
        }
    }
    return { models, animations };
}

/** 某运行目录下的 spark 模块目录（缓存根）；返回 [{dir, id}]，不硬编码 modid */
function sparkModuleDirs(runDir) {
    const base = path.join(runDir, 'spark_modules');
    return safeReaddirAbs(base)
        .filter((e) => e.isDirectory())
        .map((e) => ({ dir: path.join(base, e.name), id: e.name }));
}

// ==================== 三态之间的比对 ====================

/** 源码目录里「应当进 jar」的文件清单（相对路径用 / 分隔，与 jar 条目名同构） */
function listSourcePackFiles(sourceDir) {
    const out = [];
    let truncated = false;
    const walk = (abs, rel) => {
        if (out.length >= MAX_WALK_FILES) { truncated = true; return; }
        for (const e of safeReaddirAbs(abs)) {
            const childAbs = path.join(abs, e.name);
            const childRel = rel ? `${rel}/${e.name}` : e.name;
            if (e.isDirectory()) {
                if (SOURCE_EXCLUDE_DIRS.has(e.name)) continue;
                walk(childAbs, childRel);
            } else if (e.isFile()) {
                if (SOURCE_EXCLUDE_FILES.has(e.name)) continue;
                out.push({ rel: childRel, abs: childAbs });
                if (out.length >= MAX_WALK_FILES) { truncated = true; return; }
            }
        }
    };
    walk(sourceDir, '');
    return { files: out, truncated };
}

/**
 * 源码 ↔ 产物 jar 逐条目比对（这是「jar 是不是旧的」的唯一硬证据）。
 * 返回 { checked, equal, stale, missingInJar, extraInJar, skippedTooBig, newestSourceMs }
 */
function diffSourceVsJar(sourceDir, jarPath) {
    const res = {
        ok: false, checked: 0, equal: 0,
        stale: [], missingInJar: [], extraInJar: [], skippedTooBig: [],
        newestSourceMs: 0, note: null,
    };
    const st = statOrNull(jarPath);
    if (!st) { res.note = '产物 jar 不存在'; return res; }
    const { files, truncated } = listSourcePackFiles(sourceDir);
    let arc;
    try { arc = JarReader.openArchive(jarPath); }
    catch (e) { res.note = `产物 jar 无法解析：${e.message}`; return res; }
    try {
        const jarNames = new Set(arc.list());
        // 「jar 里有、源码里没有」只对真实文件有意义：目录条目（结尾 /）、编译产物（.class）、
        // META-INF/（含 MANIFEST）都是 Gradle 侧生成的，不算源码残留差异
        const jarRealFiles = [...jarNames].filter((n) =>
            !n.endsWith('/') && !n.endsWith('.class') && !n.startsWith('META-INF/'));
        for (const f of files) {
            const fstat = statOrNull(f.abs);
            if (fstat && fstat.isFile()) res.newestSourceMs = Math.max(res.newestSourceMs, fstat.mtimeMs);
            res.checked++;
            if (!jarNames.has(f.rel)) { res.missingInJar.push(f.rel); continue; }
            if (fstat && fstat.size > MAX_DIFF_BYTES) { res.skippedTooBig.push(f.rel); continue; }
            let srcBuf;
            try { srcBuf = fs.readFileSync(f.abs); } catch { res.skippedTooBig.push(f.rel); continue; }
            const jarBuf = arc.read(f.rel);
            if (jarBuf === null) { res.missingInJar.push(f.rel); continue; }
            if (md5Hex(srcBuf) === md5Hex(jarBuf)) res.equal++;
            else res.stale.push(f.rel);
        }
        const srcSet = new Set(files.map((f) => f.rel));
        res.extraInJar = jarRealFiles.filter((n) => !srcSet.has(n));
        res.ok = true;
        res.truncated = truncated;
    } finally {
        try { arc.close(); } catch { /* 只读 */ }
    }
    return res;
}

/**
 * jar 内 spark 资源 ↔ 运行目录 spark 缓存逐文件比对。
 * 返回 { total, present, match, stale, absent, moduleDirs, items:[{src,target,cachePath,state}] }
 */
function diffJarAgainstSparkCache(jarPath, runDir) {
    const out = {
        total: 0, present: 0, match: 0, stale: 0, absent: 0,
        moduleDirs: sparkModuleDirs(runDir).map((m) => m.id),
        hasCacheRoot: fs.existsSync(path.join(runDir, 'spark_modules')),
        items: [], checked: false, note: null,
    };
    if (!out.hasCacheRoot) out.note = '该运行侧没有 spark_modules 缓存目录（没跑过 / 服务端不写缓存）';
    if (!statOrNull(jarPath)) { out.note = '无产物 jar'; return out; }
    let arc;
    try { arc = JarReader.openArchive(jarPath); }
    catch (e) { out.note = `jar 无法解析：${e.message}`; return out; }
    try {
        const names = arc.list();
        const { models, animations } = sparkTargetsFromEntries(names);
        const targets = [...models, ...animations];
        out.total = targets.length;
        for (const t of targets) {
            let jarBuf;
            try { jarBuf = arc.read(t.src); } catch { jarBuf = null; }
            if (jarBuf === null) continue;
            const wantMd5 = md5Hex(jarBuf);
            // 同名 target 在多个 spark 模块目录里都可能出现：逐个查，任一命中即算 present
            let hit = null;
            let hitState = 'absent';
            for (const m of out.moduleDirs) {
                const cacheAbs = path.join(runDir, 'spark_modules', m, t.kind, t.target.replace(/\//g, path.sep));
                const cst = statOrNull(cacheAbs);
                if (!cst) continue;
                hit = cacheAbs;
                let same = false;
                try { same = cst.size <= MAX_MD5_BYTES && md5Hex(fs.readFileSync(cacheAbs)) === wantMd5; }
                catch { same = false; }
                hitState = same ? 'match' : 'stale';
                break;
            }
            if (hit) out.present++; else out.absent++;
            if (hitState === 'match') out.match++;
            else if (hitState === 'stale') out.stale++;
            out.items.push({
                src: t.src, kind: t.kind, target: t.target,
                cachePath: hit, state: hitState,
                jarMd5: wantMd5.slice(0, 8),
            });
        }
        out.checked = true;
    } finally {
        try { arc.close(); } catch { /* 只读 */ }
    }
    return out;
}

// ==================== 运行目录（安装位）枚举 ====================

/**
 * 游戏会读的所有扫描位：<run>/<侧>/contentpacks 与 <run>/<侧>/mods/contentpacks
 * （依据 CatoxidesBattleRebuild.java:79-83 → ContentPackLoader.loadAll 的两个扫描目录）
 * 只认「已经存在 contentpacks 目录」或名字是 client/server/gameTestServer 的运行目录，
 * 避免把 scratch 之类的杂项目录当成部署位。
 */
function listInstallDirs(ctx) {
    const runRoot = path.join(ctx.root, 'run');
    const res = [];
    for (const e of safeReaddirAbs(runRoot)) {
        if (!e.isDirectory()) continue;
        const side = path.join(runRoot, e.name);
        const known = /^(client|server|gameTestServer|junit)$/i.test(e.name);
        const candidates = [
            { dir: path.join(side, 'contentpacks'), label: `run/${e.name}/contentpacks`, gameDir: side },
            { dir: path.join(side, 'mods', 'contentpacks'), label: `run/${e.name}/mods/contentpacks`, gameDir: side },
        ];
        for (const c of candidates) {
            const exists = fs.existsSync(c.dir);
            if (exists || known) res.push({ ...c, exists, side: e.name });
        }
    }
    return res;
}

// ==================== 四态汇总 ====================

function jarEntryCount(jarPath) {
    try { return JarReader.listEntries(jarPath).length; } catch { return null; }
}

function jarManifest(jarPath) {
    try { return JarReader.readManifest(jarPath); } catch { return null; }
}

/**
 * 单个包的四态。dirName = 包目录名；ctx 见 requireCtx。
 * 纯读盘，任何一处失败都只影响那一态（不影响其它三态的呈现）。
 */
function inspectPack(ctx, dirName, installDirs, disabledState) {
    requireCtx(ctx);
    const sourceDir = path.join(ctx.packsSourceDir || path.join(ctx.root, 'contentpacks-source'), dirName);
    let mf = null;
    let manifestError = null;
    try { mf = ManifestLib.readManifest(sourceDir); }
    catch (e) { manifestError = String(e.message || e); }
    const moduleName = (mf && mf['Module-Name']) || '';
    // Module-Name 缺失时按目录名推断产物名（contentpack.gradle 的兜底是 project.name）
    const jarName = Scaffold.expectedJarName(moduleName || dirName);
    const jarNameOk = JAR_NAME_RE.test(jarName);
    const builtPath = jarNameOk ? path.join(ctx.packsOutDir, jarName) : null;
    const archivedPath = jarNameOk ? path.join(disabledDirOf(ctx), jarName) : null;

    const built = measureFile(builtPath, `contentpacks/${jarName}`);
    const archived = measureFile(archivedPath, `contentpacks/disabled/${jarName}`);
    const effectiveJar = built.exists ? builtPath : (archived.exists ? archivedPath : null);

    const installed = installDirs.map((d) => {
        if (!jarNameOk) return { exists: false, rel: d.label, path: null, size: 0, mtimeMs: 0, md5: null, matchesBuilt: null };
        const p = path.join(d.dir, jarName);
        const m = measureFile(p, d.label);
        if (m.exists && built.exists && m.md5 && built.md5) m.matchesBuilt = (m.md5 === built.md5);
        else if (m.exists) m.matchesBuilt = null; // 无法判定（超大文件或读取失败）
        return m;
    });

    const srcStat = statOrNull(sourceDir);
    const source = {
        exists: !!srcStat,
        dir: sourceDir,
        isPack: !!mf,
        manifestError,
        moduleName,
        version: (mf && mf['Module-Version']) || '',
        deps: (mf && mf['Module-Dependencies']) || '',
        entry: (mf && mf['Module-Entry']) || '',
        hasSparkModels: fs.existsSync(path.join(sourceDir, 'spark_models')),
        hasEntities: fs.existsSync(path.join(sourceDir, 'entities')),
        hasJava: fs.existsSync(path.join(sourceDir, 'java')),
        mtimeMs: srcStat ? srcStat.mtimeMs : 0,
    };

    const diff = diffSourceVsJar(sourceDir, builtPath);
    // spark 缓存按「运行侧」各查一次（同一侧的 contentpacks 与 mods/contentpacks 共用一份缓存）
    const cacheSides = [...new Map(
        installDirs.filter((d) => d.exists).map((d) => [d.gameDir, d]),
    ).values()];
    const cache = cacheSides.map((d) => ({
        label: `run/${d.side}`,
        gameDir: d.gameDir,
        ...diffJarAgainstSparkCache(effectiveJar || builtPath, d.gameDir),
    }));

    const disabledMark = disabledState.disabled[jarName] || null;

    // ---------- 诊断（只报测得的事实，每条都给出处） ----------
    const issues = [];
    const jarManifestAttrs = built.exists ? jarManifest(builtPath) : (archived.exists ? jarManifest(archivedPath) : null);
    const jarModuleType = jarManifestAttrs ? jarManifestAttrs['Module-Type'] : null;

    if (!source.exists) {
        issues.push({ level: 'err', code: 'E-SOURCE-MISSING', msg: `包目录不存在：${path.relative(ctx.root, sourceDir)}`, src: 'contentpacks-source/' });
    }
    if (!jarNameOk) {
        issues.push({ level: 'err', code: 'E-MODULE-NAME-BAD', msg: `Module-Name="${moduleName}" 拼出的产物名 ${jarName} 含非法字符，无法核对与搬运（仅允许字母/数字/._-）`, src: 'contentpack.gradle: archiveFileName' });
    }
    if (!built.exists && !archived.exists) {
        issues.push({ level: 'err', code: 'E-NO-JAR', msg: `没有产物 jar（预期 contentpacks/${jarName}）—— 源码改了也不会进游戏`, src: 'contentpack.gradle: destinationDirectory' });
    }
    if (built.exists && jarModuleType !== 'contentpack') {
        issues.push({ level: 'err', code: 'E-JAR-TYPE', msg: `产物 Module-Type="${jarModuleType || ''}"，运行期会被静默跳过`, src: 'ContentPackManifest.parseAttributes' });
    }
    if (built.exists && moduleName && jarManifestAttrs && jarManifestAttrs['Module-Name'] !== moduleName) {
        issues.push({ level: 'err', code: 'E-JAR-NAME', msg: `产物 Module-Name="${jarManifestAttrs['Module-Name'] || ''}" 与源码 "${moduleName}" 不一致`, src: 'contentpack.gradle: archiveFileName' });
    }
    if (diff.ok) {
        if (diff.missingInJar.length) {
            issues.push({ level: 'err', code: 'E-SRC-NOT-IN-JAR', msg: `源码有 ${diff.missingInJar.length} 个文件不在产物 jar 里（jar 落后于源码 / 新文件未被打包）：${diff.missingInJar.slice(0, 6).join(', ')}${diff.missingInJar.length > 6 ? ' …' : ''}`, src: 'contentpack.gradle: from(projectDir)' });
        }
        if (diff.stale.length) {
            issues.push({ level: 'err', code: 'E-SRC-JAR-DRIFT', msg: `${diff.stale.length} 个文件源码与 jar 内容不同（需重建）：${diff.stale.slice(0, 6).join(', ')}${diff.stale.length > 6 ? ' …' : ''}`, src: 'md5 逐条目比对' });
        }
        if (diff.truncated) {
            issues.push({ level: 'warn', code: 'W-DIFF-TRUNCATED', msg: `包内文件数超过 ${MAX_WALK_FILES}，比对未覆盖全部`, src: 'packStatus.js MAX_WALK_FILES' });
        }
    } else if (diff.note && built.exists) {
        issues.push({ level: 'err', code: 'E-JAR-UNREADABLE', msg: diff.note, src: 'JarReader.openArchive' });
    }
    for (const m of installed) {
        if (m.exists && m.matchesBuilt === false) {
            issues.push({ level: 'err', code: 'E-INSTALL-DRIFT', msg: `${m.rel} 与产物 jar md5 不同（旧副本残留）`, src: 'build.gradle: copyContentPacksTo*' });
        }
    }
    if (built.exists && installed.length && !installed.some((m) => m.exists && m.matchesBuilt !== false)) {
        issues.push({ level: 'warn', code: 'W-NOT-INSTALLED', msg: '产物 jar 未出现在任何运行目录 —— 下次启动不会加载（Gradle 的 copy 任务或面板「安装」可补）', src: 'ContentPackLoader.loadAll 扫描目录' });
    }
    for (const c of cache) {
        if (!c.checked) continue;
        if (c.stale) issues.push({ level: 'err', code: 'E-CACHE-STALE', msg: `${c.label} 的 spark 磁盘缓存有 ${c.stale}/${c.total} 个同名文件内容不同于 jar —— 缓存是 Spark 直接读盘那份（SparkPackResourceLoader.loadAllModules），游戏看到的可能是它而不是包里的版本`, src: 'ContentPackLoader.bridgeSparkResources + spark_modules/' });
        if (c.absent) issues.push({ level: 'info', code: 'I-CACHE-EMPTY', msg: `${c.label} 有 ${c.absent}/${c.total} 个 spark 资源不在磁盘缓存里${c.hasCacheRoot ? '' : `（${c.note}）`} —— 内容包资源走内存桥接，缓存没有 ≠ 游戏里看不见`, src: 'run/*/spark_modules' });
    }
    if (disabledMark && built.exists) {
        issues.push({ level: 'err', code: 'E-DISABLED-BUT-BUILT', msg: `标记为停用，但 contentpacks/${jarName} 又出现了（重建产物所致）—— 游戏会照常加载它`, src: 'disabled/' + STATE_FILE });
    }

    const enabled = built.exists && !disabledMark;
    const drift = {
        sourceNewerThanJar: diff.ok && diff.newestSourceMs > 0 && built.exists ? (diff.newestSourceMs > built.mtimeMs) : null,
        sourceNewestMs: diff.newestSourceMs || null,
        jarMs: built.exists ? built.mtimeMs : null,
        driftSeconds: (diff.ok && built.exists && diff.newestSourceMs > built.mtimeMs)
            ? Math.round((diff.newestSourceMs - built.mtimeMs) / 1000) : 0,
    };

    return {
        dirName, jarName, source,
        built: { ...built, entries: built.exists ? jarEntryCount(builtPath) : 0 },
        archived: { ...archived, entries: archived.exists ? jarEntryCount(archivedPath) : 0, disabledAt: disabledMark ? disabledMark.at : null },
        installed,
        cache,
        diff,
        drift,
        enabled,
        hasDisabledMarker: !!disabledMark,
        issues,
    };
}

/** 全量：包库目录（+ 新建包目录）里每个包目录的四态，附重复 Module-Name 交叉检查 */
function inspectAll(ctx, opts = {}) {
    requireCtx(ctx);
    const sourceDir = ctx.packsSourceDir || path.join(ctx.root, 'contentpacks-source');
    const dirs = safeReaddirAbs(sourceDir).filter((e) => e.isDirectory() && !SOURCE_EXCLUDE_DIRS.has(e.name) && e.name !== '_libs')
        .map((e) => e.name);
    if (ctx.newPackDir && ctx.newPackDir !== sourceDir) {
        for (const e of safeReaddirAbs(ctx.newPackDir)) {
            if (e.isDirectory() && !SOURCE_EXCLUDE_DIRS.has(e.name) && e.name !== '_libs' && !dirs.includes(e.name)) dirs.push(e.name);
        }
    }
    const wanted = Array.isArray(opts.only) && opts.only.length ? opts.only : null;
    const installDirs = listInstallDirs(ctx);
    const state = readDisabledState(ctx);
    const all = dirs.filter((d) => !wanted || wanted.includes(d))
        .map((d) => inspectPack(ctx, d, installDirs, state));
    // 包库里没有 MANIFEST.MF 的目录（如 editor/）不是内容包：单列出来，不进四态表，避免把「不是包」当成「包坏了」
    const packs = all.filter((p) => p.source.isPack);
    const nonPackDirs = all.filter((p) => !p.source.isPack)
        .map((p) => ({ dirName: p.dirName, reason: p.source.manifestError || '无 MANIFEST.MF' }));

    // 产物目录里两个 jar 声明同一个 Module-Name = 运行期只会留下一个（真实事故来源）
    const byModule = new Map();
    for (const e of safeReaddirAbs(ctx.packsOutDir)) {
        if (!e.isFile() || !/\.jar$/i.test(e.name)) continue;
        const m = jarManifest(path.join(ctx.packsOutDir, e.name));
        if (!m) continue;
        const key = m['Module-Name'] || '(无 Module-Name)';
        if (!byModule.has(key)) byModule.set(key, []);
        byModule.get(key).push(e.name);
    }
    const duplicates = [...byModule.entries()].filter(([, names]) => names.length > 1);

    return {
        root: ctx.root,
        packsOutDir: ctx.packsOutDir,
        packsSourceDir: sourceDir,
        disabledDir: disabledDirOf(ctx),
        installDirs,
        packs: packs.sort((a, b) => a.dirName.localeCompare(b.dirName)),
        nonPackDirs,
        duplicates,
        scannedAt: nowIso(),
    };
}

// ==================== 搬运动作 ====================

function moveTo(src, dst) {
    fs.mkdirSync(path.dirname(dst), { recursive: true });
    if (statOrNull(dst)) fs.rmSync(dst, { force: true });
    fs.renameSync(src, dst);
}

function copyTo(src, dst) {
    fs.mkdirSync(path.dirname(dst), { recursive: true });
    fs.copyFileSync(src, dst);
}

/** 搬运动作统一包一层：游戏在跑时 jar 会被占用（EPERM/EBUSY），单条失败不该中断整个切换 */
function tryOp(label, fn) {
    try { return { ok: true, label, value: fn() }; }
    catch (e) { return { ok: false, label, error: `${label} 失败：${e.message}` }; }
}

/** 不删任何 jar：让位的那份改名收进 disabled/。绝不能留在 contentpacks/ —— loader 扫的就是那层 *.jar，会把它当成一个包 */
function parkAsStale(absPath, jarName, ctx) {
    const st = statOrNull(absPath);
    if (!st) return null;
    const dir = disabledDirOf(ctx);
    fs.mkdirSync(dir, { recursive: true });
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    const parked = path.join(dir, `STALE-${stamp}-${jarName}`);
    fs.renameSync(absPath, parked);
    return { parked: path.basename(parked), size: st.size, mtimeIso: new Date(st.mtimeMs).toISOString() };
}

/**
 * on/off 切换（不新增运行期机制：移动产物 jar + 增删安装副本）。
 * 返回 { enabled, actions:[可读的搬运记录], needsRelaunch, cacheWarning, hint }
 */
function setEnabled(ctx, jarName, enabled) {
    requireCtx(ctx);
    checkJarName(jarName);
    const builtPath = path.join(ctx.packsOutDir, jarName);
    const archivedPath = path.join(disabledDirOf(ctx), jarName);
    const state = readDisabledState(ctx);
    const actions = [];
    const installDirs = listInstallDirs(ctx).filter((d) => d.exists);

    if (!enabled) {
        state.disabled[jarName] = { at: nowIso(), reason: 'editor-toggle' };
        if (statOrNull(builtPath)) {
            const r = tryOp(`移出产物 contentpacks/${jarName}`, () => moveTo(builtPath, archivedPath));
            actions.push(r.ok
                ? `移出产物：contentpacks/${jarName} → contentpacks/disabled/${jarName}`
                : `✗ ${r.error}（游戏正在运行占用 jar？先关掉客户端再切）`);
        } else if (statOrNull(archivedPath)) {
            actions.push(`产物已在 disabled/ 下（未重复移动）`);
        } else {
            actions.push('没有产物 jar（本来就未部署）；已写下停用标记，重建后需再点一次或用「重新归档」');
        }
        for (const d of installDirs) {
            const p = path.join(d.dir, jarName);
            if (statOrNull(p)) {
                const r = tryOp(`删除安装副本 ${d.label}/${jarName}`, () => fs.rmSync(p, { force: true }));
                if (r.ok) actions.push(`删除安装副本：${d.label}/${jarName}`);
                else actions.push(`✗ ${r.error}`);
            }
        }
    } else {
        delete state.disabled[jarName];
        const b = statOrNull(builtPath);
        const a = statOrNull(archivedPath);
        if (b && a) {
            // 两者都在 → 谁是刚构建的那份？按 mtime 判定；输的那份改名留在 disabled/，绝不删除
            const winnerIsBuilt = b.mtimeMs >= a.mtimeMs;
            const loser = winnerIsBuilt ? archivedPath : builtPath;
            const parked = tryOp('让位副本改名留存 disabled/', () => parkAsStale(loser, jarName, ctx));
            if (!parked.ok) {
                actions.push(`✗ ${parked.error}`);
            } else if (winnerIsBuilt) {
                actions.push(`产物更新（${isoOf(b.mtimeMs)} ≥ ${isoOf(a.mtimeMs)}）：归档让位为 ${parked.value.parked}`);
            } else {
                actions.push(`归档更新（${isoOf(a.mtimeMs)} > ${isoOf(b.mtimeMs)}）：用停用时的版本覆盖产物，旧产物让位为 ${parked.value.parked}`);
                const up = tryOp(`提升归档 contentpacks/${jarName}`, () => moveTo(archivedPath, builtPath));
                actions.push(up.ok ? `disabled/${jarName} → contentpacks/${jarName}` : `✗ ${up.error}`);
            }
        } else if (a && !b) {
            const r = tryOp(`归档回迁 contentpacks/${jarName}`, () => moveTo(archivedPath, builtPath));
            actions.push(r.ok
                ? `归档回到产物目录：disabled/${jarName} → contentpacks/${jarName}`
                : `✗ ${r.error}`);
        } else if (!a && !b) {
            actions.push('既无产物也无归档 —— 需先打包（Gradle contentpackJar）');
        } else {
            actions.push('产物本来就在位');
        }
        if (statOrNull(builtPath)) {
            for (const d of installDirs) {
                const p = path.join(d.dir, jarName);
                if (!statOrNull(p)) {
                    const r = tryOp(`安装到 ${d.label}/${jarName}`, () => copyTo(builtPath, p));
                    actions.push(r.ok ? `安装到：${d.label}/${jarName}` : `✗ ${r.error}`);
                }
            }
        }
    }
    const stateWrite = tryOp(`写停用标记 ${DISABLED_DIR}/${STATE_FILE}`, () => writeDisabledState(ctx, state));

    const failed = actions.filter((a) => a.startsWith('✗'));
    // 「关掉后还会不会看见」取决于磁盘缓存里有没有同名条目 —— 逐个实测，不含糊
    const effectivePath = statOrNull(builtPath) ? builtPath : archivedPath;
    const cachePresent = [];
    for (const run of [...new Map(listInstallDirs(ctx).filter((d) => d.exists).map((d) => [d.gameDir, d])).values()]) {
        const d = diffJarAgainstSparkCache(effectivePath, run.gameDir);
        if (d.total > 0) cachePresent.push({ label: `run/${run.side}`, total: d.total, present: d.present, stale: d.stale, match: d.match });
    }
    let sparkTotal = 0;
    try {
        const t = sparkTargetsFromEntries(JarReader.listEntries(effectivePath).map((e) => e.name));
        sparkTotal = t.models.length + t.animations.length;
    } catch { sparkTotal = 0; }

    return {
        ok: failed.length === 0 && stateWrite.ok,
        enabled: !!enabled,
        actions,
        needsRelaunch: true,
        sparkResourceCount: sparkTotal,
        cachePresent,
        cacheWarning: sparkTotal > 0
            ? `本包声明 ${sparkTotal} 个 spark 模型/动画条目；磁盘缓存实测：` +
              (cachePresent.length
                  ? cachePresent.map((c) => `${c.label} 命中 ${c.present}/${c.total}（其中 ${c.stale} 个内容不同）`).join('；')
                  : '各运行侧都没有 spark_modules 缓存目录') +
              (enabled
                  ? '。启用后若内容与缓存不同，先「清本包缓存」再进游戏看效果。'
                  : `。${cachePresent.some((c) => c.present > 0) ? '缓存里仍有本包条目 → 游戏里还会看见那些模型/动画，那是磁盘缓存不是「没关掉」，点「清本包缓存」才算真关掉。' : '缓存里没有本包条目 → 停用后不会再显示。'}`)
            : null,
        error: failed.length || !stateWrite.ok
            ? `有动作失败：\n${[...failed, stateWrite.ok ? '' : stateWrite.error].filter(Boolean).join('\n')}`
            : undefined,
        hint: 'NeoForge 的注册表（DeferredRegister）无反注册：已生成的实体/物品不会当场消失，下次启动才生效。',
    };
}

/** 把产物 jar 复制到各运行目录的扫描位（等价于 Gradle 的 copyContentPacksTo*，但不必跑构建） */
function installToRun(ctx, jarName, labels) {
    requireCtx(ctx);
    checkJarName(jarName);
    const builtPath = path.join(ctx.packsOutDir, jarName);
    if (!statOrNull(builtPath)) return { ok: false, error: `产物不存在：contentpacks/${jarName}（先打包）` };
    const src = measureFile(builtPath, `contentpacks/${jarName}`);
    const written = [];
    const skipped = [];
    const failed = [];
    for (const d of listInstallDirs(ctx)) {
        if (!d.exists) { skipped.push(`${d.label}（目录不存在）`); continue; }
        if (Array.isArray(labels) && labels.length && !labels.includes(d.label)) continue;
        const dst = path.join(d.dir, jarName);
        const cur = measureFile(dst, d.label);
        if (cur.exists && cur.md5 && cur.md5 === src.md5) { skipped.push(`${d.label}（md5 已一致）`); continue; }
        try {
            copyTo(builtPath, dst);
            written.push(`${d.label}/${jarName}`);
        } catch (e) {
            failed.push(`${d.label}/${jarName} —— ${e.message}`);
        }
    }
    return {
        ok: failed.length === 0, written, skipped, failed,
        md5: src.md5, size: src.size,
        error: failed.length ? `部分安装失败：\n${failed.join('\n')}` : undefined,
    };
}

/** 只删「jar 里确有、且缓存内容 = jar 内容」的文件；内容不同的（可能被别的包占用同 id）不动，如实报出 */
function clearSparkCache(ctx, jarName, runDirLabels) {
    requireCtx(ctx);
    checkJarName(jarName);
    const jarPath = statOrNull(path.join(ctx.packsOutDir, jarName))
        ? path.join(ctx.packsOutDir, jarName)
        : path.join(disabledDirOf(ctx), jarName);
    if (!statOrNull(jarPath)) return { ok: false, error: `找不到 ${jarName}（产物与归档都没有）` };
    let arc;
    try { arc = JarReader.openArchive(jarPath); }
    catch (e) { return { ok: false, error: `jar 无法解析：${e.message}` }; }
    const deleted = [];
    const conflicts = [];
    const absent = [];
    try {
        const names = arc.list();
        const { models, animations } = sparkTargetsFromEntries(names);
        const targets = [...models, ...animations];
        for (const run of [...new Map(
            listInstallDirs(ctx).filter((d) => d.exists).map((d) => [d.gameDir, d]),
        ).values()]) {
            const absRun = run.gameDir;
            const sideLabel = `run/${run.side}`;
            if (Array.isArray(runDirLabels) && runDirLabels.length && !runDirLabels.includes(sideLabel)) continue;
            const mods = sparkModuleDirs(absRun);
            for (const t of targets) {
                const jarBuf = arc.read(t.src);
                if (jarBuf === null) continue;
                const wantMd5 = md5Hex(jarBuf);
                let any = false;
                for (const m of mods) {
                    const cacheAbs = path.join(m.dir, t.kind, t.target.replace(/\//g, path.sep));
                    if (!statOrNull(cacheAbs)) continue;
                    any = true;
                    let curMd5 = null;
                    try { curMd5 = md5Hex(fs.readFileSync(cacheAbs)); } catch { curMd5 = null; }
                    if (curMd5 !== wantMd5) {
                        conflicts.push(`${sideLabel} · ${m.id}/${t.kind}/${t.target}（与 jar 内容不同，可能是别的包的同 id 资源 → 未删）`);
                        continue;
                    }
                    fs.rmSync(cacheAbs, { force: true });
                    deleted.push(`${sideLabel} · ${m.id}/${t.kind}/${t.target}`);
                }
                if (!any) absent.push(`${sideLabel} · ${t.kind}/${t.target}`);
            }
        }
    } finally {
        try { arc.close(); } catch { /* 只读 */ }
    }
    return {
        ok: true, jarPath, deleted, conflicts,
        absentCount: absent.length,
        note: deleted.length
            ? '已删除的缓存会在下次启动时由包重新桥接生成。'
            : '没有需要删除的缓存文件（缓存里没有本包条目，或已被清过）。',
    };
}

/** 停用标记存在、但重建又把 jar 放回了 contentpacks/ → 按标记重新归档 */
function rearchiveByMarker(ctx, jarName) {
    requireCtx(ctx);
    checkJarName(jarName);
    const state = readDisabledState(ctx);
    if (!state.disabled[jarName]) return { ok: false, error: `${jarName} 没有停用标记（先点「停用」）` };
    const r = setEnabled(ctx, jarName, false);
    return { ...r, rearchived: true };
}

module.exports = {
    inspectAll,
    inspectPack,
    listInstallDirs,
    diffSourceVsJar,
    diffJarAgainstSparkCache,
    sparkTargetsFromEntries,
    listSourcePackFiles,
    readDisabledState,
    setEnabled,
    installToRun,
    clearSparkCache,
    rearchiveByMarker,
    DISABLED_DIR,
    STATE_FILE,
};
