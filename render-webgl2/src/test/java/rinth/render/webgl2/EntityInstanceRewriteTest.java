package rinth.render.webgl2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import rinth.render.webgl2.shaders.TranslatedStage;

class EntityInstanceRewriteTest {

    // The inputs as SPIRV-Cross declares them in the entity vertex shader, Normal after Position.
    private static final String ENTITY = """
            #version 300 es

            layout(std140) uniform DynamicTransforms
            {
                mat4 ModelViewMat;
            } rinth_ubo_DynamicTransforms;

            layout(location = 0) in vec3 Position;
            out float rinth_io_0;
            layout(location = 5) in vec3 Normal;
            layout(location = 1) in vec4 Color;
            layout(location = 4) in ivec2 UV2;
            layout(location = 2) in vec2 UV0;

            void main()
            {
                gl_Position = rinth_ubo_DynamicTransforms.ModelViewMat * vec4(Position, 1.0);
                vec3 param_4 = Normal;
            }
            """;

    private static TranslatedStage stage(String glsl, boolean normal) {
        List<TranslatedStage.Attribute> attributes = normal
                ? List.of(
                        new TranslatedStage.Attribute("Position", 0, false),
                        new TranslatedStage.Attribute("Normal", 5, false),
                        new TranslatedStage.Attribute("Color", 1, false),
                        new TranslatedStage.Attribute("UV2", 4, true),
                        new TranslatedStage.Attribute("UV0", 2, false))
                : List.of(
                        new TranslatedStage.Attribute("Position", 0, false),
                        new TranslatedStage.Attribute("Color", 1, false));
        return new TranslatedStage(
                "vertex minecraft:core/entity ",
                "0",
                glsl,
                attributes,
                List.of("DynamicTransforms"),
                List.of("Sampler2"),
                null);
    }

    @Test
    void positionAndNormalComeFromThePartsRows() {
        TranslatedStage rewritten = EntityInstanceRewrite.rewrite(stage(ENTITY, true));

        assertThat(rewritten.glsl())
                .startsWith("""
                        #version 300 es
                        layout(location = 6) in uint RinthPart;
                        layout(location = 7) in uint RinthFirstRow;
                        """)
                .contains("uniform RinthParts")
                .contains("layout(location = 0) in vec3 RinthLocalPosition;")
                .contains("#define Position rinth_part_position()")
                .contains("layout(location = 5) in vec3 RinthLocalNormal;")
                .contains("#define Normal rinth_part_normal()")
                .doesNotContain("in vec3 Position;")
                .doesNotContain("in vec3 Normal;")
                // The rest of the shader reads what it read before.
                .contains("vec4(Position, 1.0)")
                .contains("vec3 param_4 = Normal;");
        assertThat(rewritten.attributes())
                .extracting(TranslatedStage.Attribute::name)
                .containsExactly(
                        "RinthLocalPosition", "RinthLocalNormal", "Color", "UV2", "UV0", "RinthPart", "RinthFirstRow");
        assertThat(rewritten.attributes())
                .filteredOn(attribute -> attribute.name().startsWith("RinthPart")
                        || attribute.name().equals("RinthFirstRow"))
                .allMatch(TranslatedStage.Attribute::integer);
        assertThat(rewritten.uniformBlocks()).containsExactly("DynamicTransforms", "RinthParts");
    }

    @Test
    void aShaderWithoutNormalsOnlyPlacesPositions() {
        String glsl = """
                #version 300 es
                layout(location = 0) in vec3 Position;
                layout(location = 1) in vec4 Color;
                void main() { gl_Position = vec4(Position, 1.0); }
                """;
        TranslatedStage rewritten = EntityInstanceRewrite.rewrite(stage(glsl, false));

        assertThat(rewritten.glsl()).contains("in uint RinthPart;").doesNotContain("rinth_part_normal");
        assertThat(rewritten.attributes())
                .extracting(TranslatedStage.Attribute::location)
                .containsExactly(0, 1, 2, 3);
    }

    @Test
    void aShaderWithoutPositionIsRefused() {
        String glsl = "#version 300 es\nlayout(location = 1) in vec4 Color;\nvoid main() {}\n";
        TranslatedStage stage = new TranslatedStage(
                "vertex minecraft:core/other ",
                "0",
                glsl,
                List.of(new TranslatedStage.Attribute("Color", 1, false)),
                List.of(),
                List.of(),
                null);

        assertThatThrownBy(() -> EntityInstanceRewrite.rewrite(stage)).isInstanceOf(IllegalArgumentException.class);
    }
}
