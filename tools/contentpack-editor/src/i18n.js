// i18n.js - 中英文切换（渲染进程，挂 window.I18n，禁用 module.exports）
// 用法：
//   window.I18n.t('key')           → 当前语言文本（无则回退 en）
//   window.I18n.setLang('zh'|'en') → 切换并持久化 + 重新应用界面文案
//   window.I18n.applyDOM()         → 把 [data-i18n]/[data-i18n-ph]/[data-i18n-title] 替换为当前语言
// 必须在 views/app.js 之前加载（index.html script 顺序第一位）
(function () {
    const DICT = {
        zh: {
            // 顶栏
            'app.title': 'ContentPack Editor',
            'app.lang': '语言',
            // 左栏
            'pack.title': '内容包',
            'pack.new': '+ 新建',
            'pack.collapse': '收起',
            'pack.expand': '展开内容包列表',
            'pack.namePh': '包名 (Module-Name)',
            'pack.versionPh': '版本 (默认 0.1.0)',
            'pack.requiresPh': 'Module-Requires',
            'pack.depsPh': 'Module-Dependencies (逗号分隔)',
            'pack.modReqPh': 'Module-Mod-Requires (逗号分隔)',
            'pack.create': '创建',
            'pack.cancel': '取消',
            // 中栏
            'entity.title': '实体',
            'entity.new': '+ 新建',
            // 编辑器
            'editor.empty': '请选择或新建一个内容包，然后选择/新建实体。',
            'editor.export': '导出',
            'editor.saveDraft': '保存草稿',
            'editor.build': '打包',
            'editor.tab.basic': '基本信息',
            'editor.tab.model': '模型与资源',
            'editor.tab.parts': '部位配置',
            'editor.tab.anim': '动画状态机',
            // 基本信息页签
            'basic.identity': '标识',
            'basic.id': 'id（必填，实体注册名）',
            'basic.ns': 'namespace（缺省挂主 mod）',
            'basic.dn': 'displayName',
            'basic.collision': '碰撞箱',
            'basic.width': 'width',
            'basic.height': 'height',
            'basic.attrs': '属性',
            'basic.maxHp': 'maxHealth',
            'basic.atk': 'attackDamage',
            'basic.speed': 'movementSpeed',
            'basic.follow': 'followRange',
            'basic.armor': 'armor',
            'basic.behaviors': '行为组件',
            'basic.addBehavior': '+ 添加行为',
            // 模型页签
            'model.title': '模型',
            'model.type': 'model.type',
            'model.id': 'model.id（如 ns:model）',
            'model.pickFile': '选择本地模型文件…',
            'model.pickJar': '从 jar 导入模型…',
            'model.texture': '贴图',
            'model.textureLabel': 'texture（如 ns:textures/entity/x.png）',
            'model.sounds': '音效（ResourceLocation，留空 = 无）',
            // 部位页签
            'parts.genDefault': '生成默认部位（按模型骨骼分组）',
            'parts.addPart': '+ 添加部位',
            'parts.noParts': '尚无部位配置。先选择模型（模型与资源页签），再点"生成默认部位"。',
            'parts.hint': '点击部位卡片选中，再从左侧骨骼树点 + 添加骨骼到该部位。',
            'parts.noModel': '未解析模型。请在"模型与资源"页签选择模型文件。',
            'parts.boneTree': '模型骨骼树',
            'parts.name': '部位名',
            'parts.hp': '血量',
            'parts.fatal': '致死',
            'parts.trans': '传导',
            'parts.del': '删除部位',
            'parts.coeff': '传导',
            'parts.armor': '装甲',
            'parts.antiPierce': '抗贯穿',
            'parts.tag': '标签',
            // 动画页签
            'anim.template': '状态机模板',
            'anim.applyTemplate': '应用模板动画名',
            'anim.note': '状态判定由主 mod 写死（determineAnimationState），你只需为每个状态填动画名。动画名对应 spark_animations/entity/<ns>/<name>/<动画名>.json 的文件名。',
            'anim.state': '状态',
            'anim.meaning': '含义',
            'anim.animName': '动画名',
            // 通用
            'common.saveFailed': '保存失败: ',
            'common.exportFailed': '导出失败: ',
            'common.idRequired': 'id 必填',
            // 动态（视图运行时）
            'dyn.newEntity': '新实体',
            'dyn.unnamed': '未命名',
            'dyn.draft': '草稿',
            'dyn.newDraft': '新建（草稿）',
            'dyn.draftUnexported': '草稿 ● 未导出',
            'dyn.exported': '已导出',
            'dyn.stagedCount': '已暂存 {n} 项 ● 未导出',
            'dyn.savedAsDraft': '实体 "{id}" 已保存为草稿（暂存区）。点「导出」写入正式包',
            'dyn.exportConfirm': '将把 "{name}" 的全部暂存修改导出到正式包（contentpacks-source/{name}/），并清空暂存区。\n\n确认导出？',
            'dyn.exportedDone': '已导出 {n} 个文件到正式包，暂存区已回收',
            'dyn.buildStagedWarn': '该包有 {n} 个暂存修改未导出。\n打包会使用正式包内容（不含草稿）。\n\n是否先导出再打包？\n「确定」= 先导出再打包；「取消」= 跳过导出直接打包',
            'dyn.buildDone': '打包完成 → contentpacks/{name}',
            'dyn.buildFail': '打包失败',
            'dyn.buildRunning': '打包中… 调用 contentpacks-source/build-pack.ps1',
            'dyn.deleteConfirm': '删除实体 "{id}"？（同时删除其部位配置）',
            'dyn.deleted': '实体 "{id}" 已删除',
            'dyn.editEntity': '编辑实体',
            'dyn.newEntityTitle': '新建实体',
            'dyn.edit': '编辑',
            'dyn.delete': '删除',
            'dyn.close': '关闭',
            'dyn.loadPacksFail': '加载包列表失败: ',
            'dyn.loadEntitiesFail': '加载实体列表失败: ',
            'dyn.readEntityFail': '读取实体失败: ',
            'dyn.getDefaultsFail': '获取默认实体失败: ',
            'dyn.loadTemplatesFail': '加载状态机模板失败: ',
            'dyn.addToPart': '添加到选中部位',
            'dyn.createPackFail': '创建失败: ',
            'dyn.packCreated': '包 "{name}" 创建成功',
            'dyn.packNameRequired': '包名（Module-Name）必填',
            'dyn.selectPartFirst': '请先点击选中一个部位，再点骨骼 + 按钮',
            'dyn.boneDuplicated': '骨骼 {bone} 已分配到部位 {part}',
            'dyn.partMissing': '选中部位不存在',
            'dyn.modelRequired': '请先在"模型与资源"页签选择/解析模型文件',
            'dyn.genPartsDone': '已按模型骨骼生成默认部位分组',
            'dyn.noModelParsed': '未解析模型。请在"模型与资源"页签选择模型文件。',
            'dyn.modelParsed': '已解析: {id}（{n} 骨骼）',
            'dyn.jarNoModels': 'jar 中没有 spark_models',
            'dyn.jarModelImported': '已从 jar 导入: {path}（{n} 骨骼）',
            'dyn.invalidIndex': '无效序号',
            'dyn.pickModelNum': '选择要导入的模型（输入序号）:\n{list}',
            'dyn.readJarFail': '读取 jar 失败: ',
            'dyn.parseModelFail': '解析模型失败: ',
            'dyn.templateApplied': '已应用模板 "{name}" 的默认动画名',
        },
        en: {
            'app.title': 'ContentPack Editor',
            'app.lang': 'Language',
            'pack.title': 'Content Packs',
            'pack.new': '+ New',
            'pack.collapse': 'Collapse',
            'pack.expand': 'Expand pack list',
            'pack.namePh': 'Pack name (Module-Name)',
            'pack.versionPh': 'Version (default 0.1.0)',
            'pack.requiresPh': 'Module-Requires',
            'pack.depsPh': 'Module-Dependencies (comma-separated)',
            'pack.modReqPh': 'Module-Mod-Requires (comma-separated)',
            'pack.create': 'Create',
            'pack.cancel': 'Cancel',
            'entity.title': 'Entities',
            'entity.new': '+ New',
            'editor.empty': 'Select or create a content pack, then select or create an entity.',
            'editor.export': 'Export',
            'editor.saveDraft': 'Save Draft',
            'editor.build': 'Build',
            'editor.tab.basic': 'Basic',
            'editor.tab.model': 'Model & Assets',
            'editor.tab.parts': 'Body Parts',
            'editor.tab.anim': 'Animation',
            'basic.identity': 'Identity',
            'basic.id': 'id (required, registry name)',
            'basic.ns': 'namespace (default: main mod)',
            'basic.dn': 'displayName',
            'basic.collision': 'Hitbox',
            'basic.width': 'width',
            'basic.height': 'height',
            'basic.attrs': 'Attributes',
            'basic.maxHp': 'maxHealth',
            'basic.atk': 'attackDamage',
            'basic.speed': 'movementSpeed',
            'basic.follow': 'followRange',
            'basic.armor': 'armor',
            'basic.behaviors': 'Behaviors',
            'basic.addBehavior': '+ Add Behavior',
            'model.title': 'Model',
            'model.type': 'model.type',
            'model.id': 'model.id (e.g. ns:model)',
            'model.pickFile': 'Pick model file…',
            'model.pickJar': 'Import from jar…',
            'model.texture': 'Texture',
            'model.textureLabel': 'texture (e.g. ns:textures/entity/x.png)',
            'model.sounds': 'Sounds (ResourceLocation, empty = none)',
            'parts.genDefault': 'Generate default parts (by model bones)',
            'parts.addPart': '+ Add Part',
            'parts.noParts': 'No parts yet. Pick a model (Model tab), then click "Generate default parts".',
            'parts.hint': 'Click a part card to select it, then click + on the bone tree to add bones.',
            'parts.noModel': 'No model parsed. Pick a model file in the "Model & Assets" tab.',
            'parts.boneTree': 'Model Bone Tree',
            'parts.name': 'Name',
            'parts.hp': 'HP',
            'parts.fatal': 'Fatal',
            'parts.trans': 'Transmit',
            'parts.del': 'Delete',
            'parts.coeff': 'Transmit',
            'parts.armor': 'Armor',
            'parts.antiPierce': 'Anti-Pierce',
            'parts.tag': 'Tag',
            'anim.template': 'State Machine Template',
            'anim.applyTemplate': 'Apply Template Anim Names',
            'anim.note': 'State logic is hardcoded in the main mod (determineAnimationState). You only fill animation names. Name must match spark_animations/entity/<ns>/<name>/<anim>.json.',
            'anim.state': 'State',
            'anim.meaning': 'Meaning',
            'anim.animName': 'Anim Name',
            'common.saveFailed': 'Save failed: ',
            'common.exportFailed': 'Export failed: ',
            'common.idRequired': 'id is required',
            // dynamic (view runtime)
            'dyn.newEntity': 'New Entity',
            'dyn.unnamed': 'Unnamed',
            'dyn.draft': 'Draft',
            'dyn.newDraft': 'New (Draft)',
            'dyn.draftUnexported': 'Draft ● Un-exported',
            'dyn.exported': 'Exported',
            'dyn.stagedCount': '{n} staged ● un-exported',
            'dyn.savedAsDraft': 'Entity "{id}" saved as draft. Click Export to write to the pack.',
            'dyn.exportConfirm': 'Export ALL staged changes of "{name}" to the pack (contentpacks-source/{name}/) and clear staging?\n\nConfirm?',
            'dyn.exportedDone': 'Exported {n} files to pack. Staging cleared.',
            'dyn.buildStagedWarn': 'This pack has {n} un-exported staged changes.\nBuild uses the real pack content (no drafts).\n\nExport first?\nOK = export then build; Cancel = build without export',
            'dyn.buildDone': 'Build done → contentpacks/{name}',
            'dyn.buildFail': 'Build failed',
            'dyn.buildRunning': 'Building… calling contentpacks-source/build-pack.ps1',
            'dyn.deleteConfirm': 'Delete entity "{id}"? (Its body-part config will also be deleted)',
            'dyn.deleted': 'Entity "{id}" deleted',
            'dyn.editEntity': 'Edit Entity',
            'dyn.newEntityTitle': 'New Entity',
            'dyn.edit': 'Edit',
            'dyn.delete': 'Delete',
            'dyn.close': 'Close',
            'dyn.loadPacksFail': 'Failed to load packs: ',
            'dyn.loadEntitiesFail': 'Failed to load entities: ',
            'dyn.readEntityFail': 'Failed to read entity: ',
            'dyn.getDefaultsFail': 'Failed to get defaults: ',
            'dyn.loadTemplatesFail': 'Failed to load templates: ',
            'dyn.addToPart': 'Add to selected part',
            'dyn.createPackFail': 'Create failed: ',
            'dyn.packCreated': 'Pack "{name}" created',
            'dyn.packNameRequired': 'Pack name (Module-Name) required',
            'dyn.selectPartFirst': 'Select a part card first, then click + on the bone tree.',
            'dyn.boneDuplicated': 'Bone {bone} is already assigned to part {part}',
            'dyn.partMissing': 'Selected part not found',
            'dyn.modelRequired': 'Parse a model first (Model & Assets tab).',
            'dyn.genPartsDone': 'Default parts generated from model bones',
            'dyn.noModelParsed': 'No model parsed. Pick a model file in the "Model & Assets" tab.',
            'dyn.modelParsed': 'Parsed: {id} ({n} bones)',
            'dyn.jarNoModels': 'No spark_models in jar',
            'dyn.jarModelImported': 'Imported from jar: {path} ({n} bones)',
            'dyn.invalidIndex': 'Invalid index',
            'dyn.pickModelNum': 'Pick model to import (enter index):\n{list}',
            'dyn.readJarFail': 'Failed to read jar: ',
            'dyn.parseModelFail': 'Failed to parse model: ',
            'dyn.templateApplied': 'Template "{name}" anim names applied',
        },
    };

    const LANG_KEY = 'contentpack-editor.lang';

    function detectDefaultLang() {
        try {
            const saved = localStorage.getItem(LANG_KEY);
            if (saved === 'zh' || saved === 'en') return saved;
            const nav = (navigator.language || '').toLowerCase();
            return nav.startsWith('zh') ? 'zh' : 'en';
        } catch { return 'zh'; }
    }

    let currentLang = detectDefaultLang();

    function t(key) {
        const table = DICT[currentLang] || DICT.en;
        return table[key] != null ? table[key] : (DICT.en[key] != null ? DICT.en[key] : key);
    }

    /** 插值翻译：t('dyn.stagedCount', { n: 3 }) → '已暂存 3 项 ● 未导出' */
    function tf(key, params) {
        let s = t(key);
        if (params) {
            for (const [k, v] of Object.entries(params)) {
                s = s.split('{' + k + '}').join(String(v));
            }
        }
        return s;
    }

    function getLang() { return currentLang; }

    function setLang(lang) {
        currentLang = (lang === 'zh' || lang === 'en') ? lang : 'zh';
        try { localStorage.setItem(LANG_KEY, currentLang); } catch {}
        applyDOM();
        return currentLang;
    }

    /** 应用语言到 DOM：data-i18n 文本 + data-i18n-ph 占位符 + data-i18n-title 标题 */
    function applyDOM() {
        document.querySelectorAll('[data-i18n]').forEach((el) => {
            el.textContent = t(el.getAttribute('data-i18n'));
        });
        document.querySelectorAll('[data-i18n-ph]').forEach((el) => {
            el.placeholder = t(el.getAttribute('data-i18n-ph'));
        });
        document.querySelectorAll('[data-i18n-title]').forEach((el) => {
            el.title = t(el.getAttribute('data-i18n-title'));
        });
    }

    window.I18n = { t, tf, getLang, setLang, applyDOM, detectDefaultLang };
})();
