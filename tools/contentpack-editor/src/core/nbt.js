// nbt.js - 极简 NBT 读写（gzip，面向结构模板 .nbt）
//
// 【NBT 二进制规范】（对照真实 MC 1.21.1 level.dat 逐字节验证 + nbtlib 2.0.4 源码）：
//   根：[类型号 1B=10][根名长 int16][根名][compound 值体]
//   compound 值体：( [类型号 1B][名长 int16][名][值体] )* + [0x00 END]
//     —— 条目是【类型号在前、名字在后】（不是名字在前！）
//   list 值体：[元素类型号 1B][数量 int32]( [值体] )*
//     —— 元素只写值体，类型由列表头声明（level.dat 实证：元素不重复类型号）
//   字节序：Minecraft 用【小端】（level.dat 实证）；NBT 规范原文/部分工具（nbtlib 默认）
//     用大端 → 本模块两种都支持（endianness 参数），decodeAuto 自动判别。
//
// 编码 API：
//   NBT.encode(rootObj, endianness?) → Buffer（未压缩；'LE' 默认 | 'BE'）
//   NBT.gzip(rootObj, endianness?)   → Buffer（gzip 后，可直接写 .nbt）
// 解码 API：
//   NBT.decode(buffer, endianness?)  → 普通 JS 对象树（自动识别 gzip 魔数）
//   NBT.decodeAuto(buffer)           → { root, endianness }：LE/BE 都试，按可解析性打分选优
//
// tag 构建器：NBT.str / NBT.int / NBT.byte / NBT.list(元素类型号, items) / ...
//   构建器产物 { $t, v } 是中间表示，与字节序无关。

'use strict';
const zlib = require('zlib');

// ===== 类型号 =====
const T_END = 0, T_BYTE = 1, T_SHORT = 2, T_INT = 3, T_LONG = 4, T_FLOAT = 5, T_DOUBLE = 6, T_STRING = 8, T_LIST = 9, T_COMPOUND = 10;

// ===== 构建器（与字节序无关） =====

function str(s) { return { $t: T_STRING, v: String(s == null ? '' : s) }; }
function byte(n) { return { $t: T_BYTE, v: Math.trunc(n) }; }
function int(n) { return { $t: T_INT, v: Math.trunc(n) }; }
function long(n) { return { $t: T_LONG, v: BigInt(n) }; }
function flt(n) { return { $t: T_FLOAT, v: n }; }
function dbl(n) { return { $t: T_DOUBLE, v: n }; }
function list(elemType, items) { return { $t: T_LIST, elemType: elemType, v: items || [] }; }

// ===== 字节读写（按端序） =====

function makeReader(buf, le) {
    return {
        b: buf, o: 0,
        u8() { return this.b.readUInt8(this.o++); },
        i16(n = 2) { const v = le ? this.b.readInt16LE(this.o) : this.b.readInt16BE(this.o); this.o += n; return v; },
        i32() { const v = le ? this.b.readInt32LE(this.o) : this.b.readInt32BE(this.o); this.o += 4; return v; },
        big64() { const v = le ? this.b.readBigInt64LE(this.o) : this.b.readBigInt64BE(this.o); this.o += 8; return v; },
        f32() { const v = le ? this.b.readFloatLE(this.o) : this.b.readFloatBE(this.o); this.o += 4; return v; },
        f64() { const v = le ? this.b.readDoubleLE(this.o) : this.b.readDoubleBE(this.o); this.o += 8; return v; },
        utf8(n) { const v = this.b.toString('utf-8', this.o, this.o + n); this.o += n; return v; },
    };
}

function makeWriter(le) {
    const chunks = [];
    return {
        chunks,
        u8(v) { chunks.push(Buffer.from([v & 0xff])); },
        i16(v) { const x = Buffer.alloc(2); (le ? x.writeInt16LE : x.writeInt16BE).call(x, v, 0); chunks.push(x); },
        i32(v) { const x = Buffer.alloc(4); (le ? x.writeInt32LE : x.writeInt32BE).call(x, v, 0); chunks.push(x); },
        big64(v) { const x = Buffer.alloc(8); (le ? x.writeBigInt64LE : x.writeBigInt64BE).call(x, BigInt(v), 0); chunks.push(x); },
        f32(v) { const x = Buffer.alloc(4); (le ? x.writeFloatLE : x.writeFloatBE).call(x, v, 0); chunks.push(x); },
        f64(v) { const x = Buffer.alloc(8); (le ? x.writeDoubleLE : x.writeDoubleBE).call(x, v, 0); chunks.push(x); },
        utf8(s) { const d = Buffer.from(s, 'utf-8'); if (d.length > 0x7fff) throw new Error('NBT: 字符串超长: ' + s); const x = Buffer.alloc(2 + d.length); (le ? x.writeInt16LE : x.writeInt16BE).call(x, d.length, 0); d.copy(x, 2); chunks.push(x); },
        raw(buf) { chunks.push(buf); },
    };
}

// ===== 编码 =====

/** 写 tag 的“值体”（不含类型号）。 */
function writeBody(w, rd, tag) {
    if (!tag || typeof tag !== 'object' || !Number.isInteger(tag.$t)) {
        throw new Error('NBT: 非法 tag: ' + JSON.stringify(tag));
    }
    const v = tag.v;
    switch (tag.$t) {
        case T_BYTE: w.u8(v & 0xff); break;
        case T_SHORT: w.i16(v); break;
        case T_INT: w.i32(v); break;
        case T_LONG: w.big64(v); break;
        case T_FLOAT: w.f32(v); break;
        case T_DOUBLE: w.f64(v); break;
        case T_STRING: w.utf8(v); break;
        case T_LIST: {
            w.u8(tag.elemType);
            w.i32(v.length);
            // NBT 规范（level.dat 实证）：元素只写值体，类型由列表头 elemType 声明
            for (const item of v) writeBody(w, rd, item);
            break;
        }
        case T_COMPOUND:
            writeCompoundBody(w, rd, v);
            break;
        default:
            throw new Error('NBT: 未实现的编码类型 ' + tag.$t);
    }
}

/** compound 值体：( [类型号][名长][名][值体] )* + END —— 类型号在前！ */
function writeCompoundBody(w, rd, obj) {
    for (const [name, value] of Object.entries(obj || {})) {
        w.u8(value.$t);
        w.utf8(name);
        writeBody(w, rd, value);
    }
    w.u8(T_END);
}

/**
 * 编码为完整 NBT 流。
 * @param {Object} rootObj 根 compound 内容 { 键: tag }
 * @param {string} endianness 'LE'（Minecraft，默认）| 'BE'（规范原文/部分工具）
 * @returns {Buffer}
 */
function encode(rootObj, endianness = 'LE') {
    const le = endianness !== 'BE';
    const w = makeWriter(le);
    w.u8(T_COMPOUND);
    w.i16(0); // 根名（模板根名为空）
    writeCompoundBody(w, null, rootObj);
    return Buffer.concat(w.chunks);
}

/** 编码 + gzip（可直接作为 .nbt 文件内容） */
function gzip(rootObj, endianness = 'LE') {
    return zlib.gzipSync(encode(rootObj, endianness));
}

// ===== 解码 =====

function readValueOf(rd, type) {
    switch (type) {
        case T_BYTE: { const v = rd.u8(); return v > 127 ? v - 256 : v; }
        case T_SHORT: return rd.i16();
        case T_INT: return rd.i32();
        case T_LONG: return rd.big64().toString();
        case T_FLOAT: return rd.f32();
        case T_DOUBLE: return rd.f64();
        case T_STRING: {
            const n = rd.i16();
            if (n < 0 || rd.o + n > rd.b.length) throw new Error('NBT: 字符串长度越界 ' + n);
            return rd.utf8(n);
        }
        case T_LIST: {
            const elemType = rd.u8();
            const count = rd.i32();
            if (count < 0 || count > 100000000) throw new Error('NBT: list 数量异常 ' + count);
            const arr = [];
            for (let i = 0; i < count; i++) arr.push(readValueOf(rd, elemType));
            return arr;
        }
        case T_COMPOUND:
            return readCompoundBody(rd);
        default:
            throw new Error('NBT: 未实现的解码类型 ' + type);
    }
}

/** 读 compound 值体：( [类型号][名长][名][值体] )* + 单字节 END —— 类型号在前！ */
function readCompoundBody(rd) {
    const obj = {};
    let guard = 0;
    for (;;) {
        if (++guard > 10000000) throw new Error('NBT: compound 条目过多（疑似布局/字节序错误）');
        const t = rd.u8();
        if (t === 0) break; // END
        const n = rd.i16();
        if (n < 0 || rd.o + n > rd.b.length) throw new Error('NBT: 名字长度越界 ' + n + '（疑似字节序错误）');
        const name = rd.utf8(n);
        obj[name] = readValueOf(rd, t);
    }
    return obj;
}

/**
 * 解码 NBT。输入 Buffer，自动识别 gzip 魔数。
 * @param {Buffer} input
 * @param {string} endianness 'LE'（默认）| 'BE'
 * @returns {Object} 根对象
 */
function decode(input, endianness = 'LE') {
    let buf = Buffer.isBuffer(input) ? input : Buffer.from(input);
    if (buf.length > 2 && buf[0] === 0x1f && buf[1] === 0x8b) buf = zlib.gunzipSync(buf);
    const le = endianness !== 'BE';
    const rd = makeReader(buf, le);
    const rootType = rd.u8();
    if (rootType !== T_COMPOUND) throw new Error('NBT: 根必须是 compound（得到 ' + rootType + '）');
    const rootNameLen = rd.i16();
    if (rootNameLen > 0) rd.utf8(rootNameLen); // 跳过根名（模板根名为空）
    return readCompoundBody(rd);
}

/** 根键名可打印比例（打分用） */
function printability(root) {
    const keys = Object.keys(root);
    if (!keys.length) return 0;
    let ok = 0;
    for (const k of keys) {
        let good = k.length > 0;
        for (let i = 0; i < k.length; i++) {
            const c = k.charCodeAt(i);
            if (c < 32 || c > 126) { good = false; break; }
        }
        if (good) ok++;
    }
    return ok / keys.length;
}

/**
 * 自动判别字节序并解码：LE 与 BE 各试一次，按（成功 + 根键可打印性）打分取优。
 * @returns {{root:Object, endianness:'LE'|'BE'}}
 */
function decodeAuto(input) {
    const results = [];
    for (const eo of ['LE', 'BE']) {
        try {
            const root = decode(input, eo);
            results.push({ root, endianness: eo, score: 1000 + printability(root) * 100 + Object.keys(root).length });
        } catch (e) {
            results.push({ error: e.message, endianness: eo, score: 0 });
        }
    }
    results.sort((a, b) => b.score - a.score);
    const best = results[0];
    if (!best.root) {
        throw new Error('NBT: 无法识别（LE: ' + (results.find(r => r.endianness === 'LE').error) + '；BE: ' + (results.find(r => r.endianness === 'BE').error) + '）');
    }
    return best;
}

module.exports = {
    // 构建器
    str, byte, int, long, flt, dbl, list,
    // 编解码
    encode, gzip, decode, decodeAuto,
    // 类型号（调用方/测试用）
    T: { END: T_END, BYTE: T_BYTE, SHORT: T_SHORT, INT: T_INT, LONG: T_LONG, FLOAT: T_FLOAT, DOUBLE: T_DOUBLE, STRING: T_STRING, LIST: T_LIST, COMPOUND: T_COMPOUND },
};
