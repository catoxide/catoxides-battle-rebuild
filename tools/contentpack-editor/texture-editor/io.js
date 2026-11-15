// io.js - Node 侧 I/O 助手（core 保持纯函数；文件/jar 读取与 PNG 解码集中在此）
// 仅 Node 使用（CLI / 单测）。浏览器侧由 IPC 提供等价数据，不加载本文件。
'use strict';
const fs = require('fs');
const path = require('path');
const PNG = require('./core/png.js');
const Library = require('./core/library.js');

/** 从元素库目录加载 lib：读 elements.json + 解码全部精灵。 */
function loadLibraryDir(dir) {
    const elements = JSON.parse(fs.readFileSync(path.join(dir, 'elements.json'), 'utf8'));
    const sprites = {};
    for (const [name, def] of Object.entries(elements.sprites || {})) {
        const buf = fs.readFileSync(path.join(dir, def.file));
        const im = PNG.decode(buf);
        sprites[name] = { width: im.width, height: im.height, data: im.data };
    }
    return Library.createLibrary(elements, sprites);
}

/** 读 preset/spec JSON 文件。 */
function readJson(file) { return JSON.parse(fs.readFileSync(file, 'utf8')); }

module.exports = { loadLibraryDir, readJson, PNG };