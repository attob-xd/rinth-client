import org.lwjgl.sdl.SDLVideo;

/**
 * Regression, for web-classlib's SDLVideo stand-in: Sodium asks SDL every frame whether the
 * window is minimized, and the browser build's answer comes from the page. Its first version kept
 * the page's state in a JavaScript body on the substitute itself, and the WebAssembly module failed
 * to compile. Compile it against the game's class path. Prints "probe passed" when a visible,
 * focused page reads as a window that isn't minimized.
 */
public final class SdlWindowProbe {

    private SdlWindowProbe() {}

    public static void main(String[] args) {
        long flags = SDLVideo.SDL_GetWindowFlags(0L);
        System.out.println((flags & 64L) == 0 ? "probe passed" : "probe failed: flags " + Long.toHexString(flags));
    }
}
