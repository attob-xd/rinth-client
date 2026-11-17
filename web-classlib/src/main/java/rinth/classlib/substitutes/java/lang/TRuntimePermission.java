package rinth.classlib.substitutes.java.lang;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.lang.RuntimePermission}, which TeaVM lacks: a named runtime permission. A page has
 * no security manager, so nothing ever checks one (the checks are stripped with the rest of
 * {@code java.security}); this only lets code that makes one at start-up run. Log4j's
 * {@code LoaderUtil} makes its {@code getClassLoader} permission as it loads, which a mod logging
 * through Log4j (Sodium) reaches.
 */
public final class TRuntimePermission {

    private final String name;

    /**
     * @throws NullPointerException if {@code name} is null
     * @throws IllegalArgumentException if {@code name} is empty
     */
    public TRuntimePermission(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name can't be empty");
        }
        this.name = name;
    }

    /** As {@link #TRuntimePermission(String)}: the actions are unused, as the JDK documents. */
    public TRuntimePermission(String name, @Nullable String actions) {
        this(name);
    }

    public String getName() {
        return name;
    }

    /** Runtime permissions have no actions. */
    public String getActions() {
        return "";
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof TRuntimePermission permission && permission.name.equals(name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return "(\"java.lang.RuntimePermission\" \"" + name + "\")";
    }
}
