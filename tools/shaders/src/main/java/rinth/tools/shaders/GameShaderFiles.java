package rinth.tools.shaders;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * The shader files in the game's jar, named the way the game names them at run time: a stage by
 * the file's id without extension ({@code minecraft:core/gui}), an include by its file name under
 * {@code shaders/include} ({@code minecraft:globals.glsl}), a post effect by its file name under
 * {@code post_effect} ({@code minecraft:blur}).
 */
final class GameShaderFiles {

    private final Map<String, String> vertex = new HashMap<>();
    private final Map<String, String> fragment = new HashMap<>();
    private final Map<String, String> includes = new HashMap<>();
    private final Map<String, String> postEffects = new TreeMap<>();

    static GameShaderFiles read(Path clientJar) throws IOException {
        GameShaderFiles files = new GameShaderFiles();
        try (ZipFile jar = new ZipFile(clientJar.toFile())) {
            Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    files.add(entry.getName(), () -> text(jar, entry), false);
                }
            }
        }
        return files;
    }

    /**
     * Adds the built-in Fabric mods' stages and includes (ADR 0065), under the game's: each mod's
     * assets are at {@code <mod>/assets/<namespace>/...} under {@code root}, as tools/fabricmods
     * writes them. Their post effects aren't the game's pipelines, so they aren't read.
     */
    void addMods(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.filter(Files::isRegularFile).toList();
        }
        for (Path file : paths) {
            Path relative = root.relativize(file);
            if (relative.getNameCount() > 1) {
                String name =
                        relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/');
                add(name, () -> Files.readString(file, StandardCharsets.UTF_8), true);
            }
        }
    }

    /** A file's text, read only if it's a shader file. */
    private interface Text {
        String read() throws IOException;
    }

    private void add(String name, Text text, boolean mod) throws IOException {
        // assets/<namespace>/<path>
        String[] parts = name.split("/", 3);
        if (parts.length < 3 || !parts[0].equals("assets")) {
            return;
        }
        String namespace = parts[1];
        String path = parts[2];
        if (path.startsWith("shaders/include/") && path.endsWith(".glsl")) {
            put(includes, namespace + ":" + path.substring("shaders/include/".length()), text, mod);
        } else if (path.startsWith("shaders/") && path.endsWith(".vsh")) {
            put(vertex, namespace + ":" + strip(path, "shaders/", ".vsh"), text, mod);
        } else if (path.startsWith("shaders/") && path.endsWith(".fsh")) {
            put(fragment, namespace + ":" + strip(path, "shaders/", ".fsh"), text, mod);
        } else if (!mod && path.startsWith("post_effect/") && path.endsWith(".json")) {
            postEffects.put(namespace + ":" + strip(path, "post_effect/", ".json"), text.read());
        }
    }

    /** A mod's file never replaces the game's: the browser translates the game's shaders as vanilla. */
    private static void put(Map<String, String> files, String id, Text text, boolean mod) throws IOException {
        if (!mod || !files.containsKey(id)) {
            files.put(id, text.read());
        }
    }

    private static String strip(String path, String prefix, String suffix) {
        return path.substring(prefix.length(), path.length() - suffix.length());
    }

    private static String text(ZipFile jar, ZipEntry entry) throws IOException {
        try (InputStream in = jar.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Nullable
    String shader(ShaderType type, String id) {
        return (type == ShaderType.VERTEX ? vertex : fragment).get(id);
    }

    Map<String, String> includes() {
        return includes;
    }

    /** Post effect JSON by id, sorted. */
    Map<String, String> postEffects() {
        return postEffects;
    }

    int shaderCount() {
        return vertex.size() + fragment.size();
    }
}
