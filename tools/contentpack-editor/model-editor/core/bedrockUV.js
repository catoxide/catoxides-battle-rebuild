// bedrockUV.js - Bedrock cube UV 布局计算（W2 视口渲染核心数学；纯函数，node 可测）
//
// 移植自 Blockbench 参照实现（D:\Software\MC develop pack\Blockbench\js）：
//   formats\bedrock\bedrock.js   parseCube（simple uv → box_uv；per-face 导入；up/down 角点交换）
//   outliner\types\cube.js       updateUV（face_list 布局表、mirror 翻转+东西交换、角点→顶点 UV、
//                                uv_rotation 循环）、updateFaces（索引）
//   util\three_custom.js         setShape（顶点表）
// 交叉验证（design §4 规则7）：
//   - zombie_3 64×64 贴图各区域在此布局下无重叠平铺
//   - 标准 zombie 皮肤躯干区域吻合：uv(16,16) 8×12×4 → 顶(20,16) 前(20,20) 右(28,20) 背(32,20)
//   - 游戏内 A/B 为最终裁决（design §8）
//
// 坐标约定：
//   - UV 像素空间：u 向右、v 向下；贴图尺寸 pw×ph
//   - three.js 顶点 UV：(u/pw, 1 - v/ph)（v 向上）
//   - 面顺序 = three BoxGeometry 24 顶点顺序 = [east, west, up, down, south, north]，
//     每面 4 顶点，顶点顺序 = setShape 顶点表（cubeVertexPositions）
//
// 标准 box UV 布局（simple uv [u0,v0]，cube 尺寸 sx×sy×sz；from/size 表示，v 向下）：
//   east  from [0, sz]        size [sz, sy]
//   north from [sz, sz]       size [sx, sy]
//   west  from [sz+sx, sz]    size [sz, sy]
//   south from [2sz+sx, sz]   size [sx, sy]
//   up    from [sz+sx, sz]    size [-sx, -sz]   （角点反序：u0v0 = 矩形右下）
//   down  from [sz+2sx, 0]    size [-sx, sz]
//
// mirror（bone/cube 级）：所有面矩形水平翻转（from[0]+=size[0]; size[0]*=-1，角点反转）
//   + 交换 east/west 两面矩形。仅作用于 simple uv（per-face 矩形由文件显式给出，不受 mirror 影响）
//
// per-face UV：矩形 (u, v, u+w, v+h)（w/h 可为负 = 翻转）；up/down 面先做角点 180° 交换
//   （补偿游戏上下面朝向差异），再应用 uv_rotation（0/90/180/270，角点循环）

(function (root, factory) {
    const api = factory();
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    if (root) root.BedrockUV = api;
})(typeof self !== 'undefined' ? self : (typeof globalThis !== 'undefined' ? globalThis : null), function () {

    const FACES = ['east', 'west', 'up', 'down', 'south', 'north'];

    /**
     * 每面 UV 矩形（from/size 表示，UV 像素坐标，v 向下；含 signed size）。
     * @param {Object} cube 规范形 cube {size, uv: [u,v] | perFace{face:{uv,uv_size?,uv_rotation?}}, mirror}
     * @returns {Object<string, [number,number,number,number]|null>} 按 FACES；null = 该面无 UV
     */
    function faceRects(cube) {
        const sx = cube.size[0], sy = cube.size[1], sz = cube.size[2];
        const rects = {};
        if (Array.isArray(cube.uv)) {
            const u0 = cube.uv[0], v0 = cube.uv[1];
            // from/size 表示与 Blockbench face_list 完全一致（up/down 角点反序，不可"归一"）
            rects.east  = [u0,                v0 + sz, sz,  sy];
            rects.north = [u0 + sz,           v0 + sz, sx,  sy];
            rects.west  = [u0 + sz + sx,      v0 + sz, sz,  sy];
            rects.south = [u0 + 2 * sz + sx,  v0 + sz, sx,  sy];
            rects.up    = [u0 + sz + sx,      v0 + sz, -sx, -sz];
            rects.down  = [u0 + sz + sx * 2,  v0,      -sx, sz];
            if (cube.mirror) {
                // 水平翻转正角点（矩形范围不变，角点顺序反转）
                for (const f of FACES) {
                    const r = rects[f];
                    r[0] += r[2];
                    r[2] *= -1;
                }
                // 交换 east/west 矩形
                const tmp = rects.east;
                rects.east = rects.west;
                rects.west = tmp;
            }
        } else {
            for (const f of FACES) {
                const face = cube.uv[f];
                if (!face) { rects[f] = null; continue; }
                const w = face.uv_size ? face.uv_size[0] : 0;
                const h = face.uv_size ? face.uv_size[1] : 0;
                rects[f] = [face.uv[0], face.uv[1], w, h];
            }
        }
        return rects;
    }

    /**
     * 6 面 × 4 顶点 UV（three.js 0..1，v 向上），面序 = FACES（three 24 顶点顺序）。
     * @param {Object} cube 规范形 cube（mirror 已按 bone/cube 解析好）
     * @param {number} pw 贴图宽（px）
     * @param {number} ph 贴图高（px）
     * @returns {Float32Array} 长 48
     */
    function cubeFaceUVs(cube, pw, ph) {
        const out = new Float32Array(48);
        const rects = faceRects(cube);
        const perFace = !Array.isArray(cube.uv);
        for (let i = 0; i < 6; i++) {
            const f = FACES[i];
            const r = rects[f];
            if (!r) continue; // 无 UV 的面留 0（视口侧处理）
            let u0 = r[0], v0 = r[1];
            let u1 = r[0] + r[2], v1 = r[1] + r[3];
            if (perFace && (f === 'up' || f === 'down')) {
                // per-face 上/下面角点 180° 交换（bedrock.js 导入期归一）
                let t = u0; u0 = u1; u1 = t;
                t = v0; v0 = v1; v1 = t;
            }
            let rot = perFace && cube.uv[f] && cube.uv[f].uv_rotation ? cube.uv[f].uv_rotation : 0;
            let arr = [
                [u0 / pw, 1 - v0 / ph],
                [u1 / pw, 1 - v0 / ph],
                [u0 / pw, 1 - v1 / ph],
                [u1 / pw, 1 - v1 / ph],
            ];
            while (rot > 0) {
                const a = arr[0];
                arr[0] = arr[2];
                arr[2] = arr[3];
                arr[3] = arr[1];
                arr[1] = a;
                rot -= 90;
            }
            for (let k = 0; k < 4; k++) {
                out[i * 8 + k * 2] = arr[k][0];
                out[i * 8 + k * 2 + 1] = arr[k][1];
            }
        }
        return out;
    }

    /**
     * cube 顶点表（setShape 移植）：from/to = three 空间最小/最大角（from.x < to.x 等）。
     * 面序 = FACES，每面 4 顶点（与 cubeFaceUVs 的 UV 角点一一对应）。
     * @returns {Float32Array} 长 72
     */
    function cubeVertexPositions(fx, fy, fz, tx, ty, tz) {
        return new Float32Array([
            // east (+x)
            tx, ty, tz,  tx, ty, fz,  tx, fy, tz,  tx, fy, fz,
            // west (-x)
            fx, ty, fz,  fx, ty, tz,  fx, fy, fz,  fx, fy, tz,
            // up (+y)
            fx, ty, fz,  tx, ty, fz,  fx, ty, tz,  tx, ty, tz,
            // down (-y)
            fx, fy, tz,  tx, fy, tz,  fx, fy, fz,  tx, fy, fz,
            // south (+z)
            fx, ty, tz,  tx, ty, tz,  fx, fy, tz,  tx, fy, tz,
            // north (-z)
            tx, ty, fz,  fx, ty, fz,  tx, fy, fz,  fx, fy, fz,
        ]);
    }

    /** 每面索引 (v0,v2,v1)/(v2,v3,v1)，外朝向；36 个索引，面序 = FACES。 */
    function cubeIndices() {
        const out = [];
        for (let i = 0; i < 6; i++) {
            const b = i * 4;
            out.push(b + 0, b + 2, b + 1, b + 2, b + 3, b + 1);
        }
        return out;
    }

    return {
        FACES,
        faceRects,
        cubeFaceUVs,
        cubeVertexPositions,
        cubeIndices,
    };
});
