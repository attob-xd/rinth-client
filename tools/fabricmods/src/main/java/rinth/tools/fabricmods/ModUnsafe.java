package rinth.tools.fabricmods;

import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

/**
 * The mods' {@code sun.misc.Unsafe}, as web-classlib's {@code OffHeapUnsafe}. Mods that write
 * vertices to native memory (Sodium's {@code MemoryIntrinsics}) take {@code Unsafe.theUnsafe} by
 * reflection and use its methods that take an address; those work over the browser's memory there.
 * Only the mods' own classes: the game's libraries (Netty, Guava, JOML) must still find
 * {@code Unsafe} unavailable and take their safe paths.
 */
final class ModUnsafe {

    static final String UNSAFE = "sun/misc/Unsafe";

    /** web-classlib's, which keeps this name. */
    static final String OFF_HEAP = "rinth/classlib/memory/OffHeapUnsafe";

    private ModUnsafe() {}

    /** {@code bytes} with {@code Unsafe} read as {@code OffHeapUnsafe}, or null if the class doesn't name it. */
    static byte @Nullable [] redirect(byte[] bytes) {
        if (!References.of(bytes).contains(UNSAFE)) {
            return null;
        }
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, new SimpleRemapper(Opcodes.ASM9, UNSAFE, OFF_HEAP)), 0);
        return writer.toByteArray();
    }
}
