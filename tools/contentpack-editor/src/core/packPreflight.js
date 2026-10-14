// packPreflight.js - 打包前预检（需要 fs，Node 侧运行）
//
// 目的：把「构建成功 ≠ 能加载」之间的坑提前暴露在编辑器里，
// 而不是等游戏启动后在日志里翻。规则来自 packScaffold.validateManifest /
// validateEntities（纯函数，单测覆盖）+ 目录/产物层面的一致性检查。
const fs = require('fs');
const path = require('path');

const ManifestLib = require('./manifest');
const Scaffold = require('./packScaffold');
const JarReader = require('./jarReader');

/**
 * 预检一个包目录。
 *
 * @param {string} packDir contentpacks-source/<包>
 * @param {{root?:string}} [opts] root = 项目根（用于扫描 contentpacks/ 产物与运行时目录）
 * @returns {{ok:boolean, errors:Array, warnings:Array, findings:Array, manifest:Object, entities:Array,
 *            expectedJar:string, gradleTask:string}}
 */
function preflightPack(packDir, opts = {}) {
    const findings = [];
    let manifest = {};
    const entities = [];

    // --- 1. MANIFEST ---
    try {
        manifest = ManifestLib.readManifest(packDir);
    } catch (e) {
        findings.push({ level: 'error', code: 'E-NO-MANIFEST', msg: `读不到 MANIFEST.MF：${e.message}`, src: 'ContentPackManifest.fromJar' });
    }
    findings.push(...Scaffold.validateManifest(manifest));

    // --- 2. 实体 JSON ---
    const entitiesDir = path.join(packDir, 'entities');
    if (fs.existsSync(entitiesDir)) {
        for (const file of fs.readdirSync(entitiesDir).filter((f) => f.endsWith('.json'))) {
            const full = path.join(entitiesDir, file);
            try {
                const obj = JSON.parse(fs.readFileSync(full, 'utf-8'));
                entities.push(obj);
                // 文件名与 id 必须一致：加载器/骨架按 id 找 entities/<id>.json
                if (obj && obj.id && `${obj.id}.json` !== file) {
                    findings.push({
                        level: 'warn', code: 'W-ENT-FILENAME',
                        msg: `entities/${file} 的 id 是 "${obj.id}"：文件名与 id 不一致，骨架代码按 entities/${obj.id}.json 读取会找不到。`,
                        src: 'TestMobPack.java:40',
                    });
                }
            } catch (e) {
                findings.push({ level: 'error', code: 'E-ENT-JSON', msg: `entities/${file} 不是合法 JSON：${e.message}`, src: 'MobDefinitionParser.parse' });
            }
        }
    }
    findings.push(...Scaffold.validateEntities(entities));

    // --- 3. 有数据无入口：entities 不会被自动注册 ---
    const entry = (manifest['Module-Entry'] || '').trim();
    if (!entry && entities.length > 0) {
        findings.push({
            level: 'error', code: 'E-DATA-NO-ENTRY',
            msg: `有 ${entities.length} 个 entities/*.json 但 MANIFEST 没有 Module-Entry：加载器不会自动注册 entities/，` +
                `只有包的 register() 调 ContentPackContext.registerDataDrivenMob 才会注册 ⇒ 这些 JSON 是死数据（游戏里不存在该实体）。` +
                `填上 Module-Entry 并让编辑器生成入口类即可。`,
            src: 'ContentPackContext.java:198 / TestMobPack.java:40-47',
        });
    }

    // --- 4. 入口类源码是否存在 ---
    if (entry) {
        const loc = Scaffold.entryLocation(entry);
        if (loc && !fs.existsSync(path.join(packDir, loc.path))) {
            findings.push({
                level: 'error', code: 'E-ENTRY-NO-SOURCE',
                msg: `Module-Entry 指向 ${loc.path}，但该源码不存在：编译产物里没有入口类 ⇒ 运行期找不到入口。` +
                    `（若这是只作复制用的模板包，可忽略。）`,
                src: 'ContentPackLoader（入口类实例化）',
            });
        }
    }

    // --- 5. Gradle 骨架 ---
    const hasBuildGradle = fs.existsSync(path.join(packDir, 'build.gradle'));
    if (!hasBuildGradle) {
        findings.push({
            level: 'warn', code: 'W-NO-BUILD-GRADLE',
            msg: '缺少 build.gradle：settings.gradle 自动发现只认有 build.gradle 的目录，' +
                '所以 gradlew contentpackJar 不会构建它（只能走回退脚本 build-pack.ps1）。' +
                '在编辑器里「保存 MANIFEST」或重新创建即可补上。',
            src: 'settings.gradle（条件纳入 + 自动发现）',
        });
    }

    // --- 6. 产物层面：同名 Module-Name 的重复 jar ---
    const moduleName = (manifest['Module-Name'] || '').trim();
    const expectedJar = moduleName ? Scaffold.expectedJarName(moduleName) : '(未知)';
    if (moduleName && opts.root) {
        findings.push(...scanDuplicateModuleName(opts.root, moduleName, expectedJar));
    }

    const errors = findings.filter((x) => x.level === 'error');
    const warnings = findings.filter((x) => x.level === 'warn');
    return {
        ok: errors.length === 0,
        errors,
        warnings,
        findings,
        manifest,
        entities,
        expectedJar,
        gradleTask: `${Scaffold.gradleProjectPath(path.basename(packDir))}:contentpackJar`,
        gradleCommand: Scaffold.gradleBuildCommand(path.basename(packDir)),
    };
}

/**
 * 扫描 contentpacks/ 与 run/client/contentpacks/ 里【同 Module-Name 的其它 jar】。
 *
 * 为什么必须查：ContentPackLoader 遇到重复 Module-Name 只打印
 *   Duplicate ContentPack Module-Name 'x': ignoring '<后扫描到的>' (already provided by '<先扫描到的>')
 * 然后丢弃一个——【哪个被加载取决于扫描顺序】，不可复现。
 * 典型成因：产物按 Module-Name 命名后旧文件名残留（如 x-pack.jar → x.jar），
 * 而 copyContentPacksToClient 是 Copy 不是 Sync，运行时目录里的陈旧文件也不会被清掉。
 */
function scanDuplicateModuleName(root, moduleName, expectedJar) {
    const findings = [];
    const dirs = [
        path.join(root, 'contentpacks'),
        path.join(root, 'run', 'client', 'contentpacks'),
        path.join(root, 'run', 'server', 'contentpacks'),
    ];
    for (const dir of dirs) {
        if (!fs.existsSync(dir)) continue;
        for (const file of fs.readdirSync(dir).filter((f) => f.toLowerCase().endsWith('.jar'))) {
            const full = path.join(dir, file);
            let theirs = null;
            try {
                theirs = JarReader.readManifest(full);
            } catch {
                continue; // 不是有效 jar（可能正在被写入），跳过
            }
            if (!theirs || theirs['Module-Name'] !== moduleName) continue;
            const rel = path.relative(root, full);
            if (file === expectedJar) {
                continue; // 就是本包的预期产物，正常
            }
            findings.push({
                level: 'error', code: 'E-DUP-MODULE-NAME',
                msg: `${rel} 与本次产物同名（Module-Name "${moduleName}"）：加载器会丢弃其中一个，` +
                    `且【丢弃哪个取决于扫描顺序】⇒ 不可复现。请删除该文件（预期产物名是 contentpacks/${expectedJar}）。`,
                src: 'ContentPackLoader（Duplicate Module-Name）',
            });
        }
    }
    return findings;
}

/** 生成包骨架文件清单（路径 → 内容），供 pack:create / 补齐脚本使用 */
function scaffoldFiles(manifest, opts = {}) {
    const moduleName = (manifest['Module-Name'] || '').trim();
    const files = {};
    files['MANIFEST.MF'] = ManifestLib.stringifyManifest(Scaffold.canonicalizeManifest(manifest));
    files['build.gradle'] = Scaffold.renderBuildGradle(moduleName, { extraDeps: opts.extraDeps || [] });
    const entry = (manifest['Module-Entry'] || '').trim();
    if (entry) {
        const src = Scaffold.renderEntryClass({
            moduleEntry: entry,
            packId: opts.packId || moduleName.replace(/-/g, '_'),
            displayName: opts.displayName || moduleName,
            version: manifest['Module-Version'] || '1.0.0',
            entityIds: opts.entityIds || [],
        });
        if (src) {
            const loc = Scaffold.entryLocation(entry);
            files[loc.path] = src;
        }
    }
    return files;
}

const PackPreflightAPI = { preflightPack, scanDuplicateModuleName, scaffoldFiles };

if (typeof module === 'object' && module.exports) module.exports = PackPreflightAPI;
