// 生长/诊断测试的固定样例蓝图（大厅 + 塔 + 双向走廊链）。
// 原先直接读 contentpacks-source/structure-example-pack 的 blueprint.json；
// 那个示例包已删除，断言依赖的具体几何（main_hall 的 top@5,8,5、tower 6x12x6、
// 走廊 8x4x4 以 x=8 步进）必须原样保留，所以改成测试内自带。
'use strict';

const BLUEPRINT = {
    formatVersion: 1,
    structureId: 'example_structure',
    namespace: 'structure_example',
    structure: {
        step: 'surface_structures',
        terrainAdaptation: 'beard_thin',
        startPool: 'start',
        maxDistanceFromCenter: 80,
        biomes: ['#minecraft:is_overworld'],
        projectToSurface: true,
        maxSurfaceLevel: 0,
    },
    pieces: [
        {
            name: 'main_hall',
            size: [10, 8, 10],
            boxFills: [
                { from: [0, 0, 0], to: [9, 0, 9], block: 'minecraft:stone_bricks' },
                { from: [0, 1, 0], to: [9, 6, 0], block: 'minecraft:stone_bricks' },
                { from: [0, 1, 9], to: [9, 6, 9], block: 'minecraft:stone_bricks' },
                { from: [0, 1, 1], to: [0, 6, 4], block: 'minecraft:stone_bricks' },
                { from: [0, 1, 6], to: [0, 6, 8], block: 'minecraft:stone_bricks' },
                { from: [0, 2, 5], to: [0, 6, 5], block: 'minecraft:stone_bricks' },
                { from: [9, 1, 1], to: [9, 6, 4], block: 'minecraft:stone_bricks' },
                { from: [9, 1, 6], to: [9, 6, 8], block: 'minecraft:stone_bricks' },
                { from: [9, 2, 5], to: [9, 6, 5], block: 'minecraft:stone_bricks' },
                { from: [0, 7, 0], to: [9, 7, 4], block: 'minecraft:stone_bricks' },
                { from: [0, 7, 6], to: [9, 7, 9], block: 'minecraft:stone_bricks' },
            ],
            fillBlocks: [
                { x: 2, y: 2, z: 2, block: 'minecraft:polished_andesite' },
                { x: 7, y: 2, z: 7, block: 'minecraft:polished_andesite' },
                { x: 5, y: 1, z: 5, block: 'minecraft:lantern' },
            ],
            jigsaws: [
                { x: 5, y: 7, z: 5, name: 'top', pool: 'tower', target: 'bottom', joint: 'aligned', orientation: 'up_north', finalState: 'minecraft:stone_bricks', selectionPriority: 10, placementPriority: 10 },
                { x: 9, y: 1, z: 5, name: 'east', pool: 'corridor', target: 'entry_w', joint: 'aligned', orientation: 'east_up', finalState: 'minecraft:stone_bricks', selectionPriority: 10, placementPriority: 10 },
                { x: 0, y: 1, z: 5, name: 'west', pool: 'corridor', target: 'entry_e', joint: 'aligned', orientation: 'west_up', finalState: 'minecraft:stone_bricks', selectionPriority: 10, placementPriority: 10 },
            ],
        },
        {
            name: 'tower',
            size: [6, 12, 6],
            boxFills: [
                { from: [0, 0, 0], to: [2, 0, 5], block: 'minecraft:stone_bricks' },
                { from: [4, 0, 0], to: [5, 0, 5], block: 'minecraft:stone_bricks' },
                { from: [0, 1, 0], to: [5, 10, 0], block: 'minecraft:stone_bricks' },
                { from: [0, 1, 5], to: [5, 10, 5], block: 'minecraft:stone_bricks' },
                { from: [0, 1, 1], to: [0, 10, 4], block: 'minecraft:stone_bricks' },
                { from: [5, 1, 1], to: [5, 10, 4], block: 'minecraft:stone_bricks' },
                { from: [0, 11, 0], to: [5, 11, 5], block: 'minecraft:stone_bricks' },
            ],
            fillBlocks: [
                { x: 3, y: 0, z: 0, block: 'minecraft:stone_bricks' },
                { x: 3, y: 0, z: 1, block: 'minecraft:stone_bricks' },
                { x: 3, y: 0, z: 2, block: 'minecraft:stone_bricks' },
                { x: 3, y: 0, z: 4, block: 'minecraft:stone_bricks' },
                { x: 3, y: 0, z: 5, block: 'minecraft:stone_bricks' },
                { x: 3, y: 5, z: 3, block: 'minecraft:lantern' },
            ],
            jigsaws: [
                { x: 3, y: 0, z: 3, name: 'bottom', pool: 'main_hall', target: 'top', joint: 'aligned', orientation: 'down_south', finalState: 'minecraft:stone_bricks', selectionPriority: 10, placementPriority: 10 },
            ],
        },
        {
            name: 'corridor',
            size: [8, 4, 4],
            boxFills: [
                { from: [0, 0, 0], to: [7, 0, 3], block: 'minecraft:stone_bricks' },
                { from: [0, 1, 0], to: [7, 2, 0], block: 'minecraft:stone_bricks' },
                { from: [0, 1, 3], to: [7, 2, 3], block: 'minecraft:stone_bricks' },
                { from: [0, 3, 0], to: [7, 3, 3], block: 'minecraft:stone_bricks' },
                { from: [0, 1, 2], to: [0, 2, 3], block: 'minecraft:stone_bricks' },
                { from: [7, 1, 2], to: [7, 2, 3], block: 'minecraft:stone_bricks' },
            ],
            fillBlocks: [
                { x: 0, y: 2, z: 0, block: 'minecraft:stone_bricks' },
                { x: 7, y: 2, z: 0, block: 'minecraft:stone_bricks' },
                { x: 4, y: 1, z: 2, block: 'minecraft:lantern' },
            ],
            jigsaws: [
                { x: 0, y: 1, z: 1, name: 'entry_w', pool: 'corridor', target: 'entry_e', joint: 'aligned', orientation: 'west_up', finalState: 'minecraft:stone_bricks', selectionPriority: 10, placementPriority: 10 },
                { x: 7, y: 1, z: 1, name: 'entry_e', pool: 'corridor', target: 'entry_w', joint: 'aligned', orientation: 'east_up', finalState: 'minecraft:stone_bricks', selectionPriority: 10, placementPriority: 10 },
            ],
        },
    ],
    templatePools: [
        { name: 'start', elements: [{ template: 'main_hall', weight: 1, projection: 'rigid' }], fallback: 'minecraft:empty' },
        { name: 'main_hall', elements: [{ template: 'main_hall', weight: 1, projection: 'rigid' }], fallback: 'minecraft:empty' },
        { name: 'tower', elements: [{ template: 'tower', weight: 1, projection: 'rigid' }], fallback: 'minecraft:empty' },
        { name: 'corridor', elements: [{ template: 'corridor', weight: 1, projection: 'rigid' }], fallback: 'minecraft:empty' },
    ],
    structureSet: {
        spacing: 32,
        separation: 12,
        salt: 87654321,
        frequency: 1.0,
        exclusionZone: null,
    },
};

/** 每次返回独立副本，测试可随意改写 */
function sampleBlueprint() {
    return JSON.parse(JSON.stringify(BLUEPRINT));
}

module.exports = { sampleBlueprint };
