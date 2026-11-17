package rinth.tools.shaders;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import rinth.render.webgl2.shaders.ShaderStageKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;

/**
 * The pipelines the built-in Fabric mods asked the browser for (ADR 0065). A mod makes its pipelines
 * as it runs (Sodium's terrain, with defines for each pass), so the build can't list them as it
 * lists the game's; the browser reports each one it had no shaders for, and the dev server keeps
 * them in {@code mods/mod-shaders.txt} for the next translation. A line is
 * {@code <pipeline>\t<vertex stage key>\t<fragment stage key>}, the keys as
 * {@link ShaderStageKey#asString} writes them; {@code #} starts a comment.
 */
final class ModPipelines {

    private ModPipelines() {}

    static List<PipelineCatalog.Pipeline> read(Path file) throws IOException, ShaderException {
        List<PipelineCatalog.Pipeline> pipelines = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return pipelines;
        }
        int number = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            number++;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length != 3) {
                throw new ShaderException(file + ":" + number + ": expected a pipeline and two stage keys");
            }
            ShaderStageKey vertex = key(fields[1], ShaderType.VERTEX, file, number);
            ShaderStageKey fragment = key(fields[2], ShaderType.FRAGMENT, file, number);
            pipelines.add(new PipelineCatalog.Pipeline(fields[0], vertex, fragment, defines(vertex.defines()), false));
        }
        return pipelines;
    }

    /** A key as {@link ShaderStageKey#asString} writes it: {@code <stage> <id> <defines>}. */
    static ShaderStageKey key(String text, ShaderType type, Path file, int number) throws ShaderException {
        String[] parts = text.split(" ", 3);
        if (parts.length != 3 || !parts[0].equals(type.getName())) {
            throw new ShaderException(file + ":" + number + ": not a " + type.getName() + " stage key: " + text);
        }
        Identifier id = Identifier.tryParse(parts[1]);
        if (id == null) {
            throw new ShaderException(file + ":" + number + ": not a shader id: " + parts[1]);
        }
        // Read back and written again, so a key that isn't canonical is caught here.
        ShaderStageKey key = ShaderStageKey.of(type, id, defines(parts[2]));
        if (!key.asString().equals(text)) {
            throw new ShaderException(file + ":" + number + ": defines aren't in canonical order: " + text);
        }
        return key;
    }

    /** Defines as a key holds them: {@code KEY=value} pairs, then bare flags, joined by {@code ;}. */
    static ShaderDefines defines(String canonical) {
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> flags = new LinkedHashSet<>();
        if (!canonical.isEmpty()) {
            for (String part : canonical.split(";", -1)) {
                int equals = part.indexOf('=');
                if (equals < 0) {
                    flags.add(part);
                } else {
                    values.put(part.substring(0, equals), part.substring(equals + 1));
                }
            }
        }
        return new ShaderDefines(Map.copyOf(values), Set.copyOf(flags));
    }
}
