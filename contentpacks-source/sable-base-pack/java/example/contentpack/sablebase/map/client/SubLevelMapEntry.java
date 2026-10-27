package example.contentpack.sablebase.map.client;

import com.catoxide.catoxidesbattlerebuild.core.sable.ISubLevelService;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelBounds;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelInfo;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/**
 * 一个 sub-level 的俯视纹理 + 待重采 chunk。
 *
 * <p>纹理像素坐标系：{@code x = localX - bounds.minX}，{@code y = localZ - bounds.minZ}。
 * 大步长（大船降采样）时，每个像素取该步长格左上角那一列的顶面颜色。
 */
final class SubLevelMapEntry {

    private static final int REFRESH_TICKS = 100;

    private final UUID id;
    private SubLevelInfo info;

    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;

    private int step = 1;
    private int imageW;
    private int imageH;

    private NativeImage image;
    private DynamicTexture texture;
    private ResourceLocation location;

    private final Set<Long> pending = new HashSet<>();
    private boolean dirty;
    private long lastRefreshTick;

    SubLevelMapEntry(SubLevelInfo info) {
        this.id = info.id();
        this.info = info;
        this.applyBounds(info.bounds());
    }

    UUID id() {
        return this.id;
    }

    SubLevelInfo info() {
        return this.info;
    }

    ResourceLocation textureLocation() {
        return this.location;
    }

    int imageW() {
        return this.imageW;
    }

    int imageH() {
        return this.imageH;
    }

    int step() {
        return this.step;
    }

    boolean hasTexture() {
        return this.location != null && this.image != null;
    }

    /** 用于 3D 姿态 → 2D 投影的参考平面高度。 */
    double referenceY() {
        return (this.minY + this.maxY) / 2.0;
    }

    /**
     * 更新姿态/包围盒；返回 true 表示包围盒变化（调用方应 ensureImage）。
     */
    boolean updateInfo(SubLevelInfo next) {
        SubLevelBounds before = this.info.bounds();
        SubLevelBounds after = next.bounds();
        boolean boundsChanged = !sameBounds(before, after);
        this.info = next;
        if (boundsChanged) {
            this.applyBounds(after);
        }
        return boundsChanged;
    }

    private void applyBounds(SubLevelBounds b) {
        this.minX = b.minX();
        this.minY = b.minY();
        this.minZ = b.minZ();
        this.maxX = b.maxX();
        this.maxY = b.maxY();
        this.maxZ = b.maxZ();
    }

    private static boolean sameBounds(SubLevelBounds a, SubLevelBounds b) {
        return a.minX() == b.minX() && a.minY() == b.minY() && a.minZ() == b.minZ()
                && a.maxX() == b.maxX() && a.maxY() == b.maxY() && a.maxZ() == b.maxZ();
    }

    /** 按当前包围盒确保纹理尺寸正确；尺寸/原点变化时重建并排队全部 chunk。 */
    void ensureImage(Minecraft minecraft, int maxTexture) {
        SubLevelBounds b = this.info.bounds();
        if (b.isEmpty()) {
            this.release(minecraft);
            return;
        }

        int width = b.maxX() - b.minX() + 1;
        int depth = b.maxZ() - b.minZ() + 1;
        int newStep = Math.max(1, (int) Math.ceil(Math.max(width, depth) / (double) maxTexture));
        int newW = Math.max(1, (width + newStep - 1) / newStep);
        int newH = Math.max(1, (depth + newStep - 1) / newStep);

        if (this.image != null && this.step == newStep && this.imageW == newW
                && this.imageH == newH && this.minX == b.minX() && this.minZ == b.minZ()) {
            return;
        }

        this.release(minecraft);
        this.step = newStep;
        this.imageW = newW;
        this.imageH = newH;
        this.minX = b.minX();
        this.minY = b.minY();
        this.minZ = b.minZ();
        this.maxX = b.maxX();
        this.maxY = b.maxY();
        this.maxZ = b.maxZ();

        this.image = new NativeImage(newW, newH, true);
        this.texture = new DynamicTexture(this.image);
        this.location = minecraft.getTextureManager().register(
                "sable_map_ship_" + this.id.toString().replace("-", ""),
                this.texture);
        this.dirty = true;
        this.queueAll();
    }

    void queueAll() {
        this.pending.clear();
        if (this.image == null || this.maxX < this.minX || this.maxZ < this.minZ) {
            return;
        }
        int cx0 = this.minX >> 4;
        int cx1 = this.maxX >> 4;
        int cz0 = this.minZ >> 4;
        int cz1 = this.maxZ >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                this.pending.add(key(cx, cz));
            }
        }
    }

    void queueChunk(ChunkPos chunk) {
        if (this.image == null) {
            return;
        }
        int cx = chunk.x;
        int cz = chunk.z;
        if ((cx << 4) > this.maxX || ((cx << 4) + 15) < this.minX
                || (cz << 4) > this.maxZ || ((cz << 4) + 15) < this.minZ) {
            return;
        }
        this.pending.add(key(cx, cz));
    }

    void maybeRefresh(long tick) {
        if (this.image == null || tick - this.lastRefreshTick < REFRESH_TICKS) {
            return;
        }
        this.lastRefreshTick = tick;
        this.queueAll();
    }

    /** 处理最多 {@code budget} 个 chunk，返回剩余预算。 */
    int process(ISubLevelService service, Level level, int budget) {
        if (this.image == null || this.pending.isEmpty()) {
            return budget;
        }
        Iterator<Long> iterator = this.pending.iterator();
        while (iterator.hasNext() && budget > 0) {
            long key = iterator.next();
            iterator.remove();
            budget--;
            this.processChunk(service, level, (int) (key >> 32), (int) key);
        }
        return budget;
    }

    private void processChunk(ISubLevelService service, Level level, int chunkX, int chunkZ) {
        int blockX0 = Math.max(this.minX, chunkX << 4);
        int blockX1 = Math.min(this.maxX, (chunkX << 4) + 15);
        int blockZ0 = Math.max(this.minZ, chunkZ << 4);
        int blockZ1 = Math.min(this.maxZ, (chunkZ << 4) + 15);
        if (blockX0 > blockX1 || blockZ0 > blockZ1) {
            return;
        }

        int px0 = ceilDiv(blockX0 - this.minX, this.step);
        int px1 = (blockX1 - this.minX) / this.step;
        int py0 = ceilDiv(blockZ0 - this.minZ, this.step);
        int py1 = (blockZ1 - this.minZ) / this.step;

        for (int py = py0; py <= py1; py++) {
            int localZ = this.minZ + py * this.step;
            for (int px = px0; px <= px1; px++) {
                int localX = this.minX + px * this.step;
                int y = service.topBlockY(level, this.info, localX, localZ);
                if (y == Integer.MIN_VALUE) {
                    this.image.setPixelRGBA(px, py, 0);
                    this.dirty = true;
                    continue;
                }

                BlockState state = service.getBlockState(level, this.info, localX, y, localZ);
                int argb = state.isAir() ? 0
                        : SableMapColors.colorFor(state, level,
                        new BlockPos(localX, y, localZ), y, this.minY, this.maxY);
                this.image.setPixelRGBA(px, py,
                        argb == 0 ? 0 : SableMapColors.toNativeAbgr(argb));
                this.dirty = true;
            }
        }
    }

    void uploadIfDirty() {
        if (this.dirty && this.texture != null) {
            this.texture.upload();
            this.dirty = false;
        }
    }

    void release(Minecraft minecraft) {
        if (this.location != null && minecraft != null) {
            // TextureManager.release → DynamicTexture.close() → NativeImage.close()
            minecraft.getTextureManager().release(this.location);
        }
        this.location = null;
        this.texture = null;
        this.image = null;
        this.pending.clear();
        this.dirty = false;
    }

    private static int ceilDiv(int value, int divisor) {
        return -Math.floorDiv(-value, divisor);
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }
}
