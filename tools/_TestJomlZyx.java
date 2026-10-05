import org.joml.Matrix4f;

public class _TestJomlZyx {
    public static void main(String[] args) {
        float x = 0.7f, y = 1.1f, z = -0.4f;
        Matrix4f m = new Matrix4f().rotationZYX(x, y, z);
        System.out.println("JOML rotationZYX(x=0.7,y=1.1,z=-0.4) toString:");
        System.out.println(m);
        System.out.println("m00=" + m.m00() + " m01=" + m.m01() + " m02=" + m.m02());
        System.out.println("m10=" + m.m10() + " m11=" + m.m11() + " m12=" + m.m12());
        System.out.println("m20=" + m.m20() + " m21=" + m.m21() + " m22=" + m.m22());
        System.out.println("rotationX(0.7): " + new Matrix4f().rotationX(0.7f));
        System.out.println("rotationY(0.7): " + new Matrix4f().rotationY(0.7f));
        System.out.println("rotationZ(0.7): " + new Matrix4f().rotationZ(0.7f));
    }
}
