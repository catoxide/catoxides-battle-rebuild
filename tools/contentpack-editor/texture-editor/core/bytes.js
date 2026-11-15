// bytes.js - 环境无关字节缓冲助手（Node=Buffer / 浏览器=Uint8Array）
// core 铁律：零 DOM 零 Electron。渲染器/图像操作须在浏览器渲染进程也能跑（无 Buffer），
// 故分配/拷贝一律走这里；Node 下仍返回 Buffer（单测可用 .equals/.copy）。
(function (globalScope) {
'use strict';
const hasBuffer = typeof Buffer !== 'undefined';

function alloc(n) { return hasBuffer ? Buffer.alloc(n) : new Uint8Array(n); }
function clone(src) { return hasBuffer ? Buffer.from(src) : src.slice(); }
function copy(src, dst, dstStart, srcStart, srcEnd) {
    for (let i = srcStart; i < srcEnd; i++) dst[dstStart + i - srcStart] = src[i];
}

const API = { alloc, clone, copy };
if (typeof module === 'object' && module.exports) module.exports = API;
if (globalScope) globalScope.Bytes = API;
})(typeof self !== 'undefined' ? self : (typeof globalThis !== 'undefined' ? globalThis : null));