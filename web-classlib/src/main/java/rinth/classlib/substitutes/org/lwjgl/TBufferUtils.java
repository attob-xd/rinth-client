package rinth.classlib.substitutes.org.lwjgl;

import java.nio.ByteBuffer;
import rinth.classlib.memory.Heap;

/**
 * LWJGL's {@code BufferUtils}: zeroed, native-order buffers the garbage collector frees, which in
 * the browser build are TeaVM's direct buffers in linear memory ({@link Heap#collectedBuffer}).
 * The audio decoder uses it for every chunk it decodes, so nothing keeps them.
 */
public final class TBufferUtils {

    private TBufferUtils() {}

    public static ByteBuffer createByteBuffer(int capacity) {
        return Heap.collectedBuffer(capacity);
    }
}
