package rinth.tools.webbuild;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * The Mods panel's Fabric converter (ADR 0065, ADR 0067), on the dev server only: the page sends a
 * mod jar, this puts it in {@code mods/} and runs the build-time processor for its report, and on
 * request rebuilds the browser build with the mods in.
 *
 * <ul>
 *   <li>{@code GET /fabric/mods}: the jars in {@code mods/}, as a JSON array of
 *       {@code {"name", "enabled", "id"}}, the id from the jar's {@code fabric.mod.json}
 *   <li>{@code POST /fabric/upload?name=<jar>}: the body saved as that jar, then the report
 *   <li>{@code POST /fabric/remove?name=<jar>}: the jar deleted
 *   <li>{@code POST /fabric/enable?name=<jar>&on=<true|false>}: the jar switched on or off for the
 *       next build: off is {@code <jar>.disabled}, which the processor skips, as launchers do
 *   <li>{@code POST /fabric/build}: the rebuild started; {@code GET /fabric/status} follows it
 *   <li>{@code POST /fabric/shaders}: the mods' pipelines the page had no shaders for, kept in
 *       {@code mods/mod-shaders.txt} for {@code :app-web:shaders}
 * </ul>
 *
 * <p>Building runs code from the mods (their mixin plugins), so this answers only the page itself:
 * a request must name the server as {@code localhost} and, to change anything, come from its origin.
 */
final class ModConverter implements HttpHandler {

    private static final Pattern JAR_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,120}\\.jar");
    private static final Pattern PAIRS = Pattern.compile("&");
    private static final String DISABLED = ".disabled";
    private static final int LOG_LINES = 40;
    private static final int MAX_SHADER_REPORT = 64 * 1024;
    /** {@code <pipeline>\t<vertex key>\t<fragment key>}, as the WebGL2 device reports a mod's pipeline. */
    private static final Pattern SHADER_LINE =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+\tvertex [a-z0-9_.-]+:[a-z0-9_./-]+ [^\t\\p{Cntrl}]{0,1000}"
                    + "\tfragment [a-z0-9_.-]+:[a-z0-9_./-]+ [^\t\\p{Cntrl}]{0,1000}");

    private final Path repo;
    private final Path mods;
    private final Path shaders;
    private final int port;

    private final Object lock = new Object();
    private String state = "idle";
    private long startedAt;
    private long finishedAt;
    private final Deque<String> log = new ArrayDeque<>();
    private boolean busy;

    ModConverter(Path repo, int port) {
        this.repo = repo.toAbsolutePath().normalize();
        this.mods = this.repo.resolve("mods");
        this.shaders = mods.resolve("mod-shaders.txt");
        this.port = port;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!fromThePage(exchange)) {
                send(exchange, 403, "text/plain", "Only the page on this machine may use the converter");
                return;
            }
            String action = exchange.getRequestURI().getPath().substring("/fabric/".length());
            boolean post = exchange.getRequestMethod().equals("POST");
            switch (action) {
                case "mods" -> send(exchange, 200, "application/json", json(jars()));
                case "status" -> send(exchange, 200, "application/json", status());
                case "upload" -> {
                    if (post) {
                        upload(exchange);
                    } else {
                        send(exchange, 405, "text/plain", "POST a jar");
                    }
                }
                case "remove" -> {
                    if (post) {
                        remove(exchange);
                    } else {
                        send(exchange, 405, "text/plain", "POST to remove");
                    }
                }
                case "enable" -> {
                    if (post) {
                        enable(exchange);
                    } else {
                        send(exchange, 405, "text/plain", "POST to turn a jar on or off");
                    }
                }
                case "build" -> {
                    if (post) {
                        startBuild(exchange);
                    } else {
                        send(exchange, 405, "text/plain", "POST to build");
                    }
                }
                case "shaders" -> {
                    if (post) {
                        keepShaders(exchange);
                    } else {
                        send(exchange, 405, "text/plain", "POST the pipelines to keep");
                    }
                }
                default -> send(exchange, 404, "text/plain", "No such converter call");
            }
        }
    }

    /** The page on this machine: named as localhost (no DNS rebinding), and, for changes, from its own origin. */
    private boolean fromThePage(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || !(host.equals("localhost:" + port) || host.equals("127.0.0.1:" + port))) {
            return false;
        }
        if (exchange.getRequestMethod().equals("GET")) {
            return true;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        return origin != null && origin.equals("http://" + host);
    }

    private void upload(HttpExchange exchange) throws IOException {
        String name = jarName(exchange);
        if (name == null) {
            send(exchange, 400, "text/plain", "A .jar file name of letters, digits, . _ + and - is needed");
            return;
        }
        synchronized (lock) {
            if (busy) {
                send(exchange, 409, "text/plain", "The game is being rebuilt; try again when it's done");
                return;
            }
            busy = true;
        }
        try {
            Files.createDirectories(mods);
            try (InputStream body = exchange.getRequestBody()) {
                Files.write(mods.resolve(name), body.readAllBytes());
            }
            // A new copy is on, replacing one that was switched off.
            Files.deleteIfExists(mods.resolve(name + DISABLED));
            List<String> output = new ArrayList<>();
            int exit = gradle(List.of(":fabric:runtime:fabricMods"), output, TimeUnit.MINUTES.toMillis(10));
            Path report = repo.resolve("fabric/runtime/build/fabric-mods/report.txt");
            if (exit == 0 && Files.isRegularFile(report)) {
                send(exchange, 200, "text/plain", Files.readString(report, StandardCharsets.UTF_8));
            } else {
                send(exchange, 500, "text/plain", "The processor failed:\n" + tail(output));
            }
        } finally {
            synchronized (lock) {
                busy = false;
            }
        }
    }

    private void remove(HttpExchange exchange) throws IOException {
        String name = jarName(exchange);
        if (name == null) {
            send(exchange, 400, "text/plain", "Which jar?");
            return;
        }
        Files.deleteIfExists(mods.resolve(name));
        Files.deleteIfExists(mods.resolve(name + DISABLED));
        send(exchange, 200, "text/plain", "Removed " + name);
    }

    private void startBuild(HttpExchange exchange) throws IOException {
        synchronized (lock) {
            if (busy) {
                send(exchange, 409, "text/plain", "Already busy");
                return;
            }
            busy = true;
            state = "building";
            startedAt = System.currentTimeMillis();
            log.clear();
        }
        Thread builder = new Thread(
                () -> {
                    List<String> output = new ArrayList<>();
                    int exit;
                    try {
                        exit = gradle(
                                List.of(":app-web:teavm", ":app-web:buildManifest"),
                                output,
                                TimeUnit.HOURS.toMillis(2));
                    } catch (IOException e) {
                        output.add(e.toString());
                        exit = -1;
                    }
                    synchronized (lock) {
                        state = exit == 0 ? "done" : "failed";
                        finishedAt = System.currentTimeMillis();
                        busy = false;
                    }
                },
                "fabric-rebuild");
        builder.setDaemon(true);
        builder.start();
        send(exchange, 202, "application/json", status());
    }

    private String status() {
        synchronized (lock) {
            long end = state.equals("building") ? System.currentTimeMillis() : finishedAt;
            long minutes = startedAt == 0 ? 0 : Math.max(0, (end - startedAt) / 60_000);
            return "{\"state\":" + quote(state) + ",\"minutes\":" + minutes + ",\"log\":"
                    + quote(String.join("\n", log)) + "}";
        }
    }

    /**
     * Runs the repo's Gradle wrapper with {@code tasks} on this server's JDK, its output kept in
     * {@code output} and the last lines in the status log. Returns its exit code.
     */
    private int gradle(List<String> tasks, List<String> output, long timeoutMillis) throws IOException {
        List<String> command = new ArrayList<>();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            command.addAll(List.of("cmd.exe", "/c", repo.resolve("gradlew.bat").toString()));
        } else {
            command.add(repo.resolve("gradlew").toString());
        }
        command.addAll(tasks);
        command.add("--console=plain");
        ProcessBuilder builder =
                new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true);
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process process = builder.start();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                output.add(line);
                synchronized (lock) {
                    log.addLast(line);
                    while (log.size() > LOG_LINES) {
                        log.removeFirst();
                    }
                }
            }
        }
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return -1;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return -1;
        }
        return process.exitValue();
    }

    /**
     * A jar in {@code mods/}: on, or switched off as {@code <name>.disabled}, which the build skips;
     * {@code id} is its Fabric mod id, empty if its {@code fabric.mod.json} doesn't say.
     */
    private record Jar(String name, boolean enabled, String id) {}

    private static final Pattern MOD_ID = Pattern.compile("\"id\"\\s*:\\s*\"([a-z][a-z0-9_-]{0,63})\"");

    private List<Jar> jars() throws IOException {
        if (!Files.isDirectory(mods)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(mods)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> {
                        String name = file.getFileName().toString();
                        boolean off = name.toLowerCase(Locale.ROOT).endsWith(".jar" + DISABLED);
                        String jar = off ? name.substring(0, name.length() - DISABLED.length()) : name;
                        return new Jar(jar, !off, modId(file));
                    })
                    .filter(jar -> jar.name().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted((a, b) -> a.name().compareTo(b.name()))
                    .toList();
        }
    }

    /** The mod id in a jar's {@code fabric.mod.json}; empty if there's none or it can't be read. */
    private static String modId(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) {
                return "";
            }
            try (InputStream in = zip.getInputStream(entry)) {
                Matcher matcher = MOD_ID.matcher(new String(in.readNBytes(256 * 1024), StandardCharsets.UTF_8));
                return matcher.find() ? matcher.group(1) : "";
            }
        } catch (IOException e) {
            return "";
        }
    }

    private void enable(HttpExchange exchange) throws IOException {
        String name = jarName(exchange);
        String query = exchange.getRequestURI().getRawQuery();
        if (name == null || query == null) {
            send(exchange, 400, "text/plain", "Which jar, and on=true or on=false?");
            return;
        }
        boolean on = PAIRS.splitAsStream(query).anyMatch(pair -> pair.equals("on=true"));
        synchronized (lock) {
            if (busy) {
                send(exchange, 409, "text/plain", "The game is being rebuilt; try again when it's done");
                return;
            }
        }
        Path enabled = mods.resolve(name);
        Path disabled = mods.resolve(name + DISABLED);
        if (on && Files.isRegularFile(disabled)) {
            Files.move(disabled, enabled, StandardCopyOption.REPLACE_EXISTING);
        } else if (!on && Files.isRegularFile(enabled)) {
            Files.move(enabled, disabled, StandardCopyOption.REPLACE_EXISTING);
        }
        send(exchange, 200, "text/plain", (on ? "Turned on " : "Turned off ") + name + "; rebuild to apply");
    }

    /**
     * Keeps the mods' pipelines the page had no shaders for, for the next translation
     * ({@code :app-web:shaders}): each line names a pipeline and its two stages' keys, tab-separated.
     * The file only grows, sorted and without repeats; a line that doesn't look like one is refused.
     */
    private void keepShaders(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readNBytes(MAX_SHADER_REPORT), StandardCharsets.UTF_8);
        }
        Set<String> lines = new TreeSet<>();
        for (String line : body.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            if (!SHADER_LINE.matcher(line).matches()) {
                send(exchange, 400, "text/plain", "Not a pipeline and two stage keys: " + line);
                return;
            }
            lines.add(line);
        }
        synchronized (lock) {
            if (Files.isRegularFile(shaders)) {
                for (String line : Files.readAllLines(shaders, StandardCharsets.UTF_8)) {
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        lines.add(line);
                    }
                }
            }
            Files.createDirectories(mods);
            Files.writeString(
                    shaders,
                    "# The Fabric mods' pipelines the page had no shaders for; :app-web:shaders translates them.\n"
                            + String.join("\n", lines) + "\n",
                    StandardCharsets.UTF_8);
        }
        send(exchange, 200, "text/plain", "Kept " + lines.size() + " pipelines for the next translation");
    }

    private static @Nullable String jarName(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        return PAIRS.splitAsStream(query)
                .filter(pair -> pair.startsWith("name="))
                .map(pair -> URLDecoder.decode(pair.substring("name=".length()), StandardCharsets.UTF_8))
                .filter(name -> JAR_NAME.matcher(name).matches())
                .findFirst()
                .orElse(null);
    }

    private static String tail(List<String> lines) {
        return String.join("\n", lines.subList(Math.max(0, lines.size() - LOG_LINES), lines.size()));
    }

    private static String json(List<Jar> jars) {
        List<String> objects = new ArrayList<>();
        for (Jar jar : jars) {
            objects.add("{\"name\":" + quote(jar.name()) + ",\"enabled\":" + jar.enabled() + ",\"id\":"
                    + quote(jar.id()) + "}");
        }
        return "[" + String.join(",", objects) + "]";
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
