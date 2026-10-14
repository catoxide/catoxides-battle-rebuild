// tick.js - 时间换算：秒 ↔ tick ↔ 网格吸附 + 舍入
// 纯函数，node 可测。
//
// 背景（design §3.3 时间表示决策）：
//   内部一律存秒（float）；现网文件混有 20fps（0.05/0.875…）与 24fps（0.0417…）步长，
//   秒 float 全兼容。tick 与网格只服务于 UI 显示/吸附与导出舍入：
//   - TICK_SEC = 0.05（1 tick @ 20 tps，Minecraft 基准帧率）
//   - 导出统一四舍五入 4 位小数（Spark 关键帧时间的精度上限，现网已核）

// 双模式：node（require/module.exports）+ 浏览器经典 script（self.Tick）
(function (globalScope) {
const TICK_SEC = 0.05;

/** 秒 → tick（四舍五入到最近 tick）。 */
function toTicks(sec) {
    return Math.round(sec / TICK_SEC);
}

/** tick → 秒（整数 tick 精确值；非整数也返回）。 */
function toSec(ticks) {
    return round4(ticks * TICK_SEC);
}

/**
 * 四舍五入到 4 位小数（导出用）。
 * 注意浮点：先乘后除，结果可能带浮点尾数（如 0.1+0.2 类），
 * 用 toFixed(4) 截尾再 parseFloat 归一。
 */
function round4(x) {
    if (!Number.isFinite(x)) return x;
    return parseFloat(x.toFixed(4));
}

/**
 * 吸附到网格（默认 1 tick = 0.05s）。
 * @param {number} sec
 * @param {number} [gridSec] 网格步长（秒）
 */
function snapToGrid(sec, gridSec = TICK_SEC) {
    if (gridSec <= 0) return round4(sec);
    return round4(Math.round(sec / gridSec) * gridSec);
}

/** 秒 → 显示字符串（最多 4 位小数，去尾零：3 → "3"，0.875 → "0.875"，1/24 → "0.0417"）。 */
function formatTime(sec) {
    return String(round4(sec));
}

/** 显示字符串 → 秒（"0.875" → 0.875；非法 → NaN）。 */
function parseTime(s) {
    if (typeof s !== 'string') return NaN;
    const t = s.trim();
    if (!/^-?\d+(\.\d+)?$/.test(t)) return NaN;
    return parseFloat(t);
}

/**
 * 判断一个秒值是否"落在 24fps 网格上"（步长 1/24 ≈ 0.0417）。
 * 用于 UI 提示/吸附模式选择（现网 24fps 文件的还原诊断用）。
 */
function isOn24fpsGrid(sec) {
    const frames = Math.round(sec / (1 / 24));
    if (frames < 0) return false;
    return Math.abs(sec - frames / 24) < 5e-5; // 4 位小数舍入容差
}

// W7 时间系统（design/ANIMATION_TECH_STACK.md §2）：
// 引擎动画时钟 = 60Hz 物理帧（PhysicsLevel.TPS=60，step=speed/60 秒/帧，时间 1:1 真实时间）。
// 编辑器网格三档：60fps（默认）/ 20TPS / 24fps（兼容现网 wrist 系文件）。
const FPS_60 = 60;
const FPS_24 = 24;
const SEC_PER_FRAME_60 = 1 / 60; // ≈ 0.0167（导出按 round4 落 0.0167）
const FPS_GRIDS = [
    { fps: FPS_60, label: '60fps', gridSec: SEC_PER_FRAME_60 },
    { fps: 20, label: '20TPS', gridSec: TICK_SEC },
    { fps: FPS_24, label: '24fps', gridSec: 1 / 24 },
];

/** 网格步长（秒）for 指定 fps；fps 非法 → null。 */
function frameGridSec(fps) {
    if (!Number.isFinite(fps) || fps <= 0) return null;
    return 1 / fps;
}

/** 秒 → 指定 fps 的帧序号（四舍五入）。 */
function toFrames(sec, fps) {
    const g = frameGridSec(fps);
    if (!g) return NaN;
    return Math.round(sec / g);
}

/** 显示 "0.8333s (50f@60)"（fps 缺省 60）。 */
function formatTimeAt(sec, fps = FPS_60) {
    const f = toFrames(sec, fps);
    return `${formatTime(sec)}s (${f}f@${fps})`;
}

const TickAPI = {
    TICK_SEC,
    toTicks,
    toSec,
    round4,
    snapToGrid,
    formatTime,
    parseTime,
    isOn24fpsGrid,
    FPS_60,
    FPS_24,
    SEC_PER_FRAME_60,
    FPS_GRIDS,
    frameGridSec,
    toFrames,
    formatTimeAt,
};
if (typeof module === 'object' && module.exports) module.exports = TickAPI;
if (globalScope) globalScope.Tick = TickAPI;
})(typeof self !== 'undefined' ? self : (typeof globalThis !== 'undefined' ? globalThis : null));
