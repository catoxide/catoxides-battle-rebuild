// preloadKeys.test.js - 防回归：preload api 对象键名冲突
// 背景（10/09 事故）：新增 listPacks/readTexture 与既有键重名，
// 对象字面量后者覆盖前者 → 主窗口包列表/贴图读取拿到错误 handler 而静默失败。
'use strict';
const fs = require('fs');
const path = require('path');
const assert = require('assert');

let passed = 0, failed = 0;
function t(name, fn) {
    try { fn(); console.log('  ✓ ' + name); passed++; }
    catch (e) { console.error('  ✗ ' + name + ': ' + e.message); failed++; }
}

const src = fs.readFileSync(path.join(__dirname, '..', 'preload.js'), 'utf8');
// api 对象内的键（4 空格缩进的 `key:` 行）
const re = /^\s{4}([A-Za-z_$][\w$]*)\s*:/gm;
const keys = [];
let m;
while ((m = re.exec(src))) keys.push(m[1]);

t('api 对象有键', () => {
    assert.ok(keys.length > 20, '应至少 20 个键，实际 ' + keys.length);
});

t('无重复键（重复 = 后者覆盖前者 = 静默功能错乱）', () => {
    const seen = {};
    for (const k of keys) seen[k] = (seen[k] || 0) + 1;
    const dups = Object.entries(seen).filter(([, n]) => n > 1).map(([k]) => k);
    assert.deepStrictEqual(dups, [], '重复键: ' + dups.join(', '));
});

console.log(`\n=== preloadKeys 测试: ${passed} passed, ${failed} failed（api 键总数 ${keys.length}）===`);
process.exit(failed ? 1 : 0);
