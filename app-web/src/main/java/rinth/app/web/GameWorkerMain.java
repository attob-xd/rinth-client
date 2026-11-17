package rinth.app.web;

import java.io.IOException;
import java.util.List;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.ArrayBuffer;
import rinth.classlib.concurrent.Waits;
import rinth.classlib.resources.ClassPathResources;
import rinth.client.datafix.DataFixing;
import rinth.client.memory.LightCopies;
import rinth.client.net.PacketHandoff;
import rinth.client.net.PlatformEventLoopGroupHolder;
import rinth.client.singleplayer.ChannelLink;
import rinth.client.singleplayer.WorldWorker;
import rinth.client.startup.CriterionCodecs;
import rinth.client.world.BiomeCells;
import rinth.client.world.ChunkCompression;
import rinth.client.world.Collisions;
import rinth.client.world.DensityBranches;
import rinth.client.world.GoalFlags;
import rinth.client.world.Heights;
import rinth.client.world.PositionalRandoms;
import rinth.client.world.RemoteTerrain;
import rinth.client.world.SpawnCosts;
import rinth.client.world.StrongholdRings;
import rinth.client.world.SurfaceBuffers;
import rinth.client.world.SurfaceWrites;
import rinth.fabric.ModSwitchState;
import rinth.platform.api.Platforms;
import rinth.platform.web.WebPlatform;
import rinth.platform.web.WorkerPlatform;
import rinth.platform.web.storage.WebFileSystem;

/**
 * The entry point of a copy of the game in a worker (ADR 0039): {@code WebMain.main(["--worker",
 * role])}, as {@code game-worker.ts} starts it. It sets up what the role needs and nothing of the
 * client: a file system of its own (filled by the page), the client jar's resources, the headless
 * platform, and the settings the page makes that the server's code reads too.
 */
final class GameWorkerMain {

    /** Where the page's storage worker keeps the client jar, from the private file system's root. */
    private static final String CACHED_JAR = ".rinth-cache/client-" + ClientJar.VERSION + ".jar";

    private GameWorkerMain() {}

    /** {@code query}: the page's query string, for the page options read here too. */
    static void main(String role, String query) throws IOException {
        LibraryDefaults.apply();
        // A background pool task that blocks gives the pool up, as on the page.
        Waits.beforeWait(WebPlatform::beforeWait);
        WebFileSystem files = WebFileSystem.installDetached(List.of(ClassPathResources.ROOT));
        int mounted = mountClientJar(files, !"all".equals(option(query, "workerjar")));
        System.out.println("[Rinth] Worker (" + role + "): " + mounted + " of the client jar's resources mounted");
        // The page's ?instance=, so the world's server reads and saves the same instance's directory.
        WebStorage storage = WebStorage.forInstance(option(query, "instance"));
        Platforms.install(new WorkerPlatform(storage, files));
        // Where the mods switched off in Mod Menu are saved, read before the game's code runs (ModSwitchState).
        System.setProperty(ModSwitchState.DIRECTORY_PROPERTY, storage.gameDirectory());
        PlatformEventLoopGroupHolder.useLocalEventLoops();
        // The page's defaults for what the server's code reads as well (WebMain).
        DataFixing.buildLazily();
        LightCopies.setEnabled(true);
        Collisions.setEnabled(true);
        PacketHandoff.setEnabled(true);
        StrongholdRings.setEnabled(!"game".equals(option(query, "rings")));
        SpawnCosts.setEnabled(!"game".equals(option(query, "spawncosts")));
        SpawnCosts.setVerify("verify".equals(option(query, "spawncosts")));
        SurfaceWrites.setEnabled(!"game".equals(option(query, "surface")));
        BiomeCells.setEnabled(!"game".equals(option(query, "biomecells")));
        BiomeCells.setVerify("verify".equals(option(query, "biomecells")));
        Heights.setEnabled(!"game".equals(option(query, "heights")));
        GoalFlags.setEnabled(!"game".equals(option(query, "goalflags")));
        DensityBranches.setEnabled(!"game".equals(option(query, "densitychoices")));
        RemoteTerrain.setHelpers(terrainHelpers(option(query, "terrainworkers")));
        CriterionCodecs.setEnabled(!"game".equals(option(query, "criteria")));
        ChunkCompression.setEnabled(!"game".equals(option(query, "deflate")));
        PositionalRandoms.setEnabled(!"game".equals(option(query, "randoms")));
        SurfaceBuffers.setEnabled(!"game".equals(option(query, "surfacebuffers")));
        // ADR 0041's packets a flush per message (?packets=each: one each, as before).
        ChannelLink.setBatched(!"each".equals(option(query, "packets")));
        switch (role) {
            case "server" -> WorldWorker.run();
            case "terrain" -> RemoteTerrain.serve();
            default -> throw new IllegalArgumentException("No such worker role: " + role);
        }
    }

    /**
     * Mounts the client jar's resources a worker reads, and returns how many. A server (or a
     * terrain helper) reads the data packs, the language file and the root files (the version, the
     * markers that find the vanilla pack's roots), never the client's assets: those stay out, and
     * the jar's buffer is let go once they're copied (ADR 0043). {@code ?workerjar=all} mounts
     * every resource, as before. The buffer is a local here so that it isn't held for the
     * program's life by {@code main}'s frame, which never returns.
     */
    private static int mountClientJar(WebFileSystem files, boolean serverOnly) throws IOException {
        ArrayBuffer jar = WorkerPlatform.cachedZip(CACHED_JAR, ClientJar.URL);
        return files.mountZip(
                ClassPathResources.ROOT, jar, serverOnly ? GameWorkerMain::serverResource : GameWorkerMain::resource);
    }

    /** Every resource of the jar: not a class, not its signature. */
    private static boolean resource(String name) {
        return !name.endsWith(".class") && !name.startsWith("META-INF/");
    }

    /** A resource a server reads: the data packs, the languages, and the files at the root. */
    private static boolean serverResource(String name) {
        return resource(name)
                && (name.startsWith("data/")
                        || name.startsWith("assets/minecraft/lang/")
                        || name.equals("assets/.mcassetsroot")
                        || name.indexOf('/') < 0);
    }

    /**
     * How many terrain helpers singleplayer's server starts (ADR 0042): the page's
     * {@code ?terrainworkers=N}, none otherwise. Off by default: no gain was measured where the
     * server keeps up, and where the page and the server have two cores between them a helper would
     * only take from both.
     */
    private static int terrainHelpers(String option) {
        if (option.isEmpty()) {
            return 0;
        }
        try {
            return Math.min(Integer.parseInt(option), Math.max(1, hardwareConcurrency() - 2));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @JSBody(script = "return (self.navigator && self.navigator.hardwareConcurrency) || 1;")
    private static native int hardwareConcurrency();

    /** A parameter of the page's query string, or "" if it has none. */
    @JSBody(
            params = {"query", "name"},
            script = "return new URLSearchParams(query).get(name) || '';")
    private static native String option(String query, String name);
}
