// undo.js - undo/redo（快照式，D1：文档规模小，快照便宜，实现简单）
// 纯函数 + 轻状态机，node 可测。快照 = JSON 深拷贝（文档是纯数据，零 DOM/three）。
//
// 用法（UI 层）：
//   const stack = createUndoStack(initialDoc);
//   // 每次"原子编辑"（一次 gizmo 拖拽结束 / 一次关键帧增删改）后：
//   stack.push(newDoc, '移动骨骼');
//   if (stack.canUndo()) setDoc(stack.undo());
//   if (stack.canRedo()) setDoc(stack.redo());

// 双模式：node（require/module.exports）+ 浏览器经典 script（self.Undo）
(function (globalScope) {
/** 深拷贝纯数据（JSON 安全；文档模型里不允许函数/循环引用）。 */
function cloneState(state) {
    return JSON.parse(JSON.stringify(state));
}

/**
 * 创建 undo 栈。
 * @param {Object} initial 初始文档状态
 * @param {number} [limit=200] 快照上限（超出丢最旧）
 */
function createUndoStack(initial, limit = 200) {
    const stack = {
        past: [],
        present: cloneState(initial),
        future: [],
        limit,
        /** 最近一次编辑的说明（状态栏显示用） */
        label: null,
    };
    return stack;
}

/**
 * 推入一个新状态（= "本次编辑已发生"）。
 * 与当前 present 深相等时不推（避免空操作刷屏）。
 * @returns {boolean} 是否真的推了
 */
function push(stack, next, label = '') {
    if (JSON.stringify(next) === JSON.stringify(stack.present)) return false;
    stack.past.push(stack.present);
    if (stack.past.length > stack.limit) stack.past.shift();
    stack.present = cloneState(next);
    stack.future = [];
    stack.label = label || null;
    return true;
}

/** @returns {Object|null} 回退后的新状态（不可撤销 → null） */
function undo(stack) {
    if (stack.past.length === 0) return null;
    stack.future.push(stack.present);
    stack.present = stack.past.pop();
    stack.label = null;
    return stack.present;
}

/** @returns {Object|null} 前进后的新状态（不可重做 → null） */
function redo(stack) {
    if (stack.future.length === 0) return null;
    stack.past.push(stack.present);
    stack.present = stack.future.pop();
    stack.label = null;
    return stack.present;
}

function canUndo(stack) {
    return stack.past.length > 0;
}

function canRedo(stack) {
    return stack.future.length > 0;
}

/** 清空（切换文档时）。保留 present。 */
function clear(stack) {
    stack.past = [];
    stack.future = [];
    stack.label = null;
}

const UndoAPI = {
    cloneState,
    createUndoStack,
    push,
    undo,
    redo,
    canUndo,
    canRedo,
    clear,
};
if (typeof module === 'object' && module.exports) module.exports = UndoAPI;
if (globalScope) globalScope.Undo = UndoAPI;
})(typeof self !== 'undefined' ? self : (typeof globalThis !== 'undefined' ? globalThis : null));
