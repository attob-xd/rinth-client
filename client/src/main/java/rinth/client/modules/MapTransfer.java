package rinth.client.modules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import rinth.framework.ModuleSystem;
import rinth.framework.hud.Shape;
import rinth.framework.waypoint.Waypoint;
import rinth.framework.waypoint.Waypoints;

/**
 * The world map's Export and Import (Rinth): what the open world's map has seen, and its waypoints, as
 * one file, {@code <world>.rinthmap}, that another Rinth client imports into the world it has open.
 * Import only adds: the chunks its map hasn't seen and the waypoints it doesn't have.
 *
 * <p>The file is a zip: {@code rinthmap.json} (the format, its version, the world's name),
 * {@code waypoints.json}, and {@code map/<dimension folder>/<x>.<z>.map}, each region as {@link
 * MapMemory} keeps it.
 *
 * <p>Render thread.
 */
final class MapTransfer {

    static final String EXTENSION = ".rinthmap";
    private static final String FORMAT = "rinth-map";
    private static final int VERSION = 1;
    /** An import's limit: a world seen far and wide is tens of MB, not hundreds. */
    private static final long MAX_BYTES = 512L * 1024 * 1024;

    private MapTransfer() {}

    /** The file name an Export of {@code world} gets. */
    static String fileName(String world) {
        String plain = world.replaceAll("[^A-Za-z0-9 ._-]", "_").strip();
        return (plain.isEmpty() ? "world" : plain) + EXTENSION;
    }

    /** The open world's map and waypoints, as an Export file. */
    static byte[] export(ModuleSystem system) throws IOException {
        Waypoints waypoints = system.waypoints();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256 * 1024);
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            JsonObject about = new JsonObject();
            about.addProperty("format", FORMAT);
            about.addProperty("version", VERSION);
            about.addProperty("world", String.valueOf(waypoints.world()));
            put(zip, "rinthmap.json", about.toString().getBytes(StandardCharsets.UTF_8));
            JsonArray marks = new JsonArray();
            for (Waypoint waypoint : waypoints.all()) {
                if (waypoint.isDeath()) {
                    continue; // where this player died isn't another's
                }
                JsonObject mark = new JsonObject();
                mark.addProperty("name", waypoint.name());
                mark.addProperty("x", waypoint.x());
                mark.addProperty("y", waypoint.y());
                mark.addProperty("z", waypoint.z());
                mark.addProperty("dimension", waypoint.dimension());
                mark.addProperty("rgb", waypoint.rgb());
                mark.addProperty("icon", waypoint.icon().name());
                mark.addProperty("visible", waypoint.isVisible());
                mark.addProperty("beam", waypoint.hasBeam());
                marks.add(mark);
            }
            put(zip, "waypoints.json", marks.toString().getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> region : MapMemory.exportRegions().entrySet()) {
                put(zip, "map/" + region.getKey(), region.getValue());
            }
        }
        return bytes.toByteArray();
    }

    /** Imports an Export file into the open world; a sentence for the player saying what it added. */
    static String importFile(Path file, ModuleSystem system) {
        if (system.waypoints().world() == null) {
            return "Open a world first, then import its map.";
        }
        int regions = 0;
        int chunks = 0;
        int marks = 0;
        boolean ours = false;
        try (InputStream raw = Files.newInputStream(file);
                ZipInputStream zip = new ZipInputStream(raw)) {
            long total = 0;
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                byte[] data = zip.readAllBytes();
                total += data.length;
                if (total > MAX_BYTES) {
                    return "That file is too big to be a map.";
                }
                String name = entry.getName();
                if (name.equals("rinthmap.json")) {
                    JsonObject about = JsonParser.parseString(new String(data, StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    ours = about.has("format")
                            && FORMAT.equals(about.get("format").getAsString());
                } else if (name.equals("waypoints.json")) {
                    marks += importWaypoints(new String(data, StandardCharsets.UTF_8), system.waypoints());
                } else if (name.startsWith("map/") && name.endsWith(".map")) {
                    int added = MapMemory.importRegion(name.substring("map/".length()), data);
                    if (added >= 0) {
                        regions++;
                        chunks += added;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return "Couldn't read " + file.getFileName() + ": it isn't a Rinth map.";
        }
        if (!ours) {
            return file.getFileName() + " isn't a Rinth map (" + EXTENSION + ").";
        }
        return String.format(
                Locale.ROOT,
                "Imported: %d new chunk%s from %d region%s, %d new waypoint%s.",
                chunks,
                chunks == 1 ? "" : "s",
                regions,
                regions == 1 ? "" : "s",
                marks,
                marks == 1 ? "" : "s");
    }

    /** Adds the file's waypoints this world doesn't have (same name and block); how many. */
    private static int importWaypoints(String json, Waypoints waypoints) {
        int added = 0;
        for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
            JsonObject mark = element.getAsJsonObject();
            String name = mark.get("name").getAsString();
            int x = mark.get("x").getAsInt();
            int y = mark.get("y").getAsInt();
            int z = mark.get("z").getAsInt();
            String dimension = mark.get("dimension").getAsString();
            boolean have = false;
            for (Waypoint waypoint : waypoints.all()) {
                have |= waypoint.name().equals(name)
                        && waypoint.x() == x
                        && waypoint.y() == y
                        && waypoint.z() == z
                        && waypoint.dimension().equals(dimension);
            }
            if (have) {
                continue;
            }
            Shape icon;
            try {
                icon = Shape.valueOf(mark.get("icon").getAsString());
            } catch (IllegalArgumentException | NullPointerException e) {
                icon = Shape.CIRCLE;
            }
            Waypoint waypoint =
                    new Waypoint(name, x, y, z, dimension, mark.get("rgb").getAsInt(), icon);
            if (mark.has("visible")) {
                waypoint.setVisible(mark.get("visible").getAsBoolean());
            }
            if (mark.has("beam")) {
                waypoint.setBeam(mark.get("beam").getAsBoolean());
            }
            waypoints.add(waypoint);
            added++;
        }
        return added;
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}
