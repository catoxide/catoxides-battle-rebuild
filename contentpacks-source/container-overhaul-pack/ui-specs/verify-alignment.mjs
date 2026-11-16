// 侧面板的机械校验：贴图 ↔ spec ↔ Java 常量 三者对齐。
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

/** 从 Java 源码里取 `static final int NAME = 123;`，取不到就报错（避免静默通过）。 */
function javaInt(source, name, where) {
  const m = source.match(new RegExp(`static\\s+final\\s+int\\s+${name}\\s*=\\s*(\\d+)\\s*;`));
  if (!m) throw new Error(`无法从 ${where} 解析常量 ${name}`);
  return Number(m[1]);
}

function layer(spec, id) {
  const found = spec.layers.find((l) => l.id === id);
  if (!found) throw new Error(`${spec.name}: 缺少图层 ${id}`);
  return found;
}

const slotSprite = png.decode(fs.readFileSync(path.join(libRoot, 'sprites', 'slot.png')));
check(slotSprite.width === SLOT && slotSprite.height === SLOT, '元素库 slot 精灵不是 18×18');

function patchEquals(img, x, y) {
  for (let j = 0; j < SLOT; j++) {
    for (let i = 0; i < SLOT; i++) {
      const a = ((y + j) * img.width + (x + i)) * 4;
      const b = (j * SLOT + i) * 4;
      for (let k = 0; k < 4; k++) {
        if (img.data[a + k] !== slotSprite.data[b + k]) return false;
      }
    }
  }
  return true;
}

/** 每个格点/单点都应恰好是未改色的原版槽位精灵。 */
function checkSlotPatches(name, spec, img) {
  for (const l of spec.layers) {
    if (l.type === 'grid') {
      for (let r = 0; r < l.rows; r++) {
        for (let c = 0; c < l.cols; c++) {
          const x = l.origin[0] + c * l.pitch;
          const y = l.origin[1] + r * l.pitch;
          check(x + SLOT <= img.width && y + SLOT <= img.height,
            `${name}/${l.id}: 格点 (${x},${y}) 出画布`);
          check(patchEquals(img, x, y),
            `${name}/${l.id}: 槽位 (${x},${y}) 与原版 slot.png 不一致`);
        }
      }
    } else if (l.type === 'stamp' && l.stamp === 'vanilla/slot') {
      check(patchEquals(img, l.at[0], l.at[1]),
        `${name}/${l.id}: 单点 (${l.at[0]},${l.at[1]}) 与原版 slot.png 不一致`);
    }
  }
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
 * UV 只覆盖图的 256/W、256/H 比例的区域，像素被拉长（纵向通常比横向更明显）。
 */
function checkBlitTextureSize(screenName, source, spec, sizeArgs = ['this.imageWidth', 'this.imageHeight']) {
  const calls = source.match(/\.blit\([^()]*\)/g) ?? [];
  check(calls.length > 0, `${screenName}: 没找到 blit 调用，贴图尺寸检查失效`);
  const isVanillaSized = spec.canvas[0] === 256 && spec.canvas[1] === 256;
  for (const call of calls) {
    const args = call.slice(call.indexOf('(') + 1, -1).split(',').map((s) => s.trim());
    if (args.length === 7) {
      check(isVanillaSized,
        `${screenName}: blit 用 7 参重载（隐含 256×256），贴图却是 ${spec.canvas.join('×')}` +
        ` —— 会被拉成横向 256/${spec.canvas[0]}、纵向 256/${spec.canvas[1]}`);
    } else if (args.length >= 9) {
      check(args.at(-2) === sizeArgs[0] && args.at(-1) === sizeArgs[1],
        `${screenName}: blit 的 textureWidth/Height 应为 ${sizeArgs.join('/')}，实际 ${args.slice(-2).join(', ')}`);
    }
  }
}

// ---------- 侧面板：贴图格点 = Java 常量（挂到原版容器屏） ----------

function verifySidePanel() {
  const specPath = path.join(here, 'side_panel.json');
  const pngPath = path.join(spriteDir, 'side_panel.png');
  check(fs.existsSync(pngPath), `side_panel: 缺少生成贴图 ${pngPath}`);
  if (!fs.existsSync(pngPath)) return;

  const spec = readJson(specPath);
  const img = png.decode(fs.readFileSync(pngPath));
  check(img.width === spec.canvas[0] && img.height === spec.canvas[1],
    `side_panel: PNG ${img.width}×${img.height} 与 canvas ${spec.canvas.join('×')} 不符`);
  checkSlotPatches('side_panel', spec, img);
  checkReproducible('side_panel', specPath, pngPath);

  const side = readJava(path.join('ui', 'SidePanel.java'));
  const where = 'SidePanel.java';
  const width = javaInt(side, 'WIDTH', where);
  const height = javaInt(side, 'HEIGHT', where);
  const pitch = javaInt(side, 'SLOT_PITCH', where);
  const storageX = javaInt(side, 'STORAGE_X', where);
  const storageY = javaInt(side, 'STORAGE_Y', where);
  const storageCols = javaInt(side, 'STORAGE_COLS', where);
  const storageRows = javaInt(side, 'STORAGE_ROWS', where);
  const upgradeX = javaInt(side, 'UPGRADE_X', where);
  const upgradeY = javaInt(side, 'UPGRADE_Y', where);
  const upgradeCols = javaInt(side, 'UPGRADE_COLS', where);

  check(width === spec.canvas[0] && height === spec.canvas[1],
    `side_panel: Java 面板 ${width}×${height} ≠ canvas ${spec.canvas.join('×')}`);
  check(storageY + (storageRows - 1) * pitch + SLOT <= height,
    `side_panel: 暂存格超出面板高度（底边 ${storageY + (storageRows - 1) * pitch + SLOT} > ${height}）`);
  check(upgradeY + SLOT <= height,
    `side_panel: 升级格超出面板高度（底边 ${upgradeY + SLOT} > ${height}）`);

  const storage = layer(spec, 'storage');
  check(storage.origin[0] === storageX && storage.origin[1] === storageY,
    `side_panel/storage: spec origin ${storage.origin} ≠ Java (${storageX},${storageY})`);
  check(storage.pitch === pitch, `side_panel/storage: spec pitch ${storage.pitch} ≠ Java ${pitch}`);
  check(storage.cols === storageCols && storage.rows === storageRows,
    `side_panel/storage: spec ${storage.cols}×${storage.rows} ≠ Java ${storageCols}×${storageRows}`);

  const upgrade = layer(spec, 'upgrade_row');
  check(upgrade.origin[0] === upgradeX && upgrade.origin[1] === upgradeY,
    `side_panel/upgrade_row: spec origin ${upgrade.origin} ≠ Java (${upgradeX},${upgradeY})`);
  check(upgrade.pitch === pitch, `side_panel/upgrade_row: spec pitch ${upgrade.pitch} ≠ Java ${pitch}`);
  check(upgrade.cols === upgradeCols && upgrade.rows === 1,
    `side_panel/upgrade_row: spec ${upgrade.cols}×${upgrade.rows} ≠ Java ${upgradeCols}×1`);

  const renderer = readJava(path.join('client', 'SidePanelRenderer.java'));
  checkBlitTextureSize('SidePanelRenderer.java', renderer, spec, ['SidePanel.WIDTH', 'SidePanel.HEIGHT']);
  // 拖动偏移是槽位与底板的共同真源：渲染器漏掉任何一半，拖动后面板就会和格子分家。
  check(renderer.includes('SidePanelLayout.INSTANCE'),
    'side_panel: 渲染器没取布局实例（SidePanelLayout.INSTANCE）');
  check(renderer.includes('layout.offsetX()') && renderer.includes('layout.offsetY()'),
    'side_panel: 渲染器没用拖动偏移（layout.offsetX/Y）');
  check(renderer.includes('layout.place('),
    'side_panel: 渲染器没把偏移落到槽位（layout.place）');

  // 拖动入口只在 hud-layout-pack 的 F8 里：本包自己再收鼠标事件就会有两份状态。
  const layout = readJava(path.join('client', 'SidePanelLayout.java'));
  check(!layout.includes('EVENT_BUS'),
    'side_panel: SidePanelLayout 又自己挂了鼠标事件（拖动入口应只在 F8 编辑模式）');
  check(readJava('ContainerOverhaulPack.java').includes('UiEditTargets.register(SidePanelLayout.INSTANCE)'),
    'side_panel: 没登记成 F8 编辑目标（UiEditTargets.register）');
}

verifySidePanel();

if (failures.length > 0) {
  for (const f of failures) console.error('FAIL: ' + f);
  console.error(`\n${failures.length} 项失败 / 共 ${checks} 项检查`);
  process.exit(1);
}
console.log(`OK: ${checks} 项检查全部通过（贴图 = spec 渲染结果；格点 = 原版 slot 精灵；面板常量 = spec）`);
