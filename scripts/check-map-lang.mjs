#!/usr/bin/env node
// 地图包本地化闸门（D89，只读、不启游戏）。
//
//   node scripts/check-map-lang.mjs [packDir]
//
// 三条规则：
//   1. 包内 Java 里出现的翻译键（catoxidesbattlerebuild.* / hudlayout.* / key.*）
//      必须存在于 en_us.json —— 缺键 FAIL。
//   2. en_us 的键集减去 zh_cn 的键集 —— 只列缺口 WARN（允许回落英文，Q6）。
//   3. Component.literal("词") 与 drawString(..., "词") 里出现两个以上连续字母
//      —— 新增写死的界面英文 FAIL（符号/单位白名单见 isSymbolOnly）。

import { readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const packDir = resolve(repoRoot, process.argv[2] ?? "contentpacks-source/map-pack-v2");
const langDir = join(packDir, "assets/map-pack/lang");
const enUsPath = join(langDir, "en_us.json");
const zhCnPath = join(langDir, "zh_cn.json");

const failures = [];
const warnings = [];

function readJson(path) {
    return JSON.parse(readFileSync(path, "utf8"));
}

function walkJava(dir) {
    const files = [];
    for (const entry of readdirSync(dir)) {
        const full = join(dir, entry);
        if (statSync(full).isDirectory()) {
            files.push(...walkJava(full));
        } else if (entry.endsWith(".java")) {
            files.push(full);
        }
    }
    return files;
}

function lineAt(text, index) {
    return text.slice(0, index).split("\n").length;
}

function fail(message) {
    failures.push(message);
}

function warn(message) {
    warnings.push(message);
}

const enUs = readJson(enUsPath);
const zhCn = readJson(zhCnPath);

// ---- 1. 翻译键必须存在于 en_us（也覆盖三元表达式里的两个分支） ----

const KEY_PREFIXES = [
    "catoxidesbattlerebuild.",
    "hudlayout.",
    "key.",
];
const keyPattern = /"((?:[^"\\]|\\.)*)"/g;

for (const file of walkJava(packDir)) {
    const text = readFileSync(file, "utf8");
    const shown = relative(repoRoot, file);
    for (const match of text.matchAll(keyPattern)) {
        const value = match[1];
        if (!KEY_PREFIXES.some((prefix) => value.startsWith(prefix))) {
            continue;
        }
        if (value.endsWith(".")) {
            // 动态拼接前缀（如 marker.type. + enum）：至少要能拼出某个现有键
            if (!Object.keys(enUs).some((key) => key.startsWith(value))) {
                fail(`${shown}:${lineAt(text, match.index)} 无键可拼的翻译前缀 "${value}"`);
            }
            continue;
        }
        if (!Object.prototype.hasOwnProperty.call(enUs, value)) {
            fail(`${shown}:${lineAt(text, match.index)} en_us 缺键 "${value}"`);
        }
    }
}

// ---- 2. zh_cn 允许缺键，只列缺口；多余键提示拼写 ----

const enKeys = new Set(Object.keys(enUs));
const zhKeys = new Set(Object.keys(zhCn));
const missingZh = [...enKeys].filter((key) => !zhKeys.has(key));
// .tooltip 只在 zh_cn 里写（en_us 继续用 TOML comment，见 D84），不算多余
const extraZh = [...zhKeys].filter((key) => !enKeys.has(key) && !key.endsWith(".tooltip"));
if (missingZh.length > 0) {
    warn(`zh_cn 缺 ${missingZh.length} 个键（回落 en_us）: ${missingZh.slice(0, 12).join(", ")}`
            + (missingZh.length > 12 ? " ..." : ""));
}
if (extraZh.length > 0) {
    warn(`zh_cn 多出 ${extraZh.length} 个 en_us 没有的键: ${extraZh.slice(0, 12).join(", ")}`
            + (extraZh.length > 12 ? " ..." : ""));
}

// ---- 3. 界面上的写死文案（符号/单位放过） ----

const literalPatterns = [
    /Component\.literal\(\s*"((?:[^"\\]|\\.)*)"/g,
    /\.draw(?:Centered)?String\([^;]*?,\s*"((?:[^"\\]|\\.)*)"/g,
];

function isSymbolOnly(value) {
    // 不含两个连续拉丁字母：单个字母的热键（C/X/Y/Z）、符号、单位、空白都放过
    return !/[A-Za-z]{2,}/.test(value);
}

for (const file of walkJava(packDir)) {
    const text = readFileSync(file, "utf8");
    const shown = relative(repoRoot, file);
    for (const pattern of literalPatterns) {
        for (const match of text.matchAll(pattern)) {
            const value = match[1];
            if (value !== "" && isSymbolOnly(value)) {
                continue;
            }
            if (value === "") {
                continue;
            }
            fail(`${shown}:${lineAt(text, match.index)} 写死的界面文案 "${value}"`);
        }
    }
}

// ---- 汇总 ----

for (const warning of warnings) {
    console.log(`[WARN] ${warning}`);
}
for (const failure of failures) {
    console.log(`[FAIL] ${failure}`);
}
console.log(`check-map-lang: FAIL=${failures.length} WARN=${warnings.length}`
        + ` (en_us=${enKeys.size} zh_cn=${zhKeys.size})`);
process.exitCode = failures.length > 0 ? 1 : 0;
