// timeline.js - W7 动画时间线面板（Blockbench 式：动画族列表 + 传输条 + 标尺 + 骨骼×R/P/S 菱形轨道
// + ⚡过点脚本轨道 + 🔗帧搬运 + 🏃移动模拟控制）
// 经典 script（self.Timeline）；纯 DOM（与 outliner/inspector 同模式）。
//
// api（app.js 提供）：
//   getState() -> { model, animations: Map, currentAnim, animTime, animPlaying, animSpeed,
//                   timeGrid, animMode, dirty: Set<file>, deleted: Set<file>,
//                   simOn, simSpeed, simDir }
//   onSelectAnim(name) / onNewAnim(name)->err|null / onDeleteAnim(name)
//   onTime(t) / onPlayToggle() / onSpeed(v) / onGrid(fps) / onLoopChange(animName, loop)
//   onKeyAdd(bone, ch, t, value) / onKeyRemove(bone, ch, t) / onKeyMove(bone, ch, t, newT)
//   onKeyEdit(bone, ch, t, patch)   patch = {post?, pre?, preOn?, lerpMode?}
//   onNotifyAdd(animName, t, script) / onNotifyEdit(animName, t, index, script) / onNotifyRemove(animName, t, index)
//   onChainTailToHead(src, dst) / onChainHeadToTail(src, dst)
//   onSim(on) / onSimSpeed(v) / onSimDir(d)
//   onParamsChange(animName, params) -> null|错误文案   参数声明（增删改，undo 可回退）
//   onParamPreview(animName, name, value)              仅预览用的参数测试值（不写盘）
// getState 附带 paramPreview（{name: 测试值}）。
// 返回：{ refresh(), onTime(t), onPlaying(b), refreshDirty() }
(function (globalScope) {
    'use strict';
    // 2026-10-14：时间线缩放（px/秒，离散档位；工具栏 🔍 或 Ctrl+滚轮）
    const ZOOM_LEVELS = [24, 36, 48, 72, 96, 120, 180, 240, 360, 480, 960, 1920];
    const ZOOM_DEFAULT = 5; // 120px/s
    const ZOOM_KEY = 'me.tlZoomPx';
    // 时间原点右移量（px）：0 刻度/关键帧不贴标签列左缘（标尺区与车道同偏移 → 仍对齐）
    const RULER_PAD = 12;
    const CH_META = [
        { ch: 'rotation', label: 'R', cls: 'tl-kf-r' },
        { ch: 'position', label: 'P', cls: 'tl-kf-p' },
        { ch: 'scale', label: 'S', cls: 'tl-kf-s' },
    ];

    function create(container, api) {
        const Tk = globalScope.Tick, AN = globalScope.Animation, Kf = globalScope.KeyframeOps;
        let selKey = null; // {bone, ch, t} 当前选中的关键帧（内联编辑器）
        let selNotify = null; // {t, index}
        let rafPending = 0;

        // ---------- DOM 骨架 ----------
        container.innerHTML = `
            <div class="tl-toolbar">
                <span class="tl-title">🎬 动画</span>
                <button class="btn ghost small" id="tlNew" title="新建动画（独立文件）">＋ 动画</button>
                <span class="ed-spacer"></span>
                <button class="btn ghost small" id="tlToStart" title="播放头回 0">⏮</button>
                <button class="btn small" id="tlPlay" title="播放/暂停（Space）">▶</button>
                <button class="btn ghost small" id="tlToEnd" title="播放头到末尾">⏭</button>
                <!-- 2026-10-14：可输入 —— 键入精确时间回车 = 播放头跳转（便于在指定时刻 K 帧） -->
                <input class="tl-time" id="tlTime" type="text" inputmode="decimal" value="0s"
                       title="可输入：键入秒数（如 0.25 或 0.25s）回车 = 播放头跳到该时刻">
                <label class="tl-opt" title="播放速度">速度
                    <select id="tlSpeed">
                        <option value="0.25">0.25×</option>
                        <option value="0.5">0.5×</option>
                        <option value="1" selected>1×</option>
                        <option value="2">2×</option>
                    </select>
                </label>
                <label class="tl-opt" title="时间网格（引擎 = 60 物理帧/秒）">网格
                    <select id="tlGrid">
                        <option value="60" selected>60fps</option>
                        <option value="20">20TPS</option>
                        <option value="24">24fps</option>
                    </select>
                </label>
                <label class="tl-opt" title="循环方式（引擎 Loop 枚举）">loop
                    <select id="tlLoop">
                        <option value="once">once</option>
                        <option value="hold_on_last_frame">hold</option>
                        <option value="true">true</option>
                    </select>
                </label>
                <!-- 运行时参数（params）：声明即同步契约（见 core/animParams.js / fork docs/RUNTIME_ANIM_PARAMS.md） -->
                <button class="btn ghost small" id="tlParamsBtn"
                        title="运行时参数声明（params）：只有声明过的参数才会被收集并同步到客户端；Molang 里用 v.名字 读取">🔧 参数 <span id="tlParamCount">0</span></button>
                <span class="tl-opt" title="时间线缩放（= 标尺/轨道像素密度；也可在标尺/轨道上 Ctrl+滚轮）">🔍
                    <button class="btn ghost small" id="tlZoomOut" title="缩小">−</button>
                    <span class="tl-zoomval" id="tlZoomVal"></span>
                    <button class="btn ghost small" id="tlZoomIn" title="放大">＋</button>
                </span>
                <span class="ed-spacer"></span>
                <span class="tl-opt" title="帧搬运：把源动画的姿态写成本动画的关键帧（接续播放的帧连续性）">🔗
                    <select id="tlChainFrom"></select>
                    <select id="tlChainDir" title="搬运方向：尾→本头 = 源末帧→本动画 t=0；头→本尾 = 源 t=0→本动画末尾；状态→播放头 = 源 t=0 姿态插入到当前播放头时刻">
                        <option value="tail-to-head">尾→本头</option>
                        <option value="head-to-tail">头→本尾</option>
                        <option value="state-to-playhead">状态→播放头</option>
                    </select>
                    <button class="btn ghost small" id="tlChainGo">搬运</button>
                </span>
                <span class="tl-opt" title="移动视觉模拟：脚底 16px 网格 + 跑步机（速度 = blocks/s）">🏃
                    <button class="btn ghost small" id="tlSimToggle">关</button>
                    <select id="tlSimSpeed">
                        <option value="4.3" selected>4.3 走路</option>
                        <option value="5.6">5.6 疾跑</option>
                        <option value="3">3 慢</option>
                        <option value="7">7 快</option>
                    </select>
                    <button class="btn ghost small" id="tlSimDir" title="前/反向">前</button>
                </span>
            </div>
            <!-- 运行时参数面板（🔧 参数 展开；声明 = 名字 + 默认值 + 相位 + 仅预览用的测试值） -->
            <div class="tl-params" id="tlParams" hidden>
                <div class="tl-params-head">
                    <span class="tl-params-title">🔧 运行时参数</span>
                    <span class="note" id="tlParamsHint" style="font-size:10px"></span>
                    <span class="ed-spacer"></span>
                    <button class="btn ghost small" id="tlParamAdd" title="添加一条参数声明">＋ 参数</button>
                    <button class="btn ghost small" id="tlParamsClose" title="收起面板">✕</button>
                </div>
                <datalist id="tlParamNames"></datalist>
                <div class="tl-params-rows" id="tlParamRows"></div>
            </div>
            <div class="tl-body">
                <div class="tl-list" id="tlList"></div>
                <div class="tl-right">
                    <!-- 2026-10-14：标尺移入轨道同一滚动容器、与轨道行共享 110px 标签列
                         （.tl-rowlabel 同宽占位）——此前标尺是轨道上方的兄弟元素：轨道行左边的
                         骨名列把车道右推 110px，而标尺刻度从 x=0 起 → 时间轴整体错位；且轨道
                         横向滚动时标尺不跟随。现在两者同一 scroll 容器 + 同一标签列 → 恒对齐。
                         播放头 = 贯穿标尺+轨道的全高线（tlPlayLine）。 -->
                    <div class="tl-tracks" id="tlTracks">
                        <div class="tl-scrollcontent" id="tlScrollContent">
                            <div class="tl-ruler" id="tlRuler"></div>
                        </div>
                    </div>
                    <div class="tl-keyedit" id="tlKeyEdit" hidden></div>
                </div>
            </div>
        `;
        // 与 CSS .tl-rowlabel 的 width 一致（标尺占位/播放头 x 偏移用）
        const LABEL_W = 110;
        const $ = (id) => container.querySelector('#' + id);
        const state = () => api.getState();
        const curAnim = () => {
            const s = state();
            return s.currentAnim ? s.animations.get(s.currentAnim) : null;
        };
        // 2026-10-14：缩放状态（localStorage 记忆）+ 时间↔像素换算
        let pxPerSec = (() => {
            try {
                const v = parseInt(localStorage.getItem(ZOOM_KEY) || '', 10);
                return ZOOM_LEVELS.includes(v) ? v : ZOOM_LEVELS[ZOOM_DEFAULT];
            } catch (e) { return ZOOM_LEVELS[ZOOM_DEFAULT]; }
        })();
        const t2x = (t) => RULER_PAD + t * pxPerSec;
        const x2t = (x) => Math.max(0, (x - RULER_PAD) / pxPerSec);
        const durPx = () => {
            const a = curAnim();
            const d = a ? Math.max(a.durationSec, AN.computeDuration(a)) : 1;
            return RULER_PAD * 2 + Math.max(1, d) * pxPerSec;
        };
        /** 切换缩放档；mouseClientX = 鼠标 clientX（锚定该处时间点），null = 锚定 t=0 */
        function setZoom(idx, mouseClientX) {
            const clamped = Math.max(0, Math.min(ZOOM_LEVELS.length - 1, idx));
            const next = ZOOM_LEVELS[clamped];
            if (next === pxPerSec) return;
            pxPerSec = next;
            try { localStorage.setItem(ZOOM_KEY, String(pxPerSec)); } catch (e) {}
            const zv = $('tlZoomVal');
            if (zv) zv.textContent = pxPerSec + 'px/s';
            if (mouseClientX != null) {
                const scroller = $('tlTracks');
                const rect = scroller.getBoundingClientRect();
                const vpX = mouseClientX - rect.left;
                const t = x2t(vpX + scroller.scrollLeft - LABEL_W);
                scroller.scrollLeft = LABEL_W + RULER_PAD + t * pxPerSec - vpX;
            }
            refresh();
        }

        // ---------- 动画族列表（按文件分组 = bundle 一等公民；重名被遮蔽者灰显） ----------
        function renderList() {
            const s = state();
            const list = $('tlList');
            list.innerHTML = '';
            const fileNames = s.animFileNames || {};
            const files = Object.keys(fileNames).sort();
            if (files.length === 0) {
                list.innerHTML = '<div class="note" style="padding:6px 8px;font-size:11px">无动画 —— ＋新建</div>';
                return;
            }
            for (const file of files) {
                const all = fileNames[file] || [];
                const active = all.filter((n) => s.animations.has(n));
                const head = document.createElement('div');
                head.className = 'tl-filehead' + ((s.dirty && s.dirty.has(file)) ? ' dirty' : '') + ((s.deleted && s.deleted.has(file)) ? ' deleted' : '');
                head.innerHTML = `<span class="tl-filename" title="${file}">📄 ${file.replace(/\.json$/, '')}</span>` +
                    (all.length > 1 ? `<span class="note" style="font-size:10px">×${active.length}${active.length !== all.length ? `/${all.length}` : ''}</span>` : '') +
                    (s.deleted && s.deleted.has(file) ? '<span class="note" style="font-size:10px">将删</span>' : '');
                list.appendChild(head);
                for (const name of all) {
                    const anim = s.animations.get(name);
                    // 遮蔽判定：名字不在本文件（被更早文件优先载入）→ 只读灰显
                    const isShadow = !anim || (s.animFiles && s.animFiles[name] !== file);
                    const row = document.createElement('div');
                    if (anim && !isShadow) {
                        row.className = 'tl-animrow' + (s.currentAnim === name ? ' cur' : '');
                        row.innerHTML = `<span class="tl-animname" title="${name}">${name}</span>` +
                            `<span class="note" style="font-size:10px">${anim.loop === 'true' ? '↻' : anim.loop === 'hold_on_last_frame' ? '⏸' : '1×'}</span>` +
                            `<button class="tl-del" title="删除动画">🗑</button>`;
                        row.querySelector('.tl-animname').onclick = () => { api.onSelectAnim(name); };
                        row.querySelector('.tl-del').onclick = (e) => {
                            e.stopPropagation();
                            if (window.confirm(`删除动画 ${name}？（文件内无剩余动画时文件将删除）`)) api.onDeleteAnim(name);
                        };
                    } else {
                        // W7：重名被遮蔽（首文件优先）—— 只读展示，不可编辑
                        row.className = 'tl-animrow shadow';
                        row.title = `重名：被更早的文件遮蔽（未载入、不编辑、不重写）`;
                        row.innerHTML = `<span class="tl-animname">${name}</span><span class="note" style="font-size:10px">遮蔽</span>`;
                    }
                    list.appendChild(row);
                }
            }
        }

        // ---------- 标尺（2026-10-14：与轨道行共享 110px 标签占位列 → 时间轴对齐） ----------
        function renderRuler() {
            const s = state();
            const ruler = $('tlRuler');
            const width = durPx();
            const grid = Tk.frameGridSec(s.timeGrid) || Tk.TICK_SEC;
            ruler.innerHTML = '';
            // 左标签占位（= 轨道行骨名列同宽；sticky 左钉住）
            const spacer = document.createElement('div');
            spacer.className = 'tl-rowlabel tl-rulerspacer';
            spacer.textContent = '时间(s)';
            spacer.title = '拖动标尺 = 移动播放头';
            ruler.appendChild(spacer);
            // 刻度区（与车道同一时间轴原点：t=0 都从本区左缘起）
            const area = document.createElement('div');
            area.className = 'tl-rulerarea';
            area.style.width = width + 'px';
            // 细格 = 网格；主格 = 0.5s（带标签）
            const frag = document.createDocumentFragment();
            const dSec = (width - 2 * RULER_PAD) / pxPerSec;
            for (let t = 0; t <= dSec + 1e-9; t += grid) {
                const minor = document.createElement('div');
                minor.className = 'tl-tick' + (Math.abs(t % 0.5) < 1e-9 || Math.abs(t % 0.5 - 0.5) < 1e-9 ? '' : ' minor');
                minor.style.left = t2x(t) + 'px';
                if (Math.abs(t % 0.5) < 1e-9) {
                    minor.classList.add('major');
                    minor.innerHTML = `<span>${Tk.formatTime(t)}</span>`;
                }
                frag.appendChild(minor);
            }
            area.appendChild(frag);
            ruler.appendChild(area);
            area.onpointerdown = (e) => {
                if (!s.animMode) return;
                const rect = area.getBoundingClientRect();
                const scrub = (ev) => {
                    const t = x2t(ev.clientX - rect.left);
                    api.onTime(t);
                };
                scrub(e);
                const move = (ev) => scrub(ev);
                const up = () => {
                    window.removeEventListener('pointermove', move);
                    window.removeEventListener('pointerup', up);
                };
                window.addEventListener('pointermove', move);
                window.addEventListener('pointerup', up);
            };
        }

        // ---------- 轨道（骨骼 × R/P/S 菱形 + ⚡过点） ----------
        function keyAt(anim, bone, ch, t) {
            const track = AN.getTrack(anim, bone);
            if (!track || !Array.isArray(track[ch])) return null;
            return track[ch].find((k) => Math.abs(k.timeSec - t) <= 1e-6) || null;
        }
        function renderTracks() {
            const s = state();
            const box = $('tlScrollContent');
            // 只清轨道行：保留标尺（tlRuler）与全高播放头线（tlPlayLine）
            for (const c of [...box.children]) {
                if (c.id !== 'tlRuler' && c.id !== 'tlPlayLine') c.remove();
            }
            const width = durPx();
            const a = curAnim();
            const rows = [];
            if (a) {
                for (const tr of a.tracks) {
                    rows.push(tr.bone);
                }
            }
            if (rows.length === 0) {
                const empty = document.createElement('div');
                empty.className = 'tl-empty';
                empty.style.cssText = 'padding:6px 8px;font-size:11px';
                empty.textContent = '无轨道 —— 选中/点击骨骼后拖拽 3D 手柄（auto-key 自动建帧），或双击轨道建关键帧';
                box.appendChild(empty);
            }
            const grid = Tk.frameGridSec(s.timeGrid) || Tk.TICK_SEC;
            for (const bone of rows) {
                const row = document.createElement('div');
                row.className = 'tl-row';
                const label = document.createElement('div');
                label.className = 'tl-rowlabel';
                label.textContent = bone;
                label.title = bone + '（点击选中该骨）';
                label.onclick = () => {
                    api.onSelectBone && api.onSelectBone(bone);
                };
                row.appendChild(label);
                const lane = document.createElement('div');
                lane.className = 'tl-lane';
                lane.style.width = width + 'px';
                lane.dataset.bone = bone;
                // 网格背景线
                lane.style.backgroundSize = `${grid * pxPerSec}px 100%`;
                if (a) {
                    for (const meta of CH_META) {
                        const kfs = AN.getTrack(a, bone) && AN.getTrack(a, bone)[meta.ch];
                        if (!Array.isArray(kfs)) continue;
                        for (const k of kfs) {
                            const kf = document.createElement('div');
                            kf.className = `tl-kf ${meta.cls}` + (selKey && selKey.bone === bone && selKey.ch === meta.ch && Math.abs(selKey.t - k.timeSec) < 1e-6 ? ' sel' : '');
                            kf.style.left = t2x(k.timeSec) + 'px';
                            kf.textContent = meta.label;
                            kf.title = `${bone} ${meta.ch} @ ${Tk.formatTime(k.timeSec)}s${k.pre != null ? '（含 pre）' : ''}`;
                            kf.addEventListener('pointerdown', (e) => startKfDrag(e, lane, bone, meta.ch, k.timeSec, width));
                            lane.appendChild(kf);
                        }
                    }
                }
                // 双击空处 = 建关键帧（三通道，值 = 当前时刻采样）
                lane.addEventListener('dblclick', (e) => {
                    if (!s.animMode || !a) return;
                    const rect = lane.getBoundingClientRect();
                    const t = Tk.snapToGrid(x2t(e.clientX - rect.left), grid);
                    for (const meta of CH_META) {
                        const fallback = meta.ch === 'scale' ? [1, 1, 1] : [0, 0, 0];
                        const v = Kf.currentValue(a, bone, meta.ch, t, fallback);
                        api.onKeyAdd(bone, meta.ch, t, v);
                    }
                });
                row.appendChild(lane);
                box.appendChild(row);
            }
            // ⚡过点脚本轨道
            const nrow = document.createElement('div');
            nrow.className = 'tl-row tl-notifyrow';
            const nlabel = document.createElement('div');
            nlabel.className = 'tl-rowlabel';
            nlabel.textContent = '⚡过点';
            nlabel.title = 'OAnimation.timeline：过点 MoLang 脚本（引擎在播放过点时 eval）';
            const addBtn = document.createElement('button');
            addBtn.className = 'btn ghost small';
            addBtn.textContent = '＋';
            addBtn.title = '在播放头处加过点脚本';
            addBtn.onclick = () => {
                const t = Tk.snapToGrid(s.animTime, grid);
                if (!window.askText) return;
                window.askText({
                    title: '⚡ 过点脚本',
                    label: `t=${Tk.formatTime(t)}s 处执行的 MoLang（如：sound.play("x") / variable.set("hit", 1)）`,
                }).then((script) => {
                    if (script != null && String(script).trim()) api.onNotifyAdd(s.currentAnim, t, String(script).trim());
                });
            };
            nlabel.appendChild(addBtn);
            nrow.appendChild(nlabel);
            const nlane = document.createElement('div');
            nlane.className = 'tl-lane';
            nlane.style.width = width + 'px';
            nlane.style.backgroundSize = `${grid * pxPerSec}px 100%`;
            // 全高播放头线（贯穿标尺+全部轨道；x = 标签列宽 + t×px，与车道/刻度同原点）
            let pl = $('tlPlayLine');
            if (!pl) {
                pl = document.createElement('div');
                pl.id = 'tlPlayLine';
                pl.className = 'tl-playhead-line';
            }
            pl.style.left = (LABEL_W + t2x(s.animTime)) + 'px';
            box.appendChild(pl);
            if (a && Array.isArray(a.timeline)) {
                a.timeline.forEach((entry, i) => {
                    const n = document.createElement('div');
                    n.className = 'tl-kf tl-kf-n' + (selNotify && Math.abs(selNotify.t - entry.timeSec) < 1e-6 && selNotify.index === i ? ' sel' : '');
                    n.style.left = t2x(entry.timeSec) + 'px';
                    n.textContent = '⚡';
                    n.title = `t=${Tk.formatTime(entry.timeSec)}s: ${entry.scripts.join(' / ')}`;
                    n.onclick = () => {
                        selNotify = { t: entry.timeSec, index: i };
                        renderKeyEdit();
                    };
                    nlane.appendChild(n);
                });
            }
            nrow.appendChild(nlane);
            box.appendChild(nrow);
            renderKeyEdit();
        }
        function startKfDrag(e, lane, bone, ch, t0, width) {
            e.preventDefault();
            const grid = Tk.frameGridSec(state().timeGrid) || Tk.TICK_SEC;
            const rect = lane.getBoundingClientRect();
            let moved = false;
            const kfEl = e.currentTarget;
            const onMove = (ev) => {
                const dx = ev.clientX - e.clientX;
                if (Math.abs(dx) > 2) moved = true;
                kfEl.style.left = Math.max(RULER_PAD, Math.min(width - RULER_PAD, t2x(t0) + dx)) + 'px';
            };
            const onUp = (ev) => {
                window.removeEventListener('pointermove', onMove);
                window.removeEventListener('pointerup', onUp);
                const dx = ev.clientX - e.clientX;
                const tNew = Tk.snapToGrid(x2t(t2x(t0) + dx), grid);
                if (Math.abs(tNew - t0) > 1e-6) {
                    api.onKeyMove(bone, ch, t0, tNew);
                } else {
                    selKey = { bone, ch, t: t0 };
                    renderTracks();
                }
            };
            window.addEventListener('pointermove', onMove);
            window.addEventListener('pointerup', onUp);
        }

        // ---------- 关键帧/过点 内联编辑器 ----------
        function renderKeyEdit() {
            const box = $('tlKeyEdit');
            const a = curAnim();
            // ⚡过点脚本编辑（selNotify 优先）
            if (selNotify && a && Array.isArray(a.timeline)) {
                const entry = a.timeline[selNotify.index];
                if (entry) {
                    box.hidden = false;
                    box.innerHTML = `
                        <span class="note" style="font-size:11px">⚡ 过点脚本</span>
                        <label>t <input id="neT" type="number" step="0.0001" value="${Tk.formatTime(entry.timeSec)}"></label>
                        <select id="neIdx">${a.timeline.map((e2, i2) => `<option value="${i2}" ${i2 === selNotify.index ? 'selected' : ''}>t=${Tk.formatTime(e2.timeSec)}</option>`).join('')}</select>
                        <input id="neScript" style="width:340px" value="${String(entry.scripts.join('; ')).replace(/"/g, '&quot;')}" placeholder="MoLang 脚本（; 分隔多条）">
                        <button class="btn ghost small" id="neDel">🗑 删除</button>
                    `;
                    box.querySelector('#neScript').addEventListener('change', (e) => {
                        const scripts = e.target.value.split(/;/).map((s) => s.trim()).filter(Boolean);
                        if (scripts.length) api.onNotifyEdit(a.name, entry.timeSec, selNotify.index, scripts);
                    });
                    box.querySelector('#neDel').addEventListener('click', () => {
                        api.onNotifyRemove(a.name, entry.timeSec, selNotify.index);
                        selNotify = null;
                        renderTracks();
                    });
                    return;
                }
            }
            if (!selKey || !a) { box.hidden = true; box.innerHTML = ''; return; }
            const { bone, ch, t } = selKey;
            const k = keyAt(a, bone, ch, t);
            if (!k) { selKey = null; box.hidden = true; box.innerHTML = ''; return; }
            const post = Array.isArray(k.post) ? k.post : [k.post, k.post, k.post];
            const preOn = k.pre != null;
            const pre = Array.isArray(k.pre) ? k.pre : (preOn ? [k.pre, k.pre, k.pre] : post);
            const isMolang = typeof k.post === 'string' || post.some((x) => typeof x === 'string');
            box.hidden = false;
            box.innerHTML = `
                <span class="note" style="font-size:11px">关键帧</span>
                <span class="tl-ke-name">${bone}</span>
                <select id="keCh">${CH_META.map((m) => `<option value="${m.ch}" ${m.ch === ch ? 'selected' : ''}>${m.label} ${m.ch}</option>`).join('')}</select>
                <label>t <input id="keT" type="number" step="0.0001" value="${Tk.formatTime(t)}"></label>
                ${isMolang
                    ? '<span class="note" style="font-size:11px">MoLang 值（只读透传）：' + String(k.post).slice(0, 40) + '</span>'
                    : '<label>R <input class="kePost" data-i="0" type="number" step="0.001" value="' + post[0] + '"></label>' +
                      '<label>G <input class="kePost" data-i="1" type="number" step="0.001" value="' + post[1] + '"></label>' +
                      '<label>B <input class="kePost" data-i="2" type="number" step="0.001" value="' + post[2] + '"></label>'}
                <label class="tl-pre" title="pre = 反向切线值（catmullrom 控制点 / 关键帧跳变目标）">
                    <input id="kePreOn" type="checkbox" ${preOn ? 'checked' : ''}> pre
                </label>
                <span id="kePreBox" ${preOn ? '' : 'hidden'}>
                    <input class="kePre" data-i="0" type="number" step="0.001" value="${pre[0]}">
                    <input class="kePre" data-i="1" type="number" step="0.001" value="${pre[1]}">
                    <input class="kePre" data-i="2" type="number" step="0.001" value="${pre[2]}">
                </span>
                <select id="keLerp" title="插值模式（作用于"进入本帧"的段）">
                    <option value="linear" ${k.lerpMode === 'linear' ? 'selected' : ''}>linear</option>
                    <option value="catmullrom" ${k.lerpMode === 'catmullrom' ? 'selected' : ''}>catmullrom</option>
                    ${k.lerpMode !== 'linear' && k.lerpMode !== 'catmullrom' ? `<option value="${k.lerpMode}" selected>${k.lerpMode}（未知，透传）</option>` : ''}
                </select>
                <button class="btn ghost small" id="keDel">🗑 删帧</button>
            `;
            const emit = (patch) => api.onKeyEdit(bone, ch, t, patch);
            box.querySelectorAll('.kePost').forEach((inp) => {
                inp.addEventListener('change', () => {
                    if (isMolang) return;
                    const p = post.slice();
                    p[+inp.dataset.i] = parseFloat(inp.value) || 0;
                    emit({ post: p });
                });
            });
            const preBox = $('kePreBox');
            box.querySelector('#kePreOn').addEventListener('change', (e) => {
                preBox.hidden = !e.target.checked;
                if (e.target.checked) emit({ preOn: true, pre: post.slice() });
                else emit({ preOn: false, pre: null });
            });
            box.querySelectorAll('.kePre').forEach((inp) => {
                inp.addEventListener('change', () => {
                    if (!preOn) return;
                    const p = pre.slice();
                    p[+inp.dataset.i] = parseFloat(inp.value) || 0;
                    emit({ preOn: true, pre: p });
                });
            });
            box.querySelector('#keLerp').addEventListener('change', (e) => emit({ lerpMode: e.target.value }));
            box.querySelector('#keCh').addEventListener('change', (e) => {
                selKey = { bone, ch: e.target.value, t };
                renderTracks();
            });
            box.querySelector('#keT').addEventListener('change', (e) => {
                const tNew = parseFloat(e.target.value);
                if (Number.isFinite(tNew) && tNew >= 0 && Math.abs(tNew - t) > 1e-9) {
                    api.onKeyMove(bone, ch, t, tNew);
                }
            });
            box.querySelector('#keDel').addEventListener('click', () => {
                api.onKeyRemove(bone, ch, t);
                selKey = null;
                renderTracks();
            });
        }

        // ---------- 运行时参数（params）面板 ----------
        // 声明即同步契约：只有这里列出的参数才会被服务端收集/同步（见 fork docs/RUNTIME_ANIM_PARAMS.md）。
        const P = globalScope.AnimParams;
        const PARAMS_OPEN_KEY = 'me.tlParamsOpen';
        const escHtml = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
        const paramsOf = (anim) => (anim && anim.params) || {};
        let paramsOpen = false;
        try { paramsOpen = localStorage.getItem(PARAMS_OPEN_KEY) === '1'; } catch (e) { paramsOpen = false; }

        function renderParamCount() {
            const n = Object.keys(paramsOf(curAnim())).length;
            $('tlParamCount').textContent = String(n);
            $('tlParamsBtn').classList.toggle('on', !$('tlParams').hidden);
        }

        function setParamsOpen(open) {
            paramsOpen = !!open;
            $('tlParams').hidden = !paramsOpen;
            try { localStorage.setItem(PARAMS_OPEN_KEY, paramsOpen ? '1' : '0'); } catch (e) { /* 隐私模式忽略 */ }
            renderParams();
        }

        /** 默认值输入 → { value, warning? }（数字串转 number；内建数值参数给非数字时警告） */
        function parseDefaultInput(raw, meta) {
            const s = String(raw).trim();
            if (s === '') return { value: 0, warning: '默认值为空 → 0' };
            if (/^-?(?:\d+(?:\.\d+)?|\.\d+)$/.test(s)) return { value: Number(s) };
            if (meta && meta.kind === 'num') {
                return { value: s, warning: `内置参数 ${meta.name} 是数值型，"${s}" 不是数字（引擎按 0 读）` };
            }
            return { value: s };
        }

        function renderParams() {
            renderParamCount();
            const anim = curAnim();
            const box = $('tlParamRows');
            if ($('tlParams').hidden) return;
            const s = state();
            if (!anim) { box.innerHTML = '<div class="note" style="padding:6px 8px">先选一个动画</div>'; return; }
            const params = paramsOf(anim);
            const entries = Object.entries(params);
            const preview = s.paramPreview || {};
            $('tlParamsHint').textContent = entries.length
                ? `已声明 ${entries.length} 个（只有声明的参数会收集/同步；默认值仅在运行时缺值时兜底）`
                : '还没有声明参数（动画表达式里可读内置参数，见名字下拉）';
            let html = '';
            if (entries.length) {
                html += '<div class="tl-prow tl-phead">' +
                    '<span>名字</span><span>默认值</span><span>相位</span><span>预览值</span><span>说明</span><span></span>' +
                    '</div>';
            }
            for (const [name, decl] of entries) {
                const meta = P.builtin(name);
                const previewRaw = preview[name] == null ? '' : String(preview[name]);
                html += '<div class="tl-prow" data-name="' + escHtml(name) + '">' +
                    '<input class="tl-pname" list="tlParamNames" value="' + escHtml(name) + '" spellcheck="false">' +
                    '<input class="tl-pdef" value="' + escHtml(String(decl.default)) + '">' +
                    '<select class="tl-pphase">' +
                    '<option value="tick"' + (decl.phase === 'tick' ? ' selected' : '') + '>tick 20Hz</option>' +
                    '<option value="substep"' + (decl.phase === 'substep' ? ' selected' : '') + '>substep 60Hz</option>' +
                    '</select>' +
                    '<input class="tl-pprev" value="' + escHtml(previewRaw) + '" placeholder="' + escHtml(String(decl.default)) + '" title="仅编辑器预览用，不写盘">' +
                    '<span class="tl-pdesc" title="' + escHtml(meta ? meta.desc : '自定义参数：需要在业务侧注册 provider，否则运行时一直是默认值') + '">' +
                    escHtml(meta ? meta.desc : '自定义（需业务 provider）') + '</span>' +
                    '<button class="btn ghost small tl-pdel" title="删除该参数声明">✕</button>' +
                    '</div>';
            }
            box.innerHTML = html;

            const commit = (mutate) => {
                const next = mutate(params);
                const err = api.onParamsChange ? api.onParamsChange(anim.name, next) : '编辑器未接线 onParamsChange';
                if (err) { setStatus('✗ ' + err); renderParams(); }
            };
            box.querySelectorAll('.tl-prow[data-name]').forEach((row) => {
                const oldName = row.dataset.name;
                const meta = P.builtin(oldName);
                const nameInput = row.querySelector('.tl-pname');
                nameInput.addEventListener('change', (e) => {
                    const newName = e.target.value.trim();
                    if (newName === oldName) return;
                    const r = P.renameDecl(params, oldName, newName);
                    if (r.error) { setStatus('✗ ' + r.error); renderParams(); return; }
                    const newMeta = P.builtin(newName);
                    if (newMeta) r.params[newName] = { default: r.params[newName].default, phase: newMeta.phase };
                    commit(() => r.params);
                });
                row.querySelector('.tl-pdef').addEventListener('change', (e) => {
                    const parsed = parseDefaultInput(e.target.value, meta);
                    if (parsed.warning) setStatus('⚠ ' + parsed.warning);
                    commit((p) => P.withDecl(p, oldName, { default: parsed.value, phase: params[oldName].phase }));
                });
                row.querySelector('.tl-pphase').addEventListener('change', (e) => {
                    commit((p) => P.withDecl(p, oldName, { default: params[oldName].default, phase: e.target.value }));
                });
                row.querySelector('.tl-pprev').addEventListener('change', (e) => {
                    if (api.onParamPreview) api.onParamPreview(anim.name, oldName, e.target.value);
                });
                row.querySelector('.tl-pdel').addEventListener('click', () => {
                    commit((p) => P.withoutDecl(p, oldName));
                });
            });
        }

        function addParam() {
            const anim = curAnim();
            if (!anim) { setStatus('✗ 先选一个动画'); return; }
            const params = paramsOf(anim);
            let n = 1;
            while (params['param_' + n] != null) n++;
            const next = P.withDecl(params, 'param_' + n, { default: 0, phase: 'tick' });
            const err = api.onParamsChange ? api.onParamsChange(anim.name, next) : '编辑器未接线 onParamsChange';
            if (err) setStatus('✗ ' + err);
            else if ($('tlParams').hidden) setParamsOpen(true);
        }

        // ---------- 刷新 ----------
        function refresh() {
            renderList();
            renderRuler();
            renderTracks();
            updateTransport();
            renderParams();
        }
        // 时间输入框：只在未聚焦时写入（不打断输入）
        function setTimeDisplay(t) {
            const inp = $('tlTime');
            if (document.activeElement !== inp) inp.value = Tk.formatTimeAt(t, state().timeGrid);
        }
        function parseTimeInput(v) {
            const n = parseFloat(String(v).replace(/[^0-9.eE\-]/g, ''));
            return Number.isFinite(n) && n >= 0 ? n : null;
        }
        function updateTransport() {
            const s = state();
            setTimeDisplay(s.animTime);
            $('tlPlay').textContent = s.animPlaying ? '⏸' : '▶';
            $('tlSpeed').value = String(s.animSpeed);
            $('tlGrid').value = String(s.timeGrid);
            const a = curAnim();
            $('tlLoop').value = a ? a.loop : 'once';
            $('tlSimToggle').textContent = s.simOn ? '开' : '关';
            $('tlSimToggle').classList.toggle('on', !!s.simOn);
            $('tlSimDir').textContent = s.simDir >= 0 ? '前' : '反';
            // 帧搬运来源下拉
            const sel = $('tlChainFrom');
            const names = s.animations ? [...s.animations.keys()] : [];
            sel.innerHTML = names.filter((n) => n !== s.currentAnim)
                .map((n) => `<option value="${n}">${n}</option>`).join('') || '<option value="">(无)</option>';
            const disabled = !s.animMode || !a;
            ['tlPlay', 'tlToStart', 'tlToEnd', 'tlSpeed', 'tlGrid', 'tlLoop', 'tlChainGo', 'tlSimToggle'].forEach((id) => {
                $(id).disabled = disabled;
            });
            // W7 分页：时间线面板只在动画页显示；animMode = 姿态编辑（动画页恒开）
            $('tlNew').disabled = !s.model;
        }
        function onTime(t) {
            const s = state();
            setTimeDisplay(t);
            const pl = $('tlPlayLine');
            if (pl) pl.style.left = (LABEL_W + t2x(t)) + 'px';
        }
        function onPlaying(b) {
            $('tlPlay').textContent = b ? '⏸' : '▶';
        }
        function refreshDirty() {
            renderList();
            updateTransport();
            renderParams();
        }

        // ---------- 事件 ----------
        $('tlNew').onclick = () => {
            if (!window.askText) return;
            window.askText({ title: '新建动画', label: '动画名（对应 JSON 内 animations 键）', value: 'anim_new' })
                .then((r) => {
                    if (!r || !r.trim()) return;
                    const err = api.onNewAnim(r.trim());
                    if (err) setStatus('✗ ' + err);
                });
        };
        function setStatus(msg) { api.onStatus && api.onStatus(msg); }
        $('tlPlay').onclick = () => api.onPlayToggle();
        $('tlToStart').onclick = () => api.onTime(0);
        // 2026-10-14：时间输入 → 精确跳转播放头（K 帧定位）
        $('tlTime').addEventListener('keydown', (e) => { if (e.key === 'Enter') e.target.blur(); });
        $('tlTime').addEventListener('change', (e) => {
            const t = parseTimeInput(e.target.value);
            if (t != null) api.onTime(t);
            else setTimeDisplay(state().animTime); // 非法输入 → 还原显示
        });
        $('tlToEnd').onclick = () => {
            const a = curAnim();
            if (a) api.onTime(AN.computeDuration(a));
        };
        $('tlSpeed').onchange = (e) => api.onSpeed(parseFloat(e.target.value) || 1);
        $('tlGrid').onchange = (e) => api.onGrid(parseInt(e.target.value, 10) || 60);
        // 2026-10-14：缩放 —— 工具栏按钮 / Ctrl+滚轮（锚定鼠标下时间点）
        $('tlZoomVal').textContent = pxPerSec + 'px/s';
        $('tlZoomIn').onclick = () => setZoom(ZOOM_LEVELS.indexOf(pxPerSec) + 1);
        $('tlZoomOut').onclick = () => setZoom(ZOOM_LEVELS.indexOf(pxPerSec) - 1);
        $('tlTracks').addEventListener('wheel', (e) => {
            if (!e.ctrlKey) return; // 普通滚轮 = 原生滚动
            e.preventDefault();
            setZoom(ZOOM_LEVELS.indexOf(pxPerSec) + (e.deltaY < 0 ? 1 : -1), e.clientX);
        }, { passive: false });
        $('tlLoop').onchange = (e) => {
            const a = curAnim();
            if (a) api.onLoopChange(a.name, e.target.value);
        };
        $('tlChainGo').onclick = () => {
            const src = $('tlChainFrom').value;
            const dst = state().currentAnim;
            if (!src || !dst) return;
            const dir = $('tlChainDir').value;
            let msg;
            if (dir === 'tail-to-head') msg = api.onChainTailToHead(src, dst);
            else if (dir === 'head-to-tail') msg = api.onChainHeadToTail(src, dst);
            else msg = api.onChainStateToPlayhead(src, dst); // 状态→播放头
            api.onStatus && api.onStatus('🔗 ' + (msg || ''));
        };
        $('tlSimToggle').onclick = () => api.onSim(!state().simOn);
        $('tlSimSpeed').onchange = (e) => api.onSimSpeed(parseFloat(e.target.value) || 4.3);
        $('tlSimDir').onclick = () => api.onSimDir(-state().simDir);
        // 运行时参数面板（声明即同步契约）
        $('tlParamsBtn').onclick = () => setParamsOpen($('tlParams').hidden);
        $('tlParamsClose').onclick = () => setParamsOpen(false);
        $('tlParamAdd').onclick = addParam;
        $('tlParamNames').innerHTML = P.BUILTIN.map((e) =>
            `<option value="${e.name}" label="${e.phase} · ${escHtml(e.desc)}"></option>`).join('');
        setParamsOpen(paramsOpen);

        return { refresh, onTime, onPlaying, refreshDirty };
    }

    globalScope.Timeline = { create };
})(typeof self !== 'undefined' ? self : globalThis);
