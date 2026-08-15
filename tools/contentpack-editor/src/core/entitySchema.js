// entitySchema.js - 实体定义 JSON 读写与校验
// 格式兼容主 mod MobDefinitionParser.parse()（src/core/mob/MobDefinitionParser.java）
// 输出：entities/<id>.json
const fs = require('fs');
const path = require('path');

const ENTITY_DIR = 'entities';

/**
 * 创建默认实体对象（新实体表单初始值）
 * @returns {Object}
 */
function createDefaultEntity() {
    return {
        id: 'example_mob',
        namespace: '',
        displayName: '',
        size: { width: 0.6, height: 1.95 },
        attributes: {
            maxHealth: 20.0,
            attackDamage: 3.0,
            movementSpeed: 0.23,
            followRange: 16.0,
            armor: 0.0,
        },
        model: { type: 'entity', id: '' },
        texture: '',
        sounds: {
            ambient: 'minecraft:entity.zombie.ambient',
            hurt: 'minecraft:entity.zombie.hurt',
            death: 'minecraft:entity.zombie.death',
            step: 'minecraft:entity.zombie.step',
            swing: '',
        },
        animations: {
            0: 'still',
            1: 'walking',
            2: 'running',
            3: 'attack',
            4: 'alert',
            5: 'hit_front',
            6: 'hit_back',
            7: 'aggressive',
        },
        behaviors: [
            { type: 'attack', damage: 3.0, cooldown: 20 },
            { type: 'ai', goals: ['hunt_player', 'random_stroll', 'look_at_player', 'look_around', 'hurt_by_target'] },
        ],
    };
}

/**
 * 校验实体定义（与主 mod 解析器要求一致）
 * @param {Object} entity
 * @returns {{valid:boolean, errors:string[]}}
 */
function validateEntity(entity) {
    const errors = [];
    if (!entity || typeof entity !== 'object') {
        return { valid: false, errors: ['实体定义不能为空'] };
    }
    if (!entity.id || typeof entity.id !== 'string' || !entity.id.trim()) {
        errors.push('id 必填（实体注册名）');
    } else if (!/^[a-z0-9_.-]+$/.test(entity.id)) {
        errors.push('id 只能包含小写字母/数字/_/./-');
    }
    if (entity.namespace && !/^[a-z0-9_.-]+$/.test(entity.namespace)) {
        errors.push('namespace 只能包含小写字母/数字/_/./-');
    }
    if (entity.size) {
        if (!(entity.size.width > 0)) errors.push('size.width 必须 > 0');
        if (!(entity.size.height > 0)) errors.push('size.height 必须 > 0');
    }
    if (entity.attributes) {
        if (entity.attributes.maxHealth != null && entity.attributes.maxHealth <= 0) {
            errors.push('attributes.maxHealth 必须 > 0');
        }
        for (const k of ['attackDamage', 'movementSpeed', 'followRange', 'armor']) {
            if (entity.attributes[k] != null && entity.attributes[k] < 0) {
                errors.push(`attributes.${k} 不能为负`);
            }
        }
    }
    if (entity.model) {
        // model.id 允许为空：主 mod MobDefinitionParser 缺省时按 "ns:id" 自动推断
        if (entity.model.id && !/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(entity.model.id)) {
            errors.push('model.id 格式应为 "命名空间:路径"（如 ns:modular_zombie_3）');
        }
    }
    if (entity.animations) {
        for (const [state, anim] of Object.entries(entity.animations)) {
            if (!anim) errors.push(`动画状态 ${state} 缺少动画名`);
        }
    }
    return { valid: errors.length === 0, errors };
}

/**
 * 实体文件路径（包内）
 * @param {string} packDir
 * @param {string} id
 */
function entityFilePath(packDir, id) {
    return path.join(packDir, ENTITY_DIR, `${id}.json`);
}

/**
 * 列出包内所有实体
 * @param {string} packDir
 * @returns {Array<{id:string, namespace:string, displayName:string, file:string, hasHealthConfig:boolean}>}
 */
function listEntities(packDir) {
    const dir = path.join(packDir, ENTITY_DIR);
    if (!fs.existsSync(dir)) return [];
    const result = [];
    for (const file of fs.readdirSync(dir)) {
        if (!file.endsWith('.json')) continue;
        const id = file.slice(0, -5);
        try {
            const entity = readEntity(packDir, id);
            result.push({
                id,
                namespace: entity.namespace || '',
                displayName: entity.displayName || entity.id,
                file: path.join(ENTITY_DIR, file),
            });
        } catch {
            // 跳过无法解析的实体文件
        }
    }
    return result;
}

/**
 * 读取单个实体
 * @param {string} packDir
 * @param {string} id
 * @returns {Object}
 */
function readEntity(packDir, id) {
    const file = entityFilePath(packDir, id);
    if (!fs.existsSync(file)) {
        throw new Error(`实体不存在: ${id} (${file})`);
    }
    return JSON.parse(fs.readFileSync(file, 'utf-8'));
}

/**
 * 保存实体（校验通过后写入，返回保存后的实体）
 * @param {string} packDir
 * @param {Object} entity
 * @returns {{entity:Object, file:string, warnings:string[]}}
 */
function saveEntity(packDir, entity) {
    const { valid, errors } = validateEntity(entity);
    if (!valid) {
        throw new Error(`实体校验失败:\n${errors.join('\n')}`);
    }
    const id = entity.id;
    // 清理空字段（避免无意义键污染输出）
    const clean = JSON.parse(JSON.stringify(entity));
    if (clean.namespace === '') delete clean.namespace;
    if (clean.displayName === '' || clean.displayName === id) delete clean.displayName;
    if (clean.texture === '') delete clean.texture;
    if (clean.sounds) {
        for (const k of ['ambient', 'hurt', 'death', 'step', 'swing']) {
            if (!clean.sounds[k]) delete clean.sounds[k];
        }
        if (Object.keys(clean.sounds).length === 0) delete clean.sounds;
    }
    const dir = path.join(packDir, ENTITY_DIR);
    fs.mkdirSync(dir, { recursive: true });
    const file = entityFilePath(packDir, id);
    fs.writeFileSync(file, JSON.stringify(clean, null, 2), 'utf-8');
    return { entity: clean, file: path.join(ENTITY_DIR, `${id}.json`), warnings: [] };
}

/**
 * 删除实体
 * @param {string} packDir
 * @param {string} id
 */
function deleteEntity(packDir, id) {
    const file = entityFilePath(packDir, id);
    if (fs.existsSync(file)) {
        fs.unlinkSync(file);
        return { deleted: id };
    }
    return { deleted: null };
}

module.exports = {
    createDefaultEntity,
    validateEntity,
    entityFilePath,
    listEntities,
    readEntity,
    saveEntity,
    deleteEntity,
};
