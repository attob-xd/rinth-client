package net.fabricmc.loader.impl;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.EntrypointException;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.ObjectShare;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

/**
 * Rinth's Fabric Loader, behind the public API ({@code FabricLoader.getInstance()} returns it):
 * the mods built into this client, their entrypoints and the game's directories. It loads nothing:
 * the mods were compiled in and their mixins applied when the client was built (tools/fabricmods,
 * ADR 0065). {@link #setUp} fills it once, from the generated mod table, in the game
 * (fabric/runtime) and in the build tool (for mods' mixin plugins, which ask what's loaded).
 */
public final class FabricLoaderImpl implements FabricLoader {

    /** What {@code FabricLoader.getInstance()} returns: the same field name as Fabric's own loader. */
    public static final FabricLoaderImpl INSTANCE = new FabricLoaderImpl();

    /** An entrypoint the build listed: its key, its mod, its declaration, and how to make it. */
    public record Entrypoint(String key, String modId, String value, Supplier<Object> factory) {}

    private EnvType environment = EnvType.CLIENT;
    private Path gameDir = Path.of(".");
    private String gameVersion = "";
    private Object gameInstance;
    private final Map<String, ModContainer> mods = new LinkedHashMap<>();
    private final Map<String, ModContainer> provided = new LinkedHashMap<>();
    private final Map<String, List<Entrypoint>> entrypoints = new LinkedHashMap<>();
    /** One object per mod and declaration, whichever keys list it, as Fabric does. */
    private final Map<String, Object> instances = new LinkedHashMap<>();

    private final ObjectShareImpl share = new ObjectShareImpl();
    private ModSwitchControl switches;

    private FabricLoaderImpl() {}

    /**
     * Fills the loader: the side, the game's directory and version, the game object (or null), the
     * mods and their entrypoints. Again only to replace them (the build tool, between runs).
     */
    public synchronized void setUp(
            EnvType side,
            Path gameDirectory,
            String version,
            Object game,
            List<ModContainerImpl> modList,
            List<Entrypoint> entrypointList) {
        environment = side;
        gameDir = gameDirectory;
        gameVersion = version;
        gameInstance = game;
        mods.clear();
        provided.clear();
        entrypoints.clear();
        instances.clear();
        for (ModContainerImpl mod : modList) {
            mods.put(mod.getMetadata().getId(), mod);
            provided.put(mod.getMetadata().getId(), mod);
            for (String alias : mod.getMetadata().getProvides()) {
                provided.putIfAbsent(alias, mod);
            }
        }
        for (Entrypoint entrypoint : entrypointList) {
            entrypoints
                    .computeIfAbsent(entrypoint.key(), key -> new ArrayList<>())
                    .add(entrypoint);
        }
    }

    /** Hands over Mod Menu's switches (fabric/runtime, as the game starts). */
    public synchronized void setSwitches(ModSwitchControl control) {
        switches = control;
    }

    /** Mod Menu's switches, where the game has them. */
    public synchronized Optional<ModSwitchControl> switches() {
        return Optional.ofNullable(switches);
    }

    /**
     * Switches built-in mods off for this session (Mod Menu's switches, fabric/runtime's
     * ModSwitchState): they stay in {@link #getAllMods} and {@link #getModContainer} by their own
     * ids, so the menu lists them to switch back on, but aren't loaded ({@link #isModLoaded}, and
     * no other mod's alias finds them) and their entrypoints aren't made. After {@link #setUp}.
     */
    public synchronized void switchOff(Collection<String> ids) {
        for (String id : ids) {
            ModContainer mod = mods.get(id);
            if (mod == null) {
                continue;
            }
            provided.values().removeIf(container -> container == mod);
            for (List<Entrypoint> list : entrypoints.values()) {
                list.removeIf(entrypoint -> entrypoint.modId().equals(id));
            }
        }
    }

    /** The object for {@code entrypoint}, made on first use and shared by its other keys. */
    synchronized Object instance(Entrypoint entrypoint) {
        String id = entrypoint.modId() + "|" + entrypoint.value();
        Object made = instances.get(id);
        if (made == null) {
            made = entrypoint.factory().get();
            instances.put(id, made);
        }
        return made;
    }

    /**
     * The object for the entrypoint {@code modId} declared as {@code value}, if the build listed one:
     * what the default language adapter can make, since the browser can't look classes up by name.
     */
    public synchronized Optional<Object> createListed(String modId, String value) {
        for (List<Entrypoint> list : entrypoints.values()) {
            for (Entrypoint entrypoint : list) {
                if (entrypoint.modId().equals(modId) && entrypoint.value().equals(value)) {
                    return Optional.of(instance(entrypoint));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public <T> List<T> getEntrypoints(String key, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (EntrypointContainer<T> container : getEntrypointContainers(key, type)) {
            result.add(container.getEntrypoint());
        }
        return result;
    }

    @Override
    public <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) {
        List<Entrypoint> listed;
        synchronized (this) {
            listed = new ArrayList<>(entrypoints.getOrDefault(key, List.of()));
        }
        List<EntrypointContainer<T>> result = new ArrayList<>();
        for (Entrypoint entrypoint : listed) {
            Object made;
            try {
                made = instance(entrypoint);
            } catch (RuntimeException | LinkageError e) {
                throw new EntrypointException(key, entrypoint.modId(), e);
            }
            if (!type.isInstance(made)) {
                throw new EntrypointException(
                        key,
                        entrypoint.modId(),
                        new ClassCastException(entrypoint.value() + " isn't a " + type.getName()));
            }
            ModContainer mod = mods.get(entrypoint.modId());
            result.add(new EntrypointContainerImpl<>(type.cast(made), mod, entrypoint.value()));
        }
        return result;
    }

    @Override
    public <T> void invokeEntrypoints(String key, Class<T> type, Consumer<? super T> invoker) {
        RuntimeException failure = null;
        for (EntrypointContainer<T> container : getEntrypointContainers(key, type)) {
            try {
                invoker.accept(container.getEntrypoint());
            } catch (RuntimeException e) {
                RuntimeException wrapped = new EntrypointException(
                        key, container.getProvider().getMetadata().getId(), e);
                if (failure == null) {
                    failure = wrapped;
                } else {
                    failure.addSuppressed(wrapped);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public ObjectShare getObjectShare() {
        return share;
    }

    @Override
    public MappingResolver getMappingResolver() {
        return IdentityMappingResolver.INSTANCE;
    }

    @Override
    public synchronized Optional<ModContainer> getModContainer(String id) {
        // A switched-off mod (switchOff) keeps its container by its own id: Mod Menu lists it, with
        // its icon and files, to switch it back on.
        ModContainer mod = provided.get(id);
        return Optional.ofNullable(mod != null ? mod : mods.get(id));
    }

    @Override
    public synchronized Collection<ModContainer> getAllMods() {
        return Collections.unmodifiableList(new ArrayList<>(mods.values()));
    }

    @Override
    public synchronized boolean isModLoaded(String id) {
        return provided.containsKey(id);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return false;
    }

    @Override
    public EnvType getEnvironmentType() {
        return environment;
    }

    @Override
    public String getRawGameVersion() {
        return gameVersion;
    }

    @Override
    @Deprecated
    public Object getGameInstance() {
        return gameInstance;
    }

    @Override
    public Path getGameDir() {
        return gameDir;
    }

    @Override
    @Deprecated
    public File getGameDirectory() {
        return gameDir.toFile();
    }

    @Override
    public Path getConfigDir() {
        Path dir = gameDir.resolve("config");
        // As Fabric Loader's: made when first asked for, so mods write their config files straight in.
        if (!Files.isDirectory(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new UncheckedIOException("Couldn't make the config directory " + dir, e);
            }
        }
        return dir;
    }

    @Override
    @Deprecated
    public File getConfigDirectory() {
        return getConfigDir().toFile();
    }

    @Override
    public String[] getLaunchArguments(boolean sanitize) {
        return new String[0];
    }
}
