package rinth.app.web.jsmods;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import rinth.framework.game.Game;
import rinth.framework.game.GameKey;
import rinth.framework.game.PlayerView;
import rinth.framework.hud.HudCanvas;
import rinth.framework.script.ScriptHost;
import rinth.framework.script.Scripts;

/**
 * The browser's script host (ADR 0067): the player's JavaScript mods run in the page
 * ({@code jsmods.ts}, as {@code window.rinthMods}), and this hands them the game's events and
 * draws their HUD. They ask about the game, and act on it, through the one function this puts on
 * the page, {@code window.rinthNative(op, arg)}: answers are strings, and actions (chat,
 * messages) wait for the next tick. Every call into the page catches what the mods throw.
 *
 * <p>Game thread (the page's only thread).
 */
public final class WebScriptHost implements ScriptHost {

    private static final Logger LOGGER = LogUtils.getLogger();

    private Game game = Game.NONE;
    private @Nullable HudCanvas canvas;
    /** Actions from the mods, done at the next tick: "say" or "show" or "notify", then the text. */
    private final List<String> pending = new ArrayList<>();

    private WebScriptHost() {}

    /** Before the game starts: the host, and the page's way into it. */
    public static void install() {
        WebScriptHost host = new WebScriptHost();
        Scripts.install(host);
        installNative(host::call);
    }

    @Override
    public void start(Game started) {
        game = started;
        pageEvent("start", "");
    }

    @Override
    public void event(String name, String argument) {
        if (name.equals("tick")) {
            for (int i = 0; i + 1 < pending.size(); i += 2) {
                act(pending.get(i), pending.get(i + 1));
            }
            pending.clear();
        }
        pageEvent(name, argument);
    }

    @Override
    public boolean drawsHud() {
        return pageDrawsHud();
    }

    @Override
    public void drawHud(HudCanvas drawing) {
        canvas = drawing;
        try {
            draw(drawing, pageHud(drawing.screenWidth(), drawing.screenHeight()));
        } finally {
            canvas = null;
        }
    }

    /** {@code window.rinthNative(op, arg)}: the mods' questions, answered now, and their actions, queued. */
    private String call(String op, String arg) {
        try {
            return switch (op) {
                case "player" -> player();
                case "fps" -> Integer.toString(game.fps());
                case "world" -> world();
                case "screen" -> screen();
                case "key" -> Boolean.toString(keyDown(arg));
                case "textWidth" -> Integer.toString(textWidth(arg));
                case "lineHeight" -> Integer.toString(lineHeight());
                case "clipboard" -> game.clipboard();
                case "say", "show", "notify", "copy" -> {
                    pending.add(op);
                    pending.add(arg);
                    yield "";
                }
                default -> "";
            };
        } catch (RuntimeException e) {
            LOGGER.warn("JS mods: {} failed: {}", op, e.toString());
            return "";
        }
    }

    private void act(String op, String arg) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            switch (op) {
                case "say" -> {
                    ClientPacketListener connection = minecraft.getConnection();
                    if (connection != null && !arg.isBlank()) {
                        if (arg.startsWith("/")) {
                            connection.sendCommand(arg.substring(1));
                        } else {
                            connection.sendChat(arg);
                        }
                    }
                }
                case "show" -> minecraft.gui.hud.getChat().addClientSystemMessage(Component.literal(arg));
                case "notify" -> {
                    int split = arg.indexOf('\n');
                    game.showMessage(
                            split < 0 ? "JS mod" : arg.substring(0, split), split < 0 ? arg : arg.substring(split + 1));
                }
                case "copy" -> game.copy(arg);
                default -> {}
            }
        } catch (RuntimeException e) {
            LOGGER.warn("JS mods: {} failed: {}", op, e.toString());
        }
    }

    /** The player as JSON, or an empty string outside a world. */
    private String player() {
        PlayerView player = game.player();
        if (player == null) {
            return "";
        }
        return "{\"x\":" + player.x() + ",\"y\":" + player.y() + ",\"z\":" + player.z() + ",\"heading\":"
                + player.heading() + ",\"dimension\":\"" + player.dimension().replace("\"", "") + "\",\"dead\":"
                + player.isDead() + "}";
    }

    /** The world or server's name, or empty outside one. */
    private String world() {
        String world = game.world();
        return world == null ? "" : world;
    }

    private int lineHeight() {
        HudCanvas drawing = canvas;
        return drawing != null ? drawing.lineHeight() : Minecraft.getInstance().font.lineHeight;
    }

    private static String screen() {
        Screen screen = Minecraft.getInstance().gui.screen();
        return screen == null ? "" : screen.getClass().getSimpleName();
    }

    private boolean keyDown(String name) {
        try {
            return game.isDown(GameKey.valueOf(name.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private int textWidth(String text) {
        HudCanvas drawing = canvas;
        return drawing != null
                ? drawing.textWidth(text)
                : Minecraft.getInstance().font.width(text);
    }

    /**
     * Runs the page's drawing: one command a line, fields split by tabs, the text last.
     * {@code T x y argb text}, {@code F x y width height argb}, {@code D cx cy radius argb},
     * {@code R cx cy radius width argb}, {@code P x y scale} (push) and {@code Q} (pop).
     */
    private static void draw(HudCanvas drawing, String commands) {
        int depth = 0;
        int start = 0;
        while (start < commands.length()) {
            int end = commands.indexOf('\n', start);
            if (end < 0) {
                end = commands.length();
            }
            String line = commands.substring(start, end);
            start = end + 1;
            if (line.isEmpty()) {
                continue;
            }
            try {
                Fields fields = new Fields(line);
                switch (fields.next()) {
                    case "T" -> {
                        int x = fields.integer();
                        int y = fields.integer();
                        int argb = fields.color();
                        drawing.text(fields.remainder(), x, y, argb);
                    }
                    case "F" ->
                        drawing.fill(
                                fields.integer(), fields.integer(), fields.integer(), fields.integer(), fields.color());
                    case "D" -> drawing.disc(fields.decimal(), fields.decimal(), fields.decimal(), fields.color());
                    case "R" ->
                        drawing.ring(
                                fields.decimal(), fields.decimal(), fields.decimal(), fields.decimal(), fields.color());
                    case "P" -> {
                        drawing.push(fields.decimal(), fields.decimal(), fields.decimal());
                        depth++;
                    }
                    case "Q" -> {
                        if (depth > 0) {
                            drawing.pop();
                            depth--;
                        }
                    }
                    default -> {}
                }
            } catch (RuntimeException e) {
                // A malformed command from a mod: skipped, the rest still drawn.
            }
        }
        while (depth-- > 0) {
            drawing.pop();
        }
    }

    /** A command's tab-separated fields, read in order. */
    private static final class Fields {

        private final String line;
        private int at;

        Fields(String line) {
            this.line = line;
        }

        String next() {
            int tab = line.indexOf('\t', at);
            String field = tab < 0 ? line.substring(at) : line.substring(at, tab);
            at = tab < 0 ? line.length() : tab + 1;
            return field;
        }

        int integer() {
            return (int) Math.round(Double.parseDouble(next()));
        }

        float decimal() {
            return Float.parseFloat(next());
        }

        int color() {
            return Integer.parseUnsignedInt(next(), 16);
        }

        /** Everything after the fields read so far: a text command's text, tabs and all. */
        String remainder() {
            return line.substring(Math.min(at, line.length()));
        }
    }

    @JSFunctor
    interface Native extends JSObject {
        String call(String op, String arg);
    }

    @JSBody(
            params = {"call"},
            script = "window.rinthNative = function(op, arg) {"
                    + " return call(String(op), arg == null ? '' : String(arg)); };")
    private static native void installNative(Native call);

    @JSBody(
            params = {"name", "arg"},
            script = "try { var m = window.rinthMods; if (m) m.event(name, arg); }"
                    + " catch (e) { console.error('JS mods:', e); }")
    private static native void pageEvent(String name, String arg);

    @JSBody(
            script = "try { var m = window.rinthMods; return !!(m && m.drawsHud()); }"
                    + " catch (e) { console.error('JS mods:', e); return false; }")
    private static native boolean pageDrawsHud();

    @JSBody(
            params = {"width", "height"},
            script = "try { var m = window.rinthMods; return m ? String(m.hud(width, height)) : ''; }"
                    + " catch (e) { console.error('JS mods:', e); return ''; }")
    private static native String pageHud(int width, int height);
}
