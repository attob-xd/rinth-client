package rinth.classlib.substitutes.org.lwjgl;

import rinth.classlib.memory.Heap;
import rinth.classlib.substitutes.org.lwjgl.system.TPointer;
import java.nio.ByteBuffer;

/**
 * LWJGL's {@code PointerBuffer}, over {@link Heap}: pointers of {@link TPointer#POINTER_SIZE} bytes
 * from an address, with a position and limit as a NIO buffer has. Sodium hands one to the render
 * pass's {@code multiDrawIndexed}. Not a {@code CustomBuffer}: the browser build has none.
 */
public final class TPointerBuffer implements TPointer {

    private final long address;
    private final int capacity;
    private int position;
    private int limit;

    private TPointerBuffer(long address, int capacity) {
        this.address = address;
        this.capacity = capacity;
        this.limit = capacity;
    }

    /** A buffer over {@code capacity} pointers at {@code address}, as {@code memPointerBuffer} makes. */
    public static TPointerBuffer create(long address, int capacity) {
        return new TPointerBuffer(address, capacity);
    }

    /** {@code capacity} pointers in a new block, which the collector frees with the buffer. */
    public static TPointerBuffer allocateDirect(int capacity) {
        return create(Heap.address(Heap.collectedBuffer(capacity * POINTER_SIZE)), capacity);
    }

    /** The address of the pointer at the position, as LWJGL's buffers report. */
    @Override
    public long address() {
        return address + (long) position * POINTER_SIZE;
    }

    public long address0() {
        return address;
    }

    public int capacity() {
        return capacity;
    }

    public int position() {
        return position;
    }

    public TPointerBuffer position(int position) {
        if (position < 0 || position > limit) {
            throw new IllegalArgumentException("Position " + position + " outside 0.." + limit);
        }
        this.position = position;
        return this;
    }

    public int limit() {
        return limit;
    }

    public TPointerBuffer limit(int limit) {
        if (limit < 0 || limit > capacity) {
            throw new IllegalArgumentException("Limit " + limit + " outside 0.." + capacity);
        }
        this.limit = limit;
        position = Math.min(position, limit);
        return this;
    }

    public int remaining() {
        return limit - position;
    }

    public boolean hasRemaining() {
        return position < limit;
    }

    public TPointerBuffer flip() {
        limit = position;
        position = 0;
        return this;
    }

    public TPointerBuffer rewind() {
        position = 0;
        return this;
    }

    public TPointerBuffer clear() {
        position = 0;
        limit = capacity;
        return this;
    }

    public long get() {
        return Heap.getLong(slot(position++));
    }

    public long get(int index) {
        return Heap.getLong(slot(index));
    }

    public TPointerBuffer put(long pointer) {
        Heap.putLong(slot(position++), pointer);
        return this;
    }

    public TPointerBuffer put(int index, long pointer) {
        Heap.putLong(slot(index), pointer);
        return this;
    }

    /** Puts the address of {@code buffer}'s position. */
    public TPointerBuffer put(ByteBuffer buffer) {
        return put(Heap.address(buffer));
    }

    private long slot(int index) {
        if (index < 0 || index >= limit) {
            throw new IndexOutOfBoundsException("Pointer " + index + " outside 0.." + limit);
        }
        return address + (long) index * POINTER_SIZE;
    }
}
