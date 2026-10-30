// deployStatus.js - 顶栏「🚀 部署状态」：四态核对面板 + 内容包 on/off
// 依赖：window.api（preload）、window.I18n、window.askChoice
// 暴露：window.DeployStatus.init() / open(opts?) / close() / refresh() / isOpen()
//       opts = { buildDirName } → 打开面板并对该包直接开构建（中栏「📦 打包」用）
//
// 面板只做两件事：把 core/packStatus.js 实测出来的四态摆平（源码 / 产物 jar / run 安装副本 /
// spark 磁盘缓存），以及提供搬运动作（打包 / 安装 / 清缓存 / 开关）。
// 数字一律来自后端测量，前端不推算、不补全；拿不到就显示「—」。
window.DeployStatus = (function () {
    const t = (k) => (window.I18n ? window.I18n.t(k) : k);
    const tf = (k, p) => (window.I18n ? window.I18n.tf(k, p) : k);

    let data = null;          // 最近一次 inspectAll 结果
    let busy = false;         // 同一时刻只允许一个搬运动作（jar 被占用时并发点击只会互相绊倒）

    const el = (tag, cls, text) => {
        const n = document.createElement(tag);
        if (cls) n.className = cls;
        if (text !== undefined && text !== null) n.textContent = String(text);
        return n;
    };

    function bar(msg, ok) {
        const b = document.getElementById('deployStatus');
        if (b) b.className = 'status ' + (ok ? 'ok' : 'err');
        if (b) b.textContent = String(msg || '');
    }

    function fmtSize(n) {
        if (!n) return '—';
        return n > 1024 * 1024 ? (n / 1024 / 1024).toFixed(2) + ' MB' : Math.round(n / 1024) + ' KB';
    }
    function fmtTime(iso) {
        if (!iso) return '—';
        return String(iso).replace('T', ' ').slice(0, 16);
    }
    function shortMd5(md5) {
        return md5 ? String(md5).slice(0, 8) : '—';
    }

    // ===== 构建输出控制台（0.2.2 第 2 步：Gradle 流式输出） =====
    // 后端把子进程 stdout/stderr 原样推过来，这里只保留尾部若干字符并自动滚动。
    // 不改写、不「整理」日志内容 —— 用户要的是 Gradle 说了什么。
    const CONSOLE_MAX_CHARS = 120000;
    let consoleBuf = '';
    let consoleFolded = false;
    let buildBusy = false;
    let unsubOutput = null;

    function setBuildState(text) {
        const s = document.getElementById('buildOutputState');
        if (s) s.textContent = String(text || '');
    }

    function paintConsole() {
        const pre = document.getElementById('deployConsoleBody');
        if (!pre) return;
        pre.textContent = consoleBuf;
        pre.scrollTop = pre.scrollHeight;
    }

    function pushOutput(p) {
        if (!p || typeof p.text !== 'string') return;
        consoleBuf += p.text;
        if (consoleBuf.length > CONSOLE_MAX_CHARS) {
            consoleBuf = '…（前部输出已丢弃）\n' + consoleBuf.slice(consoleBuf.length - CONSOLE_MAX_CHARS);
        }
        paintConsole();
    }

    function clearConsole() {
        consoleBuf = '';
        paintConsole();
        setBuildState('');
    }

    function toggleConsoleFold() {
        consoleFolded = !consoleFolded;
        const box = document.getElementById('deployConsole');
        if (box) box.classList.toggle('folded', consoleFolded);
        const b = document.getElementById('btnBuildConsoleToggle');
        if (b) b.textContent = consoleFolded ? t('deploy.consoleExpand') : t('deploy.consoleFold');
    }

    // ===== 列渲染 =====

    function cellSource(p) {
        const box = el('div');
        if (!p.source.exists) {
            box.appendChild(el('span', 'dp-err', t('deploy.srcMissing')));
            return box;
        }
        box.appendChild(el('div', 'dp-ok', t('deploy.srcOk')));
        const bits = [];
        if (p.source.version) bits.push('v' + p.source.version);
        if (p.source.deps) bits.push('dep:' + p.source.deps);
        if (p.source.hasSparkModels) bits.push('spark_models');
        if (p.source.hasEntities) bits.push('entities');
        if (p.source.hasJava) bits.push('java');
        box.appendChild(el('div', 'dp-sub', bits.join(' · ') || '—'));
        return box;
    }

    function cellJar(p) {
        const box = el('div');
        const m = p.built;
        const a = p.archived;
        if (!m.exists && !a.exists) {
            box.appendChild(el('span', 'dp-err', t('deploy.noJar')));
        } else if (m.exists) {
            box.appendChild(el('div', 'dp-mono', `${fmtSize(m.size)} · ${m.entries} ${t('deploy.entries')}`));
            box.appendChild(el('div', 'dp-mono', 'md5 ' + shortMd5(m.md5)));
            box.appendChild(el('div', 'dp-sub', fmtTime(m.mtimeIso)));
        } else {
            box.appendChild(el('div', 'dp-err', t('deploy.archived')));
            box.appendChild(el('div', 'dp-mono', `${fmtSize(a.size)} · md5 ${shortMd5(a.md5)}`));
            box.appendChild(el('div', 'dp-sub', a.disabledAt ? fmtTime(a.disabledAt) : ''));
        }
        // 源码 ↔ jar 逐条目差异（这才是「jar 是不是旧的」的硬证据）
        const d = p.diff;
        if (d.ok) {
            const line = el('div', 'dp-sub');
            const parts = [];
            if (d.stale.length) parts.push(el('span', 'dp-err', `≠ ${d.stale.length}`));
            if (d.missingInJar.length) parts.push(el('span', 'dp-err', `∉jar ${d.missingInJar.length}`));
            parts.push(el('span', parts.length ? '' : 'dp-ok', `= ${d.equal}/${d.checked}`));
            parts.forEach((n, i) => { if (i) line.appendChild(el('span', 'dp-dim', ' · ')); line.appendChild(n); });
            box.appendChild(line);
        } else if (d.note && (m.exists || a.exists)) {
            box.appendChild(el('div', 'dp-err', d.note));
        }
        return box;
    }

    function cellInstall(p) {
        const box = el('div');
        const live = p.installed.filter((m) => m.rel && m.rel.endsWith('contentpacks'));
        let anyExists = false;
        for (const m of live) {
            if (!m.exists) continue;
            anyExists = true;
            const row = el('div', 'dp-mono', `${m.rel.replace(/^run[\\/]/, '')}/ `);
            row.appendChild(el('span', m.matchesBuilt === false ? 'dp-err' : 'dp-ok',
                m.matchesBuilt === false ? t('deploy.driftDiff') : t('deploy.same')));
            row.appendChild(el('span', 'dp-dim', ' md5 ' + shortMd5(m.md5)));
            box.appendChild(row);
        }
        if (!anyExists) box.appendChild(el('span', 'dp-warn', t('deploy.absent')));
        return box;
    }

    function cellCache(p) {
        const box = el('div');
        if (!p.cache.length) { box.appendChild(el('span', 'dp-dim', '—')); return box; }
        for (const c of p.cache) {
            if (!c.total) continue;   // 本包没有 spark 资源 → 不占行
            const row = el('div', 'dp-mono', c.label + ': ');
            const cls = c.stale ? 'dp-err' : (c.match === c.total ? 'dp-ok' : 'dp-warn');
            row.appendChild(el('span', cls, `${t('deploy.cacheHit')} ${c.present}/${c.total}`));
            if (c.stale) row.appendChild(el('span', 'dp-err', ` ≠${c.stale}`));
            if (!c.hasCacheRoot) row.appendChild(el('span', 'dp-dim', ' (' + (c.note || '') + ')'));
            box.appendChild(row);
        }
        if (!box.childNodes.length) box.appendChild(el('span', 'dp-dim', '—'));
        return box;
    }

    function cellSwitch(p) {
        const box = el('div', 'dp-switch ' + (p.enabled ? 'on' : 'off'), p.enabled ? 'ON' : 'OFF');
        if (p.hasDisabledMarker && !p.built.exists && p.archived.exists) {
            box.appendChild(el('div', 'dp-sub', 'disabled/'));
        }
        return box;
    }

    function cellActions(p) {
        const box = el('div', 'dp-actions');
        const mk = (label, cls, fn) => {
            const b = el('button', 'btn small ' + (cls || 'ghost'), label);
            b.disabled = busy;
            b.addEventListener('click', fn);
            box.appendChild(b);
            return b;
        };
        mk(t('deploy.build'), 'accent', () => buildPack(p));
        if (p.built.exists) {
            mk(t('deploy.install'), 'ghost', () => runOp(p, () => window.api.packStatusInstall({ jarName: p.jarName })));
            mk(t('deploy.clearCache'), 'ghost', () => clearCache(p));
        }
        if (p.hasDisabledMarker && p.built.exists) {
            mk(t('deploy.rearchive'), 'danger', () => runOp(p, () => window.api.packStatusSetEnabled({ jarName: p.jarName, enabled: false })));
        }
        mk(p.enabled ? t('deploy.off') : t('deploy.on'), p.enabled ? 'danger' : 'accent', () => toggle(p));
        // 只读回溯：不碰工作区（第 3 步才接上「归档 + 强制提交 + restore」的动作链）
        mk(t('deploy.history'), 'ghost', () => {
            if (window.GitHistoryView) window.GitHistoryView.open(p);
            else bar(t('history.failed') + ' views/gitHistoryView.js 未加载');
        });
        return box;
    }

    function cellIssues(p) {
        const box = el('ul', 'dp-issues');
        for (const i of p.issues) {
            const li = el('li', i.level === 'err' ? 'dp-err' : (i.level === 'warn' ? 'dp-warn' : 'dp-dim'),
                `[${i.code}] ${i.msg}`);
            li.title = i.src || '';
            box.appendChild(li);
        }
        return box;
    }

    // ===== 动作 =====

    /** 统一收尾：动作 → 重新测量 → 面板重绘；失败时把后端原文贴出来（不粉饰） */
    async function runOp(p, fn) {
        if (busy) return;
        busy = true;
        render();
        try {
            const res = await fn();
            if (!res) { bar('IPC 无响应（主进程未返回）'); return; }
            if (!res.ok) {
                bar(t('deploy.failed') + (res.error || '未知错误'));
                if (res.data && Array.isArray(res.data.actions)) {
                    bar(t('deploy.failed') + (res.error || '') + '\n已完成：' + res.data.actions.join(' / '));
                }
                return;
            }
            const d = res.data || {};
            const lines = [];
            if (Array.isArray(d.actions)) lines.push(...d.actions);
            if (Array.isArray(d.written)) lines.push(...d.written.map((x) => '→ ' + x));
            if (Array.isArray(d.deleted)) lines.push(`缓存删除 ${d.deleted.length} 个` + (d.conflicts && d.conflicts.length ? `，冲突未删 ${d.conflicts.length} 个` : ''));
            if (d.cacheWarning) lines.push(d.cacheWarning);
            if (d.hint) lines.push(d.hint);
            bar(tf('deploy.doneOp', { name: p.dirName, n: lines.length }) + '\n' + lines.join('\n'), true);
            if (d.needsRelaunch) bar(tf('deploy.doneOp', { name: p.dirName, n: lines.length }) + '\n' + lines.join('\n') + '\n' + t('deploy.relaunchHint'), true);
            await refresh(true);
        } catch (e) {
            bar(t('deploy.failed') + String((e && e.message) || e));
        } finally {
            busy = false;
            render();
        }
    }

    async function toggle(p) {
        const key = p.enabled ? 'deploy.confirmOff' : 'deploy.confirmOn';
        const label = tf(key, { name: p.dirName });
        const okLabel = p.enabled ? t('deploy.off') : t('deploy.on');
        let choice = null;
        if (window.askChoice) {
            choice = await window.askChoice({ title: okLabel, label, options: [okLabel, t('pack.cancel')] , value: okLabel });
        } else {
            choice = window.confirm(label + '\n' + t('deploy.relaunchHint')) ? okLabel : null;
        }
        if (!choice || choice === t('pack.cancel')) return;
        await runOp(p, () => window.api.packStatusSetEnabled({ jarName: p.jarName, enabled: !p.enabled }));
    }

    async function clearCache(p) {
        const label = p.jarName + ' → run/*/spark_modules';
        let choice = null;
        if (window.askChoice) {
            choice = await window.askChoice({
                title: t('deploy.clearCache'),
                label: `${label}\n只删「jar 里确有、且缓存内容与 jar 相同」的文件；内容不同的（可能是别的包同 id）会原样保留并列出来。`,
                options: [t('deploy.clearCache'), t('pack.cancel')],
                value: t('deploy.clearCache'),
            });
        } else {
            choice = window.confirm(label) ? t('deploy.clearCache') : null;
        }
        if (!choice || choice === t('pack.cancel')) return;
        await runOp(p, () => window.api.packStatusClearCache({ jarName: p.jarName }));
    }

    async function buildPack(p) {
        if (busy) return;
        busy = true;
        buildBusy = true;
        if (consoleFolded) toggleConsoleFold();
        setBuildState(tf('deploy.buildRunning', { name: p.dirName }));
        render();
        bar(tf('deploy.building', { name: p.dirName }));
        try {
            const res = await window.api.buildPack({ packDir: p.source.dir });
            buildBusy = false;
            setBuildState(res && res.ok ? tf('deploy.buildDone', { name: p.dirName }) : tf('deploy.buildFail', { name: p.dirName }));
            if (!res || !res.ok) {
                const d = res && res.data;
                let msg = t('deploy.failed') + ((res && res.error) || '未知错误');
                if (d && Array.isArray(d.verify) && d.verify.length) {
                    msg += '\n' + d.verify.filter((v) => v.level === 'error').map((v) => `· ${v.msg}`).join('\n');
                }
                bar(msg);
                return;
            }
            const d = res.data || {};
            bar(`✓ ${p.dirName} 打包完成（${d.mode || ''}）\n` +
                ((d.verify || []).map((v) => `${v.level === 'error' ? '✗' : (v.level === 'warn' ? '!' : '·')} ${v.msg}`).join('\n')), true);
            // 打完立刻装 + 重测：copyContentPacksTo* 只在 Gradle 任务链里跑，这里不等它
            await refresh(true);
        } catch (e) {
            bar(t('deploy.failed') + String((e && e.message) || e));
        } finally {
            busy = false;
            buildBusy = false;
            render();
        }
    }

    // ===== 渲染 =====

    function render() {
        const body = document.getElementById('deployBody');
        if (!body) return;
        body.innerHTML = '';
        const scanned = document.getElementById('deployScanned');
        if (scanned) scanned.textContent = data && data.scannedAt ? fmtTime(data.scannedAt) + ' UTC' : '';

        if (!data) {
            body.appendChild(el('div', 'dp-banner', t('deploy.measuring')));
            return;
        }
        if (data.duplicates && data.duplicates.length) {
            for (const [name, jars] of data.duplicates) {
                body.appendChild(el('div', 'dp-banner err', `${t('deploy.dupName')}${name} → ${jars.join(' / ')}\n${t('deploy.dupHint')}`));
            }
        }
        if (data.nonPackDirs && data.nonPackDirs.length) {
            body.appendChild(el('div', 'dp-banner', t('deploy.nonPack') + data.nonPackDirs.map((d) => d.dirName).join(', ')));
        }

        const table = el('table', 'dp-table');
        const thead = el('thead');
        const htr = el('tr');
        for (const k of ['deploy.colPack', 'deploy.colSource', 'deploy.colJar', 'deploy.colInstall', 'deploy.colCache', 'deploy.colSwitch', 'deploy.colAction']) {
            htr.appendChild(el('th', null, t(k)));
        }
        thead.appendChild(htr);
        table.appendChild(thead);

        const tbody = el('tbody');
        for (const p of data.packs) {
            const tr = el('tr');
            const errs = p.issues.filter((i) => i.level === 'err').length;
            const warns = p.issues.filter((i) => i.level === 'warn').length;
            if (errs) tr.className = 'dp-bad';
            else if (warns) tr.className = 'dp-warnrow';

            const nameTd = el('td');
            nameTd.appendChild(el('div', 'dp-name', p.dirName));
            nameTd.appendChild(el('div', 'dp-sub dp-mono', p.jarName));
            nameTd.appendChild(cellIssues(p));
            if (!errs && !warns) nameTd.appendChild(el('div', 'dp-ok dp-sub', t('deploy.allOk')));
            tr.appendChild(nameTd);

            tr.appendChild(el('td', null, null)).appendChild(cellSource(p));
            tr.appendChild(el('td', null, null)).appendChild(cellJar(p));
            tr.appendChild(el('td', null, null)).appendChild(cellInstall(p));
            tr.appendChild(el('td', null, null)).appendChild(cellCache(p));
            tr.appendChild(cellSwitch(p));
            tr.appendChild(el('td', null, null)).appendChild(cellActions(p));
            tbody.appendChild(tr);
        }
        table.appendChild(tbody);
        body.appendChild(table);
    }

    async function refresh(silent) {
        if (!silent) bar(t('deploy.measuring'));
        const body = document.getElementById('deployBody');
        if (!data && body) {
            // 首次进入先占个位，避免点了没反应的观感（0.2.1 同款教训）
            body.innerHTML = '';
            body.appendChild(el('div', 'dp-banner', t('deploy.measuring')));
        }
        let res;
        try { res = await window.api.packStatusInspect({}); }
        catch (e) { res = { ok: false, error: String((e && e.message) || e) }; }
        if (!res || !res.ok) {
            data = null;
            bar((res && res.error) || t('deploy.measuring'));
            if (body) {
                body.innerHTML = '';
                body.appendChild(el('div', 'dp-banner err', (res && res.error) || t('deploy.failed')));
            }
            return;
        }
        data = res.data;
        render();
        if (!silent) bar('');
    }

    function isOpen() {
        const o = document.getElementById('deployOverlay');
        return !!o && !o.classList.contains('hidden');
    }

    function open(opts) {
        const o = document.getElementById('deployOverlay');
        if (!o) return;
        o.classList.remove('hidden');
        // opts.buildDirName：中栏「📦 打包」进来时直接对该包开构建 —— 那里没有四态记录，
        // 必须先 refresh() 拿到 data.packs 才能找到对应的 p。
        const want = opts && opts.buildDirName;
        refresh().then(() => {
            if (!want || busy || !data) return;
            const p = (data.packs || []).find((x) => x.dirName === want);
            if (p) buildPack(p);
        });
    }

    function close() {
        const o = document.getElementById('deployOverlay');
        if (o) o.classList.add('hidden');
    }

    function init() {
        const btnOpen = document.getElementById('btnDeployStatus');
        // 不直接把 open 当监听器：addEventListener 会把 MouseEvent 当第一个参数传进去，
        // 而 open(opts) 现在会读 opts.buildDirName。
        if (btnOpen) btnOpen.addEventListener('click', () => open());
        const btnClose = document.getElementById('btnDeployClose');
        if (btnClose) btnClose.addEventListener('click', close);
        const btnRefresh = document.getElementById('btnDeployRefresh');
        if (btnRefresh) btnRefresh.addEventListener('click', () => refresh());
        const o = document.getElementById('deployOverlay');
        if (o) o.addEventListener('mousedown', (e) => { if (e.target === o) close(); });
        // 构建输出流：主进程 pack:output → 面板控制台（只注册一次，重复注册会翻倍显示）
        if (!unsubOutput && window.api && typeof window.api.onBuildOutput === 'function') {
            try { unsubOutput = window.api.onBuildOutput(pushOutput); } catch { /* 老 preload 没这键 */ }
        }
        const btnFold = document.getElementById('btnBuildConsoleToggle');
        if (btnFold) btnFold.addEventListener('click', toggleConsoleFold);
        const btnClear = document.getElementById('btnBuildConsoleClear');
        if (btnClear) btnClear.addEventListener('click', clearConsole);
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape' && isOpen()) close();
        });
    }

    return { init, open, close, refresh, rerender: render, isOpen, _console: () => consoleBuf, _push: pushOutput };
})();
