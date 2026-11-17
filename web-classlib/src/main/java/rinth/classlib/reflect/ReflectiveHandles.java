package rinth.classlib.reflect;

import rinth.classlib.substitutes.java.lang.invoke.TMethodHandle;
import rinth.classlib.substitutes.java.lang.invoke.TMethodHandles$Lookup;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Method handles made from reflection, for the built-in Fabric mods (ADR 0065): tools/fabricmods
 * sends their {@code Lookup.unreflect...} calls here, and their {@code invoke} and
 * {@code invokeExact} calls to generated bridges that box the arguments for {@link #invoke}.
 * Sodium reads JOML's frustum planes through a getter made this way. A handle calls the reflected
 * member as the JDK's would: the receiver first for an instance member, and what the member
 * throws thrown as it is.
 *
 * <p>The game's own lookups still find nothing (web-classlib's {@code TMethodHandles}): the
 * libraries that probe for method handles must keep their fallbacks. The mods' changed classes refer
 * to this class by name; keep its name and members stable.
 */
public final class ReflectiveHandles {

    private ReflectiveHandles() {}

    @SuppressWarnings("unused") // The lookup's access checks are the JDK's; reflection has made them.
    public static TMethodHandle unreflectGetter(TMethodHandles$Lookup lookup, Field field) {
        return new Getter(field);
    }

    @SuppressWarnings("unused")
    public static TMethodHandle unreflectSetter(TMethodHandles$Lookup lookup, Field field) {
        return new Setter(field);
    }

    @SuppressWarnings("unused")
    public static TMethodHandle unreflect(TMethodHandles$Lookup lookup, Method method) {
        return new Call(method);
    }

    @SuppressWarnings("unused")
    public static TMethodHandle unreflectConstructor(TMethodHandles$Lookup lookup, Constructor<?> constructor) {
        return new Construct(constructor);
    }

    /** Calls {@code handle} with {@code arguments}, boxed; only a handle made here can be called. */
    public static @Nullable Object invoke(TMethodHandle handle, @Nullable Object[] arguments) throws Throwable {
        if (handle instanceof Reflective reflective) {
            try {
                return reflective.call(arguments);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                throw cause == null ? e : cause;
            }
        }
        throw new UnsupportedOperationException(
                "Only method handles made from reflection can be called in the browser build");
    }

    /** A handle over one reflected member. */
    private abstract static class Reflective extends TMethodHandle {

        abstract @Nullable Object call(@Nullable Object[] arguments) throws ReflectiveOperationException;
    }

    private static final class Getter extends Reflective {

        private final Field field;

        Getter(Field field) {
            this.field = field;
            field.setAccessible(true);
        }

        @Override
        @Nullable
        Object call(@Nullable Object[] arguments) throws IllegalAccessException {
            return field.get(Modifier.isStatic(field.getModifiers()) ? null : arguments[0]);
        }
    }

    private static final class Setter extends Reflective {

        private final Field field;

        Setter(Field field) {
            this.field = field;
            field.setAccessible(true);
        }

        @Override
        @Nullable
        Object call(@Nullable Object[] arguments) throws IllegalAccessException {
            boolean isStatic = Modifier.isStatic(field.getModifiers());
            field.set(isStatic ? null : arguments[0], arguments[isStatic ? 0 : 1]);
            return null;
        }
    }

    private static final class Call extends Reflective {

        private final Method method;

        Call(Method method) {
            this.method = method;
            method.setAccessible(true);
        }

        @Override
        @Nullable
        Object call(@Nullable Object[] arguments) throws ReflectiveOperationException {
            if (Modifier.isStatic(method.getModifiers())) {
                return method.invoke(null, arguments);
            }
            return method.invoke(arguments[0], Arrays.copyOfRange(arguments, 1, arguments.length));
        }
    }

    private static final class Construct extends Reflective {

        private final Constructor<?> constructor;

        Construct(Constructor<?> constructor) {
            this.constructor = constructor;
            constructor.setAccessible(true);
        }

        @Override
        Object call(@Nullable Object[] arguments) throws ReflectiveOperationException {
            return constructor.newInstance(arguments);
        }
    }
}
