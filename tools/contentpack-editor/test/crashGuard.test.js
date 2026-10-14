// crashGuard.test.js - W5.3 renderer 崩溃窗口计数
'use strict';
const assert = require('assert');
const { makeCrashTracker } = require('../src/core/crashGuard');

let clock = 0;
function tracker(opts) {
    return makeCrashTracker(Object.assign({ now: () => clock }, opts));
}

// ① 10 分钟内 2 次 crashed → 第 2 次触发
clock = 0;
let t = tracker();
assert.strictEqual(t.record('crashed'), false, '第 1 次崩溃不应触发');
clock = 60000; // +1 分钟
assert.strictEqual(t.record('crashed'), true, '第 2 次崩溃应触发');

// ② killed / clean 不算故障
clock = 0;
t = tracker();
assert.strictEqual(t.record('killed'), false, 'killed 不计');
assert.strictEqual(t.record('clean'), false, 'clean 不计');
assert.strictEqual(t.record('crashed'), false, '只有 1 次真实崩溃');
assert.strictEqual(t.record('crashed'), true, '第 2 次真实崩溃触发');

// ③ 滑窗过期：10 分钟前的记录不再计数
clock = 0;
t = tracker();
t.record('crashed');
clock = 600001; // 恰好超出 10 分钟
assert.strictEqual(t.record('crashed'), false, '旧记录已过期，重新算第 1 次');
assert.strictEqual(t.record('crashed'), true, '窗口内 2 次 → 触发');

// ④ oom 也算故障
clock = 0;
t = tracker();
assert.strictEqual(t.record('oom'), false);
assert.strictEqual(t.record('crashed'), true, 'oom + crashed → 触发');

// ⑤ 自定义阈值
clock = 0;
t = tracker({ threshold: 3 });
t.record('crashed');
t.record('crashed');
assert.strictEqual(t.record('crashed'), true, 'threshold=3 → 第 3 次触发');

// ⑥ reset 清空
clock = 0;
t = tracker();
t.record('crashed');
t.reset();
assert.strictEqual(t.size(), 0, 'reset 后计数清零');
assert.strictEqual(t.record('crashed'), false, 'reset 后重新计数');

console.log('crashGuard: all tests passed');
