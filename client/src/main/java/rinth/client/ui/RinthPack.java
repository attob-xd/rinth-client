package rinth.client.ui;

import com.mojang.logging.LogUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.flag.FeatureFlagSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Rinth's own resources, as a resource pack that's always on, above the game's and any the
 * player picks: the client's font, and whatever else the look needs from the game's resource system.
 * Its files are class-path resources under {@value #ROOT}, listed in {@code files.txt} there (the
 * browser's class path can't be listed); a line {@code <path> = <file>} serves one of Rinth's files
 * at another path, which is how the game's default font is replaced without a file under {@code
 * assets/minecraft/} in the repo. Every file in it is Rinth's own work or openly licensed
 * (the font's OFL beside it), never the game's. A client built with Fabric mods (ADR 0065) serves
 * their assets here too, listed by the build in {@value #MOD_FILES} with class-path paths of their own.
 */
public final class RinthPack implements RepositorySource {

    /** For {@code PackRepository}'s sources ({@code Minecraft}'s constructor). */
    public static final RinthPack SOURCE = new RinthPack();

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ROOT = "/rinth-pack/";
    /** The built-in Fabric mods' assets, if any (fabric/runtime's build writes it). */
    private static final String MOD_FILES = "/fabric-mods/files.txt";

    private static final PackLocationInfo LOCATION =
            new PackLocationInfo("rinth", Component.literal("Rinth"), PackSource.BUILT_IN, Optional.empty());

    /** The pack's paths, each with the class-path file served for it. */
    private static @Nullable Map<String, String> files;

    private RinthPack() {}

    @Override
    public void loadPacks(Consumer<Pack> result) {
        Pack.Metadata metadata = new Pack.Metadata(
                Component.literal("The client's look"), PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of());
        Pack.ResourcesSupplier resources = new Pack.ResourcesSupplier() {
            @Override
            public Resources openMetadata(PackLocationInfo location) {
                return new Resources();
            }

            @Override
            public Stream<PackResources> openResources(PackLocationInfo location, Pack.Metadata meta) {
                return Stream.of(new Resources());
            }
        };
        result.accept(new Pack(LOCATION, resources, metadata, new PackSelectionConfig(true, Pack.Position.TOP, true)));
    }

    /** The pack's files, by path from the pack's root ({@code assets/...}), to the file served for each. */
    private static synchronized Map<String, String> files() {
        Map<String, String> listed = files;
        if (listed == null) {
            listed = new LinkedHashMap<>();
            if (!list(ROOT + "files.txt", listed)) {
                LOGGER.warn("Rinth's pack has no files.txt");
            }
            // The mods' files never replace Rinth's own.
            list(MOD_FILES, listed);
            files = listed;
        }
        return listed;
    }

    /** Adds a list's {@code <path>} and {@code <path> = <file>} lines to {@code listed}; whether there was one. */
    private static boolean list(String resource, Map<String, String> listed) {
        try (InputStream in = RinthPack.class.getResourceAsStream(resource)) {
            if (in == null) {
                return false;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String entry = line.trim();
                if (entry.isEmpty() || entry.startsWith("#")) {
                    continue;
                }
                int equals = entry.indexOf('=');
                if (equals < 0) {
                    listed.putIfAbsent(entry, entry);
                } else {
                    listed.putIfAbsent(
                            entry.substring(0, equals).trim(),
                            entry.substring(equals + 1).trim());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Couldn't read {}: {}", resource, e.toString());
        }
        return true;
    }

    /** A listed file: relative to Rinth's pack, or a class-path path of its own (a mod's, from the root). */
    private static IoSupplier<InputStream> open(String path) {
        return () -> {
            InputStream in = RinthPack.class.getResourceAsStream(path.startsWith("/") ? path : ROOT + path);
            if (in == null) {
                throw new IOException("Rinth's pack has no " + path);
            }
            return in;
        };
    }

    private static final class Resources implements PackResources {

        @Override
        public @Nullable IoSupplier<InputStream> getRootResource(String... path) {
            return null;
        }

        @Override
        public @Nullable IoSupplier<InputStream> getResource(PackType type, Identifier location) {
            if (type != PackType.CLIENT_RESOURCES) {
                return null;
            }
            String file = files().get("assets/" + location.getNamespace() + "/" + location.getPath());
            return file == null ? null : open(file);
        }

        @Override
        public void listResources(PackType type, String namespace, String directory, ResourceOutput output) {
            if (type != PackType.CLIENT_RESOURCES) {
                return;
            }
            String prefix = "assets/" + namespace + "/";
            String under = prefix + directory + "/";
            for (Map.Entry<String, String> file : files().entrySet()) {
                String path = file.getKey();
                if (path.startsWith(under)) {
                    output.accept(
                            Identifier.fromNamespaceAndPath(namespace, path.substring(prefix.length())),
                            open(file.getValue()));
                }
            }
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            Set<String> namespaces = new LinkedHashSet<>();
            if (type == PackType.CLIENT_RESOURCES) {
                for (String path : files().keySet()) {
                    int end = path.indexOf('/', "assets/".length());
                    if (path.startsWith("assets/") && end > 0) {
                        namespaces.add(path.substring("assets/".length(), end));
                    }
                }
            }
            return namespaces;
        }

        @Override
        public <T> @Nullable T getMetadataSection(MetadataSectionType<T> type) {
            return null;
        }

        @Override
        public PackLocationInfo location() {
            return LOCATION;
        }

        @Override
        public void close() {}
    }
}
