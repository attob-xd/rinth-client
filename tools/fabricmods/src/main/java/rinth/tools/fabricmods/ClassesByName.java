package rinth.tools.fabricmods;

import java.util.Collection;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * {@code Class.forName} for the built-in mods' own classes. The browser build keeps only the
 * classes some code reaches and has no table of names for the rest, so a mod that makes its
 * implementations from their names (Sodium's {@code DependencyInjection}) finds nothing. The mods'
 * {@code Class.forName} calls go to a generated {@code forName} instead, which answers the names
 * {@link ReflectedClasses} found with those classes' constants, and asks {@code Class.forName}
 * about any other.
 */
final class ClassesByName {

    static final String NAME = "rinth/fabric/generated/ClassesByName";

    private static final String FOR_NAME = "(Ljava/lang/String;)Ljava/lang/Class;";
    private static final String FOR_NAME_LOADER = "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;";

    private ClassesByName() {}

    /** The lookup class, answering {@code classes} (internal names). */
    static byte[] write(Collection<String> classes) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                NAME,
                null,
                "java/lang/Object",
                null);

        MethodVisitor find = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "find", FOR_NAME, null, null);
        find.visitCode();
        for (String name : classes) {
            Label next = new Label();
            find.visitLdcInsn(name.replace('/', '.'));
            find.visitVarInsn(Opcodes.ALOAD, 0);
            find.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
            find.visitJumpInsn(Opcodes.IFEQ, next);
            find.visitLdcInsn(Type.getObjectType(name));
            find.visitInsn(Opcodes.ARETURN);
            find.visitLabel(next);
        }
        find.visitInsn(Opcodes.ACONST_NULL);
        find.visitInsn(Opcodes.ARETURN);
        find.visitMaxs(0, 0);
        find.visitEnd();

        forName(writer, FOR_NAME, 1);
        forName(writer, FOR_NAME_LOADER, 3);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** {@code forName(name, ...)}: {@code find(name)}, else {@code Class.forName} with the same arguments. */
    private static void forName(ClassWriter writer, String descriptor, int arguments) {
        MethodVisitor code =
                writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "forName", descriptor, null, new String[] {
                    "java/lang/ClassNotFoundException"
                });
        code.visitCode();
        Label notFound = new Label();
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, NAME, "find", FOR_NAME, false);
        code.visitInsn(Opcodes.DUP);
        code.visitJumpInsn(Opcodes.IFNULL, notFound);
        code.visitInsn(Opcodes.ARETURN);
        code.visitLabel(notFound);
        code.visitInsn(Opcodes.POP);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        if (arguments == 3) {
            code.visitVarInsn(Opcodes.ILOAD, 1);
            code.visitVarInsn(Opcodes.ALOAD, 2);
        }
        code.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", descriptor, false);
        code.visitInsn(Opcodes.ARETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
    }

    /** {@code bytes} with its {@code Class.forName} calls sent to the lookup, or null if it makes none. */
    static byte @Nullable [] redirect(byte[] bytes) {
        return StaticCalls.redirect(bytes, "java/lang/Class", "forName", Set.of(FOR_NAME, FOR_NAME_LOADER), NAME);
    }
}
