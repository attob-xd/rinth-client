package rinth.tools.fabricmods;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class ClassesByNameTest {

    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A class whose one method calls {@code Class.forName(name)}. */
    private static byte[] caller() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/Caller", null, "java/lang/Object", null);
        MethodVisitor code = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "find", "(Ljava/lang/String;)Ljava/lang/Class;", null, null);
        code.visitCode();
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(
                Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
        code.visitInsn(Opcodes.ARETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void findsTheModsClassesAndAsksTheJvmAboutOthers() throws Exception {
        Map<String, byte[]> classes = Map.of(
                ClassesByName.NAME,
                ClassesByName.write(List.of("example/Impl")),
                "example/Impl",
                emptyClass("example/Impl"),
                "example/Caller",
                Objects.requireNonNull(ClassesByName.redirect(caller())));
        ClassLoader loader = new ClassLoader(ClassesByNameTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Method find = loader.loadClass("example.Caller").getMethod("find", String.class);

        assertThat(((Class<?>) find.invoke(null, "example.Impl")).getName()).isEqualTo("example.Impl");
        assertThat(find.invoke(null, "java.lang.String")).isEqualTo(String.class);
    }

    @Test
    void sendsOnlyForNameToTheLookup() {
        ClassNode node = new ClassNode();
        new ClassReader(Objects.requireNonNull(ClassesByName.redirect(caller()))).accept(node, 0);
        for (AbstractInsnNode instruction : node.methods.get(0).instructions) {
            if (instruction instanceof MethodInsnNode call) {
                assertThat(call.owner).isEqualTo(ClassesByName.NAME);
            }
        }
        assertThat(ClassesByName.redirect(emptyClass("example/Plain"))).isNull();
    }
}
