// spec.js - spec 解析 + 校验（纯函数，零 DOM 零 Electron，Node 可测）
// spec 是唯一源（REQ §1 文档优先）；校验前置，非法直接抛错，不近似（REQ §3.6）。
// 四原语：nine_slice / grid / rect / stamp（REQ §3.2）。错误消息带 layer id + 字段，便于 AI/人定位。
(function (globalScope) {
'use strict';

const Library = (typeof require === 'function') ? require('./library.js') : globalScope.Library;
const TYPES = ['nine_slice', 'grid', 'rect', 'stamp'];
const isInt = (v) => Number.isInteger(v);
const isNum = (v) => typeof v === 'number' && Number.isFinite(v);

function fail(msg) { throw new Error('spec: ' + msg); }

/** 解析（字符串→JSON）+ 基本形状校验。返回 spec 对象。 */
function parseSpec(input) {
    let spec = input;
    if (typeof input === 'string') {
        try { spec = JSON.parse(input); } catch (e) { fail('JSON 解析失败: ' + e.message); }
    }
    if (!spec || typeof spec !== 'object') fail('须为对象');
    if (spec.schema !== 1) fail('schema 必须为 1');
    if (!Array.isArray(spec.canvas) || spec.canvas.length !== 2 || !spec.canvas.every(v => isInt(v) && v > 0)) fail('canvas 须为 [正整数宽, 正整数高]');
    if (!Array.isArray(spec.layers)) fail('layers 须为数组');
    if (spec.tokens !== undefined && (typeof spec.tokens !== 'object' || spec.tokens === null || Array.isArray(spec.tokens))) fail('tokens 须为对象');
    if (spec.refs !== undefined && (typeof spec.refs !== 'object' || spec.refs === null || Array.isArray(spec.refs))) fail('refs 须为对象');
    return spec;
}

/** 拆 ref/stamp 引用 "libName/element" → {libName, elem}。 */
function splitRef(ref, where) {
    if (typeof ref !== 'string' || ref.indexOf('/') < 1) fail(where + ' 须为 "库名/元素" 形式，得到 "' + ref + '"');
    const i = ref.indexOf('/');
    return { libName: ref.slice(0, i), elem: ref.slice(i + 1) };
}

/** 合并所有库的角色（rect.color / tokens 校验用）。 */
function mergedRoles(libs) {
    const out = {};
    for (const lib of Object.values(libs)) Object.assign(out, lib.roles);
    return out;
}

const inCanvas = (rect, canvas) => rect[0] >= 0 && rect[1] >= 0 && rect[0] + rect[2] <= canvas[0] && rect[1] + rect[3] <= canvas[1];

/** 完整校验。libs = {name: Library}。非法抛错（带 id/字段）。 */
function validateSpec(spec, libs) {
    const canvas = spec.canvas;
    const roles = mergedRoles(libs);
    const ids = new Set();

    if (spec.tokens) {
        for (const [k, v] of Object.entries(spec.tokens)) {
            if (!(k in roles)) fail('tokens."' + k + '" 不是已知角色（可用: ' + Object.keys(roles).join(', ') + '）');
            if (v !== null && !Library.HEX_RE.test(v)) fail('tokens."' + k + '" 非法颜色 "' + v + '"');
        }
    }

    spec.layers.forEach((layer, idx) => {
        const tag = 'layers[' + idx + ']' + (layer && layer.id ? '("' + layer.id + '")' : '');
        if (!layer || typeof layer !== 'object') fail(tag + ' 须为对象');
        if (typeof layer.id !== 'string' || !layer.id) fail(tag + ' 缺 id');
        if (ids.has(layer.id)) fail(tag + ' id 重复: "' + layer.id + '"');
        ids.add(layer.id);
        if (!TYPES.includes(layer.type)) fail(tag + ' 未知 type "' + layer.type + '"（可用: ' + TYPES.join(', ') + '）');

        if (layer.type === 'nine_slice') {
            const { libName, elem } = splitRef(layer.ref, tag + '.ref');
            const lib = libs[libName];
            if (!lib) fail(tag + '.ref 未知库 "' + libName + '"');
            if (elem !== 'panel') fail(tag + '.ref 九宫格只能引用 panel，得到 "' + elem + '"');
            if (!Array.isArray(layer.rect) || layer.rect.length !== 4 || !layer.rect.every(isInt)) fail(tag + '.rect 须为 [x,y,w,h] 整数');
            const [x, y, w, h] = layer.rect;
            if (w <= 0 || h <= 0) fail(tag + '.rect 宽高须为正');
            if (!inCanvas(layer.rect, canvas)) fail(tag + '.rect 越界 canvas ' + JSON.stringify(canvas));
            const C = lib.panel.cornerSize;
            if (C * 2 >= Math.min(w, h)) fail(tag + '.rect 太小：cornerSize*2=' + (C * 2) + ' 须 < min(w,h)=' + Math.min(w, h));
        } else if (layer.type === 'grid') {
            const { libName, elem } = splitRef(layer.stamp, tag + '.stamp');
            const lib = libs[libName];
            if (!lib) fail(tag + '.stamp 未知库 "' + libName + '"');
            const spr = lib.sprites[elem];
            if (!spr) fail(tag + '.stamp 未知精灵 "' + elem + '"');
            if (!Array.isArray(layer.origin) || layer.origin.length !== 2 || !layer.origin.every(isInt)) fail(tag + '.origin 须为 [x,y] 整数');
            if (!isInt(layer.pitch) || layer.pitch < 1) fail(tag + '.pitch 须为正整数');
            if (layer.pitch < spr.width || layer.pitch < spr.height) fail(tag + '.pitch=' + layer.pitch + ' 须 >= 精灵尺寸 ' + spr.width + 'x' + spr.height);
            if (!isInt(layer.cols) || layer.cols < 1) fail(tag + '.cols 须 >= 1');
            if (!isInt(layer.rows) || layer.rows < 1) fail(tag + '.rows 须 >= 1');
            const endX = layer.origin[0] + (layer.cols - 1) * layer.pitch + spr.width;
            const endY = layer.origin[1] + (layer.rows - 1) * layer.pitch + spr.height;
            if (layer.origin[0] < 0 || layer.origin[1] < 0 || endX > canvas[0] || endY > canvas[1]) fail(tag + ' 格点区越界 canvas（终点 ' + endX + ',' + endY + '）');
        } else if (layer.type === 'rect') {
            if (!Array.isArray(layer.rect) || layer.rect.length !== 4 || !layer.rect.every(isInt)) fail(tag + '.rect 须为 [x,y,w,h] 整数');
            if (layer.rect[2] <= 0 || layer.rect[3] <= 0) fail(tag + '.rect 宽高须为正');
            if (!inCanvas(layer.rect, canvas)) fail(tag + '.rect 越界 canvas ' + JSON.stringify(canvas));
            if (typeof layer.color !== 'string' || !(layer.color in roles)) fail(tag + '.color "' + layer.color + '" 不是已知角色（可用: ' + Object.keys(roles).join(', ') + '）');
        } else if (layer.type === 'stamp') {
            const { libName, elem } = splitRef(layer.stamp, tag + '.stamp');
            const lib = libs[libName];
            if (!lib) fail(tag + '.stamp 未知库 "' + libName + '"');
            const spr = lib.sprites[elem];
            if (!spr) fail(tag + '.stamp 未知精灵 "' + elem + '"');
            if (!Array.isArray(layer.at) || layer.at.length !== 2 || !layer.at.every(isInt)) fail(tag + '.at 须为 [x,y] 整数');
            if (layer.at[0] < 0 || layer.at[1] < 0 || layer.at[0] + spr.width > canvas[0] || layer.at[1] + spr.height > canvas[1]) fail(tag + ' 精灵越界 canvas');
        }
    });
    return spec;
}

const API = { parseSpec, validateSpec, splitRef, mergedRoles, TYPES };
if (typeof module === 'object' && module.exports) module.exports = API;
if (globalScope) globalScope.Spec = API;
})(typeof self !== 'undefined' ? self : (typeof globalThis !== 'undefined' ? globalThis : null));