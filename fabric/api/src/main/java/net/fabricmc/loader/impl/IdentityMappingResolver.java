package net.fabricmc.loader.impl;

import java.util.Collection;
import java.util.List;
import net.fabricmc.loader.api.MappingResolver;

/**
 * Minecraft 26.x isn't obfuscated, so mods use the game's own names at run time (Fabric's
 * intermediary for 26.x is empty): every mapping is the name itself.
 */
final class IdentityMappingResolver implements MappingResolver {

    static final IdentityMappingResolver INSTANCE = new IdentityMappingResolver();

    private IdentityMappingResolver() {}

    @Override
    public Collection<String> getNamespaces() {
        return List.of("official", "named", "intermediary");
    }

    @Override
    public String getCurrentRuntimeNamespace() {
        return "official";
    }

    @Override
    public String mapClassName(String namespace, String className) {
        return className;
    }

    @Override
    public String unmapClassName(String targetNamespace, String className) {
        return className;
    }

    @Override
    public String mapFieldName(String namespace, String owner, String name, String descriptor) {
        return name;
    }

    @Override
    public String mapMethodName(String namespace, String owner, String name, String descriptor) {
        return name;
    }
}
