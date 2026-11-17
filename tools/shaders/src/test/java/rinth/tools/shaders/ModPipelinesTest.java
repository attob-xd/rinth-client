package rinth.tools.shaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rinth.render.webgl2.shaders.ShaderStageKey;

class ModPipelinesTest {

    @TempDir
    Path dir;

    @Test
    void readsBackTheKeysTheBrowserReports() throws Exception {
        ShaderDefines defines =
                new ShaderDefines(Map.of("ALPHA_CUTOUT", "0.5"), Set.of("USE_FOG", "USE_VERTEX_COMPRESSION"));
        Identifier id = Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque");
        // As the WebGL2 device writes a line (PipelineCompiler.reportModPipeline).
        String line = "sodium:cutout\t" + ShaderStageKey.of(ShaderType.VERTEX, id, defines) + "\t"
                + ShaderStageKey.of(ShaderType.FRAGMENT, id, defines);
        Path file = dir.resolve("mod-shaders.txt");
        Files.writeString(file, "# kept by the dev server\n" + line + "\n\n", StandardCharsets.UTF_8);

        List<PipelineCatalog.Pipeline> pipelines = ModPipelines.read(file);

        assertThat(pipelines).singleElement().satisfies(pipeline -> {
            assertThat(pipeline.name()).isEqualTo("sodium:cutout");
            assertThat(pipeline.vertex()).isEqualTo(ShaderStageKey.of(ShaderType.VERTEX, id, defines));
            assertThat(pipeline.fragment()).isEqualTo(ShaderStageKey.of(ShaderType.FRAGMENT, id, defines));
            assertThat(pipeline.defines().values()).isEqualTo(defines.values());
            assertThat(pipeline.defines().flags()).isEqualTo(defines.flags());
        });
        assertThat(ModPipelines.read(dir.resolve("absent.txt"))).isEmpty();
    }

    @Test
    void refusesLinesThatArentKeys() throws Exception {
        Path file = dir.resolve("mod-shaders.txt");
        Files.writeString(
                file, "sodium:x\tfragment sodium:a USE_FOG\tvertex sodium:a USE_FOG\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ModPipelines.read(file)).isInstanceOf(ShaderException.class);
        Files.writeString(
                file, "sodium:x\tvertex sodium:a USE_FOG;A=1\tfragment sodium:a USE_FOG;A=1\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ModPipelines.read(file))
                .isInstanceOf(ShaderException.class)
                .hasMessageContaining("canonical");
    }

    @Test
    void modsAddStagesAndIncludesUnderTheGames() throws Exception {
        Path shaders = dir.resolve("sodium/assets/sodium/shaders");
        Files.createDirectories(shaders.resolve("blocks"));
        Files.createDirectories(shaders.resolve("include"));
        Files.writeString(shaders.resolve("blocks/terrain.vsh"), "#version 330\n", StandardCharsets.UTF_8);
        Files.writeString(shaders.resolve("include/fog.glsl"), "float fog;\n", StandardCharsets.UTF_8);
        Path post = dir.resolve("sodium/assets/sodium/post_effect");
        Files.createDirectories(post);
        Files.writeString(post.resolve("glow.json"), "{\"passes\": []}", StandardCharsets.UTF_8);

        GameShaderFiles files = new GameShaderFiles();
        files.addMods(dir);

        assertThat(files.shader(ShaderType.VERTEX, "sodium:blocks/terrain")).isEqualTo("#version 330\n");
        assertThat(files.includes()).containsEntry("sodium:fog.glsl", "float fog;\n");
        assertThat(files.postEffects()).isEmpty();
    }
}
