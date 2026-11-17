package rinth.tools.fabricmods;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import rinth.classlib.ServiceProviders;

class ModServicesTest {

    private static byte[] type(String name, int access, String... interfaces) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, access, name, null, "java/lang/Object", interfaces);
        if ((access & Opcodes.ACC_INTERFACE) == 0) {
            MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(1, 1);
            init.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A class whose one method returns {@code ServiceLoader.load(Service.class)}. */
    private static byte[] caller() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/Caller", null, "java/lang/Object", null);
        MethodVisitor code = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "find", "()Ljava/util/ServiceLoader;", null, null);
        code.visitCode();
        code.visitLdcInsn(Type.getObjectType("example/Service"));
        code.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/util/ServiceLoader",
                "load",
                "(Ljava/lang/Class;)Ljava/util/ServiceLoader;",
                false);
        code.visitInsn(Opcodes.ARETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @AfterEach
    void unbind() {
        ServiceProviders.bind(service -> List.of());
    }

    @Test
    void bindsTheProvidersAtTheFirstLookup() throws Exception {
        Map<String, byte[]> classes = Map.of(
                ModServices.NAME,
                ModServices.write(Map.of("example/Service", List.of("example/Provider"))),
                "example/Service",
                type("example/Service", Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT),
                "example/Provider",
                type("example/Provider", Opcodes.ACC_PUBLIC, "example/Service"),
                "example/Caller",
                Objects.requireNonNull(ModServices.redirect(caller())));
        ClassLoader loader = new ClassLoader(ModServicesTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> service = loader.loadClass("example.Service");
        assertThat(ServiceProviders.of(service)).isEmpty();

        Method find = loader.loadClass("example.Caller").getMethod("find");
        assertThat(find.invoke(null)).isInstanceOf(ServiceLoader.class);

        assertThat(ServiceProviders.of(service))
                .singleElement()
                .extracting(provider -> provider.getClass().getName())
                .isEqualTo("example.Provider");
        assertThat(ServiceProviders.of(Runnable.class)).isEmpty();
    }

    @Test
    void sendsOnlyServiceLoaderLoadToTheClass() {
        ClassNode node = new ClassNode();
        new ClassReader(Objects.requireNonNull(ModServices.redirect(caller()))).accept(node, 0);
        for (AbstractInsnNode instruction : node.methods.get(0).instructions) {
            if (instruction instanceof MethodInsnNode call) {
                assertThat(call.owner).isEqualTo(ModServices.NAME);
            }
        }
        assertThat(ModServices.redirect(type("example/Plain", Opcodes.ACC_PUBLIC)))
                .isNull();
    }

    @Test
    void makesOnlyConcreteClassesWithAConstructorWithoutArguments() {
        assertThat(ModServices.instantiable(type("example/Provider", Opcodes.ACC_PUBLIC)))
                .isTrue();
        assertThat(ModServices.instantiable(
                        type("example/Service", Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)))
                .isFalse();
    }
}
