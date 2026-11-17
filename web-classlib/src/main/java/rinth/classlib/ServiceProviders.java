package rinth.classlib;

import java.util.List;
import java.util.function.Function;

/**
 * Service providers bound beyond the class library's own, for {@code ServiceLoader}
 * ({@code TServiceLoader}): the built-in Fabric mods' (ADR 0065). tools/fabricmods generates a
 * class that lists the providers the mods' {@code META-INF/services} files name, and binds it here
 * the first time a mod looks a service up.
 *
 * <p>The mods' generated class refers to this class by name; keep its name and members stable.
 */
public final class ServiceProviders {

    private static Function<Class<?>, List<?>> bound = service -> List.of();

    private ServiceProviders() {}

    /** Binds {@code providers}, which answers a service type with its providers (none: empty). */
    public static void bind(Function<Class<?>, List<?>> providers) {
        bound = providers;
    }

    /** The bound providers of {@code service}, in their service files' order. */
    public static List<?> of(Class<?> service) {
        return bound.apply(service);
    }
}
