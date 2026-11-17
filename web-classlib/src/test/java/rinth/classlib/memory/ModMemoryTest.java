package rinth.classlib.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import rinth.classlib.substitutes.java.lang.foreign.TMemorySegment;
import rinth.classlib.substitutes.java.lang.ref.TPhantomReference;
import rinth.classlib.substitutes.org.lwjgl.TPointerBuffer;
import rinth.classlib.substitutes.org.lwjgl.system.TMemoryStack;
import rinth.classlib.substitutes.org.lwjgl.system.TMemoryUtil;
import rinth.classlib.substitutes.org.lwjgl.system.TPointer;
import java.lang.ref.ReferenceQueue;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/** The off-heap memory the built-in Fabric mods use beyond the game's (Sodium's). */
class ModMemoryTest {

    @Test
    void theModsUnsafeIsFoundByReflectionAndWorksOnAddresses() throws ReflectiveOperationException {
        Field field = OffHeapUnsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        OffHeapUnsafe unsafe = (OffHeapUnsafe) field.get(null);

        long address = TMemoryUtil.nmemCalloc(1, 32);
        unsafe.putInt(address, 0x11223344);
        unsafe.putFloat(address + 4, 2.5f);
        unsafe.putLong(address + 8, -3L);
        unsafe.putShort(address + 16, (short) 7);
        unsafe.putByte(address + 18, (byte) -1);
        assertThat(TMemoryUtil.memGetInt(address)).isEqualTo(0x11223344);
        assertThat(unsafe.getFloat(address + 4)).isEqualTo(2.5f);
        assertThat(unsafe.getLong(address + 8)).isEqualTo(-3L);
        assertThat(unsafe.getShort(address + 16)).isEqualTo((short) 7);
        assertThat(unsafe.getByte(address + 18)).isEqualTo((byte) -1);

        // Overlapping copies, as Sodium duplicates the last vertex in place.
        unsafe.copyMemory(address, address + 4, 8);
        assertThat(unsafe.getInt(address + 4)).isEqualTo(0x11223344);
        assertThat(unsafe.getFloat(address + 8)).isEqualTo(2.5f);
        assertThat(unsafe.addressSize()).isEqualTo(TPointer.POINTER_SIZE);
        TMemoryUtil.nmemFree(address);
    }

    @Test
    void alignedBlocksAreAlignedAndFreeTheirWholeBlock() {
        long before = Heap.liveBytes();
        for (int alignment : new int[] {1, 8, 32, 64}) {
            long address = TMemoryUtil.nmemAlignedAlloc(alignment, 100);
            assertThat(address % alignment).isZero();
            TMemoryUtil.memSet(address, 1, 100);
            TMemoryUtil.nmemAlignedFree(address);
        }
        TMemoryUtil.nmemAlignedFree(0);
        assertThat(Heap.liveBytes()).isEqualTo(before);
        assertThatThrownBy(() -> TMemoryUtil.nmemAlignedAlloc(24, 8)).isInstanceOf(IllegalArgumentException.class);

        ByteBuffer buffer = TMemoryUtil.memAlignedAlloc(32, 16);
        assertThat(TMemoryUtil.memAddress(buffer) % 32).isZero();
        TMemoryUtil.memAlignedFree(buffer.position(4));
        assertThat(Heap.liveBytes()).isEqualTo(before);
    }

    @Test
    void pointerBuffersReadWhatAddressesHold() {
        long address = TMemoryUtil.nmemAlloc(3L * TPointer.POINTER_SIZE);
        for (int i = 0; i < 3; i++) {
            TMemoryUtil.memPutAddress(address + ((long) i << TPointer.POINTER_SHIFT), 100L * i);
        }
        TPointerBuffer pointers = TMemoryUtil.memPointerBuffer(address, 3);
        assertThat(pointers.remaining()).isEqualTo(3);
        assertThat(pointers.get(2)).isEqualTo(200L);
        assertThat(pointers.get()).isZero();
        assertThat(pointers.address()).isEqualTo(address + TPointer.POINTER_SIZE);
        pointers.put(1, 5L);
        assertThat(TMemoryUtil.memGetAddress(address + TPointer.POINTER_SIZE)).isEqualTo(5L);
        assertThatThrownBy(() -> pointers.get(3)).isInstanceOf(IndexOutOfBoundsException.class);
        TMemoryUtil.nmemFree(address);
    }

    @Test
    void addressesOfBufferBytesCountFromTheStart() {
        ByteBuffer buffer = TMemoryUtil.memAlloc(16);
        long start = TMemoryUtil.memAddress(buffer);
        buffer.position(8);
        assertThat(TMemoryUtil.memAddress(buffer, 4)).isEqualTo(start + 4);
        TMemoryUtil.memFree(buffer.position(0));
    }

    @Test
    void stackAllocationsAreAlignedForPointers() {
        try (TMemoryStack stack = TMemoryStack.stackPush()) {
            stack.nmalloc(1, 3);
            assertThat(stack.nmalloc(5) % TPointer.POINTER_SIZE).isZero();
        }
    }

    @Test
    void segmentsWrapAddresses() {
        long address = TMemoryUtil.nmemCalloc(1, 16);
        TMemorySegment segment = TMemorySegment.ofAddress(address);
        assertThat(segment.byteSize()).isZero();
        TMemorySegment sized = segment.reinterpret(16);
        assertThat(sized.address()).isEqualTo(address);
        TMemoryUtil.memPutInt(address + 4, 0x01020304);

        ByteBuffer bytes = sized.asSlice(4, 8).asByteBuffer();
        assertThat(bytes.capacity()).isEqualTo(8);
        assertThat(bytes.order()).isEqualTo(ByteOrder.BIG_ENDIAN);
        assertThat(bytes.get(0)).isEqualTo((byte) 4);
        assertThat(sized.asSlice(12).byteSize()).isEqualTo(4);
        assertThatThrownBy(() -> sized.asSlice(12, 8)).isInstanceOf(IndexOutOfBoundsException.class);
        TMemoryUtil.nmemFree(address);
    }

    @Test
    void phantomReferencesNeverGiveTheirReferentBack() {
        Object referent = new Object();
        TPhantomReference<Object> reference = new TPhantomReference<>(referent, new ReferenceQueue<>());
        assertThat(reference.get()).isNull();
        assertThat(reference.refersTo(referent)).isTrue();
    }
}
