'use strict';
// build-library.js - 从原版客户端 jar 提炼「规整化 vanilla 元素库」（ panels-only v1 ）
//
// 产品：library/vanilla-1.21.1/{elements.json, sprites/slot.png, presets/chest_54.json}
// 纪律：
//   - 角色色由「位置 + 频次」从原件判定（ring 分析），不硬编码颜色值。
//   - 四角按【画布朝向】逐像素提取，保留原版不对称（调研已证：不对称是风格，不是缺陷）。
//   - 默认零覆盖输出必须与原版逐字节相同 —— 本脚本内置边框环重合成自检（3 张面板）。
//   - 只用 Node 内置 + 复用 src/core/jarReader.js + core/png.js，零第三方图像库。
//
// 用法: node build-library.js [--jar <client-extra.jar>] [--out <dir>]
//   默认 jar = .gradle/repositories/ng_dummy_ng/net/minecraft/client/1.21.1/client-1.21.1-client-extra.jar
//   默认 out = ./library/vanilla-1.21.1

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const JarReader = require('../src/core/jarReader.js');
const PNG = require('./core/png.js');

// ---------- argv ----------
function argVal(flag, def) {
    const i = process.argv.indexOf(flag);
    return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : def;
}
const REPO = path.resolve(__dirname, '../../..');
const DEFAULT_JAR = path.join(REPO, '.gradle/repositories/ng_dummy_ng/net/minecraft/client/1.21.1/client-1.21.1-client-extra.jar');
const JAR = argVal('--jar', DEFAULT_JAR);
const OUT = argVal('--out', path.join(__dirname, 'library/vanilla-1.21.1'));

const GUI = 'assets/minecraft/textures/gui';
const P = {
    inventory: `${GUI}/container/inventory.png`,
    generic_54: `${GUI}/container/generic_54.png`,
    hopper: `${GUI}/container/hopper.png`,
    slot: `${GUI}/sprites/container/slot.png`,
};

// ---------- 像素 helpers ----------
const px = (im, x, y) => { const i = (y * im.width + x) * 4; return [im.data[i], im.data[i + 1], im.data[i + 2], im.data[i + 3]]; };
const hex = (r, g, b) => '#' + [r, g, b].map(v => v.toString(16).padStart(2, '0').toUpperCase()).join('');
const pxKey = (im, x, y) => { const [r, g, b, a] = px(im, x, y); return a === 0 ? '.' : hex(r, g, b); };

function bbox(im) {
    let x0 = 1e9, y0 = 1e9, x1 = -1, y1 = -1;
    for (let y = 0; y < im.height; y++) for (let x = 0; x < im.width; x++) {
        if (px(im, x, y)[3] > 0) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
    }
    if (x1 < 0) throw new Error('全透明图，无面板');
    return [x0, y0, x1, y1];
}
// ring k 的像素坐标列表（Chebyshev 距边框 k）
function ringPixels(r, k) {
    const x0 = r[0] + k, x1 = r[2] - k, y0 = r[1] + k, y1 = r[3] - k;
    if (x1 < x0 || y1 < y0) return null;
    const out = [];
    for (let x = x0; x <= x1; x++) { out.push([x, y0]); if (y1 !== y0) out.push([x, y1]); }
    for (let y = y0 + 1; y < y1; y++) { out.push([x0, y]); if (x1 !== x0) out.push([x1, y]); }
    return out;
}
function modeOf(keys) {
    const cnt = {};
    for (const k of keys) cnt[k] = (cnt[k] || 0) + 1;
    return Object.entries(cnt).sort((a, b) => b[1] - a[1]);
}
function ringMode(im, r, k) {
    const pts = ringPixels(r, k);
    if (!pts) return null;
    return modeOf(pts.map(([x, y]) => pxKey(im, x, y)));
}

// ---------- 角色判定（位置 + 频次） ----------
function deriveRoles(im, r) {
    // border = ring0 非透明众数
    const m0 = ringMode(im, r, 0).filter(e => e[0] !== '.');
    const border = m0[0][0];
    // bevel：ring1 按边拆，上+左 = bevelLight，下+右 = bevelDark
    const x0 = r[0] + 1, x1 = r[2] - 1, y0 = r[1] + 1, y1 = r[3] - 1;
    const lightKeys = [], darkKeys = [];
    for (let x = x0; x <= x1; x++) { lightKeys.push(pxKey(im, x, y0)); darkKeys.push(pxKey(im, x, y1)); }
    for (let y = y0 + 1; y < y1; y++) { lightKeys.push(pxKey(im, x0, y)); darkKeys.push(pxKey(im, x1, y)); }
    const bevelLight = modeOf(lightKeys.filter(k => k !== '.' && k !== border))[0][0];
    const bevelDark = modeOf(darkKeys.filter(k => k !== '.' && k !== border))[0][0];
    // body + inset：第一环 ≥90% 单一色，且该色不是 border/bevel（否则会把黑边环误判成 body）
    const chromeColors = new Set([border, bevelLight, bevelDark, '.']);
    let inset = null, body = null;
    for (let k = 0; k < 12; k++) {
        const m = ringMode(im, r, k);
        if (!m) break;
        const tot = m.reduce((s, e) => s + e[1], 0);
        const top = m.find(e => !chromeColors.has(e[0]));
        if (top && top[1] / tot >= 0.9) { inset = k; body = top[0]; break; }
    }
    if (inset === null) throw new Error('找不到 body 环（inset=null）');
    return { border, bevelLight, bevelDark, body, inset };
}

// ---------- 9-slice 几何提取 ----------
function colKey(im, x, y0, y1) { const a = []; for (let y = y0; y <= y1; y++) a.push(pxKey(im, x, y)); return a.join('|'); }
function rowKey(im, y, x0, x1) { const a = []; for (let x = x0; x <= x1; x++) a.push(pxKey(im, x, y)); return a.join('|'); }

// 最小 C 使四边带各自均匀（C 从 inset+1 起，确保角部斜切例外被收进角块）
function detectCornerSize(im, r, inset) {
    const W = r[2] - r[0] + 1, H = r[3] - r[1] + 1;
    for (let C = inset + 1; C <= 16; C++) {
        if (C * 2 >= Math.min(W, H)) break;
        const x0 = r[0], y0 = r[1], x1 = r[2], y1 = r[3];
        let ok = true;
        const topRef = colKey(im, x0 + C, y0, y0 + C - 1);
        const botRef = colKey(im, x0 + C, y1 - C + 1, y1);
        for (let x = x0 + C; x <= x1 - C && ok; x++) {
            if (colKey(im, x, y0, y0 + C - 1) !== topRef) ok = false;
            if (colKey(im, x, y1 - C + 1, y1) !== botRef) ok = false;
        }
        const leftRef = rowKey(im, y0 + C, x0, x0 + C - 1);
        const rightRef = rowKey(im, y0 + C, x1 - C + 1, x1);
        for (let y = y0 + C; y <= y1 - C && ok; y++) {
            if (rowKey(im, y, x0, x0 + C - 1) !== leftRef) ok = false;
            if (rowKey(im, y, x1 - C + 1, x1) !== rightRef) ok = false;
        }
        if (ok) return C;
    }
    throw new Error('找不到使边带均匀的 cornerSize');
}

function extractChrome(im, r, C, symOf) {
    const x0 = r[0], y0 = r[1], x1 = r[2], y1 = r[3];
    const crop = (cx, cy) => {
        const rows = [];
        for (let j = 0; j < C; j++) {
            let s = '';
            for (let i = 0; i < C; i++) s += symOf(pxKey(im, cx + i, cy + j));
            rows.push(s);
        }
        return rows;
    };
    const corners = {
        TL: crop(x0, y0),
        TR: crop(x1 - C + 1, y0),
        BL: crop(x0, y1 - C + 1),
        BR: crop(x1 - C + 1, y1 - C + 1),
    };
    const line = (keys) => keys.map(symOf);
    const edges = {
        top: line(Array.from({ length: C }, (_, j) => pxKey(im, x0 + C, y0 + j))),
        bottom: line(Array.from({ length: C }, (_, j) => pxKey(im, x0 + C, y1 - C + 1 + j))),
        left: line(Array.from({ length: C }, (_, i) => pxKey(im, x0 + i, y0 + C))),
        right: line(Array.from({ length: C }, (_, i) => pxKey(im, x1 - C + 1 + i, y0 + C))),
    };
    return { cornerSize: C, corners, edges, center: symOf(pxKey(im, x0 + C, y0 + C)) };
}

// ---------- 重合成（自检用；生产渲染器在 core/render.js） ----------
function reconstructChrome(chrome, colorOf, W, H) {
    const C = chrome.cornerSize;
    const out = Buffer.alloc(W * H * 4);
    const put = (x, y, sym) => {
        const c = colorOf(sym); const i = (y * W + x) * 4;
        out[i] = c[0]; out[i + 1] = c[1]; out[i + 2] = c[2]; out[i + 3] = c[3];
    };
    // center
    for (let y = C; y < H - C; y++) for (let x = C; x < W - C; x++) put(x, y, chrome.center);
    // edges（nearest 复制）
    for (let x = C; x < W - C; x++) for (let j = 0; j < C; j++) { put(x, j, chrome.edges.top[j]); put(x, H - C + j, chrome.edges.bottom[j]); }
    for (let y = C; y < H - C; y++) for (let i = 0; i < C; i++) { put(i, y, chrome.edges.left[i]); put(W - C + i, y, chrome.edges.right[i]); }
    // corners（逐像素）
    const place = (cx, cy, grid) => { for (let j = 0; j < C; j++) for (let i = 0; i < C; i++) put(cx + i, cy + j, grid[j][i]); };
    place(0, 0, chrome.corners.TL);
    place(W - C, 0, chrome.corners.TR);
    place(0, H - C, chrome.corners.BL);
    place(W - C, H - C, chrome.corners.BR);
    return out;
}

// ---------- 主流程 ----------
function main() {
    if (!fs.existsSync(JAR)) throw new Error('jar 不存在: ' + JAR);
    const arc = JarReader.openArchive(JAR);
    const readPng = (name) => {
        const raw = arc.read(name);
        if (!raw) throw new Error('jar 内缺条目: ' + name);
        return { raw, im: PNG.decode(raw) };
    };
    const inv = readPng(P.inventory);
    const g54 = readPng(P.generic_54);
    const hop = readPng(P.hopper);
    const slot = readPng(P.slot);

    const r = bbox(inv.im);
    const roles = deriveRoles(inv.im, r);
    const C = detectCornerSize(inv.im, r, roles.inset);

    // 符号 ↔ 角色 ↔ 颜色
    const symbols = { '.': 'transparent', '#': 'border', 'W': 'bevelLight', '5': 'bevelDark', 'B': 'body' };
    const roleHex = { transparent: null, border: roles.border, bevelLight: roles.bevelLight, bevelDark: roles.bevelDark, body: roles.body };
    const hexToSym = {};
    for (const [sym, role] of Object.entries(symbols)) {
        const h = roleHex[role];
        if (h !== null) hexToSym[h] = sym;
    }
    const symOf = (key) => {
        if (key === '.') return '.';
        const s = hexToSym[key];
        if (!s) throw new Error('chrome 出现未判定角色色: ' + key);
        return s;
    };
    const colorOf = (sym) => {
        const h = roleHex[symbols[sym]];
        if (h === null) return [0, 0, 0, 0];
        return [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16), 255];
    };

    const chrome = extractChrome(inv.im, r, C, symOf);

    // slot 角色（从 slot.png 色板判定：背景 = 众数，暗 = 次众数非白）
    const slotPal = modeOf(Array.from({ length: slot.im.width * slot.im.height }, (_, i) => {
        const a = slot.im.data[i * 4 + 3];
        return a === 0 ? '.' : hex(slot.im.data[i * 4], slot.im.data[i * 4 + 1], slot.im.data[i * 4 + 2]);
    })).filter(e => e[0] !== '.');
    const slotBg = slotPal[0][0];
    const slotDark = slotPal.find(e => e[0] !== '#FFFFFF' && e[0] !== slotBg)[0];

    // ---------- 自检 1：边框环重合成 vs 3 张面板（逐字节） ----------
    const panels = [
        { name: 'inventory', im: inv.im, rect: r },
        { name: 'generic_54', im: g54.im, rect: bbox(g54.im) },
        { name: 'hopper', im: hop.im, rect: bbox(hop.im) },
    ];
    const borderReport = [];
    for (const p of panels) {
        const pr = p.rect;
        const W = pr[2] - pr[0] + 1, H = pr[3] - pr[1] + 1;
        const recon = reconstructChrome(chrome, colorOf, W, H);
        let mismatch = 0, checked = 0;
        for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
            const ring = Math.min(x, W - 1 - x, y, H - 1 - y);
            if (ring >= C) continue; // 只验边框环（中心是内容区，由 preset 层负责）
            checked++;
            const gi = ((pr[1] + y) * p.im.width + (pr[0] + x)) * 4;
            const ri = (y * W + x) * 4;
            if (recon[ri] !== p.im.data[gi] || recon[ri + 1] !== p.im.data[gi + 1] || recon[ri + 2] !== p.im.data[gi + 2] || recon[ri + 3] !== p.im.data[gi + 3]) mismatch++;
        }
        borderReport.push({ name: p.name, size: [W, H], checked, mismatch });
        if (mismatch !== 0) throw new Error(`边框环重合成失配: ${p.name} ${mismatch}/${checked}px`);
    }

    // ---------- 自检 2：chest_54 全图（chrome + 90 槽）vs generic_54 逐字节 ----------
    const preset = {
        schema: 1,
        name: 'chest_54',
        canvas: [176, 222],
        layers: [
            { id: 'panel', type: 'nine_slice', ref: 'vanilla/panel', rect: [0, 0, 176, 222] },
            { id: 'slots_chest', type: 'grid', stamp: 'vanilla/slot', origin: [7, 17], pitch: 18, cols: 9, rows: 6 },
            { id: 'slots_inv', type: 'grid', stamp: 'vanilla/slot', origin: [7, 139], pitch: 18, cols: 9, rows: 3 },
            { id: 'slots_hotbar', type: 'grid', stamp: 'vanilla/slot', origin: [7, 197], pitch: 18, cols: 9, rows: 1 },
        ],
    };
    const gW = 176, gH = 222;
    const full = reconstructChrome(chrome, colorOf, gW, gH);
    const stampSlot = (ox, oy) => {
        for (let j = 0; j < slot.im.height; j++) for (let i = 0; i < slot.im.width; i++) {
            const si = (j * slot.im.width + i) * 4, di = ((oy + j) * gW + (ox + i)) * 4;
            full[di] = slot.im.data[si]; full[di + 1] = slot.im.data[si + 1]; full[di + 2] = slot.im.data[si + 2]; full[di + 3] = slot.im.data[si + 3];
        }
    };
    let slotCount = 0;
    for (const layer of preset.layers) {
        if (layer.type !== 'grid') continue;
        for (let ry = 0; ry < layer.rows; ry++) for (let cx = 0; cx < layer.cols; cx++) {
            stampSlot(layer.origin[0] + cx * layer.pitch, layer.origin[1] + ry * layer.pitch);
            slotCount++;
        }
    }
    const gr = bbox(g54.im);
    let fullMismatch = 0;
    for (let y = 0; y < gH; y++) for (let x = 0; x < gW; x++) {
        const gi = ((gr[1] + y) * g54.im.width + (gr[0] + x)) * 4, di = (y * gW + x) * 4;
        if (full[di] !== g54.im.data[gi] || full[di + 1] !== g54.im.data[gi + 1] || full[di + 2] !== g54.im.data[gi + 2] || full[di + 3] !== g54.im.data[gi + 3]) fullMismatch++;
    }
    if (fullMismatch !== 0) throw new Error(`chest_54 全图重合成失配: ${fullMismatch}px（槽位 ${slotCount}）`);

    // ---------- 写产物 ----------
    const elements = {
        schema: 1,
        source: 'minecraft-1.21.1',
        jarSha1: crypto.createHash('sha1').update(fs.readFileSync(JAR)).digest('hex'),
        roles: {
            transparent: null,
            border: roles.border,
            bevelLight: roles.bevelLight,
            bevelDark: roles.bevelDark,
            body: roles.body,
            slotBg,
            slotDark,
        },
        panel: Object.assign({ symbols }, chrome),
        sprites: {
            slot: { file: 'sprites/slot.png', size: [slot.im.width, slot.im.height] },
        },
    };

    fs.mkdirSync(path.join(OUT, 'sprites'), { recursive: true });
    fs.mkdirSync(path.join(OUT, 'presets'), { recursive: true });
    fs.writeFileSync(path.join(OUT, 'elements.json'), JSON.stringify(elements, null, 2) + '\n');
    fs.writeFileSync(path.join(OUT, 'sprites/slot.png'), slot.raw); // 逐字节原样
    fs.writeFileSync(path.join(OUT, 'presets/chest_54.json'), JSON.stringify(preset, null, 2) + '\n');

    // ---------- 报告 ----------
    console.log('jar:', JAR);
    console.log('jarSha1:', elements.jarSha1);
    console.log('roles:', JSON.stringify(elements.roles));
    console.log('inset:', roles.inset, ' cornerSize:', C);
    console.log('corners (画布朝向):');
    for (const w of ['TL', 'TR', 'BL', 'BR']) console.log('  ' + w + ': ' + chrome.corners[w].join(' / '));
    console.log('edges:', JSON.stringify(chrome.edges), ' center:', chrome.center);
    console.log('slot.png:', slot.im.width + 'x' + slot.im.height, ' slotBg=' + slotBg, ' slotDark=' + slotDark, ' bytes=' + slot.raw.length);
    console.log('自检1 边框环重合成（mismatch 必须 0）:');
    for (const b of borderReport) console.log(`  ${b.name.padEnd(12)} ${b.size[0]}x${b.size[1]} checked=${b.checked} mismatch=${b.mismatch}`);
    console.log(`自检2 chest_54 全图 vs generic_54: 槽位=${slotCount} mismatch=${fullMismatch}（必须 0）`);
    console.log('产物:');
    console.log('  ' + path.join(OUT, 'elements.json'));
    console.log('  ' + path.join(OUT, 'sprites/slot.png'));
    console.log('  ' + path.join(OUT, 'presets/chest_54.json'));
}

main();