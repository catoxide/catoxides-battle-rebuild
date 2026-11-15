// library.js - 元素库加载/校验 + 角色色解析（纯函数，零 DOM 零 Electron，Node 可测）
// 元素库 = build-library.js 从原版 jar 提炼的规整化产物（elements.json + 解码后的精灵）。
// 本模块不做 I/O：精灵以 {width,height,data:RGBA} 传入（Node 由 CLI 解码，浏览器由 IPC 提供）。
//
// 角色色解析链：symbol → role → (tokens 覆盖后的) hex → RGBA。
// tokens 只覆盖既有角色，不定义新角色（REQ §3.1）；换色 = 改 tokens，全量重生成。
(function (globalScope) {
'use strict';

const HEX_RE = /^#[0-9A-Fa-f]{6}$/;
const CORNERS = ['TL', 'TR', 'BL', 'BR'];
const EDGES = ['top', 'bottom', 'left', 'right'];

function fail(msg) { throw new Error('library: ' + msg); }
const isInt = (v) => Number.isInteger(v);

function hexToRGBA(h) {
    if (h === null || h === undefined) return [0, 0, 0, 0];
    if (!HEX_RE.test(h)) fail('非法颜色 "' + h + '"（须 #RRGGBB 或 null）');
    return [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16), 255];
}

/** 校验 elements.json 结构 + 精灵尺寸，返回规范形 lib。sprites = {name:{width,height,data}} */
function createLibrary(elements, sprites) {
    if (!elements || elements.schema !== 1) fail('elements.schema 必须为 1');
    const roles = elements.roles;
    if (!roles || typeof roles !== 'object') fail('缺 roles');
    for (const [k, v] of Object.entries(roles)) {
        if (v !== null && !HEX_RE.test(v)) fail('roles.' + k + ' 非法颜色 "' + v + '"');
    }
    if (!('transparent' in roles) || roles.transparent !== null) fail('roles.transparent 必须为 null');

    const panel = elements.panel;
    if (!panel) fail('缺 panel');
    const C = panel.cornerSize;
    if (!isInt(C) || C < 1) fail('panel.cornerSize 须为正整数');
    const symbols = panel.symbols;
    if (!symbols || typeof symbols !== 'object') fail('缺 panel.symbols');
    for (const [sym, role] of Object.entries(symbols)) {
        if (typeof sym !== 'string' || sym.length !== 1) fail('panel.symbols 键须为单字符');
        if (!(role in roles)) fail('panel.symbols["' + sym + '"] 指向未知角色 "' + role + '"');
    }
    const checkSym = (s, where) => { if (!(s in symbols)) fail(where + ' 含未定义符号 "' + s + '"'); };
    if (!panel.corners) fail('缺 panel.corners');
    for (const w of CORNERS) {
        const grid = panel.corners[w];
        if (!Array.isArray(grid) || grid.length !== C) fail('panel.corners.' + w + ' 须为 ' + C + ' 行');
        grid.forEach((row, j) => {
            if (typeof row !== 'string' || row.length !== C) fail('panel.corners.' + w + '[' + j + '] 须为 ' + C + ' 字符');
            for (const ch of row) checkSym(ch, 'panel.corners.' + w + '[' + j + ']');
        });
    }
    if (!panel.edges) fail('缺 panel.edges');
    for (const e of EDGES) {
        const prof = panel.edges[e];
        if (!Array.isArray(prof) || prof.length !== C) fail('panel.edges.' + e + ' 须为长度 ' + C + ' 的符号数组');
        prof.forEach((s, i) => checkSym(s, 'panel.edges.' + e + '[' + i + ']'));
    }
    checkSym(panel.center, 'panel.center');

    const libSprites = {};
    const spriteDefs = elements.sprites || {};
    for (const [name, def] of Object.entries(spriteDefs)) {
        const got = sprites && sprites[name];
        if (!got) fail('缺精灵解码数据: ' + name);
        if (!Array.isArray(def.size) || def.size.length !== 2) fail('sprites.' + name + '.size 须为 [w,h]');
        if (got.width !== def.size[0] || got.height !== def.size[1]) fail('sprites.' + name + ' 尺寸与声明不符');
        if (!got.data || got.data.length !== got.width * got.height * 4) fail('sprites.' + name + ' RGBA 长度不符');
        libSprites[name] = { width: got.width, height: got.height, data: got.data };
    }
    return { roles, panel, sprites: libSprites };
}

/** tokens 覆盖角色色（只覆盖既有角色）；返回 {role: hex|null}。非法 key 抛错。 */
function effectiveRoles(lib, tokens) {
    const out = Object.assign({}, lib.roles);
    if (tokens) {
        for (const [k, v] of Object.entries(tokens)) {
            if (!(k in lib.roles)) throw new Error('tokens: 未知角色 "' + k + '"（只能覆盖既有角色）');
            if (v !== null && !HEX_RE.test(v)) throw new Error('tokens.' + k + ': 非法颜色 "' + v + '"');
            out[k] = v;
        }
    }
    return out;
}

/** 面板符号 → RGBA 查找表（应用 tokens 后）。 */
function symbolColorTable(lib, tokens) {
    const roles = effectiveRoles(lib, tokens);
    const table = {};
    for (const [sym, role] of Object.entries(lib.panel.symbols)) table[sym] = hexToRGBA(roles[role]);
    return table;
}

const API = { createLibrary, effectiveRoles, symbolColorTable, hexToRGBA, HEX_RE };
if (typeof module === 'object' && module.exports) module.exports = API;
if (globalScope) globalScope.Library = API;
})(typeof self !== 'undefined' ? self : (typeof globalThis !== 'undefined' ? globalThis : null));