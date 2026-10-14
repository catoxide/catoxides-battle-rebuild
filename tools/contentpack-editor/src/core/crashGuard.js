// crashGuard.js - W5.3 renderer 崩溃窗口计数（纯逻辑，node 可测）
//
// 背景：部分机器（GPU 驱动问题）renderer 会成组崩溃（同一毫秒多窗口齐崩）。
// W4.8 的逐窗口自动重载能救单次崩溃，但驱动级故障靠重载治不了——
// 连续崩溃只会反复闪窗。规则：`windowMs`（默认 10 分钟）内出现
// `threshold`（默认 2）次 crashed/oom 型崩溃 → 返回 true，由调用方
// 降级到软件渲染（--disable-gpu，WebGL 走 SwiftShader，彻底绕开 GPU 驱动）。
'use strict';

function makeCrashTracker(opts) {
    const o = opts || {};
    const nowFn = typeof o.now === 'function' ? o.now : () => Date.now();
    const windowMs = o.windowMs || 600000; // 10 分钟滑窗
    const threshold = o.threshold || 2;
    const times = [];
    return {
        /** 记录一次 render-process-gone；达到降级阈值返回 true */
        record(reason) {
            // killed（被 OS/用户杀）/ clean（正常退出）不算故障
            if (reason !== 'crashed' && reason !== 'oom') return false;
            const t = nowFn();
            while (times.length && t - times[0] > windowMs) times.shift();
            times.push(t);
            return times.length >= threshold;
        },
        /** 清空计数（如确认已降级后） */
        reset() { times.length = 0; },
        size() { return times.length; },
    };
}

module.exports = { makeCrashTracker };
