package rinth.tools.fabricmods;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class MethodHandleCallsTest {

    /**
     * Stands in for web-classlib's ReflectiveHandles on this JVM: real handles, called. Public, as
     * the bridges are loaded by another class loader.
     */
    public static final class Handles {

        private Handles() {}

        public static Object invoke(MethodHandle handle, Object[] arguments) throws Throwable {
            return handle.invokeWithArguments(arguments);
        }
    }

    public static long scaled(int value, double factor, String unit) {
        return Math.round(value * factor) + unit.length();
    }

    public static void ignored(long value) {}

    /** A class like Sodium's SimpleFrustum: a getter made with unreflectGetter, called with invokeExact. */
    private static byte[] caller() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/Frustum", null, "java/lang/Object", null);
        MethodVisitor code = writer.visitMethod(
                Opcodes.ACC_STATIC,
                "planes",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/reflect/Field;Ljava/lang/Object;)[Ljava/lang/Object;",
                null,
                null);
        code.visitCode();
        code.visitVarInsn(Opcodes.ALOAD, 0);
        code.visitVarInsn(Opcodes.ALOAD, 1);
        code.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandles$Lookup",
                "unreflectGetter",
                "(Ljava/lang/reflect/Field;)Ljava/lang/invoke/MethodHandle;",
                false);
        code.visitVarInsn(Opcodes.ALOAD, 2);
        code.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle",
                "invokeExact",
                "(Ljava/lang/Object;)[Ljava/lang/Object;",
                false);
        code.visitInsn(Opcodes.ARETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void sendsLookupsToWebClasslibAndCallsToBridges() {
        List<String> bridges = new ArrayList<>();
        ClassNode node = new ClassNode();
        new ClassReader(Objects.requireNonNull(MethodHandleCalls.redirect(caller(), bridges))).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode instruction : node.methods.get(0).instructions) {
            if (instruction instanceof MethodInsnNode call) {
                assertThat(call.getOpcode()).isEqualTo(Opcodes.INVOKESTATIC);
                calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        assertThat(calls)
                .containsExactly(
                        MethodHandleCalls.HANDLES + ".unreflectGetter(Ljava/lang/invoke/MethodHandles$Lookup;"
                                + "Ljava/lang/reflect/Field;)Ljava/lang/invoke/MethodHandle;",
                        MethodHandleCalls.NAME + ".invoke(Ljava/lang/invoke/MethodHandle;Ljava/lang/Object;)"
                                + "[Ljava/lang/Object;");
        assertThat(bridges).containsExactly("(Ljava/lang/invoke/MethodHandle;Ljava/lang/Object;)[Ljava/lang/Object;");
    }

    @Test
    void bridgesBoxArgumentsAndUnboxResults() throws Throwable {
        String scaled = "(Ljava/lang/invoke/MethodHandle;IDLjava/lang/String;)J";
        String ignored = "(Ljava/lang/invoke/MethodHandle;J)V";
        byte[] written = MethodHandleCalls.write(List.of(scaled, ignored));
        // On this JVM, the bridges call this test's invoke instead of web-classlib's.
        ClassWriter remapped = new ClassWriter(0);
        new ClassReader(written)
                .accept(
                        new ClassRemapper(
                                remapped,
                                new SimpleRemapper(
                                        Opcodes.ASM9,
                                        MethodHandleCalls.HANDLES,
                                        Handles.class.getName().replace('.', '/'))),
                        0);
        byte[] bytes = remapped.toByteArray();
        Class<?> invokers = new ClassLoader(MethodHandleCallsTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                return name.equals(MethodHandleCalls.NAME.replace('/', '.'))
                        ? defineClass(name, bytes, 0, bytes.length)
                        : super.findClass(name);
            }
        }.loadClass(MethodHandleCalls.NAME.replace('/', '.'));

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle target = lookup.findStatic(
                MethodHandleCallsTest.class,
                "scaled",
                MethodType.methodType(long.class, int.class, double.class, String.class));
        Method bridge = invokers.getMethod("invoke", MethodHandle.class, int.class, double.class, String.class);
        assertThat(bridge.invoke(null, target, 10, 1.5, "ab")).isEqualTo(17L);

        MethodHandle sink = lookup.findStatic(
                MethodHandleCallsTest.class, "ignored", MethodType.methodType(void.class, long.class));
        assertThat(invokers.getMethod("invoke", MethodHandle.class, long.class).invoke(null, sink, 3L))
                .isNull();
    }

    @Test
    void leavesClassesWithoutHandlesAlone() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/Plain", null, "java/lang/Object", null);
        writer.visitEnd();
        assertThat(MethodHandleCalls.redirect(writer.toByteArray(), new ArrayList<>()))
                .isNull();
    }
}
