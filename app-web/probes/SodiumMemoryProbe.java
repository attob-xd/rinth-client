import java.lang.foreign.MemorySegment;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.nio.ByteBuffer;
import net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.GLDrawBatch;
import org.lwjgl.system.MemoryUtil;

/**
 * Check, with Sodium built in: the native memory Sodium writes vertices and draw batches to works
 * in the browser (web-classlib's OffHeapUnsafe, Pointer, aligned blocks, pointer buffers,
 * MemorySegment and PhantomReference; tools/fabricmods' Unsafe redirect). Compile it against
 * fabric/runtime/build/fabric-mods/classes and the game's class path. Prints "probe passed" when
 * every step reads back what it wrote.
 */
public final class SodiumMemoryProbe {

    private SodiumMemoryProbe() {}

    public static void main(String[] args) {
        StringBuilder failures = new StringBuilder();
        step(failures, "MemoryIntrinsics", SodiumMemoryProbe::intrinsics);
        step(failures, "GLDrawBatch", SodiumMemoryProbe::drawBatch);
        step(failures, "MemorySegment", SodiumMemoryProbe::segments);
        step(failures, "PhantomReference", () -> new PhantomReference<>(new Object(), new ReferenceQueue<>()).get() == null);
        System.out.println(failures.isEmpty() ? "probe passed" : "probe failed: " + failures);
    }

    private interface Step {
        boolean run() throws Throwable;
    }

    private static void step(StringBuilder failures, String name, Step step) {
        try {
            if (!step.run()) {
                failures.append(name).append(" read back the wrong values; ");
            }
        } catch (Throwable e) {
            failures.append(name).append(": ").append(e).append("; ");
        }
    }

    private static boolean intrinsics() {
        long address = MemoryUtil.nmemCalloc(1, 32);
        MemoryIntrinsics.putInt(address, 0x11223344);
        MemoryIntrinsics.putFloat(address + 4, 2.5f);
        MemoryIntrinsics.putShort(address + 8, (short) -2);
        MemoryIntrinsics.putByte(address + 10, (byte) 7);
        MemoryIntrinsics.putAddress(address + 16, 0x1234L);
        MemoryIntrinsics.copyMemory(address, address + 24, 8);
        boolean ok = MemoryUtil.memGetInt(address + 24) == 0x11223344
                && MemoryIntrinsics.getFloat(address + 28) == 2.5f
                && MemoryIntrinsics.getShort(address + 8) == -2
                && MemoryIntrinsics.getByte(address + 10) == 7
                && MemoryIntrinsics.getAddress(address + 16) == 0x1234L;
        MemoryUtil.nmemFree(address);
        return ok;
    }

    private static boolean drawBatch() {
        GLDrawBatch batch = new GLDrawBatch(8);
        batch.put(0, 6, 40, 3);
        batch.put(1, 12, 80, 9);
        boolean ok = batch.getMaxElementCount() == 12;
        batch.delete();
        return ok;
    }

    /** As Sodium's ChunkMeshBufferBuilder holds its vertices (its formats need the game's platform). */
    private static boolean segments() {
        MemorySegment segment = MemorySegment.ofAddress(MemoryUtil.nmemRealloc(0L, 40)).reinterpret(40);
        MemoryUtil.memPutInt(segment.address() + 20, 0x0A0B0C0D);
        ByteBuffer vertices = segment.asSlice(20, 20).asByteBuffer();
        boolean ok = vertices.remaining() == 20 && vertices.get(0) == 0x0D;
        MemoryUtil.nmemFree(segment.address());
        return ok;
    }
}
