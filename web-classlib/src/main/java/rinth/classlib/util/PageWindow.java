package rinth.classlib.util;

import org.teavm.jso.JSBody;

/**
 * The page's state, as a window's: what web-classlib's SDL stand-in answers mods with. Here, not on
 * the substitute: TeaVM's WebAssembly GC backend miscompiles a JavaScript body declared on a class
 * that stands in for another (the module fails to compile; app-web/probes' SdlWindowProbe).
 */
public final class PageWindow {

    public static final int HIDDEN = 1;
    public static final int FOCUSED = 2;
    public static final int FULLSCREEN = 4;

    private PageWindow() {}

    /** {@link #HIDDEN}, {@link #FOCUSED} and {@link #FULLSCREEN} as bits, or -1 without a document (a worker). */
    @JSBody(
            script = "if (typeof document === 'undefined') return -1;"
                    + " return (document.hidden ? 1 : 0) | (document.hasFocus() ? 2 : 0)"
                    + " | (document.fullscreenElement ? 4 : 0);")
    public static native int state();
}
