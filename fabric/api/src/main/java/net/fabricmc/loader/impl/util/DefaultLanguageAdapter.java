package net.fabricmc.loader.impl.util;

import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.impl.FabricLoaderImpl;

/**
 * Fabric's default language adapter, for mods that make their own entrypoints from a declaration.
 * The browser build can't look classes up by name, so it hands out only the entrypoints the build
 * listed (FabricLoaderImpl), by their declaration.
 */
public final class DefaultLanguageAdapter implements LanguageAdapter {

    public static final DefaultLanguageAdapter INSTANCE = new DefaultLanguageAdapter();

    private DefaultLanguageAdapter() {}

    @Override
    public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
        Object made = FabricLoaderImpl.INSTANCE
                .createListed(mod.getMetadata().getId(), value)
                .orElseThrow(() -> new LanguageAdapterException(value + " isn't an entrypoint the build listed for "
                        + mod.getMetadata().getId()));
        if (!type.isInstance(made)) {
            throw new LanguageAdapterException(value + " isn't a " + type.getName());
        }
        return type.cast(made);
    }
}
