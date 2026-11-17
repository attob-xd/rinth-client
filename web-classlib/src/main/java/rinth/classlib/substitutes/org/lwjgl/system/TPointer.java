package rinth.classlib.substitutes.org.lwjgl.system;

/**
 * LWJGL's {@code Pointer}: the pointer sizes, for the built-in Fabric mods (Sodium lays out its draw
 * batches with them). Eight bytes, as {@link TMemoryUtil#memGetAddress} reads them here, like
 * 64-bit LWJGL; the browser's addresses are 32-bit values.
 */
public interface TPointer {

    int POINTER_SIZE = 8;

    int POINTER_SHIFT = 3;

    int CLONG_SIZE = 8;

    int CLONG_SHIFT = 3;

    boolean BITS32 = false;

    boolean BITS64 = true;

    long address();
}
