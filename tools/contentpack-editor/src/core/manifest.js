// manifest.js - MANIFEST.MF 读写与包扫描
// 格式兼容 CONTENTPACK_FORMAT.md §2：Module-Name / Module-Version / Module-Type / Module-Entry
// Module-Requires / Module-Dependencies / Module-Mod-Requires / Module-Mod-Optional
const fs = require('fs');
const path = require('path');

/**
 * 解析 MANIFEST.MF 文本为对象
 * 支持标准格式 "Key: value"（保留冒号后的空格，统一 trim）。
 * REVIEW-L8：支持 72 字节折行的续行 —— 以单个空格开头的行是上一属性值的延续
 * （规范 JAR Manifest：行长限 72 字节，续行行首空格不计入值）。
 * 此前不处理 → 长属性值（>70 字符，如超长 Module-Editor-Schema/Module-Requires）被误拆成新键。
 * @param {string} content
 * @returns {Object<string,string>}
 */
function parseManifest(content) {
    const result = {};
    let pending = null; // [key, value]（等待可能的续行）
    const commit = () => { if (pending) { result[pending[0]] = pending[1]; pending = null; } };
    for (const rawLine of String(content == null ? '' : content).split(/\r?\n/)) {
        // 续行判定必须在 trim 之前（行首单空格 = 续行标记）
        if (rawLine.startsWith(' ') || rawLine.startsWith('\t')) {
            if (pending) pending[1] += rawLine.slice(1);
            continue;
        }
        const line = rawLine.trim();
        if (!line || line.startsWith('#')) { commit(); continue; }
        const idx = line.indexOf(':');
        if (idx === -1) { commit(); continue; }
        const key = line.slice(0, idx).trim();
        if (!key) { commit(); continue; }
        commit();
        pending = [key, line.slice(idx + 1).trim()];
    }
    commit();
    return result;
}

/**
 * 对象序列化为 MANIFEST.MF 文本（保持声明顺序）
 * @param {Object<string,string>} manifest
 * @returns {string}
 */
function stringifyManifest(manifest) {
    const lines = ['Manifest-Version: 1.0'];
    for (const [key, value] of Object.entries(manifest)) {
        if (!value) continue;
        lines.push(`${key}: ${value}`);
    }
    return lines.join('\r\n') + '\r\n';
}

/**
 * 从包目录读取 MANIFEST
 * @param {string} packDir 包源码目录（含 MANIFEST.MF）
 * @returns {Object<string,string>}
 */
function readManifest(packDir) {
    const file = path.join(packDir, 'MANIFEST.MF');
    if (!fs.existsSync(file)) {
        throw new Error(`MANIFEST.MF 不存在: ${packDir}`);
    }
    return parseManifest(fs.readFileSync(file, 'utf-8'));
}

/**
 * 写入 MANIFEST 到包目录
 * @param {string} packDir
 * @param {Object<string,string>} manifest
 */
function writeManifest(packDir, manifest) {
    const file = path.join(packDir, 'MANIFEST.MF');
    fs.mkdirSync(packDir, { recursive: true });
    fs.writeFileSync(file, stringifyManifest(manifest), 'utf-8');
}

/**
 * 扫描源码目录下的所有内容包
 * 每个含 MANIFEST.MF 的子目录视为一个包
 * @param {string} sourceDir contentpacks-source/
 * @returns {Array<{dir:string, name:string, version:string, manifest:Object}>}
 */
function scanPacks(sourceDir) {
    if (!fs.existsSync(sourceDir)) return [];
    const packs = [];
    for (const entry of fs.readdirSync(sourceDir, { withFileTypes: true })) {
        if (!entry.isDirectory()) continue;
        const packDir = path.join(sourceDir, entry.name);
        const manifestFile = path.join(packDir, 'MANIFEST.MF');
        if (!fs.existsSync(manifestFile)) continue;
        try {
            const manifest = readManifest(packDir);
            packs.push({
                dir: packDir,
                name: manifest['Module-Name'] || entry.name,
                version: manifest['Module-Version'] || '0.0.0',
                manifest,
                // 是否是可被 settings.gradle 自动发现并 contentpackJar 构建的 Gradle 包
                hasBuildGradle: fs.existsSync(path.join(packDir, 'build.gradle')),
            });
        } catch {
            // 跳过损坏的包
        }
    }
    return packs;
}

module.exports = {
    parseManifest,
    stringifyManifest,
    readManifest,
    writeManifest,
    scanPacks,
};
