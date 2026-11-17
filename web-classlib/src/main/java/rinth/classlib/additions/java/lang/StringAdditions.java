package rinth.classlib.additions.java.lang;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import rinth.classlib.Constructor;
import rinth.classlib.InstanceMethod;
import rinth.classlib.Replaces;
import rinth.classlib.UnicodePatterns;

/** {@code java.lang.String} methods TeaVM lacks. */
public final class StringAdditions {

    private StringAdditions() {}

    /**
     * {@code String(byte[] ascii, int hibyte, int offset, int count)}: each byte is a character's
     * low eight bits and {@code hibyte} the high eight. Deprecated in the JDK, but Netty's
     * {@code ByteBufUtil} decodes ASCII and Latin-1 with it.
     */
    @Constructor
    public static char[] characters(byte[] ascii, int hibyte, int offset, int count) {
        if (offset < 0 || count < 0 || offset > ascii.length - count) {
            throw new StringIndexOutOfBoundsException(
                    "offset " + offset + ", count " + count + ", length " + ascii.length);
        }
        char high = (char) ((hibyte & 0xFF) << 8);
        char[] characters = new char[count];
        for (int i = 0; i < count; i++) {
            characters[i] = (char) (high | (ascii[offset + i] & 0xFF));
        }
        return characters;
    }

    /**
     * {@code replaceAll}, with the Unicode blocks TeaVM's regex lacks written as ranges
     * ({@link UnicodePatterns#blocks}): Log4j's status logger cleans property names with
     * {@code \P{InBasic_Latin}}, which a mod logging through Log4j (Sodium) reaches.
     */
    @InstanceMethod
    @Replaces
    public static String replaceAll(String self, String regex, String replacement) {
        return Pattern.compile(UnicodePatterns.blocks(regex)).matcher(self).replaceAll(replacement);
    }

    /** {@code matches}, with the Unicode blocks TeaVM's regex lacks written as ranges. */
    @InstanceMethod
    @Replaces
    public static boolean matches(String self, String regex) {
        return Pattern.compile(UnicodePatterns.blocks(regex)).matcher(self).matches();
    }

    /** The lines of the string, split at {@code \n}, {@code \r} or {@code \r\n}. */
    @InstanceMethod
    public static Stream<String> lines(String self) {
        if (self.isEmpty()) {
            return Stream.empty();
        }
        String[] parts = self.split("\r\n|\r|\n", -1);
        char last = self.charAt(self.length() - 1);
        // A trailing line terminator ends the last line rather than starting an empty one.
        int count = last == '\n' || last == '\r' ? parts.length - 1 : parts.length;
        return Arrays.stream(parts, 0, count);
    }

    /**
     * Java 21's {@code split} that keeps the delimiters, each between the parts around it, as
     * {@code Pattern.splitWithDelimiters} does. Sodium formats its version for the options screen
     * with it.
     */
    @InstanceMethod
    public static String[] splitWithDelimiters(String self, String regex, int limit) {
        List<String> parts = new ArrayList<>();
        Matcher delimiter = Pattern.compile(regex).matcher(self);
        int start = 0;
        int splits = 0;
        // A positive limit allows limit - 1 splits; the last part keeps the rest.
        while ((limit <= 0 || splits < limit - 1) && delimiter.find()) {
            if (delimiter.end() == 0) {
                continue; // A zero-width match at the start splits nothing off.
            }
            parts.add(self.substring(start, delimiter.start()));
            parts.add(delimiter.group());
            start = delimiter.end();
            splits++;
        }
        if (splits == 0) {
            return new String[] {self};
        }
        parts.add(self.substring(start));
        if (limit == 0) {
            // Trailing empty strings, parts or delimiters, are left out.
            while (!parts.isEmpty() && parts.get(parts.size() - 1).isEmpty()) {
                parts.remove(parts.size() - 1);
            }
        }
        return parts.toArray(new String[0]);
    }
}
