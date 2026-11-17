package rinth.tools.fabricmods;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What the mod build found, for whoever runs it: notes (fine, worth knowing), problems (a mod, or
 * a part of one, won't work) and the summary. Printed as it goes and written beside the output.
 */
final class Report {

    private final List<String> lines = new ArrayList<>();
    private int problems;

    void note(String line) {
        add("  " + line);
    }

    void problem(String line) {
        problems++;
        add("! " + line);
    }

    void heading(String line) {
        add("== " + line);
    }

    int problems() {
        return problems;
    }

    private void add(String line) {
        lines.add(line);
        System.out.println("[fabricmods] " + line);
    }

    void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }
}
