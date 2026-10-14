// logger.test.js - W3.6 文件日志：格式 / 轮转 / 容错
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');
const Logger = require('../logger');

let passed = 0, failed = 0;
function t(name, fn) {
    try { fn(); console.log('  ✓ ' + name); passed++; }
    catch (e) { console.error('  ✗ ' + name + ': ' + e.message); failed++; }
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'logger-test-'));
const logDir = path.join(tmp, 'logs', 'contentpack-editor');
const logFile = path.join(logDir, 'editor.log');

t('init 创建日志目录并返回 true', () => {
    assert.strictEqual(Logger.init(tmp), true);
    assert.ok(fs.existsSync(logDir));
    assert.strictEqual(Logger.getLogFile(), logFile);
});

t('info/warn/error 写入且格式正确（换行折叠为 |）', () => {
    Logger.info('test', 'hello');
    Logger.warn('test', 'careful');
    Logger.error('test', 'bad\nthing');
    const lines = fs.readFileSync(logFile, 'utf8').trim().split('\n');
    assert.strictEqual(lines.length, 3);
    assert.match(lines[0], /^\[\d{4}-\d{2}-\d{2}T[\d:.]+Z\] \[INFO\] \[test\] hello$/);
    assert.match(lines[1], / \[WARN\] \[test\] careful$/);
    assert.match(lines[2], / \[ERROR\] \[test\] bad \| thing$/);
});

t('超过 1MB 轮转为 editor.old.log', () => {
    const pad = 'x'.repeat(1024 * 1024 - fs.statSync(logFile).size + 10);
    Logger.info('pad', pad);
    assert.ok(fs.statSync(logFile).size > 1024 * 1024);
    Logger.info('after-rotate', 'marker');
    assert.ok(fs.existsSync(path.join(logDir, 'editor.old.log')), 'old.log 应存在');
    const tail = fs.readFileSync(logFile, 'utf8');
    assert.ok(tail.includes('after-rotate'));
    assert.ok(!tail.includes('hello'), '新文件不应含轮转前内容');
});

t('init 到"文件路径之下"返回 false（不抛异常）', () => {
    const blocker = path.join(tmp, 'blocker-file');
    fs.writeFileSync(blocker, 'x');
    assert.strictEqual(Logger.init(path.join(blocker, 'sub')), false);
});

t('日志未初始化时 log 回退 console 不抛异常', () => {
    // 上一条 init 失败后内部 file=null；此处 log 应走 console 分支不抛
    Logger.info('fallback', 'should-not-throw');
    Logger.error('fallback', 'error-should-not-throw');
    assert.ok(true);
});

// 清理
fs.rmSync(tmp, { recursive: true, force: true });
console.log(`\n=== logger 测试: ${passed} passed, ${failed} failed ===`);
process.exit(failed ? 1 : 0);
