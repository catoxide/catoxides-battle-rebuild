// jarReader.js - 极简 zip/jar 读取器（只读中央目录，够用即可）
//
// 为什么需要它：内容包 jar 的 MANIFEST 决定包能否被识别，而「同 Module-Name 两份 jar」
// 这类事故只有读 jar 内部才能发现（ContentPackLoader 只会在运行期打一条日志）。
// 编辑器在【打包后校验】与【重复 Module-Name 扫描】时用它。
//
// 只支持 store(0) / deflate(8)，只读中央目录 + 本地头，不处理 zip64 / 加密 / 多卷。
// 内容包 jar 都在几十 KB 量级，直接整文件读入。
const fs = require('fs');
const zlib = require('zlib');

const EOCD_SIG = 0x06054b50;
const CEN_SIG = 0x02014b50;
const LOC_SIG = 0x04034b50;
const MAX_JAR_BYTES = 64 * 1024 * 1024;

function readEntries(jarPath) {
    const stat = fs.statSync(jarPath);
    if (stat.size > MAX_JAR_BYTES) {
        throw new Error(`jar 过大，超出只读读取器范围: ${stat.size} bytes`);
    }
    const buf = fs.readFileSync(jarPath);

    // --- 1. 从尾部回扫 EOCD ---
    let eocd = -1;
    const minPos = Math.max(0, buf.length - 0xFFFF - 22);
    for (let i = buf.length - 22; i >= minPos; i--) {
        if (buf.readUInt32LE(i) === EOCD_SIG) { eocd = i; break; }
    }
    if (eocd < 0) throw new Error('不是有效的 zip/jar（找不到 EOCD）');

    const count = buf.readUInt16LE(eocd + 10);
    const cenSize = buf.readUInt32LE(eocd + 12);
    const cenOff = buf.readUInt32LE(eocd + 16);

    // --- 2. 遍历中央目录 ---
    const entries = [];
    let p = cenOff;
    const cenEnd = cenOff + cenSize;
    for (let i = 0; i < count && p + 46 <= cenEnd; i++) {
        if (buf.readUInt32LE(p) !== CEN_SIG) break;
        const method = buf.readUInt16LE(p + 10);
        const compSize = buf.readUInt32LE(p + 20);
        const uncompSize = buf.readUInt32LE(p + 24);
        const nameLen = buf.readUInt16LE(p + 28);
        const extraLen = buf.readUInt16LE(p + 30);
        const commentLen = buf.readUInt16LE(p + 32);
        const localOff = buf.readUInt32LE(p + 42);
        const name = buf.toString('utf8', p + 46, p + 46 + nameLen);
        entries.push({ name, method, compSize, uncompSize, localOff });
        p += 46 + nameLen + extraLen + commentLen;
    }
    return { buf, entries };
}

/** 列出条目 [{name, size, method}] */
function listEntries(jarPath) {
    const { entries } = readEntries(jarPath);
    return entries.map((e) => ({ name: e.name, size: e.uncompSize, method: e.method }));
}

/** 读取单个条目内容（Buffer），不存在返回 null */
function readEntry(jarPath, wanted) {
    const { buf, entries } = readEntries(jarPath);
    const e = entries.find((x) => x.name === wanted);
    if (!e) return null;
    if (buf.readUInt32LE(e.localOff) !== LOC_SIG) throw new Error(`本地头损坏: ${e.name}`);
    const nameLen = buf.readUInt16LE(e.localOff + 26);
    const extraLen = buf.readUInt16LE(e.localOff + 28);
    const dataOff = e.localOff + 30 + nameLen + extraLen;
    const raw = buf.subarray(dataOff, dataOff + e.compSize);
    if (e.method === 0) return Buffer.from(raw);
    if (e.method === 8) return zlib.inflateRawSync(raw);
    throw new Error(`不支持的压缩方式 ${e.method}: ${e.name}`);
}

/**
 * 读 jar 的 MANIFEST.MF 主属性 → 对象；无 MANIFEST 返回 null。
 * REVIEW-L8：支持 72 字节折行续行（行首单空格 = 续行，与 manifest.parseManifest 一致）。
 */
function readManifest(jarPath) {
    const raw = readEntry(jarPath, 'META-INF/MANIFEST.MF');
    if (!raw) return null;
    const attrs = {};
    let pending = null;
    const commit = () => { if (pending) { attrs[pending[0]] = pending[1]; pending = null; } };
    for (const rawLine of raw.toString('utf8').split(/\r?\n/)) {
        if (rawLine.startsWith(' ') || rawLine.startsWith('\t')) {
            if (pending) pending[1] += rawLine.slice(1);
            continue;
        }
        const line = rawLine.trim();
        if (!line) { commit(); continue; }
        const i = line.indexOf(':');
        if (i <= 0) { commit(); continue; }
        const key = line.slice(0, i).trim();
        if (!/^[A-Za-z][A-Za-z0-9-]*$/.test(key)) { commit(); continue; }
        commit();
        pending = [key, line.slice(i + 1).trim()];
    }
    commit();
    return attrs;
}

/** 该 jar 是否是一个内容包（Module-Type: contentpack） */
function isContentPack(jarPath) {
    const m = readManifest(jarPath);
    return !!(m && m['Module-Type'] === 'contentpack');
}

const JarReaderAPI = { listEntries, readEntry, readManifest, isContentPack, MAX_JAR_BYTES };

if (typeof module === 'object' && module.exports) module.exports = JarReaderAPI;
if (typeof self !== 'undefined') self.JarReader = JarReaderAPI;
