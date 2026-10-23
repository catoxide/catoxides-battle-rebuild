// commandRunner.js - 子进程命令执行（0.2.2 第 2 步：从 main.js 抽出来，好让它能被单测覆盖）
// 暴露：runCommand(cmd, args, opts) → Promise<{code, stdout, stderr}>
//
// 两条铁律（都是这仓库里踩过的坑）：
//   ① 绝不走 shell。Windows 下 shell:true 时参数里的 & | ^ < > 可被 cmd.exe 解析成命令
//      （已实证：包目录名 'x & calc' 点打包即执行任意命令）。spawn 直接把 argv 交给
//      CreateProcess，.bat/.cmd 由系统自己包一层 cmd，元字符保持字面量。
//   ② 长任务要能被看见。opts.onOutput(kind, text) 在每个 data 事件上回调，
//      这样打包这类分钟级任务可以实时把 Gradle 原文推到界面，而不是等终态。
//      回调抛错一律吞掉：日志推送绝不能把构建本身搞挂。
'use strict';

const { spawn } = require('child_process');

/**
 * @param {string} cmd
 * @param {string[]} args           必须以数组给出（不拼字符串）
 * @param {object} opts
 * @param {string} [opts.cwd]
 * @param {object} [opts.env]
 * @param {(kind:'stdout'|'stderr', text:string)=>void} [opts.onOutput]   实时输出回调
 * @param {string} [opts.failPrefix] 非 0 退出时错误信息的前缀（默认「<cmd> 退出码 N」）
 * @returns {Promise<{code:number, stdout:string, stderr:string}>}
 */
function runCommand(cmd, args, opts = {}) {
    const argv = Array.isArray(args) ? args : [];
    const emit = typeof opts.onOutput === 'function' ? opts.onOutput : null;
    return new Promise((resolve, reject) => {
        let child;
        try {
            child = spawn(cmd, argv, Object.assign({ windowsHide: true }, opts));
        } catch (e) {
            reject(e);
            return;
        }
        let stdout = '';
        let stderr = '';
        if (child.stdout) child.stdout.on('data', (d) => {
            const text = d.toString();
            stdout += text;
            if (emit) { try { emit('stdout', text); } catch { /* 推送失败不拖垮命令 */ } }
        });
        if (child.stderr) child.stderr.on('data', (d) => {
            const text = d.toString();
            stderr += text;
            if (emit) { try { emit('stderr', text); } catch { /* 同上 */ } }
        });
        child.on('close', (code) => {
            if (code === 0) { resolve({ code, stdout, stderr }); return; }
            reject(new Error(opts.failPrefix
                ? `${opts.failPrefix}（退出码 ${code}）\n${stdout}\n${stderr}`
                : `${cmd} 退出码 ${code}\n${stdout}\n${stderr}`));
        });
        child.on('error', (err) => reject(err));
    });
}

module.exports = { runCommand };
