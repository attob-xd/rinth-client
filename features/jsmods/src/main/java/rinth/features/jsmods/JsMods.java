package rinth.features.jsmods;

import rinth.framework.Category;
import rinth.framework.Module;
import rinth.framework.event.AttackListener;
import rinth.framework.event.ClickListener;
import rinth.framework.event.Events;
import rinth.framework.event.JoinListener;
import rinth.framework.event.KillListener;
import rinth.framework.event.LeaveListener;
import rinth.framework.event.ScreenshotListener;
import rinth.framework.event.TickListener;
import rinth.framework.hud.Anchor;
import rinth.framework.hud.HudCanvas;
import rinth.framework.hud.HudElement;
import rinth.framework.script.Scripts;

/**
 * JS Mods: the player's JavaScript mods, added and switched in the page's Mods panel, get the
 * game's events and draw over the HUD. Always on: the panel turns each mod on and off. The scripts
 * run in the platform's {@link rinth.framework.script.ScriptHost}, which catches their errors; on a
 * platform without one this does nothing.
 */
public final class JsMods extends Module {

    private final TickListener tick = () -> Scripts.host().event("tick", "");
    private final JoinListener join = server -> Scripts.host().event("join", server);
    private final LeaveListener leave = () -> Scripts.host().event("leave", "");
    private final ClickListener click = button -> Scripts.host().event("click", Integer.toString(button));
    private final AttackListener attack = reach -> Scripts.host().event("attack", Double.toString(reach));
    private final KillListener kill = () -> Scripts.host().event("kill", "");
    private final ScreenshotListener screenshot = file -> Scripts.host().event("screenshot", file);
    private boolean started;

    public JsMods() {
        super("jsmods", "JS Mods", "Runs the JavaScript mods added in the page's Mods panel.", Category.UTILITY);
        alwaysOn();
        hud(new Overlay());
        on(Events.TICK, tick);
        on(Events.JOIN, join);
        on(Events.LEAVE, leave);
        on(Events.CLICK, click);
        on(Events.ATTACK, attack);
        on(Events.KILL, kill);
        on(Events.SCREENSHOT, screenshot);
    }

    @Override
    protected void onEnable() {
        if (!started) {
            started = true;
            Scripts.host().start(system().game());
        }
    }

    /** The scripts' drawing, over the whole screen: not placed, so the HUD editor leaves it out. */
    private static final class Overlay extends HudElement {

        Overlay() {
            super("overlay", "JS mods' HUD", Anchor.TOP_LEFT, 0, 0);
        }

        @Override
        public boolean isVisible() {
            return Scripts.host().drawsHud();
        }

        @Override
        public boolean isPlaceable() {
            return false;
        }

        @Override
        public int width(HudCanvas canvas) {
            return canvas.screenWidth();
        }

        @Override
        public int height(HudCanvas canvas) {
            return canvas.screenHeight();
        }

        @Override
        public void draw(HudCanvas canvas) {
            Scripts.host().drawHud(canvas);
        }
    }
}
