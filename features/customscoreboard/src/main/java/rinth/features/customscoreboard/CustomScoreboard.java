package rinth.features.customscoreboard;

import rinth.framework.Category;
import rinth.framework.Module;
import rinth.framework.game.GamePart;
import rinth.framework.hud.Anchor;
import rinth.framework.hud.GamePartElement;

/**
 * Custom Scoreboard: the scoreboard sidebar a server sends, wherever the player puts it and at the
 * size they choose. The game draws the sidebar as it always does (its lines, its numbers, the
 * client's look); the module only takes over where ({@link GamePartElement}), so it's placed with
 * the HUD editor like any other element. Nothing shows while the server sends no sidebar.
 */
public final class CustomScoreboard extends Module {

    /** Custom Scoreboard, off until the player turns it on. */
    public CustomScoreboard() {
        super(
                "customscoreboard",
                "Custom Scoreboard",
                "Move and resize the scoreboard a server shows, with the HUD editor.",
                Category.HUD);
        // The game's place: on the right, a unit in, about halfway down.
        hud(new GamePartElement(GamePart.SCOREBOARD, "sidebar", Anchor.RIGHT, 1, 0));
    }
}
