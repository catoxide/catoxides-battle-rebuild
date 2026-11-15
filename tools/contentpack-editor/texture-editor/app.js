// app.js - UI 贴图编辑器引导（spec 唯一源 + 实时预览 + tokens/预设/导出）
// 经典 script：core 经 <script> 挂 self.*；所有渲染决策在 core（纯函数），此处只做接线与显示。
// 浏览器无 Buffer：core 走 bytes.js（Uint8Array）；精灵用原生 Image 解码，导出用 canvas.toDataURL。
'use strict';

(function () {
    const $ = (id) => document.getElementById(id);
    const Lib = self.Library, Spec = self.Spec, Render = self.Render, ImageOps = self.ImageOps;

    const preview = self.Preview.createPreview($('teCanvasWrap'));
    const statusEl = $('teStatus');
    const specArea = $('teSpec');

    let libs = null, lib = null;
    let specPath = null;
    let scale = 4, diffOn = false;
    let last = null; // {rgba, W, H}

    function setStatus(msg, kind) {
        statusEl.textContent = msg;
        statusEl.className = 'te-status' + (kind ? ' ' + kind : '');
    }

    // ---------- 精灵解码（dataURL → RGBA） ----------
    function decodeSprite(dataUrl, w, h) {
        return new Promise((resolve, reject) => {
            const im = new Image();
            im.onload = () => {
                const c = document.createElement('canvas'); c.width = w; c.height = h;
                const cx = c.getContext('2d'); cx.imageSmoothingEnabled = false;
                cx.drawImage(im, 0, 0, w, h);
                resolve({ width: w, height: h, data: cx.getImageData(0, 0, w, h).data });
            };
            im.onerror = () => reject(new Error('精灵解码失败'));
            im.src = dataUrl;
        });
    }

    // ---------- 渲染循环 ----------
    function currentSpec() { return Spec.parseSpec(specArea.value); }

    function renderLoop() {
        if (!libs) return;
        try {
            const spec = currentSpec();
            Spec.validateSpec(spec, libs);
            const [W, H] = spec.canvas;
            const rgba = Render.render(spec, libs);
            last = { rgba, W, H };
            if (diffOn) {
                const baseSpec = Object.assign({}, spec); delete baseSpec.tokens;
                const base = Render.render(baseSpec, libs);
                const d = ImageOps.diff(base, rgba, W, H);
                preview.show(d.highlight, W, H, scale);
                setStatus(`diff: ${d.count} 像素相对「去 tokens」基线不同（品红）`, 'ok');
            } else {
                preview.show(rgba, W, H, scale);
                setStatus(`OK ${W}×${H} · ${spec.layers.length} 层`, 'ok');
            }
            renderLayers(spec);
            syncTokens(spec);
        } catch (e) {
            setStatus(e.message, 'err');
        }
    }

    function renderLayers(spec) {
        const box = $('teLayers');
        box.textContent = '';
        (spec.layers || []).forEach((l) => {
            const row = document.createElement('div');
            row.className = 'te-layerrow';
            const t = document.createElement('span'); t.className = 't'; t.textContent = l.type;
            const i = document.createElement('span'); i.className = 'i'; i.textContent = l.id || '(无 id)';
            row.append(t, i);
            box.appendChild(row);
        });
    }

    // ---------- tokens 面板 ----------
    function buildTokens() {
        const box = $('teTokens');
        box.textContent = '';
        for (const [role, hex] of Object.entries(lib.roles)) {
            if (hex === null) continue; // transparent 不给色板
            const row = document.createElement('div'); row.className = 'te-tokenrow';
            const input = document.createElement('input'); input.type = 'color'; input.dataset.role = role; input.value = hex;
            const name = document.createElement('span'); name.className = 'name'; name.textContent = role;
            const reset = document.createElement('button'); reset.textContent = '重置'; reset.title = '移除覆盖，回原版色';
            reset.style.cssText = 'background:none;border:none;color:#6b7280;font-size:11px;cursor:pointer;padding:0;';
            input.addEventListener('input', () => applyTokens((s) => { (s.tokens = s.tokens || {})[role] = input.value.toUpperCase(); }));
            reset.addEventListener('click', () => applyTokens((s) => { if (s.tokens) { delete s.tokens[role]; if (!Object.keys(s.tokens).length) delete s.tokens; } }));
            row.append(input, name, reset);
            box.appendChild(row);
        }
    }
    function syncTokens(spec) {
        const tokens = (spec && spec.tokens) || {};
        for (const input of $('teTokens').querySelectorAll('input[type=color]')) {
            const role = input.dataset.role;
            input.value = tokens[role] || lib.roles[role];
        }
    }
    function applyTokens(mutate) {
        let spec;
        try { spec = currentSpec(); } catch (e) { setStatus(e.message, 'err'); return; }
        mutate(spec);
        specArea.value = JSON.stringify(spec, null, 2);
        renderLoop();
    }

    // ---------- 导出 PNG 到包 ----------
    async function exportPng() {
        if (!last) { setStatus('无可导出渲染结果', 'err'); return; }
        const c = document.createElement('canvas'); c.width = last.W; c.height = last.H;
        c.getContext('2d').putImageData(new ImageData(new Uint8ClampedArray(last.rgba), last.W, last.H), 0, 0);
        const base64 = c.toDataURL('image/png').split(',')[1];
        const packs = await window.api.listModelPacks();
        if (!packs.ok || !packs.data.length) { setStatus('无可用包（先设置项目根）', 'err'); return; }
        const pack = await window.askChoice({ title: '导出 PNG', label: '目标包', options: packs.data.map(p => p.name) });
        if (!pack) return;
        let name = 'panel';
        try { name = currentSpec().name || 'panel'; } catch (e) { /* 用默认名 */ }
        const rel = await window.askText({ title: '导出 PNG', label: '包内相对路径', value: `assets/minecraft/textures/gui/${name}.png` });
        if (!rel) return;
        const r = await window.api.writeRepoTexture({ pack, rel, pngBase64: base64 });
        setStatus(r.ok ? `已导出 → ${r.data.path}` : ('导出失败: ' + r.error), r.ok ? 'ok' : 'err');
    }

    // ---------- 启动 ----------
    async function boot() {
        const r = await window.api.texLoadLibrary();
        if (!r.ok) { setStatus('加载元素库失败: ' + r.error, 'err'); return; }
        const sprites = {};
        for (const [name, def] of Object.entries(r.data.sprites)) sprites[name] = await decodeSprite(def.dataUrl, def.width, def.height);
        lib = Lib.createLibrary(r.data.elements, sprites);
        libs = { vanilla: lib };
        buildTokens();

        const presets = await window.api.texListPresets();
        const sel = $('tePreset');
        sel.textContent = '';
        (presets.ok ? presets.data : []).forEach((n) => { const o = document.createElement('option'); o.value = n; o.textContent = n; sel.appendChild(o); });

        // 默认载入第一个预设
        if (sel.options.length) {
            const p = await window.api.texReadPreset(sel.value);
            if (p.ok) { specArea.value = JSON.stringify(p.data, null, 2); }
        }
        renderLoop();
    }

    // ---------- 事件 ----------
    let debounce = null;
    specArea.addEventListener('input', () => { clearTimeout(debounce); debounce = setTimeout(renderLoop, 150); });
    $('teScale').addEventListener('change', (e) => { scale = parseInt(e.target.value, 10); renderLoop(); });
    $('teDiff').addEventListener('change', (e) => { diffOn = e.target.checked; renderLoop(); });
    $('teLoadPreset').addEventListener('click', async () => {
        const name = $('tePreset').value;
        if (!name) return;
        const p = await window.api.texReadPreset(name);
        if (p.ok) { specArea.value = JSON.stringify(p.data, null, 2); specPath = null; renderLoop(); }
        else setStatus(p.error, 'err');
    });
    $('teOpenSpec').addEventListener('click', async () => {
        const r = await window.api.texOpenSpec();
        if (r.ok && r.data) { specArea.value = r.data.content; specPath = r.data.path; renderLoop(); }
        else if (!r.ok) setStatus(r.error, 'err');
    });
    $('teSaveSpec').addEventListener('click', async () => {
        const r = await window.api.texSaveSpec({ path: specPath, content: specArea.value });
        if (r.ok) { specPath = r.data.path; setStatus('已保存 spec → ' + r.data.path, 'ok'); }
        else setStatus(r.error, 'err');
    });
    $('teExport').addEventListener('click', () => { exportPng().catch((e) => setStatus(String(e.message || e), 'err')); });

    boot().catch((e) => setStatus(String(e.message || e), 'err'));
})();