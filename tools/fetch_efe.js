// 从资源索引解析 efe.png 的 hash 并复制到 tools\_efe.png 供分析
const fs = require('fs');
const path = require('path');
const base = 'C:\\Users\\catoxide\\.gradle\\caches\\minecraft\\assets';
const idx = JSON.parse(fs.readFileSync(path.join(base, 'indexes', 'asset-index.json'), 'utf8'));
const o = idx.objects['textures/entity/player/slim/efe.png'];
if (!o) { console.log('not in index'); process.exit(1); }
const src = path.join(base, 'objects', o.hash.slice(0, 2), o.hash + '.png');
fs.copyFileSync(src, 'tools\\_efe.png');
console.log('copied:', src);
