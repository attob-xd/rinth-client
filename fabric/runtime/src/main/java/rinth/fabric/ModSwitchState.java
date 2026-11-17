package rinth.fabric;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.ModSwitchControl;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import rinth.fabric.generated.FabricModTable;

/**
 * Which built-in mods are switched off, so Mod Menu's switches need no rebuild. Every mod is built
 * into the one client, and each switchable mod's mixin code asks {@link #on} first (tools/fabricmods'
 * {@code ModGuards}): off, the game's own code runs there instead. Any combination works, however many
 * mods there are. It's read once, from {@value #FILE} in the game directory, and stays for the session;
 * a switch saves the next session's choice there, and the page reloads to use it.
 *
 * <p>The game directory is the {@value #DIRECTORY_PROPERTY} system property, which the browser's
 * entry points set before the game starts (the page and the world's server worker, so both run the
 * same combination). Without it, or the file, every mod is on.
 */
public final class ModSwitchState {

    /** The game directory, for {@value #FILE}. */
    public static final String DIRECTORY_PROPERTY = "rinth.gameDirectory";

    /** The mods switched off, one id a line, in the game directory. */
    public static final String FILE = "config/rinth-mod-switches.txt";

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The switches for Mod Menu, through the loader ({@code FabricLoaderImpl.switches()}). */
    public static final ModSwitchControl CONTROL = new ModSwitchControl() {
        @Override
        public boolean switchable(String id) {
            return ModSwitchState.switchable(id);
        }

        @Override
        public boolean isOn(String id) {
            return !saved().contains(id);
        }

        @Override
        public Optional<Set<String>> switchTo(String id, boolean on) {
            return Optional.ofNullable(ModSwitchState.switchTo(id, on));
        }

        @Override
        public boolean needsReload() {
            return ModSwitchState.needsReload();
        }

        @Override
        public boolean hidden(String id) {
            return FabricModTable.hidden().contains(id);
        }
    };

    private static boolean @Nullable [] running;
    private static Set<String> off = Set.of();
    private static @Nullable Consumer<Set<String>> saveListener;

    private ModSwitchState() {}

    /**
     * Whether the mod at {@code mod} in {@code FabricModTable.mods()} runs this session. Called by every
     * switchable mod's mixin code, often: one array read once started; never throws.
     */
    public static boolean on(int mod) {
        boolean[] current = running;
        if (current == null) {
            current = start();
        }
        return mod < 0 || mod >= current.length || current[mod];
    }

    private static synchronized boolean[] start() {
        boolean[] current = running;
        if (current != null) {
            return current;
        }
        // What the file has off, with what goes off with each (an edited file can't half-switch a mod).
        Set<String> chosen = new TreeSet<>();
        for (String id : saved()) {
            if (switchable(id)) {
                chosen.addAll(FabricModTable.dependents().getOrDefault(id, List.of(id)));
            }
        }
        List<ModContainerImpl> mods = FabricModTable.mods();
        current = new boolean[mods.size()];
        for (int i = 0; i < current.length; i++) {
            current[i] = !chosen.contains(mods.get(i).getMetadata().getId());
        }
        off = Set.copyOf(chosen);
        running = current;
        return current;
    }

    /**
     * Told the mods off after each switch is saved: the page passes it on to the launcher's instance, so
     * starting from the launcher keeps what was switched in the game.
     */
    public static void onSave(Consumer<Set<String>> listener) {
        saveListener = listener;
    }

    /** The mods off this session. */
    public static Set<String> off() {
        if (running == null) {
            start();
        }
        return off;
    }

    /** Whether Mod Menu has a switch for {@code id}. */
    public static boolean switchable(String id) {
        return FabricModTable.switchable().contains(id);
    }

    /** The mods {@value #FILE} has off: this session's, or the next one's after a switch. */
    public static Set<String> saved() {
        Path file = file();
        Set<String> ids = new TreeSet<>();
        if (file == null || !Files.isRegularFile(file)) {
            return ids;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String id = line.trim();
                if (!id.isEmpty() && !id.startsWith("#")) {
                    ids.add(id);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Couldn't read {}", file, e);
        }
        return ids;
    }

    /** Whether the next session has a different combination from this one: a reload applies it. */
    public static boolean needsReload() {
        return !saved().equals(off());
    }

    /**
     * Switches {@code id} on or off for the next session: off takes the mods that need it with it,
     * on brings the switchable mods it needs. Returns the mods the next session has off, or null if
     * {@code id} has no switch or the choice can't be saved.
     */
    public static @Nullable Set<String> switchTo(String id, boolean on) {
        if (!switchable(id)) {
            return null;
        }
        Set<String> next = saved();
        if (on) {
            next.removeAll(FabricModTable.requirements().getOrDefault(id, List.of(id)));
        } else {
            next.addAll(FabricModTable.dependents().getOrDefault(id, List.of(id)));
        }
        Path file = file();
        if (file == null) {
            return null;
        }
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# Mods switched off in Mod Menu; the game reads this when it starts.");
            lines.addAll(next);
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Couldn't save the mod switches to {}", file, e);
            return null;
        }
        Consumer<Set<String>> listener = saveListener;
        if (listener != null) {
            listener.accept(Set.copyOf(next));
        }
        return next;
    }

    private static @Nullable Path file() {
        String directory = System.getProperty(DIRECTORY_PROPERTY);
        return directory == null ? null : Path.of(directory).resolve(FILE);
    }
}
