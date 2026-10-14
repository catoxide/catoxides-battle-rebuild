// outliner.js - 大纲树（W3）：骨骼（层级）+ cube 列表
// 经典 script（self.Outliner），零 three 依赖，DOM 直出。
// api: {
//   getModel() → geometry | null
//   getSelection() → {type:'bone'|'cube', boneName, cubeIndex?} | null
//   onSelect(sel)  // sel = {type:'bone', boneName} | {type:'cube', boneName, cubeIndex} | null
//   onAddBone(parentName|null), onAddCube(boneName), onRemove(sel), onDuplicate(sel), onRename(sel, newName)
//   getAnimLock?() → boolean（W7 动画分页：true = 禁双击重命名（骨骼名=动画轨道键）；cube 行照常可选）
// }
(function (globalScope) {
    function createOutliner(container, api) {
        // hideState = { hidden, onToggle }（W4.9 cube 行 👁 临时隐藏；bone 行不传）
        function row(sel, label, depth, icon, hideState) {
            const div = document.createElement('div');
            div.className = 'ed-treerow';
            div.style.paddingLeft = (depth * 12 + 4) + 'px';
            div.style.lineHeight = '1.6';
            div.style.cursor = 'pointer';
            div.style.whiteSpace = 'nowrap';
            if (hideState) {
                div.style.display = 'flex';
                div.style.alignItems = 'center';
                const lbl = document.createElement('span');
                lbl.textContent = icon + ' ' + label;
                lbl.style.flex = '1';
                div.appendChild(lbl);
                const btn = document.createElement('span');
                btn.textContent = hideState.hidden ? '🚫' : '👁';
                btn.title = hideState.hidden ? '显示此 cube' : '隐藏此 cube（临时，不落盘）';
                btn.style.cssText = 'flex:none;cursor:pointer;font-size:11px;opacity:' + (hideState.hidden ? '0.55' : '0.9');
                btn.addEventListener('click', (e) => { e.stopPropagation(); hideState.onToggle(); });
                div.appendChild(btn);
                if (hideState.hidden) { div.style.opacity = '0.5'; lbl.style.textDecoration = 'line-through'; }
            } else {
                div.textContent = icon + ' ' + label;
            }
            div.addEventListener('click', (e) => {
                e.stopPropagation();
                api.onSelect(sel);
            });
            if (sel.type === 'bone' && !(api.getAnimLock && api.getAnimLock() === true)) {
                div.addEventListener('dblclick', async (e) => {
                    e.stopPropagation();
                    // W3.6.1：Electron 不支持 prompt() → 页面内模态框
                    // W7：动画页禁重命名（骨骼名 = 动画轨道键，改名会断轨道）
                    const nn = await window.askText({ title: '重命名骨骼', label: '骨骼名', value: sel.boneName });
                    if (nn && nn !== sel.boneName) api.onRename(sel, nn);
                });
            }
            return div;
        }

        function refresh() {
            container.innerHTML = '';
            const model = api.getModel();
            if (!model) {
                const d = document.createElement('div');
                d.className = 'note';
                d.textContent = '（未加载模型）';
                container.appendChild(d);
                return;
            }
            const { roots, byName, errors } = self.Model.buildTree(model);
            const sel = api.getSelection();
            const walk = (node, depth) => {
                const bone = node.bone;
                const boneSel = { type: 'bone', boneName: node.name };
                const r = row(boneSel, node.name, depth, '🦴');
                if (sel && sel.type === 'bone' && sel.boneName === node.name) r.style.color = '#facc15';
                container.appendChild(r);
                // W7 动画分页：cube 行照常显示可选（用户确认）
                for (let ci = 0; ci < (bone.cubes || []).length; ci++) {
                    const c = bone.cubes[ci];
                    const cubeSel = { type: 'cube', boneName: node.name, cubeIndex: ci };
                    const hidden = api.getHidden ? api.getHidden().has(node.name + '#' + ci) : false;
                    const cr = row(cubeSel, `cube ${ci} [${c.size.join('×')}]`, depth + 1, '🧊', {
                        hidden,
                        onToggle: () => api.onToggleHide(node.name, ci),
                    });
                    if (sel && sel.type === 'cube' && sel.boneName === node.name && sel.cubeIndex === ci) cr.style.color = '#facc15';
                    container.appendChild(cr);
                }
                for (const ch of node.children) walk(ch, depth + 1);
            };
            for (const r of roots) walk(r, 0);
            if (errors.length) {
                for (const e of errors) {
                    const d = document.createElement('div');
                    d.style.color = '#f87171';
                    d.style.fontSize = '11px';
                    d.textContent = '⚠ ' + e;
                    container.appendChild(d);
                }
            }
        }

        return { refresh };
    }

    const API = { createOutliner };
    if (typeof module === 'object' && module.exports) module.exports = API;
    if (globalScope) globalScope.Outliner = API;
})(typeof self !== 'undefined' ? self : (typeof globalThis !== 'undefined' ? globalThis : null));
