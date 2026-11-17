package rinth.client.online;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Rinth chat's look: Minecraft's own square boxes, edged in black and lit along their top and left,
 * as its buttons and containers are, instead of the theme's rounded glass.
 */
final class Blocky {

    /** The window's face: the dark grey of the game's containers when they're dark. */
    static final int WINDOW = 0xF0202020;

    static final int SUNKEN = 0xC0101010;
    static final int BUTTON = 0xFF6F6F6F;
    static final int BUTTON_HOT = 0xFF7F8FBF;
    static final int SELECTED = 0xFF404040;
    static final int HOVER = 0xFF303030;

    private Blocky() {}

    /** A raised box: a black edge, then a face lit along its top and left and shaded along its bottom and right. */
    static void box(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int face) {
        if (width < 3 || height < 3) {
            graphics.fill(x, y, x + width, y + height, face);
            return;
        }
        graphics.fill(x, y, x + width, y + height, 0xFF000000);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, face);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, lighter(face));
        graphics.fill(x + 1, y + 2, x + 2, y + height - 1, lighter(face));
        graphics.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, darker(face));
        graphics.fill(x + width - 2, y + 2, x + width - 1, y + height - 2, darker(face));
    }

    /** A sunken well: shaded along its top and left, lit along its bottom and right, as a text field's. */
    static void well(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int face) {
        graphics.fill(x, y, x + width, y + height, face);
        graphics.fill(x, y, x + width, y + 1, 0xFF000000);
        graphics.fill(x, y + 1, x + 1, y + height, 0xFF000000);
        graphics.fill(x, y + height - 1, x + width, y + height, 0xFF5A5A5A);
        graphics.fill(x + width - 1, y, x + width, y + height - 1, 0xFF5A5A5A);
    }

    /** A list row picked or under the pointer: a flat block, white-edged when picked, as the server list's. */
    static void row(GuiGraphicsExtractor graphics, int x, int y, int width, int height, boolean picked) {
        if (picked) {
            graphics.fill(x, y, x + width, y + height, 0xFFFFFFFF);
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF000000);
        } else {
            graphics.fill(x, y, x + width, y + height, 0xFF808080);
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, HOVER);
        }
    }

    /** A square presence block, ringed in black. */
    static void dot(GuiGraphicsExtractor graphics, int x, int y, int size, int color) {
        graphics.fill(x, y, x + size, y + size, 0xFF000000);
        graphics.fill(x + 1, y + 1, x + size - 1, y + size - 1, color);
    }

    private static int lighter(int argb) {
        return scale(argb, 1.45F);
    }

    private static int darker(int argb) {
        return scale(argb, 0.55F);
    }

    private static int scale(int argb, float by) {
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * by));
        int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * by));
        int b = Math.min(255, (int) ((argb & 0xFF) * by));
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }
}
