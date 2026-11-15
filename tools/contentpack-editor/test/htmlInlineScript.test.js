// htmlInlineScript.test.js - 防回归：主窗口 CSP 屏蔽内联脚本
// 背景（10/09 事故）：src/index.html 有 CSP `script-src 'self'`（无 'unsafe-inline'），
// 内联 <script> 被静默屏蔽——W3.6 错误捕获曾以内联脚本写入，主窗口的
// unhandledrejection 捕获从未生效（渲染端崩溃无任何日志）。
// 规则：src/index.html 不得出现内联 <script> 块（逻辑一律放外部文件）。
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
// 所有编辑器窗口共用同一 CSP（script-src 'self'，无 'unsafe-inline'）：内联脚本一律静默屏蔽。
// 新增窗口必须登记到这里，否则它的内联脚本回归抓不到（texture-editor 接入时补全）。
const HTML_FILES = [
    'src/index.html',
    'src/piece-editor.html',
    'model-editor/index.html',
    'texture-editor/index.html',
];

for (const rel of HTML_FILES) {
    t(`${rel} 无内联 <script> 块（CSP 会静默屏蔽）`, () => {
        const file = path.join(ROOT, rel);
        assert.ok(fs.existsSync(file), `${rel} 应存在`);
        const html = fs.readFileSync(file, 'utf8');
        // 匹配 <script> 或带属性但不带 src 的 <script ...>
        const inline = html.match(/<script(?![^>]*\bsrc=)[^>]*>/gi) || [];
        assert.deepStrictEqual(inline, [], '发现内联脚本: ' + inline.join(', '));
    });
}

t('错误捕获经外部文件加载（各窗口都引用 errorCapture.js）', () => {
    assert.ok(fs.existsSync(path.join(ROOT, 'src', 'lib', 'errorCapture.js')), 'errorCapture.js 应存在');
    for (const rel of HTML_FILES) {
        const html = fs.readFileSync(path.join(ROOT, rel), 'utf8');
        assert.ok(html.includes('errorCapture.js'), `${rel} 应引用 lib/errorCapture.js`);
    }
});

console.log(`\n=== htmlInlineScript 测试: ${passed} passed, ${failed} failed ===`);
process.exit(failed ? 1 : 0);
