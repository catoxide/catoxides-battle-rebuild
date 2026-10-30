// i18nCoverage.test.js - 防回归：界面引用的每个 i18n 键，中英两份都得有
// 背景：src/i18n.js 的 DICT 是手工维护的两份平行字典（zh / en）。漏一份不会报错，
// 只会在切语言后把键名（'app.packBuild'）直接显示在按钮上 —— 而按钮文案是用户唯一
// 能看见的东西，露键名等于该功能"看起来坏了"。gitHistoryView.test.js 已经为回溯视图
// 单独查过一次，这里把范围扩到整个 src/。
//
// 检查三件事：
//   1. 被引用的键（index.html / piece-editor.html 的 data-i18n*，src/**/*.js 的 t()/tf()/T()/tt() 字面量）
//      在 zh 与 en 里都存在 —— 缺失时报出 file:line。
//   2. 每份字典内部没有重复键（后写的会静默覆盖前写的，改文案时最容易踩）。
//   3. zh 与 en 的键集合完全相同（含"定义了但没人用"的键）—— 两份漂移就是下一次的露键名。
//
// 扫不到的情况（有意不扫）：t(变量) 与 t(`前缀${x}`) 这类动态键。这类调用点必须自己
// 保证每个取值都有两份文案；本测试只覆盖静态字面量。
'use strict';
const fs = require('fs');
const path = require('path');
const assert = require('assert');

let passed = 0, failed = 0;
function t(name, fn) {
    try { fn(); console.log('  ✓ ' + name); passed++; }
    catch (e) { console.error('  ✗ ' + name + ': ' + e.message); failed++; }
}

const ROOT = path.join(__dirname, '..');
const SRC = path.join(ROOT, 'src');
const I18N_FILE = path.join(SRC, 'i18n.js');
const HTML_FILES = ['index.html', 'piece-editor.html'].map((f) => path.join(SRC, f));
const rel = (p) => path.relative(ROOT, p).split(path.sep).join('/');

// ===== 解析 DICT 的两份键表 =====
// 不按 JS 求值（i18n.js 挂 window，require 不了），只按行抓 `'key':` —— 字典是纯字面量表。
function parseDict() {
    const src = fs.readFileSync(I18N_FILE, 'utf8');
    const zhAt = src.indexOf('zh: {');
    const enAt = src.indexOf('en: {', zhAt + 1);
    assert.ok(zhAt > 0 && enAt > zhAt, 'src/i18n.js 里找不到 zh: { / en: { 两段字典');
    const grab = (text) => (text.match(/^[ \t]*'([^']+)':/gm) || [])
        .map((line) => line.trim().slice(1, line.trim().indexOf("':")));
    return { zh: grab(src.slice(zhAt, enAt)), en: grab(src.slice(enAt)) };
}

// ===== 收集被引用的键（记住第一处 file:line 便于定位）=====
function collectUsed() {
    const used = new Map();
    const add = (key, loc) => { if (!used.has(key)) used.set(key, loc); };

    for (const file of HTML_FILES) {
        if (!fs.existsSync(file)) continue;
        fs.readFileSync(file, 'utf8').split(/\r?\n/).forEach((line, i) => {
            for (const m of line.matchAll(/data-i18n(?:-title|-ph)?="([^"]+)"/g)) {
                add(m[1], `${rel(file)}:${i + 1}`);
            }
        });
    }

    // t/tf/T/tt 是各视图对 window.I18n.t 的本地别名（views 顶部 const t = window.I18n.t）。
    // 要求引号后紧跟 , 或 ) —— 排除 t('a' + b) 这类拼接键（拼出来的键扫不到，见文件头）。
    const CALL = /\b(?:t|tf|T|tt)\(\s*'([^']+)'\s*[,)]/g;
    (function walk(dir) {
        for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
            const p = path.join(dir, e.name);
            if (e.isDirectory()) { walk(p); continue; }
            if (!e.name.endsWith('.js') || p === I18N_FILE) continue;
            fs.readFileSync(p, 'utf8').split(/\r?\n/).forEach((line, i) => {
                for (const m of line.matchAll(CALL)) add(m[1], `${rel(p)}:${i + 1}`);
            });
        }
    })(SRC);

    return used;
}

const dict = parseDict();
const used = collectUsed();
const zhSet = new Set(dict.zh);
const enSet = new Set(dict.en);

const fmt = (list) => list.map(([k, loc]) => `    ${k}  ← ${loc}`).join('\n');

t(`字典解析：zh ${dict.zh.length} 键 / en ${dict.en.length} 键，引用点 ${used.size} 键`, () => {
    assert.ok(dict.zh.length > 100, 'zh 字典键数异常少，解析规则可能失效');
    assert.ok(dict.en.length > 100, 'en 字典键数异常少，解析规则可能失效');
    assert.ok(used.size > 100, '引用键数异常少，扫描规则可能失效');
});

t('被引用的键在 zh 字典里都存在（缺失 = 中文界面露键名）', () => {
    const miss = [...used].filter(([k]) => !zhSet.has(k));
    assert.deepStrictEqual(miss.map(([k]) => k), [], '缺少中文文案:\n' + fmt(miss));
});

t('被引用的键在 en 字典里都存在（缺失 = 英文界面露键名）', () => {
    const miss = [...used].filter(([k]) => !enSet.has(k));
    assert.deepStrictEqual(miss.map(([k]) => k), [], '缺少英文文案:\n' + fmt(miss));
});

t('每份字典内部无重复键（重复 = 后写的静默覆盖前写的）', () => {
    const dups = (arr) => arr.filter((k, i) => arr.indexOf(k) !== i);
    assert.deepStrictEqual(dups(dict.zh), [], 'zh 有重复键');
    assert.deepStrictEqual(dups(dict.en), [], 'en 有重复键');
});

t('zh 与 en 键集合完全一致（单侧多出来的键迟早变成露键名）', () => {
    const zhOnly = dict.zh.filter((k) => !enSet.has(k));
    const enOnly = dict.en.filter((k) => !zhSet.has(k));
    assert.deepStrictEqual(zhOnly, [], '只在 zh 里有: ' + zhOnly.join(', '));
    assert.deepStrictEqual(enOnly, [], '只在 en 里有: ' + enOnly.join(', '));
});

// 信息性输出（不判失败）：定义了但没人引用的键。删不删由人决定 —— 有些键是给
// 动态拼接用的，本测试扫不到，所以这里只报数字与样例，不当成债务门禁。
const unused = dict.zh.filter((k) => !used.has(k));
if (unused.length) {
    console.log(`  · 参考：字典里有 ${unused.length} 个键未被静态引用（可能是动态拼接或已废弃）：`
        + unused.slice(0, 8).join(', ') + (unused.length > 8 ? ' …' : ''));
}

console.log(`\n=== i18nCoverage 测试: ${passed} passed, ${failed} failed ===`);
process.exit(failed ? 1 : 0);
