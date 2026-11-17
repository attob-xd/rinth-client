package rinth.client.modules;

import com.mojang.logging.LogUtils;
import rinth.client.RinthKeys;
import rinth.client.online.RinthOnline;
import rinth.client.ui.SettingControls;
import rinth.framework.Module;
import rinth.framework.ModuleSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The client's module system (ADR 0046): made with the build's features on the first tick,
 * its active profile loaded from the game directory. Runs the modules' tick and keybinds, fires
 * join and leave as the player's connection comes and goes, and opens the module menu. Game thread.
 */
public final class RinthModules {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static @Nullable ModuleSystem system;
    private static @Nullable GameKeys keys;
    /** The connection the player was in a world on, as of the last tick. */
    private static @Nullable ClientPacketListener world;
    /** The page's {@code ?modules=}: module ids turned on at start, or null to leave the profile's. */
    private static @Nullable List<String> startOn;
    /** Whether {@link #startOn} is the whole set ({@code ?moduleset=}): every other module is turned off. */
    private static boolean startExactly;

    private static @Nullable Consumer<List<String>> enabledListener;
    private static @Nullable List<String> lastEnabled;
    private static int enabledSettle = -1;

    private RinthModules() {}

    /** The module system, or null before the first tick. */
    public static @Nullable ModuleSystem current() {
        return system;
    }

    /** The module system, made and loaded if this is the first time it's asked for. */
    public static ModuleSystem system() {
        ModuleSystem current = system;
        if (current == null) {
            current = new ModuleSystem(
                    Features.all(),
                    new ModuleStorage(Minecraft.getInstance().gameDirectory.toPath()),
                    (message, error) -> {
                        if (error == null) {
                            LOGGER.warn(message);
                        } else {
                            LOGGER.warn(message, error);
                        }
                    },
                    new ModuleGame(),
                    RinthOnline.service());
            current.load();
            List<String> on = startOn;
            if (on != null) {
                for (Module module : current.modules()) {
                    if (module.isAlwaysOn()) {
                        continue;
                    }
                    boolean listed = on.contains("all") || on.contains(module.id());
                    if (listed || startExactly) {
                        module.setEnabled(listed);
                    }
                }
            }
            system = current;
            keys = new GameKeys(current);
            LOGGER.info("Modules: {} loaded, profile {}", current.modules().size(), current.activeProfile());
        }
        return current;
    }

    /**
     * Development: modules to turn on when the system is made, the page's {@code ?modules=a,b} ("all"
     * for every one), so the benchmark measures them. Before the first tick.
     */
    public static void startWith(List<String> ids) {
        startOn = List.copyOf(ids);
    }

    /**
     * A launcher instance's modules (the page's {@code ?moduleset=a,b}): exactly these on when the system
     * is made, every other one off. Before the first tick.
     */
    public static void startExactly(List<String> ids) {
        startOn = List.copyOf(ids);
        startExactly = true;
    }

    /**
     * Told the modules on (not the always-on ones) once a change has settled for a second: the page
     * passes it on to the launcher's instance, so starting from the launcher keeps what was switched in
     * the game.
     */
    public static void onEnabledChange(Consumer<List<String>> listener) {
        enabledListener = listener;
    }

    private static void trackEnabled(ModuleSystem modules) {
        Consumer<List<String>> listener = enabledListener;
        if (listener == null) {
            return;
        }
        List<String> now = new ArrayList<>();
        for (Module module : modules.modules()) {
            if (!module.isAlwaysOn() && module.isEnabled()) {
                now.add(module.id());
            }
        }
        if (lastEnabled == null) {
            lastEnabled = now;
        } else if (!now.equals(lastEnabled)) {
            lastEnabled = now;
            enabledSettle = 20;
        } else if (enabledSettle > 0 && --enabledSettle == 0) {
            listener.accept(List.copyOf(now));
        }
    }

    /** The game's names for module keys, for the settings screens. */
    static GameKeys keys() {
        system();
        GameKeys current = keys;
        if (current == null) {
            throw new IllegalStateException("No module keys");
        }
        return current;
    }

    /** From {@code RinthTicks}, once a client tick before the game's keybinds. */
    public static void tick(Minecraft minecraft) {
        ModuleSystem modules = system();
        ModuleGame.newTick();
        trackWorld(minecraft, modules);
        modules.tick(keys());
        trackEnabled(modules);
        ModuleEffects.tick(minecraft, modules);
        MapTerrain.tick(minecraft);
        ModuleHands.tick(minecraft);
        MapMemory.tick();
        while (RinthKeys.MODULE_MENU.consumeClick()) {
            if (minecraft.gui.screen() == null) {
                openMenu(null);
            }
        }
    }

    /** Opens the module menu over {@code parent} (null for the game). */
    public static void openMenu(@Nullable Screen parent) {
        Minecraft.getInstance().gui.setScreen(new ModuleListScreen(parent, system()));
    }

    /** Opens {@code module}'s settings over {@code parent}. */
    public static void openSettings(@Nullable Screen parent, Module module) {
        Minecraft.getInstance().gui.setScreen(new ModuleSettingsScreen(parent, system(), module));
    }

    /** Opens the HUD editor over {@code parent}. */
    public static void openHudEditor(@Nullable Screen parent) {
        Minecraft.getInstance().gui.setScreen(new HudEditorScreen(parent, system()));
    }

    /**
     * The game is stopping, or its page was hidden (the tab may be closed next, and nothing runs
     * after): a change still waiting out its second is saved now.
     */
    public static void saveNow() {
        ModuleSystem current = system;
        if (current != null) {
            current.flush();
        }
    }

    /**
     * Hook ({@code LevelStorageAccess.deleteLevel}, once the world's folder is gone): its waypoints
     * and explored map go too, so a new world made with the same folder name starts with none.
     */
    public static void worldDeleted(String folder) {
        String name = ModuleGame.localWorld(folder);
        system().waypoints().delete(name);
        MapMemory.delete(Minecraft.getInstance().gameDirectory.toPath(), name);
    }

    /** The keyboard layout's key names changed (the page learned them): the HUD's are made again. */
    public static void keymapChanged() {
        ModuleGame.keymapChanged();
    }

    /** Opens the profiles screen over {@code parent}. */
    public static void openProfiles(@Nullable Screen parent) {
        Minecraft.getInstance().gui.setScreen(new ProfilesScreen(parent, system()));
    }

    /**
     * {@code KeyboardHandler.keyPress}: whether {@code screen} has a module keybind waiting for a key,
     * so the game's own keys (F2's screenshot, F11's fullscreen, F1, F3) go to the bind instead, as
     * they do for the game's Controls screen.
     */
    public static boolean capturingKey(@Nullable Screen screen) {
        return screen instanceof SettingControls.KeyCapture capture && capture.capturing() != null;
    }

    /**
     * Join and leave follow the connection, not the level: a dimension change keeps the connection,
     * a transfer to another server replaces it.
     */
    @SuppressWarnings("ReferenceEquality") // The same connection object, not an equal one.
    private static void trackWorld(Minecraft minecraft, ModuleSystem modules) {
        ClientPacketListener connection = minecraft.level == null ? null : minecraft.getConnection();
        ClientPacketListener last = world;
        if (connection == last) {
            return;
        }
        world = connection;
        if (last != null) {
            GameParts.leave();
            modules.events().fireLeave();
            // The last change before leaving shouldn't wait out its second: the tab may close next.
            modules.flush();
            modules.waypoints().close();
            MapTerrain.leave();
            MapMemory.close();
            ModuleSky.leave();
            ModuleEffects.leave();
        }
        if (connection != null) {
            // Before the join event: a module joining may read the world's waypoints.
            String name = modules.game().world();
            if (name != null) {
                modules.waypoints().open(name);
                MapMemory.open(minecraft.gameDirectory.toPath(), name);
            }
            ServerData server = minecraft.getCurrentServer();
            modules.events().fireJoin(server == null || minecraft.isLocalServer() ? "" : server.ip);
        }
    }
}
