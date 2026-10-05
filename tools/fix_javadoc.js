'use strict';
// 按行替换 IronSwordWeapon.java 中过时的 offset javadoc 尾段（271-281 行区域）
const fs = require('fs');
const p = 'src/main/java/com/catoxide/catoxidesbattlerebuild/weapon/IronSwordWeapon.java';
const lines = fs.readFileSync(p, 'utf8').split('\n');

const startIdx = lines.findIndex(l => l.includes('历史教训'));
if (startIdx < 0) { console.error('未找到 历史教训 行'); process.exit(1); }
// 起始行上方若有一行纯 "*"+空白（格式错乱行），一并删掉
let from = startIdx;
if (from > 0 && /^\s*\*\s*$/.test(lines[from - 1])) from = from - 1;
const endIdx = lines.findIndex((l, i) => i > from && l.includes('游戏内若仍有偏差'));
if (endIdx < 0) { console.error('未找到 游戏内若仍有偏差 行'); process.exit(1); }

const newBlock = [
  '     *',
  '     * 验证（tools/verify_arm_ends.js，t=0）：腕端距剑柄头 0.20 格（手握柄）；',
  '     * 肩端恰在肩关节；3rd_still 全静态 → 站立恒贴肩；3rd_attack 只转 right 骨',
  '     * （绕自身 pivot + ≤0.31 位置漂移），挥动中肩端漂移 ≤0.5 格（rig 固有）。',
  '     *',
  '     * 自定义动作注意：只要动作的旋转中心保持在 right 骨 pivot 附近（手臂绕肩挥），',
  '     * 本 offset 对所有动作都成立；若某动作大幅移动了 right 骨 pivot，需用脚本',
  '     * 对该动作重算（见 tools/compute_hand_offset.js）。',
  '     *',
  '     * 目前仅 TP 使用；FP 暂保持 (0,0,0)（后续单独迭代）。',
  '     * 游戏内若仍有偏差：前/后调 z，上/下调 y，左/右调 x（值越大向该方向推得越多）。',
];
const out = lines.slice(0, from).concat(newBlock, lines.slice(endIdx + 1));
fs.writeFileSync(p, out.join('\n'));
console.log(`已替换行 ${from + 1}-${endIdx + 1} → ${newBlock.length} 行新 javadoc`);
