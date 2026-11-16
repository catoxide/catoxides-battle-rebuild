package example.contentpack.mapv2.client;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;

/**
 * 「从上面看得见吗」的筛选自检：法向自己按顶点算，不信 {@link BakedQuad#getDirection()}。
 *
 * <p>回归点：上坡铁轨的 45° 面在原版烘焙里被判成 SOUTH/NORTH/WEST/EAST
 * （UP 与水平方向点积严格平票，浮点误差让水平方向胜出），按 {@code == UP} 筛会整片丢掉。
 */
public final class BlockSurfacesSelfTest {

    private BlockSurfacesSelfTest() {
    }

    public static void run() {
        // 上坡铁轨的斜面：XZ 投影是整格，法向 (0, 1, -1)/√2，原版把它标成 SOUTH
        BakedQuad slope = quad(Direction.SOUTH, new float[][]{
                {0, 0, 0}, {0, 1, 1}, {1, 1, 1}, {1, 0, 0}});
        require(BlockSurfaces.acceptsTop(slope), "45° 斜面必须算顶面（direction 可能是 SOUTH）");

        BakedQuad flat = quad(Direction.UP, new float[][]{
                {0, 0, 0}, {0, 0, 1}, {1, 0, 1}, {1, 0, 0}});
        require(BlockSurfaces.acceptsTop(flat), "水平顶面必须收");

        BakedQuad wall = quad(Direction.NORTH, new float[][]{
                {0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}});
        require(!BlockSurfaces.acceptsTop(wall), "竖直面不能算顶面");

        BakedQuad bottom = quad(Direction.DOWN, new float[][]{
                {0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}});
        require(!BlockSurfaces.acceptsTop(bottom), "朝下的面不能算顶面");
    }

    /** 32 个 int 的顶点数组：每顶点 8 个，前三个是位置（同 {@code DefaultVertexFormats.BLOCK}）。 */
    private static BakedQuad quad(Direction direction, float[][] corners) {
        int[] vertices = new int[32];
        for (int i = 0; i < 4; i++) {
            vertices[i * 8] = Float.floatToRawIntBits(corners[i][0]);
            vertices[i * 8 + 1] = Float.floatToRawIntBits(corners[i][1]);
            vertices[i * 8 + 2] = Float.floatToRawIntBits(corners[i][2]);
        }
        return new BakedQuad(vertices, -1, direction, null, true);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
