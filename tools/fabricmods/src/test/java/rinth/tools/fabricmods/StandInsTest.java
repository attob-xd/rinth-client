package rinth.tools.fabricmods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class StandInsTest {

    /** A mod class that uses three classes nothing has: one static call, one interface, one exception. */
    private static byte[] modClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/Mod", null, "java/lang/Object", null);
        MethodVisitor code =
                writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(Lexample/Thing;)I", null, null);
        code.visitCode();
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        code.visitTryCatchBlock(start, end, handler, "example/Oops");
        code.visitLabel(start);
        code.visitMethodInsn(Opcodes.INVOKESTATIC, "example/Gone", "answer", "()I", false);
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitMethodInsn(Opcodes.INVOKEINTERFACE, "example/Thing", "size", "()I", true);
        code.visitInsn(Opcodes.IADD);
        code.visitLabel(end);
        code.visitInsn(Opcodes.IRETURN);
        code.visitLabel(handler);
        code.visitInsn(Opcodes.ICONST_0);
        code.visitInsn(Opcodes.IRETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Map<String, byte[]> standIns() throws Exception {
        try (ClassIndex index = new ClassIndex(List.of())) {
            StandIns standIns = new StandIns(index);
            standIns.scan(modClass());
            return standIns.write();
        }
    }

    @Test
    void standsInForWhatTheModUses() throws Exception {
        assertThat(standIns()).containsOnlyKeys("example/Gone", "example/Oops", "example/Thing");
    }

    @Test
    void failsOnlyWhenReached() throws Exception {
        Map<String, byte[]> written = standIns();
        ClassLoader loader = new ClassLoader(StandInsTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = written.get(name.replace('.', '/'));
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };

        Method answer = loader.loadClass("example.Gone").getMethod("answer");
        assertThat(Modifier.isStatic(answer.getModifiers())).isTrue();
        assertThatThrownBy(() -> answer.invoke(null))
                .isInstanceOf(InvocationTargetException.class)
                .cause()
                .isInstanceOf(NoClassDefFoundError.class)
                .hasMessageContaining("example.Gone");

        Class<?> thing = loader.loadClass("example.Thing");
        assertThat(thing.isInterface()).isTrue();
        assertThat(Modifier.isAbstract(thing.getMethod("size").getModifiers())).isTrue();

        assertThat(loader.loadClass("example.Oops").getSuperclass()).isEqualTo(RuntimeException.class);
    }
}
