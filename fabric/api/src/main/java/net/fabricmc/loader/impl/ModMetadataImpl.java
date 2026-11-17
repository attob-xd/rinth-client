package net.fabricmc.loader.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;
import net.fabricmc.loader.impl.util.version.StringVersion;
import net.fabricmc.loader.impl.util.version.VersionParser;

/**
 * A built-in mod's {@code fabric.mod.json}, as much of it as the build keeps: who it is, its
 * version, side, names, people and licence. Dependencies were checked when the client was built;
 * custom values aren't kept.
 */
public final class ModMetadataImpl implements ModMetadata {

    private final String id;
    private final Version version;
    private final String name;
    private final String description;
    private final ModEnvironment environment;
    private final List<String> provides;
    private final List<Person> authors;
    private final List<String> licenses;
    private final String icon;

    public ModMetadataImpl(
            String id,
            String version,
            String name,
            String description,
            String environment,
            List<String> provides,
            List<String> authors,
            List<String> licenses,
            String icon) {
        this.id = id;
        this.version = parse(version);
        this.name = name.isEmpty() ? id : name;
        this.description = description;
        this.environment = switch (environment) {
            case "client" -> ModEnvironment.CLIENT;
            case "server" -> ModEnvironment.SERVER;
            default -> ModEnvironment.UNIVERSAL;
        };
        this.provides = List.copyOf(provides);
        List<Person> people = new ArrayList<>();
        for (String author : authors) {
            people.add(new NamedPerson(author));
        }
        this.authors = List.copyOf(people);
        this.licenses = List.copyOf(licenses);
        this.icon = icon;
    }

    private static Version parse(String version) {
        try {
            return VersionParser.parse(version, false);
        } catch (VersionParsingException e) {
            return new StringVersion(version);
        }
    }

    @Override
    public String getType() {
        return "fabric";
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public Collection<String> getProvides() {
        return provides;
    }

    @Override
    public Version getVersion() {
        return version;
    }

    @Override
    public ModEnvironment getEnvironment() {
        return environment;
    }

    @Override
    public Collection<ModDependency> getDependencies() {
        return List.of();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Collection<Person> getAuthors() {
        return authors;
    }

    @Override
    public Collection<Person> getContributors() {
        return List.of();
    }

    @Override
    public ContactInformation getContact() {
        return ContactInformation.EMPTY;
    }

    @Override
    public Collection<String> getLicense() {
        return licenses;
    }

    @Override
    public Optional<String> getIconPath(int size) {
        // The largest the mod has, whatever the size asked for: Mod Menu scales it.
        return icon.isEmpty() ? Optional.empty() : Optional.of(icon);
    }

    @Override
    public boolean containsCustomValue(String key) {
        return false;
    }

    @Override
    public CustomValue getCustomValue(String key) {
        return null;
    }

    @Override
    public Map<String, CustomValue> getCustomValues() {
        return Map.of();
    }

    @Override
    @Deprecated
    public boolean containsCustomElement(String key) {
        return false;
    }

    private record NamedPerson(String getName) implements Person {

        @Override
        public ContactInformation getContact() {
            return ContactInformation.EMPTY;
        }
    }
}
