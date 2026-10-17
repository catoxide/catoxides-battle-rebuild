/**
 * bbmodel.js 测试（2026-10 坐标语义重写后）：
 *  1. 往返：spark 几何 → bbmodel v5 → 再导入，结构一致
 *  2. 往返：hcar 真实文件 → 导入 → 导出 → 再导入，几何一致（含 per-face UV 角点精确）
 *  3. hcar 结构：层级/数量/pivot 直接映射（绝对坐标 X 翻转）
 *  4. simple UV 还原 + mirror 变体
 *  5. locator / 无 outliner 扁平退化
 * 运行：node tools/contentpack-editor/model-editor/test/bbmodel.test.js
 * （hcar 实档在 TACZ 仓库；缺失时自动跳过第 2/3 段）
 */
const fs = require('fs');
const path = require('path');
const { importBBModel, exportBBModel, sniffModelFormat, _internals } = require('../core/bbmodel.js');

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

const EPS = 1e-3;
let pass = 0;
let fail = 0;

function check(label, cond, detail) {
    if (cond) {
        pass++;
    } else {
        fail++;
        console.error(`  ✗ ${label}${detail ? ' — ' + detail : ''}`);
    }
}
const r3 = (v) => (Array.isArray(v) ? v.map((x) => Math.round(x * 1000) / 1000) : v);

// ---------- 1. spark → bbmodel → spark 往返 ----------

function makeSample() {
    return {
        identifier: 'geometry.test',
        formatVersion: '1.12.0',
        textureWidth: 128,
        textureHeight: 128,
        bones: [
            {
                name: 'root', parent: null, pivot: [0, 24, 0], rotation: [0, 0, 0], scale: [1, 1, 1],
                mirror: false,
                cubes: [
                    { origin: [0, 4, 0], size: [4, 20, 4], uv: [0, 0], inflate: 0.5, pivot: [0, 0, 0], rotation: [0, 0, 0] },
                ],
                locators: [],
                children: ['torso'],
            },
            {
                name: 'torso', parent: 'root', pivot: [0, 24, 0], rotation: [0, 30, 0], scale: [1, 1, 1],
                mirror: false,
                cubes: [
                    { origin: [0, 12, 0], size: [8, 12, 4], uv: [16, 0], inflate: 0, pivot: [0, 0, 0], rotation: [0, 0, 0] },
                    {
                        origin: [3, 10, -1], size: [2, 2, 2],
                        uv: {
                            north: { uv: [10, 10], uv_size: [2, 2] },
                            south: { uv: [20, 10], uv_size: [2, 2] },
                            east: { uv: [12, 10], uv_size: [2, 2] },
                            west: { uv: [12, 10], uv_size: [2, 2] },
                            up: { uv: [12, 12], uv_size: [2, 2] },
                            down: { uv: [12, 14], uv_size: [2, 2] },
                        },
                        inflate: 0, pivot: [3, 11, -1], rotation: [10, -20, 35],
                    },
                ],
                locators: [{ name: 'attach_1', offset: [2, 18, 3], rotation: [0, 45, 0] }],
                children: ['arm_l', 'arm_r'],
            },
            { name: 'arm_l', parent: 'torso', pivot: [-5, 24, 0], rotation: [15, 0, 0], scale: [1, 1, 1], mirror: false, cubes: [], locators: [], children: ['hand_l'] },
            { name: 'hand_l', parent: 'arm_l', pivot: [-5, 12, 0], rotation: [0, 0, 0], scale: [1, 1, 1], mirror: false, cubes: [], locators: [], children: [] },
            {
                name: 'arm_r', parent: 'torso', pivot: [5, 24, 0], rotation: [0, 0, 0], scale: [1, 1, 1], mirror: true,
                cubes: [{ origin: [6, 12, 0], size: [4, 12, 4], uv: [32, 0], inflate: 0, pivot: [0, 0, 0], rotation: [0, 0, 0] }],
                locators: [], children: [],
            },
            {
                name: 'head', parent: null, pivot: [0, 32, 0], rotation: [0, 0, 0], scale: [1, 1, 1], mirror: false,
                cubes: [{ origin: [-4, 24, -4], size: [8, 8, 8], uv: [32, 0], inflate: 0, pivot: [0, 0, 0], rotation: [0, 0, 0] }],
                locators: [], children: [],
            },
        ],
    };
}

function normalize(geom) {
    const map = {};
    const childMap = {};
    for (const b of geom.bones) {
        map[b.name] = b;
        if (b.parent) (childMap[b.parent] = childMap[b.parent] || []).push(b.name);
    }
    const out = { textureWidth: geom.textureWidth, textureHeight: geom.textureHeight, bones: [] };
    function sortName(n) {
        const b = map[n];
        if (!b) return;
        out.bones.push({
            name: b.name,
            parent: b.parent,
            pivot: r3(b.pivot),
            rotation: r3(b.rotation),
            mirror: !!b.mirror,
            cubes: (b.cubes || []).map((c) => ({
                origin: r3(c.origin),
                size: r3(c.size),
                inflate: c.inflate || 0,
                pivot: r3(c.pivot),
                rotation: r3(c.rotation),
                uv: Array.isArray(c.uv)
                    ? ['simple', r3(c.uv[0]), r3(c.uv[1])]
                    : ['perface', JSON.stringify(Object.keys(c.uv).sort().map((f) => [f, r3(c.uv[f].uv), r3(c.uv[f].uv_size), c.uv[f].uv_rotation || 0]))],
            })),
            locators: (b.locators || []).map((l) => [l.name, r3(l.offset), r3(l.rotation)]),
            children: (childMap[n] || []).slice().sort(),
        });
        for (const c of (childMap[n] || []).slice().sort()) sortName(c);
    }
    for (const r of geom.bones.filter((b) => !b.parent).map((b) => b.name).sort()) sortName(r);
    return out;
}

function deepEq(label, a, b) {
    check(label, JSON.stringify(a) === JSON.stringify(b), `\n    a=${JSON.stringify(a)}\n    b=${JSON.stringify(b)}`);
}

console.log('== 1. spark → bbmodel → spark 往返 ==');
const sample = makeSample();
const bbText = exportBBModel(sample, { name: 'test' });
const bbObj = JSON.parse(bbText);
check('meta v5', bbObj.meta.format_version === '5.0' && bbObj.meta.model_format === 'bedrock');
check('groups = bones', bbObj.groups.length === sample.bones.length);
check('elements = cubes+locators', bbObj.elements.length === 5 + 1, `got ${bbObj.elements.length}`);
check('outliner 顶层 = 2 根', bbObj.outliner.length === 2);
// 子组必须是对象节点（递归查找）
function findNode(list, uuid) {
    for (const it of list || []) {
        if (!it || typeof it === 'string') continue;
        if (it.uuid === uuid) return it;
        const hit = findNode(it.children, uuid);
        if (hit) return hit;
    }
    return null;
}
const torsoGrp = bbObj.groups.find((g) => g.name === 'torso');
const torsoNode = findNode(bbObj.outliner, torsoGrp.uuid);
check('torso 对象节点', !!torsoNode && Array.isArray(torsoNode.children));
const armLGrp = bbObj.groups.find((g) => g.name === 'arm_l');
const armLNode = torsoNode && findNode(torsoNode.children, armLGrp.uuid);
check('arm_l 嵌套对象节点', !!armLNode);

const reparsed = importBBModel(bbText, { name: 'test' });
check('导入 warnings 为空', reparsed.warnings.length === 0, reparsed.warnings.join(' | '));
deepEq('texture', [reparsed.geometry.textureWidth, reparsed.geometry.textureHeight], [128, 128]);
const g1 = normalize(sample);
const g2 = normalize(reparsed.geometry);
deepEq('层级', g2.bones.map((b) => [b.name, b.parent]), g1.bones.map((b) => [b.name, b.parent]));
deepEq('pivot', g2.bones.map((b) => b.pivot), g1.bones.map((b) => b.pivot));
deepEq('rotation', g2.bones.map((b) => b.rotation), g1.bones.map((b) => b.rotation));
deepEq('mirror', g2.bones.map((b) => b.mirror), g1.bones.map((b) => b.mirror));
deepEq('cubes', g2.bones.map((b) => b.cubes), g1.bones.map((b) => b.cubes));
deepEq('locators', g2.bones.map((b) => b.locators), g1.bones.map((b) => b.locators));

// 导出文件自身：pivot = X 翻转（直接映射，绝对坐标）
const rootGrp = bbObj.groups.find((g) => g.name === 'root');
deepEq('group origin = flip(pivot)', r3(rootGrp.origin), [-0, 24, 0]);
const armRGrp = bbObj.groups.find((g) => g.name === 'arm_r');
check('arm_r mirror_uv=true', armRGrp.mirror_uv === true);
check('torso 旋转 X/Y 反号', JSON.stringify(bbObj.groups.find((g) => g.name === 'torso').rotation) === JSON.stringify([0, -30, 0]));
// cube 盒 X 镜像交换两端（Blockbench bedrock.js#compileCube 权威规则）：
//   from.x = −(origin.x + size.x)，to.x = −origin.x；y/z 不变
const el0 = bbObj.elements.find((e) => e.name === 'root_1');
deepEq('element from.x = −(cube.origin.x+size.x)，y/z = origin/size', r3(el0.from), [-4, 4, 0]);
deepEq('element to.x = −cube.origin.x，y/z = origin+size', r3(el0.to), [0, 24, 4]);
// per-face up/down 角点序
const elRot = bbObj.elements.find((e) => e.name === 'torso_2');
check('旋转 cube 有 origin+rotation', JSON.stringify(elRot.origin) === JSON.stringify([-3, 11, -1]) && JSON.stringify(elRot.rotation) === JSON.stringify([-10, 20, 35]));
check('up 面 4v = [x+w,y+h,x,y]', JSON.stringify(elRot.faces.up.uv) === JSON.stringify([12 + 2, 12 + 2, 12, 12]), JSON.stringify(elRot.faces.up.uv));

// ---------- 2. hcar 往返 ----------
console.log('== 2. hcar 真实文件 导入→导出→再导入 ==');
const hcarPath = path.join(REPO_ROOT, '..', 'allmobstacz', 'TACZ-1.21.1-neoforge-1.21.1', 'src', 'main', 'reserve repository', 'hcar_model', 'hcar.bbmodel');
if (fs.existsSync(hcarPath)) {
    const text = fs.readFileSync(hcarPath, 'utf8');
    check('sniff = bbmodel', sniffModelFormat(text) === 'bbmodel');
    const r1 = importBBModel(text, { name: 'humanoid_composite' });
    const gA = normalize(r1.geometry);
    check('bones = 110', r1.geometry.bones.length === 110, `got ${r1.geometry.bones.length}`);
    const cubeCount = r1.geometry.bones.reduce((n, b) => n + (b.cubes ? b.cubes.length : 0), 0);
    check('cubes = 1114', cubeCount === 1114, `got ${cubeCount}`);
    check('顶层骨 = 4（outliner 根）', r1.geometry.bones.filter((b) => !b.parent).length === 4);
    const out = r1.geometry.bones.filter((b) => !b.parent).map((b) => b.name).sort().join(',');
    check('顶层 = camera,positioning,root,view', out === 'camera,positioning,root,view', out);
    // warnings 概况（无 outliner 引用缺失等）
    check('无严重 warnings', r1.warnings.filter((w) => !/无 UV/.test(w)).length === 0, r1.warnings.slice(0, 5).join(' | '));

    // 导出再导入
    const bbOut = exportBBModel(r1.geometry, { name: 'humanoid_composite' });
    const r2 = importBBModel(bbOut, { name: 'humanoid_composite' });
    const gB = normalize(r2.geometry);
    deepEq('往返层级', gB.bones.map((b) => [b.name, b.parent]), gA.bones.map((b) => [b.name, b.parent]));
    deepEq('往返 pivot', gB.bones.map((b) => b.pivot), gA.bones.map((b) => b.pivot));
    deepEq('往返 cubes', gB.bones.map((b) => b.cubes), gA.bones.map((b) => b.cubes));
    check('往返无新增 warnings', r2.warnings.length === 0, r2.warnings.slice(0, 5).join(' | '));

    // 导出文件结构与 hcar 对账（组数/元素数/顶层）
    const o2 = JSON.parse(bbOut);
    check('导出 groups=110', o2.groups.length === 110);
    check('导出 elements=1114', o2.elements.length === 1114);
    check('导出 outliner 顶层=4', o2.outliner.length === 4);

    // 抽验：root 组 origin 直接映射
    const hcar = JSON.parse(text);
    const hRoot = hcar.groups.find((g) => g.name === 'root');
    const eRoot = gA.bones.find((b) => b.name === 'root');
    deepEq('root pivot = flip(origin)', r3(eRoot.pivot), [-hRoot.origin[0], hRoot.origin[1], hRoot.origin[2]]);
    // 抽验 per-face up 面映射公式：[33.5,38,32,31] → uv=[32,31], size=[1.5,7]
    const hUp = hcar.elements.find((e) => e.type === 'cube' && e.faces && Array.isArray(e.faces.up && e.faces.up.uv) && e.faces.up.uv[0] === 33.5);
    if (hUp) {
        let ok = false;
        for (const b of r1.geometry.bones) {
            for (const c of b.cubes || []) {
                if (!Array.isArray(c.uv) && c.uv.up) {
                    const u = c.uv.up;
                    if (Math.abs(u.uv[0] - 32) < EPS && Math.abs(u.uv[1] - 31) < EPS && Math.abs(u.uv_size[0] - 1.5) < EPS && Math.abs(u.uv_size[1] - 7) < EPS) ok = true;
                }
            }
        }
        check('up 面角点映射 [33.5,38,32,31]→uv[32,31]+size[1.5,7]', ok);
    } else {
        check('hcar 含 up=[33.5,38,32,31] 样本', false);
    }
    console.log(`  hcar: bones=${r1.geometry.bones.length} cubes=${cubeCount} warnings=${r1.warnings.length}`);
} else {
    console.log(`  (跳过: 未找到 ${hcarPath})`);
}

// ---------- 3. simple UV 还原 ----------
console.log('== 3. simple UV 还原（含 mirror 变体） ==');
{
    const { boxFace4v } = _internals;
    const size = [8, 12, 4];
    const mk = (uv, mirror) => {
        const t = boxFace4v(uv[0], uv[1], size, mirror);
        const faces = {};
        for (const f of Object.keys(t)) faces[f] = { uv: t[f], texture: 0 };
        return {
            meta: { format_version: '5.0', model_format: 'bedrock', box_uv: true },
            resolution: { width: 64, height: 64 },
            name: 't', model_identifier: 't',
            groups: [{ name: 'g', uuid: 'g1', origin: [0, 0, 0], export: true, mirror_uv: mirror }],
            elements: [{ name: 'c', type: 'cube', uuid: 'e1', from: [0, 0, 0], to: size.slice(), faces }],
            outliner: [{ uuid: 'g1', isOpen: false, children: ['e1'] }],
            textures: [{ name: 't', width: 64, height: 64, uuid: 'tx1' }],
        };
    };
    for (const mirror of [false, true]) {
        const m = mk([4, 6], mirror);
        const r = importBBModel(m, {});
        const c = r.geometry.bones[0].cubes[0];
        check(`mirror=${mirror} 还原 simple [4,6]`, Array.isArray(c.uv) && c.uv[0] === 4 && c.uv[1] === 6, JSON.stringify(c.uv));
        check(`mirror=${mirror} bone.mirror`, r.geometry.bones[0].mirror === mirror);
    }
    // 非布局表 → per-face
    const m3 = mk([4, 6], false);
    m3.elements[0].faces.north = { uv: [0, 0, 99, 99], texture: 0 };
    const r3x = importBBModel(m3, {});
    check('非布局表 → per-face', !Array.isArray(r3x.geometry.bones[0].cubes[0].uv));
}

// ---------- 4. locator / 扁平退化 ----------
console.log('== 4. locator 与无 outliner 扁平退化 ==');
{
    const m = {
        meta: { format_version: '5.0', model_format: 'bedrock', box_uv: false },
        resolution: { width: 64, height: 64 },
        name: 't', model_identifier: 't',
        groups: [{ name: 'g', uuid: 'g1', origin: [1, 2, 3], rotation: [10, 20, 30], export: true }],
        elements: [
            { name: 'c', type: 'cube', uuid: 'e1', from: [0, 0, 0], to: [4, 4, 4], faces: { north: { uv: [0, 0, 4, 4], texture: 0 }, south: { uv: [0, 0, 4, 4], texture: 0 }, east: { uv: [0, 0, 4, 4], texture: 0 }, west: { uv: [0, 0, 4, 4], texture: 0 }, up: { uv: [0, 0, 4, 4], texture: 0 }, down: { uv: [0, 0, 4, 4], texture: 0 } } },
            { name: 'loc1', type: 'locator', uuid: 'e2', position: [5, 6, 7], rotation: [1, 2, 3], export: true },
        ],
        outliner: [{ uuid: 'g1', isOpen: false, children: ['e1', 'e2'] }],
        textures: [{ name: 't', width: 64, height: 64, uuid: 'tx1' }],
    };
    const r = importBBModel(m, {});
    const b = r.geometry.bones[0];
    deepEq('locator offset flip', r3(b.locators[0].offset), [-5, 6, 7]);
    deepEq('locator rot flip', r3(b.locators[0].rotation), [-1, -2, 3]);
    deepEq('组 pivot flip', r3(b.pivot), [-1, 2, 3]);
    deepEq('组 rot flip', r3(b.rotation), [-10, -20, 30]);

    // 无 outliner：扁平退化 + warning
    const m2 = Object.assign({}, m, { outliner: undefined });
    const r2 = importBBModel(m2, {});
    check('无 outliner 有 warning', r2.warnings.some((w) => w.includes('outliner')));
    check('无 outliner 元素挂 __root', r2.geometry.bones.some((b2) => b2.name === '__root' && b2.cubes.length === 1));
    check('无 outliner 组仍导入', r2.geometry.bones.some((b2) => b2.name === 'g'));
}

// ---------- 5. 盒 X 镜像（真实 sword.bbmodel 数据回归） ----------
// 2026-10-18 真机对账：Blockbench 5.2.2 打开 sword.bbmodel 后渲染 x∈[-12,-8]（left 手），
// 本编辑器旧实现画到 x∈[-16,-12]（整体偏 −size.x）。下面的数值取自该文件 + Blockbench 实读。
console.log('== 5. 盒 X 镜像（Blockbench 对账） ==');
{
    const m = {
        meta: { format_version: '5.0', model_format: 'bedrock', box_uv: false },
        resolution: { width: 32, height: 32 },
        name: 'sword', model_identifier: 'sword',
        groups: [{ name: 'hands', uuid: 'g1', origin: [-11, 3, 8], rotation: [0, 0, 0], export: true }],
        elements: [{
            name: 'left', type: 'cube', uuid: 'e1',
            from: [-12, 3, 7], to: [-8, 15, 11], origin: [-11, 3, 8], rotation: [90, 0, 0],
            faces: { north: { uv: [0, 0, 4, 12], texture: 0 } },
        }],
        outliner: [{ uuid: 'g1', isOpen: false, children: ['e1'] }],
        textures: [{ name: 'sword', width: 32, height: 32, uuid: 'tx1' }],
    };
    const g = importBBModel(m, {}).geometry;
    const c = g.bones.find((b) => b.name === 'hands').cubes[0];
    // 编辑器（spark）原点 = 镜像后的最小角 = (−to.x, from.y, from.z) = (8, 3, 7)
    deepEq('导入 cube.origin = (−to.x, from.y, from.z)', r3(c.origin), [8, 3, 7]);
    deepEq('导入 cube.size 不变', r3(c.size), [4, 12, 4]);
    deepEq('导入 cube.pivot = flip(bb origin)', r3(c.pivot), [11, 3, 8]);
    deepEq('导入 cube.rotation = flipRot', r3(c.rotation), [-90, 0, 0]);

    // 再导出：from.x = −(o.x+s.x) = −12，to.x = −o.x = −8 → 与 Blockbench 实读一致
    const out = JSON.parse(exportBBModel(g, { name: 'sword' }));
    const e = out.elements.find((x) => x.type === 'cube');
    deepEq('导出 from 回到 Blockbench 坐标', r3(e.from), [-12, 3, 7]);
    deepEq('导出 to 回到 Blockbench 坐标', r3(e.to), [-8, 15, 11]);
    deepEq('导出 origin', r3(e.origin), [-11, 3, 8]);

    // 内嵌贴图（textures[].source dataURL）导入/导出各透传一次，Blockbench 才能看到贴图
    const PNG_1PX = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
    const m2 = JSON.parse(JSON.stringify(m));
    m2.textures = [{ name: 'sword.png', width: 32, height: 32, uuid: 'tx1', source: PNG_1PX }];
    const r2 = importBBModel(m2, {});
    check('导入内嵌贴图 dataURL', r2.texture && r2.texture.dataUrl === PNG_1PX && r2.texture.name === 'sword.png');
    const out2 = JSON.parse(exportBBModel(r2.geometry, { name: 'sword', texture: r2.texture }));
    check('导出贴图写回 textures[].source', out2.textures[0].source === PNG_1PX && out2.textures[0].name === 'sword.png');
    const outNoTex = JSON.parse(exportBBModel(r2.geometry, { name: 'sword' }));
    check('无贴图时不写 source', outNoTex.textures[0].source === undefined);
}

console.log(`\n结果: ${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
