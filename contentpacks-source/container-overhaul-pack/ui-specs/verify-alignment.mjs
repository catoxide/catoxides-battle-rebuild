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
function verifyBlits(screenName, source) {
  const calls = source.match(/\.blit\([^()]*\)/g) ?? [];
  check(calls.length === 1, `${screenName}: 整板应共用一次 blit，实际 ${calls.length} 次`);
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

// ---------- 悬浮物品栏部件 + 截底 ----------

function verifyFloat() {
  const component = readJava(path.join('client', 'PlayerInventoryFloatComponent.java'));
  check(component.includes('implements ContainerUiComponent'),
    'float: 悬浮物品栏不是独立部件（没实现 ContainerUiComponent）');
  check(component.includes('"textures/gui/board_" + rows + ".png"'),
    'float: 贴图不是按行数取 board_N.png');
  check(component.includes('BOARD_TEXTURES[rows]'),
    'float: 没有按行数选整板贴图');
  check(component.includes('SidePanel.boardRows(view.upgrades())'),
    'float: 板行数不是由解锁的升级槽决定');
  check(component.includes('SidePanel.ROW_WIDTH, SidePanel.boardHeight(rows)'),
    'float: 不是按「板宽 × 板高(行数)」整张画');
  check(component.includes('SidePanel.boardY(view.hotbarY(), rows)'),
    'float: 板顶没用 boardY(view.hotbarY(), rows)（快捷栏行要钉在这一屏的原版位置）');
  check(component.includes('board.place(view, screen.getXSize())'),
    'float: 没把偏移/坐标落到槽位（place）');
  check(component.includes('board.offsetX()') && component.includes('board.offsetY()'),
    'float: 底板没用拖动偏移');
  check(component.includes('ContainerUiRenderer.blitBoard'),
    'float: 没走整板 1:1 画');
  check(!component.includes('slot.png'),
    'float: 还在盖独立 slot.png（格子应烘进整板贴图）');

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

// ---------- 截底的底边条：9px 原版面板，只取最底下 4 行 ----------

function pixelAt(img, x, y) {
  const a = (y * img.width + x) * 4;
  return [img.data[a], img.data[a + 1], img.data[a + 2], img.data[a + 3]];
}

function hexToRgba(hex) {
  const n = parseInt(hex.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255, 255];
}

function verifyFloor() {
  const lib = readJson(path.join(libRoot, 'elements.json'));
  const body = hexToRgba(lib.roles.body);
  const dark = hexToRgba(lib.roles.bevelDark);
  const border = hexToRgba(lib.roles.border);
  const roleAt = [
    // 源图 v=5..8：body / 暗棱 / 暗棱 / 黑边（vanilla/panel 的 bottom 边）
    { y: 5, color: body, name: 'body' },
    { y: 6, color: dark, name: 'bevelDark' },
    { y: 7, color: dark, name: 'bevelDark' },
    { y: 8, color: border, name: 'border' },
  ];

  const component = readJava(path.join('client', 'ContainerFloorComponent.java'));
  const widths = (component.match(/WIDTHS = \{([^}]*)\}/) ?? [])[1];
  check(widths === '176, 230, 256',
    `floor: 底边条只做原版实际存在的三种宽度 176/230/256，实际 [${widths}]`);
  check(component.includes('ScreenCuts.visibleHeight(screen)'),
    'floor: 底边条没用统一登记的可见高度');
  check(component.includes('screen.getGuiTop() + visible'),
    'floor: 底边条画在可见高度往下（不能盖住没被裁掉的内容）');
  check(component.includes('screen.getXSize()'),
    'floor: 底边条宽度没跟容器屏原图宽度走');

  for (const width of [176, 230, 256]) {
    const name = `floor_${width}`;
    const specPath = path.join(here, `${name}.json`);
    const pngPath = path.join(spriteDir, `${name}.png`);
    check(fs.existsSync(specPath), `${name}: 缺少 spec ${name}.json`);
    check(fs.existsSync(pngPath), `${name}: 缺少生成贴图 ${name}.png`);
    if (!fs.existsSync(specPath) || !fs.existsSync(pngPath)) continue;

    const spec = readJson(specPath);
    check(spec.canvas[0] === width && spec.canvas[1] === 9,
      `${name}: canvas ${spec.canvas.join('×')} ≠ ${width}×9`);
    const panel = layer(spec, 'panel');
    check(panel.type === 'nine_slice' && panel.rect.join(',') === `0,0,${width},9`,
      `${name}: panel 应铺满 ${width}×9，实际 ${panel.rect}`);
    checkReproducible(name, specPath, pngPath);

    const img = png.decode(fs.readFileSync(pngPath));
    check(img.width === width && img.height === 9, `${name}: PNG ${img.width}×${img.height} ≠ ${width}×9`);
    for (const { y, color, name: role } of roleAt) {
      check(pixelAt(img, 100, y).join(',') === color.join(','),
        `${name}: 第 ${y} 行中线不是 ${role} 色（底边 4 行必须是原版面板 bottom 边）`);
    }
    // 底角要内凹：黑边那一行的最左边应是透明，不能是一条直边。
    check(pixelAt(img, 0, 8)[3] === 0, `${name}: 底边左下角没有内凹（原版面板底角）`);
  }

  const hub = readJava(path.join('client', 'ContainerUiRenderer.java'));
  check(hub.includes('new ContainerFloorComponent()'),
    'floor: 渲染入口没挂底边条');
  check(hub.indexOf('new ContainerFloorComponent()') < hub.indexOf('new PlayerInventoryFloatComponent()'),
    'floor: 底边条应在悬浮板之前画（叠放顺序）');
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
  verifyBlits('ContainerUiRenderer.java', hub);
  check(hub.includes('ContainerUiComponent'),
    'wiring: 渲染入口没按部件接口遍历');
  // 部件各自判断出场：入口若按侧面板挂载状态提前返回，会把所有部件一起带走。
  check(!hub.includes('SidePanelMounts'),
    'wiring: 渲染入口还看侧面板挂载状态（部件应各自判断出场）');
  check(!hub.includes('drawBoard('),
    'wiring: 渲染入口还在九宫格切图（整板应 1:1 画）');
  check(hub.includes('new PlayerInventoryFloatComponent()') && hub.includes('new UpgradePanelComponent()'),
    'wiring: 渲染入口没挂悬浮物品栏 / 升级板');
  check(!hub.includes('StorageBoardComponent') && !hub.includes('PlayerInventoryBaseComponent'),
    'wiring: 渲染入口还引用已删除的旧部件');

  const upgrade = readJava(path.join('client', 'UpgradePanelComponent.java'));
  check(upgrade.includes('implements ContainerUiComponent'),
    'wiring: 升级板不是独立部件');
  check(upgrade.includes('textures/gui/upgrade.png'), 'wiring: 升级板没引用 upgrade.png');
  check(!upgrade.includes('slot.png'),
    'wiring: 升级板还在盖独立 slot.png（格子应烘进整板贴图）');
  check(upgrade.includes('SidePanelLayout.upgrades()'),
    'wiring: 升级板没取浮板布局（SidePanelLayout.upgrades()）');
  check(upgrade.includes('upgrades.place(view, screen.getXSize())'),
    'wiring: 升级板没把偏移落到槽位（place）');
  check(upgrade.includes('SidePanelLayout.storage().offsetX()')
    && upgrade.includes('SidePanelLayout.storage().offsetY()'),
    'wiring: 升级板没跟着物品栏板的偏移联动');
  check(upgrade.includes('upgrades.offsetX()') && upgrade.includes('upgrades.offsetY()'),
    'wiring: 升级板底板没用拖动偏移');
  check(upgrade.includes('SidePanel.UPGRADE_WIDTH, SidePanel.UPGRADE_HEIGHT'),
    'wiring: 升级板不是按自身尺寸整张画');
  check(upgrade.includes('SidePanel.upgradePanelY(view.hotbarY())'),
    'wiring: 升级板 y 没跟这一屏的快捷栏行走');
  check(upgrade.includes('ContainerUiRenderer.blitBoard'),
    'wiring: 升级板没走整板 1:1 画');

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
  const floatComponent = readJava(path.join('client', 'PlayerInventoryFloatComponent.java'));
  check(floatComponent.includes('view.hotbarY()'),
    'float: 底板没钉在这一屏的快捷栏行上');

  for (const stale of ['side_panel.json', 'storage_row.json', 'upgrade_row.json',
    'board.json', 'slot.json', 'base.json']) {
    check(!fs.existsSync(path.join(here, stale)), `wiring: 旧 spec ${stale} 还在`);
  }
  for (const stale of ['side_panel.png', 'storage_row.png', 'upgrade_row.png',
    'board.png', 'slot.png', 'base.png']) {
    check(!fs.existsSync(path.join(spriteDir, stale)), `wiring: 旧贴图 ${stale} 还在`);
  }
}

verifyBoards();
verifyFloat();
verifyFloor();
verifyLadder();
verifyWiring();

if (failures.length > 0) {
  for (const f of failures) console.error('FAIL: ' + f);
  console.error(`\n${failures.length} 项失败 / 共 ${checks} 项检查`);
  process.exit(1);
}
console.log(`OK: ${checks} 项检查全部通过（整板 PNG = spec 渲染结果，格子 = 原版精灵；板常量 = spec）`);
