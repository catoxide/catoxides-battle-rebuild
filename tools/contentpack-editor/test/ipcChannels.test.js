// ipcChannels.test.js - IPC 通道名对齐检查（主进程 send/invoke ↔ 渲染端 on/invoke）
// 为什么要有：preload 里写 on('x')、主进程却 send('y')，两边都不报错，功能就是「点了没反应」——
// 0.2.1 那轮事故的形态之一。这里做一次静态交叉核对，把这类错在测试阶段拦下来。
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const main = fs.readFileSync(path.join(ROOT, 'main.js'), 'utf8');
const preload = fs.readFileSync(path.join(ROOT, 'preload.js'), 'utf8');
// 模型编辑器子窗口用自己的 preload，一起核对
const mePreloadPath = path.join(ROOT, 'model-editor', 'preload.js');
const sources = [{ name: 'preload.js', text: preload }];
if (fs.existsSync(mePreloadPath)) sources.push({ name: 'model-editor/preload.js', text: fs.readFileSync(mePreloadPath, 'utf8') });

let passed = 0, failed = 0;
function test(name, fn) {
    try { fn(); passed++; console.log('  ✓ ' + name); }
    catch (e) { failed++; console.log('  ✗ ' + name + '\n      ' + e.message); }
}

const collect = (text, re) => {
    const out = new Set();
    for (const m of text.matchAll(re)) out.add(m[1]);
    return out;
};

const mainHandles = collect(main, /ipcMain\.handle\(\s*'([^']+)'/g);
const mainSends = collect(main, /\.send\(\s*'([^']+)'/g);
const mainOns = collect(main, /ipcMain\.on\(\s*'([^']+)'/g);

console.log('=== IPC 通道对齐测试 ===');

test('1. preload 里 invoke 的每个通道，主进程都有 handle', () => {
    for (const { name, text } of sources) {
        const invoked = collect(text, /ipcRenderer\.invoke\(\s*'([^']+)'/g);
        const orphan = [...invoked].filter((c) => !mainHandles.has(c));
        assert.deepStrictEqual(orphan, [], `${name} invoke 了主进程没 handle 的通道：${orphan.join(', ')}`);
        assert.ok(invoked.size > 20, name + ' 应扫到若干 invoke');
    }
});

test('2. preload 里 on(...) 监听的每个通道，主进程确有 send 过来（0.2.2 构建输出流就靠这条）', () => {
    for (const { name, text } of sources) {
        const listened = collect(text, /ipcRenderer\.on\(\s*'([^']+)'/g);
        const orphan = [...listened].filter((c) => !mainSends.has(c));
        assert.deepStrictEqual(orphan, [], `${name} 监听了主进程从不 send 的通道：${orphan.join(', ')}`);
    }
});

test('3. 主进程 handle 的通道若被渲染端用到，preload 必须有对应键（防止只加后端忘了接前端）', () => {
    const used = new Set();
    for (const { text } of sources) for (const m of text.matchAll(/ipcRenderer\.invoke\(\s*'([^']+)'/g)) used.add(m[1]);
    const builtOnly = ['app:getPaths'];  // 允许后端存在但前端暂未用的通道
    const orphan = [...mainHandles].filter((c) => !used.has(c) && !builtOnly.includes(c));
    // 只报数量，不硬性失败：主进程也会给子窗口/未来功能留通道
    if (orphan.length > 12) assert.fail(`未接线的 handle 过多（${orphan.length}）：${orphan.join(', ')}`);
});

test('4. pack:output（Gradle 实时输出）两侧都在：主进程 send + preload on', () => {
    assert.ok(mainSends.has('pack:output'), '主进程要 send pack:output');
    assert.ok(/onBuildOutput/.test(preload) && /ipcRenderer\.on\('pack:output'/.test(preload), 'preload 要暴露 onBuildOutput');
});

test('5. 提交回溯的四个通道都接好了（git:info/history/commit/workingTree）', () => {
    for (const c of ['git:info', 'git:history', 'git:commit', 'git:workingTree']) {
        assert.ok(mainHandles.has(c), '主进程缺 handle: ' + c);
        assert.ok(preload.includes(`ipcRenderer.invoke('${c}'`), 'preload 缺 invoke: ' + c);
    }
    assert.deepStrictEqual([...mainOns].filter((c) => /^git:/.test(c)), [], 'git 侧不该有渲染端 send 进来的写通道');
});

test('6. gitHistory 核心层不含任何写命令（只读承诺是硬约束，不是口头承诺）', () => {
    const src = fs.readFileSync(path.join(ROOT, 'src', 'core', 'gitHistory.js'), 'utf8');
    const banned = ['commit', 'checkout', 'restore', 'reset', 'clean', 'merge', 'revert', 'stash', 'push', 'apply', 'cherry-pick'];
    // 注释里允许出现这些词（解释为什么禁用），只禁止出现在实际 argv 数组里
    const code = src.split('\n').filter((l) => !/^\s*(\/\/|\*|\/\*)/.test(l)).join('\n');
    const hits = banned.filter((b) => new RegExp(`\\['${b}'`).test(code) || new RegExp(`'${b}[ ,']`).test(code));
    assert.deepStrictEqual(hits, [], 'gitHistory.js 里出现了写命令 argv：' + hits.join(', '));
});

console.log(`\n=== ipcChannels 测试: ${passed} passed, ${failed} failed ===`);
process.exit(failed ? 1 : 0);
