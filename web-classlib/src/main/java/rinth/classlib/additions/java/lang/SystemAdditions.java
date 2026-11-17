package rinth.classlib.additions.java.lang;

/** {@code java.lang.System} methods TeaVM lacks. */
public final class SystemAdditions {

    private SystemAdditions() {}

    /**
     * A page can't end its own process. Exiting stops the calling thread for good (the game's main
     * thread, in practice), which halts the game where a desktop JVM would have exited; the page
     * stays open with the console log.
     */
    @SuppressWarnings("InfiniteLoopStatement") // Never returning is the point.
    public static void exit(int status) {
        // tools/webbuild's BrowserCheck watches for this line.
        System.err.println("Java exited with status " + status);
        Object never = new Object();
        synchronized (never) {
            while (true) {
                try {
                    never.wait();
                } catch (InterruptedException e) {
                    // Still exited.
                }
            }
        }
    }

    /**
     * A page has no environment: no variable is set. Code that reads one for an optional setting
     * (Sodium's chunk builder reads its thread count this way) takes its default, as it would on a
     * PC where the variable isn't set.
     */
    public static @org.jspecify.annotations.Nullable String getenv(String name) {
        java.util.Objects.requireNonNull(name);
        return null;
    }

    /** The page's environment: empty, and unmodifiable as the JDK's is. */
    public static java.util.Map<String, String> getenv() {
        return java.util.Collections.emptyMap();
    }

    /** No native libraries in a page; the name is only ever logged or compared. */
    public static String mapLibraryName(String name) {
        return name;
    }
}
