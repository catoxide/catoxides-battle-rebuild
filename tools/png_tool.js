'use strict';
/**
 * png_tool.js — 极简 PNG(RGBA8/RGB8) 解码 + 绘制 + 编码（无第三方库，zlib 内置）。
 * 用法: node tools/png_tool.js <png路径> <mode>
 *   mode=dump  : 打印尺寸 + 各区域像素统计（非透明像素数/颜色）
 *   mode=paint : 绘制右臂占位纹理（右下象限填手臂色，底左象限清空）
 */
const fs = require('fs');
const zlib = require('zlib');

const SIG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

function readChunks(buf) {
  const chunks = [];
  let off = 8;
  while (off < buf.length) {
    const len = buf.readUInt32BE(off);
    const type = buf.toString('ascii', off + 4, off + 8);
    const data = buf.subarray(off + 8, off + 8 + len);
    chunks.push({ type, data });
    off += 12 + len;
  }
  return chunks;
}

function decodePNG(buf) {
  if (!buf.subarray(0, 8).equals(Buffer.from(SIG))) throw new Error('not a PNG');
  const chunks = readChunks(buf);
  const ihdr = chunks.find(c => c.type === 'IHDR').data;
  const w = ihdr.readUInt32BE(0), h = ihdr.readUInt32BE(4);
  const bitDepth = ihdr[8], colorType = ihdr[9];
  if (bitDepth !== 8) throw new Error('bitDepth ' + bitDepth + ' unsupported');
  const channels = { 0: 1, 2: 3, 4: 2, 6: 4 }[colorType];
  if (!channels) throw new Error('colorType ' + colorType + ' unsupported');
  const raw = Buffer.concat(chunks.filter(c => c.type === 'IDAT').map(c => c.data));
  const inf = zlib.inflateSync(raw);
  const stride = w * channels;
  const px = Buffer.alloc(w * h * channels);
  let pos = 0;
  for (let y = 0; y < h; y++) {
    const filter = inf[pos++];
    const row = inf.subarray(pos, pos + stride);
    pos += stride;
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
        case 4: {
          const p = a + b - c;
          const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
          const pr = (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
          v = (v + pr) & 255; break;
        }
        default: throw new Error('bad filter ' + filter);
      }
      cur[x] = v;
    }
  }
  // normalize to RGBA
  const rgba = Buffer.alloc(w * h * 4);
  for (let i = 0; i < w * h; i++) {
    rgba[i * 4] = px[i * channels];
    rgba[i * 4 + 1] = px[i * channels + 1];
    rgba[i * 4 + 2] = px[i * channels + 2];
    rgba[i * 4 + 3] = channels === 4 ? px[i * channels + 3] : (channels === 2 ? px[i * channels + 1] : 255);
  }
  return { w, h, colorType, rgba };
}

function paeth(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
  return (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
}

function encodePNG(w, h, rgba) {
  const chunks = [];
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  chunks.push(['IHDR', ihdr]);
  const stride = w * 4;
  const raw = Buffer.alloc((stride + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (stride + 1)] = 0; // filter None
    raw.set(rgba.subarray(y * stride, (y + 1) * stride), y * (stride + 1) + 1); // 不用 Buffer.copy：本机 Node v24 构建下显式参数 copy 存在静默 no-op
  }
  // (filter None 足够; 若想压缩率可改 Paeth, 但 None 最简单可靠)
  const idat = zlib.deflateSync(raw, { level: 9 });
  chunks.push(['IDAT', idat]);
  // IEND 必须有！stb_image(MC 用) 靠 IEND 结束 chunk 循环，缺失会报
  // "PNG not supported: unknown PNG chunk type"
  chunks.push(['IEND', Buffer.alloc(0)]);
  let body = Buffer.alloc(0);
  for (const [type, data] of chunks) {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0);
    const t = Buffer.from(type, 'ascii');
    const crc = crc32(Buffer.concat([t, data]));
    const crcBuf2 = Buffer.alloc(4); crcBuf2.writeUInt32BE(crc >>> 0, 0);
    body = Buffer.concat([body, len, t, data, crcBuf2]);
  }
  return Buffer.concat([Buffer.from(SIG), body]);
}

// CRC32
let crcTable = null;
function crc32(buf) {
  if (!crcTable) {
    crcTable = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
      crcTable[n] = c;
    }
  }
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = crcTable[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) | 0;
}

function regionStats(w, h, rgba, x0, y0, x1, y1) {
  let opaque = 0; const colors = new Map();
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) {
    const i = (y * w + x) * 4;
    if (rgba[i + 3] > 0) {
      opaque++;
      const key = rgba.slice(i, i + 4).join(',');
      colors.set(key, (colors.get(key) || 0) + 1);
    }
  }
  const top = [...colors.entries()].sort((a, b) => b[1] - a[1]).slice(0, 6).map(([c, n]) => `rgba(${c})x${n}`);
  return `opaque=${opaque}/${(x1 - x0) * (y1 - y0)} top: ${top.join(' | ') || '-'}`;
}

const [, , file, mode] = process.argv;
const path = require('path').resolve(file);
const buf = fs.readFileSync(path);
const { w, h, colorType, rgba } = decodePNG(buf);
console.log(`PNG: ${w}x${h} colorType=${colorType} (${path})`);

if (mode === 'dump') {
  console.log('全图      :', regionStats(w, h, rgba, 0, 0, w, h));
  console.log('右下象限(右臂UV区) x[16,32) y[16,32):', regionStats(w, h, rgba, 16, 16, 32, 32));
  console.log('底左象限(左臂新UV区) x[0,16) y[16,32) :', regionStats(w, h, rgba, 0, 16, 16, 32));
  console.log('剑区 x[0,16) y[0,16)                 :', regionStats(w, h, rgba, 0, 0, 16, 16));
  console.log('右上(闪光层) x[16,32) y[0,16)         :', regionStats(w, h, rgba, 16, 0, 32, 16));
} else if (mode === 'paint') {
  // 占位手臂色: 暖肤色 (C8,9B,6E)
  const ARM = [0xC8, 0x9B, 0x6E, 255];
  // 右下象限 x[16,32) y[16,32) = 右臂 UV 区 → 填手臂色
  // （注意：底左象限有剑刃/握把 UV 延伸，绝对不能动！左臂已改为 cubes:[] 无需 UV）
  for (let y = 16; y < 32; y++) for (let x = 16; x < 32; x++) {
    const i = (y * w + x) * 4;
    rgba[i] = ARM[0]; rgba[i + 1] = ARM[1]; rgba[i + 2] = ARM[2]; rgba[i + 3] = ARM[3];
  }
  const out = encodePNG(w, h, rgba);
  fs.writeFileSync(path, out);
  console.log(`已绘制: 右臂区=${ARM.slice(0,3).map(v => v.toString(16).toUpperCase()).join('')} 不透明。写出 ${out.length} 字节 → ${path}`);
} else {
  console.error('unknown mode'); process.exit(1);
}
