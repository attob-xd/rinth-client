package rinth.classlib.substitutes.org.lwjgl.sdl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import rinth.classlib.util.PageWindow;

class SDLVideoTest {

    @Test
    void aHiddenTabIsAMinimizedWindow() {
        // Sodium's check, as its mixin makes it every frame.
        assertThat(TSDLVideo.flags(PageWindow.HIDDEN) & 64L).isNotZero();
        assertThat(TSDLVideo.flags(PageWindow.FOCUSED) & 64L).isZero();
        assertThat(TSDLVideo.flags(PageWindow.FOCUSED | PageWindow.FULLSCREEN))
                .isEqualTo(TSDLVideo.RESIZABLE | TSDLVideo.INPUT_FOCUS | TSDLVideo.FULLSCREEN);
        // Without a document (a worker), nothing is known but that it can be resized.
        assertThat(TSDLVideo.flags(-1)).isEqualTo(TSDLVideo.RESIZABLE);
    }
}
