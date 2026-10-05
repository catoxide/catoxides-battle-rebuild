'use strict';
// 分析 Mojang 官方 4px_reference.png 皮肤模板：把每个颜色映射成字符，打印 64x64 ASCII 图
// 用来确认各面区域（front/back/left/right/up/down）的坐标布局
const fs = require('fs');
const zlib = require('zlib');
const path = require('path');

const SIG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
function readChunks(buf) {
  const chunks = []; let off = 8;
  while (off < buf.length) {
    const len = buf.readUInt32BE(off);
    chunks.push({ type: buf.toString('ascii', off + 4, off + 8), data: buf.subarray(off + 8, off + 8 + len) });
    off += 12 + len;
  }
  return chunks;
}
function decodePNG(buf) {
  const chunks = readChunks(buf);
  const ihdr = chunks.find(c => c.type === 'IHDR').data;
  const w = ihdr.readUInt32BE(0), h = ihdr.readUInt32BE(4);
  const channels = { 0: 1, 2: 3, 4: 2, 6: 4 }[ihdr[9]];
  const raw = Buffer.concat(chunks.filter(c => c.type === 'IDAT').map(c => c.data));
  const inf = zlib.inflateSync(raw);
  const stride = w * channels;
  const px = Buffer.alloc(w * h * channels);
  let pos = 0;
  for (let y = 0; y < h; y++) {
    const filter = inf[pos++];
    const row = inf.subarray(pos, pos + stride); pos += stride;
    const prev = y > 0 ? px.subarray((y - 1) * stride, y * stride) : null;
    const cur = px.subarray(y * stride, (y + 1) * stride);
    for (let x = 0; x < stride; x++) {
      const a = x >= channels ? cur[x - channels] : 0;
      const b = prev ? prev[x] : 0;
      const c = (x >= channels && prev) ? prev[x - channels] : 0;
      let v = row[x];
      switch (filter) {
        case 0: break;
        case 1: v = (v + a) & 255; break;
        case 2: v = (v + b) & 255; break;
        case 3: v = (v + ((a + b) >> 1)) & 255; break;
        case 4: { const p = a + b - c; const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
          v = (v + (pa <= pb && pa <= pc ? a : (pb <= pc ? b : c))) & 255; break; }
      }
      cur[x] = v;
    }
  }
  return { w, h, channels, px };
}

const file = path.resolve(process.argv[2]);
const { w, h, channels, px } = decodePNG(fs.readFileSync(file));
console.log(`PNG: ${w}x${h} channels=${channels}`);

// 颜色 -> 字符（按出现频率排序，最多 26 种）
const freq = new Map();
for (let i = 0; i < w * h; i++) {
  const key = channels === 4 ? `${px[i * 4]},${px[i * 4 + 1]},${px[i * 4 + 2]},${px[i * 4 + 3]}`
    : channels === 3 ? `${px[i * 3]},${px[i * 3 + 1]},${px[i * 3 + 2]},255`
    : `${px[i]},${px[i]},${px[i]},${px[i]}`;
  freq.set(key, (freq.get(key) || 0) + 1);
}
const sorted = [...freq.entries()].sort((a, b) => b[1] - a[1]);
const chars = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
const colorChar = new Map();
sorted.slice(0, 62).forEach(([c, n], i) => colorChar.set(c, chars[i] || '#'));
const legend = sorted.slice(0, 62).map(([c, n], i) => `${chars[i] || '#'}=${c}(x${n})`).join('  ');
console.log('图例:', legend, '\n');

for (let y = 0; y < h; y++) {
  let line = '';
  for (let x = 0; x < w; x++) {
    const i = y * w + x;
    const key = channels === 4 ? `${px[i * 4]},${px[i * 4 + 1]},${px[i * 4 + 2]},${px[i * 4 + 3]}`
      : channels === 3 ? `${px[i * 3]},${px[i * 3 + 1]},${px[i * 3 + 2]},255`
      : `${px[i]},${px[i]},${px[i]},${px[i]}`;
    const alpha = channels === 4 ? px[i * 4 + 3] : 255;
    line += alpha === 0 ? '.' : (colorChar.get(key) || '?');
  }
  console.log(line);
}
