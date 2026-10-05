'use strict';
// 验证 64x64 皮肤头部 6 面区域：统计每面皮肤色/深色/红(嘴)像素，并打印各面 8x8 亮度图
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

const file = path.resolve(process.argv[2] || 'tools/_steve.png');
const { w, h, channels, px } = decodePNG(fs.readFileSync(file));
const pxf = (x, y) => { const i = (w * y + x) * channels; return [px[i], px[i + 1], px[i + 2]]; };

// 新布局的 6 个头部面
const faces = {
  up:    [8, 0],
  down:  [16, 0],
  west:  [0, 8],
  north: [8, 8],
  east:  [16, 8],
  south: [24, 8],
};

const lum = (c) => (c[0] + c[1] + c[2]) / 3;

for (const [name, [x0, y0]] of Object.entries(faces)) {
  let skin = 0, dark = 0, mouth = 0, alpha = 0;
  const grid = [];
  for (let y = y0; y < y0 + 8; y++) {
    let line = '';
    for (let x = x0; x < x0 + 8; x++) {
      const c = pxf(x, y);
      const l = lum(c);
      const i = (w * y + x) * channels;
      const a = channels === 4 ? px[i + 3] : 255;
      if (a === 0) { alpha++; line += '.'; continue; }
      if (l > 120) { skin++; line += l > 170 ? 'W' : 's'; }   // s=皮肤 W=很亮(眼白/高光)
      else if (l < 75) { dark++; line += 'D'; }              // D=深色(瞳孔/头发阴影)
      else { line += l > 100 ? 'o' : 'm'; }                   // m=中间 o=偏亮中间
      if (c[0] > 90 && c[0] - c[1] > 40 && c[1] < 90) mouth++; // 红棕色=嘴
    }
    grid.push(line);
  }
  console.log(`${name.padEnd(6)} skin=${String(skin).padStart(3)} dark=${String(dark).padStart(3)} mouth=${mouth} alpha=${alpha}`);
  for (const g of grid) console.log('   ' + g);
  console.log();
}
