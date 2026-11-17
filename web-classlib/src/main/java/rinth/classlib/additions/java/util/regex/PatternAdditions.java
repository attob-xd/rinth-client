package rinth.classlib.additions.java.util.regex;

import java.util.function.Predicate;
import java.util.regex.Pattern;
import rinth.classlib.InstanceMethod;

/** {@code java.util.regex.Pattern} methods TeaVM lacks. */
public final class PatternAdditions {

    private PatternAdditions() {}

    @InstanceMethod
    public static Predicate<String> asPredicate(Pattern self) {
        return text -> self.matcher(text).find();
    }

    @InstanceMethod
    public static Predicate<String> asMatchPredicate(Pattern self) {
        return text -> self.matcher(text).matches();
    }
}
