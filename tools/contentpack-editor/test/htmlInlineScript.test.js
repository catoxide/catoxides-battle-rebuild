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

const html = fs.readFileSync(path.join(__dirname, '..', 'src', 'index.html'), 'utf8');

t('src/index.html 无内联 <script> 块（CSP 会静默屏蔽）', () => {
    // 匹配 <script> 或带属性但不带 src 的 <script ...>
    const inline = html.match(/<script(?![^>]*\bsrc=)[^>]*>/gi) || [];
    assert.deepStrictEqual(inline, [], '发现内联脚本: ' + inline.join(', '));
});

t('错误捕获经外部文件加载', () => {
    assert.ok(html.includes('errorCapture.js'), '应引用 lib/errorCapture.js');
    assert.ok(fs.existsSync(path.join(__dirname, '..', 'src', 'lib', 'errorCapture.js')), 'errorCapture.js 应存在');
});

console.log(`\n=== htmlInlineScript 测试: ${passed} passed, ${failed} failed ===`);
process.exit(failed ? 1 : 0);
