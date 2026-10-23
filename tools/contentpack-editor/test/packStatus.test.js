// packStatus.test.js - 四态核对与 on/off 搬运的离线测试（node 直接运行，不碰真实工程）
//
// 为什么值得单独测：这一层全是「读盘 → 比对 → 搬运」，出错的方式恰好就是 10/24 事故的方式
// （jar 没跟上源码、运行目录留旧副本、缓存与包内容分家）。用临时目录 + 自制 store-only zip
// 把四种状态全部摆出来，才能在改动前确认判定不会说谎。
'use strict';
const assert = require('assert');
const path = require('path');
const fs = require('fs');
const os = require('os');
const zlib = require('zlib');

const PS = require('../src/core/packStatus');
const ManifestLib = require('../src/core/manifest');
const JarReader = require('../src/core/jarReader');

let passed = 0, failed = 0;
function test(name, fn) {
    try { fn(); passed++; console.log(`  ✓ ${name}`); }
    catch (e) { failed++; console.log(`  ✗ ${name}\n    ${e.stack ? e.stack.split('\n').slice(0, 3).join('\n    ') : e.message}`); }
}

// ---------- 最小 zip 写入器（store + deflate 都支持，JarReader 两种都读） ----------
const CRC_TABLE = (() => {
    const t = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
        t[n] = c;
    }
    return t;
})();
function crc32(buf) {
    let c = 0xFFFFFFFF;
    for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
    return (c ^ 0xFFFFFFFF) >>> 0;
}
function makeZip(entries, { deflate = false } = {}) {
    const locals = [];
    const cen = [];
    let off = 0;
    for (const e of entries) {
        const nameBuf = Buffer.from(e.name, 'utf8');
        const plain = Buffer.isBuffer(e.data) ? e.data : Buffer.from(String(e.data), 'utf8');
        const data = deflate ? zlib.deflateRawSync(plain) : plain;
        const crc = crc32(plain);
        const lh = Buffer.alloc(30);
        lh.writeUInt32LE(0x04034b50, 0);
        lh.writeUInt16LE(20, 4);
        lh.writeUInt16LE(0, 6);                    // flags
        lh.writeUInt16LE(deflate ? 8 : 0, 8);      // method
        lh.writeUInt32LE(crc, 14);
        lh.writeUInt32LE(data.length, 18);
        lh.writeUInt32LE(plain.length, 22);
        lh.writeUInt16LE(nameBuf.length, 26);
        lh.writeUInt16LE(0, 28);
        locals.push(lh, nameBuf, data);
        const ch = Buffer.alloc(46);
        ch.writeUInt32LE(0x02014b50, 0);
        ch.writeUInt16LE(20, 4);
        ch.writeUInt16LE(20, 6);
        ch.writeUInt16LE(deflate ? 8 : 0, 10);
        ch.writeUInt32LE(crc, 16);
        ch.writeUInt32LE(data.length, 20);
        ch.writeUInt32LE(plain.length, 24);
        ch.writeUInt16LE(nameBuf.length, 28);
        ch.writeUInt32LE(off, 42);
        cen.push(Buffer.concat([ch, nameBuf]));
        off += 30 + nameBuf.length + data.length;
    }
    const cenBuf = Buffer.concat(cen);
    const eocd = Buffer.alloc(22);
    eocd.writeUInt32LE(0x06054b50, 0);
    eocd.writeUInt16LE(entries.length, 8);
    eocd.writeUInt16LE(entries.length, 10);
    eocd.writeUInt32LE(cenBuf.length, 12);
    eocd.writeUInt32LE(off, 16);
    return Buffer.concat([...locals, cenBuf, eocd]);
}

function manifestEntry(attrs) {
    return { name: 'META-INF/MANIFEST.MF', data: Object.entries(attrs).map(([k, v]) => `${k}: ${v}`).join('\r\n') + '\r\n\r\n' };
}

// ---------- 临时工程 ----------
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cpe-packstatus-'));
const ROOT = tmp;
const SRC = path.join(ROOT, 'contentpacks-source');
const OUT = path.join(ROOT, 'contentpacks');
const CLIENT = path.join(ROOT, 'run', 'client');
const SERVER = path.join(ROOT, 'run', 'server');
const ctx = { root: ROOT, packsSourceDir: SRC, packsOutDir: OUT };

const PKG = 'alpha-pack';
const NS = 'alphapack';

function buildPackDir() {
    const d = path.join(SRC, PKG);
    fs.mkdirSync(path.join(d, 'entities'), { recursive: true });
    fs.mkdirSync(path.join(d, 'spark_models', 'entity', NS), { recursive: true });
    ManifestLib.writeManifest(d, {
        'Module-Name': PKG, 'Module-Version': '0.1.0', 'Module-Type': 'contentpack',
    });
    fs.writeFileSync(path.join(d, 'entities', 'alpha_mob.json'), '{"id":"alpha_mob"}');
    fs.writeFileSync(path.join(d, 'spark_models', 'entity', NS, 'alpha_mob.json'), '{"boxes":1}');
    return d;
}

/** 按包目录现状做一个「应当正确」的 jar */
function buildJarFromSource({ extraModelText = '', anim = false } = {}) {
    const d = path.join(SRC, PKG);
    const entries = [manifestEntry({
        'Module-Name': PKG, 'Module-Version': '0.1.0', 'Module-Type': 'contentpack',
    })];
    const walk = (abs, rel) => {
        for (const e of fs.readdirSync(abs, { withFileTypes: true })) {
            const childAbs = path.join(abs, e.name);
            const childRel = rel ? `${rel}/${e.name}` : e.name;
            if (e.isDirectory()) { walk(childAbs, childRel); continue; }
            if (['MANIFEST.MF', 'build.gradle'].includes(e.name)) continue;
            let text = fs.readFileSync(childAbs, 'utf8');
            if (childRel.endsWith('alpha_mob.json') && childRel.startsWith('spark_models/')) text += extraModelText;
            entries.push({ name: childRel, data: text });
        }
    };
    walk(d, '');
    if (anim) {
        entries.push({ name: `spark_animations/entity/${NS}/alpha_mob/idle.json`, data: '{"tracks":0}' });
    }
    fs.mkdirSync(OUT, { recursive: true });
    fs.writeFileSync(path.join(OUT, `${PKG}.jar`), makeZip(entries));
}

function installToRunDirs(md5of) {
    for (const dir of [path.join(CLIENT, 'contentpacks'), path.join(SERVER, 'contentpacks')]) {
        fs.mkdirSync(dir, { recursive: true });
        fs.copyFileSync(path.join(OUT, `${PKG}.jar`), path.join(dir, `${PKG}.jar`));
    }
}

/** 写 spark 磁盘缓存里本包的那份模型（可指定内容与包内不同 = 陈旧） */
function writeSparkCache(text, { side = CLIENT } = {}) {
    const f = path.join(side, 'spark_modules', 'catoxidesbattlerebuild', 'models', NS, 'entity', 'alpha_mob.json');
    fs.mkdirSync(path.dirname(f), { recursive: true });
    fs.writeFileSync(f, text);
    return f;
}

console.log('=== 四态核对 / on-off 测试 ===');

buildPackDir();
buildJarFromSource();
installToRunDirs();

test('1. 全绿时不报错（源码=jar=安装副本，且缓存与包一致）', () => {
    writeSparkCache('{"boxes":1}');
    const r = PS.inspectAll(ctx);
    const p = r.packs.find((x) => x.dirName === PKG);
    assert.ok(p, '包应出现在四态表里');
    assert.strictEqual(p.built.exists, true);
    assert.strictEqual(p.enabled, true);
    assert.deepStrictEqual(p.diff.missingInJar, []);
    assert.deepStrictEqual(p.diff.stale, []);
    assert.strictEqual(p.installed.filter((m) => m.exists && m.matchesBuilt).length, 2, 'client+server 两份都该在');
    assert.strictEqual(p.cache.find((c) => c.label === 'run/client').stale, 0);
    assert.deepStrictEqual(p.issues.filter((i) => i.level !== 'info').map((i) => i.code), [], JSON.stringify(p.issues, null, 1));
});

test('2. 源码改了没重建 → E-SRC-JAR-DRIFT（10/24 C 类缺陷的当场形态）', () => {
    fs.writeFileSync(path.join(SRC, PKG, 'entities', 'alpha_mob.json'), '{"id":"alpha_mob","hp":99}');
    const p = PS.inspectAll(ctx).packs.find((x) => x.dirName === PKG);
    assert.ok(p.issues.some((i) => i.code === 'E-SRC-JAR-DRIFT'), JSON.stringify(p.issues));
    assert.ok(p.drift.sourceNewerThanJar, 'mtime 也该判出源码更新');
    // 还原，避免污染后续用例
    fs.writeFileSync(path.join(SRC, PKG, 'entities', 'alpha_mob.json'), '{"id":"alpha_mob"}');
});

test('3. 新增文件未进 jar → E-SRC-NOT-IN-JAR', () => {
    fs.mkdirSync(path.join(SRC, PKG, 'assets', NS, 'textures', 'entity'), { recursive: true });
    fs.writeFileSync(path.join(SRC, PKG, 'assets', NS, 'textures', 'entity', 'alpha.png'), 'PNGFAKE');
    let p = PS.inspectAll(ctx).packs.find((x) => x.dirName === PKG);
    assert.ok(p.issues.some((i) => i.code === 'E-SRC-NOT-IN-JAR'), JSON.stringify(p.issues));
    buildJarFromSource();          // 重建后应消失
    installToRunDirs();
    p = PS.inspectAll(ctx).packs.find((x) => x.dirName === PKG);
    assert.ok(!p.issues.some((i) => i.code === 'E-SRC-NOT-IN-JAR'), '重建后不该再报缺文件');
});

test('4. 运行目录留旧副本 → E-INSTALL-DRIFT（逐侧点名）', () => {
    fs.writeFileSync(path.join(SERVER, 'contentpacks', `${PKG}.jar`), 'STALE-JAR');
    const p = PS.inspectAll(ctx).packs.find((x) => x.dirName === PKG);
    const drift = p.issues.filter((i) => i.code === 'E-INSTALL-DRIFT');
    assert.strictEqual(drift.length, 1, JSON.stringify(p.issues));
    assert.ok(drift[0].msg.includes('run/server/contentpacks'), drift[0].msg);
    installToRunDirs();
});

test('5. spark 缓存与包内容分家 → E-CACHE-STALE，一致时不报', () => {
    writeSparkCache('{"boxes":2}');   // 与 jar 里的 {"boxes":1} 不同
    let p = PS.inspectAll(ctx).packs.find((x) => x.dirName === PKG);
    assert.ok(p.issues.some((i) => i.code === 'E-CACHE-STALE'), JSON.stringify(p.issues));
    writeSparkCache('{"boxes":1}');
    p = PS.inspectAll(ctx).packs.find((x) => x.dirName === PKG);
    assert.ok(!p.issues.some((i) => i.code === 'E-CACHE-STALE'), '缓存一致时不该报陈旧');
});

test('6. 缓存路径换算与 ContentPackLoader.bridgeSparkResources 一致', () => {
    const t = PS.sparkTargetsFromEntries([
        `spark_models/entity/${NS}/alpha_mob.json`,
        `spark_animations/entity/${NS}/alpha_mob/idle.json`,
        `spark_models/entity/${NS}/deep/nested/alpha_mob.json`, // 加载器取最后一段 → 压平
        'entities/alpha_mob.json',
    ]);
    assert.deepStrictEqual(t.models.map((m) => m.target), [`${NS}/entity/alpha_mob.json`, `${NS}/entity/alpha_mob.json`]);
    assert.deepStrictEqual(t.animations.map((m) => m.target), [`${NS}/entity/alpha_mob/idle.json`]);
});

test('7. 停用 = jar 进 disabled/ + 删安装副本 + 落标记（loader 只扫顶层 *.jar）', () => {
    const r = PS.setEnabled(ctx, `${PKG}.jar`, false);
    assert.strictEqual(r.ok, true, JSON.stringify(r.actions));
    assert.strictEqual(fs.existsSync(path.join(OUT, `${PKG}.jar`)), false);
    assert.strictEqual(fs.existsSync(path.join(OUT, 'disabled', `${PKG}.jar`)), true);
    assert.strictEqual(fs.existsSync(path.join(CLIENT, 'contentpacks', `${PKG}.jar`)), false);
    assert.strictEqual(fs.existsSync(path.join(SERVER, 'contentpacks', `${PKG}.jar`)), false);
    const st = PS.readDisabledState(ctx);
    assert.ok(st.disabled[`${PKG}.jar`], '应有停用标记');
    const p = PS.inspectAll(ctx).packs.find((x) => x.dirName === PKG);
    assert.strictEqual(p.enabled, false);
    assert.strictEqual(p.archived.exists, true);
    assert.ok(r.cacheWarning, '有 spark 资源的包必须给出缓存提醒');
});

test('8. 停用期间重建产物 → E-DISABLED-BUT-BUILT（重建会悄悄把它打开）', () => {
    buildJarFromSource();
    const p = PS.inspectAll(ctx).packs.find((x) => x.dirName === PKG);
    assert.ok(p.issues.some((i) => i.code === 'E-DISABLED-BUT-BUILT'), JSON.stringify(p.issues));
    const r = PS.rearchiveByMarker(ctx, `${PKG}.jar`);
    assert.strictEqual(r.ok, true);
    assert.strictEqual(fs.existsSync(path.join(OUT, `${PKG}.jar`)), false, '重新归档后产物目录不该有 jar');
});

test('9. 启用 = 归档迁回 + 自动补装各运行目录', () => {
    const r = PS.setEnabled(ctx, `${PKG}.jar`, true);
    assert.strictEqual(r.ok, true, JSON.stringify(r.actions));
    assert.strictEqual(fs.existsSync(path.join(OUT, `${PKG}.jar`)), true);
    assert.strictEqual(fs.existsSync(path.join(OUT, 'disabled', `${PKG}.jar`)), false);
    assert.strictEqual(fs.existsSync(path.join(CLIENT, 'contentpacks', `${PKG}.jar`)), true);
    assert.strictEqual(fs.existsSync(path.join(SERVER, 'contentpacks', `${PKG}.jar`)), true);
    assert.strictEqual(PS.readDisabledState(ctx).disabled[`${PKG}.jar`], undefined);
});

test('10. 产物比归档旧时启用 → 用停用那刻的版本，旧产物让位为 STALE-*（不删任何 jar）', () => {
    PS.setEnabled(ctx, `${PKG}.jar`, false);
    // 造一个「更早」的产物（模拟：停用时留的归档才是好的）
    const old = makeZip([manifestEntry({ 'Module-Name': PKG, 'Module-Type': 'contentpack' }), { name: 'entities/alpha_mob.json', data: '{"id":"alpha_mob","hp":1}' }]);
    fs.writeFileSync(path.join(OUT, `${PKG}.jar`), old);
    const t = Date.now() / 1000 - 3600;
    fs.utimesSync(path.join(OUT, `${PKG}.jar`), t, t);
    const r = PS.setEnabled(ctx, `${PKG}.jar`, true);
    assert.strictEqual(r.ok, true, JSON.stringify(r.actions));
    const promoted = JarReader.readEntry(path.join(OUT, `${PKG}.jar`), 'entities/alpha_mob.json').toString('utf8');
    assert.strictEqual(promoted, '{"id":"alpha_mob"}', '启用的应是停用那刻的归档内容');
    const parked = fs.readdirSync(path.join(OUT, 'disabled')).filter((n) => n.startsWith('STALE-'));
    assert.strictEqual(parked.length, 1, '旧产物该留档：' + fs.readdirSync(path.join(OUT, 'disabled')).join(','));
    const parkedText = JarReader.readEntry(path.join(OUT, 'disabled', parked[0]), 'entities/alpha_mob.json').toString('utf8');
    assert.strictEqual(parkedText, '{"id":"alpha_mob","hp":1}', '留档的应是那份旧产物');
});

test('11. 清缓存只删「jar 确有且内容相同」的文件，内容不同的（可能被别包占用）不动', () => {
    installToRunDirs();
    const cacheFile = writeSparkCache('{"boxes":1}');
    const serverCache = writeSparkCache('{"boxes":DIFFERENT}', { side: SERVER });
    const r = PS.clearSparkCache(ctx, `${PKG}.jar`);
    assert.strictEqual(r.ok, true, r.error);
    assert.strictEqual(fs.existsSync(cacheFile), false, '一致的那份该删掉');
    assert.strictEqual(fs.existsSync(serverCache), true, '内容不同的一份该保留');
    assert.strictEqual(r.conflicts.length, 1, JSON.stringify(r.conflicts));
    assert.ok(r.note.includes('已删除'), r.note);
});

test('12. 安装动作：已一致则跳过，缺失则补写（不覆盖成旧内容）', () => {
    fs.rmSync(path.join(CLIENT, 'contentpacks', `${PKG}.jar`));
    const r = PS.installToRun(ctx, `${PKG}.jar`);
    assert.strictEqual(r.ok, true, JSON.stringify(r));
    assert.strictEqual(r.written.length, 1, JSON.stringify(r));
    assert.ok(r.skipped.some((s) => s.includes('md5 已一致')), JSON.stringify(r.skipped));
    const again = PS.installToRun(ctx, `${PKG}.jar`);
    assert.strictEqual(again.written.length, 0, '第二次不该再写');
});

test('13. 非法 jar 名一律拒绝（路径穿越 / shell 元字符）', () => {
    for (const bad of ['../evil.jar', 'a/..jar', 'x & calc.jar', '', 'noext', '中文.jar']) {
        assert.throws(() => PS.setEnabled(ctx, bad, false), /非法/, '应拒绝: ' + bad);
        assert.throws(() => PS.installToRun(ctx, bad), /非法/, '应拒绝: ' + bad);
        assert.throws(() => PS.clearSparkCache(ctx, bad), /非法/, '应拒绝: ' + bad);
    }
});

test('14. 非包目录（无 MANIFEST.MF）不进四态表，只单列', () => {
    fs.mkdirSync(path.join(SRC, 'editor'), { recursive: true });
    fs.writeFileSync(path.join(SRC, 'editor', 'index.html'), '<html></html>');
    const r = PS.inspectAll(ctx);
    assert.ok(!r.packs.some((p) => p.dirName === 'editor'), 'editor/ 不该当成包');
    assert.ok(r.nonPackDirs.some((d) => d.dirName === 'editor'), JSON.stringify(r.nonPackDirs));
});

test('15. 同 Module-Name 两份 jar → duplicates 点名（运行期只会留一份）', () => {
    installToRunDirs();
    const src = path.join(OUT, `${PKG}.jar`);
    fs.writeFileSync(path.join(OUT, 'alpha-pack-old.jar'),
        makeZip([manifestEntry({ 'Module-Name': PKG, 'Module-Type': 'contentpack' })]));
    const r = PS.inspectAll(ctx);
    const dup = r.duplicates.find(([, names]) => names.length > 1);
    assert.ok(dup, JSON.stringify(r.duplicates));
    assert.deepStrictEqual(dup[1].sort(), ['alpha-pack-old.jar', `${PKG}.jar`]);
    fs.rmSync(path.join(OUT, 'alpha-pack-old.jar'));
});

test('16. deflate 压缩的 jar 也能核对（Gradle 产物默认是 deflate）', () => {
    const d = path.join(SRC, PKG);
    const entries = [manifestEntry({ 'Module-Name': PKG, 'Module-Type': 'contentpack' }),
        { name: 'entities/alpha_mob.json', data: '{"id":"alpha_mob"}' },
        { name: `spark_models/entity/${NS}/alpha_mob.json`, data: '{"boxes":1}' }];
    fs.writeFileSync(path.join(OUT, `${PKG}.jar`), makeZip(entries, { deflate: true }));
    installToRunDirs();
    const p = PS.inspectAll(ctx).packs.find((x) => x.dirName === PKG);
    assert.strictEqual(p.built.entries, 3, '条目数应读得到: ' + p.built.entries);
    assert.strictEqual(p.diff.stale.length, 0, 'deflate 条目的 md5 比对要落在解压后的内容上');
});

fs.rmSync(tmp, { recursive: true, force: true });
console.log(`\n=== packStatus 测试: ${passed} passed, ${failed} failed ===`);
process.exit(failed ? 1 : 0);
