package rinth.tools.fabricmods;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

/**
 * Mixin's global properties, kept in a map. Mixin takes the first property service it finds
 * (META-INF/services), and the ones it ships need a LaunchWrapper or ModLauncher; this tool's jar
 * comes first on its class path.
 */
public final class OfflineGlobalProperties implements IGlobalPropertyService {

    private record Key(String name) implements IPropertyKey {}

    private final Map<IPropertyKey, Object> values = new HashMap<>();

    public OfflineGlobalProperties() {}

    @Override
    public IPropertyKey resolveKey(String name) {
        return new Key(name);
    }

    @Override
    @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
    public synchronized <T> @Nullable T getProperty(IPropertyKey key) {
        return (T) values.get(key);
    }

    @Override
    public synchronized void setProperty(IPropertyKey key, Object value) {
        values.put(key, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> T getProperty(IPropertyKey key, T defaultValue) {
        Object value = values.get(key);
        return value == null ? defaultValue : (T) value;
    }

    @Override
    public synchronized String getPropertyString(IPropertyKey key, String defaultValue) {
        @Nullable Object value = values.get(key);
        return value == null ? defaultValue : value.toString();
    }
}
