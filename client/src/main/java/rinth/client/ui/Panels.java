package rinth.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The look's panels and the color arithmetic its parts share: glass panels for the client's own
 * screens and cards, drawn as the skin draws the game's popups. Render thread; nothing allocates.
 */
public final class Panels {

    private Panels() {}

    /**
     * Whether the client's screens draw in Minecraft's classic look, as the game's own look does:
     * square black panels framed like the game's lists, and switches like little stone buttons.
     */
    public static boolean classic() {
        return Look.vanilla();
    }

    /** A panel: glass with an edge and, in Normal, a soft shadow below. */
    public static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, float fade) {
        if (classic()) {
            classicBox(graphics, x, y, width, height, 0x90000000, 0xFF000000, fade);
            return;
        }
        boolean normal = Look.normal();
        RoundRects.box(
                graphics,
                x,
                y,
                width,
                height,
                Theme.PANEL_RADIUS,
                alpha(normal ? Theme.GLASS : Theme.GLASS_SOLID, fade),
                alpha(normal ? Theme.GLASS_EDGE : Theme.GLASS_EDGE_SOLID, fade),
                normal ? 6 : 0,
                2,
                alpha(0x40081420, fade));
    }

    /**
     * Blurs everything drawn before this stratum (the world, the HUD), at most once a frame: a
     * screen that frosts its backdrop in either preset ({@link Look#frost}).
     */
    public static void blurBehind(GuiGraphicsExtractor graphics) {
        FrostSkin.blur(graphics);
    }

    /** {@code argb} with its alpha scaled by {@code fade}. */
    public static int alpha(int argb, float fade) {
        if (fade >= 1.0F) {
            return argb;
        }
        int a = Math.round((argb >>> 24) * Math.max(0.0F, fade));
        return a << 24 | (argb & 0xFFFFFF);
    }

    /** The color {@code t} of the way from {@code from} to {@code to}, each channel. */
    public static int mix(int from, int to, float t) {
        if (t <= 0) {
            return from;
        }
        if (t >= 1) {
            return to;
        }
        int a = channel(from >>> 24, to >>> 24, t);
        int r = channel(from >> 16 & 0xFF, to >> 16 & 0xFF, t);
        int g = channel(from >> 8 & 0xFF, to >> 8 & 0xFF, t);
        int b = channel(from & 0xFF, to & 0xFF, t);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static int channel(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }

    /** A switch's size in GUI units. */
    public static final int SWITCH_WIDTH = 18;

    public static final int SWITCH_HEIGHT = 10;

    /**
     * An on/off switch at ({@code x}, {@code y}): an ice-blue track with a dark knob at the right
     * when on, a faint one with a grey knob at the left when off. In Normal the knob slides.
     */
    public static void toggle(GuiGraphicsExtractor graphics, int x, int y, boolean on, float fade) {
        if (classic()) {
            classicToggle(graphics, x, y, on, fade);
            return;
        }
        float t = Motion.ease(Motion.key(x, y, SWITCH_WIDTH, -SWITCH_HEIGHT), on ? 1 : 0);
        int track = mix(0x2EFFFFFF, Theme.ACCENT, t);
        RoundRects.fill(graphics, x, y, SWITCH_WIDTH, SWITCH_HEIGHT, SWITCH_HEIGHT / 2, alpha(track, fade));
        int knob = SWITCH_HEIGHT - 2;
        int knobX = x + 1 + Math.round(t * (SWITCH_WIDTH - 2 - knob));
        RoundRects.fill(graphics, knobX, y + 1, knob, knob, knob / 2, alpha(mix(0xFFC9D1D9, 0xFF0D2233, t), fade));
    }

    /**
     * A card: a button-like glass tile that lights toward the pointer, edged in the accent while
     * {@code on}; in Normal, a glow under the pointer.
     */
    public static void card(
            GuiGraphicsExtractor graphics, int x, int y, int width, int height, boolean hot, boolean on, float fade) {
        if (classic()) {
            // As the game's world and server lists frame an entry: white under the pointer.
            int edge = hot ? 0xFFFFFFFF : on ? 0xFF8B8B8B : 0xFF3A3A3A;
            classicBox(graphics, x, y, width, height, hot ? 0xB0000000 : 0x80000000, edge, fade);
            return;
        }
        float t = Motion.ease(Motion.key(x, y, width, height), hot ? 1 : 0);
        boolean normal = Look.normal();
        int edge = on ? Theme.ACCENT & 0x8CFFFFFF : normal ? Theme.GLASS_EDGE : Theme.GLASS_EDGE_SOLID;
        if (!normal && hot) {
            edge = Theme.ACCENT;
        }
        RoundRects.box(
                graphics,
                x,
                y,
                width,
                height,
                Theme.RADIUS + 1,
                alpha(mix(Theme.BUTTON, Theme.BUTTON_HOT, t * 0.7F), fade),
                alpha(edge, fade),
                normal && t > 0 ? Theme.GLOW : 0,
                0,
                alpha(Theme.ACCENT, 0.22F * t * fade));
    }

    /**
     * A dark panel, for what must read over a bright world in either preset: a container's slots and
     * labels. Edged and shadowed like {@link #panel}.
     */
    public static void darkPanel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, float fade) {
        if (classic()) {
            classicBox(graphics, x, y, width, height, 0xC0000000, 0xFF000000, fade);
            return;
        }
        boolean normal = Look.normal();
        RoundRects.box(
                graphics,
                x,
                y,
                width,
                height,
                Theme.PANEL_RADIUS,
                alpha(normal ? 0xC21A2129 : Theme.GLASS_SOLID, fade),
                alpha(normal ? Theme.GLASS_EDGE : Theme.GLASS_EDGE_SOLID, fade),
                normal ? 6 : 0,
                2,
                alpha(0x40081420, fade));
    }

    /** A square box in the classic look: {@code fill} inside a one-pixel {@code edge}. */
    private static void classicBox(
            GuiGraphicsExtractor graphics, int x, int y, int width, int height, int fill, int edge, float fade) {
        if (width <= 2 || height <= 2) {
            return;
        }
        int right = x + width;
        int bottom = y + height;
        graphics.fill(x + 1, y + 1, right - 1, bottom - 1, alpha(fill, fade));
        int line = alpha(edge, fade);
        graphics.fill(x, y, right, y + 1, line);
        graphics.fill(x, bottom - 1, right, bottom, line);
        graphics.fill(x, y + 1, x + 1, bottom - 1, line);
        graphics.fill(right - 1, y + 1, right, bottom - 1, line);
    }

    /**
     * A switch in the classic look: a black-framed slot, green when on, with a little stone knob lit
     * on its top and left edges as the game's buttons are.
     */
    private static void classicToggle(GuiGraphicsExtractor graphics, int x, int y, boolean on, float fade) {
        int right = x + SWITCH_WIDTH;
        int bottom = y + SWITCH_HEIGHT;
        graphics.fill(x, y, right, bottom, alpha(0xFF000000, fade));
        graphics.fill(x + 1, y + 1, right - 1, bottom - 1, alpha(on ? 0xFF3C8C2E : 0xFF4A4A4A, fade));
        int knob = SWITCH_HEIGHT - 2;
        int knobX = on ? right - 1 - knob : x + 1;
        int knobRight = knobX + knob;
        graphics.fill(knobX, y + 1, knobRight, bottom - 1, alpha(0xFFB5B5B5, fade));
        graphics.fill(knobX, y + 1, knobRight, y + 2, alpha(0xFFE8E8E8, fade));
        graphics.fill(knobX, y + 2, knobX + 1, bottom - 1, alpha(0xFFE8E8E8, fade));
        graphics.fill(knobX, bottom - 2, knobRight, bottom - 1, alpha(0xFF5A5A5A, fade));
        graphics.fill(knobRight - 1, y + 2, knobRight, bottom - 2, alpha(0xFF5A5A5A, fade));
    }
}
