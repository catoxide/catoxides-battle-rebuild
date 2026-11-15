// render.js - 纯函数渲染器 render(spec, libs) -> RGBA Buffer（零 DOM 零 Electron，Node 可测）
// REQ §3.3：纯函数、无 I/O、无时间戳；缩放/拉伸一律 nearest（整数行/列复制），永不插值；
// 层按数组顺序叠加，后写覆盖先写；输出 1×。同 spec + 同 libs ⇒ 逐字节相同。
(function (globalScope) {
'use strict';

const Library = (typeof require === 'function') ? require('./library.js') : globalScope.Library;
const Spec = (typeof require === 'function') ? require('./spec.js') : globalScope.Spec;
const Bytes = (typeof require === 'function') ? require('./bytes.js') : globalScope.Bytes;

function putPixel(buf, W, x, y, c) {
    const i = (y * W + x) * 4;
    buf[i] = c[0]; buf[i + 1] = c[1]; buf[i + 2] = c[2]; buf[i + 3] = c[3];
}
function blitSprite(buf, W, spr, ox, oy) {
    for (let j = 0; j < spr.height; j++) {
        const src = j * spr.width * 4;
        const dst = ((oy + j) * W + ox) * 4;
        Bytes.copy(spr.data, buf, dst, src, src + spr.width * 4);
    }
}

// 九宫格：四角逐像素拷贝 + 四边 nearest 复制拉伸 + 中心填充（与 build-library 自检同算法）
function nineSlice(buf, W, panel, table, rect) {
    const [rx, ry, w, h] = rect;
    const C = panel.cornerSize;
    const { corners, edges, center } = panel;
    const put = (x, y, sym) => putPixel(buf, W, rx + x, ry + y, table[sym]);
    for (let y = C; y < h - C; y++) for (let x = C; x < w - C; x++) put(x, y, center);
    for (let x = C; x < w - C; x++) for (let j = 0; j < C; j++) { put(x, j, edges.top[j]); put(x, h - C + j, edges.bottom[j]); }
    for (let y = C; y < h - C; y++) for (let i = 0; i < C; i++) { put(i, y, edges.left[i]); put(w - C + i, y, edges.right[i]); }
    const place = (cx, cy, grid) => { for (let j = 0; j < C; j++) for (let i = 0; i < C; i++) put(cx + i, cy + j, grid[j][i]); };
    place(0, 0, corners.TL);
    place(w - C, 0, corners.TR);
    place(0, h - C, corners.BL);
    place(w - C, h - C, corners.BR);
}

/** 渲染。spec 须已通过 Spec.validateSpec。libs = {name: Library}。返回 canvas 尺寸 RGBA Buffer。 */
function render(spec, libs) {
    const [W, H] = spec.canvas;
    const buf = Bytes.alloc(W * H * 4); // 全透明
    const tokens = spec.tokens || {};
    const merged = Object.assign(Spec.mergedRoles(libs), tokens);

    for (const layer of spec.layers) {
        if (layer.type === 'nine_slice') {
            const { libName } = Spec.splitRef(layer.ref, 'ref');
            const lib = libs[libName];
            const libTokens = {};
            for (const [k, v] of Object.entries(tokens)) if (k in lib.roles) libTokens[k] = v;
            const table = Library.symbolColorTable(lib, libTokens);
            nineSlice(buf, W, lib.panel, table, layer.rect);
        } else if (layer.type === 'grid') {
            const { libName, elem } = Spec.splitRef(layer.stamp, 'stamp');
            const spr = libs[libName].sprites[elem];
            for (let ry = 0; ry < layer.rows; ry++) for (let cx = 0; cx < layer.cols; cx++) {
                blitSprite(buf, W, spr, layer.origin[0] + cx * layer.pitch, layer.origin[1] + ry * layer.pitch);
            }
        } else if (layer.type === 'rect') {
            const c = Library.hexToRGBA(merged[layer.color]);
            const [x, y, w, h] = layer.rect;
            for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) putPixel(buf, W, x + i, y + j, c);
        } else if (layer.type === 'stamp') {
            const { libName, elem } = Spec.splitRef(layer.stamp, 'stamp');
            blitSprite(buf, W, libs[libName].sprites[elem], layer.at[0], layer.at[1]);
        }
    }
    return buf;
}

const API = { render };
if (typeof module === 'object' && module.exports) module.exports = API;
if (globalScope) globalScope.Render = API;
})(typeof self !== 'undefined' ? self : (typeof globalThis !== 'undefined' ? globalThis : null));