import org.joml.Matrix4f;
import org.joml.Vector3f;

public class TestJoml2 {
    static final float[][] E = {{1,0,0},{0,1,0},{0,0,1}};
    static void show(String label, Matrix4f m) {
        for (int i = 0; i < 3; i++) {
            Vector3f v = new Vector3f(E[i][0], E[i][1], E[i][2]);
            m.transformPosition(v);
            System.out.printf("  %s e%d = (%+.4f, %+.4f, %+.4f)%n", label, i + 1, v.x, v.y, v.z);
        }
    }
    public static void main(String[] args) {
        show("rotZYX(-60,0,0)", new Matrix4f().rotateZYX((float) Math.toRadians(-60), 0, 0));
        show("rotZYX(0,-90,0) ", new Matrix4f().rotateZYX(0, (float) Math.toRadians(-90), 0));
        show("rotZYX(0,0,45)  ", new Matrix4f().rotateZYX(0, 0, (float) Math.toRadians(45)));
        float x = (float) Math.toRadians(-60);
        float cx = (float) Math.cos(x), sx = (float) Math.sin(x);
        Matrix4f Rx = new Matrix4f(1, 0, 0, 0, 0, cx, sx, 0, 0, -sx, cx, 0, 0, 0, 0, 1);
        Vector3f v = new Vector3f(0, 1, 0);
        Rx.transformPosition(v);
        System.out.printf("手工Rx(-60)·(0,1,0) = (%+.4f, %+.4f, %+.4f)%n", v.x, v.y, v.z);
    }
}
