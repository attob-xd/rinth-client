package rinth.classlib.memory;

/**
 * {@code sun.misc.Unsafe} for the built-in Fabric mods (ADR 0065): tools/fabricmods points their
 * references here. Mods that write vertices to native memory (Sodium's {@code MemoryIntrinsics})
 * take the instance from {@code theUnsafe} by reflection and use the methods that take an
 * address, which work over {@link Heap}: in the browser, the addresses LWJGL's {@code MemoryUtil}
 * hands out. Field offsets and other object access aren't here, so code using them fails where it's
 * reached.
 *
 * <p>The game's own {@code Unsafe} stays unavailable (web-classlib's {@code TUnsafe}): Netty, Guava
 * and JOML probe for it and must take their safe fallbacks. The mods' changed classes refer to this
 * class by name; keep its name and members stable.
 */
public final class OffHeapUnsafe {

    /** Read by reflection, as the JDK's field of the same name is. */
    @SuppressWarnings({"unused", "UnusedVariable"})
    private static final OffHeapUnsafe theUnsafe = new OffHeapUnsafe();

    private OffHeapUnsafe() {}

    /** Eight, as {@code TPointer.POINTER_SIZE} says. */
    public int addressSize() {
        return Long.BYTES;
    }

    public long allocateMemory(long bytes) {
        return Heap.allocate(bytes, false);
    }

    public long reallocateMemory(long address, long bytes) {
        return Heap.reallocate(address, bytes);
    }

    public void freeMemory(long address) {
        Heap.free(address);
    }

    /** Copies {@code bytes} bytes; the ranges may overlap. */
    public void copyMemory(long source, long target, long bytes) {
        Heap.copy(source, target, bytes);
    }

    public void setMemory(long address, long bytes, byte value) {
        Heap.fill(address, value, bytes);
    }

    public byte getByte(long address) {
        return Heap.getByte(address);
    }

    public void putByte(long address, byte value) {
        Heap.putByte(address, value);
    }

    public short getShort(long address) {
        return Heap.getShort(address);
    }

    public void putShort(long address, short value) {
        Heap.putShort(address, value);
    }

    public char getChar(long address) {
        return (char) Heap.getShort(address);
    }

    public void putChar(long address, char value) {
        Heap.putShort(address, (short) value);
    }

    public int getInt(long address) {
        return Heap.getInt(address);
    }

    public void putInt(long address, int value) {
        Heap.putInt(address, value);
    }

    public long getLong(long address) {
        return Heap.getLong(address);
    }

    public void putLong(long address, long value) {
        Heap.putLong(address, value);
    }

    public float getFloat(long address) {
        return Heap.getFloat(address);
    }

    public void putFloat(long address, float value) {
        Heap.putFloat(address, value);
    }

    public double getDouble(long address) {
        return Heap.getDouble(address);
    }

    public void putDouble(long address, double value) {
        Heap.putDouble(address, value);
    }

    /** A pointer, eight bytes as {@link #addressSize} says. */
    public long getAddress(long address) {
        return Heap.getLong(address);
    }

    public void putAddress(long address, long value) {
        Heap.putLong(address, value);
    }
}
