'use strict';
// 把 tools/_SwordPlayerModelHandler.java 的 PKG 占位符替换为真实包名（程序化读取，避免拼写歧义），
// 并写入 client 包目录（同样程序化定位）。
const fs = require('fs');
const path = require('path');
const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';

function findFile(dir, name, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name.startsWith('.') || e.name === 'node_modules') continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) findFile(p, name, out);
    else if (e.name === name) out.push(p);
  }
  return out;
}

const ciPath = findFile(ROOT + '/src/main/java', 'ClientInitializer.java')[0];
const ciSrc = fs.readFileSync(ciPath, 'utf8');
// ClientInitializer 在 <base>.client 包 → 取前 3 段作为基础包 com.catoxide.<mod>
const fullPkg = ciSrc.match(/package ([a-z0-9_.]+);/)[1];
const pkg = fullPkg.split('.').slice(0, 3).join('.');
console.log('真实基础包名:', pkg, '(len', pkg.length + ')');

let src = fs.readFileSync(ROOT + '/tools/_SwordPlayerModelHandler.java', 'utf8');
src = src.replace(/\bPKG\b/g, pkg);
if (src.includes('PKG')) throw new Error('PKG 替换不完全');

const outPath = path.join(path.dirname(ciPath), 'SwordPlayerModelHandler.java');
fs.writeFileSync(outPath, src, 'utf8');
console.log('写入:', outPath);

// 校验
const out = fs.readFileSync(outPath, 'utf8');
console.log('package 行:', out.split('\n')[0]);
console.log('import 校验:', out.includes(pkg + '.CatoxidesBattleRebuildConstants'), out.includes(pkg + '.registry.ModWeapons'));
