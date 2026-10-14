// inspector.js - 属性面板（W3）：模型信息 + 骨骼/cube 属性 + locators + UV（简单/per-face）
// 经典 script（self.Inspector），零 three 依赖，DOM 直出。
// 数字输入在 'change'（Enter/失焦）提交 → api.onChange(label)（app 侧推 undo）。
// api: {
//   getModel(), getSelection(),
//   onChange(label)          // 数据已改，请求刷新（undo 由 app 处理）
// }
(function (globalScope) {
    const FACES = ['north', 'east', 'south', 'west', 'up', 'down'];
    const FACE_CN = { north: '前', east: '右', south: '背', west: '左', up: '上', down: '下' };

    function createInspector(container, api) {
        function label(text) {
            const d = document.createElement('div');
            d.className = 'ed-insp-label';
            d.textContent = text;
            return d;
        }
        function numInput(value, { step = 1, deg = false, intOnly = false } = {}) {
            const i = document.createElement('input');
            i.type = 'number';
            i.className = 'ed-insp-num';
            i.step = step;
            i.value = Math.round(value * 10000) / 10000;
            if (deg) { i.step = 1; }
            if (intOnly) { i.step = 1; }
            return i;
        }
        function readNum(i, fallback) {
            const v = parseFloat(i.value);
            return Number.isFinite(v) ? v : fallback;
        }

        /** 向量行：label + 3 输入；change 提交整组 */
        function vec3Row(parent, text, value, onCommit, { step = 1, deg = false, intOnly = false } = {}) {
            const row = document.createElement('div');
            row.className = 'ed-insp-row';
            const t = document.createElement('span');
            t.className = 'ed-insp-rowlabel';
            t.textContent = text;
            row.appendChild(t);
            const inputs = value.map((v) => numInput(v, { step, deg, intOnly }));
            inputs.forEach((i) => row.appendChild(i));
            row.addEventListener('change', () => {
                const vals = inputs.map((i) => readNum(i, 0));
                if (intOnly) vals.forEach((_, k) => { inputs[k].value = Math.round(vals[k]); });
                onCommit(vals.slice());
            });
            return row;
        }

        function refresh() {
            container.innerHTML = '';
            const model = api.getModel();
            if (!model) {
                container.appendChild(label('（未加载模型）'));
                return;
            }

            // ---------- 模型信息 ----------
            container.appendChild(label('模型'));
            const identRow = document.createElement('div');
            identRow.className = 'ed-insp-row';
            const idInput = document.createElement('input');
            idInput.type = 'text';
            idInput.value = model.identifier;
            idInput.className = 'ed-insp-text';
            idInput.addEventListener('change', () => {
                if (idInput.value && idInput.value !== model.identifier) {
                    model.identifier = idInput.value;
                    api.onChange('修改 identifier');
                }
            });
            identRow.appendChild(idInput);
            container.appendChild(identRow);
            const texInfo = document.createElement('div');
            texInfo.className = 'note';
            texInfo.style.fontSize = '11px';
            texInfo.textContent = `贴图 ${model.textureWidth}×${model.textureHeight} · 骨骼 ${model.bones.length} · cube ${model.bones.reduce((n, b) => n + (b.cubes || []).length, 0)}`;
            container.appendChild(texInfo);

            const sel = api.getSelection();
            if (!sel) {
                const hint = document.createElement('div');
                hint.className = 'note';
                hint.style.marginTop = '8px';
                hint.textContent = '点选大纲/视口中的骨骼或 cube 进行编辑。快捷键：G 移动 · R 旋转 · S 缩放 · Delete 删除 · Ctrl+Z 撤销';
                container.appendChild(hint);
            } else if (sel.type === 'bone') {
                const bone = model.bones.find((b) => b.name === sel.boneName);
                if (!bone) { container.appendChild(label('（骨骼不存在）')); return; }
                container.appendChild(label(`骨骼：${bone.name}`));
                container.appendChild(vec3Row(container, 'pivot（父空间）', bone.pivot, (v) => { bone.pivot = v; api.onChange(`改 ${bone.name} pivot`); }, { intOnly: true }));
                container.appendChild(vec3Row(container, 'rotation °', bone.rotation, (v) => { bone.rotation = v; api.onChange(`改 ${bone.name} rotation`); }, { deg: true }));
                container.appendChild(vec3Row(container, 'scale', bone.scale, (v) => { bone.scale = v; api.onChange(`改 ${bone.name} scale`); }, { step: 0.1 }));
                const mirRow = document.createElement('div');
                mirRow.className = 'ed-insp-row';
                const cb = document.createElement('input');
                cb.type = 'checkbox';
                cb.checked = bone.mirror === true;
                const cbLabel = document.createElement('span');
                cbLabel.textContent = 'mirror（镜像 UV）';
                cb.addEventListener('change', () => { bone.mirror = cb.checked; api.onChange(`改 ${bone.name} mirror`); });
                mirRow.appendChild(cb);
                mirRow.appendChild(cbLabel);
                container.appendChild(mirRow);

                // ---------- locators ----------
                const locTitle = label('Locators');
                container.appendChild(locTitle);
                bone.locators = bone.locators || [];
                bone.locators.forEach((loc, li) => {
                    const row = document.createElement('div');
                    row.className = 'ed-insp-row';
                    const nm = document.createElement('input');
                    nm.type = 'text';
                    nm.value = loc.name;
                    nm.className = 'ed-insp-text';
                    nm.style.width = '70px';
                    nm.addEventListener('change', () => { loc.name = nm.value; api.onChange('改 locator 名'); });
                    row.appendChild(nm);
                    const off = numInput(loc.offset[0], { intOnly: true });
                    const off2 = numInput(loc.offset[1], { intOnly: true });
                    const off3 = numInput(loc.offset[2], { intOnly: true });
                    [off, off2, off3].forEach((i, k) => i.addEventListener('change', () => {
                        loc.offset = [readNum(off, 0), readNum(off2, 0), readNum(off3, 0)];
                        api.onChange('改 locator offset');
                    }));
                    row.appendChild(off); row.appendChild(off2); row.appendChild(off3);
                    const del = document.createElement('button');
                    del.className = 'btn ghost small';
                    del.textContent = '×';
                    del.addEventListener('click', () => {
                        bone.locators.splice(li, 1);
                        api.onChange(`删 locator ${loc.name}`);
                    });
                    row.appendChild(del);
                    container.appendChild(row);
                });
                const addLoc = document.createElement('button');
                addLoc.className = 'btn ghost small';
                addLoc.style.marginTop = '4px';
                addLoc.textContent = '+ 添加 locator';
                addLoc.addEventListener('click', () => {
                    const names = bone.locators.map((l) => l.name);
                    let n = 1;
                    while (names.includes(`locator${n}`)) n++;
                    bone.locators.push({ name: `locator${n}`, offset: [0, 0, 0], rotation: [0, 0, 0] });
                    api.onChange(`加 locator`);
                });
                container.appendChild(addLoc);
            } else {
                const bone = model.bones.find((b) => b.name === sel.boneName);
                const cube = bone && bone.cubes[sel.cubeIndex];
                if (!bone || !cube) { container.appendChild(label('（cube 不存在）')); return; }
                container.appendChild(label(`cube[${sel.cubeIndex}] @ ${bone.name}`));
                container.appendChild(vec3Row(container, 'origin（骨父空间）', cube.origin, (v) => { cube.origin = v; api.onChange(`改 cube origin`); }, { intOnly: true }));
                container.appendChild(vec3Row(container, 'size', cube.size, (v) => {
                    cube.size = v.map((x) => Math.max(0, x));
                    api.onChange('改 cube size');
                }, { intOnly: true }));
                const infRow = vec3Row(container, 'pivot / inflate', [cube.pivot[0], cube.pivot[1], cube.pivot[2]], (v) => { cube.pivot = v; api.onChange('改 cube pivot'); }, { intOnly: true });
                container.appendChild(infRow);
                const infl = numInput(cube.inflate, { step: 0.1 });
                const inflRow = document.createElement('div');
                inflRow.className = 'ed-insp-row';
                const inflLabel = document.createElement('span');
                inflLabel.className = 'ed-insp-rowlabel';
                inflLabel.textContent = 'inflate';
                infl.addEventListener('change', () => { cube.inflate = readNum(infl, 0); api.onChange('改 cube inflate'); });
                inflRow.appendChild(inflLabel);
                inflRow.appendChild(infl);
                container.appendChild(inflRow);
                container.appendChild(vec3Row(container, 'rotation °', cube.rotation, (v) => { cube.rotation = v; api.onChange('改 cube rotation'); }, { deg: true }));

                // ---------- UV（简单形 = 2 输入 + 派生按钮；per-face 形 = 逐面表） ----------
                container.appendChild(label('UV'));
                const uvRow = document.createElement('div');
                uvRow.className = 'ed-insp-row';
                if (Array.isArray(cube.uv)) {
                    const ul = document.createElement('span');
                    ul.className = 'ed-insp-rowlabel';
                    ul.textContent = 'uv 原点';
                    const u0 = numInput(cube.uv[0], { intOnly: true });
                    const v0 = numInput(cube.uv[1], { intOnly: true });
                    [u0, v0].forEach((i, k) => i.addEventListener('change', () => {
                        cube.uv = [readNum(u0, 0), readNum(v0, 0)];
                        api.onChange('改 cube uv');
                    }));
                    uvRow.appendChild(ul); uvRow.appendChild(u0); uvRow.appendChild(v0);
                    container.appendChild(uvRow);
                    const derive = document.createElement('button');
                    derive.className = 'btn ghost small';
                    derive.style.marginTop = '4px';
                    derive.textContent = '派生 per-face UV（Bedrock 标准布局）';
                    derive.addEventListener('click', () => {
                        const rects = self.BedrockUV.faceRects({ size: cube.size, uv: cube.uv, mirror: bone.mirror === true });
                        const perFace = {};
                        for (const f of FACES) {
                            const r = rects[f];
                            if (r) perFace[f] = { uv: [r[0], r[1]], uv_size: [r[2], r[3]] };
                        }
                        cube.uv = perFace;
                        api.onChange('派生 per-face UV');
                    });
                    container.appendChild(derive);
                } else {
                    // per-face 表
                    const tbl = document.createElement('div');
                    tbl.className = 'ed-insp-perface';
                    tbl.style.fontSize = '11px';
                    for (const f of FACES) {
                        const face = cube.uv[f];
                        if (!face) continue;
                        const r = document.createElement('div');
                        r.className = 'ed-insp-row';
                        const fl = document.createElement('span');
                        fl.className = 'ed-insp-rowlabel';
                        fl.textContent = `${f}(${FACE_CN[f]})`;
                        r.appendChild(fl);
                        const mk = (val, commit, step) => {
                            const i = numInput(val, { step: step || 1 });
                            i.addEventListener('change', () => { commit(readNum(i, 0)); });
                            return i;
                        };
                        r.appendChild(mk(face.uv[0], (x) => { face.uv = [x, face.uv[1]]; api.onChange(`改 ${f} uv`); }));
                        r.appendChild(mk(face.uv[1], (y) => { face.uv = [face.uv[0], y]; api.onChange(`改 ${f} uv`); }));
                        if (face.uv_size) {
                            r.appendChild(mk(face.uv_size[0], (x) => { face.uv_size = [x, face.uv_size[1]]; api.onChange(`改 ${f} uv_size`); }));
                            r.appendChild(mk(face.uv_size[1], (y) => { face.uv_size = [face.uv_size[0], y]; api.onChange(`改 ${f} uv_size`); }));
                        }
                        if (face.uv_rotation != null) {
                            r.appendChild(mk(face.uv_rotation, (x) => { face.uv_rotation = x; api.onChange(`改 ${f} uv_rotation`); }, 15));
                        }
                        tbl.appendChild(r);
                    }
                    container.appendChild(tbl);
                }
            }

            // ---------- 校验 ----------
            const { errors, warnings } = self.Model.validateGeometry(model);
            const problems = [...errors.map((e) => '✗ ' + e), ...warnings.map((w) => '⚠ ' + w)];
            if (problems.length) {
                const box = document.createElement('div');
                box.style.marginTop = '8px';
                box.style.fontSize = '11px';
                for (const p of problems) {
                    const d = document.createElement('div');
                    d.style.color = p.startsWith('✗') ? '#f87171' : '#fbbf24';
                    d.textContent = p;
                    box.appendChild(d);
                }
                container.appendChild(box);
            }
        }

        return { refresh };
    }

    const API = { createInspector };
    if (typeof module === 'object' && module.exports) module.exports = API;
    if (globalScope) globalScope.Inspector = API;
})(typeof self !== 'undefined' ? self : (typeof globalThis !== 'undefined' ? globalThis : null));
