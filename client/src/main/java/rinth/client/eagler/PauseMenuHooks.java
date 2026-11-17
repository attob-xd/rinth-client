package rinth.client.eagler;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.Screen;
import rinth.client.settings.RinthSettingsScreen;
import rinth.client.ui.Icons;
import rinth.client.ui.Ui;

/**
 * What Rinth adds to the pause menu: Rinth Settings' button, an Eagler server's own buttons and
 * icons, and voice chat.
 */
public final class PauseMenuHooks {

    private PauseMenuHooks() {}

    /**
     * {@code PauseScreen}, in the button grid below the row of icon buttons: Rinth Settings, with
     * Rinth's badge (everything else of Rinth's is behind it), then the server's own buttons.
     */
    public static void addButtons(GridLayout.RowHelper row) {
        row.addChild(
                Ui.menuButton(RinthSettingsScreen.TITLE, Icons.RINTH, 204, () -> {
                            Minecraft minecraft = Minecraft.getInstance();
                            minecraft.gui.setScreen(new RinthSettingsScreen(minecraft.gui.screen()));
                        })
                        .centered(),
                2);
        ServerPauseMenu.addButtons(row);
    }

    /**
     * {@code PauseScreen.getCustomAdditions}: whether the server sent pause menu buttons of its
     * own, which take the Server Links button's place.
     */
    public static boolean replacesServerLinks() {
        return ServerPauseMenu.active();
    }

    /** {@code PauseScreen}, after its widgets: the voice panel, and the icons over the buttons. */
    public static void addOverlays(
            Screen screen, Consumer<AbstractWidget> addWidget, Consumer<Renderable> addRenderable) {
        ServerPauseMenu.addIcons(screen, addRenderable);
        VoicePanel voice = VoicePanel.create(screen);
        if (voice != null) {
            addWidget.accept(voice);
        }
    }
}
