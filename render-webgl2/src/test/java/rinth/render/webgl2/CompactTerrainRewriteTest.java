package rinth.render.webgl2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import rinth.render.webgl2.shaders.TranslatedStage;

class CompactTerrainRewriteTest {

    // The inputs as SPIRV-Cross declares them in the terrain vertex shader.
    private static final String TERRAIN = """
            #version 300 es
            layout(location = 0) in vec3 Position;
            out float rinth_io_0;
            layout(location = 1) in vec4 Color;
            layout(location = 3) in ivec2 UV2;
            out vec2 rinth_io_3;
            layout(location = 2) in vec2 UV0;

            void main()
            {
                vec3 pos = Position;
                ivec2 param_2 = UV2;
                rinth_io_3 = UV0;
            }
            """;

    private static TranslatedStage stage(String glsl) {
        return new TranslatedStage(
                "vertex minecraft:core/terrain ",
                "0",
                glsl,
                List.of(
                        new TranslatedStage.Attribute("Position", 0, false),
                        new TranslatedStage.Attribute("Color", 1, false),
                        new TranslatedStage.Attribute("UV2", 3, true),
                        new TranslatedStage.Attribute("UV0", 2, false)),
                List.of("Globals"),
                List.of("Sampler2"),
                null);
    }

    @Test
    void theGamesInputsDecodeTheCompactVertex() {
        TranslatedStage rewritten = CompactTerrainRewrite.rewrite(stage(TERRAIN));

        assertThat(rewritten.glsl())
                .contains("layout(location = 0) in uvec4 RinthPositionLight;")
                .contains("#define Position (vec3(RinthPositionLight.xyz) * (1.0 / 2048.0) - vec3(8.0))")
                .contains("layout(location = 2) in uvec2 RinthUv;")
                .contains("#define UV0 (vec2(RinthUv) * (1.0 / 32768.0))")
                .contains("#define UV2 ivec2(int(RinthPositionLight.w & 255u), int(RinthPositionLight.w >> 8u))")
                .contains("layout(location = 1) in vec4 Color;")
                .doesNotContain("in vec3 Position;")
                .doesNotContain("in ivec2 UV2;")
                // The body reads the same names as before.
                .contains("vec3 pos = Position;");
        assertThat(rewritten.attributes())
                .containsExactly(
                        new TranslatedStage.Attribute("RinthPositionLight", 0, true),
                        new TranslatedStage.Attribute("Color", 1, false),
                        new TranslatedStage.Attribute("RinthUv", 2, true));
    }

    @Test
    void aShaderWithoutTheTerrainInputsIsRefused() {
        assertThatThrownBy(() -> CompactTerrainRewrite.rewrite(stage("#version 300 es\nvoid main() {}\n")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
