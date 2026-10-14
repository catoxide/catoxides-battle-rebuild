// askModal.js - 页面内模态框（替代 window.prompt：Electron 不支持 prompt()，会抛 "prompt() is not supported"）
// 暴露：
//   window.askText({ title, label, placeholder, value, confirmLabel, cancelLabel })
//       → Promise<string|null>（null = 取消）
//   window.askChoice({ title, label, options: string[], value })
//       → Promise<string|null>（选中的 option 值）
// 自包含：注入 DOM + 最小 CSS；同一时刻只允许一个模态（后开者排队等待）。
(function () {
    'use strict';
    let queue = Promise.resolve();

    function ensureStyle() {
        if (document.getElementById('askModalStyle')) return;
        const st = document.createElement('style');
        st.id = 'askModalStyle';
        st.textContent = [
            '.askm-overlay { position: fixed; inset: 0; z-index: 200; display: flex; align-items: center; justify-content: center; background: rgba(0,0,0,.55); }',
            '.askm-card { width: 420px; max-width: 92vw; background: #16181f; border: 1px solid rgba(255,255,255,.14); border-radius: 8px; padding: 14px 16px; box-shadow: 0 12px 40px rgba(0,0,0,.5); color: #e2e8f0; font-size: 13px; }',
            '.askm-title { font-weight: 600; margin-bottom: 10px; }',
            '.askm-label { display: block; margin: 8px 0 4px; color: #94a3b8; font-size: 12px; }',
            '.askm-input { width: 100%; box-sizing: border-box; padding: 6px 8px; background: #0f1117; border: 1px solid rgba(255,255,255,.14); border-radius: 4px; color: #e2e8f0; font-size: 13px; }',
            '.askm-input:focus { outline: 1px solid #38bdf8; }',
            '.askm-btns { display: flex; gap: 8px; justify-content: flex-end; margin-top: 14px; }',
            '.askm-btns button { padding: 5px 16px; border-radius: 4px; border: 1px solid rgba(255,255,255,.18); background: rgba(255,255,255,.07); color: #e2e8f0; cursor: pointer; font-size: 13px; }',
            '.askm-btns button.primary { background: #2563eb; border-color: #2563eb; }',
            '.askm-btns button:hover { filter: brightness(1.15); }',
        ].join('\n');
        document.head.appendChild(st);
    }

    function open(opts) {
        return new Promise((resolve) => {
            queue = queue.then(() => new Promise((inner) => {
                ensureStyle();
                const overlay = document.createElement('div');
                overlay.className = 'askm-overlay';
                const card = document.createElement('div');
                card.className = 'askm-card';
                const title = document.createElement('div');
                title.className = 'askm-title';
                title.textContent = opts.title || '';
                card.appendChild(title);

                let input;
                if (Array.isArray(opts.options)) {
                    input = document.createElement('select');
                    input.className = 'askm-input';
                    for (const o of opts.options) {
                        const op = document.createElement('option');
                        op.value = o;
                        op.textContent = o;
                        input.appendChild(op);
                    }
                    if (opts.value && opts.options.indexOf(opts.value) >= 0) input.value = opts.value;
                } else {
                    input = document.createElement('input');
                    input.type = 'text';
                    input.className = 'askm-input';
                    input.placeholder = opts.placeholder || '';
                    input.value = opts.value != null ? String(opts.value) : '';
                }
                if (opts.label) {
                    const lb = document.createElement('label');
                    lb.className = 'askm-label';
                    lb.textContent = opts.label;
                    card.appendChild(lb);
                }
                card.appendChild(input);

                const btns = document.createElement('div');
                btns.className = 'askm-btns';
                const cancel = document.createElement('button');
                cancel.textContent = opts.cancelLabel || '取消';
                const ok = document.createElement('button');
                ok.className = 'primary';
                ok.textContent = opts.confirmLabel || '确定';
                btns.append(cancel, ok);
                card.appendChild(btns);
                overlay.appendChild(card);

                let done = false;
                function finish(val) {
                    if (done) return;
                    done = true;
                    document.removeEventListener('keydown', onKey);
                    overlay.remove();
                    resolve(val);
                    inner(); // 放行队列
                }
                ok.addEventListener('click', () => finish(input.value));
                cancel.addEventListener('click', () => finish(null));
                function onKey(e) {
                    if (e.key === 'Enter') { e.preventDefault(); finish(input.value); }
                    else if (e.key === 'Escape') { e.preventDefault(); finish(null); }
                }
                document.addEventListener('keydown', onKey);
                overlay.addEventListener('mousedown', (e) => { if (e.target === overlay) finish(null); });
                document.body.appendChild(overlay);
                requestAnimationFrame(() => { input.focus(); if (input.select) input.select(); });
            }));
        });
    }

    window.askText = (opts) => open(opts || {});
    window.askChoice = (opts) => open(opts || {});
})();
