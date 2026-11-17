package rinth.tools.copycheck;

import rinth.tools.build.BuildConfig;
import rinth.tools.build.CliException;
import rinth.tools.build.Git;
import rinth.tools.build.Log;
import rinth.tools.build.Workspace;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code ./rinth copycheck}: fails if any Rinth Java file contains a long run copied from
 * the decompiled game.
 *
 * <p>Compares against the pristine decompiled sources in {@code work/decompiled}, not the
 * patched tree: the patched tree holds Rinth's own replacement classes, which would match
 * themselves. Checks every Java file git tracks or would track outside {@code work/}.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        try {
            System.exit(run(List.of(args)));
        } catch (CliException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        }
    }

    static int run(List<String> args) throws Exception {
        int window = CopyCheck.DEFAULT_WINDOW;
        for (String arg : args) {
            if (arg.startsWith("--window=")) {
                window = Integer.parseInt(arg.substring("--window=".length()));
            } else {
                throw new CliException("Unknown option '" + arg + "'. Usage: ./rinth copycheck [--window=<tokens>]");
            }
        }

        Workspace ws = Workspace.discover(Path.of(System.getProperty("rinth.cwd", ".")));
        BuildConfig config = BuildConfig.load(ws.repoRoot());
        Path vendor = ws.decompiledDir(config.minecraftVersion());
        if (!Files.isDirectory(vendor)) {
            throw new CliException("No decompiled sources to compare against. Run: ./rinth setup");
        }

        Log.step("Indexing the decompiled " + config.minecraftVersion() + " sources");
        CopyCheck check = CopyCheck.indexVendorTree(vendor, window);

        List<String> files = rinthJavaFiles(ws);
        Log.step("Checking " + files.size() + " Rinth Java files (" + window + "-token windows)");
        List<CopyCheck.Finding> findings = new ArrayList<>();
        for (String file : files) {
            String source = Files.readString(ws.repoRoot().resolve(file), StandardCharsets.UTF_8);
            findings.addAll(check.check(file, source));
        }

        if (!findings.isEmpty()) {
            System.err.println("Code identical to the decompiled game (write Rinth code fresh, never copy it):");
            findings.forEach(f -> System.err.println("  " + f));
            return 1;
        }
        Log.step("Copy check passed: no Rinth file repeats vendor code");
        return 0;
    }

    /** Tracked and untracked-but-not-ignored Java files outside {@code work/}. */
    private static List<String> rinthJavaFiles(Workspace ws) throws Exception {
        String out = new Git(ws.repoRoot())
                .run("ls-files", "--cached", "--others", "--exclude-standard", "-z", "--", "*.java");
        List<String> files = new ArrayList<>();
        for (String file : out.split("\0", -1)) {
            if (!file.isEmpty()
                    && !file.startsWith("work/")
                    && Files.isRegularFile(ws.repoRoot().resolve(file))) {
                files.add(file);
            }
        }
        files.sort(null);
        return files;
    }
}
