// noBrowserPrompt.test.js - 防回归：Electron 不支持 window.prompt()（抛 "prompt() is not supported"）
// 背景（10/09 真实 exe 日志）：模型编辑器 ✨新建 用三连 prompt()，真实应用里全部抛异常 → 功能不可用。
// 规则：应用代码（src/、model-editor/，lib/ 除外）不得调用 prompt(；交互一律走 askModal（页面内模态框）。
// 注：alert()/confirm() 在 Electron 有原生实现，暂不禁止（但新代码建议也避免）。
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
const TARGETS = [path.join(ROOT, 'src'), path.join(ROOT, 'model-editor')];
const SKIP = /[/\\]lib[/\\]/;

function walk(dir, out = []) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, e.name);
        if (e.isDirectory()) { if (e.name !== 'node_modules' && !SKIP.test(p)) walk(p, out); }
        else if (e.name.endsWith('.js')) out.push(p);
    }
    return out;
}

t('应用代码无 prompt() 调用（Electron 抛异常）', () => {
    const hits = [];
    for (const dir of TARGETS) {
        for (const f of walk(dir)) {
            const lines = fs.readFileSync(f, 'utf8').split('\n');
            lines.forEach((ln, i) => {
                // 排除注释行
                const code = ln.replace(/\/\/.*$/, '');
                if (/\bprompt\s*\(/.test(code)) hits.push(`${path.relative(ROOT, f)}:${i + 1}: ${ln.trim()}`);
            });
        }
    }
    assert.deepStrictEqual(hits, [], '发现 prompt() 调用:\n' + hits.join('\n'));
});

t('askModal.js 存在且被两个窗口引用', () => {
    assert.ok(fs.existsSync(path.join(ROOT, 'src', 'lib', 'askModal.js')));
    const main = fs.readFileSync(path.join(ROOT, 'src', 'index.html'), 'utf8');
    const me = fs.readFileSync(path.join(ROOT, 'model-editor', 'index.html'), 'utf8');
    assert.ok(main.includes('askModal.js'), 'src/index.html 应引用 askModal.js');
    assert.ok(me.includes('askModal.js'), 'model-editor/index.html 应引用 askModal.js');
});

console.log(`\n=== noBrowserPrompt 测试: ${passed} passed, ${failed} failed ===`);
process.exit(failed ? 1 : 0);
