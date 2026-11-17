package rinth.classlib.additions.java.nio;

import java.nio.ByteBuffer;
import rinth.classlib.InstanceMethod;

/** {@code java.nio.ByteBuffer} methods TeaVM lacks. */
public final class ByteBufferAdditions {

    private ByteBufferAdditions() {}

    /** The {@code length} bytes from {@code index}, sharing content; this buffer is unchanged. */
    @InstanceMethod
    public static ByteBuffer slice(ByteBuffer self, int index, int length) {
        ByteBuffer view = self.duplicate();
        view.limit(index + length).position(index);
        return view.slice();
    }
}
