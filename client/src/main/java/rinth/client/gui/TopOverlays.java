package rinth.client.gui;

import rinth.client.profiler.ProfilerOverlay;
import rinth.client.recorder.ScreenRecorder;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * What Rinth draws over everything, screens included, after the game's debug overlay: the
 * profiler's overlay and the screen recorder's REC (ADR 0062). Render thread.
 */
public final class TopOverlays {

    private TopOverlays() {}

    /** Hook ({@code Gui}, after the debug overlay). */
    public static void extract(GuiGraphicsExtractor graphics) {
        ProfilerOverlay.extract(graphics);
        ScreenRecorder.extract(graphics);
    }
}
