// pixelEditor.js - W5.1 像素编辑器核心：像素缓冲 + 工具（画笔/橡皮/填充/吸管）+ 撤销重做 + 主/副色
// 交互参考：Piskel（左键主色/右键副色、X 互换、色板）· Aseprite（填充、笔刷尺寸）· Blockbench（像素网格、面区高亮联动）
// 数据模型：ImageData 缓冲（源真值）+ 1×1px work canvas（PNG 导出 / THREE 贴图同源）
// 透明度 = 像素 alpha 通道（游戏端 Spark 渲染为 cutout：alpha<0.5 丢弃）
// window.PixelEditor = { create({canvas, onStroke, onStrokeEnd, onPick, onHover}) }
(function () {
    'use strict';

    const TOOLS = ['brush', 'eraser', 'fill', 'pick'];
    const MAX_HISTORY = 30;
    // MC 风预设色板（zombie 皮肤常用色调 + 中性色）
    const PRESET = [
        [255, 255, 255, 1], [200, 200, 200, 1], [128, 128, 128, 1], [70, 70, 70, 1], [15, 15, 15, 1],
        [220, 45, 45, 1], [90, 160, 50, 1], [40, 40, 200, 1], [235, 195, 40, 1], [205, 125, 35, 1],
        [155, 70, 185, 1], [125, 85, 45, 1],
    ];

    function create({ canvas, onStroke, onStrokeEnd, onPick, onHover }) {
        let w = 0, h = 0, img = null; // ImageData 缓冲
        const work = document.createElement('canvas'); // 1×1px work canvas
        const wctx = work.getContext('2d');
        let history = [], future = [];
        let tool = 'brush', size = 1;
        let fg = [90, 160, 50, 1], bg = [255, 255, 255, 1];
        let drawing = false, lastPt = null, strokeColor = null;
        let zoom = 8; // 显示画布缩放（app.js 每次重绘时同步）
        let recent = [];
        try { recent = JSON.parse(localStorage.getItem('meRecentColors') || '[]'); } catch (e) { recent = []; }

        function alloc(wNew, hNew) {
            w = wNew; h = hNew;
            work.width = w; work.height = h;
            img = new ImageData(w, h); // 全零 = 全透明
            syncWork();
            history = []; future = [];
        }
        function syncWork() { if (img) wctx.putImageData(img, 0, 0); }

        /** 从 dataURL 载入缓冲（无 = 清空）；尺寸不匹配时最近邻缩放填充 */
        function setTexture(dataUrl) {
            return new Promise((resolve) => {
                if (!img) { resolve(); return; }
                if (!dataUrl) {
                    img.data.fill(0); syncWork();
                    history = []; future = [];
                    resolve(); return;
                }
                const im = new Image();
                im.onload = () => {
                    const t = document.createElement('canvas');
                    t.width = w; t.height = h;
                    const tc = t.getContext('2d');
                    tc.imageSmoothingEnabled = false;
                    tc.drawImage(im, 0, 0, w, h);
                    img.data.set(tc.getImageData(0, 0, w, h).data);
                    syncWork();
                    history = []; future = [];
                    resolve();
                };
                im.onerror = () => resolve();
                im.src = dataUrl;
            });
        }

        // ---------- 像素操作 ----------
        const inBounds = (x, y) => x >= 0 && y >= 0 && x < w && y < h;
        const idx = (x, y) => (y * w + x) * 4;
        function getAt(x, y) {
            if (!inBounds(x, y)) return [0, 0, 0, 0];
            const i = idx(x, y);
            return [img.data[i], img.data[i + 1], img.data[i + 2], img.data[i + 3] / 255];
        }
        function setPx(x, y, rgba) {
            const i = idx(x, y);
            img.data[i] = rgba[0]; img.data[i + 1] = rgba[1]; img.data[i + 2] = rgba[2];
            img.data[i + 3] = Math.round(rgba[3] * 255);
        }
        /** 方形笔刷（size×size） */
        function stamp(x, y, rgba) {
            for (let dy = 0; dy < size; dy++) {
                for (let dx = 0; dx < size; dx++) {
                    const X = x + dx, Y = y + dy;
                    if (inBounds(X, Y)) setPx(X, Y, rgba);
                }
            }
        }
        function line(x0, y0, x1, y1, rgba) { // Bresenham
            const dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
            const sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
            let err = dx - dy;
            for (let guard = 0; guard < (w + h) * 2; guard++) {
                stamp(x0, y0, rgba);
                if (x0 === x1 && y0 === y1) return;
                const e2 = 2 * err;
                if (e2 > -dy) { err -= dy; x0 += sx; }
                if (e2 < dx) { err += dx; y0 += sy; }
            }
        }
        /** 4-邻域精确匹配填充（像素画比容差填充更可预测；返回填充像素数） */
        function floodFill(x, y, rgba) {
            const i0 = idx(x, y);
            const tr = img.data[i0], tg = img.data[i0 + 1], tb = img.data[i0 + 2], ta = img.data[i0 + 3];
            const fr = rgba[0], fgc = rgba[1], fb = rgba[2], fa = Math.round(rgba[3] * 255);
            if (tr === fr && tg === fgc && tb === fb && ta === fa) return 0;
            const stack = [x, y];
            let n = 0;
            while (stack.length) {
                const Y = stack.pop(), X = stack.pop();
                const i = idx(X, Y);
                if (img.data[i] === fr && img.data[i + 1] === fgc && img.data[i + 2] === fb && img.data[i + 3] === fa) continue;
                if (img.data[i] !== tr || img.data[i + 1] !== tg || img.data[i + 2] !== tb || img.data[i + 3] !== ta) continue;
                img.data[i] = fr; img.data[i + 1] = fgc; img.data[i + 2] = fb; img.data[i + 3] = fa;
                n++;
                if (X > 0) stack.push(X - 1, Y);
                if (X < w - 1) stack.push(X + 1, Y);
                if (Y > 0) stack.push(X, Y - 1);
                if (Y < h - 1) stack.push(X, Y + 1);
            }
            return n;
        }

        // ---------- 撤销/重做 ----------
        function pushUndo() {
            history.push(new Uint8ClampedArray(img.data));
            if (history.length > MAX_HISTORY) history.shift();
            future.length = 0;
        }
        function undo() {
            if (!history.length) return false;
            future.push(new Uint8ClampedArray(img.data));
            img.data.set(history.pop());
            syncWork();
            return true;
        }
        function redo() {
            if (!future.length) return false;
            history.push(new Uint8ClampedArray(img.data));
            img.data.set(future.pop());
            syncWork();
            return true;
        }
        function pushRecent(rgba) {
            const key = rgba.join(',');
            recent = recent.filter((c) => c.join(',') !== key);
            recent.unshift(rgba.slice());
            recent = recent.slice(0, 12);
            try { localStorage.setItem('meRecentColors', JSON.stringify(recent)); } catch (e) {}
        }

        // ---------- 指针事件（挂在缩放显示画布上） ----------
        function canvasPt(e) {
            const rect = canvas.getBoundingClientRect();
            return { x: Math.floor((e.clientX - rect.left) / zoom), y: Math.floor((e.clientY - rect.top) / zoom) };
        }
        canvas.addEventListener('contextmenu', (e) => e.preventDefault());
        canvas.addEventListener('pointerdown', (e) => {
            if (e.button !== 0 && e.button !== 2) return;
            const p = canvasPt(e);
            if (!inBounds(p.x, p.y)) return;
            const color = e.button === 2 ? bg : fg;
            if (tool === 'pick') {
                const c = getAt(p.x, p.y);
                if (e.button === 2) bg = c.slice(); else fg = c.slice();
                if (onPick) onPick(c, e.button === 2);
                return;
            }
            pushUndo();
            drawing = true;
            lastPt = p;
            try { canvas.setPointerCapture(e.pointerId); } catch (err) {}
            if (tool === 'fill') {
                const n = floodFill(p.x, p.y, color);
                drawing = false;
                syncWork();
                pushRecent(color.slice());
                if (onStrokeEnd) onStrokeEnd();
                if (onStroke) onStroke(n);
                return;
            }
            strokeColor = (tool === 'eraser') ? [0, 0, 0, 0] : color.slice();
            stamp(p.x, p.y, strokeColor);
            syncWork();
            if (onStroke) onStroke(1);
        });
        canvas.addEventListener('pointermove', (e) => {
            const p = canvasPt(e);
            if (onHover) onHover(p);
            if (!drawing || (tool !== 'brush' && tool !== 'eraser')) return;
            if (!inBounds(p.x, p.y) || (p.x === lastPt.x && p.y === lastPt.y)) return;
            line(lastPt.x, lastPt.y, p.x, p.y, strokeColor);
            lastPt = p;
            syncWork();
            if (onStroke) onStroke(1);
        });
        const endStroke = () => {
            if (!drawing) return;
            drawing = false;
            if (tool === 'brush' && strokeColor) pushRecent(strokeColor);
            if (onStrokeEnd) onStrokeEnd();
        };
        canvas.addEventListener('pointerup', endStroke);
        canvas.addEventListener('pointercancel', endStroke);
        canvas.addEventListener('pointerleave', () => { if (onHover) onHover({ x: -1, y: -1 }); });

        return {
            alloc, setTexture,
            getWorkCanvas: () => work,
            getImageData: () => img,
            getDims: () => ({ w, h }),
            setZoom: (z) => { zoom = Math.max(1, z); },
            setTool: (t) => { if (TOOLS.includes(t)) { tool = t; return true; } return false; },
            getTool: () => tool,
            setSize: (s) => { size = Math.max(1, Math.min(8, s | 0)); },
            getSize: () => size,
            getFG: () => fg.slice(),
            setFG: (c) => { fg = c.slice(); },
            getBG: () => bg.slice(),
            setBG: (c) => { bg = c.slice(); },
            swapColors: () => { const t = fg; fg = bg; bg = t; },
            undo, redo,
            canUndo: () => history.length > 0,
            canRedo: () => future.length > 0,
            toPngDataUrl: () => work.toDataURL('image/png'),
            getAt,
            getRecent: () => recent.slice(),
            PRESET,
        };
    }

    window.PixelEditor = { create, TOOLS, PRESET };
})();
