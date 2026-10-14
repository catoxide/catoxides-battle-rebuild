// colorPicker.js - W5.1 HSVa 滑条选色器（自研零依赖；交互参考 vanilla-colorful 布局 + Photopea 实时应用）
// 布局：[色相竖条][SV 方][透明度竖条] + hex/α 标签；拖拽即实时更新（无确定按钮）；Esc / 点外部关闭。
// window.ColorPicker = { open(anchorEl, rgba, onChange), close(), isOpen() }
// rgba = [r,g,b 0-255, a 0-1]
(function () {
    'use strict';

    // ---------- 颜色数学 ----------
    function rgbToHsva(r, g, b, a) {
        r /= 255; g /= 255; b /= 255;
        const max = Math.max(r, g, b), min = Math.min(r, g, b);
        const d = max - min;
        let h = 0;
        if (d) {
            if (max === r) h = ((g - b) / d + 6) % 6;
            else if (max === g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h *= 60;
        }
        return { h, s: max === 0 ? 0 : d / max, v: max, a: a === undefined ? 1 : a };
    }
    function hsvToRgb(h, s, v) {
        h = ((h % 360) + 360) % 360;
        const c = v * s, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = v - c;
        let r = 0, g = 0, b = 0;
        if (h < 60) { r = c; g = x; }
        else if (h < 120) { r = x; g = c; }
        else if (h < 180) { g = c; b = x; }
        else if (h < 240) { g = x; b = c; }
        else if (h < 300) { r = x; b = c; }
        else { r = c; b = x; }
        return [Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255)];
    }
    const hex2 = (n) => n.toString(16).padStart(2, '0');
    function rgbaHex(rgba) {
        return ('#' + hex2(rgba[0]) + hex2(rgba[1]) + hex2(rgba[2])).toUpperCase()
            + (rgba[3] < 0.995 ? hex2(Math.round(rgba[3] * 255)).toUpperCase() : '');
    }
    const clamp01 = (t) => Math.max(0, Math.min(1, t));

    // ---------- DOM ----------
    const SV = 148, BARS = 16; // SV 方边长 / 竖条宽度
    let pop = null, svCv, hueCv, alphaCv, labelEl;
    let hsva = { h: 120, s: 0.6, v: 0.8, a: 1 };
    let onChangeCb = null;

    function mkCanvas(w, h) {
        const c = document.createElement('canvas');
        c.width = w; c.height = h;
        c.style.cssText = 'border:1px solid rgba(255,255,255,.2);border-radius:3px;flex:none;cursor:crosshair';
        return c;
    }

    function attach(cv, fn) {
        let active = false;
        cv.addEventListener('pointerdown', (e) => {
            active = true;
            try { cv.setPointerCapture(e.pointerId); } catch (err) {}
            move(e);
        });
        cv.addEventListener('pointermove', (e) => { if (active) move(e); });
        const end = () => { active = false; };
        cv.addEventListener('pointerup', end);
        cv.addEventListener('pointercancel', end);
        function move(e) {
            const r = cv.getBoundingClientRect();
            fn((e.clientX - r.left) / r.width, (e.clientY - r.top) / r.height);
        }
    }

    const currentRgba = () => {
        const [r, g, b] = hsvToRgb(hsva.h, hsva.s, hsva.v);
        return [r, g, b, hsva.a];
    };

    function drawPointer(ctx, x, y) {
        ctx.beginPath(); ctx.arc(x, y, 4, 0, Math.PI * 2);
        ctx.strokeStyle = '#fff'; ctx.lineWidth = 1.5; ctx.stroke();
        ctx.beginPath(); ctx.arc(x, y, 5.5, 0, Math.PI * 2);
        ctx.strokeStyle = 'rgba(0,0,0,.6)'; ctx.lineWidth = 1; ctx.stroke();
    }

    function drawAll() {
        // SV 方：底色 = 当前色相满饱和 + 白→透明（横）+ 透明→黑（竖）
        const sctx = svCv.getContext('2d');
        const [br, bg, bb] = hsvToRgb(hsva.h, 1, 1);
        sctx.fillStyle = `rgb(${br},${bg},${bb})`;
        sctx.fillRect(0, 0, SV, SV);
        let g = sctx.createLinearGradient(0, 0, SV, 0);
        g.addColorStop(0, 'rgba(255,255,255,1)');
        g.addColorStop(1, 'rgba(255,255,255,0)');
        sctx.fillStyle = g; sctx.fillRect(0, 0, SV, SV);
        g = sctx.createLinearGradient(0, 0, 0, SV);
        g.addColorStop(0, 'rgba(0,0,0,0)');
        g.addColorStop(1, 'rgba(0,0,0,1)');
        sctx.fillStyle = g; sctx.fillRect(0, 0, SV, SV);
        drawPointer(sctx, hsva.s * SV, (1 - hsva.v) * SV);

        // 色相竖条
        const hctx = hueCv.getContext('2d');
        const hg = hctx.createLinearGradient(0, 0, 0, SV);
        for (let i = 0; i <= 6; i++) hg.addColorStop(i / 6, `hsl(${i * 60},100%,50%)`);
        hctx.fillStyle = hg; hctx.fillRect(0, 0, BARS, SV);
        hctx.strokeStyle = '#fff'; hctx.lineWidth = 1.5;
        hctx.strokeRect(0.5, hsva.h / 360 * SV - 2, BARS - 1, 4);

        // 透明度竖条：棋盘底 + 透明→不透明
        const actx = alphaCv.getContext('2d');
        for (let y = 0; y < SV; y += 4) {
            for (let x = 0; x < BARS; x += 4) {
                actx.fillStyle = ((x / 4 + y / 4) % 2 === 0) ? '#4a4a52' : '#3a3a41';
                actx.fillRect(x, y, 4, 4);
            }
        }
        const [ar, ag, ab] = hsvToRgb(hsva.h, hsva.s, hsva.v);
        const agd = actx.createLinearGradient(0, 0, 0, SV);
        agd.addColorStop(0, `rgba(${ar},${ag},${ab},0)`);
        agd.addColorStop(1, `rgba(${ar},${ag},${ab},1)`);
        actx.fillStyle = agd; actx.fillRect(0, 0, BARS, SV);
        actx.strokeStyle = '#fff'; actx.lineWidth = 1.5;
        actx.strokeRect(0.5, (1 - hsva.a) * SV - 2, BARS - 1, 4);

        labelEl.textContent = rgbaHex(currentRgba()) + '  ·  α ' + Math.round(hsva.a * 100) + '%';
    }

    function build() {
        pop = document.createElement('div');
        pop.style.cssText = 'position:fixed;z-index:300;display:none;background:#16181f;border:1px solid rgba(255,255,255,.18);border-radius:8px;padding:10px;box-shadow:0 10px 30px rgba(0,0,0,.5);user-select:none';
        const row = document.createElement('div');
        row.style.cssText = 'display:flex;gap:8px';
        hueCv = mkCanvas(BARS, SV);
        svCv = mkCanvas(SV, SV);
        alphaCv = mkCanvas(BARS, SV);
        row.append(hueCv, svCv, alphaCv);
        labelEl = document.createElement('div');
        labelEl.style.cssText = 'margin-top:8px;font:11px Consolas,monospace;color:#e2e8f0;text-align:center';
        // ✕ 关闭按钮（目标级 click 监听 = 实测安全；取代已废弃的 document 点外部关闭）
        const closeBtn = document.createElement('button');
        closeBtn.textContent = '✕';
        closeBtn.style.cssText = 'position:absolute;top:2px;right:2px;width:18px;height:18px;border:none;border-radius:4px;background:rgba(255,255,255,.08);color:#94a3b8;font-size:11px;line-height:1;cursor:pointer;padding:0';
        closeBtn.addEventListener('click', () => window.ColorPicker.close());
        pop.style.position = 'fixed'; // 保持 fixed（✕ 定位需要）
        pop.append(closeBtn, row, labelEl);
        document.body.appendChild(pop);
        attach(hueCv, (t) => { hsva.h = clamp01(t) * 360; refresh(); });
        attach(svCv, (tx, ty) => { hsva.s = clamp01(tx); hsva.v = 1 - clamp01(ty); refresh(); });
        attach(alphaCv, (t) => { hsva.a = clamp01(t); refresh(); });
        // 2026-10-13 真机事故（CDP 矩阵全量隔离，scratch/cdp-*.js）：
        // pop 打开态时，【任何 window/document 级事件监听器在分派期间执行逻辑】
        // （pointerdown/keydown，哪怕只存变量+排 timer）→ 本机 Chromium 渲染进程无声
        // 退出（5/5 复现，无 WER/crashpad/render-process-gone，软件/硬件渲染均触发；
        // no-op 监听器与目标级监听器实测安全）。因此本组件【不注册任何 document 级
        // 监听器】：关闭 = ✕ 按钮 / 再点色块 / 视图切换 / 程序调用（全部走目标级或
        // 非事件上下文）。app.js 的全局 keydown 在色板打开期间整体让行（isActive）。
    }

    function refresh() {
        drawAll();
        if (onChangeCb) onChangeCb(currentRgba());
    }

    // 关闭状态（DOM/样式写入永远在事件分派之外的独立任务执行；标志同步更新）
    let closeTimer = 0;
    let pickerOpenFlag = false; // 纯布尔（非 DOM）：app.js 全局 keydown 的让行依据

    window.ColorPicker = {
        /** 打开选色器（锚点元素旁；rgba 初始值；拖拽中实时 onChange(rgba)） */
        open(anchorEl, rgba, onChange) {
            if (!pop) build();
            if (closeTimer) { clearTimeout(closeTimer); closeTimer = 0; }
            // 2026-10-13：pointer-events 必须无条件重置（此前仅在 closeTimer 挂起时重置，
            // 完整关闭后再开会残留 pointer-events:none → 色板"幽灵态"不可点击）。
            // 此刻 pop 尚为 display:none（未提升图层）→ 样式写入安全；随后再显示。
            pop.style.pointerEvents = '';
            pickerOpenFlag = true;
            hsva = rgbToHsva(rgba[0] | 0, rgba[1] | 0, rgba[2] | 0, rgba[3]);
            onChangeCb = onChange || null;
            drawAll();
            const r = anchorEl.getBoundingClientRect();
            pop.style.display = 'block';
            const pw = pop.offsetWidth, ph = pop.offsetHeight;
            let left = r.left, top = r.top - ph - 8;
            if (top < 8) top = r.bottom + 8;
            left = Math.max(8, Math.min(left, window.innerWidth - pw - 8));
            pop.style.left = left + 'px';
            pop.style.top = top + 'px';
        },
        close() {
            pickerOpenFlag = false; // 同步清标志（非 DOM）：全局快捷键即刻让行
            if (!pop) return;
            onChangeCb = null; // 非 DOM 操作：立即切断取色回调（防止延迟窗口内继续改色）
            if (closeTimer) return;
            if (pop.style.display === 'none') return;
            // 关键：所有 DOM/样式写入都在事件分派之外的独立任务执行
            closeTimer = setTimeout(() => {
                closeTimer = 0;
                if (!pop) return;
                if (pop.style.display === 'none') return;
                pop.style.pointerEvents = 'none';
                pop.style.display = 'none';
            }, 0);
        },
        isOpen() { return pop && pop.style.display !== 'none'; },
        /** 纯布尔（零 DOM 访问）：供 app.js 全局 keydown 在分派期间安全地判断让行 */
        isActive() { return pickerOpenFlag; },
    };
})();
