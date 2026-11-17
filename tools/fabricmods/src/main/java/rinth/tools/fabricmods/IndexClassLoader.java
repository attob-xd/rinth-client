package rinth.tools.fabricmods;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Loads the mods' and the game's classes from the {@link ClassIndex} for what Mixin must run on
 * this JVM: config plugins, which decide which mixins apply, and the classes they touch. This
 * tool's own classes (Mixin, Fabric's API) come from its parent. The mods' other files are its
 * resources, every mod's copy of one: a plugin's {@code ServiceLoader} reads them all.
 */
final class IndexClassLoader extends ClassLoader {

    private final ClassIndex index;
    private final List<ModJar> mods;

    IndexClassLoader(ClassIndex index, List<ModJar> mods, ClassLoader parent) {
        super("fabric-mods", parent);
        this.index = index;
        this.mods = mods;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String internalName = name.replace('.', '/');
        byte[] bytes = index.onGameClassPath(internalName) ? index.bytes(internalName) : null;
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }
        return defineClass(name, bytes, 0, bytes.length);
    }

    @Override
    protected @Nullable URL findResource(String name) {
        List<URL> found = modFiles(name);
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        return Collections.enumeration(modFiles(name));
    }

    private List<URL> modFiles(String name) {
        List<URL> found = new ArrayList<>();
        if (name.endsWith(".class")) {
            return found;
        }
        for (ModJar mod : mods) {
            byte[] bytes = mod.entries().get(name);
            URL url = bytes == null ? null : url(mod.id(), name, bytes);
            if (url != null) {
                found.add(url);
            }
        }
        return found;
    }

    /** A URL that reads a mod's file from memory, or null for a name no URL can hold. */
    private static @Nullable URL url(String modId, String name, byte[] bytes) {
        URLStreamHandler handler = new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) {
                return new URLConnection(url) {
                    @Override
                    public void connect() {}

                    @Override
                    public InputStream getInputStream() {
                        return new ByteArrayInputStream(bytes);
                    }
                };
            }
        };
        try {
            return URL.of(URI.create("fabric-mods:/" + modId + "/" + name), handler);
        } catch (MalformedURLException | IllegalArgumentException e) {
            return null;
        }
    }
}
