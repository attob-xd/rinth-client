package rinth.classlib;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** UnicodePatterns against the JDK's own {@code UNICODE_CHARACTER_CLASS}. */
class UnicodePatternsTest {

    private static final int UNICODE = Pattern.UNICODE_CHARACTER_CLASS;

    @Test
    void matchesEveryCharacterLikeTheJdk() {
        String[] regexes = {
            "\\w",
            "\\W",
            "\\d",
            "\\D",
            "\\s",
            "\\S",
            "[\\W_]",
            "[\\w-]",
            "[\\w-x]",
            "[\\W-x]",
            "[x\\s-]",
            "[\\W\\S]",
            "[\\W^]",
            "[]\\W]",
            "[^\\w]",
            "[^\\W]",
            "[^\\w.]",
            "[^\\W_]",
            "[^\\w-]",
            "[^\\s\\S]",
            "[^]\\s]",
            "[a\\s]",
            "[\\S]",
            "[^\\d\\s]",
            "[]\\w]",
            "[^\\d]",
            "[\\D&&[^x]]",
            "\\pL",
            "\\p{Lu}",
        };
        List<String> differences = new ArrayList<>();
        for (String regex : regexes) {
            Pattern ours = UnicodePatterns.compile(regex, UNICODE);
            Pattern jdk = Pattern.compile(regex, UNICODE);
            for (int c = 0; c <= Character.MAX_CODE_POINT; c++) {
                // The enclosed letters past U+FFFF aren't word characters here (see UnicodePatterns).
                if (c >= 0x1F130 && c <= 0x1F189) {
                    continue;
                }
                String text = Character.toString(c);
                if (ours.matcher(text).matches() != jdk.matcher(text).matches()) {
                    differences.add(String.format("%s on U+%04X", regex, c));
                }
            }
        }
        assertThat(differences).isEmpty();
    }

    @Test
    void splitsAndFindsLikeTheJdk() {
        String text = "Render Distance_\u00e9t\u00e9 \u0663\u0664 (\u24b6) chunk-builder\u2003x\u200cy \\w";
        for (String regex : new String[] {"[\\W_]+", "\\s+", "\\d+"}) {
            assertThat(UnicodePatterns.compile(regex, UNICODE).split(text))
                    .as(regex)
                    .containsExactly(Pattern.compile(regex, UNICODE).split(text));
        }
        for (String regex : new String[] {"\\w+", "\\S+\\s\\d", "\\Q\\w\\E", "(?<=\\s)\\w", "[a-z\\d]+"}) {
            assertThat(found(UnicodePatterns.compile(regex, UNICODE), text))
                    .as(regex)
                    .isEqualTo(found(Pattern.compile(regex, UNICODE), text));
        }
    }

    @Test
    void basicLatinBlockMatchesLikeTheJdk() {
        for (String regex : new String[] {"\\P{InBasic_Latin}", "\\p{InBasic_Latin}+"}) {
            for (String text : new String[] {"chunk-builder", "café", "٣", "a\u007F\u0080"}) {
                assertThat(UnicodePatterns.compile(regex).matcher(text).find())
                        .as(regex + " in " + text)
                        .isEqualTo(Pattern.compile(regex).matcher(text).find());
            }
        }
    }

    @Test
    void keepsTheOtherFlags() {
        Pattern plain = UnicodePatterns.compile("a\\w", Pattern.CASE_INSENSITIVE);
        assertThat(plain.pattern()).isEqualTo("a\\w");
        assertThat(plain.flags()).isEqualTo(Pattern.CASE_INSENSITIVE);

        // The flag implies UNICODE_CASE.
        Pattern caseless = UnicodePatterns.compile("\u00e9", Pattern.CASE_INSENSITIVE | UNICODE);
        assertThat(caseless.matcher("\u00c9").matches()).isTrue();
        assertThat(caseless.flags()).isEqualTo(Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

        assertThat(UnicodePatterns.compile("\\w", Pattern.LITERAL | UNICODE)
                        .matcher("\\w")
                        .matches())
                .isTrue();
        Pattern commented = UnicodePatterns.compile("\\w+ # a [comment\n \\d", Pattern.COMMENTS | UNICODE);
        assertThat(commented.matcher("\u00e9t\u00e9\u0663").matches()).isTrue();
    }

    @Test
    void refusesWhatItDoesntRewrite() {
        String[] regexes = {"\\b\u00e9", "x\\B", "\\p{Alpha}", "\\p{IsPunct}", "[^a[\\S]]", "[\\w&&[^_]]", "[a[b]\\W]"};
        for (String regex : regexes) {
            assertThatThrownBy(() -> UnicodePatterns.compile(regex, UNICODE))
                    .as(regex)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("isn't supported in the browser build");
        }
    }

    private static List<String> found(Pattern pattern, String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }
}
