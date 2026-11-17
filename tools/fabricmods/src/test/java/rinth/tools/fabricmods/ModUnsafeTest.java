package rinth.tools.fabricmods;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

class ModUnsafeTest {

    /** A class like Sodium's MemoryIntrinsics: an Unsafe field, its class literal and an address write. */
    private static byte[] intrinsics() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/Intrinsics", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "UNSAFE", "Lsun/misc/Unsafe;", null, null)
                .visitEnd();
        MethodVisitor code = writer.visitMethod(Opcodes.ACC_STATIC, "write", "(JI)Ljava/lang/Class;", null, null);
        code.visitCode();
        code.visitFieldInsn(Opcodes.GETSTATIC, "example/Intrinsics", "UNSAFE", "Lsun/misc/Unsafe;");
        code.visitVarInsn(Opcodes.LLOAD, 0);
        code.visitVarInsn(Opcodes.ILOAD, 2);
        code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "sun/misc/Unsafe", "putInt", "(JI)V", false);
        code.visitLdcInsn(Type.getObjectType("sun/misc/Unsafe"));
        code.visitInsn(Opcodes.ARETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void theModsUnsafeIsWebClasslibs() {
        byte[] redirected = Objects.requireNonNull(ModUnsafe.redirect(intrinsics()));
        assertThat(References.of(redirected)).contains(ModUnsafe.OFF_HEAP).doesNotContain(ModUnsafe.UNSAFE);
    }

    @Test
    void leavesClassesWithoutUnsafeAlone() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/Plain", null, "java/lang/Object", null);
        writer.visitEnd();
        assertThat(ModUnsafe.redirect(writer.toByteArray())).isNull();
    }
}
