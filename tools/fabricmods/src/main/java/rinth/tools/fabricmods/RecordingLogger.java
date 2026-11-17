package rinth.tools.fabricmods;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.logging.Level;
import org.spongepowered.asm.logging.LoggerAdapterConsole;

/**
 * Mixin's logger: info and above to the console, as Mixin's own would, and warnings and errors kept
 * for the report too: a mixin that didn't apply is a warning, not an error, when its config isn't
 * required ({@link MixinRun} makes every config so).
 */
final class RecordingLogger extends LoggerAdapterConsole {

    private static final Set<Level> KEPT = EnumSet.of(Level.FATAL, Level.ERROR, Level.WARN);
    private static final Set<Level> SHOWN = EnumSet.of(Level.FATAL, Level.ERROR, Level.WARN, Level.INFO);

    private static final Map<String, RecordingLogger> LOGGERS = new ConcurrentHashMap<>();
    private static final List<String> RECORDED = new ArrayList<>();

    static RecordingLogger get(String name) {
        return LOGGERS.computeIfAbsent(name, RecordingLogger::new);
    }

    /** The warnings and errors logged since the last call. */
    static List<String> drain() {
        synchronized (RECORDED) {
            List<String> lines = List.copyOf(RECORDED);
            RECORDED.clear();
            return lines;
        }
    }

    private RecordingLogger(String name) {
        super(name);
    }

    @Override
    public void log(Level level, String message, @Nullable Object... params) {
        if (KEPT.contains(level)) {
            record(level, format(message, params));
        }
        if (SHOWN.contains(level)) {
            super.log(level, message, params);
        }
    }

    @Override
    public void log(Level level, String message, Throwable t) {
        if (KEPT.contains(level)) {
            record(level, message + ": " + t);
        }
        if (SHOWN.contains(level)) {
            super.log(level, message, t);
        }
    }

    @Override
    public void catching(Level level, Throwable t) {
        if (KEPT.contains(level)) {
            record(level, t.toString());
        }
        super.catching(level, t);
    }

    private static void record(Level level, String line) {
        synchronized (RECORDED) {
            RECORDED.add(level + " " + line);
        }
    }

    /** Mixin's messages use {@code {}} placeholders, like SLF4J's. */
    private static String format(String message, @Nullable Object... params) {
        StringBuilder out = new StringBuilder();
        int from = 0;
        int next = 0;
        for (int at = message.indexOf("{}"); at >= 0; at = message.indexOf("{}", from)) {
            out.append(message, from, at);
            out.append(params != null && next < params.length ? String.valueOf(params[next++]) : "{}");
            from = at + 2;
        }
        return out.append(message.substring(from)).toString();
    }
}
