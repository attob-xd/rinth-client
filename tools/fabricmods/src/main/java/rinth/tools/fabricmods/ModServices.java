package rinth.tools.fabricmods;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

/**
 * {@code ServiceLoader} for the built-in mods' own services. The browser build's
 * {@code ServiceLoader} binds providers instead of reading {@code META-INF/services} (web-classlib's
 * {@code TServiceLoader}), so mods made for several loaders, which find their platform's helpers
 * with {@code ServiceLoader.load(Helpers.class).findFirst()} (Iris, Sodium), found none. Their
 * {@code ServiceLoader.load} calls go to a generated {@code load} instead, whose class binds the
 * providers the mods' service files list (web-classlib's {@code ServiceProviders}) before the first
 * lookup.
 */
final class ModServices {

    static final String NAME = "rinth/fabric/generated/ModServices";

    /** web-classlib's hook, which keeps this name. */
    private static final String PROVIDERS = "rinth/classlib/ServiceProviders";

    private static final String LOADER = "java/util/ServiceLoader";
    private static final String LOAD = "(Ljava/lang/Class;)Ljava/util/ServiceLoader;";
    private static final String LOAD_WITH = "(Ljava/lang/Class;Ljava/lang/ClassLoader;)Ljava/util/ServiceLoader;";

    private ModServices() {}

    /** The class, binding {@code services} (internal names: each service's providers, in order). */
    static byte[] write(Map<String, List<String>> services) {
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
                new String[] {"java/util/function/Function"});

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor bind = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        bind.visitCode();
        bind.visitTypeInsn(Opcodes.NEW, NAME);
        bind.visitInsn(Opcodes.DUP);
        bind.visitMethodInsn(Opcodes.INVOKESPECIAL, NAME, "<init>", "()V", false);
        bind.visitMethodInsn(Opcodes.INVOKESTATIC, PROVIDERS, "bind", "(Ljava/util/function/Function;)V", false);
        bind.visitInsn(Opcodes.RETURN);
        bind.visitMaxs(0, 0);
        bind.visitEnd();

        // apply(service): a new list of the service's providers, or an empty one.
        MethodVisitor apply =
                writer.visitMethod(Opcodes.ACC_PUBLIC, "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        apply.visitCode();
        for (Map.Entry<String, List<String>> service : services.entrySet()) {
            Label next = new Label();
            apply.visitVarInsn(Opcodes.ALOAD, 1);
            apply.visitLdcInsn(Type.getObjectType(service.getKey()));
            apply.visitJumpInsn(Opcodes.IF_ACMPNE, next);
            List<String> providers = service.getValue();
            apply.visitLdcInsn(providers.size());
            apply.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            for (int i = 0; i < providers.size(); i++) {
                apply.visitInsn(Opcodes.DUP);
                apply.visitLdcInsn(i);
                apply.visitTypeInsn(Opcodes.NEW, providers.get(i));
                apply.visitInsn(Opcodes.DUP);
                apply.visitMethodInsn(Opcodes.INVOKESPECIAL, providers.get(i), "<init>", "()V", false);
                apply.visitInsn(Opcodes.AASTORE);
            }
            apply.visitMethodInsn(
                    Opcodes.INVOKESTATIC, "java/util/List", "of", "([Ljava/lang/Object;)Ljava/util/List;", true);
            apply.visitInsn(Opcodes.ARETURN);
            apply.visitLabel(next);
        }
        apply.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/List", "of", "()Ljava/util/List;", true);
        apply.visitInsn(Opcodes.ARETURN);
        apply.visitMaxs(0, 0);
        apply.visitEnd();

        load(writer, LOAD, 1);
        load(writer, LOAD_WITH, 2);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** {@code load(...)}: {@code ServiceLoader.load} with the same arguments, once the class bound its providers. */
    private static void load(ClassWriter writer, String descriptor, int arguments) {
        MethodVisitor code =
                writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "load", descriptor, null, null);
        code.visitCode();
        for (int i = 0; i < arguments; i++) {
            code.visitVarInsn(Opcodes.ALOAD, i);
        }
        code.visitMethodInsn(Opcodes.INVOKESTATIC, LOADER, "load", descriptor, false);
        code.visitInsn(Opcodes.ARETURN);
        code.visitMaxs(0, 0);
        code.visitEnd();
    }

    /** {@code bytes} with its {@code ServiceLoader.load} calls sent to the class, or null if it makes none. */
    static byte @Nullable [] redirect(byte[] bytes) {
        return StaticCalls.redirect(bytes, LOADER, "load", Set.of(LOAD, LOAD_WITH), NAME);
    }

    /** Whether {@code bytes} is a class {@code ServiceLoader} can make: concrete, with a no-argument constructor. */
    static boolean instantiable(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
        return (node.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) == 0
                && node.methods.stream().anyMatch(method -> method.name.equals("<init>") && method.desc.equals("()V"));
    }
}
