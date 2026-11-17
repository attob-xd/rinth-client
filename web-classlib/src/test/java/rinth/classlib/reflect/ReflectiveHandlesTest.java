package rinth.classlib.reflect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import rinth.classlib.substitutes.java.lang.invoke.TMethodHandle;
import rinth.classlib.substitutes.java.lang.invoke.TMethodHandles$Lookup;
import org.junit.jupiter.api.Test;

class ReflectiveHandlesTest {

    /** Like JOML's FrustumIntersection, whose private planes Sodium reads through a getter handle. */
    static final class Frustum {
        @SuppressWarnings("FieldMayBeFinal")
        private float[] planes = {1, 2};

        private static int made;

        Frustum() {
            made++;
        }

        @SuppressWarnings("UnusedMethod") // Called through its handle.
        private float plane(int index) {
            return planes[index];
        }

        static int twice(int value) {
            if (value < 0) {
                throw new IllegalArgumentException("negative");
            }
            return value * 2;
        }
    }

    private static final TMethodHandles$Lookup LOOKUP = new TMethodHandles$Lookup();

    @Test
    void gettersAndSettersReachTheField() throws Throwable {
        Frustum frustum = new Frustum();
        TMethodHandle getter = ReflectiveHandles.unreflectGetter(LOOKUP, Frustum.class.getDeclaredField("planes"));
        assertThat(ReflectiveHandles.invoke(getter, new Object[] {frustum})).isEqualTo(new float[] {1, 2});

        TMethodHandle setter = ReflectiveHandles.unreflectSetter(LOOKUP, Frustum.class.getDeclaredField("planes"));
        assertThat(ReflectiveHandles.invoke(setter, new Object[] {frustum, new float[] {3}}))
                .isNull();
        assertThat(frustum.planes).containsExactly(3);

        TMethodHandle staticGetter = ReflectiveHandles.unreflectGetter(LOOKUP, Frustum.class.getDeclaredField("made"));
        assertThat(ReflectiveHandles.invoke(staticGetter, new Object[0])).isEqualTo(Frustum.made);
    }

    @Test
    void methodsTakeTheReceiverFirstAndThrowWhatTheyThrow() throws Throwable {
        TMethodHandle plane = ReflectiveHandles.unreflect(LOOKUP, Frustum.class.getDeclaredMethod("plane", int.class));
        assertThat(ReflectiveHandles.invoke(plane, new Object[] {new Frustum(), 1}))
                .isEqualTo(2f);

        TMethodHandle twice = ReflectiveHandles.unreflect(LOOKUP, Frustum.class.getDeclaredMethod("twice", int.class));
        assertThat(ReflectiveHandles.invoke(twice, new Object[] {4})).isEqualTo(8);
        assertThatThrownBy(() -> ReflectiveHandles.invoke(twice, new Object[] {-1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("negative");

        int before = Frustum.made;
        TMethodHandle make = ReflectiveHandles.unreflectConstructor(LOOKUP, Frustum.class.getDeclaredConstructor());
        assertThat(ReflectiveHandles.invoke(make, new Object[0])).isInstanceOf(Frustum.class);
        assertThat(Frustum.made).isEqualTo(before + 1);
    }

    @Test
    void onlyItsOwnHandlesCanBeCalled() {
        TMethodHandle other = new TMethodHandle() {};
        assertThatThrownBy(() -> ReflectiveHandles.invoke(other, new Object[0]))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
