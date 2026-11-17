package rinth.framework.script;

/**
 * The platform's {@link ScriptHost}: the browser build installs its JavaScript mods' host before
 * the game starts; elsewhere it stays {@link ScriptHost#NONE}. Game thread.
 */
public final class Scripts {

    private static ScriptHost host = ScriptHost.NONE;

    private Scripts() {}

    public static ScriptHost host() {
        return host;
    }

    /** Before the game starts. */
    public static void install(ScriptHost installed) {
        host = installed;
    }
}
