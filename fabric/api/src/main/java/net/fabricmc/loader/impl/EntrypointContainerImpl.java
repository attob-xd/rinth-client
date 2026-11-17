package net.fabricmc.loader.impl;

import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

/** An entrypoint's object with the mod that declared it, and the declaration. */
final class EntrypointContainerImpl<T> implements EntrypointContainer<T> {

    private final T entrypoint;
    private final ModContainer provider;
    private final String definition;

    EntrypointContainerImpl(T entrypoint, ModContainer provider, String definition) {
        this.entrypoint = entrypoint;
        this.provider = provider;
        this.definition = definition;
    }

    @Override
    public T getEntrypoint() {
        return entrypoint;
    }

    @Override
    public ModContainer getProvider() {
        return provider;
    }

    @Override
    public String getDefinition() {
        return definition;
    }
}
