// preview.js - 2D 预览画布（nearest 放大，透明区透出棋盘格背景）
// 经典 script：self.Preview。只做显示，不做渲染决策（渲染在 core/render.js）。
(function () {
    'use strict';

    function createPreview(wrap) {
        const canvas = document.createElement('canvas');
        wrap.appendChild(canvas);
        const ctx = canvas.getContext('2d');
        const off = document.createElement('canvas');
        const offctx = off.getContext('2d');

        /** rgba = Uint8Array/Buffer（W*H*4）；scale 整数倍 nearest。 */
        function show(rgba, W, H, scale) {
            off.width = W; off.height = H;
            offctx.putImageData(new ImageData(new Uint8ClampedArray(rgba), W, H), 0, 0);
            canvas.width = W * scale; canvas.height = H * scale;
            ctx.imageSmoothingEnabled = false;
            ctx.clearRect(0, 0, canvas.width, canvas.height);
            ctx.drawImage(off, 0, 0, W, H, 0, 0, W * scale, H * scale);
        }
        function clear() { ctx.clearRect(0, 0, canvas.width, canvas.height); }
        return { show, clear, canvas };
    }

    self.Preview = { createPreview };
})();