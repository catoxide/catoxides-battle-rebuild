// imageops.js - 预览/评审用纯函数图像操作（零 DOM 零 Electron，Node 可测）
// REQ §3.5：preview = 4× nearest 放大（不参与打包）；diff = 逐像素差异高亮。
// 一律 nearest，永不插值（插值会造出原版不存在的中间色）。
(function (globalScope) {
'use strict';

const Bytes = (typeof require === 'function') ? require('./bytes.js') : globalScope.Bytes;

/** 整数倍 nearest 放大。scale>=1。返回 {width,height,data}。 */
function upscaleNearest(rgba, W, H, scale) {
    if (!Number.isInteger(scale) || scale < 1) throw new Error('imageops: scale 须为 >=1 整数');
    if (rgba.length !== W * H * 4) throw new Error('imageops: RGBA 长度与尺寸不符');
    const OW = W * scale, OH = H * scale;
    const out = Bytes.alloc(OW * OH * 4);
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
        const s = (y * W + x) * 4;
        for (let dy = 0; dy < scale; dy++) for (let dx = 0; dx < scale; dx++) {
            const d = ((y * scale + dy) * OW + (x * scale + dx)) * 4;
            out[d] = rgba[s]; out[d + 1] = rgba[s + 1]; out[d + 2] = rgba[s + 2]; out[d + 3] = rgba[s + 3];
        }
    }
    return { width: OW, height: OH, data: out };
}

/** 逐像素差异。返回 {count, pixels:[[x,y]...], highlight:RGBA}；highlight = a 副本，差异像素染品红。 */
function diff(a, b, W, H) {
    if (a.length !== W * H * 4 || b.length !== W * H * 4) throw new Error('imageops: diff 两图尺寸须一致');
    const highlight = Bytes.clone(a);
    const pixels = [];
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
        const i = (y * W + x) * 4;
        if (a[i] !== b[i] || a[i + 1] !== b[i + 1] || a[i + 2] !== b[i + 2] || a[i + 3] !== b[i + 3]) {
            pixels.push([x, y]);
            highlight[i] = 255; highlight[i + 1] = 0; highlight[i + 2] = 255; highlight[i + 3] = 255;
        }
    }
    return { count: pixels.length, pixels, highlight };
}

const API = { upscaleNearest, diff };
if (typeof module === 'object' && module.exports) module.exports = API;
if (globalScope) globalScope.ImageOps = API;
})(typeof self !== 'undefined' ? self : (typeof globalThis !== 'undefined' ? globalThis : null));