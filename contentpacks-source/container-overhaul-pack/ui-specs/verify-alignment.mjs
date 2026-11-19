// 浮板的机械校验：贴图 ↔ spec ↔ Java 常量 三者对齐。
//
// 用法：node ui-specs/verify-alignment.mjs
// 失败退出码 1。改动 spec / 面板常量 / 渲染器后跑一次；也算一次「PNG 没被手改」的检查。
//
// 依赖 texture-editor 的 core（纯 Node），不启动 Electron、不需要原版 jar。

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const packRoot = path.resolve(here, '..');
const repoRoot = path.resolve(packRoot, '..', '..');
const editorRoot = path.join(repoRoot, 'tools', 'contentpack-editor', 'texture-editor');
const png = require(path.join(editorRoot, 'core', 'png.js'));
const libRoot = path.join(editorRoot, 'library', 'vanilla-1.21.1');
const spriteDir = path.join(packRoot, 'assets', 'container-overhaul-pack', 'textures', 'gui');
const javaRoot = path.join(packRoot, 'java', 'example', 'contentpack', 'containeroverhaul');

const SLOT = 18;
const failures = [];
let checks = 0;

function check(ok, message) {
  checks++;
  if (!ok) failures.push(message);
}

function readJson(p) {
  return JSON.parse(fs.readFileSync(p, 'utf8'));
}

function readJava(rel) {
  return fs.readFileSync(path.join(javaRoot, rel), 'utf8');
}

/**
 * 解出源码里所有 `static final int NAME = <整数表达式>;`。
 * 表达式只允许 `+ * ( )` 和已声明的常量名 —— 面板常量是推导出来的（`ROW_INSET * 2 + ...`），
 * 只认字面量会漏掉真正的值。
 */
function javaConstants(source, where) {
  const raw = new Map();
  const re = /static\s+final\s+int\s+([A-Za-z_]\w*)\s*=\s*([^;]+);/g;
  let m;
  while ((m = re.exec(source)) !== null) {
    raw.set(m[1], m[2].trim());
  }
  const values = new Map();
  const resolve = (name) => {
    if (values.has(name)) return values.get(name);
    if (!raw.has(name)) throw new Error(`${where}: 缺少常量 ${name}`);
    const expr = raw.get(name);
    if (!/^[\w\s+*()]+$/.test(expr)) {
      throw new Error(`${where}: 常量 ${name} 不是纯整数表达式: ${expr}`);
    }
    const substituted = expr.replace(/[A-Za-z_]\w*/g, (id) => {
      if (id === name) throw new Error(`${where}: 常量 ${name} 自引用`);
      return String(resolve(id));
    });
    const value = Number(Function(`"use strict"; return (${substituted});`)());
    if (!Number.isInteger(value)) throw new Error(`${where}: 常量 ${name} 解析结果不是整数: ${value}`);
    values.set(name, value);
    return value;
  };
  return resolve;
}

function layer(spec, id) {
  const found = spec.layers.find((l) => l.id === id);
  if (!found) throw new Error(`${spec.name}: 缺少图层 ${id}`);
  return found;
}

const slotSprite = png.decode(fs.readFileSync(path.join(libRoot, 'sprites', 'slot.png')));
check(slotSprite.width === SLOT && slotSprite.height === SLOT, '元素库 slot 精灵不是 18×18');

// 运行时格底：包内的 slot.png 必须就是原版 slot 精灵（逐像素），并且由 spec 生成。
const packSlotPath = path.join(spriteDir, 'slot.png');
const packSlotSpecPath = path.join(here, 'slot.json');
check(fs.existsSync(packSlotSpecPath), 'slot: 缺少 spec slot.json');
check(fs.existsSync(packSlotPath), 'slot: 缺少运行时格底贴图 slot.png');
if (fs.existsSync(packSlotSpecPath) && fs.existsSync(packSlotPath)) {
  const packSlot = png.decode(fs.readFileSync(packSlotPath));
  check(packSlot.width === SLOT && packSlot.height === SLOT,
    `slot: 运行时格底 ${packSlot.width}×${packSlot.height} ≠ ${SLOT}×${SLOT}`);
  let same = packSlot.width === slotSprite.width && packSlot.height === slotSprite.height;
  for (let i = 0; same && i < slotSprite.data.length; i++) {
    same = packSlot.data[i] === slotSprite.data[i];
  }
  check(same, 'slot: 运行时格底与原版 slot 精灵不是逐像素一致');
  checkReproducible('slot', packSlotSpecPath, packSlotPath);
}

// ---------- 运行时面板：panel_skin 九宫格 + 原版 slot 现拼，必须还原整板 ----------

const skinPath = path.join(spriteDir, 'panel_skin.png');
const skinSpecPath = path.join(here, 'panel_skin.json');
/** 九宫格角边长（元素库 panel.cornerSize / PanelPainter.CORNER）。 */
const CORNER = 4;

/**
 * 用 16×16 的 panel_skin 按九宫格拼出 w×h 的面板，算法与 PanelPainter 的九次 blit 相同：
 * 四角 1:1、四边沿一个方向最近邻复制、中心填充。
 */
function nineSlice(skin, w, h, corner, size) {
  const out = new Uint8Array(w * h * 4);
  const put = (x, y, sx, sy) => {
    const src = (sy * size + sx) * 4;
    const dst = (y * w + x) * 4;
    for (let k = 0; k < 4; k++) out[dst + k] = skin.data[src + k];
  };
  const far = size - corner;
  for (let j = 0; j < corner; j++) {
    for (let i = 0; i < corner; i++) {
      put(i, j, i, j);
      put(w - corner + i, j, far + i, j);
      put(i, h - corner + j, i, far + j);
      put(w - corner + i, h - corner + j, far + i, far + j);
    }
  }
  for (let x = corner; x < w - corner; x++) {
    for (let j = 0; j < corner; j++) {
      put(x, j, corner, j);
      put(x, h - corner + j, corner, far + j);
    }
  }
  for (let y = corner; y < h - corner; y++) {
    for (let i = 0; i < corner; i++) {
      put(i, y, i, corner);
      put(w - corner + i, y, far + i, corner);
    }
  }
  for (let y = corner; y < h - corner; y++) {
    for (let x = corner; x < w - corner; x++) put(x, y, corner, corner);
  }
  return out;
}

/** 运行时画出来的整板（面板 + 原版 slot 格）= 已提交的 board_N.png？ */
function checkRuntimeRebuild(skin, pngName, spec, img, corner, size) {
  const w = img.width;
  const h = img.height;
  const rebuilt = nineSlice(skin, w, h, corner, size);
  for (const grid of spec.layers.filter((l) => l.type === 'grid')) {
    for (let row = 0; row < grid.rows; row++) {
      for (let col = 0; col < grid.cols; col++) {
        const ox = grid.origin[0] + col * grid.pitch;
        const oy = grid.origin[1] + row * grid.pitch;
        for (let j = 0; j < slotSprite.height; j++) {
          for (let i = 0; i < slotSprite.width; i++) {
            const s = (j * slotSprite.width + i) * 4;
            const d = ((oy + j) * w + ox + i) * 4;
            for (let k = 0; k < 4; k++) rebuilt[d + k] = slotSprite.data[s + k];
          }
        }
      }
    }
  }
  let mismatch = 0;
  let first = null;
  for (let i = 0; i < rebuilt.length; i += 4) {
    if (rebuilt[i] !== img.data[i] || rebuilt[i + 1] !== img.data[i + 1]
      || rebuilt[i + 2] !== img.data[i + 2] || rebuilt[i + 3] !== img.data[i + 3]) {
      mismatch++;
      if (!first) first = [(i / 4) % w, Math.floor(i / 4 / w)];
    }
  }
  check(mismatch === 0,
    `${pngName}: 用 panel_skin 现拼的结果与整板差 ${mismatch}px（首个 ${first}）—— 运行时会画错`);
}

let skinImage = null;

/** 运行时面板贴图：16×16 九宫格源图，已提交的内容必须等于 spec 渲染结果。 */
function loadSkin() {
  if (skinImage) return skinImage;
  check(fs.existsSync(skinSpecPath), 'panel_skin: 缺少 spec panel_skin.json');
  check(fs.existsSync(skinPath), 'panel_skin: 缺少生成贴图 panel_skin.png');
  if (!fs.existsSync(skinSpecPath) || !fs.existsSync(skinPath)) return null;
  skinImage = png.decode(fs.readFileSync(skinPath));
  check(skinImage.width === CORNER * 4 && skinImage.height === CORNER * 4,
    `panel_skin: 运行时贴图应为 ${CORNER * 4}×${CORNER * 4}，实际 ${skinImage.width}×${skinImage.height}`);
  checkReproducible('panel_skin', skinSpecPath, skinPath);
  return skinImage;
}

/** 已提交的 PNG 必须等于 spec 现渲染的结果（防止手改 PNG）。 */
function checkReproducible(name, specPath, pngPath) {
  const tmp = path.join(os.tmpdir(), `verify-${name}-${process.pid}.png`);
  try {
    execFileSync('node', [path.join(editorRoot, 'cli.js'), 'render', specPath, tmp], { stdio: 'pipe' });
    const rendered = fs.readFileSync(tmp);
    const committed = fs.readFileSync(pngPath);
    check(rendered.equals(committed), `${name}: 已提交 PNG 与 spec 渲染结果不一致（手改过？重跑 render）`);
  } finally {
    fs.rmSync(tmp, { force: true });
  }
}

/**
 * 非 256×256 的贴图不能走 7 参 blit：该重载隐含 textureWidth/Height = 256，
 * UV 只覆盖图的 256/W、256/H 比例的区域，像素被拉长。整板是 1:1 画，
 * 调用宽高必须同时等于贴图宽高。
 */
function verifyBlits(screenName, source, expected) {
  const calls = source.match(/\.blit\([^()]*\)/g) ?? [];
  check(calls.length === expected, `${screenName}: blit 调用应为 ${expected} 次，实际 ${calls.length} 次`);
  for (const call of calls) {
    const args = call.slice(call.indexOf('(') + 1, -1).split(',').map((s) => s.trim());
    check(args.length === 9, `${screenName}: blit 应为 9 参（显式贴图尺寸），实际 ${args.length} 参`);
    if (args.length === 9) {
      check(args[5] === 'width' && args[6] === 'height' &&
        args[7] === 'width' && args[8] === 'height',
        `${screenName}: 整板不是 1:1 画（绘制 ${args[5]}×${args[6]}，贴图 ${args[7]}×${args[8]}）`);
    }
  }
}

// ---------- 整板：board_N.json = N 行各一张，边框与格子都烘进图里 ----------

/** 渲染图在 (x,y) 起 18×18 是否逐像素等于原版 slot 精灵（精灵须不透明）。 */
function spriteEquals(img, sprite, x, y) {
  for (let j = 0; j < sprite.height; j++) {
    for (let i = 0; i < sprite.width; i++) {
      const a = ((y + j) * img.width + x + i) * 4;
      const b = (j * sprite.width + i) * 4;
      for (let k = 0; k < 4; k++) {
        if (img.data[a + k] !== sprite.data[b + k]) return false;
      }
    }
  }
  return true;
}

function checkBoard(specFile, pngName, rows, geom) {
  const specPath = path.join(here, specFile);
  const pngPath = path.join(spriteDir, pngName);
  check(fs.existsSync(specPath), `${pngName}: 缺少 spec ${specFile}`);
  check(fs.existsSync(pngPath), `${pngName}: 缺少生成贴图 ${pngName}`);
  if (!fs.existsSync(specPath) || !fs.existsSync(pngPath)) return;

  const { width, height, cols, inset, rowPitch, slotPitch, hotbarGap } = geom;
  const spec = readJson(specPath);
  const img = png.decode(fs.readFileSync(pngPath));
  check(spec.canvas[0] === width && spec.canvas[1] === height,
    `${pngName}: canvas ${spec.canvas.join('×')} ≠ Java ${width}×${height}`);
  check(img.width === width && img.height === height,
    `${pngName}: PNG ${img.width}×${img.height} ≠ Java ${width}×${height}`);
  checkReproducible(pngName, specPath, pngPath);

  const panel = layer(spec, 'panel');
  check(panel.type === 'nine_slice' && panel.rect.join(',') === `0,0,${width},${height}`,
    `${pngName}/panel: nine_slice rect ${panel.rect} ≠ [0,0,${width},${height}]`);

  const grids = spec.layers.filter((l) => l.type === 'grid');
  check(grids.length === rows, `${pngName}: 应只有 ${rows} 行格子，实际 ${grids.length}`);
  for (let row = 0; row < rows; row++) {
    const g = layer(spec, `row_${row}`);
    // spec 行号自上而下；最底一行是快捷栏，只有它让开 hotbarGap。
    const gap = row === rows - 1 ? hotbarGap : 0;
    const expected = [inset, inset + row * rowPitch + gap];
    check(g.stamp === 'vanilla/slot', `${pngName}/row_${row}: stamp 不是 vanilla/slot`);
    check(g.origin.join(',') === expected.join(','),
      `${pngName}/row_${row}: origin ${g.origin} ≠ ${expected}`);
    check(g.pitch === slotPitch, `${pngName}/row_${row}: pitch ${g.pitch} ≠ ${slotPitch}`);
    check(g.cols === cols, `${pngName}/row_${row}: cols ${g.cols} ≠ ${cols}`);
    check(g.rows === 1, `${pngName}/row_${row}: rows ${g.rows} ≠ 1`);
    // 格子必须真的烘进图里：这一格的像素应与元素库 slot 精灵逐像素一致。
    check(spriteEquals(img, slotSprite, expected[0], expected[1]),
      `${pngName}/row_${row}: (${expected}) 处不是原版 slot 像素（格子没烘进整板？）`);
  }

  // 运行时不读这张整板：它按 panel_skin 九宫格现拼 + 原版 slot 现画。
  // 两者必须逐像素一致，否则「烘出来的样子」和「玩家看到的样子」会分家。
  const skin = loadSkin();
  if (skin) checkRuntimeRebuild(skin, pngName, spec, img, CORNER, skin.width);
}

function verifyBoards() {
  const side = readJava(path.join('ui', 'SidePanel.java'));
  const c = javaConstants(side, 'SidePanel.java');
  const width = c('ROW_WIDTH');
  const pitch = c('SLOT_PITCH');
  const inset = c('ROW_INSET');
  const cols = c('ROW_SLOTS');
  const rowPitch = c('ROW_PITCH');
  const hotbarGap = c('HOTBAR_GAP');
  const maxRows = c('BACKPACK_ROWS') + 1;

  check(width === inset * 2 + cols * pitch,
    `board: Java ROW_WIDTH=${width} ≠ ROW_INSET*2+ROW_SLOTS*SLOT_PITCH=${inset * 2 + cols * pitch}`);
  // 背包行要和原版一样紧贴：行距就是格子边距 18，不是「18 + 上下内边距」。
  check(rowPitch === pitch,
    `board: ROW_PITCH=${rowPitch} ≠ SLOT_PITCH=${pitch}（行与行要按原版紧贴）`);
  // 快捷栏单独让开：原版快捷栏 142、主栏底行 120 —— 差值 22 = 18 + 4。
  check(hotbarGap === 4,
    `board: HOTBAR_GAP=${hotbarGap} ≠ 4（原版快捷栏 142 - 主栏底行 120 - 行距 18）`);
  check(maxRows === 5, `board: 板应最多 5 行（快捷栏 + 4 行背包），实际 ${maxRows}`);

  // 运行时 skin 与整板必须都走元素库的原版色板：spec 不写 tokens，换色只在库一处。
  for (const spec of ['panel_skin', 'upgrade', ...[1, 2, 3, 4, 5].map((n) => `board_${n}`)]) {
    check(!('tokens' in readJson(path.join(here, `${spec}.json`))),
      `${spec}: spec 还带着 tokens（面板色板应统一继承元素库原版 roles）`);
  }

  for (let rows = 1; rows <= maxRows; rows++) {
    const gap = rows > 1 ? hotbarGap : 0;
    checkBoard(`board_${rows}.json`, `board_${rows}.png`, rows,
      { width, height: rows * rowPitch + gap + inset * 2, cols, inset, rowPitch,
        slotPitch: pitch, hotbarGap: gap });
  }
  check(!fs.existsSync(path.join(here, `board_${maxRows + 1}.json`)),
    `board: 多出第 ${maxRows + 1} 行 spec（BACKPACK_ROWS+1=${maxRows}）`);

  checkBoard('upgrade.json', 'upgrade.png', 1, {
    width: c('UPGRADE_WIDTH'), height: c('UPGRADE_HEIGHT'),
    cols: c('UPGRADE_COLS'), inset: c('UPGRADE_INSET'), rowPitch: c('UPGRADE_HEIGHT'),
    slotPitch: pitch, hotbarGap: 0,
  });

  const lib = readJson(path.join(libRoot, 'elements.json'));
  check(lib.sprites.slot.size[0] === SLOT && lib.sprites.slot.size[1] === SLOT,
    `board: 元素库 slot 精灵不是 ${SLOT}×${SLOT}`);

  const normalized = side.replace(/\s+/g, ' ');
  // 槽位比格点右下各 1px（原版 renderSlot 画在 slot.x-1）；固定写法，抓回来防手改。
  check(normalized.includes('boardX(guiWidth) + ROW_INSET + 1 + (containerSlot % ROW_SLOTS) * SLOT_PITCH'),
    'board: SidePanel.slotX 的列坐标不再是「板 x + 格点 + 1」');
  check(normalized.includes('return hotbarY - row * ROW_PITCH - (row > 0 ? HOTBAR_GAP : 0) + 1;'),
    'board: SidePanel.slotY 不再是「快捷栏行 - 行号×ROW_PITCH - 快捷栏缝 + 1」');
  check(normalized.includes('return (containerSlot - ROW_SLOTS) / ROW_SLOTS + 1;'),
    'board: SidePanel.slotRow 不再是「(格号-9)/9+1」');
  check(normalized.includes('return hotbarY - (rows - 1) * ROW_PITCH - (rows > 1 ? HOTBAR_GAP : 0) - ROW_INSET;'),
    'board: SidePanel.boardY 不再是「快捷栏行 - (行数-1)×ROW_PITCH - 快捷栏缝 - 内边距」');
  check(normalized.includes('return rows * ROW_PITCH + (rows > 1 ? HOTBAR_GAP : 0) + ROW_INSET * 2;'),
    'board: SidePanel.boardHeight 不再是「行数×ROW_PITCH + 快捷栏缝 + 上下内边距」');
}

// ---------- 运行时面板：合并 + 现画 + 截底 ----------

function verifyPanels() {
  const skin = readJava(path.join('client', 'PanelPainter.java'));
  const pc = javaConstants(skin, 'PanelPainter.java');
  check(pc('CORNER') === CORNER,
    `painter: 九宫格角 ${pc('CORNER')} ≠ 元素库 cornerSize ${CORNER}`);
  check(pc('TEXTURE') === CORNER * 4,
    `painter: 贴着图宽 ${pc('TEXTURE')} ≠ ${CORNER * 4}（16×16 四角各 4px，边/心各 1px）`);
  check(skin.includes('textures/gui/panel_skin.png'),
    'painter: 没引用运行时面板贴图 panel_skin.png');
  // 九宫格必须九次 blit：四角 + 四边 + 心。少一次就是某块没画（角/边缺失）。
  check((skin.match(/blit\(graphics,/g) ?? []).length === 9,
    'painter: 九宫格应是九次 blit（四角 + 四边 + 心）');
  // 只画外框的板不能填板身：原版容器屏的槽位格底 / 内部装饰都烘在自己的贴图里。
  check(skin.includes('if (mode == Panel.Mode.BODY)'),
    'painter: 板身没有按画法开关（FRAME 板会盖住原版内部）');
  check(skin.indexOf('if (mode == Panel.Mode.BODY)')
      < skin.indexOf('innerW, innerH, CORNER, CORNER, 1, 1'),
    'painter: 板身填充应在画法判断之后（否则 FRAME 也填）');
  // 格底要自己画：1.21 的原版 renderSlot 只画物品与悬停高亮，格底属于容器贴图。
  check(skin.includes('textures/gui/slot.png'), 'painter: 没引用运行时格底贴图 slot.png');
  const slotCall = skin.match(/graphics\.blit\(SLOT,([\s\S]*?)\);/);
  check(!!slotCall, 'painter: 没有画格底（paintSlot）');
  if (slotCall) {
    const args = ['SLOT', ...slotCall[1].split(',')].map((s) => s.trim());
    check(args.length === 9 && args[5] === 'SLOT_SIZE' && args[6] === 'SLOT_SIZE'
      && args[7] === 'SLOT_SIZE' && args[8] === 'SLOT_SIZE',
      `painter: 格底必须 18×18 1:1 画（显式贴图尺寸），实际 ${args.slice(5).join('×')}`);
  }
  // 悬浮板与外框共用一张 skin：色板只有一套（原版），外框内侧那一行就是板身色，
  // 压在 vanilla 板身上不会留色差；再按画法换贴图就是把色差又请回来。
  check(!skin.includes('SKIN_DOCKED') && !skin.includes('panel_skin_docked'),
    'painter: 还在按画法换 skin（外框与悬浮板应共用一套原版色板）');
  // 16×16 的贴图在 11 参 blit 里显式给 texW/texH，7 参会按 256 采样把整张图缩进角落。
  const scaled = skin.match(/graphics\.blit\(SKIN,([\s\S]*?)\);/);
  check(!!scaled, 'painter: 没有走 GuiGraphics.blit(SKIN, ...)');
  if (scaled) {
    const args = ['SKIN', ...scaled[1].split(',')].map((s) => s.trim());
    check(args.length === 11 && args[9] === 'TEXTURE' && args[10] === 'TEXTURE',
      `painter: 必须用 11 参缩放 blit 并显式贴图尺寸，实际 ${args.length} 参`);
  }

  const merge = readJava(path.join('ui', 'PanelMerge.java'));
  check(merge.includes('a.level() == b.level()'),
    'merge: 合并没按层级过滤（不同层的板会并成一块）');
  check(merge.includes('a.mode() == b.mode()'),
    'merge: 合并没按画法过滤（只画外框的板会与整板并成一块）');
  check(merge.includes('cells.addAll(b.cells())'),
    'merge: 合并后没并集格底（合并板上的格子会消失）');
  check(merge.includes('overlapX >= 0 && overlapY >= 0'),
    'merge: 贴边判定不是「两轴都不分离」');
  check(merge.includes('(overlapX > 0 || overlapY > 0)'),
    'merge: 只碰一个角也算贴边（外包矩形会多出没人占的面积）');

  const component = readJava(path.join('client', 'InventoryPanelsComponent.java'));
  check(component.includes('implements ContainerUiComponent'),
    'panels: 物品栏板 / 升级板不是独立部件（没实现 ContainerUiComponent）');
  check(component.includes('List<Panel> declare('),
    'panels: 部件没按统一声明交出面板（declare 返回 List<Panel>）');
  check(component.includes('Panel.body(boardRect, Panel.FLOATING')
    && component.includes('Panel.body(upgradeRect, Panel.FLOATING'),
    'panels: 两块板不是同层整板声明（Panel.body / Panel.FLOATING）');
  check(component.includes('SidePanel.ROW_WIDTH, SidePanel.boardHeight(rows)'),
    'panels: 物品栏板不是按「板宽 × 板高(行数)」现画');
  check(component.includes('SidePanel.UPGRADE_WIDTH, SidePanel.UPGRADE_HEIGHT'),
    'panels: 升级板不是按自身尺寸现画');
  check(component.includes('SidePanel.boardRows(view.upgrades())'),
    'panels: 板高不是由解锁的升级槽决定');
  check(component.includes('SidePanel.boardY(view.hotbarY(), rows)'),
    'panels: 板顶没用 boardY(view.hotbarY(), rows)（快捷栏行要钉在这一屏的原版位置）');
  check(component.includes('SidePanel.upgradePanelY(view.hotbarY())'),
    'panels: 升级板 y 没跟这一屏的快捷栏行走');
  check(component.includes('placeSlots(AbstractContainerScreen<?> screen)')
    && component.includes('board.place(view, screen.getXSize())')
    && component.includes('upgrades.place(view, screen.getXSize())'),
    'panels: 没把偏移/坐标落到槽位（placeSlots）');
  check(component.includes('board.offsetX()') && component.includes('upgrades.offsetY()'),
    'panels: 底板没用拖动偏移（或升级板没联动物品栏板）');
  check(!component.includes('board_'),
    'panels: 还在取 board_N.png 整板贴图（应改为运行时现画）');
  check(!component.includes('textures/gui/slot') && !component.includes('PanelPainter.paintSlot'),
    'panels: 格底不该自己 blit（格底统一由渲染器按声明补画）');
  check(component.includes('slot.x - 1') && component.includes('slot.y - 1')
    && component.includes('PanelPainter.SLOT_SIZE'),
    'panels: 格底没按槽位坐标左上各 1px 声明');
  check(component.includes('SidePanel.unlockedCount(view.upgrades())') && component.includes('break;'),
    'panels: 没跳过锁着的行（会在板上方画出空格底）');

  const cut = readJava(path.join('client', 'ContainerCut.java'));
  check(cut.includes('implements ScreenCuts.Provider'),
    'cut: 截底不是 core 的 ScreenCuts 提供者');
  check(cut.includes('SidePanelMounts.view(screen.getMenu())'),
    'cut: 没挂载悬浮物品栏的屏也会被截底');
  // 截线必须是挂载时的快照：之后浮板每帧把 9–35 挪到板上，现读 slot.y 会拿到浮板坐标。
  check(cut.includes('view.cutHeight()'),
    'cut: 截线没取挂载时算好的快照（View#cutHeight）');
  check(cut.includes('view.cutHeight() < 0'),
    'cut: 没处理「这一屏找不到玩家主栏 = 不截」');
  check(!cut.includes('slot.y'),
    'cut: 又在运行时读槽位 y（浮板每帧会把这些坐标改掉）');
  check(!cut.includes('CUT_INVENTORY_SCREEN') && !cut.includes('CUT_CHEST_BOTTOM'),
    'cut: 还在用按屏硬编码的截底常量');
  check(!cut.includes('instanceof InventoryMenu') && !cut.includes('instanceof ChestMenu'),
    'cut: 仍按菜单类型分支截底（应统一按槽位结构）');
}

// ---------- 面板声明：一种形状，层级 / 画法 / 格底 ----------

function verifyDeclaration() {
  const panel = readJava(path.join('ui', 'Panel.java'));
  check(panel.includes('record Panel('), 'decl: 面板声明不是统一的 Panel 记录');
  check(panel.includes('enum Mode') && panel.includes('BODY') && panel.includes('FRAME'),
    'decl: 画法不是 BODY / FRAME 两种');
  check(panel.includes('List<UiRect> cells'), 'decl: 声明里没有格底列表');
  check(panel.includes('List.copyOf(cells)'),
    'decl: 格底列表没做不可变拷贝（外部改得动声明）');
  check(panel.includes('public static final int DOCKED') && panel.includes('public static final int FLOATING'),
    'decl: 层级不是 DOCKED / FLOATING 两个具名常量');
  check(panel.includes('public static Panel frame('),
    'decl: 没有只画外框的声明入口（Panel.frame）');
}

// ---------- 原版容器屏上半部分：只换外框，底边收在截线上 ----------

function pixelRgb(img, x, y) {
  const a = (y * img.width + x) * 4;
  return [img.data[a], img.data[a + 1], img.data[a + 2]];
}

function hexRgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

// ---------- 色板：全部对齐原版（悬浮板 / 外框 / 整板共用元素库 roles） ----------

function verifyPalette() {
  const lib = readJson(path.join(libRoot, 'elements.json'));
  const vanilla = { border: '#000000', bevelLight: '#FFFFFF', bevelDark: '#555555', body: '#C6C6C6' };
  for (const [name, hex] of Object.entries(vanilla)) {
    check(lib.roles[name] === hex,
      `palette: 元素库 ${name}=${lib.roles[name]} ≠ 原版 ${hex}（面板会与原版窗口有色差）`);
  }

  const img = loadSkin();
  if (!img) return;
  const at = (x, y) => pixelRgb(img, x, y).join(',');
  const role = (name) => hexRgb(lib.roles[name]).join(',');
  // 源图 16×16：四角 4px，边 1px 在源列 x=4 / 源行 y=4。
  const points = [
    [4, 0, 'border', '上边框'], [0, 4, 'border', '左边框'],
    [4, 1, 'bevelLight', '上亮棱'], [1, 4, 'bevelLight', '左亮棱'],
    [4, 13, 'bevelDark', '下暗棱'], [13, 4, 'bevelDark', '右暗棱'],
    [4, 4, 'body', '板身'],
  ];
  for (const [x, y, name, label] of points) {
    check(at(x, y) === role(name),
      `palette: panel_skin (${x},${y}) ${label} 是 ${at(x, y)}，元素库 ${name} 是 ${role(name)}`);
  }
  // 外框与悬浮板共用这张 skin：四条边内侧那一行/列必须是板身色，
  // 压在 vanilla 板身上才无缝（上 v=3、下 v=12、左 u=3、右 u=12）。
  for (const [x, y, edge] of [[8, 3, '上'], [8, 12, '下'], [3, 8, '左'], [12, 8, '右']]) {
    check(at(x, y) === role('body'),
      `palette: skin ${edge}边内侧 (${x},${y}) 是 ${at(x, y)}，不是板身色 —— 外框与内部会留色差`);
  }
}

function verifyUpper() {
  const component = readJava(path.join('client', 'ContainerUpperPanelComponent.java'));
  check(component.includes('implements ContainerUiComponent'),
    'upper: 上半部分外框不是独立部件');
  check(component.includes('ScreenCuts.visibleHeight(screen)'),
    'upper: 外框没用统一登记的可见高度');
  check(component.includes('Panel.frame(') && component.includes('Panel.DOCKED'),
    'upper: 上半部分应是只换外框（Panel.frame / Panel.DOCKED）');
  check(component.includes('visible + PanelPainter.CORNER'),
    'upper: 外框高度不是「可见高度 + 外框厚度」（底边要落在截线以下收口）');
  check(component.includes('screen.getGuiLeft()') && component.includes('screen.getGuiTop()')
    && component.includes('screen.getXSize()'),
    'upper: 外框没跟这一屏的面板矩形走');
  check(!component.includes('floor_') && !component.includes('WIDTHS'),
    'upper: 还在按宽度取整条底边贴图（外框应任意宽度现画）');
}

// ---------- 解锁阶梯 / 升级板常量 ----------

function verifyLadder() {
  const side = readJava(path.join('ui', 'SidePanel.java'));
  const c = javaConstants(side, 'SidePanel.java');

  // 4 个升级槽 → 9/18/27/36/45。
  check(c('UPGRADE_COLS') === 4, `ladder: 升级槽应为 4 个（9→45 需要 4 级），实际 ${c('UPGRADE_COLS')}`);
  check(c('ROW_SLOTS') === 9, `ladder: 一行应为 9 格，实际 ${c('ROW_SLOTS')}`);
  check(c('BASE_UNLOCKED') === c('ROW_SLOTS'),
    `ladder: 基础解锁格数 ${c('BASE_UNLOCKED')} ≠ 一行 ${c('ROW_SLOTS')}`);
  check(c('UNLOCK_PER_UPGRADE') === c('ROW_SLOTS'),
    `ladder: 每级解锁 ${c('UNLOCK_PER_UPGRADE')} ≠ 一行 ${c('ROW_SLOTS')}`);
  check(c('BASE_UNLOCKED') + c('UPGRADE_COLS') * c('UNLOCK_PER_UPGRADE') === c('MAX_ITEM_SLOTS'),
    `ladder: 9 + 4×9 ≠ 物品格总数 ${c('MAX_ITEM_SLOTS')}`);
  check(c('BACKPACK_ROWS') === 4, `ladder: 背包最多 4 行，实际 ${c('BACKPACK_ROWS')}`);
  check(c('MAX_ITEM_SLOTS') === (c('BACKPACK_ROWS') + 1) * c('ROW_SLOTS'),
    `ladder: 物品格总数 ${c('MAX_ITEM_SLOTS')} ≠ (快捷栏+4 行)×9`);
  check(c('EXTRA_ROW_START') === c('MAX_ITEM_SLOTS') - c('ROW_SLOTS'),
    `ladder: 补挂行起点 ${c('EXTRA_ROW_START')} ≠ 总格数 - 一行`);
  check(c('UPGRADE_START') === c('MAX_ITEM_SLOTS'),
    `ladder: UPGRADE_START=${c('UPGRADE_START')} ≠ MAX_ITEM_SLOTS=${c('MAX_ITEM_SLOTS')}`);
  check(c('COUNT') === c('MAX_ITEM_SLOTS') + c('UPGRADE_COLS'),
    `ladder: COUNT=${c('COUNT')} ≠ MAX_ITEM_SLOTS+UPGRADE_COLS`);

  check(c('UPGRADE_WIDTH') === c('UPGRADE_INSET') * 2 + c('UPGRADE_COLS') * c('SLOT_PITCH'),
    `ladder: UPGRADE_WIDTH=${c('UPGRADE_WIDTH')} ≠ 边距+4 格`);
  check(c('UPGRADE_HEIGHT') === c('UPGRADE_INSET') * 2 + c('SLOT_PITCH'),
    `ladder: UPGRADE_HEIGHT=${c('UPGRADE_HEIGHT')} ≠ 边距+1 格`);

  const normalized = side.replace(/\s+/g, ' ');
  check(normalized.includes('boardX(guiWidth) + UPGRADE_INSET + 1 + col * SLOT_PITCH'),
    'ladder: SidePanel.upgradeSlotX 的列坐标不再是「板 x + 格点 + 1」');
  check(normalized.includes('return upgradePanelY(hotbarY) + UPGRADE_INSET + 1;'),
    'ladder: SidePanel.upgradeSlotY 不再是「板 y + 格点 + 1」');
  check(normalized.includes('return hotbarY + ROW_PITCH + ROW_INSET + UPGRADE_GAP;'),
    'ladder: 升级板不再挂在快捷栏行下方');
}

// ---------- 渲染器 / 布局 / 包接线 ----------

function verifyWiring() {
  const hub = readJava(path.join('client', 'ContainerUiRenderer.java'));
  verifyBlits('ContainerUiRenderer.java', hub, 0);
  check(hub.includes('ContainerUiComponent'),
    'wiring: 渲染入口没按部件接口遍历');
  // 部件各自判断出场：入口若按侧面板挂载状态提前返回，会把所有部件一起带走。
  check(!hub.includes('SidePanelMounts'),
    'wiring: 渲染入口还看侧面板挂载状态（部件应各自判断出场）');
  check(!hub.includes('drawBoard(') && !hub.includes('blitBoard('),
    'wiring: 渲染入口还在整板 1:1 画（画板应收在 PanelPainter）');
  check(hub.includes('PanelMerge.merge'), 'wiring: 渲染入口没统一合并面板声明');
  check(hub.includes('PanelPainter.paint(graphics, panel.rect(), panel.mode())'),
    'wiring: 渲染入口没按声明统一画板（矩形 + 画法）');
  check(hub.indexOf('PanelMerge.merge') < hub.indexOf('PanelPainter.paint('),
    'wiring: 先画后合并（贴边缝会留两条边框）');
  check(hub.indexOf('PanelPainter.paint(') < hub.indexOf('PanelPainter.paintSlot'),
    'wiring: 格底应在面板之后画（否则被板身盖住）');
  check(hub.includes('component.placeSlots(screen)') && hub.includes('component.declare(screen)'),
    'wiring: 渲染入口没按「先摆槽位、再收声明」的顺序调部件');
  check(hub.includes('new ContainerUpperPanelComponent()'),
    'wiring: 渲染入口没挂原版上半部分外框');
  check(hub.indexOf('new ContainerUpperPanelComponent()') < hub.indexOf('new InventoryPanelsComponent()'),
    'wiring: 上半部分外框应在悬浮板之前画（叠放顺序）');
  check(!hub.includes('new ContainerFloorComponent()'),
    'wiring: 渲染入口还引用已删除的底边条部件');
  check(hub.includes('new InventoryPanelsComponent()'),
    'wiring: 渲染入口没挂物品栏板 / 升级板');
  check(!hub.includes('PlayerInventoryFloatComponent') && !hub.includes('UpgradePanelComponent'),
    'wiring: 渲染入口还引用已删除的旧部件');

  const upgrade = readJava(path.join('client', 'InventoryPanelsComponent.java'));
  check(upgrade.includes('SidePanelLayout.upgrades()'),
    'wiring: 升级板没取浮板布局（SidePanelLayout.upgrades()）');
  check(upgrade.includes('upgrades.place(view, screen.getXSize())'),
    'wiring: 升级板没把偏移落到槽位（placeSlots）');
  check(upgrade.includes('board.offsetX()') && upgrade.includes('board.offsetY()'),
    'wiring: 升级板没跟着物品栏板的偏移联动');
  check(upgrade.includes('upgrades.offsetX()') && upgrade.includes('upgrades.offsetY()'),
    'wiring: 升级板底板没用拖动偏移');
  check(upgrade.includes('Panel.FLOATING'),
    'wiring: 两块板没有明确层级/画法（合并规则按两者过滤）');

  // 拖动入口只在 hud-layout-pack 的 F8 里：本包自己再收鼠标事件就会有两份状态。
  const layout = readJava(path.join('client', 'SidePanelLayout.java'));
  check(!layout.includes('EVENT_BUS'),
    'wiring: SidePanelLayout 又自己挂了鼠标事件（拖动入口应只在 F8 编辑模式）');
  check(layout.includes('UiEditTargets.register'),
    'wiring: SidePanelLayout 没有登记 F8 编辑目标');
  check(layout.includes('"storage"') && layout.includes('"upgrades"'),
    'wiring: 浮板 id 不是 storage / upgrades（ui-layout.json 的键）');
  check(layout.includes('SidePanel.slotX(guiWidth, containerSlot) + offsetX()') &&
    layout.includes('SidePanel.slotY(view.hotbarY(), containerSlot) + offsetY()'),
    'wiring: place 没把浮板偏移加到槽位上（或纵向没跟这一屏的快捷栏行走）');
  // 两块板串联拖动：升级板挂在快捷栏下方，物品栏板一动它必须跟着走。
  check(layout.includes('int linkX = STORAGE.offsetX() + offsetX();')
    && layout.includes('int linkY = STORAGE.offsetY() + offsetY();'),
    'wiring: 升级板槽位没叠加物品栏板的偏移（拖动不联动）');
  check(layout.includes('screen.getGuiLeft() + SidePanel.boardX(screen.getXSize()) + STORAGE.offsetX()'),
    'wiring: 升级板的编辑框基准没跟物品栏板联动（或板 x 没跟面板宽走）');
  // 板 x 由面板宽推出：附魔台 256、信标 230 都得贴在各自面板右边。
  check(layout.includes('SidePanel.boardX(screen.getXSize())'),
    'wiring: 物品栏板 x 没跟面板宽走（多宽度的原版屏会错位）');
  check(layout.includes('SidePanel.boardRows(view.upgrades())'),
    'wiring: 物品栏板的编辑框高度没跟解锁行数走');
  check(layout.includes('SidePanel.boardY(hotbarY, rows)'),
    'wiring: 物品栏板的编辑框顶边没跟板顶 / 这一屏快捷栏行走');
  check(layout.includes('view.itemSlots()'),
    'wiring: place 没遍历全部 0–44 物品格');
  check(layout.includes('view.upgradeSlots()'),
    'wiring: place 没遍历升级槽');
  check(!layout.includes('STORAGE_ROWS; row++'),
    'wiring: 物品栏板又被拆回多块（不应再按行建编辑目标）');

  const pack = readJava('ContainerOverhaulPack.java');
  check(pack.includes('ContainerUiRenderer.init()'),
    'wiring: 包没挂容器界面渲染入口（ContainerUiRenderer.init()）');
  check(pack.includes('SidePanelLayout.register()'),
    'wiring: 包没登记浮板（SidePanelLayout.register()）');
  check(pack.includes('ContainerCut.register()'),
    'wiring: 包没登记截底（ContainerCut.register()）');
  check(pack.includes('InventoryCapacityPolicy.register()'),
    'wiring: 包没注册解锁策略（InventoryCapacityPolicy.register()）');
  check(pack.includes('InventoryMigration.install()'),
    'wiring: 包没安装旧存档迁移（InventoryMigration.install()）');

  // 创造屏共用玩家的 inventoryMenu：必须有关屏闸门，否则悬浮板 / 截底会漏进创造物品栏。
  check(pack.includes('ContainerScreenGate.install()'),
    'wiring: 包没装创造屏闸门（ContainerScreenGate.install()）');
  const gate = readJava(path.join('client', 'ContainerScreenGate.java'));
  check(gate.includes('CreativeModeInventoryScreen'),
    'wiring: 闸门没排除创造模式物品栏');
  check(gate.includes('SidePanelMounts.setClientGate'),
    'wiring: 闸门没接到 SidePanelMounts.setClientGate');
  const mounts = readJava(path.join('ui', 'SidePanelMounts.java'));
  check(mounts.includes('clientGate.test(menu)'),
    'wiring: SidePanelMounts.view 没经过客户端闸门');

  // 外推范围：明确的菜单类白名单（不按「有 Inventory 槽位」猜，模组屏/坐骑栏会混进来）。
  check(mounts.includes('SUPPORTED.stream().noneMatch'),
    'mounts: 挂载范围不再是明确的菜单类白名单');
  for (const menu of ['InventoryMenu.class', 'ChestMenu.class', 'ShulkerBoxMenu.class',
    'HopperMenu.class', 'DispenserMenu.class', 'CraftingMenu.class', 'AbstractFurnaceMenu.class',
    'BrewingStandMenu.class', 'EnchantmentMenu.class', 'AnvilMenu.class', 'GrindstoneMenu.class',
    'LoomMenu.class', 'StonecutterMenu.class', 'CartographyTableMenu.class', 'SmithingMenu.class',
    'CrafterMenu.class', 'BeaconMenu.class']) {
    check(mounts.includes(menu), `mounts: 白名单漏了 ${menu}`);
  }
  check(!mounts.includes('MerchantMenu') && !mounts.includes('HorseInventoryMenu'),
    'mounts: 商人屏 / 坐骑屏不能挂（商人交易列表与物品栏同一纵向带，无法横切）');
  // 菜单构造时屏还不存在，补挂槽位的初值只能用默认宽度；真实位置由 place() 每帧重摆。
  check(mounts.includes('SidePanel.slotX(SidePanel.DEFAULT_GUI_WIDTH, containerSlot)')
    && mounts.includes('SidePanel.upgradeSlotX(SidePanel.DEFAULT_GUI_WIDTH, col)'),
    'mounts: 补挂槽位的初值没按默认面板宽算');
  // 板底钉的是这一屏自己的快捷栏：各屏位置不同（漏斗 109、信标 195、6 行箱子更下），
  // 不能写死标准屏的 142。
  check(mounts.includes('PlayerInventoryLayout.isHotbarSlot(slot.getContainerSlot())')
    && mounts.includes('Math.min(top, slot.y)'),
    'mounts: hotbarY 没从这一屏原版快捷栏槽位读出');
  check(mounts.includes('int hotbarY, int cutHeight'),
    'mounts: View 没带 hotbarY / cutHeight（渲染侧拿不到这一屏的快捷栏位置与截线）');
  // 截线同样在挂载时按「玩家主栏（9–35）最高顶边 - 1」算好，不按屏硬编码。
  check(mounts.includes('SidePanel.isVanillaMainSlot(slot.getContainerSlot())')
    && mounts.includes('Math.min(mainTop, slot.y)') && mounts.includes('mainTop - 1'),
    'mounts: 截线没按这一屏原版主栏槽位位置算');
  const floatComponent = readJava(path.join('client', 'InventoryPanelsComponent.java'));
  check(floatComponent.includes('view.hotbarY()'),
    'float: 底板没钉在这一屏的快捷栏行上');

  for (const stale of ['side_panel.json', 'storage_row.json', 'upgrade_row.json',
    'board.json', 'base.json', 'floor_176.json', 'floor_230.json', 'floor_256.json',
    'panel_skin_docked.json']) {
    check(!fs.existsSync(path.join(here, stale)), `wiring: 旧 spec ${stale} 还在`);
  }
  for (const stale of ['side_panel.png', 'storage_row.png', 'upgrade_row.png',
    'board.png', 'base.png', 'floor_176.png', 'floor_230.png', 'floor_256.png',
    'panel_skin_docked.png']) {
    check(!fs.existsSync(path.join(spriteDir, stale)), `wiring: 旧贴图 ${stale} 还在`);
  }
}

verifyBoards();
verifyDeclaration();
verifyPanels();
verifyUpper();
verifyPalette();
verifyLadder();
verifyWiring();

if (failures.length > 0) {
  for (const f of failures) console.error('FAIL: ' + f);
  console.error(`\n${failures.length} 项失败 / 共 ${checks} 项检查`);
  process.exit(1);
}
console.log(`OK: ${checks} 项检查全部通过（整板 PNG = spec 渲染结果，格子 = 原版精灵；板常量 = spec）`);
