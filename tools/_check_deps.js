'use strict';
// 检查 contentpack JAR 里是否有 pack 依赖 catoxidesbattierebuild:zombie_pack
const fs = require('fs');
const path = require('path');
const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';
const cpDir = ROOT + '/run/client/contentpacks';
if (!fs.existsSync(cpDir)) { console.log('no contentpacks dir'); process.exit(0); }
for (const f of fs.readdirSync(cpDir)) {
  if (!f.endsWith('.jar')) continue;
  const p = path.join(cpDir, f);
  // 简单读 zip 内 meta.json：用 unzip? 这里用 java? 不——用 node 的 zip 解析太重。
  // 直接列出 jar 内文件找 meta.json 的内容：用 adm-zip? 没装。
  // 退而求其次：打印 jar 名字，稍后人工/另法检查
  console.log('JAR:', f, fs.statSync(p).size, 'bytes');
}
