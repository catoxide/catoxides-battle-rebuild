// logger.js - W3.6 文件日志（主进程）
// - 目录：<项目根>/logs/contentpack-editor/（未设项目根时回退 console）
// - 文件：editor.log，>1MB 轮转为 editor.old.log
// - appendFileSync：崩溃瞬间也能留下最后一条记录；写失败绝不抛出（日志系统自身不能成为故障源）
// - 格式：[ISO 时间] [LEVEL] [scope] 消息（换行折叠为 |）
const fs = require('fs');
const path = require('path');

const MAX_BYTES = 1024 * 1024; // 1MB 轮转阈值
let dir = null;
let file = null;

function ensure(root) {
    if (!root) return false;
    dir = path.join(root, 'logs', 'contentpack-editor');
    try {
        fs.mkdirSync(dir, { recursive: true });
        file = path.join(dir, 'editor.log');
        return true;
    } catch (e) {
        console.error('[logger] 目录创建失败:', e.message);
        dir = null;
        file = null;
        return false;
    }
}

function rotate() {
    try {
        if (fs.existsSync(file) && fs.statSync(file).size > MAX_BYTES) {
            fs.renameSync(file, path.join(dir, 'editor.old.log'));
        }
    } catch {
        // 轮转失败（占用/权限）不阻塞写入
    }
}

function log(level, scope, msg) {
    const line = `[${new Date().toISOString()}] [${String(level).toUpperCase()}] [${scope || '-'}] ${String(msg).replace(/\r?\n/g, ' | ')}`;
    if (!file) {
        // 项目根未就绪：回退 console（开发态 electron 终端可见）
        if (level === 'error') console.error(line);
        else console.log(line);
        return;
    }
    try {
        rotate();
        fs.appendFileSync(file, line + '\n', 'utf8');
    } catch (e) {
        console.error('[logger] 写入失败:', e.message);
    }
}

module.exports = {
    /** 设置项目根并创建日志目录；返回是否成功 */
    init(root) { return ensure(root); },
    log,
    info: (scope, msg) => log('info', scope, msg),
    warn: (scope, msg) => log('warn', scope, msg),
    error: (scope, msg) => log('error', scope, msg),
    /** 日志目录（UI 展示/打开用；未初始化 = null） */
    getLogDir: () => dir,
    /** 当前日志文件路径 */
    getLogFile: () => file,
};
