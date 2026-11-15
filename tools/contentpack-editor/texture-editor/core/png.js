// png.js - PNG 编解码（纯函数，零 DOM 零 Electron，Node 可测）
// texture-editor/core 铁律：双模式（Node require / 浏览器 self.PNG），只用 Node 内置 zlib。
// decode 支持 bitDepth 1/2/4/8、colorType 0/2/3/4/6、非隔行；encode 输出 8-bit RGBA（colorType 6，filter 0）。
(function (globalScope) {
'use strict';

const zlib = (typeof require === 'function') ? require('zlib') : globalScope.__zlib;

// ---------- CRC32（PNG chunk 校验） ----------
const CRC_TABLE = (() => {
    const t = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
        t[n] = c;
    }
    return t;
})();
function crc32(buf) {
    let c = 0xFFFFFFFF;
    for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
    return (c ^ 0xFFFFFFFF) >>> 0;
}

// ---------- 解码 ----------
function decode(buf) {
    if (!buf || buf.length < 8 || buf.readUInt32BE(0) !== 0x89504E47) throw new Error('PNG: 非法签名');
    let off = 8, width = 0, height = 0, bitDepth = 0, colorType = 0, interlace = 0;
    let palette = null, trns = null;
    const idat = [];
    while (off + 8 <= buf.length) {
        const len = buf.readUInt32BE(off);
        const type = buf.toString('ascii', off + 4, off + 8);
        const data = buf.subarray(off + 8, off + 8 + len);
        if (type === 'IHDR') {
            width = data.readUInt32BE(0); height = data.readUInt32BE(4);
            bitDepth = data[8]; colorType = data[9]; interlace = data[12];
        } else if (type === 'PLTE') palette = data;
        else if (type === 'tRNS') trns = data;
        else if (type === 'IDAT') idat.push(data);
        else if (type === 'IEND') break;
        off += 12 + len;
    }
    if (![1, 2, 4, 8].includes(bitDepth)) throw new Error('PNG: 不支持的 bitDepth ' + bitDepth);
    if (interlace !== 0) throw new Error('PNG: 不支持隔行扫描');
    const ch = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[colorType];
    if (!ch) throw new Error('PNG: 不支持的 colorType ' + colorType);
    if (!palette && colorType === 3) throw new Error('PNG: 调色板图缺 PLTE');

    const raw = zlib.inflateSync(Buffer.concat(idat));
    const stride = Math.ceil(width * ch * bitDepth / 8);
    const bpp = Math.max(1, Math.ceil(ch * bitDepth / 8));
    const out = Buffer.alloc(height * stride);
    let prev = Buffer.alloc(stride), p = 0;
    for (let y = 0; y < height; y++) {
        const f = raw[p++];
        const line = raw.subarray(p, p + stride); p += stride;
        const cur = Buffer.alloc(stride);
        for (let x = 0; x < stride; x++) {
            const a = x >= bpp ? cur[x - bpp] : 0, b = prev[x], c = x >= bpp ? prev[x - bpp] : 0;
            let v = line[x];
            if (f === 1) v = (v + a) & 255;
            else if (f === 2) v = (v + b) & 255;
            else if (f === 3) v = (v + ((a + b) >> 1)) & 255;
            else if (f === 4) {
                const q = a + b - c, pa = Math.abs(q - a), pb = Math.abs(q - b), pc = Math.abs(q - c);
                v = (v + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)) & 255;
            } else if (f !== 0) throw new Error('PNG: 未知行滤波 ' + f);
            cur[x] = v;
        }
        cur.copy(out, y * stride); prev = cur;
    }
    const sample = (pi, si) => {
        const bitPos = (pi * ch + si) * bitDepth, bytePos = bitPos >> 3, bitOff = bitPos & 7;
        if (bitDepth === 8) return out[bytePos];
        return (out[bytePos] >> (8 - bitDepth - bitOff)) & ((1 << bitDepth) - 1);
    };
    const gscale = 255 / ((1 << bitDepth) - 1);
    const rgba = Buffer.alloc(width * height * 4);
    for (let i = 0; i < width * height; i++) {
        let r, g, b, a = 255;
        if (colorType === 6) { r = sample(i, 0); g = sample(i, 1); b = sample(i, 2); a = sample(i, 3); }
        else if (colorType === 2) {
            r = sample(i, 0); g = sample(i, 1); b = sample(i, 2);
            if (trns && trns.length >= 6 && r === trns.readUInt16BE(0) && g === trns.readUInt16BE(2) && b === trns.readUInt16BE(4)) a = 0;
        } else if (colorType === 0) {
            const v = sample(i, 0); r = g = b = Math.round(v * gscale);
            if (trns && trns.length >= 2 && v === trns.readUInt16BE(0)) a = 0;
        } else if (colorType === 4) {
            const v = sample(i, 0); r = g = b = Math.round(v * gscale); a = Math.round(sample(i, 1) * gscale);
        } else if (colorType === 3) {
            const idx = sample(i, 0); r = palette[idx * 3]; g = palette[idx * 3 + 1]; b = palette[idx * 3 + 2];
            if (trns && idx < trns.length) a = trns[idx];
        }
        rgba[i * 4] = r; rgba[i * 4 + 1] = g; rgba[i * 4 + 2] = b; rgba[i * 4 + 3] = a;
    }
    return { width, height, data: rgba };
}

// ---------- 编码（8-bit RGBA，filter 0，确定性） ----------
function chunk(type, data) {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body), 0);
    return Buffer.concat([len, body, crc]);
}
function encode(width, height, rgba) {
    if (rgba.length !== width * height * 4) throw new Error('PNG: RGBA 长度与尺寸不符');
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(width, 0); ihdr.writeUInt32BE(height, 4);
    ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
    const stride = width * 4;
    const raw = Buffer.alloc(height * (stride + 1));
    for (let y = 0; y < height; y++) {
        raw[y * (stride + 1)] = 0; // filter None
        rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
    }
    const idat = zlib.deflateSync(raw, { level: 9 });
    return Buffer.concat([
        Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
        chunk('IHDR', ihdr), chunk('IDAT', idat), chunk('IEND', Buffer.alloc(0)),
    ]);
}

const API = { decode, encode, crc32 };
if (typeof module === 'object' && module.exports) module.exports = API;
if (globalScope) globalScope.PNG = API;
})(typeof self !== 'undefined' ? self : (typeof globalThis !== 'undefined' ? globalThis : null));