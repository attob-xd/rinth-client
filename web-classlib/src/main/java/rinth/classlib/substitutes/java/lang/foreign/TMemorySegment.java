package rinth.classlib.substitutes.java.lang.foreign;

import rinth.classlib.memory.Heap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * {@code java.lang.foreign.MemorySegment}, only native segments over {@link Heap} (the browser's
 * linear memory): what the built-in Fabric mods use to wrap memory from LWJGL's
 * {@code MemoryUtil} (Sodium's chunk meshes). TeaVM has no {@code java.lang.foreign}. A segment
 * here has an address and a size, and no arena: freeing the memory is still the caller's job.
 */
public interface TMemorySegment {

    /** A zero-length segment at {@code address}, as the JDK makes for a raw address. */
    static TMemorySegment ofAddress(long address) {
        return new Native(address, 0);
    }

    long address();

    long byteSize();

    /** The same memory, {@code newSize} bytes long. */
    TMemorySegment reinterpret(long newSize);

    /** {@code newSize} bytes from {@code offset}, which must lie inside this segment. */
    TMemorySegment asSlice(long offset, long newSize);

    TMemorySegment asSlice(long offset);

    /** A view of the segment's bytes, big-endian as the JDK's are. */
    ByteBuffer asByteBuffer();

    /** A native segment; not part of the JDK's API. */
    final class Native implements TMemorySegment {

        private final long address;
        private final long size;

        Native(long address, long size) {
            this.address = address;
            this.size = size;
        }

        @Override
        public long address() {
            return address;
        }

        @Override
        public long byteSize() {
            return size;
        }

        @Override
        public TMemorySegment reinterpret(long newSize) {
            if (newSize < 0) {
                throw new IllegalArgumentException("Negative size: " + newSize);
            }
            return new Native(address, newSize);
        }

        @Override
        public TMemorySegment asSlice(long offset, long newSize) {
            if (offset < 0 || newSize < 0 || offset > size - newSize) {
                throw new IndexOutOfBoundsException(
                        "Slice of " + newSize + " bytes at " + offset + " outside a segment of " + size);
            }
            return new Native(address + offset, newSize);
        }

        @Override
        public TMemorySegment asSlice(long offset) {
            return asSlice(offset, size - offset);
        }

        @Override
        public ByteBuffer asByteBuffer() {
            if (size > Integer.MAX_VALUE) {
                throw new UnsupportedOperationException("Segment too large for a buffer: " + size);
            }
            return Heap.buffer(address, (int) size).order(ByteOrder.BIG_ENDIAN);
        }

        @Override
        public String toString() {
            return "MemorySegment{address: 0x" + Long.toHexString(address) + ", byteSize: " + size + "}";
        }
    }
}
