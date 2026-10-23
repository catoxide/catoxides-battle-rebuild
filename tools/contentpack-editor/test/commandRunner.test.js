// commandRunner.test.js - 子进程执行层（构建输出流的地基）
// 这里跑的是真实短命子进程（node 自己），不碰工程、不启 Gradle：
// 要验证的只有四件事 —— 输出捕获、实时回调、失败原文、以及「参数绝不经 shell」。
'use strict';
const assert = require('assert');
const path = require('path');
const { runCommand } = require('../src/core/commandRunner');

const NODE = process.execPath;
let passed = 0, failed = 0;
const CASES = [];
function test(name, fn) { CASES.push([name, fn]); }
async function runCases() {
    for (const [name, fn] of CASES) {
        try { await fn(); passed++; console.log('  ✓ ' + name); }
        catch (e) { failed++; console.log('  ✗ ' + name + '\n      ' + (e && e.message)); }
    }
}

console.log('=== commandRunner 测试 ===');

test('1. stdout 收全，且实时回调先于 resolve 到达（面板靠这个才有“还在跑”的证据）', async () => {
    const chunks = [];
    const order = [];
    const r = await runCommand(NODE, ['-e', 'console.log("line one"); console.log("line two");'], {
        onOutput: (kind, text) => { chunks.push([kind, text.trim()]); order.push('chunk'); },
    });
    order.push('resolved');
    assert.strictEqual(r.code, 0);
    assert.ok(r.stdout.includes('line one') && r.stdout.includes('line two'), JSON.stringify(r.stdout));
    assert.ok(chunks.length >= 1, '至少要推一次');
    assert.ok(chunks.every(([k]) => k === 'stdout'), '本例不该有 stderr：' + JSON.stringify(chunks));
    const streamed = chunks.map((c) => c[1]).join('');
    assert.ok(streamed.includes('line one') && streamed.includes('line two'), '推的片段拼起来要和收全的一致：' + JSON.stringify(chunks));
    assert.strictEqual(order[0], 'chunk', '回调必须在 close/resolve 之前');
});

test('2. stderr 也推，且 kind 分得清（Gradle 的报错走 stderr）', async () => {
    const seen = [];
    const r = await runCommand(NODE, ['-e', 'console.error("boom"); console.log("fine"); process.exit(0);'], {
        onOutput: (kind, text) => seen.push([kind, text.trim()]),
    });
    assert.strictEqual(r.stdout.trim(), 'fine');
    assert.strictEqual(r.stderr.trim(), 'boom');
    assert.ok(seen.some(([k, t]) => k === 'stderr' && t === 'boom'), JSON.stringify(seen));
    assert.ok(seen.some(([k, t]) => k === 'stdout' && t === 'fine'), JSON.stringify(seen));
});

test('3. 非 0 退出：reject 带完整原文，failPrefix 可定制（build-pack.ps1 用「打包失败」）', async () => {
    let err = null;
    try {
        await runCommand(NODE, ['-e', 'console.log("前半"); console.error("炸了"); process.exit(3);'], { failPrefix: '打包失败' });
    } catch (e) { err = e; }
    assert.ok(err, '应 reject');
    assert.ok(/打包失败（退出码 3）/.test(err.message), err.message);
    assert.ok(err.message.includes('前半') && err.message.includes('炸了'), 'stdout/stderr 都要在错误里');
});

test('4. 默认前缀保留旧文案「<cmd> 退出码 N」（Gradle 失败回退逻辑靠它判断）', async () => {
    let err = null;
    try { await runCommand(NODE, ['-e', 'process.exit(1)']); } catch (e) { err = e; }
    assert.ok(err && /退出码 1/.test(err.message), err && err.message);
});

test('5. 回调抛错不许把构建带崩：Gradle 正常结束，前端窗口关了也照样返回结果', async () => {
    const r = await runCommand(NODE, ['-e', 'console.log("still running")'], {
        onOutput: () => { throw new Error('渲染进程已销毁'); },
    });
    assert.strictEqual(r.stdout.trim(), 'still running');
});

test('6. 参数绝不经 shell：& | ^ > 之类保持字面量（REVIEW-S7 的实证用例）', async () => {
    const evil = ['a&b|c', '^calc', 'x > out.txt', '$HOME'];
    // node -e 模式下 process.argv = [exe, ...用户参数]（脚本本身不进 argv）
    const r = await runCommand(NODE, ['-e', 'console.log(JSON.stringify(process.argv.slice(1)))', ...evil]);
    assert.deepStrictEqual(JSON.parse(r.stdout.trim()), evil, '元字符必须原样到达子进程 argv');
});

test('7. 命令不存在：reject 原始 error（ENOENT），不静默返回空结果', async () => {
    let err = null;
    try { await runCommand('__cpe_not_a_real_binary__', ['-v']); } catch (e) { err = e; }
    assert.ok(err, '应 reject');
    assert.ok(/ENOENT|no such file|spawn/i.test(err.message), err.message);
});

test('8. cwd 透传（Gradle 必须在工程根跑）', async () => {
    const r = await runCommand(NODE, ['-e', 'console.log(process.cwd())'], { cwd: path.resolve(__dirname, '..') });
    assert.strictEqual(path.resolve(r.stdout.trim()), path.resolve(__dirname, '..'));
});

runCases().then(() => {
    console.log(`\n=== commandRunner 测试: ${passed} passed, ${failed} failed ===`);
    process.exit(failed ? 1 : 0);
});
