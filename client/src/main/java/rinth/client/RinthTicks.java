package rinth.client;

import rinth.client.cosmetics.Cosmetics;
import rinth.client.eagler.EaglerVoice;
import rinth.client.legacy.LegacyRules;
import rinth.client.look.Looks;
import rinth.client.modules.RinthModules;
import rinth.client.online.RinthOnline;
import rinth.client.profiler.Profiling;
import rinth.client.recorder.ScreenRecorder;
import rinth.client.render.mesher.ChestBaking;
import rinth.client.singleplayer.QuickWorlds;
import rinth.client.ui.Logo;
import net.minecraft.client.Minecraft;

/**
 * What Rinth does once a client tick, from one hook in {@code Minecraft.tick} (after the GUI's
 * tick, before key bindings): the legacy behaviour layer follows the server's version, voice
 * chat runs, the profiler overlay's key is read, the benchmark's quick worlds are made and left, and
 * the Rinth account's connection runs, and the modules tick (their keybinds, then their listeners). The first tick also puts on the client's
 * look. Game thread.
 */
public final class RinthTicks {

    private RinthTicks() {}

    public static void tick(Minecraft minecraft) {
        Looks.start(minecraft);
        // The mark, and with it the page's icon, as soon as the game's resources have the carrot.
        Logo.tick();
        // First: the rest of the tick moves the player by the rules of the server it's on.
        LegacyRules.update(minecraft);
        // Push to talk works with no screen open, as other keys do, and while chat is closed.
        boolean pushToTalk = minecraft.gui.screen() == null && RinthKeys.PUSH_TO_TALK.isDown();
        EaglerVoice.tick(minecraft, pushToTalk);
        Profiling.tick();
        ScreenRecorder.tick(minecraft);
        ChestBaking.tick(minecraft.level);
        QuickWorlds.tick(minecraft);
        // Before the modules: they read who's an Rinth player (ADR 0058).
        RinthOnline.tick(minecraft);
        // Cosmetics read the service's emotes and what Rinth players wear (ADR 0061).
        Cosmetics.tick(minecraft);
        RinthModules.tick(minecraft);
    }
}
