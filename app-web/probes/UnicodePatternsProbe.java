import rinth.classlib.UnicodePatterns;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Regression, for web-classlib's UnicodePatterns: TeaVM's regex refuses
 * {@code UNICODE_CHARACTER_CLASS}, and Sodium's options search compiled {@code [\W_]+} with it, so
 * the game crashed as Sodium registered its options. The Unicode categories UnicodePatterns writes
 * must match in TeaVM's regex as the JDK's flag does (its tests compare them on the JDK). Prints
 * "probe passed" when every pattern matches what the JDK matches.
 */
public final class UnicodePatternsProbe {

    private static final String WORD = "ax_éⅣ́٣‿‌Ⓐ";
    private static final String SPACE = " \t  \u0085";
    private static final String OTHER = "!-🄰😀";

    private UnicodePatternsProbe() {}

    public static void main(String[] args) {
        String all = WORD + SPACE + OTHER;
        String notWord = SPACE + OTHER;
        String notSpace = WORD + OTHER;
        String[][] cases = {
            {"\\w", WORD},
            {"\\W", notWord},
            {"[\\W_]", notWord + "_"},
            {"[\\w!]", WORD + "!"},
            {"[\\w-!]", WORD + "-!"},
            {"[\\W\\S]", all},
            {"[^\\w]", notWord},
            {"[^\\W_]", WORD.replace("_", "")},
            {"[^\\w!]", notWord.replace("!", "")},
            {"[^\\s\\d]", notSpace.replace("٣", "")},
            {"\\s", SPACE},
            {"\\S", notSpace},
            {"[\\S\t]", notSpace + "\t"},
            {"\\d", "٣"},
            {"[\\D]", all.replace("٣", "")},
        };
        StringBuilder failures = new StringBuilder();
        for (String[] test : cases) {
            try {
                Pattern pattern = UnicodePatterns.compile(test[0], Pattern.UNICODE_CHARACTER_CLASS);
                String matched = all.codePoints()
                        .filter(c -> pattern.matcher(Character.toString(c)).matches())
                        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                        .toString();
                if (!sorted(matched).equals(sorted(test[1]))) {
                    failures.append(test[0]).append(" matched ").append(codes(matched)).append(", not ")
                            .append(codes(test[1])).append("; ");
                }
            } catch (Throwable e) {
                failures.append(test[0]).append(": ").append(e).append("; ");
            }
        }
        String[] words = UnicodePatterns.compile("[\\W_]+", Pattern.UNICODE_CHARACTER_CLASS)
                .split("Render Distance_été ٣(Ⓐ)");
        if (!Arrays.equals(words, new String[] {"Render", "Distance", "été", "٣", "Ⓐ"})) {
            failures.append("Sodium's split gave ").append(Arrays.toString(words)).append("; ");
        }
        // Sodium's chunk builder: a name with anything outside Basic Latin, compiled without flags.
        try {
            Pattern notAscii = UnicodePatterns.compile("\\P{InBasic_Latin}");
            String[][] blocks = {{"chunk-builder", "false"}, {"café", "true"}, {"a\u007F", "false"}, {"\u0080", "true"}};
            for (String[] test : blocks) {
                if (notAscii.matcher(test[0]).find() != Boolean.parseBoolean(test[1])) {
                    failures.append("\\P{InBasic_Latin} in ").append(codes(test[0])).append(" wasn't ")
                            .append(test[1]).append("; ");
                }
            }
            // Log4j's status logger, which Sodium's logging reaches: String.replaceAll with the block.
            String cleaned = "log4j.été".replaceAll("\\P{InBasic_Latin}", "");
            if (!cleaned.equals("log4j.t")) {
                failures.append("replaceAll gave ").append(cleaned).append("; ");
            }
        } catch (Throwable e) {
            failures.append("\\P{InBasic_Latin}: ").append(e).append("; ");
        }
        System.out.println(failures.isEmpty() ? "probe passed" : "probe failed: " + failures);
    }

    private static String sorted(String text) {
        return text.codePoints().sorted().distinct().mapToObj(Character::toString).collect(Collectors.joining());
    }

    private static String codes(String text) {
        return sorted(text).codePoints().mapToObj(c -> String.format("U+%04X", c)).collect(Collectors.joining(" "));
    }
}
