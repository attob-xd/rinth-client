package rinth.tools.fabricmods;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jspecify.annotations.Nullable;

/**
 * Reads the mods in a directory: each jar's {@code fabric.mod.json}, and the jars it nests (Fabric's
 * jar-in-jar, how mods ship their libraries and Fabric API ships its modules), as Fabric Loader
 * would find them. Same mod twice: the first found is kept, as the report says.
 */
final class ModScanner {

    private final Report report;
    private final Map<String, ModJar> mods = new LinkedHashMap<>();

    ModScanner(Report report) {
        this.report = report;
    }

    /** The mods in {@code directory}'s jars, top-level jars in name order, nested ones after their parent. */
    List<ModJar> scan(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Path> jars;
        try (Stream<Path> files = Files.list(directory)) {
            jars = files.filter(file -> file.getFileName()
                            .toString()
                            .toLowerCase(Locale.ROOT)
                            .endsWith(".jar"))
                    .sorted()
                    .toList();
        }
        for (Path jar : jars) {
            read(Files.readAllBytes(jar), jar.getFileName().toString());
        }
        return List.copyOf(mods.values());
    }

    private void read(byte[] jar, String source) throws IOException {
        Map<String, byte[]> entries = unzip(jar);
        byte[] json = entries.get("fabric.mod.json");
        if (json == null) {
            report.note(source + ": no fabric.mod.json, not a Fabric mod; skipped");
            return;
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            report.problem(source + ": fabric.mod.json isn't valid JSON (" + e.getMessage() + "); skipped");
            return;
        }
        ModJar mod = parse(root, source, entries);
        if (mod != null) {
            ModJar earlier = mods.putIfAbsent(mod.id(), mod);
            if (earlier != null) {
                report.note(source + ": " + mod.id() + " " + mod.version() + " is already in from " + earlier.source()
                        + " (" + earlier.version() + "); keeping that one");
            }
        }
        // Nested jars, as listed ("jars": [{"file": "META-INF/jars/..."}]).
        for (JsonElement nested : array(root, "jars")) {
            if (nested.isJsonObject() && nested.getAsJsonObject().has("file")) {
                String file = nested.getAsJsonObject().get("file").getAsString();
                byte[] bytes = entries.get(file);
                if (bytes == null) {
                    report.problem(source + ": nested jar " + file + " is listed but missing");
                } else {
                    read(bytes, source + "!" + file);
                }
            }
        }
    }

    private @Nullable ModJar parse(JsonObject root, String source, Map<String, byte[]> entries) {
        String id = string(root, "id");
        if (id.isEmpty()) {
            report.problem(source + ": fabric.mod.json has no id; skipped");
            return null;
        }
        Map<String, List<String>> entrypoints = new LinkedHashMap<>();
        List<String> unsupported = new ArrayList<>();
        JsonObject declared = root.has("entrypoints") && root.get("entrypoints").isJsonObject()
                ? root.getAsJsonObject("entrypoints")
                : new JsonObject();
        for (Map.Entry<String, JsonElement> key : declared.entrySet()) {
            List<String> values = new ArrayList<>();
            for (JsonElement entry : list(key.getValue())) {
                String adapter = "default";
                String value;
                if (entry.isJsonObject()) {
                    value = string(entry.getAsJsonObject(), "value");
                    if (entry.getAsJsonObject().has("adapter")) {
                        adapter = string(entry.getAsJsonObject(), "adapter");
                    }
                } else {
                    value = entry.getAsString();
                }
                // A method reference works only where the key has a known one-method interface to make it into.
                if (!adapter.equals("default")
                        || (value.contains("::") && !ModTableWriter.INITIALIZERS.containsKey(key.getKey()))) {
                    unsupported.add(
                            key.getKey() + ": " + value + (adapter.equals("default") ? "" : " (" + adapter + ")"));
                } else if (!value.isEmpty()) {
                    values.add(value);
                }
            }
            entrypoints.put(key.getKey(), List.copyOf(values));
        }
        List<String> mixins = new ArrayList<>();
        for (JsonElement entry : array(root, "mixins")) {
            if (entry.isJsonObject()) {
                String environment = entry.getAsJsonObject().has("environment")
                        ? string(entry.getAsJsonObject(), "environment")
                        : "*";
                if (!environment.equals("server")) {
                    mixins.add(string(entry.getAsJsonObject(), "config"));
                }
            } else {
                mixins.add(entry.getAsString());
            }
        }
        Map<String, List<String>> depends = new LinkedHashMap<>();
        if (root.has("depends") && root.get("depends").isJsonObject()) {
            for (Map.Entry<String, JsonElement> dependency :
                    root.getAsJsonObject("depends").entrySet()) {
                List<String> versions = new ArrayList<>();
                for (JsonElement version : list(dependency.getValue())) {
                    versions.add(version.getAsString());
                }
                depends.put(dependency.getKey(), List.copyOf(versions));
            }
        }
        List<String> authors = new ArrayList<>();
        for (JsonElement author : array(root, "authors")) {
            authors.add(author.isJsonObject() ? string(author.getAsJsonObject(), "name") : author.getAsString());
        }
        List<String> licenses = new ArrayList<>();
        if (root.has("license")) {
            for (JsonElement license : list(root.get("license"))) {
                licenses.add(license.getAsString());
            }
        }
        List<String> provides = new ArrayList<>();
        for (JsonElement alias : array(root, "provides")) {
            provides.add(alias.getAsString());
        }
        String accessWidener = root.has("accessWidener") ? string(root, "accessWidener") : null;
        return new ModJar(
                id,
                string(root, "version"),
                string(root, "name"),
                string(root, "description"),
                root.has("environment") ? string(root, "environment") : "*",
                List.copyOf(provides),
                List.copyOf(authors),
                List.copyOf(licenses),
                depends,
                entrypoints,
                List.copyOf(unsupported),
                List.copyOf(mixins),
                accessWidener,
                source,
                entries,
                icon(root));
    }

    /** The mod's icon in its jar: {@code "icon"}, a path or paths by size (the largest); empty if none. */
    private static String icon(JsonObject root) {
        JsonElement icon = root.get("icon");
        if (icon == null || icon.isJsonNull()) {
            return "";
        }
        if (icon.isJsonPrimitive()) {
            return icon.getAsString();
        }
        String best = "";
        int bestSize = -1;
        if (icon.isJsonObject()) {
            for (Map.Entry<String, JsonElement> size : icon.getAsJsonObject().entrySet()) {
                int pixels;
                try {
                    pixels = Integer.parseInt(size.getKey());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (pixels > bestSize && size.getValue().isJsonPrimitive()) {
                    bestSize = pixels;
                    best = size.getValue().getAsString();
                }
            }
        }
        return best;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static List<JsonElement> array(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null ? List.of() : list(value);
    }

    /** An array's items, or a single value as a list of one. */
    private static List<JsonElement> list(JsonElement value) {
        List<JsonElement> items = new ArrayList<>();
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            array.forEach(items::add);
        } else if (!value.isJsonNull()) {
            items.add(value);
        }
        return items;
    }

    private static Map<String, byte[]> unzip(byte[] jar) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (InputStream in = new ByteArrayInputStream(jar);
                ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), zip.readAllBytes());
                }
            }
        }
        return entries;
    }
}
