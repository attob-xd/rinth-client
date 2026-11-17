package net.fabricmc.loader.impl;

import java.util.Optional;
import java.util.Set;

/**
 * Switching built-in mods on and off without a rebuild, for Mod Menu's switches (fabric/modtoggles):
 * fabric/runtime's ModSwitchState, handed to {@link FabricLoaderImpl#setSwitches} as the game
 * starts. A switch takes effect when the page reloads; this session keeps the mods it started with.
 */
public interface ModSwitchControl {

    /** Whether {@code id} has a switch: a mod of its own jar that nothing kept on needs. */
    boolean switchable(String id);

    /** Whether {@code id} is on for the next session, as the switches stand. */
    boolean isOn(String id);

    /**
     * Switches {@code id} on or off for the next session, with the mods that go with it. Returns the
     * mods the next session has off, or nothing if that combination isn't built in or couldn't be saved.
     */
    Optional<Set<String>> switchTo(String id, boolean on);

    /** Whether the switches differ from what this session started with: a reload applies them. */
    boolean needsReload();

    /** Whether Mod Menu's list leaves {@code id} out: a library, or a mod the build hid. It still runs. */
    default boolean hidden(String id) {
        return false;
    }
}
