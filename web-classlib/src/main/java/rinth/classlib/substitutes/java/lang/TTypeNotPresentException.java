package rinth.classlib.substitutes.java.lang;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.lang.TypeNotPresentException}, which TeaVM lacks: a type named by a string that
 * can't be found. ASM's {@code ClassWriter} throws it, and a Fabric mod (Sodium) reaches ASM.
 */
public class TTypeNotPresentException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = -5101214195716534496L;

    private final String typeName;

    public TTypeNotPresentException(String typeName, @Nullable Throwable cause) {
        super("Type " + typeName + " not present", cause);
        this.typeName = typeName;
    }

    public String typeName() {
        return typeName;
    }
}
