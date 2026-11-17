package rinth.framework.script;

import rinth.framework.game.Game;
import rinth.framework.hud.HudCanvas;

/**
 * Where scripted mods run (ADR 0067): the browser's JavaScript mods, which the JS Mods module
 * hands the game's events and the HUD. A platform without scripts has {@link #NONE}.
 *
 * <p>Game thread.
 */
public interface ScriptHost {

    /** No scripts: every call does nothing. */
    ScriptHost NONE = new ScriptHost() {
        @Override
        public void start(Game game) {}

        @Override
        public void event(String name, String argument) {}

        @Override
        public boolean drawsHud() {
            return false;
        }

        @Override
        public void drawHud(HudCanvas canvas) {}
    };

    /** Once, when the module system starts: the game as modules see it, for the scripts' questions. */
    void start(Game game);

    /**
     * A game event for the scripts, by name: {@code tick}, {@code join} (the server's address, or
     * empty for singleplayer), {@code leave}, {@code click} (the button), {@code attack} (the reach),
     * {@code kill}, {@code screenshot} (the file's name). Names, not methods, so new events reach
     * the scripts without a new host.
     */
    void event(String name, String argument);

    /** Whether any script draws on the HUD now. */
    boolean drawsHud();

    /** The scripts' HUD, over the whole screen, in GUI units. */
    void drawHud(HudCanvas canvas);
}
