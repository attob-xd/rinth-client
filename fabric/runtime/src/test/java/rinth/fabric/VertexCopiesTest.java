package rinth.fabric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rinth.platform.api.Platform;
import rinth.platform.api.Platforms;
import rinth.platform.api.threads.PlatformThreads;

class VertexCopiesTest {

    /** Enough of a platform for the game's vertex formats to load, as the client's TestPlatform. */
    @BeforeAll
    static void platform() {
        if (Platforms.isInstalled()) {
            return;
        }
        PlatformThreads inline = suggested -> 0;
        Platforms.install((Platform) Proxy.newProxyInstance(
                Platform.class.getClassLoader(),
                new Class<?>[] {Platform.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "threads" -> inline;
                    case "description" -> "fabric runtime tests";
                    case "nanoTime" -> System.nanoTime();
                    case "toString" -> "TestPlatform";
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
    }

    // The plan is checked here; copy() only applies it, and runs on the browser's memory (LWJGL's
    // MemoryUtil needs its native library on a JVM).

    @Test
    void copiesEachElementToItsPlaceInTheOtherFormat() {
        // Sodium's glyphs (position, UV0, UV2, color) into the game's text format (position, color, UV0, UV2).
        VertexFormat glyph = DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR;
        VertexFormat text = DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP;
        int position = glyph.getElement("Position").format().blockSize();
        int color = glyph.getElement("Color").format().blockSize();
        int uv0 = glyph.getElement("UV0").format().blockSize();
        int uv2 = glyph.getElement("UV2").format().blockSize();

        assertThat(VertexCopies.plan(glyph, text))
                .containsExactly(
                        glyph.getVertexSize(),
                        text.getVertexSize(),
                        // Position stays at the start.
                        0,
                        0,
                        position,
                        // Color moves forward, from after the light to after the position.
                        glyph.getElement("Color").offset(),
                        text.getElement("Color").offset(),
                        color,
                        // UV0 and UV2 move back together: one copy.
                        glyph.getElement("UV0").offset(),
                        text.getElement("UV0").offset(),
                        uv0 + uv2);
    }

    @Test
    void mergesAdjacentElementsIntoOneCopy() {
        VertexFormat format = DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP;
        assertThat(VertexCopies.plan(format, format))
                .containsExactly(format.getVertexSize(), format.getVertexSize(), 0, 0, format.getVertexSize());
    }

    @Test
    void refusesASourceWithoutATargetElement() {
        assertThatThrownBy(() -> VertexCopies.plan(
                        DefaultVertexFormat.POSITION_COLOR, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UV0");
    }
}
