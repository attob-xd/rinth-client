package rinth.client.look;

import org.jspecify.annotations.Nullable;
import rinth.client.ui.Icons;

/** The icons modules name for their menu buttons ({@code MenuButton.icon}). */
final class MenuIcons {

    private MenuIcons() {}

    /** The icon called {@code name}, or null for none. */
    static @Nullable Icons of(String name) {
        return switch (name) {
            case "camera" -> Icons.CAMERA;
            case "palette" -> Icons.PALETTE;
            case "grid" -> Icons.GRID;
            case "layout" -> Icons.LAYOUT;
            case "rinth" -> Icons.RINTH;
            default -> null;
        };
    }
}
