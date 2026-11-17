package rinth.client.ui;

import com.mojang.logging.LogUtils;
import rinth.client.RinthBuild;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The client's look: Rinth's skin over the game's GUI, and the player's {@link GuiPreset},
 * kept in {@code rinth/look.txt}. Potato until the player picks Normal: new installs start on the
 * Potato graphics preset too, and the GUI shouldn't spend what the world saved.
 *
 * <p>Or the game's own look ({@link #vanilla}): Minecraft's menus, font, panorama and loading
 * screen, with Rinth's own screens kept. The build picks which a player starts with ({@code
 * rinth.look}), Rinth Settings keeps the player's choice in the same file, and the page's {@code
 * ?look=} picks one for a single run. It's settled once a run, at the first ask: the loading screen
 * and the fonts need it before the first tick, and the skin and screen edits aren't swapped live.
 *
 * <p>Render and game thread (one thread on both platforms); {@link #vanilla} from any thread.
 */
public final class Look {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE = "look.txt";
    private static final String VANILLA = "vanilla";
    private static final String RINTH = "rinth";

    /** The menu blur's radius in Normal when the game's own blur option is lower (Potato graphics sets 0). */
    private static final int NORMAL_BLUR = 5;

    private static GuiPreset preset = GuiPreset.POTATO;
    private static @Nullable Path file;
    private static boolean loaded;
    private static boolean started;
    /** Whether a screen blurs its backdrop in either preset (the HUD editor), until it closes. */
    private static boolean frosted;

    /** The page's {@code ?look=} for this run, never saved; null without one. */
    private static @Nullable String forced;
    /** The player's look for the runs to come ({@code look=} in the file); null for the build's. */
    private static @Nullable String chosen;
    /** This run's look once settled: true for the game's own. Read every frame, so no lock. */
    private static volatile @Nullable Boolean vanilla;

    private Look() {}

    /** From the first client tick: reads the saved preset and puts Rinth's skin on the GUI, unless the game's look is on. */
    public static void start(Minecraft minecraft) {
        if (started) {
            return;
        }
        started = true;
        load();
        if (vanilla()) {
            // The client's own screens follow it: Minecraft's classic colors, square panels.
            Theme.restore();
            LOGGER.info("Look: the game's own");
            return;
        }
        Skins.use(FrostSkin.INSTANCE);
        LOGGER.info("Look: Frost, {} preset", preset.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Whether this run draws the game's own look instead of Rinth's: the page's {@code ?look=},
     * else the player's choice, else the build's.
     */
    public static boolean vanilla() {
        Boolean settled = vanilla;
        return settled != null ? settled : settle();
    }

    private static synchronized boolean settle() {
        Boolean settled = vanilla;
        if (settled == null) {
            if (forced == null && !load()) {
                // Before the game: nothing to settle on yet but the build's.
                return VANILLA.equals(RinthBuild.LOOK);
            }
            settled = VANILLA.equals(forced != null ? forced : next());
            vanilla = settled;
        }
        return settled;
    }

    /** Whether the game's own look is what the next run starts in (Rinth Settings' switch). */
    public static synchronized boolean vanillaNext() {
        return VANILLA.equals(next());
    }

    /** Starts the next runs in the game's own look, or in Rinth's, and saves it. Not this run's. */
    public static synchronized void chooseVanilla(boolean on) {
        load();
        chosen = on ? VANILLA : RINTH;
        save();
    }

    /**
     * The page's {@code ?look=vanilla} or {@code ?look=rinth}: this run's look, before the game
     * starts. Not saved.
     */
    public static synchronized void force(String look) {
        String value = look.trim().toLowerCase(Locale.ROOT);
        if (value.equals(VANILLA) || value.equals(RINTH)) {
            forced = value;
        } else {
            LOGGER.warn("Unknown look \"{}\": vanilla or rinth", look);
        }
    }

    public static GuiPreset preset() {
        return preset;
    }

    /** Whether the GUI's costly effects are on: blur, animation, light sweeps. */
    public static boolean normal() {
        return preset == GuiPreset.NORMAL;
    }

    /** Switches to {@code chosen} from the next frame, and saves it. */
    public static synchronized void set(GuiPreset chosen) {
        preset = chosen;
        Motion.reset();
        save();
    }

    /**
     * Blurs the backdrop in Potato too while {@code on}: the HUD editor's, where the blur is what
     * sets the HUD apart from the world. It keeps the cost down itself ({@code BackdropCache}).
     */
    public static void frost(boolean on) {
        frosted = on;
    }

    /**
     * {@code GameRenderer}'s menu blur radius: the game's option, or at least Normal's own when the
     * Normal preset (or a {@link #frost frosted} screen) blurs the backdrop: Potato graphics turns
     * the game's blur off. The game's look keeps the game's option, but for a frosted screen.
     */
    public static int blurRadius(int option) {
        return frosted || (normal() && !vanilla()) ? Math.max(option, NORMAL_BLUR) : option;
    }

    /** The look the next run starts in: the player's choice, else the build's. */
    private static String next() {
        load();
        return chosen != null ? chosen : RinthBuild.LOOK;
    }

    /** Reads {@code look.txt} once the game directory is known; whether it is. */
    private static synchronized boolean load() {
        if (loaded) {
            return true;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }
        loaded = true;
        Path path = minecraft.gameDirectory.toPath().resolve("rinth").resolve(FILE);
        file = path;
        if (!Files.isRegularFile(path)) {
            return true;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                int split = line.indexOf('=');
                if (split <= 0) {
                    continue;
                }
                String key = line.substring(0, split).trim();
                String value = line.substring(split + 1).trim().toLowerCase(Locale.ROOT);
                if (key.equals("preset")) {
                    try {
                        preset = GuiPreset.valueOf(value.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        // A hand-edited line; keep the default.
                    }
                } else if (key.equals("look") && (value.equals(VANILLA) || value.equals(RINTH))) {
                    chosen = value;
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Couldn't read {}: {}", path, e.toString());
        }
        return true;
    }

    private static void save() {
        Path path = file;
        if (path == null) {
            return;
        }
        String text = "preset=" + preset.name().toLowerCase(Locale.ROOT) + "\n"
                + (chosen != null ? "look=" + chosen + "\n" : "");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Couldn't save {}: {}", path, e.toString());
        }
    }
}
