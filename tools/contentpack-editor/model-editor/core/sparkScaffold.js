// sparkScaffold.js - 新建模型脚手架（W3.1）：路径约定解析 + 动画目录 + 契约文件 + 人形模板
// 纯函数（零 fs、零 DOM）：node 可测 + 浏览器经典 script（self.SparkScaffold）。
//
// 约定（与现网 pack 布局一致，design/CONTENTPACK_FORMAT.md）：
//   <pack>/spark_models/<type>/<ns>/<name>.json          模型
//   <pack>/spark_animations/<type>/<ns>/<name>/          动画目录（一模型一目录）
//   <pack>/assets/<ns>/textures/<type>/<name>.png        贴图（建议路径）
//   <pack>/editor/animation-contract.json                契约（每包一份；缺省时由新建流程生成最小契约）
// 契约内容参照 contentpacks-source/modular-zombie-3/editor/animation-contract.json（首份实装）。
//
// 双模式：node（require/module.exports）+ 浏览器经典 script（self.SparkScaffold）
(function (globalScope) {
    const isNode = typeof module === 'object' && module.exports;
    const Model = isNode ? require('./model') : globalScope.Model;

    // ==================== 标准 8 状态（house 约定，zombie-3 实装抄录） ====================
    const STANDARD_STATES = [
        { name: 'still', loop: 'true' },
        { name: 'walking', loop: 'true' },
        { name: 'running', loop: 'true' },
        { name: 'attack', loop: 'hold_on_last_frame' },
        { name: 'alert', loop: 'true' },
        { name: 'hit_front', loop: 'hold_on_last_frame' },
        { name: 'hit_back', loop: 'hold_on_last_frame' },
        { name: 'aggressive', loop: 'true' },
    ];

    // ==================== 标识符（ns:name，RL 规则与 Java/Minecraft 一致） ====================
    const RL_NAMESPACE_RE = /^[a-z0-9_.\-]+$/;
    const RL_NAME_RE = /^[a-z0-9_.\-]+$/; // 实体/模型名不做文件名+RL 双用，但统一收紧（禁 '/'）

    /**
     * 解析 "ns:name"。ns 缺省 → null（调用方决定默认 ns）。
     * @returns {{ok: boolean, ns: string|null, name: string, error?: string}}
     */
    function parseIdentifier(ident) {
        if (typeof ident !== 'string' || !ident.trim()) return { ok: false, ns: null, name: '', error: 'identifier 为空' };
        ident = ident.trim();
        const sep = ident.indexOf(':');
        let ns = null, name;
        if (sep >= 0) {
            ns = ident.slice(0, sep);
            name = ident.slice(sep + 1);
            if (!RL_NAMESPACE_RE.test(ns)) return { ok: false, ns, name, error: `namespace 非法: "${ns}"（小写字母/数字/._-）` };
        } else {
            name = ident;
        }
        if (!RL_NAME_RE.test(name)) return { ok: false, ns, name, error: `模型名非法: "${name}"（小写字母/数字/._-）` };
        return { ok: true, ns, name };
    }

    // ==================== 模型文件路径约定 ====================

    /**
     * 解析 spark 模型文件绝对路径 → {type, ns, name, packRel}。
     * 约定：<...>/<pack>/spark_models/<type>/<ns>/<name>.json
     * @param {string} modelFile 绝对路径（/ 或 \\ 分隔均可）
     * @returns {{ok: boolean, error?: string, type?: string, ns?: string, name?: string, packRel?: string, packName?: string}}
     *   packRel = pack 目录相对路径（约定要求恰好 1 层 = pack 名；更深的目录也接受但 warning 由调用方判）
     */
    function parseModelPath(modelFile) {
        if (typeof modelFile !== 'string' || !modelFile.trim()) return { ok: false, error: '路径为空' };
        const parts = modelFile.split(/[\\/]+/).filter((p) => p && p !== '.' );
        const i = parts.lastIndexOf('spark_models');
        if (i < 0) return { ok: false, error: '路径不含 spark_models 段（约定: <pack>/spark_models/<type>/<ns>/<name>.json）' };
        const type = parts[i + 1];
        const ns = parts[i + 2];
        const fileBase = parts[i + 3];
        if (!type || !ns || !fileBase) return { ok: false, error: 'spark_models 后需 <type>/<ns>/<name>.json 三段' };
        if (type !== 'entity' && type !== 'item') return { ok: false, error: `type 须为 entity/item: "${type}"` };
        if (!fileBase.toLowerCase().endsWith('.json')) return { ok: false, error: `模型文件须为 .json: "${fileBase}"` };
        const name = fileBase.slice(0, -'.json'.length);
        if (!RL_NAME_RE.test(name)) return { ok: false, error: `模型名非法: "${name}"` };
        // pack 段 = spark_models 之前的所有段（约定 1 层）
        const packRel = parts.slice(0, i).join('/');
        return { ok: true, type, ns, name, packRel, packName: packRel.split('/').pop() };
    }

    /**
     * 由模型文件路径推导全套脚手架（纯计算，不碰 fs）。
     * @param {string} modelFile 模型 JSON 绝对路径
     * @param {{contractExists?: boolean, label?: string}} [opts]
     * @returns {{ok: true, data: {type,ns,name,packRel,packName,modelFile,animDir,texturePath,contract:{path,create,content}}}
     *          | {ok: false, error: string}}
     */
    function scaffoldPlan(modelFile, opts = {}) {
        const p = parseModelPath(modelFile);
        if (!p.ok) return { ok: false, error: p.error };
        const sep = modelFile.includes('\\') ? '\\' : '/';
        const toSep = (s) => s.split('/').join(sep);
        const animDir = [toSep(p.packRel), 'spark_animations', p.type, p.ns, p.name].join(sep) + sep;
        const texturePath = [toSep(p.packRel), 'assets', p.ns, 'textures', p.type, p.name + '.png'].join(sep);
        const contractPath = [toSep(p.packRel), 'editor', 'animation-contract.json'].join(sep);
        const contract = opts.contractExists
            ? { path: contractPath, create: false, content: null }
            : { path: contractPath, create: true, content: renderContract({ ns: p.ns, name: p.name, type: p.type, label: opts.label || p.name, texture: opts.texture }) };
        return {
            ok: true,
            data: {
                type: p.type, ns: p.ns, name: p.name,
                packRel: p.packRel, packName: p.packName,
                modelFile, animDir, texturePath, contract,
            },
        };
    }

    /**
     * 渲染最小契约（新建模型用；pack 已有契约时绝不覆盖 —— 调用方按 contractExists 判断）。
     * 相对路径均以 pack 根为基准（与 zombie-3 实装一致）。
     * texture = 指定贴图（W3.5-② 应用贴图落盘）；缺省 = 约定路径。
     * wiring 缺省（状态机是 pack 的 Java 侧知识，脚手架不猜测）。
     */
    function renderContract({ ns, name, type, label, texture }) {
        const obj = {
            contract_version: 1,
            content_type: 'spark_animation_set',
            sets: [
                {
                    id: name,
                    label: label || name,
                    model: {
                        path: `spark_models/${type}/${ns}/${name}.json`,
                        texture: texture || `assets/${ns}/textures/${type}/${name}.png`,
                    },
                    animations: {
                        dir: `spark_animations/${type}/${ns}/${name}/`,
                        required: STANDARD_STATES.map((s) => ({ name: s.name, loop: s.loop })),
                    },
                },
            ],
            validation: {
                bones_must_exist_in_model: true,
                max_duration: 6.0,
            },
        };
        return JSON.stringify(obj, null, 2) + '\n';
    }

    /**
     * 契约内为模型设置贴图（W3.5-②：仓库贴图 → 应用 → 持久化）。
     * 纯函数，就地修改 contractObj 并返回 {ok, data:{changed, created, set}}。
     * - 找到 model.path === modelRel 的 set：写 model.texture（model 为字符串形时升级为 {path,texture}）
     * - 找不到：新增 set（标准 8 状态，与 renderContract 同构）
     * @param {object} contractObj 已解析契约（须含 sets[]）
     * @param {string} modelRel 包内相对路径（slash 形，如 spark_models/entity/<ns>/<name>.json）
     * @param {string} textureRel 包内相对路径（如 assets/<ns>/textures/entity/<name>.png）
     */
    function setContractTexture(contractObj, modelRel, textureRel) {
        if (!contractObj || !Array.isArray(contractObj.sets)) return { ok: false, error: '契约缺少 sets[]' };
        if (typeof modelRel !== 'string' || typeof textureRel !== 'string') return { ok: false, error: '参数无效' };
        const norm = (s) => s.replace(/\\/g, '/');
        const target = norm(modelRel);
        for (const set of contractObj.sets) {
            const p = typeof set.model === 'string' ? set.model : (set.model && set.model.path);
            if (p && norm(p) === target) {
                if (typeof set.model === 'string') set.model = { path: set.model, texture: textureRel };
                else set.model.texture = textureRel;
                return { ok: true, data: { changed: true, created: false, set } };
            }
        }
        // 未找到 → 新增 set（从相对路径推 type/ns/name）
        const segs = target.split('/');
        const i = segs.indexOf('spark_models');
        if (i < 0 || segs.length < i + 4) return { ok: false, error: '模型路径不符合约定: ' + modelRel };
        const [type, ns, nameFile] = [segs[i + 1], segs[i + 2], segs[i + 3]];
        const name = nameFile.replace(/\.json$/i, '');
        const set = {
            id: name,
            label: name,
            model: { path: target, texture: textureRel },
            animations: {
                dir: `spark_animations/${type}/${ns}/${name}/`,
                required: STANDARD_STATES.map((s) => ({ name: s.name, loop: s.loop })),
            },
        };
        contractObj.sets.push(set);
        return { ok: true, data: { changed: false, created: true, set } };
    }

    // ==================== 标准人形模板（zombie-3 骨架抄录：7 骨 + hitbox locators，无 cube） ====================
    /**
     * 建"标准人形"模板骨骼（pivot/locator 与现网 zombie 一致；无 cube —— 建模者自己摆方块）。
     * @returns {Array} createBone() 数组（可直接进 createGeometry.bones）
     */
    function buildHumanoidTemplate() {
        const B = (o) => Model.createBone(o);
        return [
            B({ name: 'body', pivot: [0, 12, 0], locators: [Model.createLocator({ name: 'hitbox_body', offset: [0, 18, 0] })] }),
            B({ name: 'left_arm', parent: 'body', pivot: [5, 22, 0], mirror: true, locators: [Model.createLocator({ name: 'hitbox_left_arm', offset: [6, 18, 0] })] }),
            B({ name: 'right_arm', parent: 'body', pivot: [-5, 22, 0], locators: [Model.createLocator({ name: 'hitbox_right_arm', offset: [-6, 18, 0] })] }),
            B({ name: 'left_leg', parent: 'body', pivot: [1.9, 12, 0], mirror: true, locators: [Model.createLocator({ name: 'hitbox_left_leg', offset: [2, 6, 0] })] }),
            B({ name: 'right_leg', parent: 'body', pivot: [-1.9, 12, 0], locators: [Model.createLocator({ name: 'hitbox_right_leg', offset: [-2, 6, 0] })] }),
            B({ name: 'head', parent: 'body', pivot: [0, 24, 0], locators: [Model.createLocator({ name: 'hitbox_head', offset: [0, 28, 0] })] }),
            B({ name: 'headwear', parent: 'head', pivot: [0, 24, 0] }),
        ];
    }

    const SparkScaffoldAPI = {
        STANDARD_STATES,
        parseIdentifier,
        parseModelPath,
        scaffoldPlan,
        renderContract,
        setContractTexture,
        buildHumanoidTemplate,
    };
    if (isNode) module.exports = SparkScaffoldAPI;
    if (globalScope) globalScope.SparkScaffold = SparkScaffoldAPI;
})(typeof self !== 'undefined' ? self : (typeof globalThis !== 'undefined' ? globalThis : null));
