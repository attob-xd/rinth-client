package rinth.tools.fabricmods;

import java.util.Collection;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The mods' method handles, made from reflection. The browser build compiles no method handles:
 * its lookups find nothing, and an {@code invoke} or {@code invokeExact} call, whose type is the
 * call site's own, can't be compiled. So the mods' {@code Lookup.unreflect...} calls go to
 * web-classlib's {@code ReflectiveHandles}, which makes handles over the reflected member, and each
 * {@code invoke} call goes to a generated bridge of the call site's type, which boxes the arguments
 * for {@code ReflectiveHandles.invoke} and unboxes what it returns. Sodium reads JOML's frustum
 * planes so.
 */
final class MethodHandleCalls {

    static final String NAME = "rinth/fabric/generated/Invokers";

    /** web-classlib's, which keeps this name and its members. */
    static final String HANDLES = "rinth/classlib/reflect/ReflectiveHandles";

    private static final String HANDLE = "java/lang/invoke/MethodHandle";
    private static final String LOOKUP = "java/lang/invoke/MethodHandles$Lookup";
    private static final Set<String> UNREFLECT =
            Set.of("unreflectGetter", "unreflectSetter", "unreflect", "unreflectConstructor");

    private MethodHandleCalls() {}

    /**
     * {@code bytes} with its handle lookups and calls redirected, or null if it makes none;
     * {@code bridges} gets the type of each call it makes ({@code (Lowner;...)R}, the handle first).
     */
    static byte @Nullable [] redirect(byte[] bytes, Collection<String> bridges) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL) {
                    continue;
                }
                if (call.owner.equals(LOOKUP) && UNREFLECT.contains(call.name)) {
                    call.setOpcode(Opcodes.INVOKESTATIC);
                    call.owner = HANDLES;
                    call.desc = "(L" + LOOKUP + ";" + call.desc.substring(1);
                    changed = true;
                } else if (call.owner.equals(HANDLE)
                        && (call.name.equals("invokeExact") || call.name.equals("invoke"))) {
                    String bridge = "(L" + HANDLE + ";" + call.desc.substring(1);
                    call.setOpcode(Opcodes.INVOKESTATIC);
                    call.owner = NAME;
                    call.name = "invoke";
                    call.desc = bridge;
                    bridges.add(bridge);
                    changed = true;
                }
            }
        }
        if (!changed) {
            return null;
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** The bridges' class: one static {@code invoke} of each type in {@code bridges}. */
    static byte[] write(Collection<String> bridges) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                NAME,
                null,
                "java/lang/Object",
                null);
        for (String bridge : bridges) {
            MethodVisitor code =
                    writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "invoke", bridge, null, null);
            code.visitCode();
            Type[] parameters = Type.getArgumentTypes(bridge);
            code.visitVarInsn(Opcodes.ALOAD, 0);
            // The arguments after the handle, boxed into an array.
            code.visitLdcInsn(parameters.length - 1);
            code.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            int slot = 1;
            for (int i = 1; i < parameters.length; i++) {
                code.visitInsn(Opcodes.DUP);
                code.visitLdcInsn(i - 1);
                code.visitVarInsn(parameters[i].getOpcode(Opcodes.ILOAD), slot);
                box(code, parameters[i]);
                code.visitInsn(Opcodes.AASTORE);
                slot += parameters[i].getSize();
            }
            code.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    HANDLES,
                    "invoke",
                    "(L" + HANDLE + ";[Ljava/lang/Object;)Ljava/lang/Object;",
                    false);
            Type result = Type.getReturnType(bridge);
            if (result.getSort() == Type.VOID) {
                code.visitInsn(Opcodes.POP);
                code.visitInsn(Opcodes.RETURN);
            } else {
                unbox(code, result);
                code.visitInsn(result.getOpcode(Opcodes.IRETURN));
            }
            code.visitMaxs(0, 0);
            code.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void box(MethodVisitor code, Type type) {
        Type boxed = boxed(type);
        if (boxed != null) {
            code.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    boxed.getInternalName(),
                    "valueOf",
                    "(" + type.getDescriptor() + ")" + boxed.getDescriptor(),
                    false);
        }
    }

    private static void unbox(MethodVisitor code, Type type) {
        Type boxed = boxed(type);
        if (boxed == null) {
            code.visitTypeInsn(
                    Opcodes.CHECKCAST, type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName());
        } else {
            code.visitTypeInsn(Opcodes.CHECKCAST, boxed.getInternalName());
            code.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    boxed.getInternalName(),
                    type.getClassName() + "Value",
                    "()" + type.getDescriptor(),
                    false);
        }
    }

    /** The wrapper class of a primitive type, or null for a reference type. */
    private static @Nullable Type boxed(Type type) {
        String wrapper =
                switch (type.getSort()) {
                    case Type.BOOLEAN -> "java/lang/Boolean";
                    case Type.CHAR -> "java/lang/Character";
                    case Type.BYTE -> "java/lang/Byte";
                    case Type.SHORT -> "java/lang/Short";
                    case Type.INT -> "java/lang/Integer";
                    case Type.LONG -> "java/lang/Long";
                    case Type.FLOAT -> "java/lang/Float";
                    case Type.DOUBLE -> "java/lang/Double";
                    default -> null;
                };
        return wrapper == null ? null : Type.getObjectType(wrapper);
    }
}
