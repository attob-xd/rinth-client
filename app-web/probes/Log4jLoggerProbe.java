import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Regression, for the built-in Fabric mods: Sodium makes its loggers through Log4j's API
 * ({@code LogManager.getLogger}), which the game itself doesn't, and that start-up reached what
 * the browser build lacked (an environment variable, a Unicode block in a regex, a runtime
 * permission, a charset check). Joining a world then failed as Sodium's chunk builder loaded.
 * Prints "probe passed" when a logger is made and logs.
 */
public final class Log4jLoggerProbe {

    private Log4jLoggerProbe() {}

    public static void main(String[] args) {
        try {
            Logger logger = LogManager.getLogger("ChunkBuilder");
            logger.info("Log4j logger made in the browser: {}", logger.getName());
            System.out.println("ChunkBuilder".equals(logger.getName()) ? "probe passed" : "probe failed: named " + logger.getName());
        } catch (Throwable e) {
            StringBuilder trace = new StringBuilder(e.toString());
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                trace.append(" <- ").append(cause);
            }
            System.out.println("probe failed: " + trace);
        }
    }
}
