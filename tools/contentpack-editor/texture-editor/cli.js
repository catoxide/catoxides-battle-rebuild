#!/usr/bin/env node
'use strict';
// cli.js - 无头渲染入口（AI 驱动）。纯 Node 内置 + 本模块 core，零 Electron。
//
// 用法:
//   node cli.js render   <spec.json> <out.png> [--lib 名=目录]...
//   node cli.js validate <spec.json>            [--lib 名=目录]...
//   node cli.js preview  <spec.json> <out.png> [--scale 4] [--lib 名=目录]...
//   node cli.js diff     <a.png> <b.png> <out.png>
//
// 库解析：默认自动加载 vanilla（library/vanilla-1.21.1）；spec.refs 的「名→目录」相对 spec 文件解析；
//         --lib 名=目录 显式覆盖。错误响亮（stderr + exit 1），不产生半成品 PNG。

const fs = require('fs');
const path = require('path');
const io = require('./io.js');
const PNG = require('./core/png.js');
const Spec = require('./core/spec.js');
const Render = require('./core/render.js');
const ImageOps = require('./core/imageops.js');

const DEFAULT_LIB = path.join(__dirname, 'library/vanilla-1.21.1');

function parseArgs(argv) {
    const pos = [], libs = {};
    let scale = 4;
    for (let i = 0; i < argv.length; i++) {
        const a = argv[i];
        if (a === '--lib') { const kv = argv[++i]; const eq = kv.indexOf('='); libs[kv.slice(0, eq)] = kv.slice(eq + 1); }
        else if (a.startsWith('--lib=')) { const kv = a.slice(6); const eq = kv.indexOf('='); libs[kv.slice(0, eq)] = kv.slice(eq + 1); }
        else if (a === '--scale') { scale = parseInt(argv[++i], 10); }
        else if (a.startsWith('--scale=')) { scale = parseInt(a.slice(8), 10); }
        else pos.push(a);
    }
    return { pos, libs, scale };
}

function loadLibs(spec, specDir, overrides) {
    const libs = { vanilla: io.loadLibraryDir(DEFAULT_LIB) };
    if (spec.refs) for (const [name, dir] of Object.entries(spec.refs)) libs[name] = io.loadLibraryDir(path.resolve(specDir, dir));
    for (const [name, dir] of Object.entries(overrides)) libs[name] = io.loadLibraryDir(path.resolve(dir));
    return libs;
}

function writePng(file, W, H, rgba) {
    fs.mkdirSync(path.dirname(path.resolve(file)), { recursive: true });
    fs.writeFileSync(file, PNG.encode(W, H, rgba));
}

function usage() {
    console.log(`UI 贴图生产链 CLI（panels-only v1）
  node cli.js render   <spec.json> <out.png> [--lib 名=目录]...
  node cli.js validate <spec.json>            [--lib 名=目录]...
  node cli.js preview  <spec.json> <out.png> [--scale 4] [--lib 名=目录]...
  node cli.js diff     <a.png> <b.png> <out.png>
默认库 vanilla = library/vanilla-1.21.1；spec 契约见 README.md。`);
}

function main() {
    const [cmd, ...rest] = process.argv.slice(2);
    const { pos, libs: libOverrides, scale } = parseArgs(rest);
    if (!cmd || cmd === '--help' || cmd === '-h') { usage(); return; }

    if (cmd === 'render' || cmd === 'preview' || cmd === 'validate') {
        if (!pos[0]) throw new Error(cmd + ': 缺 spec.json 参数');
        const spec = Spec.parseSpec(io.readJson(pos[0]));
        const libs = loadLibs(spec, path.dirname(path.resolve(pos[0])), libOverrides);
        Spec.validateSpec(spec, libs);
        if (cmd === 'validate') { console.log(`OK: canvas ${spec.canvas[0]}x${spec.canvas[1]}, ${spec.layers.length} 层`); return; }
        const rgba = Render.render(spec, libs);
        const [W, H] = spec.canvas;
        if (cmd === 'render') {
            if (!pos[1]) throw new Error('render: 缺 out.png 参数');
            writePng(pos[1], W, H, rgba);
            console.log(`rendered ${W}x${H} → ${pos[1]}（${spec.layers.length} 层）`);
        } else {
            if (!pos[1]) throw new Error('preview: 缺 out.png 参数');
            const up = ImageOps.upscaleNearest(rgba, W, H, scale);
            writePng(pos[1], up.width, up.height, up.data);
            console.log(`preview ${up.width}x${up.height}（${scale}× nearest）→ ${pos[1]}`);
        }
    } else if (cmd === 'diff') {
        if (pos.length < 3) throw new Error('diff: 需 <a.png> <b.png> <out.png>');
        const a = PNG.decode(fs.readFileSync(pos[0]));
        const b = PNG.decode(fs.readFileSync(pos[1]));
        if (a.width !== b.width || a.height !== b.height) throw new Error(`diff: 尺寸不一致 ${a.width}x${a.height} vs ${b.width}x${b.height}`);
        const d = ImageOps.diff(a.data, b.data, a.width, a.height);
        writePng(pos[2], a.width, a.height, d.highlight);
        console.log(`diff: ${d.count} 像素不同 → ${pos[2]}`);
        if (d.count) console.log('  前 20 个: ' + d.pixels.slice(0, 20).map(p => `(${p[0]},${p[1]})`).join(' '));
    } else {
        throw new Error('未知命令: ' + cmd);
    }
}

try { main(); } catch (e) { console.error('ERROR: ' + e.message); process.exit(1); }