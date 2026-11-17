package net.fabricmc.loader.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;

/**
 * A built-in mod. It has no file system root: its classes were compiled in and its assets put in
 * Rinth's pack (ADR 0065). The path methods find its assets, copied out on first use; nothing else
 * of the jar is kept.
 */
public final class ModContainerImpl implements ModContainer {

    private static final ModOrigin BUILT_IN = new ModOrigin() {
        @Override
        public Kind getKind() {
            return Kind.UNKNOWN;
        }

        @Override
        public List<Path> getPaths() {
            throw new UnsupportedOperationException("A built-in mod has no paths");
        }

        @Override
        public String getParentModId() {
            throw new UnsupportedOperationException("A built-in mod has no parent");
        }

        @Override
        public String getParentSubLocation() {
            throw new UnsupportedOperationException("A built-in mod has no parent");
        }

        @Override
        public String toString() {
            return "built in";
        }
    };

    private final ModMetadataImpl metadata;

    public ModContainerImpl(ModMetadataImpl metadata) {
        this.metadata = metadata;
    }

    @Override
    public ModMetadata getMetadata() {
        return metadata;
    }

    @Override
    public List<Path> getRootPaths() {
        return List.of();
    }

    @Override
    public Optional<Path> findPath(String file) {
        return extract(file);
    }

    /**
     * The mod's file, copied to {@code .fabric/mods/<id>/} in the game directory the first time
     * it's asked for, as a path mods can read: a built-in mod's assets are class-path resources
     * under {@code /fabric-mods/<id>/} (Rinth's pack serves them), and mods read their own files
     * (icons, mostly) through paths. Empty for a file the build didn't keep: only assets are kept.
     */
    private Optional<Path> extract(String file) {
        String name = file.replace('\\', '/');
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        if (name.isEmpty() || name.contains("..")) {
            return Optional.empty();
        }
        Path target = root().resolve(name);
        if (Files.isRegularFile(target)) {
            return Optional.of(target);
        }
        try (InputStream in =
                ModContainerImpl.class.getResourceAsStream("/fabric-mods/" + metadata.getId() + "/" + name)) {
            if (in == null) {
                return Optional.empty();
            }
            Files.createDirectories(target.getParent());
            try (OutputStream out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[8192];
                for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                    out.write(buffer, 0, read);
                }
            }
            return Optional.of(target);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Path root() {
        return FabricLoaderImpl.INSTANCE
                .getGameDir()
                .resolve(".fabric")
                .resolve("mods")
                .resolve(metadata.getId());
    }

    @Override
    public ModOrigin getOrigin() {
        return BUILT_IN;
    }

    @Override
    public Optional<ModContainer> getContainingMod() {
        return Optional.empty();
    }

    @Override
    public Collection<ModContainer> getContainedMods() {
        return List.of();
    }

    @Override
    @Deprecated
    public Path getRootPath() {
        throw new UnsupportedOperationException(metadata.getId() + " is built in and has no root path");
    }

    /** As Fabric's: where the file is, which may not exist. */
    @Override
    @Deprecated
    public Path getPath(String file) {
        return extract(file).orElseGet(() -> root().resolve(file.startsWith("/") ? file.substring(1) : file));
    }

    @Override
    public String toString() {
        return metadata.getId() + " " + metadata.getVersion().getFriendlyString();
    }
}
