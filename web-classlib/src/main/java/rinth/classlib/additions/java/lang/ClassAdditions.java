package rinth.classlib.additions.java.lang;

import java.lang.reflect.Type;
import java.net.URL;
import org.jspecify.annotations.Nullable;
import rinth.classlib.InstanceMethod;
import rinth.classlib.reflect.GenericSuperclasses;
import rinth.classlib.resources.ClassPathResources;
import rinth.classlib.substitutes.java.lang.TModule;

/**
 * {@code java.lang.Class} methods TeaVM lacks. The browser build keeps no signers and few generic
 * signatures, so these answer as a class without them would; resources come from
 * {@link ClassPathResources}.
 */
public final class ClassAdditions {

    private ClassAdditions() {}

    /**
     * The generic superclass where the build kept it (type capture: {@link GenericSuperclasses}),
     * otherwise the raw one.
     */
    @InstanceMethod
    public static @Nullable Type getGenericSuperclass(Class<?> self) {
        Type kept = GenericSuperclasses.of(self);
        return kept != null ? kept : self.getSuperclass();
    }

    /**
     * The one module a page has ({@link TModule}). The game's profiler asks for {@code jdk.jfr}
     * through it, which it doesn't find (the first packet sent reaches the profiler).
     */
    @InstanceMethod
    public static TModule getModule(Class<?> self) {
        return TModule.PROGRAM;
    }

    @InstanceMethod
    public static Type[] getGenericInterfaces(Class<?> self) {
        return self.getInterfaces();
    }

    /** A {@code file:} URL into the page's copy of the class path's resources. */
    @InstanceMethod
    public static @Nullable URL getResource(Class<?> self, String name) {
        return ClassPathResources.url(ClassPathResources.find(self, name));
    }

    @InstanceMethod
    public static Object @Nullable [] getSigners(Class<?> self) {
        return null;
    }

    @InstanceMethod
    public static boolean isAnonymousClass(Class<?> self) {
        return self.getSimpleName().isEmpty();
    }
}
