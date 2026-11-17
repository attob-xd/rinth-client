package rinth.tools.fabricmods;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.jspecify.annotations.Nullable;

/**
 * Every class the mods' mixins may read, by internal name ({@code net/minecraft/client/Minecraft}),
 * first found first: the classes changed so far (access wideners, then mixins), the mods' own, the
 * game's class path in its order, then the JDK's and this tool's. Mixin reads its targets' and
 * their supertypes' bytes through it ({@link OfflineMixinService}).
 */
final class ClassIndex implements Closeable {

    private final Map<String, byte[]> changed = new LinkedHashMap<>();
    private final Map<String, byte[]> modClasses = new HashMap<>();
    private final Map<String, Source> classPath = new HashMap<>();
    private final List<JarFile> jars = new ArrayList<>();

    private record Source(
            @Nullable JarFile jar, String entry, @Nullable Path file) {}

    /** Indexes {@code entries} (jars and class directories) in order: an earlier one hides a later one's class. */
    ClassIndex(List<Path> entries) throws IOException {
        for (Path entry : entries) {
            if (Files.isDirectory(entry)) {
                try (var files = Files.walk(entry)) {
                    for (Path file : (Iterable<Path>) files::iterator) {
                        String name = entry.relativize(file).toString().replace('\\', '/');
                        if (name.endsWith(".class")) {
                            classPath.putIfAbsent(
                                    name.substring(0, name.length() - ".class".length()), new Source(null, name, file));
                        }
                    }
                }
            } else if (Files.isRegularFile(entry) && entry.toString().endsWith(".jar")) {
                JarFile jar = new JarFile(entry.toFile());
                jars.add(jar);
                for (JarEntry jarEntry : (Iterable<JarEntry>) jar.stream()::iterator) {
                    String name = jarEntry.getName();
                    if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
                        classPath.putIfAbsent(
                                name.substring(0, name.length() - ".class".length()), new Source(jar, name, null));
                    }
                }
            }
        }
    }

    /** Adds a mod's classes, under the changed ones and over the class path's. */
    void addModClasses(Map<String, byte[]> entries) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
                modClasses.putIfAbsent(name.substring(0, name.length() - ".class".length()), entry.getValue());
            }
        }
    }

    /** Replaces a mod's own class, as if the mod had it so: not a change to the game. */
    void replaceModClass(String internalName, byte[] bytes) {
        modClasses.put(internalName, bytes);
    }

    /** Replaces a class's bytes for everything that reads it from now on. */
    void change(String internalName, byte[] bytes) {
        changed.put(internalName, bytes);
    }

    /** The classes changed so far, in the order they were first changed. */
    Map<String, byte[]> changed() {
        return changed;
    }

    boolean isModClass(String internalName) {
        return modClasses.containsKey(internalName);
    }

    /** Whether the game's class path (or a mod) has the class: not the JDK's or this tool's. */
    boolean onGameClassPath(String internalName) {
        return changed.containsKey(internalName)
                || modClasses.containsKey(internalName)
                || classPath.containsKey(internalName);
    }

    /** The game's class as its class path has it, before any mod changed it; null if it isn't the game's. */
    byte @Nullable [] originalBytes(String internalName) {
        Source source = classPath.get(internalName);
        if (source == null) {
            return null;
        }
        try {
            JarFile jar = source.jar();
            if (jar != null) {
                try (InputStream in = jar.getInputStream(jar.getEntry(source.entry()))) {
                    return in.readAllBytes();
                }
            }
            Path file = source.file();
            return file == null ? null : Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't read " + internalName, e);
        }
    }

    /** The class's current bytes, or null if no one has it. */
    byte @Nullable [] bytes(String internalName) {
        byte[] bytes = changed.get(internalName);
        if (bytes == null) {
            bytes = modClasses.get(internalName);
        }
        if (bytes != null) {
            return bytes;
        }
        Source source = classPath.get(internalName);
        try {
            if (source != null) {
                JarFile jar = source.jar();
                if (jar != null) {
                    try (InputStream in = jar.getInputStream(jar.getEntry(source.entry()))) {
                        return in.readAllBytes();
                    }
                }
                Path file = source.file();
                return file == null ? null : Files.readAllBytes(file);
            }
            // The JDK's own, then this tool's (Mixin, MixinExtras, Fabric's API).
            String resource = internalName + ".class";
            try (InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(resource)) {
                if (in != null) {
                    return in.readAllBytes();
                }
            }
            try (InputStream in = ClassIndex.class.getClassLoader().getResourceAsStream(resource)) {
                return in == null ? null : in.readAllBytes();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't read " + internalName, e);
        }
    }

    @Override
    public void close() throws IOException {
        for (JarFile jar : jars) {
            jar.close();
        }
    }
}
