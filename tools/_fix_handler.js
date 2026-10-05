'use strict';
// 修复 SwordPlayerModelHandler NPE（activate 成功后未重新获取 state）+ 加防御性 try/catch
// + 修正 spark_modules 主 pack 的 meta.json 残留 zombie_pack id
const fs = require('fs');
const path = require('path');
const ROOT = 'D:/Software/MC develop pack/Neoforge/catoxides-battle-rebuild';

function findFile(dir, name, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name.startsWith('.') || e.name === 'node_modules' || e.name === 'build') continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) findFile(p, name, out);
    else if (e.name === name) out.push(p);
  }
  return out;
}

// ---------- 1) 修 handler ----------
const handlerPath = findFile(ROOT + '/src/main/java', 'SwordPlayerModelHandler.java')[0];
let src = fs.readFileSync(handlerPath, 'utf8');

// R1: activate 成功后重新获取 state
const r1Old = `                if (!activate(player)) {
                    // 皮肤尚未就绪（远程玩家下载中）→ 下一 tick 重试
                    continue;
                }
            } else if (!holding && state != null) {`;
const r1New = `                if (!activate(player)) {
                    // 皮肤尚未就绪（远程玩家下载中）→ 下一 tick 重试
                    continue;
                }
                // activate 成功后重新获取（循环开头的局部变量 state 仍是旧值 null）
                state = active.get(player.getUUID());
            } else if (!holding && state != null) {`;
if (!src.includes(r1Old)) throw new Error('R1 anchor not found');
src = src.replace(r1Old, r1New);

// R2: for 循环体包防御性 try/catch（单玩家异常不炸整个客户端 tick）
const r2Old = '        for (AbstractClientPlayer player : mc.level.players()) {\n';
const r2New = '        for (AbstractClientPlayer player : mc.level.players()) {\n            try {\n';
if (!src.includes(r2Old)) throw new Error('R2 start anchor not found');
src = src.replace(r2Old, r2New);

const r3Old = '            tickAnimations(player);\n        }\n';
const r3New = `            tickAnimations(player);
            } catch (Exception e) {
                // 防御：单个玩家的处理异常不能炸掉整个客户端 tick
                LOGGER.error("[SwordPlayerModel] tick error for {}",
                        player.getGameProfile().getName(), e);
            }
        }
`;
if (!src.includes(r3Old)) throw new Error('R3 end anchor not found');
src = src.replace(r3Old, r3New);

fs.writeFileSync(handlerPath, src, 'utf8');
console.log('handler 已修复:', handlerPath);

// ---------- 2) 修 meta.json ----------
const MODID = (() => {
  const consts = findFile(ROOT + '/src/main/java', 'CatoxidesBattleRebuildConstants.java')[0];
  return fs.readFileSync(consts, 'utf8').match(/MODID = "([^"]+)"/)[1];
})();
console.log('MODID =', MODID, '(len', MODID.length + ')');

const spDir = ROOT + '/run/client/spark_modules';
const metaPath = path.join(spDir, MODID, 'meta.json');
const meta = JSON.parse(fs.readFileSync(metaPath, 'utf8'));
console.log('meta 原 id:', meta.id);
meta.id = MODID + ':main';
meta.name = "Catoxide's Battle Rebuild (Spark)";
meta.description = 'Main Spark pack: sword item models + player sword model';
fs.writeFileSync(metaPath, JSON.stringify(meta, null, 2) + '\n', 'utf8');
console.log('meta 新 id:', meta.id, '->', metaPath);
console.log('OK');
