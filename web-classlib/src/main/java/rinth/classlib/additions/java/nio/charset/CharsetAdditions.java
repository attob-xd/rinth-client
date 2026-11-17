package rinth.classlib.additions.java.nio.charset;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;

/** {@code java.nio.charset.Charset} methods TeaVM lacks. */
public final class CharsetAdditions {

    private CharsetAdditions() {}

    /**
     * Whether a charset of that name is available: whether {@link Charset#forName} finds one. Log4j's
     * {@code PropertiesUtil} asks before reading its properties, which a mod logging through Log4j
     * (Sodium) reaches.
     *
     * @throws IllegalCharsetNameException if the name isn't a legal charset name, as the JDK does
     * @throws IllegalArgumentException if {@code charsetName} is null, as the JDK does
     */
    public static boolean isSupported(String charsetName) {
        if (charsetName == null) {
            throw new IllegalArgumentException("Null charset name");
        }
        try {
            Charset.forName(charsetName);
            return true;
        } catch (UnsupportedCharsetException e) {
            return false;
        }
    }
}
