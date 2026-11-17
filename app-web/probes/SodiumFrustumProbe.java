import net.caffeinemc.mods.sodium.client.render.viewport.frustum.SimpleFrustum;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

/**
 * Check, with Sodium built in: Sodium's frustum reads JOML's planes through a method handle made
 * with unreflectGetter and called with invokeExact (tools/fabricmods' MethodHandleCalls, web-classlib's
 * ReflectiveHandles). Without them its class couldn't initialize, and no world could draw. Compile
 * it against fabric/runtime/build/fabric-mods/classes and the game's class path. Prints "probe
 * passed" when a box in front of the camera is inside and one behind it isn't.
 */
public final class SodiumFrustumProbe {

    private SodiumFrustumProbe() {}

    public static void main(String[] args) {
        String result;
        try {
            Matrix4f view = new Matrix4f().perspective((float) Math.toRadians(70), 1.5f, 0.05f, 100f);
            SimpleFrustum frustum = new SimpleFrustum(new FrustumIntersection(view));
            boolean ahead = frustum.testAab(-1, -1, -10, 1, 1, -8);
            boolean behind = frustum.testAab(-1, -1, 8, 1, 1, 10);
            result = ahead && !behind ? "probe passed" : "probe failed: ahead " + ahead + ", behind " + behind;
        } catch (Throwable e) {
            result = "probe failed: " + e;
        }
        System.out.println(result);
    }
}
