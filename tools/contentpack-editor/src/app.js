// app.js - 渲染进程主逻辑（加载顺序在 views 之后）
// 定义 window.AppState / window.showError / window.showStatus
// 初始化：显示路径 → 包列表 → 联动实体列表 → 实体编辑器

(function () {
    // ===== 全局状态 =====
    window.AppState = {
        pack: null,          // 当前选中包 { dir, name, version, manifest }
        entity: null,        // 当前编辑中的实体对象
        entities: [],        // 当前包的实体列表
        model: null,         // 当前已解析的模型
        healthConfig: { parts: [] },  // 当前实体的部位配置
    };

    // ===== 全局 UI 辅助 =====
    window.showError = function (msg) {
        const bar = document.getElementById('statusBar');
        if (bar) {
            bar.className = 'status err';
            bar.textContent = msg;
        }
        console.error('[Editor]', msg);
    };

    window.showStatus = function (msg, ok) {
        const bar = document.getElementById('statusBar');
        if (bar) {
            bar.className = 'status ' + (ok ? 'ok' : 'err');
            bar.textContent = msg;
        }
    };

    // ===== 启动 =====
    async function init() {
        // 显示路径信息
        const pathsRes = await window.api.getPaths();
        if (pathsRes.ok) {
            const p = pathsRes.data;
            if (p.needProjectRoot) {
                await setupProjectRoot();
                return;
            }
            document.getElementById('headerPaths').textContent = `源码: ${p.packsSourceDir}`;
        }

        // 初始化左栏（包列表）→ 选中包 → 加载实体列表
        window.PackList.init(async (pack) => {
            window.AppState.pack = pack;
            document.getElementById('packNameLabel').textContent = pack.name + ' v' + pack.version;
            document.getElementById('btnNewEntity').disabled = false;
            await window.EntityList.loadEntities(pack.dir);
        });

        // 初始化中栏（实体列表）
        window.EntityList.init({
            onSelect: async (packDir, ent) => {
                const readRes = await window.api.readEntity(packDir, ent.id);
                if (!readRes.ok) { window.showError('读取实体失败: ' + readRes.error); return; }
                window.EntityEditor.open({ pack: window.AppState.pack, entity: readRes.data, isNew: false });
            },
            onNew: async (packDir) => {
                window.EntityEditor.open({ pack: window.AppState.pack, entity: null, isNew: true });
            },
        });

        // 自动加载包列表
        await window.PackList.loadPacks();
    }

    // 项目根引导：应用内提示 → 选目录 → 生效后重载界面
    async function setupProjectRoot() {
        const bar = document.getElementById('statusBar');
        if (bar) {
            bar.className = 'status err';
            bar.textContent = '未找到项目目录。请选择项目根（含 contentpacks-source 的目录）。';
        }
        const res = await window.api.pickProjectRoot();
        if (!res.ok) {
            if (res.error === 'cancelled') {
                window.showError('未选择项目目录，编辑器不可用。点击此处重试 →');
                document.querySelector('#statusBar').style.cursor = 'pointer';
                document.querySelector('#statusBar').addEventListener('click', () => location.reload());
            } else {
                window.showError('设置项目根失败: ' + res.error);
            }
            return;
        }
        window.showStatus('项目目录已设置，加载中…', true);
        location.reload();
    }

    document.addEventListener('DOMContentLoaded', init);
})();
