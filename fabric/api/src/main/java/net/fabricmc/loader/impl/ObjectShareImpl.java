package net.fabricmc.loader.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import net.fabricmc.loader.api.ObjectShare;

/** Fabric's object share for mods to swap values by key, with listeners for values to come. */
final class ObjectShareImpl implements ObjectShare {

    private final Map<String, Object> values = new HashMap<>();
    private final Map<String, List<BiConsumer<String, Object>>> waiting = new HashMap<>();

    @Override
    public synchronized Object get(String key) {
        return values.get(key);
    }

    @Override
    public void whenAvailable(String key, BiConsumer<String, Object> consumer) {
        Object value;
        synchronized (this) {
            value = values.get(key);
            if (value == null) {
                waiting.computeIfAbsent(key, k -> new ArrayList<>()).add(consumer);
                return;
            }
        }
        consumer.accept(key, value);
    }

    @Override
    public Object put(String key, Object value) {
        Object previous;
        List<BiConsumer<String, Object>> listeners;
        synchronized (this) {
            previous = values.put(key, value);
            listeners = waiting.remove(key);
        }
        if (listeners != null) {
            for (BiConsumer<String, Object> listener : listeners) {
                listener.accept(key, value);
            }
        }
        return previous;
    }

    @Override
    public Object putIfAbsent(String key, Object value) {
        synchronized (this) {
            Object present = values.get(key);
            if (present != null) {
                return present;
            }
        }
        put(key, value);
        return null;
    }

    @Override
    public synchronized Object remove(String key) {
        return values.remove(key);
    }
}
