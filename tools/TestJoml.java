import org.joml.Matrix4f;
import org.joml.Vector3f;

public class TestJoml {
    static void show(String label, Matrix4f m, float x, float y, float z) {
        Vector3f v = new Vector3f(x, y, z);
        m.transformPosition(v);
        System.out.println(label + " = (" + v.x + ", " + v.y + ", " + v.z + ")");
    }
    public static void main(String[] args) {
        float a = (float) Math.toRadians(-60);
        Matrix4f m = new Matrix4f().rotateZYX(a, 0, 0);
        show("rotateZYX(-60,0,0)(0,1,0) ", m, 0, 1, 0);
        show("rotateZYX(-60,0,0)(0,0,1) ", m, 0, 0, 1);
        Matrix4f m2 = new Matrix4f().rotateX(a);
        show("rotateX(-60)(0,1,0)       ", m2, 0, 1, 0);
        Matrix4f my = new Matrix4f().rotateZYX(0, (float) Math.toRadians(-90), 0);
        show("rotateZYX(0,-90,0)(0,0,1) ", my, 0, 0, 1);
        Matrix4f mz = new Matrix4f().rotateZYX(0, 0, (float) Math.toRadians(45));
        show("rotateZYX(0,0,45)(1,0,0)  ", mz, 1, 0, 0);
    }
}
