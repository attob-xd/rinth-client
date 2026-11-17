package rinth.modtoggles;

import rinth.client.platform.ClientPlatform;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModSwitchControl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * The ON/OFF switch drawn at the right of a mod's row in Mod Menu. Every mod is built into the one
 * client and its mixin code asks whether it's on (tools/fabricmods' ModGuards), so a switch only
 * saves the choice ({@link ModSwitchControl}) and the page reloads to start with it: no rebuild, and
 * any combination. Mods without a switch: Mod Menu and this mod (they host the switches), what they need,
 * and mods inside another mod's jar.
 *
 * <p>Render thread.
 */
public final class ModSwitches {

    private static final int WIDTH = 22;
    private static final int HEIGHT = 11;

    private ModSwitches() {}

    private static Optional<ModSwitchControl> control() {
        return FabricLoaderImpl.INSTANCE.switches();
    }

    /** Draws {@code id}'s switch in the row whose content ends at {@code right}, if it has one. */
    public static void draw(
            GuiGraphicsExtractor graphics, String id, int right, int top, int height, int mouseX, int mouseY) {
        ModSwitchControl control = control().orElse(null);
        if (control == null || !control.switchable(id)) {
            return;
        }
        boolean on = control.isOn(id);
        boolean running = FabricLoaderImpl.INSTANCE.isModLoaded(id);
        int x = right - WIDTH - 2;
        int y = top + (height - HEIGHT) / 2;
        boolean hot = inside(mouseX, mouseY, x, y);
        // A slot framed in black, green while on, with a stone knob lit along its top and left; the
        // knob is amber while the switch waits for a reload.
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, hot ? 0xFFFFFFFF : 0xFF000000);
        graphics.fill(x + 1, y + 1, x + WIDTH - 1, y + HEIGHT - 1, on ? 0xFF3C8C2E : 0xFF4A4A4A);
        int knob = HEIGHT - 2;
        int knobX = on ? x + WIDTH - 1 - knob : x + 1;
        graphics.fill(knobX, y + 1, knobX + knob, y + HEIGHT - 1, on != running ? 0xFFE0A030 : 0xFFB5B5B5);
        graphics.fill(knobX, y + 1, knobX + knob, y + 2, 0xFFE8E8E8);
        graphics.fill(knobX, y + 2, knobX + 1, y + HEIGHT - 1, 0xFFE8E8E8);
        graphics.fill(knobX, y + HEIGHT - 2, knobX + knob, y + HEIGHT - 1, 0xFF5A5A5A);
        if (hot) {
            graphics.setTooltipForNextFrame(
                    Minecraft.getInstance().font.split(tooltip(on, running), 200), mouseX, mouseY);
        }
    }

    /** Whether a click at ({@code mouseX}, {@code mouseY}) hit {@code id}'s switch; if it did, switches it. */
    public static boolean click(String id, int right, int top, int height, double mouseX, double mouseY) {
        ModSwitchControl control = control().orElse(null);
        if (control == null || !control.switchable(id)) {
            return false;
        }
        int x = right - WIDTH - 2;
        int y = top + (height - HEIGHT) / 2;
        if (!inside(mouseX, mouseY, x, y)) {
            return false;
        }
        boolean on = control.isOn(id);
        Optional<Set<String>> off = control.switchTo(id, !on);
        if (off.isEmpty()) {
            banner("Couldn't switch " + id + ": that combination of mods isn't built in. Rebuild the game with it.");
        } else if (control.needsReload()) {
            banner(
                    off.get().isEmpty()
                            ? "Every mod comes on when you reload."
                            : "Off when you reload: " + String.join(", ", off.get()) + ".");
        } else {
            banner("");
        }
        return true;
    }

    /** The page's banner with a Reload button ({@code jsmods.ts}); empty takes it down. */
    private static void banner(String text) {
        ClientPlatform.get().shell().callPage("rinthModsBanner", text);
    }

    private static Component tooltip(boolean on, boolean running) {
        if (on != running) {
            return Component.literal((on ? "On" : "Off") + " when you reload the page. Click to switch it back.");
        }
        return Component.literal(
                (on ? "On. Click to turn it off" : "Off. Click to turn it on") + ": it takes effect when you reload.");
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + HEIGHT;
    }
}
