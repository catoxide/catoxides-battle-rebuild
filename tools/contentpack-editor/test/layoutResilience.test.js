// layoutResilience.test.js - 布局韧性 / 顶层菜单的静态门禁
// 为什么要有：2026-10-27 实测 —— 窗口能缩到侧栏宽度之和以下，编辑区被压成 0 且
// body overflow:hidden 不留横向滚动条（那部分界面不可达）；顶层菜单是 Electron 通用
// 默认菜单，没有任何应用动作。修好之后用这份静态断言防止回退。
// 只做文本级断言：真实像素行为由 CDP 探针人工复核，测试里不起 Electron。
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const main = fs.readFileSync(path.join(ROOT, 'main.js'), 'utf8');
const preload = fs.readFileSync(path.join(ROOT, 'preload.js'), 'utf8');
const appJs = fs.readFileSync(path.join(ROOT, 'src', 'app.js'), 'utf8');
const css = fs.readFileSync(path.join(ROOT, 'src', 'styles.css'), 'utf8');
const html = fs.readFileSync(path.join(ROOT, 'src', 'index.html'), 'utf8');

let passed = 0, failed = 0;
function test(name, fn) {
    try { fn(); passed++; console.log('  ✓ ' + name); }
    catch (e) { failed++; console.log('  ✗ ' + name + '\n      ' + e.message); }
}

/** 取 main.js 里每个 new BrowserWindow({ ... }) 的参数块 */
function windowOptionBlocks() {
    const out = [];
    const re = /new BrowserWindow\(\{/g;
    let m;
    while ((m = re.exec(main))) {
        const start = m.index + m[0].length - 1;
        let depth = 0, i = start;
        for (; i < main.length; i++) {
            if (main[i] === '{') depth++;
            else if (main[i] === '}') { depth--; if (depth === 0) break; }
        }
        out.push(main.slice(start, i + 1));
    }
    return out;
}

/** 取 styles.css 里某选择器的规则块 */
function cssRule(selector) {
    const i = css.indexOf(selector + ' {');
    assert.ok(i !== -1, 'styles.css 找不到规则: ' + selector);
    const j = css.indexOf('}', i);
    return css.slice(i, j + 1);
}

console.log('=== 布局韧性 / 菜单门禁测试 ===');

test('1. 每个 BrowserWindow 都声明了 minWidth/minHeight（窗口缩不进不可用区）', () => {
    const blocks = windowOptionBlocks();
    assert.ok(blocks.length >= 3, '应扫到主窗口/片段编辑器/模型编辑器三个窗口，实际 ' + blocks.length);
    for (const b of blocks) {
        assert.ok(/minWidth:\s*\d+/.test(b), '窗口缺 minWidth: ' + b.slice(0, 60));
        assert.ok(/minHeight:\s*\d+/.test(b), '窗口缺 minHeight: ' + b.slice(0, 60));
    }
});

test('2. 主窗口下限不低于三栏可用下限（960 = 260+329 侧栏默认 + 300 编辑区 + 余量）', () => {
    const mainBlock = windowOptionBlocks()[0];
    const mw = Number((mainBlock.match(/minWidth:\s*(\d+)/) || [])[1]);
    assert.ok(mw >= 960, '主窗口 minWidth 应 >= 960，实际 ' + mw);
});

test('3. 视口变小时侧栏会被钳制回可用区（resize 监听 + 钳制函数都在）', () => {
    assert.ok(/function clampPanelsToViewport\(\)/.test(appJs), 'app.js 缺 clampPanelsToViewport');
    assert.ok(/addEventListener\('resize',\s*clampPanelsToViewport\)/.test(appJs), '钳制没挂到 resize 上');
    assert.ok(/clampPanelsToViewport\(\);/.test(appJs), '启动时没跑一次钳制（持久化的大宽度在小屏上会压死编辑区）');
});

test('4. 钳制是会话级的：不落盘，小屏上的一次妥协不永久改掉用户拖出的宽度', () => {
    const i = appJs.indexOf('function clampPanelsToViewport()');
    const j = appJs.indexOf('window.addEventListener(\'resize\', clampPanelsToViewport');
    assert.ok(i !== -1 && j > i, '找不到钳制函数体范围');
    assert.ok(!/saveLayout/.test(appJs.slice(i, j)), '钳制里不该写 saveLayout');
});

test('5. 拖拽上限随视口走（固定 500 能在窄窗口里把编辑区拖成负宽）', () => {
    assert.ok(/document\.documentElement\.clientWidth - otherW - MIN_EDITOR - DIVIDERS/.test(appJs), 'onMove 缺视口感知的上限');
});

test('6. 顶栏右簇与编辑工具条允许换行（不换行就会溢出被裁）', () => {
    assert.ok(/flex-wrap:\s*wrap/.test(cssRule('.hdr-right')), '.hdr-right 缺 flex-wrap');
    assert.ok(/flex-wrap:\s*wrap/.test(cssRule('.editor-actions')), '.editor-actions 缺 flex-wrap');
    assert.ok(!/<div style="margin-left:auto/.test(html), '顶栏右簇又回到内联样式（CSS 里的 wrap 会够不着它）');
    assert.ok(/class="hdr-right"/.test(html), 'index.html 缺 hdr-right 类');
});

test('7. 编辑区内部双列布局有容器查询收单列（视口断点反映不了编辑区宽度）', () => {
    assert.ok(/container-type:\s*inline-size/.test(cssRule('.editor-panel')), '.editor-panel 缺 container-type');
    assert.ok(/@container \(max-width:/.test(css), '缺 @container 断点');
});

test('8. 顶层菜单是应用自己的：主进程建模板、动作下发、语言上报三件套都在', () => {
    assert.ok(/Menu\.setApplicationMenu\(Menu\.buildFromTemplate/.test(main), '主进程没装应用菜单（会退回 Electron 通用默认菜单）');
    assert.ok(/webContents\.send\('menu:action'/.test(main), '菜单动作没下发到渲染端');
    assert.ok(/ipcMain\.on\('menu:setLang'/.test(main), '渲染端切语言同步不到菜单');
    assert.ok(/onMenuAction:\s*\(cb\)\s*=>\s*ipcRenderer\.on\('menu:action'/.test(preload), 'preload 缺 onMenuAction');
    assert.ok(/notifyMenuLang:\s*\(lang\)\s*=>\s*ipcRenderer\.send\('menu:setLang'/.test(preload), 'preload 缺 notifyMenuLang');
});

test('9. 菜单动作在渲染端落到既有入口（不复制业务逻辑，也不新增编辑器导出面）', () => {
    assert.ok(/window\.api\.onMenuAction\(\(id\)/.test(appJs), 'app.js 没接菜单动作');
    for (const btn of ['btnDeployStatus', 'btnOpenHistory', 'btnOpenModelEditor', 'btnPackBuild']) {
        assert.ok(appJs.includes("clickBtn('" + btn + "')"), '菜单动作没复用按钮: ' + btn);
    }
});

console.log(`\n=== layoutResilience 测试: ${passed} passed, ${failed} failed ===`);
process.exit(failed ? 1 : 0);
