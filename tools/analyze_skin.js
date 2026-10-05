// 皮肤双层分析：帽层(32,0) / 经典帽层(16,32) / 外层下半区(0,32)-(64,64) 的像素内容
// 用法: node analyze_skin.js <skin.png> [more.png ...]
const fs = require('fs');
const zlib = require('zlib');

function decodePNG(p) {
  const b = fs.readFileSync(p);
  if (b.readUInt32BE(0) !== 0x89504E47) throw new Error('not png: ' + p);
  const w = b.readUInt32BE(16), h = b.readUInt32BE(20);
  const ct = b[25];
  const bpp = ct === 6 ? 4 : 3;
  let off = 8; const idat = [];
  while (off < b.length) {
    const len = b.readUInt32BE(off);
    const type = b.toString('ascii', off + 4, off + 8);
    if (type === 'IDAT') idat.push(b.slice(off + 8, off + 8 + len));
    off += 12 + len;
    if (type === 'IEND') break;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const img = Buffer.alloc(w * h * 4);
  let pos = 0;
  const paeth = (a, bb, c) => {
    const p = a + bb - c, pa = Math.abs(p - a), pb = Math.abs(p - bb), pc = Math.abs(p - c);
    return pa <= pb && pa <= pc ? a : pb <= pc ? bb : c;
  };
  for (let y = 0; y < h; y++) {
    const f = raw[pos++];
    for (let x = 0; x < w; x++) {
      for (let k = 0; k < 3; k++) {
        const cur = raw[pos + x * bpp + k];
        const a = x > 0 ? img[(y * w + x - 1) * 4 + k] : 0;
        const bb = y > 0 ? img[(y - 1) * w * x * 4 + k] : 0;
        const c = (x > 0 && y > 0) ? img[(y - 1) * w * (x - 1) * 4 + k] : 0;
        let v;
        if (f === 0) v = cur;
        else if (f === 1) v = cur + a;
        else if (f === 2) v = cur + bb;
        else if (f === 3) v = cur + Math.floor((a + bb) / 2);
        else v = cur + paeth(a, bb, c);
        img[(y * w + x) * 4 + k] = v & 0xFF;
      }
      img[(y * w + x) * 4 + 3] = ct === 6 ? raw[pos + x * bpp + 3] : 255;
    }
    pos += w * bpp;
  }
  return { w, h, img };
}

function countOpaque(img, w, x0, y0, x1, y1) {
  let n = 0;
  for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) {
    if (y < 0 || x < 0 || y >= img.h || x >= img.w) continue;
    if (img.img[(y * w + x) * 4 + 3] > 0) n++;
  }
  return n;
}

function density(img, w, x0, y0, x1, y1) {
  const total = (x1 - x0) * (y1 - y0);
  return countOpaque(img, w, x0, y0, x1, y1) / total;
}

for (const p of process.argv.slice(2)) {
  const img = decodePNG(p);
  const w = img.w, h = img.h;
  console.log('==', p.split('\\').pop(), w + 'x' + h);
  if (w < 64 || h < 64) {
    console.log('   不是 64×64（无双层区）');
    continue;
  }
  // 1.21.1 布局（反编译 PlayerModel/HumanoidModel texOffs 核对）：
  const regions = [
    ['帽层 1.21 (32,0)-(64,16)      ', 32, 0, 64, 16],
    ['经典帽层 (16,32)-(32,48)      ', 16, 32, 32, 48],
    ['jacket (16,32)-(48,48)        ', 16, 32, 48, 48],
    ['right_sleeve (40,32)-(56,48)  ', 40, 32, 56, 48],
    ['left_sleeve (48,48)-(64,64)   ', 48, 48, 64, 64],
    ['right_pants (0,32)-(16,48)    ', 0, 32, 16, 48],
    ['left_pants (0,48)-(16,64)     ', 0, 48, 16, 64],
    ['下半区整体 (0,32)-(64,64)      ', 0, 32, 64, 64],
  ];
  for (const [name, x0, y0, x1, y1] of regions) {
    console.log('   ' + name + ' 不透明占比 ' + (density(img, w, x0, y0, x1, y1) * 100).toFixed(1) + '%');
  }
  // 头基本层参考 (0,0)-(32,16)
  console.log('   [参考] 头基本层 (0,0)-(32,16) ' + (density(img, w, 0, 0, 32, 16) * 100).toFixed(1) + '%');
}
