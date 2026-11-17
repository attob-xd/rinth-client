package rinth.client.look;

import rinth.client.settings.RinthSettingsScreen;
import rinth.client.ui.EditedScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * The game's own look ({@code Look.vanilla}): its screens as the game lays them out and draws
 * them, with what Rinth still needs on them. The pause menu keeps Rinth Settings' button
 * ({@code PauseMenuHooks}), and the title screen keeps the player and Edit Profile ({@code
 * TitleProfile}); here, the title screen's Realms button becomes Rinth Settings', in its place and
 * in the game's style. Realms can't be reached from the client, and the title screen has no other
 * way into Rinth Settings. Render thread.
 */
final class VanillaLook {

    private VanillaLook() {}

    static void title(EditedScreen<TitleScreen> edited) {
        AbstractWidget realms = edited.find("menu.online");
        if (realms == null) {
            return;
        }
        // Hidden, not removed: the game lays a title screen it shows again out without making its
        // widgets anew, and this edit must find the Realms button then too to put the button back.
        realms.visible = false;
        realms.active = false;
        Minecraft minecraft = Minecraft.getInstance();
        edited.add(Button.builder(
                        RinthSettingsScreen.TITLE,
                        button -> minecraft.gui.setScreen(new RinthSettingsScreen(minecraft.gui.screen())))
                .bounds(realms.getX(), realms.getY(), realms.getWidth(), realms.getHeight())
                .build());
    }
}
