// healthConfig.js - bodypart 部位配置生成/解析
// 格式兼容主 mod EntityBoneHealthConfig（src/core/combat/EntityBoneHealthConfig.java）
// 输出：assets/<namespace>/health_config/<entityId>.json
// 注意：实体 JSON 与 health_config 是分离的两个文件
const fs = require('fs');
const path = require('path');

/**
 * 创建默认部位配置（新实体的初始 bodypart 结构）
 * @param {string[]} boneNames 模型骨骼名列表（用于生成示例分组）
 * @returns {{parts: Array}}
 */
function createDefaultHealthConfig(boneNames = []) {
    // 按常见人形生物默认分组：head/body/arm/leg，其余骨骼并入 body
    const isArm = (n) => n.includes('arm');
    const isLeg = (n) => n.includes('leg');
    const isHead = (n) => n.includes('head');
    const defaultGroups = ['head', 'body', 'arm', 'leg'];
    const parts = defaultGroups.map((partName) => ({
        partName,
        maxModuleHealth: 10.0,
        isFatal: partName === 'head',
        transmissionToBase: 1.0,
        units: [],
    }));
    // 把骨骼分配到默认分组
    for (const boneName of boneNames) {
        let partName = 'body';
        if (isHead(boneName)) partName = 'head';
        else if (isArm(boneName)) partName = 'arm';
        else if (isLeg(boneName)) partName = 'leg';
        const part = parts.find((p) => p.partName === partName);
        part.units.push(createDefaultUnit(boneName));
    }
    return { parts };
}

/**
 * 创建默认骨骼单元
 * @param {string} boneName
 */
function createDefaultUnit(boneName) {
    return {
        boneName,
        transmissionCoeff: 1.0,
        armorValue: 0.0,
        antiPiercing: 0.0,
        collisionTag: boneName,
        specialEffects: [],
        hitSound: '',
    };
}

/**
 * 校验部位配置
 * @param {Object} config
 * @returns {{valid:boolean, errors:string[]}}
 */
function validateHealthConfig(config) {
    const errors = [];
    if (!config || !Array.isArray(config.parts)) {
        return { valid: false, errors: ['部位配置缺少 parts 数组'] };
    }
    const seenPart = new Set();
    const seenBone = new Set();
    for (const part of config.parts) {
        if (!part.partName) {
            errors.push('存在未命名部位（partName 必填）');
            continue;
        }
        if (seenPart.has(part.partName)) {
            errors.push(`部位名重复: ${part.partName}`);
        }
        seenPart.add(part.partName);
        if (!(part.maxModuleHealth > 0)) errors.push(`部位 ${part.partName}: maxModuleHealth 必须 > 0`);
        if (!Array.isArray(part.units)) {
            errors.push(`部位 ${part.partName}: units 必须为数组`);
            continue;
        }
        for (const unit of part.units) {
            if (!unit.boneName) {
                errors.push(`部位 ${part.partName}: 存在未命名骨骼单元`);
                continue;
            }
            if (seenBone.has(unit.boneName)) {
                errors.push(`骨骼 ${unit.boneName} 被重复分配到多个部位`);
            }
            seenBone.add(unit.boneName);
            if (!(unit.transmissionCoeff >= 0)) errors.push(`骨骼 ${unit.boneName}: transmissionCoeff 不能为负`);
            if (!(unit.armorValue >= 0)) errors.push(`骨骼 ${unit.boneName}: armorValue 不能为负`);
            if (!(unit.antiPiercing >= 0)) errors.push(`骨骼 ${unit.boneName}: antiPiercing 不能为负`);
        }
    }
    return { valid: errors.length === 0, errors };
}

// REVIEW-S2：ns/id 字符集（与 entitySchema 的 id/namespace 规则一致）。
// 此前仅真值判断 `if (!namespace)`，AI API 的 health.set/health.get 可传
// ns='../../..' 直接写出/读出包目录之外的任意 .json。
const HEALTH_NAME_RE = /^[a-z0-9_.-]+$/;

/**
 * 部位配置文件路径（包内）
 * @param {string} packDir
 * @param {string} namespace
 * @param {string} entityId
 */
function healthConfigFilePath(packDir, namespace, entityId) {
    if (!namespace || !HEALTH_NAME_RE.test(String(namespace))) {
        throw new Error(`namespace 非法（仅允许小写字母/数字/_/./-）: ${namespace}`);
    }
    if (!entityId || !HEALTH_NAME_RE.test(String(entityId))) {
        throw new Error(`实体 id 非法（仅允许小写字母/数字/_/./-）: ${entityId}`);
    }
    const file = path.join(packDir, 'assets', namespace, 'health_config', `${entityId}.json`);
    // 纵深防御：join 后边界校验（字符集已挡住分隔符，此处防未来改动）
    if (!file.startsWith(path.resolve(packDir) + path.sep)) {
        throw new Error('部位配置路径越界');
    }
    return file;
}

/**
 * 读取部位配置
 * @param {string} packDir
 * @param {string} namespace
 * @param {string} entityId
 * @returns {Object|null} 不存在返回 null
 */
function readHealthConfig(packDir, namespace, entityId) {
    const file = healthConfigFilePath(packDir, namespace, entityId);
    if (!fs.existsSync(file)) return null;
    return JSON.parse(fs.readFileSync(file, 'utf-8'));
}

/**
 * 保存部位配置
 * @param {string} packDir
 * @param {string} namespace
 * @param {string} entityId
 * @param {Object} config
 */
function saveHealthConfig(packDir, namespace, entityId, config) {
    if (!namespace) throw new Error('namespace 必填（实体注册命名空间）');
    const { valid, errors } = validateHealthConfig(config);
    if (!valid) {
        throw new Error(`部位配置校验失败:\n${errors.join('\n')}`);
    }
    const file = healthConfigFilePath(packDir, namespace, entityId);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, JSON.stringify(config, null, 2), 'utf-8');
    return { file: path.relative(packDir, file) };
}

/**
 * 删除部位配置
 * @param {string} packDir
 * @param {string} namespace
 * @param {string} entityId
 */
function deleteHealthConfig(packDir, namespace, entityId) {
    const file = healthConfigFilePath(packDir, namespace, entityId);
    if (fs.existsSync(file)) {
        fs.unlinkSync(file);
        return { deleted: true };
    }
    return { deleted: false };
}

module.exports = {
    createDefaultHealthConfig,
    createDefaultUnit,
    validateHealthConfig,
    healthConfigFilePath,
    readHealthConfig,
    saveHealthConfig,
    deleteHealthConfig,
};
