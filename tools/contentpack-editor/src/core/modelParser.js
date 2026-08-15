// modelParser.js - Spark 模型（Bedrock JSON）骨骼树解析
// 格式：spark_models/entity/<ns>/<name>.json
// 骨骼层级通过 "parent" 字段表达（非嵌套），需按 parent 构建树
const fs = require('fs');
const path = require('path');

/**
 * 解析 spark 模型 JSON 文本，返回骨骼树
 * @param {string} jsonText
 * @returns {{
 *   identifier: string,
 *   bones: Array<{name:string,parent:string|null,pivot:number[],cubes:number[],locators:Object}>,
 *   tree: Array<{name:string,parent:string|null,children:Array,locators:Object,data:Object}>,
 *   boneMap: Object<string,Object>
 * }}
 */
function parseModel(jsonText) {
    const root = JSON.parse(jsonText);
    const geometryList = root['minecraft:geometry'];
    if (!geometryList || !geometryList.length) {
        throw new Error('无效的 spark 模型：缺少 minecraft:geometry');
    }
    const geometry = geometryList[0];
    const description = geometry.description || {};
    const rawBones = geometry.bones || [];

    const bones = rawBones.map((b) => ({
        name: b.name,
        parent: b.parent || null,
        pivot: b.pivot || [0, 0, 0],
        cubes: (b.cubes || []).map((c) => ({
            origin: c.origin || [0, 0, 0],
            size: c.size || [0, 0, 0],
            pivot: c.pivot || [0, 0, 0],
            uv: c.uv || [0, 0],
        })),
        locators: b.locators || {},
    }));

    // 按 parent 构建树
    const boneMap = {};
    for (const b of bones) boneMap[b.name] = b;

    const tree = [];
    const nodeOf = (name) => ({
        name,
        parent: boneMap[name].parent,
        children: [],
        locators: boneMap[name].locators || {},
        data: boneMap[name],
    });
    const nodes = {};
    for (const b of bones) nodes[b.name] = nodeOf(b.name);

    for (const b of bones) {
        if (b.parent && nodes[b.parent]) {
            nodes[b.parent].children.push(nodes[b.name]);
        } else {
            tree.push(nodes[b.name]);
        }
    }

    return {
        identifier: description.identifier || '',
        textureWidth: description.texture_width || 64,
        textureHeight: description.texture_height || 64,
        bones,
        tree,
        boneMap,
        raw: root,
    };
}

/**
 * 从文件解析模型
 * @param {string} filePath
 */
function parseModelFile(filePath) {
    const jsonText = fs.readFileSync(filePath, 'utf-8');
    const result = parseModel(jsonText);
    result.filePath = filePath;
    result.fileName = path.basename(filePath);
    return result;
}

// ---------- jar 内模型提取 ----------
// 使用 Node 内置能力读取 jar（zip 格式）：
// Node 无内置 zip 解压，这里通过 child_process 调 jar 命令（JDK 自带，项目构建依赖 JDK）

/**
 * 列出 jar 内 spark_models 下的模型文件
 * @param {string} jarPath
 * @returns {Promise<string[]>} jar 内路径列表（如 spark_models/entity/ns/name.json）
 */
async function listModelsInJar(jarPath) {
    const { execFileSync } = require('child_process');
    const stdout = execFileSync('jar', ['tf', jarPath], { encoding: 'utf-8' });
    return stdout
        .split(/\r?\n/)
        .map((l) => l.trim())
        .filter((l) => l.startsWith('spark_models/') && l.endsWith('.json'));
}

/**
 * 从 jar 提取并解析 spark 模型
 * @param {string} jarPath
 * @param {string} modelPathInJar 如 spark_models/entity/zombie3pack/modular_zombie_3.json
 * @returns {Promise<Object>}
 */
async function parseModelFromJar(jarPath, modelPathInJar) {
    const { execFileSync } = require('child_process');
    const os = require('os');
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cpe-model-'));
    const tmpFile = path.join(tmpDir, path.basename(modelPathInJar));
    try {
        execFileSync('jar', ['xf', jarPath, modelPathInJar], { cwd: tmpDir, stdio: 'pipe' });
        const jsonText = fs.readFileSync(tmpFile, 'utf-8');
        const result = parseModel(jsonText);
        result.filePath = modelPathInJar;
        result.fromJar = jarPath;
        return result;
    } finally {
        try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch {}
    }
}

/**
 * 获取骨骼的扁平名称列表（用于 dropdown/校验）
 * @param {Object} model parseModel 的返回
 * @returns {string[]}
 */
function getBoneNames(model) {
    return model.bones.map((b) => b.name);
}

module.exports = {
    parseModel,
    parseModelFile,
    parseModelFromJar,
    listModelsInJar,
    getBoneNames,
};
