package rinth.client.look;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import rinth.client.relay.RelayScreen;
import rinth.client.ui.Look;
import rinth.client.ui.ScreenEdits;

/**
 * Puts the client's look on: the skin ({@link Look}) and the edits to the game's screens, or, in
 * the game's own look, only what Rinth still needs on them ({@link VanillaLook}).
 */
public final class Looks {

    private static boolean started;

    private Looks() {}

    /** From the first client tick, or the title screen's first layout if that comes first. */
    public static void start(Minecraft minecraft) {
        if (started) {
            return;
        }
        started = true;
        Look.start(minecraft);
        if (Look.vanilla()) {
            ScreenEdits.edit(TitleScreen.class, VanillaLook::title);
        } else {
            ScreenEdits.edit(PauseScreen.class, PauseLook::edit);
        }
        ScreenEdits.edit(JoinMultiplayerScreen.class, RelayScreen::fitRow);
    }
}
