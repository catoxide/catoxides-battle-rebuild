// errorCapture.js - W3.6 渲染端错误捕获（外部文件：主窗口 CSP script-src 'self' 屏蔽内联脚本）
// 未捕获 JS（window error）+ 未处理 Promise 拒绝（unhandledrejection）
//   → window.api.log 送主进程落盘（<根>/logs/contentpack-editor/editor.log）
//   → 模型编辑器窗口（存在 #meStatus）同时状态栏红字提示
// 注意：日志通路自身 try/catch 全包裹——捕获器绝不能成为新的故障源。
(function () {
    'use strict';
    // 作用域：模型编辑器窗口有 #meStatus（footer 先于本脚本解析）
    var scope = document.getElementById('meStatus') ? 'model-editor' : 'main-window';
    function send(level, msg) {
        try {
            if (window.api && window.api.log) window.api.log(level, scope, msg);
        } catch (e) { /* 日志失败不二次抛出 */ }
        try {
            if (level === 'error') {
                var st = document.getElementById('meStatus');
                if (st) {
                    st.textContent = '⚠ ' + msg + ' —— 已记录日志（按 F12 打开 DevTools 看堆栈）';
                    st.classList.add('err');
                }
            }
        } catch (e) { /* 同上 */ }
    }
    window.addEventListener('error', function (e) {
        var loc = ((e.filename || '').split(/[\\/]/).pop()) + ':' + (e.lineno || '?');
        send('error', 'JS 错误: ' + (e.message || e) + ' @ ' + loc);
    });
    window.addEventListener('unhandledrejection', function (e) {
        var r = e.reason;
        var detail = r && (r.stack || r.message) ? (r.stack || r.message) : String(r);
        send('error', '未处理的 Promise 拒绝: ' + detail);
    });
})();
