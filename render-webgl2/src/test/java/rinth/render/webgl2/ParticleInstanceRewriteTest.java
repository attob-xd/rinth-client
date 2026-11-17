package rinth.render.webgl2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import rinth.render.webgl2.shaders.TranslatedStage;

class ParticleInstanceRewriteTest {

    // The inputs as SPIRV-Cross declares them in the particle vertex shader.
    private static final String PARTICLE = """
            #version 300 es

            layout(location = 0) in vec3 Position;
            out float rinth_io_0;
            layout(location = 1) in vec2 UV0;
            out vec4 rinth_io_3;
            layout(location = 2) in vec4 Color;
            layout(location = 3) in ivec2 UV2;

            void main()
            {
                gl_Position = vec4(Position, 1.0);
                rinth_io_0 = UV0.x;
                rinth_io_3 = Color * float(UV2.x);
            }
            """;

    private static TranslatedStage stage(String glsl) {
        return new TranslatedStage(
                "vertex minecraft:core/particle ",
                "0",
                glsl,
                List.of(
                        new TranslatedStage.Attribute("Position", 0, false),
                        new TranslatedStage.Attribute("UV0", 1, false),
                        new TranslatedStage.Attribute("Color", 2, false),
                        new TranslatedStage.Attribute("UV2", 3, true)),
                List.of(),
                List.of("Sampler2"),
                null);
    }

    @Test
    void theQuadIsWorkedOutFromTheInstance() {
        TranslatedStage rewritten = ParticleInstanceRewrite.rewrite(stage(PARTICLE));

        assertThat(rewritten.glsl())
                .contains("layout(location = 0) in vec3 RinthCenter;")
                .contains("layout(location = 4) in vec4 RinthRotation;")
                .contains("layout(location = 5) in float RinthScale;")
                .contains("layout(location = 6) in vec4 RinthUvs;")
                .contains("layout(location = 2) in vec4 RinthColor;")
                .contains("#define Position rinth_particle_position()")
                .contains("#define UV0 rinth_particle_uv()")
                .contains("#define Color (RinthColor.zyxw)")
                .contains("gl_VertexID & 3")
                .doesNotContain("in vec3 Position;")
                .doesNotContain("in vec2 UV0;")
                .contains("layout(location = 3) in ivec2 UV2;");
        assertThat(rewritten.attributes())
                .containsExactly(
                        new TranslatedStage.Attribute("RinthCenter", 0, false),
                        new TranslatedStage.Attribute("RinthColor", 2, false),
                        new TranslatedStage.Attribute("UV2", 3, true),
                        new TranslatedStage.Attribute("RinthRotation", 4, false),
                        new TranslatedStage.Attribute("RinthScale", 5, false),
                        new TranslatedStage.Attribute("RinthUvs", 6, false));
    }

    @Test
    void anotherShaderIsRefused() {
        String noUv = PARTICLE.replace("layout(location = 1) in vec2 UV0;\n", "");
        assertThatThrownBy(() -> ParticleInstanceRewrite.rewrite(stage(noUv)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UV0");
    }
}
