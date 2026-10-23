// Debug probe: run the editor's real decoder path on actual piece files.
import fs from 'fs';
import { createRequire } from 'module';
const require = createRequire(import.meta.url);
const NBT = require('./src/core/nbt.js');

const dir = 'D:\\Download\\structure_extract\\structure\\data\\catoxide\\structures\\plain_fortress';
const files = fs.readdirSync(dir).filter(f => f.endsWith('.nbt'));
console.log('files:', files.length);

for (const f of files) {
    const buf = fs.readFileSync(dir + '\\' + f);
    const out = { file: f, bytes: buf.length, gzip0: buf[0] === 0x1f, gzip1: buf[1] === 0x8b };
    try {
        const d = NBT.decodeTaggedAuto(buf);
        const root = NBT.taggedToPlain(d.root);
        const keys = Object.keys(root);
        out.keys = keys;
        out.size = root.size;
        out.blocks_len = Array.isArray(root.blocks) ? root.blocks.length : (root.blocks ? 'obj' : null);
        out.blocks_0_kind = Array.isArray(root.blocks) ? (Array.isArray(root.blocks[0]) ? 'array' : 'object') : null;
        out.has_palette = Array.isArray(root.palette);
        out.palette_len = Array.isArray(root.palette) ? root.palette.length : null;
        out.palette_0 = Array.isArray(root.palette) && root.palette[0] ? Object.keys(root.palette[0]) : null;
        out.DataVersion = root.DataVersion;
        out.entities_len = Array.isArray(root.entities) ? root.entities.length : null;
        try {
            const region = require('./src/core/regionImport.js').parseRegionNbt(buf);
            out.region_format = region.format;
            out.region_jigsaws = region.jigsaws.length;
            out.region_blocks = region.blocks.length;
            out.region_size = region.size;
        } catch (e) {
            out.region_error = String(e && e.message || e);
        }
    } catch (e) {
        out.error = String(e && e.message || e);
    }
    console.log(JSON.stringify(out));
}
