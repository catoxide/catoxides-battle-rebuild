// app.js - 模型编辑器引导（W3：文档状态 + undo + gizmo/大纲/属性接线 + 保存落盘）
// 经典 script：core 模块经 <script> 挂 self.*；ui/viewport.js 与 ui/gizmo.js 是 ESM
//（内部 import 本地 three.module.js），此处动态 import（与 src/views/entityList.js 同模式）。
'use strict';

(async function () {
    const $ = (id) => document.getElementById(id);
    const M = self.Model, U = self.Undo, SC = self.SparkCodec, SS = self.SparkScaffold, BB = self.BBModel;
    const Ev = self.Evaluate, Kf = self.KeyframeOps, Ch = self.Chaining, Tk = self.Tick, AN = self.Animation;
    const AP = self.AnimParams; // 运行时参数声明（params）模型/校验

    // ---------- 视口 + gizmo ----------
    let gizmo = null;
    const vpModule = await import('./ui/viewport.js');
    const gizmoModule = await import('./ui/gizmo.js');
    const vp = vpModule.createViewport($('meCanvasWrap'), {
        onSelect: (sel) => {
            doc.selection = sel ? { type: 'cube', boneName: sel.boneName, cubeIndex: sel.cubeIndex } : null;
            onSelectionChanged();
        },
        onGizmoPointerDown: (e) => (gizmo ? gizmo.onPointerDown(e) : false),
        // W5.4 PS 式：对象本体手势（左拖=移动 / 右拖=旋转，未选中先选中）
        onCubeDragStart: (e, kind) => (gizmo ? gizmo.startCubeGesture(e, kind) : false),
        // W5.4：滚轮悬停 cube = 均匀缩放（绕 pivot）
        onWheelScale: (e) => (gizmo ? gizmo.wheelScaleAt(e) : false),
    });

    // ---------- 文档 ----------
    const doc = {
        model: null,        // Geometry 规范形（app 持有唯一可变引用；栈内是快照副本）
        sourceFile: null,   // 源模型文件绝对路径（spark .json 或 Blockbench .bbmodel；保存目标）
        sourceFormat: null, // 'spark' | 'bbmodel'（保存时据此选择序列化器）
        saveAsNs: null,     // 另存为的 namespace（文件 identifier 约定不含 ns，单独存）
        textureName: null,
        selection: null,    // {type:'bone'|'cube', boneName, cubeIndex?}
        undoStack: null,
        dirty: false,
        attachment: null,   // W3.5 附加预览（会话级）{pack, rel, name, boneName}
        inserted: null,      // 2026-10-14 插入模型（会话级，吸附接口/locator）{pack, rel, name, locatorName, geo, anims: Map<name,Animation>}
        selectedLocator: null, // 2026-10-14 当前选中接口（locator 名；null = 未选 → 插入到模型根）
        hiddenCubes: new Set(), // W4.9 按 cube 临时隐藏 Set<"boneName#cubeIndex">（会话级不落盘）
        texturePreview: null, // W3.5 贴图预览 {pack, rel, size}
        textureApplied: null, // W3.5-② 已应用贴图（落盘契约 model.texture）{pack, rel}
        textureDataUrl: null, // W4.7 贴图 dataURL（UV/贴图预览画布用；应用贴图处同步设置）
        // ---------- W7 动画（design/ANIMATION_TECH_STACK.md §7：一窗口 = 一个模型的全套动画） ----------
        animations: null,    // Map<name, Animation>（null = 未加载；空 Map = 加载了但无动画文件）
        animLoaded: false,   // 动画文档已尝试加载（区分"未加载"与"加载了但空"）
        animFiles: {},       // {animName: fileBaseName}（bundle：多动画 → 同一文件）
        animFileNames: {},   // {fileBaseName: [file 内全部动画名]}（含重名被遮蔽的 → 列表灰显）
        animFileContent: {}, // {fileBaseName: 载入时的原始文本}（未改动的文件保存时不重写 → 最小 diff）
        animRel: null,       // 'spark_animations/<type>/<ns>/<model>'（IPC 坐标）
        animDirty: new Set(),// Set<fileBaseName>：保存时需写出的动画文件
        animDeleted: new Set(),// Set<fileBaseName>：保存时需删除的动画文件
        currentAnim: null,   // 当前选中的动画名
        animPage: false,     // W7 动画分页（true = 动画页：隐藏建模按钮 + 时间线常驻 + 姿态编辑）
        animMode: false,     // 姿态编辑（随 animPage；姿态显示 = evaluate；auto-key 开；cube 编辑禁）
        animTime: 0,         // 播放头（秒）
        animPlaying: false,
        animSpeed: 1,
        timeGrid: 60,        // 时间网格 fps（60 默认 / 20 / 24）
        simOn: false,        // 移动视觉模拟（跑步机）
        simSpeed: 4.3,       // blocks/s（走路 4.3 / 疾跑 5.6）
        simDir: 1,           // 1 = 前向（网格向后流）/ -1 = 反向
        paramPreview: {},    // 运行时参数预览测试值 {name: 数字/字符串}（会话级不落盘；覆盖动画 params 默认值）
    };

    /** W3.5-②：打开已有模型时，从包契约读取 model.texture 并应用（无契约/无贴图 = 静默跳过） */
    async function autoApplyContractTexture() {
        const packName = currentPackName();
        const modelRel = currentModelRel();
        if (!packName || !modelRel || !window.api || !window.api.readPackContract) return;
        const rc = await window.api.readPackContract({ pack: packName });
        if (!rc || !rc.ok) return;
        let obj;
        try { obj = JSON.parse(rc.data.content); } catch (e) { return; }
        for (const set of (obj.sets || [])) {
            const p = typeof set.model === 'string' ? set.model : (set.model && set.model.path);
            if (p && p.replace(/\\/g, '/') === modelRel && set.model && set.model.texture) {
                const texRel = set.model.texture;
                const r = await window.api.readRepoTexture({ source: 'pack', pack: packName, rel: texRel });
                if (r && r.ok) {
                    const tex = await vpModule.makeTextureFromDataUrl(r.data.dataUrl);
                    if (tex) {
                        vp.setTexture(tex);
                        doc.textureApplied = { pack: packName, rel: texRel };
                        doc.textureDataUrl = r.data.dataUrl; // W4.7 UV 预览
                        requestUvRedraw(); // W5.6 常驻 UV 随贴图同步重绘
                        doc.texturePreview = { pack: packName, rel: texRel, size: r.data.width + '×' + r.data.height };
                        try { if (window.api.log) window.api.log('info', 'model-editor', `契约贴图自动应用: ${packName} ${texRel}`); } catch (e) {}
                        setStatus(`已应用契约贴图 ${texRel.split('/').pop()}（${packName}）`);
                    }
                }
                return;
            }
        }
    }

    /** 当前模型在包内的相对路径（slash 形）；未保存/无法解析 = null */
    function currentModelRel() {
        if (!doc.sourceFile) return null;
        const pp = SS.parseModelPath(doc.sourceFile);
        if (!pp.ok) return null;
        return `spark_models/${pp.type}/${pp.ns}/${pp.name}.json`;
    }
    function currentPackName() {
        if (!doc.sourceFile) return null;
        const pp = SS.parseModelPath(doc.sourceFile);
        return pp.ok ? pp.packName : null;
    }

    /** W3.5-②：把已应用贴图写进包契约（model.texture）；返回 {ok, msg} */
    async function persistTextureToContract() {
        if (!doc.textureApplied) return { ok: false, msg: '无已应用贴图' };
        const packName = currentPackName();
        const modelRel = currentModelRel();
        if (!packName || !modelRel) {
            return { ok: false, msg: '模型未保存到包内 —— 贴图将在「另存为」时写入契约' };
        }
        if (!window.api || !window.api.readPackContract || !window.api.writePackContract) {
            return { ok: false, msg: '契约读写不可用（需在主程序内打开）' };
        }
        const rc = await window.api.readPackContract({ pack: packName });
        if (!rc || !rc.ok) {
            return { ok: false, msg: `包 ${packName} 无契约（或读取失败）—— 贴图将在「另存为」时写入` };
        }
        let obj;
        try { obj = JSON.parse(rc.data.content); } catch (e) { return { ok: false, msg: '契约解析失败: ' + e.message }; }
        const r = SS.setContractTexture(obj, modelRel, doc.textureApplied.rel);
        if (!r.ok) return { ok: false, msg: r.error };
        const wc = await window.api.writePackContract({ pack: packName, content: JSON.stringify(obj, null, 2) + '\n' });
        if (!wc || !wc.ok) return { ok: false, msg: '契约写入失败: ' + ((wc && wc.error) || '?') };
        return { ok: true, msg: (r.data.created ? '契约新增 set ' : '契约已更新 ') + r.data.set.id + ' → model.texture' };
    }

    function setStatus(text) { $('meStatus').textContent = text; }

    // ---------- 面板 ----------
    const outliner = self.Outliner.createOutliner($('meOutliner'), {
        getModel: () => doc.model,
        getSelection: () => doc.selection,
        onSelect: (sel) => {
            doc.selection = sel;
            // silent：视口的 onSelect 回调是"cube 点选"语义（只回传 cube），
            // 大纲选择骨骼若让它重发，会把 bone 选择冲成 cube{cubeIndex:undefined}
            vp.setSelection(sel ? { boneName: sel.boneName, cubeIndex: sel.cubeIndex } : null, { silent: true });
            onSelectionChanged();
        },
        onAddBone: addBone,
        onAddCube: addCube,
        onRemove: removeSelected,
        onDuplicate: duplicateSelected,
        onRename: (sel, newName) => renameBone(sel, newName),
        // W4.9：cube 行 👁 临时隐藏
        getHidden: () => doc.hiddenCubes,
        // W7：动画分页 = cube 行照常可选；仅禁重命名（骨骼名 = 动画轨道键）
        getAnimLock: () => doc.animPage,
        onToggleHide: (boneName, cubeIndex) => {
            const k = boneName + '#' + cubeIndex;
            if (doc.hiddenCubes.has(k)) doc.hiddenCubes.delete(k);
            else doc.hiddenCubes.add(k);
            vp.setHiddenCubes(doc.hiddenCubes);
            outliner.refresh();
        },
    });
    const inspector = self.Inspector.createInspector($('meInspector'), {
        getModel: () => doc.model,
        getSelection: () => doc.selection,
        onChange: commit,
    });
    gizmo = gizmoModule.createGizmo(vp, {
        getSelection: () => doc.selection,
        // W7：动画模式 = 虚拟几何（骨姿态 = 当前 evaluate 绝对姿态，稳定对象就地更新 →
        // gizmo 的拖拽改动落在 info.bone 上，auto-key 回调可读回）
        getGeometry: () => (doc.animMode ? virtualAnimGeometry() : doc.model),
        onChange: commit,
        // W5.4：PS 式"作用于光标下对象"——拖拽/滚轮命中未选中 cube 时先选中
        selectCube: (boneName, cubeIndex) => {
            doc.selection = { type: 'cube', boneName, cubeIndex };
            vp.setSelection({ boneName, cubeIndex }, { silent: true });
            onSelectionChanged();
        },
        // W7 auto-key（Blockbench 式抓骨建帧）
        animPoseMode: () => doc.animMode,
        onGestureStart: animGestureStart,
        onAnimPoseChanged: animPoseChanged,
        onGestureEnd: animGestureEnd,
    });

    function onSelectionChanged() {
        outliner.refresh();
        inspector.refresh();
        gizmo.rebuild();
        const s = doc.selection;
        setStatus(s
            ? (s.type === 'bone' ? `已选择：骨骼 ${s.boneName}` : `已选择：${s.boneName} / cube[${s.cubeIndex}]`)
            : '已选择：(无)');
        if (uvVisible()) renderUvPanel(); // W4.7/W5.6 3D 选择 → UV 高亮联动（UV 常驻）
    }

    // ---------- 状态提交（undo） ----------
    /** W7：undo 快照 = 动画文档已加载时 {model, animations}（统一快照），否则裸 model（兼容旧会话） */
    function animUndoState() {
        if (doc.animLoaded && doc.animations) {
            return { model: doc.model, animations: Object.fromEntries(doc.animations) };
        }
        return doc.model;
    }

    /** 数据已被改（gizmo/inspector/core 操作）→ 推快照 + 全量刷新 */
    function commit(label) {
        if (!doc.model || !doc.undoStack) return;
        const pushed = U.push(doc.undoStack, animUndoState(), label);
        if (pushed) {
            doc.dirty = true;
            if (doc.animMode) applyAnimDisplay();
            else vp.refresh();
            outliner.refresh();
            inspector.refresh();
            gizmo.rebuild();
            if (window.__meTimeline) window.__meTimeline.refresh();
            setStatus(`${label} · 未保存`);
        }
    }

    /** 结构操作（core/model.js 纯函数已改 doc.model）→ commit 的语义化包装 */
    function applyStructural(op, label) {
        const r = op();
        if (!r || r.ok === false) { setStatus('✗ ' + (r ? r.error : '操作失败')); return null; }
        commit(label);
        return r;
    }

    function adoptState(s, label) {
        // undo/redo：栈内快照与 doc.model 分离（独立克隆，避免栈内对象被 in-place 改动）
        // W7：统一快照 {model, animations} 还原动画文档
        if (s && typeof s === 'object' && s.model && s.animations !== undefined) {
            doc.model = U.cloneState(s.model);
            doc.animations = new Map(Object.entries(U.cloneState(s.animations)));
        } else {
            doc.model = U.cloneState(s);
        }
        doc.dirty = true;
        // 选择有效性
        const sel = doc.selection;
        if (sel) {
            const bone = doc.model.bones.find((b) => b.name === sel.boneName);
            if (!bone || (sel.type === 'cube' && (!bone.cubes || sel.cubeIndex >= bone.cubes.length))) {
                doc.selection = null;
                vp.setSelection(null);
            }
        }
        // W5.4 验证发现：undo/redo 不得重置相机（fit 只用于新模型加载）——
        // 否则用户调好的视角在每次撤销时丢失
        vp.setModel(doc.model, { fit: false });
        if (doc.animMode) applyAnimDisplay();
        outliner.refresh();
        inspector.refresh();
        gizmo.rebuild();
        if (window.__meTimeline) window.__meTimeline.refresh();
        setStatus(`${label} · 未保存`);
    }

    function doUndo() {
        if (!doc.undoStack || !U.canUndo(doc.undoStack)) return;
        adoptState(U.undo(doc.undoStack), '撤销');
    }
    function doRedo() {
        if (!doc.undoStack || !U.canRedo(doc.undoStack)) return;
        adoptState(U.redo(doc.undoStack), '重做');
    }

    // ---------- 结构操作 ----------
    async function addBone() {
        const parent = doc.selection && doc.selection.type === 'bone' ? doc.selection.boneName : null;
        // W3.6.1：Electron 不支持 prompt() → 页面内模态框
        const name = await window.askText({
            title: '新建骨骼',
            label: '骨骼名（留空 = 自动命名）',
            value: parent ? parent + '_' : '',
            placeholder: parent ? parent + '_' : '留空 = 自动',
        });
        if (name === null) return;
        applyStructural(() => M.addBone(doc.model, name ? { name, parent } : { parent }),
            `加骨骼${parent ? ' → ' + parent : ''}`);
        outliner.refresh();
    }

    function addCube() {
        if (!doc.selection || doc.selection.type !== 'bone') { setStatus('✗ 先选中一个骨骼'); return; }
        applyStructural(
            () => M.addCube(doc.model, doc.selection.boneName, { origin: [0, 0, 0], size: [4, 4, 4], uv: [0, 0] }),
            `加 cube @ ${doc.selection.boneName}`);
    }

    function removeSelected() {
        const sel = doc.selection;
        if (!sel) { setStatus('✗ 未选中'); return; }
        if (sel.type === 'bone') {
            applyStructural(() => M.removeBone(doc.model, sel.boneName), `删骨骼 ${sel.boneName}`);
        } else {
            applyStructural(() => M.removeCube(doc.model, sel.boneName, sel.cubeIndex),
                `删 cube[${sel.cubeIndex}] @ ${sel.boneName}`);
        }
        doc.selection = null;
        vp.setSelection(null);
        onSelectionChanged();
    }

    function duplicateSelected() {
        const sel = doc.selection;
        if (!sel) { setStatus('✗ 未选中'); return; }
        if (sel.type === 'cube') { setStatus('✗ v1 仅支持复制骨骼（整棵子树）'); return; }
        const name = sel.boneName;
        const r = applyStructural(() => M.duplicateBone(doc.model, name), `复制骨骼 ${name}`);
        if (r) {
            doc.selection = { type: 'bone', boneName: r.name };
            vp.setSelection({ boneName: r.name, cubeIndex: undefined });
            onSelectionChanged();
        }
    }

    function renameBone(sel, newName) {
        const r = applyStructural(() => M.renameBone(doc.model, sel.boneName, newName), `重命名 ${sel.boneName} → ${newName}`);
        if (r) {
            doc.selection = { type: 'bone', boneName: newName };
            vp.setSelection({ boneName: newName, cubeIndex: undefined });
            onSelectionChanged();
            setStatus('⚠ 动画文件中引用旧名的骨骼键将失配（W4 时间轴接入后统一迁移）');
        }
    }

    // ---------- 新建（W3.1；W3.6.1 面板化——Electron 不支持 prompt()） ----------
    function newModel() {
        const panel = $('meNewPanel');
        $('meNewIdent').value = '';
        $('meNewSize').value = '';
        $('meNewTpl').value = '1';
        panel.hidden = false;
        requestAnimationFrame(() => { $('meNewIdent').focus(); });
    }

    function closeNewPanel() { $('meNewPanel').hidden = true; }

    function createNewModel() {
        if (!confirmDiscard('新建模型')) return;
        const ident = $('meNewIdent').value;
        const id = SS.parseIdentifier(ident);
        if (!id.ok) { setStatus('✗ ' + id.error); return; }
        const size = $('meNewSize').value;
        let tw = 64, th = 64;
        if (size.trim()) {
            const m = size.trim().match(/^(\d+)\s*[×xX*]\s*(\d+)$/);
            if (!m) { setStatus('✗ 贴图尺寸格式：宽×高（如 64×64）'); return; }
            tw = parseInt(m[1], 10); th = parseInt(m[2], 10);
            if (tw < 1 || tw > 4096 || th < 1 || th > 4096) { setStatus('✗ 贴图尺寸须为 1~4096'); return; }
        }
        const bones = $('meNewTpl').value === '1' ? SS.buildHumanoidTemplate() : [];
        closeNewPanel();
        const geo = M.createGeometry({
            identifier: 'geometry.' + id.name,
            textureWidth: tw,
            textureHeight: th,
            bones,
        });
        doc.model = geo;
        doc.undoStack = U.createUndoStack(geo);
        doc.dirty = false;
        notifyUnsaved();
        doc.selection = null;
        doc.sourceFile = null;
        doc.sourceFormat = 'spark';
        doc.saveAsNs = id.ns || 'new';
        doc.textureName = null;
        doc.attachment = null;
        doc.inserted = null;           // 2026-10-14：新建 = 插入模型移除
        doc.selectedLocator = null;
        doc.hiddenCubes = new Set();
        vp.setHiddenCubes(doc.hiddenCubes); // W4.9
        vp.clearAttachment();          // 2026-10-14：清附加参数（重建不重挂旧附加）
        if (vp.setLocatorHighlight) vp.setLocatorHighlight(null);
        doc.texturePreview = null;
        doc.textureApplied = null;
        doc.textureDataUrl = null; // W4.7
        vp.setModel(geo);
        vp.clearAttachment();
        vp.setSelection(null);
        $('meModelName').textContent = geo.identifier + '（未保存）';
        // W5.6：同 applyLoadedModelCore —— 重置 UV 缓冲/缩放并恢复记忆视图模式
        uvAllocKey = null; uvZoomOverride = 0; uvTexSrcApplied = null;
        setViewMode(persistedViewMode, { persist: false });
        onSelectionChanged();
        try { if (window.api.log) window.api.log('info', 'model-editor', `新建模型 ${geo.identifier}（${tw}×${th}，${bones.length} 骨）`); } catch (e) {}
        setStatus(`新建 ${geo.identifier} · ${tw}×${th} · ${bones.length} 骨 —— 💾 保存 = 另存为（<包>/spark_models/entity/${id.ns || 'new'}/）`);
    }

    // ---------- 另存为 / 一键选包保存（W3.1） ----------
    function saveAsIdentifier() {
        const plain = doc.model.identifier.replace(/^geometry\./, '');
        return doc.saveAsNs ? doc.saveAsNs + ':' + plain : plain;
    }

    // REVIEW-L10：模型类型随另存为传入（entity/item）——此前主进程硬编码 entity 目录，
    // item 模型落错目录后游戏内丢失（resolveFromId 按 item 目录查找）
    function saveAsType() {
        if (!doc.sourceFile) return 'entity';
        const p = SS.parseModelPath(doc.sourceFile);
        return (p && p.ok && (p.type === 'entity' || p.type === 'item')) ? p.type : 'entity';
    }

    function onSaveAsResult(r) {
        if (!r) return;
        if (r.ok && r.data && r.data.canceled) { setStatus('已取消另存为'); return; }
        if (!r.ok) { setStatus('✗ ' + (r.error || '另存为失败')); return; }
        doc.sourceFile = r.data.path;
        doc.dirty = false;
        notifyUnsaved();
        $('meModelName').textContent = doc.sourceFile.split(/[\\/]/).pop();
        const extra = [];
        if (r.data.contractCreated) extra.push('已生成最小契约 editor/animation-contract.json');
        extra.push(`动画目录：${r.data.animDir}`);
        extra.push(`贴图建议：${r.data.textureHint}`);
        setStatus(`已另存为 ${doc.sourceFile.split(/[\\/]/).pop()} ✓（${extra.join('；')}）`);
        try { if (window.api.log) window.api.log('info', 'model-editor', `另存为: ${doc.sourceFile}` + (r.data.contractCreated ? '（+缺省契约）' : '')); } catch (e) {}
    }

    /** 手动路径（OS 保存对话框） */
    function saveAsFlow() {
        if (!doc.model) { setStatus('✗ 未加载模型'); return; }
        let content;
        try { content = SC.exportModel(doc.model); } catch (e) { setStatus('✗ ' + e.message); return; }
        if (!window.api || !window.api.saveModelAs) {
            setStatus('✗ 保存不可用（需在主程序内打开，或无 window.api）');
            return;
        }
        setStatus('另存为…（选择保存位置）');
        window.api.saveModelAs({ content, identifier: saveAsIdentifier(), type: saveAsType(), texture: doc.textureApplied ? doc.textureApplied.rel : undefined }).then(onSaveAsResult);
    }

    /** 一键保存面板：列出内容包，点一下按约定路径落盘 */
    let savePanelBusy = false;
    function openSavePanel() {
        if (!doc.model || !window.api || !window.api.saveModelAs) { saveAsFlow(); return; }
        // 注意：listModelPacks（modelEditor:listPacks）≠ 主窗口的 listPacks（pack:list），勿混用
        const panel = $('meSavePanel');
        $('meSavePanelName').textContent = doc.model.identifier + (doc.sourceFile ? '' : '（新）');
        const listEl = $('meSavePackList');
        listEl.innerHTML = '<div class="note" style="padding:4px 8px">加载包列表…</div>';
        panel.hidden = false;
        const ns = doc.saveAsNs || 'new';
        const plain = doc.model.identifier.replace(/^geometry\./, '');
        const mtype = saveAsType();
        const srcParse = doc.sourceFile ? SS.parseModelPath(doc.sourceFile) : null;
        const currentPack = (srcParse && srcParse.ok) ? srcParse.packName : null;
        (async () => {
            let packs = [];
            if (window.api.listModelPacks) {
                const r = await window.api.listModelPacks();
                if (r && r.ok && Array.isArray(r.data)) packs = r.data;
            }
            listEl.innerHTML = '';
            if (!packs.length) {
                listEl.innerHTML = '<div class="note" style="padding:4px 8px">取不到包列表 —— 请用「手动选择文件路径」</div>';
                return;
            }
            for (const pk of packs) {
                const row = document.createElement('div');
                row.className = 'ed-packrow' + (pk.name === currentPack ? ' current' : '');
                const nm = document.createElement('span'); nm.className = 'pk-name'; nm.textContent = pk.name;
                const badge = document.createElement('span'); badge.className = 'pk-badge';
                badge.textContent = pk.name === currentPack ? '当前' : (pk.hasModels ? '' : '无 spark_models');
                const tg = document.createElement('span'); tg.className = 'pk-target';
                tg.textContent = `spark_models/${mtype}/${ns}/${plain}.json`;
                row.append(nm, badge, tg);
                row.title = `保存到 ${pk.name} → spark_models/${mtype}/${ns}/${plain}.json`;
                row.onclick = () => doSaveToPack(pk.name);
                listEl.appendChild(row);
            }
        })();
    }

    function closeSavePanel() { $('meSavePanel').hidden = true; }

    function doSaveToPack(packName) {
        if (savePanelBusy || !doc.model) return;
        let content;
        try { content = SC.exportModel(doc.model); } catch (e) { setStatus('✗ ' + e.message); return; }
        savePanelBusy = true;
        closeSavePanel();
        setStatus(`保存到 ${packName}…`);
        window.api.saveModelAs({ content, identifier: saveAsIdentifier(), type: saveAsType(), packName, texture: doc.textureApplied ? doc.textureApplied.rel : undefined }).then((r) => {
            savePanelBusy = false;
            onSaveAsResult(r);
        });
    }

    // ---------- W3.5 资源仓库选择器（模型附加预览 + 贴图预览） ----------
    const repo = { tab: 'models', items: null, textures: null, filter: '' };

    function openRepoPanel(opts = {}) {
        const panel = $('meRepoPanel');
        panel.hidden = false;
        $('meRepoSearch').value = '';
        repo.filter = '';
        repo.insertMode = !!opts.insert; // 2026-10-14：插入模式（点模型 = 插入到 🧲 接口）
        $('meRepoHint').textContent = repo.insertMode
            ? '插入模式：点模型 = 插入到当前 🧲 接口（零偏移吸附；同名状态随主模型同步播放）。未选接口 = 挂模型根'
            : '点模型 = 打开为主模型（💾 保存直写该文件）· 行内「🔗 附加」= 挂到当前所选骨预览（需先有主模型）· 点贴图 = 应用到当前模型';
        (async () => {
            if (!window.api || !window.api.listModels) {
                $('meRepoList').innerHTML = '<div class="note" style="padding:4px 8px">window.api.listModels 不可用（需在主程序内打开）</div>';
                return;
            }
            const [rm, rt] = await Promise.all([
                window.api.listModels(),
                window.api.listRepoTextures ? window.api.listRepoTextures() : { ok: false },
            ]);
            repo.items = (rm && rm.ok && Array.isArray(rm.data)) ? rm.data : [];
            repo.textures = (rt && rt.ok && Array.isArray(rt.data)) ? rt.data : [];
            renderRepoList();
        })();
    }

    function closeRepoPanel() { $('meRepoPanel').hidden = true; }

    function setRepoTab(tab) {
        repo.tab = tab;
        $('meRepoTabModels').classList.toggle('active', tab === 'models');
        $('meRepoTabTextures').classList.toggle('active', tab === 'textures');
        renderRepoList();
    }

    function renderRepoList() {
        const listEl = $('meRepoList');
        const f = repo.filter.toLowerCase();
        let rows = [];
        if (repo.tab === 'models') {
            rows = (repo.items || [])
                .filter((m) => !f || (m.pack + '/' + m.name + '/' + m.rel).toLowerCase().includes(f))
                .map((m) => ({ label: m.name, src: m.pack + ' · ' + m.type, path: m.rel, item: m }));
        } else {
            rows = (repo.textures || [])
                .filter((t) => !f || (t.pack + '/' + t.name + '/' + t.rel).toLowerCase().includes(f))
                .map((t) => ({ label: t.name, src: t.source === 'mod' ? '主 mod' : t.pack, path: t.rel, item: t }));
        }
        if (!rows.length) {
            listEl.innerHTML = '<div class="note" style="padding:4px 8px">无匹配资源</div>';
            return;
        }
        listEl.innerHTML = '';
        for (const r of rows) {
            const row = document.createElement('div');
            row.className = 'ed-reporow';
            const nm = document.createElement('span'); nm.className = 'rp-name'; nm.textContent = r.label;
            const src = document.createElement('span'); src.className = 'rp-src'; src.textContent = r.src;
            const pa = document.createElement('span'); pa.className = 'rp-path'; pa.textContent = r.path;
            row.append(nm, src, pa);
            row.title = r.path + ((repo.insertMode) ? '（单击 = 插入到当前 🧲 接口）' : '（单击 = 打开为主模型）');
            const item = r.item;
            row.onclick = () => (repo.insertMode && repo.tab === 'models') ? insertRepoModel(item) : selectRepoItem(item);
            if (repo.tab === 'models') {
                // W4.6：行内 🔗 = 附加预览（挂当前所选骨/模型根；需已加载主模型）
                const at = document.createElement('button');
                at.className = 'rp-attach';
                at.textContent = '🔗 附加';
                at.title = '附加预览到当前所选骨（需先有主模型；会话级不落盘）';
                at.onclick = (e) => { e.stopPropagation(); attachRepoModel(item); };
                row.appendChild(at);
            }
            listEl.appendChild(row);
        }
    }

    function selectRepoItem(item) {
        // W4.6：模型单击 = 打开为主模型（旧行为「附加预览」移到行内 🔗 按钮）
        if (repo.tab === 'models') openRepoModel(item);
        else previewRepoTexture(item);
    }

    /** W4.6：仓库模型 → 打开为主模型（源路径 = 主进程返回的包内绝对路径；💾 保存直写该文件，与「📂 打开模型」同链路） */
    async function openRepoModel(item) {
        if (!window.api || !window.api.readRepoModel) { setStatus('✗ readRepoModel 不可用（需在主程序内打开）'); return; }
        setStatus(`打开 ${item.pack} / ${item.name}…`);
        const r = await window.api.readRepoModel({ pack: item.pack, rel: item.rel });
        if (!r || !r.ok) { setStatus('✗ ' + ((r && r.error) || '读取模型失败')); return; }
        let data;
        try { data = SC.importModel(r.data.content).data; } catch (e) { setStatus('✗ 模型解析失败: ' + e.message); return; }
        doc.sourceFormat = 'spark';
        applyLoadedModelCore(data, {
            displayName: item.pack + '/' + item.name,
            sourcePath: r.data.path,
            statusMsg: `已打开 ${item.pack}/${item.name} ✓（源 = 仓库路径；💾 保存直写该文件）`,
        });
        closeRepoPanel();
        try { if (window.api.log) window.api.log('info', 'model-editor', `打开为主模型: ${item.pack}/${item.rel}`); } catch (e) {}
    }

    /** W3.5-①：仓库模型 → 附加预览（挂当前所选骨/模型根；会话级，不落盘） */
    async function attachRepoModel(item) {
        if (!doc.model) { setStatus('✗ 先加载主模型（附加预览挂到主模型）'); return; }
        if (!window.api || !window.api.readRepoModel) { setStatus('✗ readRepoModel 不可用（需在主程序内打开）'); return; }
        setStatus(`加载 ${item.pack} / ${item.name}…`);
        const r = await window.api.readRepoModel({ pack: item.pack, rel: item.rel });
        if (!r || !r.ok) { setStatus('✗ ' + ((r && r.error) || '读取模型失败')); return; }
        let geo;
        try { geo = SC.importModel(r.data.content).data; } catch (e) { setStatus('✗ 模型解析失败: ' + e.message); return; }
        // 约定路径贴图（assets/<ns>/textures/<type>/<name>.png）；无则灰色占位
        let attTex = null;
        if (window.api.readRepoTexture) {
            const trel = `assets/${item.ns}/textures/${item.type}/${item.name}.png`;
            const tr = await window.api.readRepoTexture({ source: 'pack', pack: item.pack, rel: trel });
            if (tr && tr.ok) attTex = await vpModule.makeTextureFromDataUrl(tr.data.dataUrl);
        }
        const boneName = (doc.selection && doc.selection.boneName) || null;
        if (!vp.setAttachment({ geo, texture: attTex, boneName, offset: [0, 0, 0] })) {
            setStatus('✗ 附加失败（视口无主模型？）');
            return;
        }
        doc.attachment = { pack: item.pack, rel: item.rel, name: item.name, boneName };
        try { if (window.api.log) window.api.log('info', 'model-editor', `附加预览: ${item.pack}/${item.rel} → 骨 ${boneName || '(模型根)'}`); } catch (e) {}
        closeRepoPanel();
        setStatus(`已附加 ${item.name} → ${boneName || '模型根'} ✓（会话预览；W4 写入契约 parts 持久化）`);
    }

    // ---------- 2026-10-14 插入模型（左栏 🧲 接口/locator 吸附 + 同名状态同步播放） ----------
    /**
     * 仓库模型 → 插入到当前选中接口（locator；未选 = 模型根）。
     * 吸附 = 零偏移挂 locator 组（locator 语义 = 骨 inner 帧绝对位，游戏端 getWorldBonePivot 同款跟随）。
     * 动画 = 载入该模型自己的动画文档；播放时按【与主模型当前动画同名】匹配（A 案：
     * zombie 播 attack → 插入的 sword 播自己的 attack），无同名 = 其 still（若有）/ 静态。
     */
    async function insertRepoModel(item) {
        if (!doc.model) { setStatus('✗ 先加载主模型'); return; }
        if (!window.api || !window.api.readRepoModel) { setStatus('✗ readRepoModel 不可用（需在主程序内打开）'); return; }
        const locName = doc.selectedLocator;
        setStatus(`插入 ${item.pack} / ${item.name} → ${locName || '模型根'}…`);
        const r = await window.api.readRepoModel({ pack: item.pack, rel: item.rel });
        if (!r || !r.ok) { setStatus('✗ ' + ((r && r.error) || '读取模型失败')); return; }
        let geo;
        try { geo = SC.importModel(r.data.content).data; } catch (e) { setStatus('✗ 模型解析失败: ' + e.message); return; }
        // 约定路径贴图
        let attTex = null;
        if (window.api.readRepoTexture) {
            const trel = `assets/${item.ns}/textures/${item.type}/${item.name}.png`;
            const tr = await window.api.readRepoTexture({ source: 'pack', pack: item.pack, rel: trel });
            if (tr && tr.ok) attTex = await vpModule.makeTextureFromDataUrl(tr.data.dataUrl);
        }
        if (!vp.setAttachment({ geo, texture: attTex, locatorName: locName, boneName: null, offset: [0, 0, 0] })) {
            setStatus('✗ 插入失败（视口无主模型？）');
            return;
        }
        // 载入该模型的动画文档（独立于主模型 doc.animations；同名同步用）
        const anims = new Map();
        try {
            const pp = SS.parseModelPath(r.data.path);
            if (pp && pp.ok) {
                const ar = await window.api.loadAnimDoc({ pack: pp.packName, rel: `spark_models/${pp.type}/${pp.ns}/${pp.name}.json` });
                if (ar && ar.ok) {
                    for (const f of ar.data.files) {
                        try {
                            const { data } = SC.importAnimationFile(f.content);
                            for (const [name, anim] of data) if (!anims.has(name)) anims.set(name, anim);
                        } catch (e) { /* 单文件解析失败不阻塞插入 */ }
                    }
                }
            }
        } catch (e) { /* 无动画 = 纯静态插入 */ }
        doc.inserted = { pack: item.pack, rel: item.rel, name: item.name, locatorName: locName, geo, anims, texture: attTex };
        closeRepoPanel();
        renderInsertPanel();
        applyAnimDisplay();
        try { if (window.api.log) window.api.log('info', 'model-editor', `插入模型: ${item.pack}/${item.rel} → 接口 ${locName || '(模型根)'}（${anims.size} 动画）`); } catch (e) {}
        setStatus(`已插入 ${item.name} → ${locName || '模型根'} ✓（${anims.size} 个动画；同名状态随主模型同步播放；会话级不落盘）`);
    }

    function removeInserted() {
        if (!doc.inserted) return;
        const name = doc.inserted.name;
        doc.inserted = null;
        vp.clearAttachment();
        renderInsertPanel();
        applyAnimDisplay();
        setStatus(`已移除插入模型 ${name}`);
    }

    /** 插入模型姿态同步：主模型播哪个动画 → 插入模型播同名动画（同一播放头 t） */
    function applyInsertedDisplay() {
        const ins = doc.inserted;
        if (!ins) return;
        const mainAnim = currentAnimObject();
        let a = mainAnim ? ins.anims.get(mainAnim.name) : null;
        if (!a) a = ins.anims.get('still') || ins.anims.get('3rd_still') || null;
        if (!a) { vp.setAttachmentPose(null); return; } // 静态
        let t = doc.animTime;
        if (a.loop === 'true' && a.durationSec > 0) t = t % a.durationSec;
        else if (a.durationSec > 0) t = Math.min(t, a.durationSec);
        vp.setAttachmentPose(evalAnimPose(ins.geo, a, t));
    }

    /** 左栏 🧲 接口面板：locator 清单（含"模型根"伪项）+ 选中态 + 插入/移除按钮 */
    function renderInsertPanel() {
        const box = $('meLocList');
        if (!box) return;
        box.innerHTML = '';
        const rootRow = document.createElement('div');
        rootRow.className = 'ed-locrow' + (doc.selectedLocator === null ? ' sel' : '');
        rootRow.innerHTML = '<span class="loc-dot">⬡</span><span class="loc-name">模型根</span><span class="note" style="font-size:10px">（无接口时）</span>';
        rootRow.title = '未选接口时插入模型挂到模型根';
        rootRow.onclick = () => { doc.selectedLocator = null; vp.setLocatorHighlight && vp.setLocatorHighlight(null); renderInsertPanel(); };
        box.appendChild(rootRow);
        if (doc.model) {
            for (const b of doc.model.bones) {
                for (const loc of b.locators || []) {
                    if (!loc || !loc.name) continue;
                    const row = document.createElement('div');
                    const sel = doc.selectedLocator === loc.name || doc.selectedLocator === b.name + '/' + loc.name;
                    row.className = 'ed-locrow' + (sel ? ' sel' : '');
                    row.innerHTML = `<span class="loc-dot">⬡</span><span class="loc-name" title="骨 ${b.name} 的 locator">${loc.name}</span><span class="note loc-bone" style="font-size:10px">${b.name}</span>`;
                    row.onclick = () => {
                        const key = vp.locatorGroups.has(loc.name) ? loc.name : (b.name + '/' + loc.name);
                        doc.selectedLocator = key;
                        vp.setLocatorHighlight && vp.setLocatorHighlight(loc.name);
                        renderInsertPanel();
                    };
                    box.appendChild(row);
                }
            }
        } else {
            const note = document.createElement('div');
            note.className = 'note';
            note.style.cssText = 'padding:2px 8px;font-size:10px';
            note.textContent = '（未加载模型）';
            box.appendChild(note);
        }
        const btnIns = $('meBtnInsert');
        const btnRm = $('meBtnInsertRemove');
        if (btnIns) btnIns.disabled = !doc.model;
        if (btnRm) { btnRm.hidden = !doc.inserted; }
        if (doc.inserted) {
            const info = $('meInsertedInfo');
            if (info) info.textContent = `📦 ${doc.inserted.name} → ${doc.inserted.locatorName || '模型根'}（${doc.inserted.anims.size} 动画）`;
        }
    }

    /** W3.5-②：仓库贴图 → 应用到当前模型（视口即时生效 + 写包契约 model.texture） */
    async function previewRepoTexture(item) {
        if (!doc.model) { setStatus('✗ 先加载模型'); return; }
        if (!window.api || !window.api.readRepoTexture) { setStatus('✗ readRepoTexture 不可用（需在主程序内打开）'); return; }
        setStatus(`应用贴图 ${item.name}…`);
        const p = { source: item.source, rel: item.rel };
        if (item.source === 'pack') p.pack = item.pack;
        const r = await window.api.readRepoTexture(p);
        if (!r || !r.ok) { setStatus('✗ ' + ((r && r.error) || '读取贴图失败')); return; }
        const tex = await vpModule.makeTextureFromDataUrl(r.data.dataUrl);
        if (!tex) { setStatus('✗ 贴图解码失败'); return; }
        vp.setTexture(tex); // 10/09 修复：setTexture 漏传贴图参数（之前永远灰色）
        doc.texturePreview = { pack: r.data.pack, rel: r.data.rel, size: r.data.width + '×' + r.data.height };
        doc.textureApplied = { pack: r.data.pack, rel: r.data.rel };
        doc.textureDataUrl = r.data.dataUrl; // W4.7 UV 预览
        requestUvRedraw(); // W5.6 常驻 UV 随贴图同步重绘
        try { if (window.api.log) window.api.log('info', 'model-editor', `应用贴图: ${r.data.pack} ${r.data.rel} (${r.data.width}×${r.data.height})`); } catch (e) {}
        // 持久化：模型已存包内且有契约 → 立即写 model.texture
        const pc = await persistTextureToContract();
        if (pc.ok) {
            setStatus(`已应用贴图 ${item.name}（${r.data.width}×${r.data.height}）✓ ${pc.msg}（⬇ 另存为 = 变体：新模型文件 + 指向新贴图的契约 set）`);
        } else {
            setStatus(`已应用贴图 ${item.name}（${r.data.width}×${r.data.height}）—— ${pc.msg}`);
        }
    }

    // ---------- W4.7 UV/贴图预览（BB 风格：贴图 + 每面 UV 矩形 + 与 3D 双向选择联动） ----------
    // UV 数学与视口同源：self.BedrockUV.faceRects（bone 级 mirror、inflate 不展 UV、零尺寸 cube 跳过 —— 与 buildHierarchy 规则一致）
    const UVF = self.BedrockUV;
    const UV_FACE_COLORS = {
        east: '#ff6b6b', west: '#feca57', up: '#48dbfb',
        down: '#1dd1a1', south: '#5f27cd', north: '#ff9ff3',
    };
    // ---------- W5.1 像素编辑器（UV 面板升级：画笔/橡皮/填充/吸管 + HSVa 滑条选色 + 保存 PNG） ----------
    // 缓冲 = uvEditor 内部 ImageData；显示画布 = meUvCanvas（缩放）；PNG 导出/3D 贴图 = editor 的 1×1px work canvas
    let uvEditor = null;
    let uvShowRects = true;      // UV 面矩形叠层开关
    let uvTexSrcApplied = null;  // 已载入缓冲的 dataURL（doc.textureDataUrl 变化即重载入）
    let uvRafPending = 0;
    let vpTexSyncPending = 0;
    let uvCheckerCv = null, uvCheckerZoom = 0;

    // ---------- W5.6 UV 常驻（替代 W4.7 浮窗）+ 视图模式 3D/分屏/UV + UV 缩放 ----------
    let viewMode = '3d';            // '3d' | 'split' | 'uv'（无模型 = 3d；模型加载后恢复记忆的 mode，默认 split = UV 常驻）
    let persistedViewMode = 'split';
    try { const m0 = localStorage.getItem('me.viewMode'); if (m0 && ['3d', 'split', 'uv', 'anim'].includes(m0)) persistedViewMode = m0; } catch (e) { }
    let uvAllocKey = null;          // 已分配贴图缓冲的 'w×h'（换模型重置；切视图模式不重分配 = 未保存笔迹不丢）
    let uvZoomOverride = 0;         // 0 = 适应窗口（1~8）；>0 = 用户手动缩放（1~32）
    let uvLastZoom = 0;             // 上次实际渲染 zoom（缩放按钮 / Ctrl+滚轮 的增量基准）
    let splitH = 0;                 // 3D/UV 分屏 UV 区高度（px；0 = 自动均分）

    uvEditor = window.PixelEditor.create({
        canvas: $('meUvCanvas'),
        onStroke: () => { requestUvRedraw(); scheduleVpTexSync(); },
        onStrokeEnd: () => { renderUvToolbar(); },
        onPick: () => { renderUvToolbar(); },
        onHover: (p) => {
            const el = $('meUvCursor');
            if (el) el.textContent = (p.x >= 0 && p.y >= 0) ? `(${p.x}, ${p.y})` : '';
        },
    });

    function requestUvRedraw() {
        if (uvRafPending) return;
        uvRafPending = requestAnimationFrame(() => { uvRafPending = 0; if (uvVisible()) renderUvPanel(); });
    }
    /** 每笔结束 → work canvas 重建 THREE 贴图（64×64 上传极便宜） */
    function scheduleVpTexSync() {
        if (vpTexSyncPending) return;
        vpTexSyncPending = requestAnimationFrame(() => {
            vpTexSyncPending = 0;
            if (!doc.model || !uvEditor.getImageData()) return;
            vp.setTexture(vpModule.makeCanvasTexture(uvEditor.getWorkCanvas()));
        });
    }
    /** 透明棋盘底纹（按 zoom 缓存） */
    function uvCheckerPattern(ctx, zoom) {
        if (!uvCheckerCv || uvCheckerZoom !== zoom) {
            uvCheckerCv = document.createElement('canvas');
            uvCheckerCv.width = zoom * 2; uvCheckerCv.height = zoom * 2;
            const p = uvCheckerCv.getContext('2d');
            p.fillStyle = '#45454d'; p.fillRect(0, 0, zoom * 2, zoom * 2);
            p.fillStyle = '#3a3a41';
            p.fillRect(0, 0, zoom, zoom);
            p.fillRect(zoom, zoom, zoom, zoom);
            uvCheckerZoom = zoom;
        }
        return ctx.createPattern(uvCheckerCv, 'repeat');
    }

    /** 与视口同规则的 cube 遍历（返回每面 UV 矩形） */
    function uvCubes(geo) {
        const out = [];
        for (const b of geo.bones) {
            for (let ci = 0; ci < (b.cubes || []).length; ci++) {
                const c = b.cubes[ci];
                if (!c.size[0] || !c.size[1] || !c.size[2]) continue; // 零尺寸 cube 跳过（同视口）
                out.push({
                    bone: b.name,
                    cubeIndex: ci,
                    rects: UVF.faceRects({ size: c.size, uv: c.uv, mirror: b.mirror === true }),
                });
            }
        }
        return out;
    }

    /** from/size 表示（size 可负 = 翻转）→ 正数矩形 {x,y,w,h} */
    function uvRectNorm(r) {
        return {
            x: r[2] < 0 ? r[0] + r[2] : r[0],
            y: r[3] < 0 ? r[1] + r[3] : r[1],
            w: Math.abs(r[2]),
            h: Math.abs(r[3]),
        };
    }

    /** W5.6：确保像素缓冲与当前模型一致（换模型重置；切视图模式不重分配，未保存笔迹不丢） */
    function ensureUvReady() {
        if (!doc.model) return;
        const key = doc.model.textureWidth + 'x' + doc.model.textureHeight;
        if (uvAllocKey === key) return;
        uvAllocKey = key;
        uvEditor.alloc(doc.model.textureWidth, doc.model.textureHeight);
        uvTexSrcApplied = null; // 强制（重新）载入当前贴图
        renderUvToolbar();
    }
    function uvVisible() { return viewMode === 'uv' || viewMode === 'split'; }
    /** W5.6：视图模式切换（3D / 3D+UV 分屏 / UV 2D）· W7：+ anim（动画分页，见 setAnimPage） */
    function setViewMode(mode, { persist = true } = {}) {
        // 2026-10-13：视图切换 = 色板锚点语境消失 → 关闭色板（close 只在分派外写 DOM，安全）
        if (window.ColorPicker && window.ColorPicker.isActive()) window.ColorPicker.close();
        // W7：动画 = 第四档视图模式（🎬 按钮 data-mode="anim"）
        if (mode === 'anim') {
            setAnimPage(true);
            if (persist) { try { localStorage.setItem('me.viewMode', 'anim'); } catch (e) { } }
            return;
        }
        if (!['3d', 'split', 'uv'].includes(mode)) return;
        if (viewMode === 'anim') setAnimPage(false); // 先退出动画页（恢复记忆模式），再落到目标模式
        viewMode = mode;
        const uvView = $('meUvView');
        const canvasWrap = $('meCanvasWrap');
        const centerDiv = $('meCenterDivider');
        const showUv = mode !== '3d';
        uvView.hidden = !showUv;
        centerDiv.hidden = mode !== 'split';
        canvasWrap.style.display = mode === 'uv' ? 'none' : '';
        if (mode === 'split' && splitH) uvView.style.flex = '0 0 ' + splitH + 'px';
        else uvView.style.flex = '';
        for (const b of $('meViewModes').querySelectorAll('button')) b.classList.toggle('active', b.dataset.mode === mode);
        if (persist) { try { localStorage.setItem('me.viewMode', mode); } catch (e) { } }
        if (showUv && doc.model) ensureUvReady();
        requestUvRedraw();
        renderMouseHint();
    }
    /** W5.6：UV 缩放（1~32；0 = 适应窗口） */
    function uvSetZoom(z) {
        uvZoomOverride = Math.max(1, Math.min(32, Math.round(z)));
        requestUvRedraw();
    }

    function renderUvPanel() {
        if (!uvVisible()) return;
        const canvas = $('meUvCanvas');
        const wrap = $('meUvCanvasWrap');
        if (!doc.model) {
            canvas.style.display = 'none';
            $('meUvInfo').textContent = '未加载模型';
            $('meUvDetail').innerHTML = '<div class="note">先加载模型（✨ 新建 / 📂 打开模型 / 📦 资源）</div>';
            return;
        }
        canvas.style.display = '';
        const geo = doc.model;
        const tw = geo.textureWidth, th = geo.textureHeight;
        // W5.6：适应缩放（1~8）或用户手动缩放（1~32；Ctrl+滚轮 / 工具条 −＋/适应）
        const fitZoom = Math.max(1, Math.min(8, Math.floor(Math.min((wrap.clientWidth - 16) / tw, (wrap.clientHeight - 16) / th))));
        const zoom = uvZoomOverride > 0 ? Math.max(1, Math.min(32, uvZoomOverride)) : fitZoom;
        uvLastZoom = zoom;
        canvas.width = tw * zoom;
        canvas.height = th * zoom;
        const ctx = canvas.getContext('2d');
        ctx.imageSmoothingEnabled = false;
        uvEditor.setZoom(zoom); // W5.1：指针→像素映射用

        // ① 贴图（W5.1：像素编辑缓冲 + 透明棋盘底；无贴图 = 全透明底）
        if (doc.textureDataUrl !== uvTexSrcApplied) {
            uvTexSrcApplied = doc.textureDataUrl;
            ctx.fillStyle = '#3f3f46';
            ctx.fillRect(0, 0, canvas.width, canvas.height);
            uvEditor.setTexture(doc.textureDataUrl).then(() => {
                if (uvVisible()) { renderUvPanel(); scheduleVpTexSync(); }
            });
            return; // 异步解码完成后重绘
        }
        {
            const img = uvEditor.getImageData();
            const workCv = uvEditor.getWorkCanvas();
            if (img && workCv.width === tw && workCv.height === th) {
                ctx.fillStyle = uvCheckerPattern(ctx, zoom);
                ctx.fillRect(0, 0, canvas.width, canvas.height);
                ctx.drawImage(workCv, 0, 0, canvas.width, canvas.height);
            } else {
                ctx.fillStyle = '#3f3f46';
                ctx.fillRect(0, 0, canvas.width, canvas.height);
            }
        }
        if (zoom >= 3) {
            ctx.strokeStyle = 'rgba(255,255,255,0.08)';
            ctx.lineWidth = 1;
            ctx.beginPath();
            for (let x = 0; x <= tw; x++) { ctx.moveTo(x * zoom + 0.5, 0); ctx.lineTo(x * zoom + 0.5, canvas.height); }
            for (let y = 0; y <= th; y++) { ctx.moveTo(0, y * zoom + 0.5); ctx.lineTo(canvas.width, y * zoom + 0.5); }
            ctx.stroke();
        }

        // ② 每面 UV 矩形（选中 cube = 黄色高亮；W5.1 可隐藏）
        const cubes = uvCubes(geo);
        const sel = (doc.selection && doc.selection.type === 'cube') ? doc.selection : null;
        let faceCount = 0;
        if (uvShowRects) {
            for (const item of cubes) {
                const isSel = sel && sel.boneName === item.bone && sel.cubeIndex === item.cubeIndex;
                for (const f of UVF.FACES) {
                    const r = item.rects[f];
                    if (!r) continue;
                    const n = uvRectNorm(r);
                    const px = n.x * zoom, py = n.y * zoom, pw = n.w * zoom, ph = n.h * zoom;
                    faceCount++;
                    ctx.fillStyle = isSel ? 'rgba(250,204,21,0.30)' : UV_FACE_COLORS[f] + '3d';
                    ctx.fillRect(px, py, pw, ph);
                    ctx.strokeStyle = isSel ? '#facc15' : UV_FACE_COLORS[f];
                    ctx.lineWidth = isSel ? 2 : 1;
                    ctx.strokeRect(px + 0.5, py + 0.5, Math.max(pw - 1, 1), Math.max(ph - 1, 1));
                    if (pw >= 26 && ph >= 12) {
                        ctx.fillStyle = isSel ? '#facc15' : 'rgba(255,255,255,0.75)';
                        ctx.font = '9px Consolas, monospace';
                        ctx.fillText(f, px + 2, py + 10);
                    }
                }
            }
        }

        // ③ 信息行 + 明细列
        $('meUvInfo').textContent =
            `贴图 ${tw}×${th}` + (doc.textureApplied ? ` · ${doc.textureApplied.rel.split('/').pop()}` : ' · 无贴图（灰底占位）')
            + ` · ${cubes.length} cube / ${faceCount} 面区 · 缩放 ${zoom}x`;
        const detail = $('meUvDetail');
        if (!sel) {
            detail.innerHTML = '<div class="note">未选中 cube<br><br>点击 UV 区域（或在 3D 视口选中 cube）查看每面 UV</div>';
        } else {
            const item = cubes.find((c) => c.bone === sel.boneName && c.cubeIndex === sel.cubeIndex);
            let html = `<div style="color:#7dd3fc;font-weight:600;margin-bottom:4px">${sel.boneName} / cube[${sel.cubeIndex}]</div>`;
            if (item) {
                for (const f of UVF.FACES) {
                    const r = item.rects[f];
                    if (!r) { html += `<div class="ed-srow"><span class="ed-slabel">${f}</span><span class="note">—</span></div>`; continue; }
                    const n = uvRectNorm(r);
                    const flip = (r[2] < 0 || r[3] < 0) ? '（翻转）' : '';
                    html += `<div class="ed-srow"><span class="ed-slabel" style="color:${UV_FACE_COLORS[f]}">${f}</span><span class="note" style="font-family:Consolas,monospace">(${Math.round(n.x)},${Math.round(n.y)}) ${Math.round(n.w)}×${Math.round(n.h)}${flip}</span></div>`;
                }
            }
            detail.innerHTML = html;
        }
    }

    // ---------- W5.1 工具条渲染 / 保存 / 快捷键 ----------
    function rgbaHexStr(rgba) {
        const h2 = (n) => n.toString(16).padStart(2, '0');
        return ('#' + h2(rgba[0]) + h2(rgba[1]) + h2(rgba[2])).toUpperCase()
            + (rgba[3] < 0.995 ? h2(Math.round(rgba[3] * 255)).toUpperCase() : '');
    }
    /** 色板块：透明棋盘底 + 当前色（含 alpha） */
    function swatchBg(el, rgba) {
        const a = (Math.round(rgba[3] * 255) / 255).toFixed(3);
        const col = `rgba(${rgba[0]},${rgba[1]},${rgba[2]},${a})`;
        el.style.background = `linear-gradient(${col},${col}), repeating-conic-gradient(#4a4a52 0 25%, #3a3a41 0 50%)`;
        el.style.backgroundSize = '100% 100%, 8px 8px';
    }
    function renderUvToolbar() {
        const tools = { brush: $('meUvToolBrush'), eraser: $('meUvToolEraser'), fill: $('meUvToolFill'), pick: $('meUvToolPick') };
        const t = uvEditor.getTool();
        for (const k of Object.keys(tools)) tools[k].classList.toggle('active', k === t);
        const s = uvEditor.getSize();
        $('meUvSize1').classList.toggle('active', s === 1);
        $('meUvSize2').classList.toggle('active', s === 2);
        $('meUvSize4').classList.toggle('active', s === 4);
        $('meUvRects').classList.toggle('active', uvShowRects);
        swatchBg($('meUvSwFg'), uvEditor.getFG());
        swatchBg($('meUvSwBg'), uvEditor.getBG());
        $('meUvHex').textContent = rgbaHexStr(uvEditor.getFG());
        $('meUvUndo').disabled = !uvEditor.canUndo();
        $('meUvRedo').disabled = !uvEditor.canRedo();
    }

    /** W5.1 保存贴图：有已应用贴图 → 覆写该文件；无 → 保存对话框（存入内容包 = 复用仓库贴图应用流：视口 + 契约） */
    async function saveUvTexture() {
        if (!doc.model || !uvEditor.getImageData()) { setStatus('✗ 无可编辑贴图'); return; }
        const png = uvEditor.toPngDataUrl();
        const b64 = png.split(',')[1];
        if (doc.textureApplied && doc.textureApplied.pack !== '(主 mod)') {
            const r = await window.api.writeRepoTexture({ pack: doc.textureApplied.pack, rel: doc.textureApplied.rel, pngBase64: b64 });
            if (!r || !r.ok) { setStatus('✗ ' + ((r && r.error) || '保存贴图失败')); return; }
            doc.textureDataUrl = png; // W5.1：同步 dataURL（缓冲重载/模型重开读到的就是新图）
            uvTexSrcApplied = png;
            setStatus(`💾 贴图已保存：${doc.textureApplied.rel.split('/').pop()} → ${r.data.path} ✓`);
            return;
        }
        const r = await window.api.saveTextureDialog({ defaultName: (doc.modelName || 'texture') + '.png', pngBase64: b64 });
        if (!r || !r.ok) { if (r && r.error) setStatus('✗ ' + r.error); return; }
        if (!r.data) return; // 用户取消
        doc.textureDataUrl = png;
        setStatus(`💾 贴图已保存：${r.data.path}`);
        if (r.data.pack && r.data.pack === currentPackName()) {
            await previewRepoTexture({ source: 'pack', pack: r.data.pack, rel: r.data.rel, name: r.data.path.split(/[\\/]/).pop() });
        } else if (r.data.pack) {
            setStatus('💾 贴图已保存（与模型不同包 —— 未应用）');
        }
    }

    /** W5.1 贴图撤销/重做（面板打开且有历史才处理；返回是否已处理，未处理则回落模型 undo） */
    function uvTexUndoRedo(dir) {
        if (!uvVisible() || !doc.model || !uvEditor.getImageData()) return false;
        const ok = dir === 'undo' ? uvEditor.undo() : uvEditor.redo();
        if (ok) { renderUvToolbar(); requestUvRedraw(); scheduleVpTexSync(); }
        return ok;
    }

    // W5.1 工具条接线
    $('meUvToolBrush').onclick = () => { uvEditor.setTool('brush'); renderUvToolbar(); };
    $('meUvToolEraser').onclick = () => { uvEditor.setTool('eraser'); renderUvToolbar(); };
    $('meUvToolFill').onclick = () => { uvEditor.setTool('fill'); renderUvToolbar(); };
    $('meUvToolPick').onclick = () => { uvEditor.setTool('pick'); renderUvToolbar(); };
    $('meUvSize1').onclick = () => { uvEditor.setSize(1); renderUvToolbar(); };
    $('meUvSize2').onclick = () => { uvEditor.setSize(2); renderUvToolbar(); };
    $('meUvSize4').onclick = () => { uvEditor.setSize(4); renderUvToolbar(); };
    $('meUvRects').onclick = () => { uvShowRects = !uvShowRects; renderUvToolbar(); requestUvRedraw(); };
    // W5.6：UV 缩放（按钮 + Ctrl+滚轮；普通滚轮 = 画布超出时滚动，不拦截）
    $('meUvZoomIn').onclick = () => uvSetZoom((uvLastZoom || 1) * 1.5);
    $('meUvZoomOut').onclick = () => uvSetZoom((uvLastZoom || 1) / 1.5);
    $('meUvZoomFit').onclick = () => { uvZoomOverride = 0; requestUvRedraw(); };
    $('meUvCanvasWrap').addEventListener('wheel', (e) => {
        if (!e.ctrlKey && !e.metaKey) return;
        e.preventDefault();
        uvSetZoom((uvLastZoom || 1) * (e.deltaY < 0 ? 1.25 : 0.8));
    }, { passive: false });
    // W5.6：分屏分隔条拖拽 / 视图模式切换 / 窗口缩放 → 重绘 UV 画布（fit zoom 依赖容器尺寸）
    if (typeof ResizeObserver !== 'undefined') new ResizeObserver(() => requestUvRedraw()).observe($('meUvCanvasWrap'));
    $('meUvUndo').onclick = () => { uvTexUndoRedo('undo'); };
    $('meUvRedo').onclick = () => { uvTexUndoRedo('redo'); };
    // W7.1 修复（2026-10-13）：色板不再"点外部关闭"（document 级监听 = 渲染进程无声退出
    // 的触发器）；色块点击 = 开关切换（已开则关）
    $('meUvSwFg').onclick = (e) => {
        if (ColorPicker.isActive()) { ColorPicker.close(); return; }
        ColorPicker.open(e.currentTarget, uvEditor.getFG(), (c) => { uvEditor.setFG(c); renderUvToolbar(); });
    };
    $('meUvSwBg').onclick = (e) => {
        if (ColorPicker.isActive()) { ColorPicker.close(); return; }
        ColorPicker.open(e.currentTarget, uvEditor.getBG(), (c) => { uvEditor.setBG(c); renderUvToolbar(); });
    };
    $('meUvSwSwap').onclick = () => { uvEditor.swapColors(); renderUvToolbar(); };
    $('meUvSaveTex').onclick = () => { saveUvTexture(); };

    /** UV 画布点击 → 3D 选中对应 cube（命中多个时取最小面区 = 更精确；W5.1 画笔涂色与选中并存） */
    $('meUvCanvas').addEventListener('click', (e) => {
        const geo = doc.model;
        if (!geo) return;
        const canvas = $('meUvCanvas');
        const rect = canvas.getBoundingClientRect();
        const zoom = canvas.width / geo.textureWidth;
        const x = (e.clientX - rect.left) / zoom;
        const y = (e.clientY - rect.top) / zoom;
        let best = null;
        for (const item of uvCubes(geo)) {
            for (const f of UVF.FACES) {
                const r = item.rects[f];
                if (!r) continue;
                const n = uvRectNorm(r);
                if (x >= n.x && x <= n.x + n.w && y >= n.y && y <= n.y + n.h) {
                    const area = n.w * n.h;
                    if (!best || area < best.area) best = { area, boneName: item.bone, cubeIndex: item.cubeIndex };
                }
            }
        }
        if (best) {
            doc.selection = { type: 'cube', boneName: best.boneName, cubeIndex: best.cubeIndex };
            vp.setSelection({ boneName: best.boneName, cubeIndex: best.cubeIndex }, { silent: true });
            onSelectionChanged();
        }
    });

    // ---------- 加载 / 保存 ----------
    /** W5.2：模型文本通用管线（spark/bbmodel 自动识别 → applyLoadedModelCore）；文件对话框/仓库/自动恢复共用 */
    function loadModelFromText(text, { name, sourcePath, statusMsg }) {
        const fmt = BB ? BB.sniffModelFormat(text) : 'unknown';
        if (fmt === 'bbmodel') {
            const baseName = name.replace(/\.[^.]+$/, '');
            const r = BB.importBBModel(text, { name: baseName });
            doc.sourceFormat = 'bbmodel';
            applyLoadedModelCore(r.geometry, { displayName: name, sourcePath, statusMsg });
            r.warnings.forEach((w) => console.warn('[bbmodel] ' + w));
            // bbmodel 工程常内嵌 PNG（textures[].source = dataURL）→ 自动贴到视口，与 Blockbench 所见一致
            if (r.texture && r.texture.dataUrl) {
                applyTextureFromDataUrl(r.texture.dataUrl, r.texture.name || 'bbmodel-texture.png');
            }
            return;
        }
        if (fmt !== 'spark') throw new Error('无法识别的模型格式（需要 spark .json 或 Blockbench .bbmodel）');
        doc.sourceFormat = 'spark';
        const { data } = SC.importModel(text);
        applyLoadedModelCore(data, { displayName: name, sourcePath, statusMsg });
    }

    function loadModelFile(file) {
        file.text().then((text) => {
            try {
                const sourcePath = (window.api && window.api.getModelFilePath) ? window.api.getModelFilePath(file) : null;
                loadModelFromText(text, { name: file.name, sourcePath, statusMsg: '已加载 ' + file.name });
            } catch (e) {
                setStatus('加载失败：' + e.message);
            }
        });
    }

    /** W4.6：模型载入核心（文件对话框 / 仓库列表共用；sourcePath = 包内绝对路径或 null） */
    function applyLoadedModelCore(data, { displayName, sourcePath, statusMsg }) {
        if (data && !confirmDiscard('更换当前模型')) return false;
        doc.model = data;
        doc.undoStack = U.createUndoStack(data);
        doc.dirty = false;
        notifyUnsaved();
        doc.selection = null;
        doc.sourceFile = sourcePath || null;
        // 另存为 ns：从源路径解析（<pack>/spark_models/<type>/<ns>/<name>.json）
        doc.saveAsNs = null;
        if (doc.sourceFile) {
            const pp = SS.parseModelPath(doc.sourceFile);
            if (pp.ok) doc.saveAsNs = pp.ns;
        }
        // W5.2：记住最后打开的模型（渲染进程崩溃→自动重载后恢复会话；新建未保存 = 清记录）
        try {
            const pp = sourcePath ? SS.parseModelPath(sourcePath) : null;
            if (pp && pp.ok) {
                localStorage.setItem('me.lastModel', JSON.stringify({
                    kind: 'repo', pack: pp.packName,
                    rel: `spark_models/${pp.type}/${pp.ns}/${pp.name}.json`,
                    name: displayName,
                }));
            } else if (sourcePath) {
                localStorage.setItem('me.lastModel', JSON.stringify({ kind: 'path', path: sourcePath, name: displayName }));
            } else {
                localStorage.removeItem('me.lastModel');
            }
        } catch (e) {}
        doc.attachment = null;
        doc.inserted = null;           // 2026-10-14：换模型 = 插入模型移除
        doc.selectedLocator = null;
        doc.hiddenCubes = new Set();
        vp.setHiddenCubes(doc.hiddenCubes); // W4.9：换模型 = 清空隐藏
        // W7：换模型 = 退出动画分页 + 清会话动画状态（动画文档随新模型异步重载）
        doc.animTime = 0;
        doc.currentAnim = null;
        setAnimPage(false);
        doc.texturePreview = null;
        doc.textureApplied = null;
        doc.textureDataUrl = null; // W4.7（autoApplyContractTexture 随后可能重新设置）
        // 2026-10-14：clearAttachment 必须在 setModel 之前 —— setModel 重建层级会按
        // pendingAttach 重挂；先清参数 = 换模型不带入旧附加
        vp.clearAttachment();
        if (vp.setLocatorHighlight) vp.setLocatorHighlight(null);
        vp.setModel(data);
        $('meModelName').textContent = displayName;
        // W5.6：换模型 → 重置 UV 缓冲/缩放，恢复记忆视图模式（默认 split = UV 常驻）
        uvAllocKey = null; uvZoomOverride = 0; uvTexSrcApplied = null;
        setViewMode(persistedViewMode, { persist: false });
        onSelectionChanged();
        setStatus(statusMsg + (doc.sourceFile ? '' : '（无法取得源路径 —— 💾 保存将触发另存为）'));
        // W3.5-②：打开已有模型 → 从包契约自动应用 model.texture（bbmodel 源无包契约，静默跳过）
        if (doc.sourceFormat !== 'bbmodel') autoApplyContractTexture().catch((e) => console.warn('[texture] ' + e.message));
        // W7：打开仓库模型 → 载入同模型的全套动画文档（异步；bbmodel/路径源无动画目录，静默跳过）
        if (doc.sourceFormat !== 'bbmodel') loadAnimDoc();
    }

    function saveModel() {
        if (!doc.model) { setStatus('✗ 未加载模型'); return; }
        if (!doc.sourceFile) { openSavePanel(); return; } // W3.1：未定位源文件 → 一键保存面板
        const isBb = doc.sourceFormat === 'bbmodel' || /\.bbmodel$/i.test(doc.sourceFile);
        let content;
        try {
            content = isBb ? BB.exportBBModel(doc.model, { name: bbBaseName(), texture: bbTextureOpt() }) : SC.exportModel(doc.model);
        } catch (e) {
            setStatus('✗ ' + e.message);
            return;
        }
        window.api.saveModelFile({ path: doc.sourceFile, content }).then((r) => {
            if (!r) return;
            if (r.ok) {
                doc.dirty = false;
        notifyUnsaved();
                if (isBb) {
                    setStatus(`已保存 ${doc.sourceFile.split(/[\\/]/).pop()} ✓（bbmodel v5；Blockbench 可直接打开）`);
                } else {
                    // W3.5-②：应用过贴图 → 同步写包契约；W7：脏动画文件随模型一起落盘
                    const baseMsg = `已保存 ${doc.sourceFile.split(/[\\/]/).pop()} ✓（规范化形式；BOM 剥离；缺省省略）`;
                    let animMsg = '';
                    let animFailed = '';
                    persistAnims().then((ar) => {
                        if (ar && ar.written.length) animMsg = ` · 动画 ${ar.written.length} 文件`;
                        if (ar && ar.failed.length) animFailed = ` · ✗ 动画失败 ${ar.failed.join('；')}`;
                        persistTextureToContract().then((pc) => {
                            setStatus(baseMsg + animMsg + animFailed + (pc.ok ? ` · ${pc.msg}` : ''));
                            if (!pc.ok) console.warn('[texture] ' + pc.msg);
                            if (ar && ar.failed.length) console.warn('[anim] ' + ar.failed.join('；'));
                        });
                    });
                }
            } else {
                setStatus('保存失败：' + (r.error || '未知错误'));
            }
        });
    }

    /** 导出 bbmodel 用的模型名：源文件名（去扩展）优先，否则 identifier 去前缀 */
    function bbBaseName() {
        if (doc.sourceFile) {
            const base = doc.sourceFile.split(/[\\/]/).pop().replace(/\.[^.]+$/, '');
            if (base) return base;
        }
        return String(doc.model.identifier || '').replace(/^geometry\./, '') || 'model';
    }

    /** 导出 bbmodel 时把当前贴图内嵌回去（Blockbench 才能看到贴图，而不是灰模） */
    function bbTextureOpt() {
        return doc.textureDataUrl ? { name: doc.textureName || 'texture.png', dataUrl: doc.textureDataUrl } : null;
    }

    /** W4.4：导出 .bbmodel（v5）——浏览器下载（外部工程格式，不走包内保存面板） */
    function exportBbModel() {
        if (!doc.model) { setStatus('✗ 未加载模型'); return; }
        let text;
        try {
            text = BB.exportBBModel(doc.model, { name: bbBaseName(), texture: bbTextureOpt() });
        } catch (e) {
            setStatus('✗ ' + e.message);
            return;
        }
        const blob = new Blob([text], { type: 'application/json' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = bbBaseName() + '.bbmodel';
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(() => URL.revokeObjectURL(a.href), 5000);
        setStatus(`已导出 bbmodel ${a.download}（v5；可直接在 Blockbench 打开编辑）`);
    }

    // ---------- 贴图 ----------
    /** 贴图 dataURL → 视口 + UV 面板（「贴图」按钮选文件 / bbmodel 内嵌贴图共用） */
    function applyTextureFromDataUrl(dataUrl, name) {
        return vpModule.makeTextureFromDataUrl(dataUrl).then((tex) => {
            if (!tex) { setStatus('贴图解码失败：' + name); return false; }
            doc.textureDataUrl = dataUrl; // W4.7 UV 预览
            requestUvRedraw(); // W5.6 常驻 UV 随贴图同步重绘
            const img = new Image();
            img.onload = () => {
                doc.textureName = name;
                vp.setTexture(tex);
                const m = doc.model;
                setStatus('贴图：' + name + '（' + img.width + '×' + img.height +
                    (m && (img.width !== m.textureWidth || img.height !== m.textureHeight)
                        ? `，模型声明 ${m.textureWidth}×${m.textureHeight} —— 尺寸不一致，UV 按声明尺寸归一，可能偏移`
                        : '，与模型声明一致') + '）');
            };
            img.onerror = () => setStatus('贴图解码失败：' + name);
            img.src = dataUrl;
            return true;
        });
    }
    function loadTextureFile(file) {
        const reader = new FileReader();
        reader.onload = () => applyTextureFromDataUrl(reader.result, file.name);
        reader.readAsDataURL(file);
    }

    // ---------- W4.2 实体状态映射（主窗口动画页签「3D 中编辑」推送实体上下文） ----------
    // 数据流：readEntity（暂存优先）→ 8 状态输入行 × 契约存在性 → saveEntityDirect 直写正式包
    doc.entityCtx = null;   // {pack, packDir, entityId}
    doc.entityData = null;  // 实体 JSON（readEntity 结果；_staged 标记已剥离）
    doc.animInfo = null;    // Map<动画名, {exists, parseError}>（契约盘点；null = 无契约/未知）

    const statePanel = () => $('meStateGroup');

    /** 8 状态（与主窗口动画页签 templates.melee 一致；状态判定由主 mod determineAnimationState 写死） */
    const STATE_ROWS = [
        ['0', '静止'], ['1', '行走'], ['2', '奔跑'], ['3', '攻击'],
        ['4', '警觉'], ['5', '受击·正'], ['6', '受击·背'], ['7', '蓄力(可选)'],
    ];

    async function applyEntityContext(ctx) {
        if (!ctx || !ctx.packDir || !ctx.entityId) {
            statePanel().hidden = true;
            partsPanel().hidden = true;
            doc.entityCtx = null; doc.entityData = null; doc.animInfo = null;
            doc.healthCfg = null; doc.healthNs = null; doc.healthBones = [];
            return;
        }
        doc.entityCtx = ctx;
        setStatus(`加载实体 ${ctx.entityId} 状态映射…`);
        const r = await window.api.readEntity(ctx.packDir, ctx.entityId);
        if (!r || !r.ok) {
            setStatus(`读取实体失败: ${(r && r.error) || '未知错误'}`);
            doc.entityData = null;
            return;
        }
        const ent = r.data;
        const wasStaged = !!ent._staged;
        delete ent._staged; // 暂存标记不落盘
        doc.entityData = ent;
        // 动画存在性盘点（与主窗口动画页签同源：contract:read 报告）
        doc.animInfo = null;
        const rc = await window.api.readContract(ctx.packDir);
        if (rc && rc.ok && rc.data && Array.isArray(rc.data.sets)) {
            const m = new Map();
            for (const set of rc.data.sets) {
                for (const a of set.animations) m.set(a.name, { exists: !!a.exists, parseError: !!a.parseError });
            }
            doc.animInfo = m;
        }
        renderStateMap();
        statePanel().hidden = false;
        $('meStateEntity').textContent = `${ctx.pack || ''}/${ctx.entityId}` + (wasStaged ? '（草稿）' : '');
        setStatus(`实体 ${ctx.entityId} 状态映射已加载` + (doc.animInfo ? '' : '（无契约：存在性未知）'));
        // W4.3：部件面板随实体上下文一起加载
        loadPartsPanel().catch((e) => console.warn('[parts] ' + e.message));
    }

    function renderStateMap() {
        const box = $('meStateMap');
        if (!box || !doc.entityData) return;
        const anims = doc.entityData.animations || {};
        box.innerHTML = '';
        for (const [key, label] of STATE_ROWS) {
            const name = anims[key] || '';
            let badge = '<span class="ed-sbadge none">—</span>';
            let tip = '未配置';
            if (name) {
                if (!doc.animInfo) { badge = '<span class="ed-sbadge warn">?</span>'; tip = '无契约，无法判定存在性'; }
                else {
                    const info = doc.animInfo.get(name);
                    if (info && info.exists && !info.parseError) { badge = '<span class="ed-sbadge ok">✓</span>'; tip = '动画文件存在'; }
                    else { badge = '<span class="ed-sbadge miss">✗</span>'; tip = info ? '动画文件缺失' : '契约未声明该动画'; }
                }
            }
            const row = document.createElement('div');
            row.className = 'ed-srow';
            row.innerHTML = `<span class="ed-snum">${key}</span><span class="ed-slabel">${label}</span>`;
            const input = document.createElement('input');
            input.className = 'ed-sanim';
            input.value = name;
            input.placeholder = '—';
            input.title = tip;
            row.appendChild(input);
            row.insertAdjacentHTML('beforeend', badge);
            box.appendChild(row);
        }
    }

    async function saveStateMap() {
        if (!doc.entityCtx || !doc.entityData) return;
        // 收集：空值删除键（entitySchema 校验：animations 存在则必须非空）
        const anims = {};
        const rows = boxRows();
        rows.forEach((row, i) => {
            const v = row.querySelector('.ed-sanim').value.trim();
            if (v) anims[STATE_ROWS[i][0]] = v;
        });
        if (Object.keys(anims).length) doc.entityData.animations = anims;
        else delete doc.entityData.animations;
        const r = await window.api.saveEntityDirect(doc.entityCtx.packDir, doc.entityData);
        if (!r || !r.ok) { setStatus(`保存失败: ${(r && r.error) || '未知错误'}`); return; }
        doc.entityData = r.data.entity; // 规范化写回（saveEntity 已清理空字段）
        renderStateMap();
        setStatus(`状态映射已保存: ${doc.entityCtx.pack || ''}/${doc.entityCtx.entityId}（正式包；build 重建 jar 后游戏生效）`);
    }
    function boxRows() { return [...$('meStateMap').querySelectorAll('.ed-srow')]; }

    // 事件
    $('meStateSave').addEventListener('click', saveStateMap);
    $('meStateClose').addEventListener('click', () => { statePanel().hidden = true; });
    if (window.api && window.api.onModelEditorEntityContext) {
        window.api.onModelEditorEntityContext((ctx) => { applyEntityContext(ctx); });
    }
    if (window.api && window.api.getModelEditorPendingContext) {
        // 新窗口加载时拉取待处理上下文（open 早于 renderer 就绪的场景）
        window.api.getModelEditorPendingContext().then((r) => {
            if (r && r.ok && r.data) applyEntityContext(r.data);
        }).catch(() => { /* 非 Electron 环境（独立调试页）忽略 */ });
    }

    // ---------- W4.3 实体部位配置（bodypart 血量/致死/传导/骨骼单元；直写正式包） ----------
    // 格式兼容主 mod EntityBoneHealthConfig（src/core/healthConfig.js 为单一事实源；
    // 该模块是 CJS 无法在浏览器加载，默认生成为其等价复制，校验仍走主进程 validateHealthConfig）
    doc.healthCfg = null;   // {parts:[{partName,maxModuleHealth,isFatal,transmissionToBase,units:[...]}]}
    doc.healthNs = null;    // namespace（entity.namespace || 包目录名 -→_）
    doc.healthBones = [];   // 骨骼名列表（已打开模型优先，否则从实体 model.id 解析）

    const partsPanel = () => $('mePartsGroup');

    function defaultUnit(boneName) {
        return {
            boneName,
            transmissionCoeff: 1.0,
            armorValue: 0.0,
            antiPiercing: 0.0,
            collisionTag: boneName,
            specialEffects: [],
            hitSound: '',
        };
    }
    /** 与 healthConfig.createDefaultHealthConfig 等价：head/body/arm/leg 自动分组 */
    function defaultHealthConfig(boneNames) {
        const isArm = (n) => n.includes('arm');
        const isLeg = (n) => n.includes('leg');
        const isHead = (n) => n.includes('head');
        const parts = ['head', 'body', 'arm', 'leg'].map((partName) => ({
            partName,
            maxModuleHealth: 10.0,
            isFatal: partName === 'head',
            transmissionToBase: 1.0,
            units: [],
        }));
        for (const boneName of boneNames) {
            let partName = 'body';
            if (isHead(boneName)) partName = 'head';
            else if (isArm(boneName)) partName = 'arm';
            else if (isLeg(boneName)) partName = 'leg';
            parts.find((p) => p.partName === partName).units.push(defaultUnit(boneName));
        }
        return { parts };
    }

    /** 部件面板上下文：ns + 骨骼名列表（已打开模型优先，否则 resolveModelFromId 解析） */
    async function ensurePartsContext() {
        if (!doc.entityCtx || !doc.entityData) return { ok: false, msg: '无实体上下文（从主窗口实体编辑器「3D 中编辑」打开）' };
        const ent = doc.entityData;
        const ns = ent.namespace || String(doc.entityCtx.pack || '').replace(/-/g, '_');
        if (!ns) return { ok: false, msg: '无法确定 namespace（实体无 namespace 且包名为空）' };
        doc.healthNs = ns;
        let bones = [];
        let note = '';
        if (doc.model && Array.isArray(doc.model.bones)) {
            bones = doc.model.bones.map((b) => b.name);
        } else if (ent.model && ent.model.id) {
            const r = await window.api.resolveModelFromId(ent.model.id, ent.model.type || 'entity');
            if (r && r.ok && r.data && Array.isArray(r.data.bones)) {
                bones = r.data.bones.map((b) => b.name);
                note = '骨骼列表解析自模型文件（打开模型可可视化核对归属）';
            }
        }
        doc.healthBones = bones;
        return { ok: true, note };
    }

    async function loadPartsPanel() {
        if (!doc.entityCtx || !doc.entityData) { partsPanel().hidden = true; return; }
        const ec = await ensurePartsContext();
        if (!ec.ok) { setStatus(ec.msg); return; }
        const r = await window.api.readHealth(doc.entityCtx.packDir, doc.healthNs, doc.entityCtx.entityId);
        if (r && r.ok && r.data) {
            const cfg = r.data;
            delete cfg._staged;
            doc.healthCfg = cfg;
        } else {
            doc.healthCfg = null;
        }
        $('mePartsEntity').textContent = `${doc.entityCtx.pack || ''}/${doc.entityCtx.entityId}`;
        renderPartsPanel(ec.note);
        partsPanel().hidden = false;
        setStatus(`部位配置已加载${doc.healthCfg ? `（${doc.healthCfg.parts.length} 部位）` : '（尚无配置）'}` + (ec.note ? ' · ' + ec.note : ''));
    }

    function renderPartsPanel(note) {
        const box = $('mePartsBody');
        if (!box) return;
        box.innerHTML = '';
        const cfg = doc.healthCfg;
        if (note) {
            const n = document.createElement('div');
            n.className = 'note';
            n.style.cssText = 'font-size:10px;padding:2px 0';
            n.textContent = note;
            box.appendChild(n);
        }
        if (!cfg || !Array.isArray(cfg.parts)) {
            const d = document.createElement('div');
            d.className = 'note';
            d.style.padding = '6px';
            d.innerHTML = '尚无部位配置。<br>「⚙ 生成默认」= head/body/arm/leg 自动分组；「+ 部位」= 空部位。<br>' +
                (doc.healthBones.length ? `模型骨骼：${doc.healthBones.length} 个` : '无骨骼列表（实体未引用模型）');
            box.appendChild(d);
            return;
        }
        cfg.parts.forEach((part, pi) => {
            const div = document.createElement('div');
            div.className = 'ed-part';
            const head = document.createElement('div');
            head.className = 'ed-part-head';
            const nameI = document.createElement('input');
            nameI.className = 'ed-part-name';
            nameI.value = part.partName || '';
            nameI.title = '部位名（唯一标识）';
            nameI.addEventListener('input', () => { part.partName = nameI.value.trim(); });
            const hpI = document.createElement('input');
            hpI.className = 'ed-part-num';
            hpI.value = String(part.maxModuleHealth != null ? part.maxModuleHealth : 10);
            hpI.title = '部位最大血量 maxModuleHealth';
            hpI.addEventListener('input', () => { part.maxModuleHealth = parseFloat(hpI.value) || 0; });
            const trI = document.createElement('input');
            trI.className = 'ed-part-num';
            trI.value = String(part.transmissionToBase != null ? part.transmissionToBase : 1);
            trI.title = '传导到基础血量倍率 transmissionToBase';
            trI.addEventListener('input', () => { part.transmissionToBase = parseFloat(trI.value) || 0; });
            const fatalL = document.createElement('label');
            fatalL.className = 'ed-part-fatal';
            const fatalC = document.createElement('input');
            fatalC.type = 'checkbox';
            fatalC.checked = !!part.isFatal;
            fatalC.title = '致死性（命中即死，通常 = 头）';
            fatalC.addEventListener('change', () => { part.isFatal = fatalC.checked; });
            fatalL.appendChild(fatalC);
            fatalL.appendChild(document.createTextNode('致死'));
            const delP = document.createElement('button');
            delP.className = 'ed-part-del';
            delP.textContent = '×';
            delP.title = '删除此部位';
            delP.addEventListener('click', () => { cfg.parts.splice(pi, 1); renderPartsPanel(note); });
            head.append(nameI, hpI, trI, fatalL, delP);
            div.appendChild(head);
            (part.units || []).forEach((u, ui) => {
                const ur = document.createElement('div');
                ur.className = 'ed-unit';
                const b = document.createElement('span');
                b.className = 'ed-unit-bone';
                b.textContent = u.boneName;
                b.title = u.boneName;
                const cI = document.createElement('input');
                cI.className = 'ed-part-num';
                cI.value = String(u.transmissionCoeff != null ? u.transmissionCoeff : 1);
                cI.title = '传导系数 transmissionCoeff';
                cI.addEventListener('input', () => { u.transmissionCoeff = parseFloat(cI.value) || 0; });
                const aI = document.createElement('input');
                aI.className = 'ed-part-num';
                aI.value = String(u.armorValue != null ? u.armorValue : 0);
                aI.title = '护甲 armorValue';
                aI.addEventListener('input', () => { u.armorValue = parseFloat(aI.value) || 0; });
                const pI = document.createElement('input');
                pI.className = 'ed-part-num';
                pI.value = String(u.antiPiercing != null ? u.antiPiercing : 0);
                pI.title = '抗贯穿 antiPiercing';
                pI.addEventListener('input', () => { u.antiPiercing = parseFloat(pI.value) || 0; });
                const delU = document.createElement('button');
                delU.className = 'ed-unit-del';
                delU.textContent = '×';
                delU.title = '从该部位移除骨骼';
                delU.addEventListener('click', () => { part.units.splice(ui, 1); renderPartsPanel(note); });
                ur.append(b, cI, aI, pI, delU);
                div.appendChild(ur);
            });
            // 添加骨骼（仅未分配给任何部位的骨骼可选 —— 校验要求骨骼唯一归属）
            const assigned = new Set(cfg.parts.flatMap((p) => (p.units || []).map((x) => x.boneName)));
            const free = doc.healthBones.filter((n) => !assigned.has(n));
            if (free.length) {
                const addR = document.createElement('div');
                addR.className = 'ed-part-addbone';
                const sel = document.createElement('select');
                const ph = document.createElement('option');
                ph.value = '';
                ph.textContent = '+ 添加骨骼…';
                sel.appendChild(ph);
                for (const n of free) {
                    const o = document.createElement('option');
                    o.value = n;
                    o.textContent = n;
                    sel.appendChild(o);
                }
                sel.addEventListener('change', () => {
                    if (!sel.value) return;
                    part.units = part.units || [];
                    part.units.push(defaultUnit(sel.value));
                    renderPartsPanel(note);
                });
                addR.appendChild(sel);
                div.appendChild(addR);
            }
            box.appendChild(div);
        });
    }

    async function saveParts() {
        if (!doc.entityCtx || !doc.healthCfg) { setStatus('无部位配置可保存'); return; }
        const r = await window.api.saveHealthDirect(doc.entityCtx.packDir, doc.healthNs, doc.entityCtx.entityId, doc.healthCfg);
        if (!r || !r.ok) { setStatus(`部位保存失败: ${(r && r.error) || '未知错误'}`); return; }
        setStatus(`部位配置已保存: ${doc.entityCtx.pack || ''}/${doc.entityCtx.entityId}（正式包；build 重建 jar 后游戏生效）`);
    }

    // 事件
    $('mePartsSave').addEventListener('click', saveParts);
    $('mePartsClose').addEventListener('click', () => { partsPanel().hidden = true; });
    $('mePartsGen').addEventListener('click', async () => {
        const ec = await ensurePartsContext();
        if (!ec.ok) { setStatus(ec.msg); return; }
        if (!doc.healthBones.length) { setStatus('无法生成：无骨骼列表（实体未引用模型）'); return; }
        doc.healthCfg = defaultHealthConfig(doc.healthBones);
        renderPartsPanel(ec.note);
        setStatus(`已生成默认部位（4 部位，${doc.healthBones.length} 骨骼自动分组）—— 核对后「💾 保存部位」`);
    });
    $('mePartsAdd').addEventListener('click', async () => {
        const ec = await ensurePartsContext();
        if (!ec.ok) { setStatus(ec.msg); return; }
        if (!doc.healthCfg) doc.healthCfg = { parts: [] };
        doc.healthCfg.parts.push({
            partName: 'part' + (doc.healthCfg.parts.length + 1),
            maxModuleHealth: 10.0,
            isFatal: false,
            transmissionToBase: 1.0,
            units: [],
        });
        renderPartsPanel(ec.note);
    });

    // ---------- 事件 ----------
    $('meBtnNew').onclick = newModel;
    $('meNewCreate').onclick = createNewModel;
    $('meNewCancel').onclick = closeNewPanel;
    $('meNewIdent').addEventListener('keydown', (e) => { if (e.key === 'Enter') createNewModel(); });
    $('meSaveManual').onclick = () => { closeSavePanel(); saveAsFlow(); };
    $('meSaveCancel').onclick = closeSavePanel;
    $('meBtnSaveAs').onclick = () => { if (doc.model) openSavePanel(); };
    $('meBtnRepo').onclick = () => openRepoPanel();
    // 2026-10-14：🧲 接口/插入模型（动画页左栏）
    $('meBtnInsert').onclick = () => openRepoPanel({ insert: true });
    $('meBtnInsertRemove').onclick = removeInserted;
    $('meRepoTabModels').onclick = () => setRepoTab('models');
    $('meRepoTabTextures').onclick = () => setRepoTab('textures');
    $('meRepoSearch').oninput = (e) => { repo.filter = e.target.value || ''; renderRepoList(); };
    $('meRepoCancel').onclick = closeRepoPanel;
    $('meBtnLog').onclick = () => {
        if (window.api && window.api.openLogDir) {
            window.api.openLogDir().then((r) => { if (r && !r.ok) setStatus('✗ ' + (r.error || '打开日志目录失败')); });
        } else {
            setStatus('✗ 日志不可用（需在主程序内打开）');
        }
    };
    // W5.6：视图模式按钮（取代旧「🧵 UV」浮窗按钮 —— UV 常驻，不按按钮弹出）
    for (const b of $('meViewModes').querySelectorAll('button')) b.onclick = () => setViewMode(b.dataset.mode);
    $('meBtnOpen').onclick = () => $('meModelFile').click();
    $('meModelFile').onchange = (e) => { if (e.target.files[0]) { loadModelFile(e.target.files[0]); e.target.value = ''; } };
    $('meBtnTex').onclick = () => $('meTexFile').click();
    $('meTexFile').onchange = (e) => { if (e.target.files[0]) loadTextureFile(e.target.files[0]); };
    $('meBtnSave').onclick = saveModel;
    $('meBtnBbExport').onclick = exportBbModel;
    $('meBtnUndo').onclick = doUndo;
    $('meBtnRedo').onclick = doRedo;
    $('meBtnAddBone').onclick = addBone;
    $('meBtnAddCube').onclick = addCube;
    $('meBtnDup').onclick = duplicateSelected;
    $('meBtnDel').onclick = removeSelected;

    // ---------- W5.4 PS 式鼠标提示（右下角，pieceEditor 同款；UV 面板打开时切 UV 文案） ----------
    // 无模式按钮（W5.4 退役 ✥/⟳/⤢/🚫 + G/R/S）：一切由鼠标手势决定，手柄常驻做轴向精调。
    const HINT_3D = 'PS 式鼠标：左点=选择 / 空白=反选 · 左拖 cube=移动(1px 网格) · 右拖 cube=旋转(15°) · 滚轮悬停 cube=缩放(绕 pivot) · 左拖空白=旋转视角 · 右拖空白=平移 · 滚轮空白=缩放视角(Shift 强制) ｜ gizmo 箭头/环/方块=轴向精调 · Alt 关吸附 · Esc=取消选择';
    const HINT_UV = 'UV 画笔：左=主色 / 右键=副色 · X 互换 · B/E/F/I=画笔/橡皮/填充/吸管 · 1/2/4=笔刷尺寸 · Ctrl+滚轮=缩放 · Ctrl+Z/Y=贴图撤销/重做 · Esc=隐藏 UV';
    const HINT_SPLIT = '3D：左拖=移动 / 右拖=旋转 / 滚轮=缩放（悬停 cube）· 空白=视角 ｜ UV：左=主色 / 右键=副色 · B/E/F/I 工具 · Ctrl+滚轮=UV 缩放 · 点 UV 区=选中 cube · Esc=隐藏 UV';
    function renderMouseHint() {
        const el = $('meMouseHint');
        if (!el) return;
        if (doc.model && viewMode === 'uv') { el.textContent = HINT_UV; return; }
        if (doc.model && viewMode === 'split') { el.textContent = HINT_SPLIT; return; }
        el.textContent = HINT_3D;
    }
    renderMouseHint();

    window.addEventListener('keydown', (e) => {
        // 2026-10-13 真机事故（色板打开态下 window 级 keydown 执行任何逻辑 → 渲染进程
        // 无声退出，CDP 5/5 复现）：色板打开期间本处理器整体让行（零逻辑）。
        // 色板关闭方式 = ✕ 按钮 / 再点色块 / 切换视图模式（见 colorPicker.js 注释）。
        if (window.ColorPicker && window.ColorPicker.isActive()) return;
        if (!doc.model) return;
        const inInput = /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement && document.activeElement.tagName);
        if ((e.ctrlKey || e.metaKey) && !inInput) {
            const k = e.key.toLowerCase();
            if (k === 'z' && !e.shiftKey) { e.preventDefault(); if (!uvTexUndoRedo('undo')) doUndo(); return; }
            if (k === 'y' || (k === 'z' && e.shiftKey)) { e.preventDefault(); if (!uvTexUndoRedo('redo')) doRedo(); return; }
        }
        if (inInput) return;
        // W5.1/W5.6：UV 可见（分屏/UV 模式）时 B/E/F/I/X = 纹理工具 · Esc = 隐藏 UV（回 3D）
        if (uvVisible()) {
            const k = e.key.toLowerCase();
            if (k === 'b') { uvEditor.setTool('brush'); renderUvToolbar(); return; }
            if (k === 'e') { uvEditor.setTool('eraser'); renderUvToolbar(); return; }
            if (k === 'f') { uvEditor.setTool('fill'); renderUvToolbar(); return; }
            if (k === 'i') { uvEditor.setTool('pick'); renderUvToolbar(); return; }
            if (k === 'x') { uvEditor.swapColors(); renderUvToolbar(); return; }
            if (e.key === 'Escape') { setViewMode('3d'); return; }
        }
        // W7 动画模式：Space = 播放/暂停 · ←/→ = 单步 ±1 网格帧
        if (doc.animMode) {
            if (e.key === ' ' || e.code === 'Space') { e.preventDefault(); toggleAnimPlay(); return; }
            if (e.key === 'ArrowLeft') { e.preventDefault(); stopAnimPlay(); stepAnimTime(-1); return; }
            if (e.key === 'ArrowRight') { e.preventDefault(); stopAnimPlay(); stepAnimTime(1); return; }
        }
        // W5.4：G/R/S 模式快捷键退役（PS 式手势取代模式）
        if (e.key === 'Delete' || e.key === 'Backspace') { e.preventDefault(); removeSelected(); }
        else if (e.key === 'Escape') { doc.selection = null; vp.setSelection(null); onSelectionChanged(); }
    });

    window.addEventListener('resize', () => vp.resize());

    // ---------- W4.5/W5.6 面板拖拽调宽（localStorage 持久化；W5.6「在产生滑条之前停止」） ----------
    /** 面板内所有可滚动容器（overflowX auto/scroll；面板本身 overflow:hidden 不滚动） */
    function panelScrollables(panel) {
        const out = [];
        for (const el of panel.querySelectorAll('*')) {
            const ox = getComputedStyle(el).overflowX;
            if (ox === 'auto' || ox === 'scroll') out.push(el);
        }
        return out;
    }
    function anyHScroll(list) {
        for (const el of list) if (el.scrollWidth > el.clientWidth + 1) return true;
        return false;
    }
    /** W5.6：恢复记忆宽度后，若内容已横向溢出（产生滚动条），加宽到无滚动条为止（上限 maxW） */
    function settlePanelNoHScroll(panel, w, maxW) {
        const sc = panelScrollables(panel);
        let guard = 0;
        panel.style.width = w + 'px';
        while (anyHScroll(sc) && w < maxW && guard++ < 300) {
            w += 4;
            panel.style.width = w + 'px';
        }
        return w;
    }

    // 左栏（W5.6 新增，镜像右栏）：拖窄到内容出现横向滚动条时停在上一宽度
    const leftPanel = $('meLeftPanel');
    const leftDivider = $('meLeftDivider');
    if (leftPanel && leftDivider) {
        try {
            const savedW = parseInt(localStorage.getItem('me.leftPanelWidth'), 10);
            if (savedW >= 120 && savedW <= 640) settlePanelNoHScroll(leftPanel, savedW, 480);
        } catch (e) { }
        let draggingLeft = false, leftGoodW = 240, leftSc = [];
        leftDivider.addEventListener('mousedown', (e) => {
            draggingLeft = true;
            leftGoodW = parseInt(leftPanel.style.width, 10) || 240;
            leftSc = panelScrollables(leftPanel);
            leftDivider.classList.add('active');
            document.body.style.userSelect = 'none';
            document.body.style.cursor = 'col-resize';
            e.preventDefault();
        });
        window.addEventListener('mousemove', (e) => {
            if (!draggingLeft) return;
            const mainEl = document.querySelector('.ed-main');
            if (!mainEl) return;
            const rect = mainEl.getBoundingClientRect();
            const w = Math.max(120, Math.min(640, Math.round(e.clientX - rect.left)));
            leftPanel.style.width = w + 'px';
            // 产生横向滚动条 → 回退到最后一个无滚动条宽度（停在滑条之前）
            if (anyHScroll(leftSc)) leftPanel.style.width = leftGoodW + 'px';
            else leftGoodW = w;
        });
        window.addEventListener('mouseup', () => {
            if (!draggingLeft) return;
            draggingLeft = false;
            leftDivider.classList.remove('active');
            document.body.style.userSelect = '';
            document.body.style.cursor = '';
            try { localStorage.setItem('me.leftPanelWidth', leftGoodW + 'px'); } catch (e2) { }
        });
    }

    // 右栏（W4.5；W5.6 同规则：产生横向滚动条即停）
    const rightPanel = $('meRightPanel');
    const rightDivider = $('meRightDivider');
    if (rightPanel && rightDivider) {
        try {
            const savedW = parseInt(localStorage.getItem('me.rightPanelWidth'), 10);
            if (savedW >= 160 && savedW <= 640) settlePanelNoHScroll(rightPanel, savedW, 480);
        } catch (e) { }
        let draggingRight = false, rightGoodW = 320, rightSc = [];
        rightDivider.addEventListener('mousedown', (e) => {
            draggingRight = true;
            rightGoodW = parseInt(rightPanel.style.width, 10) || 320;
            rightSc = panelScrollables(rightPanel);
            rightDivider.classList.add('active');
            document.body.style.userSelect = 'none';
            document.body.style.cursor = 'col-resize';
            e.preventDefault();
        });
        window.addEventListener('mousemove', (e) => {
            if (!draggingRight) return;
            const mainEl = document.querySelector('.ed-main');
            if (!mainEl) return;
            const rect = mainEl.getBoundingClientRect();
            const w = Math.max(160, Math.min(640, Math.round(rect.right - e.clientX)));
            rightPanel.style.width = w + 'px';
            if (anyHScroll(rightSc)) rightPanel.style.width = rightGoodW + 'px';
            else rightGoodW = w;
        });
        window.addEventListener('mouseup', () => {
            if (!draggingRight) return;
            draggingRight = false;
            rightDivider.classList.remove('active');
            document.body.style.userSelect = '';
            document.body.style.cursor = '';
            try { localStorage.setItem('me.rightPanelWidth', rightGoodW + 'px'); } catch (e2) { }
        });
    }

    // 中央 3D/UV 分屏分隔条（W5.6；仅分屏模式可见；上下各留 150px 最小空间）
    const centerDivider = $('meCenterDivider');
    const uvView = $('meUvView');
    const centerWrap = $('meCenterWrap');
    if (centerDivider && uvView && centerWrap) {
        try {
            const savedH = parseInt(localStorage.getItem('me.uvSplitH'), 10);
            if (savedH >= 150 && savedH <= 1200) splitH = savedH;
        } catch (e) { }
        let draggingCenter = false;
        centerDivider.addEventListener('mousedown', (e) => {
            if (viewMode !== 'split') return;
            draggingCenter = true;
            centerDivider.classList.add('active');
            document.body.style.userSelect = 'none';
            document.body.style.cursor = 'row-resize';
            e.preventDefault();
        });
        window.addEventListener('mousemove', (e) => {
            if (!draggingCenter) return;
            const rect = centerWrap.getBoundingClientRect();
            let h = Math.round(rect.bottom - e.clientY);
            h = Math.max(150, Math.min(h, Math.max(150, rect.height - 155)));
            uvView.style.flex = '0 0 ' + h + 'px';
            splitH = h;
        });
        window.addEventListener('mouseup', () => {
            if (!draggingCenter) return;
            draggingCenter = false;
            centerDivider.classList.remove('active');
            document.body.style.userSelect = '';
            document.body.style.cursor = '';
            if (splitH) { try { localStorage.setItem('me.uvSplitH', String(splitH)); } catch (e2) { } }
        });
        if (splitH) uvView.style.flex = '0 0 ' + splitH + 'px';
    }

    onSelectionChanged();
    // W5.6：初始视图模式（无模型 = 3D；模型加载后恢复记忆模式，默认 split = UV 常驻）
    setViewMode('3d', { persist: false });
    renderMouseHint();

    // ---------- 0.2.1：未保存修改上报 + 换模型前确认 ----------
    // 此前 doc.dirty 只写不读：关窗/换模型/新建都静默丢弃未保存编辑。
    function unsavedCount() {
        let n = (doc.model && doc.dirty) ? 1 : 0;
        if (doc.animDirty) n += doc.animDirty.size;
        if (doc.animDeleted) n += doc.animDeleted.size;
        return n;
    }
    function notifyUnsaved() {
        try {
            if (window.api && window.api.modelEditorDirty) window.api.modelEditorDirty(unsavedCount());
        } catch (e) { /* 忽略 */ }
    }
    function confirmDiscard(what) {
        if (!unsavedCount()) return true;
        return window.confirm(`有未保存的修改（${unsavedCount()} 处）。\n${what}会丢失这些修改，继续吗？`);
    }
    setInterval(notifyUnsaved, 1000); // 兜底：任何改动路径都会在 1s 内上报主进程（关窗提示用）

    // ---------- W5.2：页面加载时自动恢复最后打开的模型（崩溃→自动重载/窗口重开后不再"空编辑器"） ----------
    (async function restoreLastModel() {
        if (doc.model) return;
        let saved = null;
        try { saved = JSON.parse(localStorage.getItem('me.lastModel') || 'null'); } catch (e) {}
        if (!saved || typeof saved.name !== 'string') return;
        try {
            let text = null, sourcePath = null;
            if (saved.kind === 'repo' && window.api && window.api.readRepoModel) {
                const r = await window.api.readRepoModel({ pack: saved.pack, rel: saved.rel });
                if (!r || !r.ok) throw new Error((r && r.error) || '仓库文件读取失败');
                text = r.data.content; sourcePath = r.data.path;
            } else if (saved.kind === 'path' && window.api && window.api.readModelByPath) {
                const r = await window.api.readModelByPath({ path: saved.path });
                if (!r || !r.ok) throw new Error((r && r.error) || '文件不存在');
                text = r.data.content; sourcePath = r.data.path;
            } else {
                return;
            }
            loadModelFromText(text, { name: saved.name, sourcePath, statusMsg: '已自动恢复上次打开的模型：' + saved.name });
        } catch (e) {
            try { localStorage.removeItem('me.lastModel'); } catch (e2) {}
            setStatus('自动恢复上次模型失败：' + e.message);
        }
    })();

    // ---------- W7 动画模式（design/ANIMATION_TECH_STACK.md §7：auto-key 姿态编辑 + 播放 + 移动模拟） ----------
    let THREE = null;
    async function ensureThree() {
        // 路径注意：app.js 在 model-editor/ 下 → ../src/lib（viewport/gizmo 在 ui/ 下才用 ../../）
        // 2026-10-13 真机事故：写成 ../../ 打包后解析到 asar 外 resources/src/lib/ → 进动画页必崩
        if (!THREE) THREE = await import('../src/lib/three.module.js');
        return THREE;
    }

    function currentAnimObject() {
        return doc.currentAnim && doc.animations ? doc.animations.get(doc.currentAnim) : null;
    }
    function animTimeSnapped() {
        return Tk.snapToGrid(doc.animTime, Tk.frameGridSec(doc.timeGrid) || Tk.TICK_SEC);
    }
    /** 标脏：该动画所属文件需保存时写出 */
    function markAnimDirty(animName) {
        const file = doc.animFiles[animName] || animName + '.json';
        doc.animDirty.add(file);
        if (window.__meTimeline) window.__meTimeline.refreshDirty();
    }

    /** 载入模型的动画文档（repo 模型 = spark_models/<type>/<ns>/<model>.json → 同级动画目录） */
    function loadAnimDoc() {
        doc.animLoaded = false;
        doc.animations = new Map();
        doc.animFiles = {};
        doc.animFileNames = {};
        doc.animFileContent = {};
        doc.animRel = null;
        doc.animDirty = new Set();
        doc.animDeleted = new Set();
        doc.paramPreview = {};
        virtualGeoCache = null; // 换模型：虚拟几何缓存失效（骨集合变了）
        const cur = doc.currentAnim;
        doc.currentAnim = null;
        const pp = doc.sourceFile ? SS.parseModelPath(doc.sourceFile) : null;
        if (!pp || !pp.ok || !window.api || !window.api.loadAnimDoc) {
            if (window.__meTimeline) window.__meTimeline.refresh();
            return;
        }
        const pack = pp.packName, rel = `spark_models/${pp.type}/${pp.ns}/${pp.name}.json`;
        window.api.loadAnimDoc({ pack, rel }).then((r) => {
            if (!r || !r.ok) { if (r) setStatus('✗ 动画加载失败: ' + r.error); if (window.__meTimeline) window.__meTimeline.refresh(); return; }
            doc.animRel = r.data.animRel;
            let count = 0, dup = 0, failed = 0;
            for (const f of r.data.files) {
                try {
                    const { data } = SC.importAnimationFile(f.content);
                    doc.animFileNames[f.file] = [...data.keys()];
                    for (const [name, anim] of data) {
                        if (doc.animations.has(name)) { dup++; continue; }
                        // W7 真包实测：base_state.json（bundle）与 state.fall.json 等单文件重名
                        // → 首文件（按文件名字典序）优先，重名者跳过（不标脏、不重写该文件）
                        doc.animations.set(name, anim);
                        doc.animFiles[name] = f.file;
                        count++;
                    }
                    doc.animFileContent[f.file] = f.content;
                } catch (e) {
                    failed++;
                    setStatus(`✗ 动画文件解析失败 ${f.file}: ${e.message}`);
                }
            }
            doc.animLoaded = true;
            if (cur && doc.animations.has(cur)) doc.currentAnim = cur;
            // 动画文档载入后重建 undo 初值（统一 {model, animations} 快照）
            if (doc.model && doc.undoStack) doc.undoStack = U.createUndoStack(animUndoState());
            if (window.__meTimeline) window.__meTimeline.refresh();
            if (doc.animations.size && !doc.currentAnim) selectAnim([...doc.animations.keys()][0]);
            setStatus(`动画文档: ${count} 个动画（${r.data.files.length} 文件${dup ? ` · 重名跳过 ${dup}` : ''}${failed ? ` · 失败 ${failed}` : ''}）${doc.animRel ? ' · ' + doc.animRel : ''}`);
        }).catch((e) => { if (window.__meTimeline) window.__meTimeline.refresh(); console.warn('[anim] loadAnimDoc', e); });
    }

    function selectAnim(name) {
        if (!doc.animations || !doc.animations.has(name)) return;
        doc.currentAnim = name;
        doc.animTime = 0;
        if (doc.animMode) applyAnimDisplay();
        if (window.__meTimeline) window.__meTimeline.refresh();
        const anim = currentAnimObject();
        setStatus(`动画: ${name}（${anim.loop} · ${Tk.formatTime(anim.durationSec)}s）`);
    }

    /** 动画姿态求值（带运行时参数预览：动画 params 默认值 + doc.paramPreview 覆盖值） */
    function evalAnimPose(model, anim, t) {
        return Ev.evaluatePose(model, anim, t, { paramValues: doc.paramPreview || {} });
    }

    /** 姿态显示：动画模式 = evaluatePose 绝对姿态；否则 = 静态模型 */
    function applyAnimDisplay() {
        if (!doc.animMode || !doc.model) return;
        const anim = currentAnimObject();
        if (anim) {
            vp.setPose(evalAnimPose(doc.model, anim, doc.animTime));
            gizmo.rebuild();
        }
        applyInsertedDisplay(); // 2026-10-14：插入模型同名状态同步（同一播放头 t）
        if (doc.simOn) updateSimTreadmill();
    }

    /** 进入/退出姿态编辑（Blockbench 式：姿态显示 = evaluate；auto-key 开；cube 结构编辑禁） */
    function setAnimMode(on) {
        if (!doc.model) return;
        doc.animMode = !!on;
        stopAnimPlay();
        if (doc.animMode) {
            // 动画文档可能尚未异步载入（重载 + 记忆动画页恢复的竞态）→ null 安全
            if (!doc.currentAnim) {
                const names = doc.animations ? [...doc.animations.keys()] : [];
                if (names.length) selectAnim(names[0]);
            }
            if (doc.selection && doc.selection.type === 'cube') {
                doc.selection = { type: 'bone', boneName: doc.selection.boneName };
                onSelectionChanged();
            }
            vp.setModel(doc.model, { fit: false });
            applyAnimDisplay();
            setupSimGrid();
        } else {
            vp.setModel(doc.model, { fit: false });
            clearSimGrid();
        }
        if (window.__meTimeline) window.__meTimeline.refresh();
        gizmo.rebuild();
    }

    // ---------- W7 动画分页（视图模式第四档：3D / 分屏 / UV / 🎬动画） ----------
    // 动画页（animPage=true）：隐藏建模专用按钮（顶栏 新建/资源/打开/贴图/另存为/bbmodel）、
    // 左栏按钮组、UV 区（视口 = 3D 姿态显示）；时间线常驻底部；进入姿态编辑（auto-key）。
    // 左栏大纲保持骨骼+cube 可选、右栏配置照旧；退出 = 恢复进入前的视图模式。
    let savedViewMode = null;
    const ANIM_PAGE_HIDE = ['meBtnNew', 'meBtnRepo', 'meBtnOpen', 'meBtnTex', 'meBtnSaveAs', 'meBtnBbExport'];
    function setAnimPage(on) {
        on = !!on;
        if (on) {
            if (doc.animPage && viewMode === 'anim') return;
            if (!doc.model) { setStatus('✗ 未加载模型'); return; }
            doc.animPage = true;
            if (viewMode !== 'anim') savedViewMode = viewMode;
            viewMode = 'anim';
            $('meUvView').hidden = true;
            $('meCenterDivider').hidden = true;
            $('meCanvasWrap').style.display = '';
            $('meTimeline').hidden = false;
            for (const id of ANIM_PAGE_HIDE) { const el = $(id); if (el) el.hidden = true; }
            $('meModelBtns').hidden = true;
            for (const b of $('meViewModes').querySelectorAll('button')) b.classList.toggle('active', b.dataset.mode === 'anim');
            setAnimMode(true);
            outliner.refresh();
            $('meInsertPanel').hidden = false; // 2026-10-14：🧲 接口/插入面板（动画页专属）
            renderInsertPanel();
            if (window.__meTimeline) window.__meTimeline.refresh();
            setStatus('动画分页（auto-key 姿态编辑；Space 播放 · ←/→ 单步；点 3D/分屏/UV 返回建模）');
        } else {
            if (!doc.animPage && viewMode !== 'anim') return;
            doc.animPage = false;
            const back = savedViewMode || '3d';
            savedViewMode = null;
            viewMode = back;
            $('meTimeline').hidden = true;
            for (const id of ANIM_PAGE_HIDE) { const el = $(id); if (el) el.hidden = false; }
            $('meModelBtns').hidden = false;
            setViewMode(back, { persist: false }); // UV/分隔条/画布按 back 恢复
            setAnimMode(false);
            outliner.refresh();
            $('meInsertPanel').hidden = true; // 2026-10-14
            if (window.__meTimeline) window.__meTimeline.refresh();
            setStatus('建模分页');
        }
    }

    // ---------- W7 播放 ----------
    let animPlayRaf = 0, animPlayLastTs = 0;
    function animPlayLoop(ts) {
        if (!doc.animPlaying) return;
        if (!animPlayLastTs) animPlayLastTs = ts;
        const dt = Math.min(0.1, (ts - animPlayLastTs) / 1000) * doc.animSpeed;
        animPlayLastTs = ts;
        const anim = currentAnimObject();
        let t = doc.animTime + dt;
        if (anim) {
            const wrap = Math.max(anim.durationSec, AN.computeDuration(anim));
            if (anim.loop === 'true' && wrap > 0) {
                t = t % wrap;
            } else if (anim.durationSec > 0 && t >= anim.durationSec) {
                t = anim.durationSec;
                doc.animPlaying = false;
            }
        }
        doc.animTime = t;
        applyAnimDisplay();
        if (window.__meTimeline) window.__meTimeline.onTime(doc.animTime);
        if (doc.animPlaying) animPlayRaf = requestAnimationFrame(animPlayLoop);
    }
    function playAnim() {
        const anim = currentAnimObject();
        if (!doc.animMode || !anim) return;
        if (anim.loop !== 'true' && anim.durationSec > 0 && doc.animTime >= anim.durationSec - 1e-6) doc.animTime = 0;
        doc.animPlaying = true;
        animPlayLastTs = 0;
        animPlayRaf = requestAnimationFrame(animPlayLoop);
        if (window.__meTimeline) window.__meTimeline.onPlaying(true);
    }
    function stopAnimPlay() {
        if (!doc.animPlaying && !animPlayRaf) { if (window.__meTimeline) window.__meTimeline.onPlaying(false); return; }
        doc.animPlaying = false;
        if (animPlayRaf) cancelAnimationFrame(animPlayRaf);
        animPlayRaf = 0;
        animPlayLastTs = 0;
        if (window.__meTimeline) window.__meTimeline.onPlaying(false);
    }
    function toggleAnimPlay() {
        if (doc.animPlaying) stopAnimPlay();
        else playAnim();
    }
    function setAnimTime(t) {
        const anim = currentAnimObject();
        const dur = anim && anim.durationSec > 0 ? anim.durationSec : 0;
        doc.animTime = Math.max(0, dur > 0 ? Math.min(t, dur) : t);
        applyAnimDisplay();
        if (window.__meTimeline) window.__meTimeline.onTime(doc.animTime);
    }
    /** 单步 ±1 网格帧（←/→） */
    function stepAnimTime(dir) {
        setAnimTime(doc.animTime + dir * (Tk.frameGridSec(doc.timeGrid) || Tk.TICK_SEC));
    }

    // ---------- W7 auto-key（Blockbench 式：抓骨即建/改关键帧，无 overlay 层） ----------
    // 机制：动画模式下 gizmo 的 getGeometry 返回"虚拟几何"（骨姿态 = 当前 evaluate 绝对姿态，
    // 稳定对象、就地更新）。gizmo 拖拽改虚拟骨 → 回调把绝对姿态换算回通道相对值（rotation 增量 /
    // position 偏移 / scale 绝对值，引擎语义）写入 t=吸附(播放头) 的关键帧 → 重算姿态显示。
    let virtualGeoCache = null;
    let virtualGeoCacheOf = null; // 2026-10-14：缓存归属的模型引用（换模型自失效，见下）
    function virtualAnimGeometry() {
        // 2026-10-14 修复潜在崩溃：缓存只按「空」失效 → auto-restore 与 openRepoModel 的
        // 异步载入交错时（doc.model 已被换、旧缓存还在）骨集合不匹配 → sb 为 undefined →
        // sb.rotation 抛 TypeError（未处理 Promise 拒绝）。改为引用一致性：doc.model 换过
        // 就重建，任何换模型路径都安全。
        if (!virtualGeoCache || virtualGeoCacheOf !== doc.model || !doc.model) {
            virtualGeoCacheOf = doc.model;
            virtualGeoCache = doc.model ? JSON.parse(JSON.stringify(doc.model)) : null;
        }
        if (!virtualGeoCache) return null;
        const anim = currentAnimObject();
        const pose = (anim && doc.model) ? evalAnimPose(doc.model, anim, doc.animTime) : null;
        for (const b of virtualGeoCache.bones) {
            const sb = doc.model.bones && doc.model.bones.find((x) => x.name === b.name);
            const p = pose && pose.get(b.name);
            for (let i = 0; i < 3; i++) {
                b.rotation[i] = p ? p.rotation[i] : (sb && sb.rotation ? sb.rotation[i] : 0);
                b.pivot[i] = p ? p.position[i] : (sb && sb.pivot ? sb.pivot[i] : 0);
                b.scale[i] = p ? p.scale[i] : 1;
            }
        }
        return virtualGeoCache;
    }
    function animGestureChannel(h) {
        if (!h) return null;
        if (h.gizmo === 'move') return 'position';
        if (h.gizmo === 'rotate' || h.gizmo === 'rotateFree') return 'rotation';
        if (h.gizmo === 'scale') return 'scale';
        return null;
    }
    /** 绝对姿态 → 通道相对值（引擎语义：rotation 增量 / position 偏移 / scale 绝对值） */
    function absToRel(boneName, ch, abs) {
        const sb = doc.model.bones.find((b) => b.name === boneName);
        if (!sb) return abs.slice();
        if (ch === 'rotation') return [abs[0] - (sb.rotation[0] || 0), abs[1] - (sb.rotation[1] || 0), abs[2] - (sb.rotation[2] || 0)];
        if (ch === 'position') return [abs[0] - (sb.pivot[0] || 0), abs[1] - (sb.pivot[1] || 0), abs[2] - (sb.pivot[2] || 0)];
        return abs.slice();
    }
    function animGestureStart(info, h) {
        const anim = currentAnimObject();
        if (!anim || info.kind !== 'bone') return;
        const ch = animGestureChannel(h);
        if (!ch) return;
        const t = animTimeSnapped();
        const abs = ch === 'rotation' ? info.bone.rotation : (ch === 'position' ? info.bone.pivot : info.bone.scale);
        const r = Kf.ensureKeyframe(anim, info.bone.name, ch, t, absToRel(info.bone.name, ch, abs));
        if (r.created) {
            doc.animations.set(anim.name, r.anim);
            markAnimDirty(anim.name);
        }
    }
    function animPoseChanged(info, h) {
        let anim = currentAnimObject();
        if (!anim || info.kind !== 'bone') return;
        const ch = animGestureChannel(h);
        if (!ch) return;
        const t = animTimeSnapped();
        const abs = ch === 'rotation' ? info.bone.rotation : (ch === 'position' ? info.bone.pivot : info.bone.scale);
        const r = Kf.setKeyframeValue(anim, info.bone.name, ch, t, absToRel(info.bone.name, ch, abs), true);
        if (r.changed) {
            doc.animations.set(anim.name, r.anim);
            markAnimDirty(anim.name);
            applyAnimDisplay();
        }
    }
    function animGestureEnd(drag, changed, label) {
        if (!changed) return;
        commit(label + ` @${Tk.formatTime(animTimeSnapped())}s`);
    }
    /** W7 验收钩子：模拟一次完整 auto-key 拖拽（建帧 → 写绝对姿态 → 写帧 → 提交 undo） */
    function simAutoKeyDrag(boneName, kind, delta, axis) {
        if (!doc.animMode) return { error: '非动画模式' };
        if (!doc.model.bones.some((b) => b.name === boneName)) return { error: '骨骼不存在: ' + boneName };
        doc.selection = { type: 'bone', boneName };
        onSelectionChanged();
        const geo = virtualAnimGeometry();
        const bone = geo.bones.find((b) => b.name === boneName);
        if (!bone) return { error: '虚拟几何无此骨' };
        const h = kind === 'move' ? { gizmo: 'move', axis: axis || 0 }
            : (kind === 'rotate' ? { gizmo: 'rotate', axis: axis || 0 } : { gizmo: 'scale' });
        const info = { kind: 'bone', bone, sel: { type: 'bone', boneName } };
        animGestureStart(info, h);
        const ch = animGestureChannel(h);
        const arr = ch === 'scale' ? bone.scale : (ch === 'rotation' ? bone.rotation : bone.pivot);
        for (let i = 0; i < 3; i++) {
            const d = Array.isArray(delta) ? (delta[i] || 0) : (delta || 0);
            if (ch === 'scale') arr[i] = Math.min(50, Math.max(0.02, arr[i] * (1 + d)));
            else arr[i] = arr[i] + d;
        }
        animPoseChanged(info, h);
        animGestureEnd({ info, h }, true, 'auto-key ' + boneName + ' ' + ch);
        return { ok: true, ch, t: animTimeSnapped(), anim: currentAnimObject() && currentAnimObject().name };
    }

    // ---------- W7 移动视觉模拟（跑步机：脚底 16px 网格 + 速度预设） ----------
    let simGridObj = null, simGridY = 0;
    async function setupSimGrid() {
        let T;
        try {
            T = await ensureThree();
        } catch (e) {
            // 网格是视觉辅助：加载失败不得拖垮动画页
            console.warn('[anim] 模拟网格加载失败:', e.message);
            return;
        }
        clearSimGrid();
        if (!doc.model) return;
        // 静态几何最低点（cube origin 近似，忽略旋转链 —— 视觉辅助够用）
        let minY = 0;
        for (const b of doc.model.bones) for (const c of b.cubes || []) minY = Math.min(minY, c.origin[1]);
        simGridY = minY;
        simGridObj = new T.GridHelper(16 * 8, 8, 0x3f6212, 0x1a2e05);
        simGridObj.position.set(0, simGridY - 0.05, 0);
        simGridObj.visible = doc.simOn;
        vp.scene.add(simGridObj);
        if (doc.simOn) updateSimTreadmill();
    }
    function clearSimGrid() {
        if (simGridObj) {
            vp.scene.remove(simGridObj);
            if (simGridObj.geometry) simGridObj.geometry.dispose();
            if (simGridObj.material) (Array.isArray(simGridObj.material) ? simGridObj.material : [simGridObj.material]).forEach((m) => m.dispose());
            simGridObj = null;
        }
    }
    function setSim(on) {
        doc.simOn = !!on;
        if (simGridObj) simGridObj.visible = doc.simOn;
        if (doc.simOn && doc.animMode) updateSimTreadmill();
        if (window.__meTimeline) window.__meTimeline.refresh();
    }
    /** 跑步机：网格按 播放头×速度 流动（16px 周期 = 1 方块，无缝循环） */
    function updateSimTreadmill() {
        if (!simGridObj || !doc.simOn) return;
        const px = 16;
        const offset = ((doc.animTime * doc.simSpeed * doc.simDir * px) % px + px) % px;
        simGridObj.position.z = offset;
    }

    // ---------- W7 动画增删（时间线面板回调） ----------
    function newAnim(name) {
        const base = (name || '').trim();
        if (!base) return '动画名缺失';
        if (!doc.animations) doc.animations = new Map(); // 非仓库模型：动画文档从未加载
        if (doc.animations.has(base)) return `动画已存在: ${base}`;
        const anim = AN.createAnimation(base, 'once', []);
        doc.animations.set(base, anim);
        doc.animFiles[base] = base + '.json';
        markAnimDirty(base);
        selectAnim(base);
        return null;
    }
    function deleteAnim(name) {
        const anim = doc.animations.get(name);
        if (!anim) return;
        const file = doc.animFiles[name];
        doc.animations.delete(name);
        delete doc.animFiles[name];
        if (doc.animFileNames[file]) {
            doc.animFileNames[file] = doc.animFileNames[file].filter((n) => n !== name);
            if (!doc.animFileNames[file].length) delete doc.animFileNames[file];
        }
        // 文件内无剩余动画 → 文件待删
        const remaining = Object.keys(doc.animFiles).some((n) => doc.animFiles[n] === file);
        if (!remaining) {
            doc.animDirty.delete(file);
            doc.animDeleted.add(file);
        }
        if (doc.currentAnim === name) {
            const rest = [...doc.animations.keys()];
            if (rest.length) selectAnim(rest[0]);
            else { doc.currentAnim = null; if (window.__meTimeline) window.__meTimeline.refresh(); }
        } else if (window.__meTimeline) window.__meTimeline.refresh();
    }
    /** 时间线/关键帧操作统一入口：不可变更新 + 标脏 + undo 提交 */
    function withAnimMutation(name, fn, label) {
        const anim = doc.animations.get(name);
        if (!anim) return null;
        const r = fn(anim);
        if (!r || r === anim) return null;
        doc.animations.set(name, r);
        markAnimDirty(name);
        commit(label);
        if (doc.animMode) applyAnimDisplay();
        if (window.__meTimeline) window.__meTimeline.refresh();
        return r;
    }

    // ---------- W7 保存：动画文件随模型一起落盘 ----------
    function animsToBundleContent(names) {
        const animations = {};
        for (const n of names) {
            const full = JSON.parse(SC.exportAnimationFile(doc.animations.get(n)));
            animations[n] = full.animations[n];
        }
        return JSON.stringify({ animations }, null, 2) + '\n';
    }
    async function persistAnims() {
        if (!doc.animLoaded || (!doc.animDirty.size && !doc.animDeleted.size)) return null;
        const pp = doc.sourceFile ? SS.parseModelPath(doc.sourceFile) : null;
        if (!pp || !pp.ok || !window.api || !window.api.writeAnimFile) return null;
        const base = { pack: pp.packName, rel: `spark_models/${pp.type}/${pp.ns}/${pp.name}.json` };
        const written = [], failed = [];
        for (const file of [...doc.animDirty]) {
            const names = Object.keys(doc.animFiles).filter((n) => doc.animFiles[n] === file);
            if (!names.length) continue;
            try {
                const content = animsToBundleContent(names);
                const r = await window.api.writeAnimFile({ ...base, file, content });
                if (r && r.ok) { doc.animDirty.delete(file); doc.animFileContent[file] = content; written.push(file); }
                else failed.push(`${file}: ${r && r.error}`);
            } catch (e) { failed.push(`${file}: ${e.message}`); }
        }
        for (const file of [...doc.animDeleted]) {
            try {
                const r = await window.api.deleteAnimFile({ ...base, file });
                if (r && r.ok) { doc.animDeleted.delete(file); delete doc.animFileContent[file]; written.push(file + '（删）'); }
                else failed.push(`${file}: ${r && r.error}`);
            } catch (e) { failed.push(`${file}: ${e.message}`); }
        }
        if (window.__meTimeline) window.__meTimeline.refreshDirty();
        return { written, failed };
    }

    // ---------- W7 帧搬运（时间线面板 🔗 按钮） ----------
    function chainTailToHead(srcName, dstName) {
        const m = doc.model, a = doc.animations.get(srcName), b = doc.animations.get(dstName);
        if (!m || !a || !b) return '源/目标动画缺失';
        const r = Ch.tailToHead(m, a, m, b);
        withAnimMutation(dstName, () => r.anim, `帧搬运 ${srcName}尾→${dstName}头`);
        return r.written.length ? `写入 ${r.written.length} 通道` : '无写入';
    }
    function chainHeadToTail(srcName, dstName) {
        const m = doc.model, a = doc.animations.get(dstName), b = doc.animations.get(srcName);
        if (!m || !a || !b) return '源/目标动画缺失';
        const r = Ch.headToTail(m, a, m, b);
        withAnimMutation(dstName, () => r.anim, `帧搬运 ${srcName}头→${dstName}尾`);
        return r.written.length ? `写入 ${r.written.length} 通道` : '无写入';
    }
    /** 2026-10-14：源状态 → 播放头（源 t=0 姿态 = "该动画的状态"，插入到当前播放头时刻） */
    function chainStateToPlayhead(srcName, dstName) {
        const m = doc.model, a = doc.animations.get(srcName), b = doc.animations.get(dstName);
        if (!m || !a || !b) return '源/目标动画缺失';
        const t = animTimeSnapped();
        const r = Ch.stateToTime(m, a, m, b, t);
        withAnimMutation(dstName, () => r.anim, `帧搬运 ${srcName}状态→${dstName}@${Tk.formatTime(t)}s`);
        return r.written.length ? `写入 ${r.written.length} 通道 @${Tk.formatTime(t)}s` : '无写入';
    }

    // ---------- W7 时间线面板回调实现 ----------
    /** 关键帧 patch 编辑（KfOps 无 lerp/pre 专用 op → 不可变手工克隆） */
    function editKeyframe(anim, bone, ch, t, patch) {
        const out = Kf.cloneAnimation(anim);
        const track = out.tracks.find((x) => x.bone === bone);
        if (!track || !Array.isArray(track[ch])) return anim;
        const k = track[ch].find((x) => Math.abs(x.timeSec - t) <= 1e-6);
        if (!k) return anim;
        if (patch.post !== undefined) k.post = Array.isArray(patch.post) ? patch.post.slice() : patch.post;
        if (patch.preOn === false) delete k.pre;
        if (patch.preOn === true && k.pre === undefined) {
            k.pre = Array.isArray(k.post) ? k.post.slice() : [k.post, k.post, k.post];
        }
        if (patch.pre !== undefined) k.pre = Array.isArray(patch.pre) ? patch.pre.slice() : patch.pre;
        if (patch.lerpMode !== undefined) k.lerpMode = patch.lerpMode;
        return out;
    }
    function notifyAdd(anim, t, script) {
        const out = Kf.cloneAnimation(anim);
        out.timeline = out.timeline || [];
        const e = out.timeline.find((x) => Math.abs(x.timeSec - t) <= 1e-9);
        if (e) { if (!e.scripts.includes(script)) e.scripts.push(script); }
        else {
            out.timeline.push({ timeSec: t, scripts: [script] });
            out.timeline.sort((x, y) => x.timeSec - y.timeSec);
        }
        return out;
    }
    function notifyEdit(anim, t, index, scripts) {
        const out = Kf.cloneAnimation(anim);
        const e = out.timeline && out.timeline[index];
        if (!e) return anim;
        e.scripts = scripts.slice();
        return out;
    }
    function notifyRemove(anim, t, index) {
        const out = Kf.cloneAnimation(anim);
        if (!out.timeline || !out.timeline[index]) return anim;
        out.timeline.splice(index, 1);
        if (out.timeline.length === 0) delete out.timeline;
        return out;
    }

    // ---------- W7 时间线面板实例化 ----------
    window.__meTimeline = self.Timeline.create($('meTimeline'), {
        getState: () => ({
            model: doc.model, animations: doc.animations, currentAnim: doc.currentAnim,
            animTime: doc.animTime, animPlaying: doc.animPlaying, animSpeed: doc.animSpeed,
            timeGrid: doc.timeGrid, animMode: doc.animMode, dirty: doc.animDirty, deleted: doc.animDeleted,
            animFiles: doc.animFiles, animFileNames: doc.animFileNames,
            simOn: doc.simOn, simSpeed: doc.simSpeed, simDir: doc.simDir,
            paramPreview: doc.paramPreview,
        }),
        onSelectAnim: selectAnim,
        onNewAnim: newAnim,
        onDeleteAnim: deleteAnim,
        onSelectBone: (boneName) => {
            doc.selection = { type: 'bone', boneName };
            onSelectionChanged();
        },
        onTime: setAnimTime,
        onPlayToggle: toggleAnimPlay,
        onSpeed: (v) => { doc.animSpeed = v; },
        onGrid: (fps) => { doc.timeGrid = fps; if (window.__meTimeline) window.__meTimeline.refresh(); },
        onLoopChange: (name, loop) => withAnimMutation(name, (anim) => {
            const out = Kf.cloneAnimation(anim);
            out.loop = loop;
            return out;
        }, `loop→${loop} ${name}`),
        onKeyAdd: (bone, ch, t, value) => withAnimMutation(doc.currentAnim, (anim) =>
            Kf.setKeyframeValue(anim, bone, ch, t, value, true).anim, `＋关键帧 ${bone} ${ch}@${Tk.formatTime(t)}s`),
        onKeyRemove: (bone, ch, t) => withAnimMutation(doc.currentAnim, (anim) =>
            Kf.removeKeyframe(anim, bone, ch, t).anim, `删关键帧 ${bone} ${ch}@${Tk.formatTime(t)}s`),
        onKeyMove: (bone, ch, t, newT) => withAnimMutation(doc.currentAnim, (anim) =>
            Kf.moveKeyframeTime(anim, bone, ch, t, newT).anim, `移关键帧 ${bone} ${ch}→${Tk.formatTime(newT)}s`),
        onKeyEdit: (bone, ch, t, patch) => withAnimMutation(doc.currentAnim, (anim) =>
            editKeyframe(anim, bone, ch, t, patch), `改关键帧 ${bone} ${ch}@${Tk.formatTime(t)}s`),
        onNotifyAdd: (name, t, script) => withAnimMutation(name, (anim) => notifyAdd(anim, t, script), `⚡过点@${Tk.formatTime(t)}s`),
        onNotifyEdit: (name, t, index, scripts) => withAnimMutation(name, (anim) => notifyEdit(anim, t, index, scripts), `⚡过点编辑`),
        onNotifyRemove: (name, t, index) => withAnimMutation(name, (anim) => notifyRemove(anim, t, index), `⚡过点删除`),
        onChainTailToHead: chainTailToHead,
        onChainHeadToTail: chainHeadToTail,
        onChainStateToPlayhead: chainStateToPlayhead,
        onSim: setSim,
        onSimSpeed: (v) => { doc.simSpeed = v; updateSimTreadmill(); },
        onSimDir: (d) => { doc.simDir = d; updateSimTreadmill(); },
        // 运行时参数声明（params）：改声明 = 改动画数据（标脏 + undo）；预览值只影响视口，不写盘
        onParamsChange: (animName, params) => {
            const errs = AP.validateParams(params).errors;
            if (errs.length) return errs[0];
            const r = withAnimMutation(animName, (anim) => {
                const out = Kf.cloneAnimation(anim);
                if (Object.keys(params).length) out.params = params;
                else delete out.params;
                return out;
            }, `参数声明 ${animName}`);
            return r ? null : '动画不存在';
        },
        onParamPreview: (animName, name, rawValue) => {
            const v = String(rawValue == null ? '' : rawValue).trim();
            if (v === '') delete doc.paramPreview[name];
            else doc.paramPreview[name] = v;
            if (doc.animMode) applyAnimDisplay();
        },
        onStatus: setStatus,
    });
    // W7：🎬 动画 = 视图模式按钮（meViewModes 内 data-mode="anim"，点击接线在上方 setViewMode 循环）

    // 调试/测试钩子（headless playwright 验收、W4/W5 自动化复用；只读引用，非生产 API）
    window.__me = { vp, gizmo, doc, doUndo, doRedo, saveModel, setStatus, uvEditor, renderUvToolbar, saveUvTexture,
        setViewMode, uvSetZoom, getViewMode: () => viewMode, getUvZoom: () => uvLastZoom,
        // W5.6 CDP 验收钩子：程序化建模型 / 触发选择刷新（只读引用，非生产 API）
        createNewModel, onSelectionChanged,
        // W7 动画验收钩子
        loadAnimDoc, selectAnim, setAnimMode, playAnim, stopAnimPlay, setAnimTime,
        currentAnimObject, virtualAnimGeometry, persistAnims, newAnim, deleteAnim,
        chainTailToHead, chainHeadToTail, chainStateToPlayhead, withAnimMutation, setSim,
        openRepoModel, simAutoKeyDrag, setAnimPage,
        insertRepoModel, removeInserted, renderInsertPanel, applyInsertedDisplay,
        // 运行时参数（params）验收钩子
        evalAnimPose, setParamPreview: (name, value) => { doc.paramPreview[name] = value; applyAnimDisplay(); },
        getParamPreview: () => doc.paramPreview,
    };
})();
