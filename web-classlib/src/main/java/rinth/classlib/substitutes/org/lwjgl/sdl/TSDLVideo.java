package rinth.classlib.substitutes.org.lwjgl.sdl;

import rinth.classlib.util.PageWindow;

/**
 * LWJGL's {@code SDLVideo}, only what the built-in Fabric mods ask of the window (ADR 0065), from
 * the page ({@link PageWindow}): Sodium checks every frame whether the window is minimized. The
 * game itself draws to a canvas and asks the page through Rinth's platform, never SDL; every other
 * SDL call stays removed and throws.
 */
@SuppressWarnings("TypeName") // The name is LWJGL's class, as the class library maps it.
public final class TSDLVideo {

    // SDL 3's window flags.
    static final long FULLSCREEN = 0x1;
    static final long OCCLUDED = 0x4;
    static final long RESIZABLE = 0x20;
    static final long MINIMIZED = 0x40;
    static final long INPUT_FOCUS = 0x200;

    private TSDLVideo() {}

    /** The window's flags: the page's tab stands for it, hidden as minimized, whatever {@code window} is. */
    @SuppressWarnings("unused")
    public static long SDL_GetWindowFlags(long window) {
        return flags(PageWindow.state());
    }

    /** Flags for a page in {@code state} ({@link PageWindow#state}), or none known (negative). */
    static long flags(int state) {
        long flags = RESIZABLE;
        if (state < 0) {
            return flags;
        }
        if ((state & PageWindow.HIDDEN) != 0) {
            flags |= MINIMIZED | OCCLUDED;
        }
        if ((state & PageWindow.FOCUSED) != 0) {
            flags |= INPUT_FOCUS;
        }
        if ((state & PageWindow.FULLSCREEN) != 0) {
            flags |= FULLSCREEN;
        }
        return flags;
    }
}
