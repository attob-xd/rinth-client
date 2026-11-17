package rinth.classlib;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code Pattern.compile(regex, flags)} with {@link Pattern#UNICODE_CHARACTER_CLASS}, which TeaVM's
 * regex refuses (it takes no flag above {@code CANON_EQ}). tools/fabricmods sends the built-in
 * Fabric mods' calls here (ADR 0065): Sodium compiles its options search with the flag. Under it,
 * {@code \w \W \d \D \s \S} are written as the Unicode categories the JDK documents for them, which
 * TeaVM's regex has, and {@code UNICODE_CASE} is set, as the flag implies. The pattern reports the
 * rewritten expression, and its flags without this one.
 *
 * <p>TeaVM's regex loses the characters of a class that also has categories when the class is
 * negated, or when they're joined to a class of complements (app-web/probes' UnicodePatternsProbe).
 * So the complements are written as intersections, a negated class that has a predefined class
 * becomes the intersection of complements it means, and a class with {@code \W} or {@code \S}
 * becomes alternatives.
 *
 * <p>Unlike the JDK, the 78 enclosed Latin letters past U+FFFF (U+1F130 to U+1F189) aren't word
 * characters: TeaVM's regex can't leave a character past U+FFFF out of a negated class, so
 * {@code \w} and {@code \W} couldn't agree on them. A pattern this doesn't rewrite is refused, as
 * TeaVM refuses the flag: POSIX classes ({@code \p{Alpha}}…), word boundaries, and {@code \w},
 * {@code \W}, {@code \s} or {@code \S} in a class with nested classes or intersections.
 *
 * <p>The mods' changed classes refer to this class by name; keep its name and members stable.
 */
public final class UnicodePatterns {

    /** Alphabetic characters (letters, letter numbers, enclosed letters), marks, decimal digits, connectors, joiners. */
    private static final String WORD = "\\p{L}\\p{Nl}\\u24B6-\\u24E9\\p{Mn}\\p{Mc}\\p{Me}\\p{Nd}\\p{Pc}\\u200C\\u200D";

    private static final String NOT_WORD =
            "[\\P{L}&&\\P{Nl}&&\\P{Mn}&&\\P{Mc}&&\\P{Me}&&\\P{Nd}&&\\P{Pc}&&[^\\u24B6-\\u24E9\\u200C\\u200D]]";

    /** Unicode's white space: the separators and six controls. */
    private static final String SPACE = "\\p{Z}\\t\\n\\u000B\\f\\r\\u0085";

    private static final String NOT_SPACE = "[\\P{Z}&&[^\\t\\n\\u000B\\f\\r\\u0085]]";

    /** The POSIX classes that change under the flag: their Unicode versions need properties TeaVM lacks. */
    private static final Set<String> POSIX = Set.of(
            "Lower", "Upper", "Alpha", "Digit", "Alnum", "Punct", "Graph", "Print", "Blank", "Cntrl", "XDigit",
            "Space");

    private UnicodePatterns() {}

    /** {@link Pattern#compile(String)}, with the Unicode blocks TeaVM's regex lacks written as ranges. */
    public static Pattern compile(String regex) {
        return compile(regex, 0);
    }

    /** {@link Pattern#compile(String, int)}, rewriting the expression when {@code flags} has the flag. */
    public static Pattern compile(String regex, int flags) {
        if ((flags & Pattern.LITERAL) == 0) {
            regex = blocks(regex);
        }
        if ((flags & Pattern.UNICODE_CHARACTER_CLASS) == 0) {
            return Pattern.compile(regex, flags);
        }
        int others = (flags & ~Pattern.UNICODE_CHARACTER_CLASS) | Pattern.UNICODE_CASE;
        boolean literal = (flags & Pattern.LITERAL) != 0;
        return Pattern.compile(literal ? regex : rewrite(regex, (flags & Pattern.COMMENTS) != 0), others);
    }

    /**
     * {@code regex} with {@code \p{InBasic_Latin}} and {@code \P{InBasic_Latin}} written as the
     * character ranges they are, which TeaVM's regex has; it has no Unicode blocks (Sodium's chunk
     * builder tests names for non-ASCII characters with one). Other blocks are left to be refused.
     */
    public static String blocks(String regex) {
        if (!regex.contains("{InBasic_Latin}") && !regex.contains("{IsBasic_Latin}")) {
            return regex;
        }
        return regex.replace("\\p{InBasic_Latin}", "[\\u0000-\\u007F]")
                .replace("\\P{InBasic_Latin}", "[^\\u0000-\\u007F]")
                .replace("\\p{IsBasic_Latin}", "[\\u0000-\\u007F]")
                .replace("\\P{IsBasic_Latin}", "[^\\u0000-\\u007F]");
    }

    /** {@code regex} with its predefined classes written as Unicode categories. */
    static String rewrite(String regex, boolean comments) {
        StringBuilder out = new StringBuilder(regex.length() + 64);
        int i = 0;
        while (i < regex.length()) {
            char c = regex.charAt(i);
            if (c == '\\' && i + 1 < regex.length()) {
                String unicode =
                        switch (regex.charAt(i + 1)) {
                            case 'w' -> "[" + WORD + "]";
                            case 'W' -> NOT_WORD;
                            case 's' -> "[" + SPACE + "]";
                            case 'S' -> NOT_SPACE;
                            default -> null;
                        };
                if (unicode == null) {
                    i = escape(regex, i, false, out);
                } else {
                    out.append(unicode);
                    i += 2;
                }
            } else if (c == '[') {
                i = characterClass(regex, i, comments, out);
            } else if (c == '#' && comments) {
                i = comment(regex, i, out);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Writes the class at {@code start}, rewritten; returns where it ends. */
    private static int characterClass(String regex, int start, boolean comments, StringBuilder out) {
        boolean negated = start + 1 < regex.length() && regex.charAt(start + 1) == '^';
        int i = start + (negated ? 2 : 1);
        // The class's other items, with \w and \s among them unless the class is negated.
        StringBuilder items = new StringBuilder();
        if (i < regex.length() && regex.charAt(i) == ']') {
            items.append(']'); // A ']' first in a class is one of its characters.
            i++;
        }
        boolean word = false;
        boolean notWord = false;
        boolean space = false;
        boolean notSpace = false;
        boolean nested = false;
        int depth = 1;
        while (i < regex.length() && depth > 0) {
            char c = regex.charAt(i);
            if (c == '\\' && i + 1 < regex.length()) {
                char escaped = regex.charAt(i + 1);
                if ("wWsS".indexOf(escaped) < 0) {
                    i = escape(regex, i, true, items);
                    continue;
                }
                if (escaped == 'w' && !negated) {
                    items.append(WORD);
                } else if (escaped == 's' && !negated) {
                    items.append(SPACE);
                }
                word |= escaped == 'w';
                notWord |= escaped == 'W';
                space |= escaped == 's';
                notSpace |= escaped == 'S';
                i += 2;
                if (i < regex.length() && regex.charAt(i) == '-') {
                    items.append("\\-"); // A '-' after a class is a character, not a range.
                    i++;
                }
            } else if (c == '[') {
                nested = true;
                depth++;
                items.append(c);
                i++;
            } else if (c == ']') {
                depth--;
                if (depth > 0) {
                    items.append(c);
                }
                i++;
            } else if (c == '&' && regex.startsWith("&&", i)) {
                nested = true;
                items.append("&&");
                i += 2;
            } else if (c == '#' && comments) {
                i = comment(regex, i, items);
            } else {
                items.append(c);
                i++;
            }
        }
        if (depth > 0 || !(word || notWord || space || notSpace)) {
            // Unclosed (TeaVM's regex reports it), or without the classes that need more.
            out.append(negated ? "[^" : "[").append(items).append(depth > 0 ? "" : "]");
            return i;
        }
        if (nested) {
            throw refused("\\w, \\W, \\s or \\S in a class with nested classes or intersections", regex);
        }
        List<String> parts = new ArrayList<>();
        if (negated) {
            // None of them: their complements, intersected.
            if (word) {
                parts.add(NOT_WORD);
            }
            if (notWord) {
                parts.add("[" + WORD + "]");
            }
            if (space) {
                parts.add(NOT_SPACE);
            }
            if (notSpace) {
                parts.add("[" + SPACE + "]");
            }
            if (!items.isEmpty()) {
                parts.add("[^" + items + "]");
            }
            out.append(parts.size() == 1 ? parts.get(0) : "[" + String.join("&&", parts) + "]");
        } else {
            // Any of them: alternatives.
            if (!items.isEmpty()) {
                parts.add((items.charAt(0) == '^' ? "[\\" : "[") + items + "]");
            }
            if (notWord) {
                parts.add(NOT_WORD);
            }
            if (notSpace) {
                parts.add(NOT_SPACE);
            }
            out.append(parts.size() == 1 ? parts.get(0) : "(?:" + String.join("|", parts) + ")");
        }
        return i;
    }

    /** Writes the escape at {@code i}, with {@code \d} and {@code \D} as categories; returns where the next part starts. */
    private static int escape(String regex, int i, boolean inClass, StringBuilder out) {
        char escaped = regex.charAt(i + 1);
        switch (escaped) {
            case 'd' -> out.append("\\p{Nd}");
            case 'D' -> out.append("\\P{Nd}");
            case 'b', 'B' -> {
                if (!inClass) {
                    throw refused("A word boundary", regex);
                }
                out.append(regex, i, i + 2);
            }
            case 'Q' -> {
                int end = regex.indexOf("\\E", i + 2);
                end = end < 0 ? regex.length() : end + 2;
                out.append(regex, i, end);
                return end;
            }
            case 'c' -> {
                // \c takes the next character, whatever it is.
                int end = Math.min(i + 3, regex.length());
                out.append(regex, i, end);
                return end;
            }
            case 'p', 'P' -> {
                boolean braced = i + 2 < regex.length() && regex.charAt(i + 2) == '{';
                int close = braced ? regex.indexOf('}', i + 3) : -1;
                int end = braced ? (close < 0 ? regex.length() : close + 1) : Math.min(i + 3, regex.length());
                String name = regex.substring(i + (braced ? 3 : 2), braced && close >= 0 ? close : end);
                if (POSIX.contains(name.startsWith("Is") ? name.substring(2) : name)) {
                    throw refused("The POSIX class \\p{" + name + "}", regex);
                }
                out.append(regex, i, end);
                return end;
            }
            default -> out.append(regex, i, i + 2);
        }
        return i + 2;
    }

    /** Writes the comment at {@code i}, which runs to the end of the line (in a class too); returns where it ends. */
    private static int comment(String regex, int i, StringBuilder out) {
        int end = regex.indexOf('\n', i);
        end = end < 0 ? regex.length() : end;
        out.append(regex, i, end);
        return end;
    }

    private static IllegalArgumentException refused(String what, String regex) {
        return new IllegalArgumentException(
                what + " under UNICODE_CHARACTER_CLASS isn't supported in the browser build: " + regex);
    }
}
