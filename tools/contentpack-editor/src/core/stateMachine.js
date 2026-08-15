// stateMachine.js - 动画状态机模板
// 状态 ID 固定（主 mod AnimatedMob 常量）：
//   0=IDLE 1=WALKING 2=RUNNING 3=ATTACK 4=ALERT 5=HIT_FRONT 6=HIT_BACK 7=WINDING
// 判定逻辑在主 mod 写死（determineAnimationState），作者只需填动画名映射。
// 模板决定：默认动画名 + 状态说明（UI 展示）+ 可选额外状态。
const TEMPLATES = {
    melee: {
        id: 'melee',
        name: '近战（Melee）',
        description: '标准近战生物：待机/行走/奔跑/攻击/警觉/受击。主 mod 默认逻辑。',
        states: [
            { id: 0, key: '0', name: '待机', anim: 'still' },
            { id: 1, key: '1', name: '行走', anim: 'walking' },
            { id: 2, key: '2', name: '奔跑', anim: 'running' },
            { id: 3, key: '3', name: '攻击', anim: 'attack' },
            { id: 4, key: '4', name: '警觉', anim: 'alert' },
            { id: 5, key: '5', name: '正面受击', anim: 'hit_front' },
            { id: 6, key: '6', name: '背面受击', anim: 'hit_back' },
            { id: 7, key: '7', name: '蓄力', anim: 'aggressive' },
        ],
    },
    ranged: {
        id: 'ranged',
        name: '远程（Ranged）',
        description: '远程生物：攻击状态用射击动画。状态集与近战一致（主 mod 尚未区分，为编辑器预留）。',
        states: [
            { id: 0, key: '0', name: '待机', anim: 'still' },
            { id: 1, key: '1', name: '行走', anim: 'walking' },
            { id: 2, key: '2', name: '奔跑', anim: 'running' },
            { id: 3, key: '3', name: '射击', anim: 'shoot' },
            { id: 4, key: '4', name: '警觉', anim: 'alert' },
            { id: 5, key: '5', name: '正面受击', anim: 'hit_front' },
            { id: 6, key: '6', name: '背面受击', anim: 'hit_back' },
            { id: 7, key: '7', name: '瞄准', anim: 'aim' },
        ],
    },
    boss: {
        id: 'boss',
        name: 'Boss（预留）',
        description: 'Boss 生物：预留模板，状态集同上，默认动画名待定。',
        states: [
            { id: 0, key: '0', name: '待机', anim: 'still' },
            { id: 1, key: '1', name: '行走', anim: 'walking' },
            { id: 2, key: '2', name: '冲锋', anim: 'running' },
            { id: 3, key: '3', name: '攻击', anim: 'attack' },
            { id: 4, key: '4', name: '警觉', anim: 'alert' },
            { id: 5, key: '5', name: '正面受击', anim: 'hit_front' },
            { id: 6, key: '6', name: '背面受击', anim: 'hit_back' },
            { id: 7, key: '7', name: '蓄力', anim: 'aggressive' },
        ],
    },
};

/**
 * 获取模板列表（供 UI 下拉）
 * @returns {Array<{id:string,name:string,description:string}>}
 */
function getTemplateList() {
    return Object.values(TEMPLATES).map((t) => ({
        id: t.id,
        name: t.name,
        description: t.description,
    }));
}

/**
 * 根据模板生成默认 animations 映射
 * @param {string} templateId
 * @returns {Object<string,string>}
 */
function getDefaultAnimations(templateId) {
    const t = TEMPLATES[templateId] || TEMPLATES.melee;
    const result = {};
    for (const s of t.states) {
        result[s.key] = s.anim;
    }
    return result;
}

module.exports = {
    TEMPLATES,
    getTemplateList,
    getDefaultAnimations,
};
