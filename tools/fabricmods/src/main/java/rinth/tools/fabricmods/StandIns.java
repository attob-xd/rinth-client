package rinth.tools.fabricmods;

import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Stand-ins for the classes mods use that nothing has. A running JVM looks a class up only when
 * code using it first runs, so a mod's support for something that isn't there (Quilt's loader,
 * another mod's API) costs nothing until reached. The browser build links everything reachable
 * ahead of time instead, and fails on a missing class. So each gets a stand-in with what the mods
 * use of it: its methods and constructors throw {@link NoClassDefFoundError}, as the JVM would at
 * that call, and its fields keep their defaults. Annotations are left out: the build ignores the
 * ones it can't find.
 */
final class StandIns {

    /** What the scanned classes use of one missing class. */
    private static final class Missing {
        boolean isInterface;
        /** Caught or thrown: a stand-in that's a {@link RuntimeException}. */
        boolean isThrowable;
        /** Name and descriptor, and whether static. */
        final Map<String, Boolean> methods = new TreeMap<>();
        /** Name, a space and descriptor, and whether static. */
        final Map<String, Boolean> fields = new TreeMap<>();
    }

    private final ClassIndex index;
    private final Map<String, Missing> missing = new TreeMap<>();

    StandIns(ClassIndex index) {
        this.index = index;
    }

    /** Records what a class uses of the classes nothing has. */
    void scan(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        if (node.superName != null) {
            name(node.superName);
        }
        for (String implemented : node.interfaces) {
            Missing used = name(implemented);
            if (used != null) {
                used.isInterface = true;
            }
        }
        for (FieldNode field : node.fields) {
            type(Type.getType(field.desc));
        }
        for (MethodNode method : node.methods) {
            method(method.desc);
            if (method.exceptions != null) {
                for (String thrown : method.exceptions) {
                    throwable(thrown);
                }
            }
            for (TryCatchBlockNode block : method.tryCatchBlocks) {
                if (block.type != null) {
                    throwable(block.type);
                }
            }
            for (AbstractInsnNode instruction : method.instructions) {
                instruction(instruction);
            }
        }
    }

    private void instruction(AbstractInsnNode instruction) {
        if (instruction instanceof MethodInsnNode call) {
            method(call.desc);
            member(call.owner, call.name, call.desc, call.getOpcode() == Opcodes.INVOKESTATIC, call.itf);
        } else if (instruction instanceof FieldInsnNode field) {
            type(Type.getType(field.desc));
            Missing used = name(field.owner);
            if (used != null) {
                boolean isStatic = field.getOpcode() == Opcodes.GETSTATIC || field.getOpcode() == Opcodes.PUTSTATIC;
                used.fields.put(field.name + " " + field.desc, isStatic);
            }
        } else if (instruction instanceof TypeInsnNode type) {
            type(type.desc.startsWith("[") ? Type.getType(type.desc) : Type.getObjectType(type.desc));
        } else if (instruction instanceof MultiANewArrayInsnNode array) {
            type(Type.getType(array.desc));
        } else if (instruction instanceof LdcInsnNode constant && constant.cst instanceof Type type) {
            type(type);
        } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
            method(dynamic.desc);
            for (Object argument : dynamic.bsmArgs) {
                if (argument instanceof Handle handle) {
                    method(handle.getDesc().startsWith("(") ? handle.getDesc() : "()" + handle.getDesc());
                    member(
                            handle.getOwner(),
                            handle.getName(),
                            handle.getDesc(),
                            handle.getTag() == Opcodes.H_INVOKESTATIC,
                            handle.isInterface());
                } else if (argument instanceof Type type) {
                    type(type);
                }
            }
        }
    }

    private void member(String owner, String name, String descriptor, boolean isStatic, boolean onInterface) {
        Missing used = name(owner);
        if (used == null || !descriptor.startsWith("(")) {
            return;
        }
        used.isInterface |= onInterface;
        used.methods.put(name + descriptor, isStatic);
    }

    private void method(String descriptor) {
        if (!descriptor.startsWith("(")) {
            return;
        }
        Type method = Type.getMethodType(descriptor);
        type(method.getReturnType());
        for (Type argument : method.getArgumentTypes()) {
            type(argument);
        }
    }

    private void throwable(String internalName) {
        Missing used = name(internalName);
        if (used != null) {
            used.isThrowable = true;
        }
    }

    private void type(Type type) {
        Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
        if (element.getSort() == Type.OBJECT) {
            name(element.getInternalName());
        }
    }

    /** The class's record if nothing has it, or null if something does. */
    private @Nullable Missing name(String internalName) {
        // fabric/runtime's own classes (the boot hook's FabricBoot) join the build after this.
        if (internalName.startsWith("[")
                || internalName.startsWith("rinth/fabric/")
                || index.bytes(internalName) != null) {
            return null;
        }
        return missing.computeIfAbsent(internalName, k -> new Missing());
    }

    /** The stand-ins, by internal name. */
    Map<String, byte[]> write() {
        Map<String, byte[]> written = new TreeMap<>();
        for (Map.Entry<String, Missing> entry : missing.entrySet()) {
            written.put(entry.getKey(), write(entry.getKey(), entry.getValue()));
        }
        return written;
    }

    private static byte[] write(String name, Missing used) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        boolean isInterface = used.isInterface && !used.isThrowable;
        String superName = used.isThrowable ? "java/lang/RuntimeException" : "java/lang/Object";
        writer.visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC | (isInterface ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : Opcodes.ACC_SUPER),
                name,
                null,
                superName,
                null);
        for (Map.Entry<String, Boolean> field : used.fields.entrySet()) {
            int space = field.getKey().indexOf(' ');
            boolean isStatic = field.getValue() || isInterface;
            writer.visitField(
                            Opcodes.ACC_PUBLIC
                                    | (isStatic ? Opcodes.ACC_STATIC : 0)
                                    | (isInterface ? Opcodes.ACC_FINAL : 0),
                            field.getKey().substring(0, space),
                            field.getKey().substring(space + 1),
                            null,
                            null)
                    .visitEnd();
        }
        for (Map.Entry<String, Boolean> method : used.methods.entrySet()) {
            int paren = method.getKey().indexOf('(');
            String methodName = method.getKey().substring(0, paren);
            String descriptor = method.getKey().substring(paren);
            boolean isStatic = method.getValue();
            if (isInterface && !isStatic) {
                writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, methodName, descriptor, null, null)
                        .visitEnd();
                continue;
            }
            if (methodName.equals("<clinit>") || (isInterface && methodName.equals("<init>"))) {
                continue;
            }
            MethodVisitor code = writer.visitMethod(
                    Opcodes.ACC_PUBLIC | (isStatic ? Opcodes.ACC_STATIC : 0), methodName, descriptor, null, null);
            code.visitCode();
            if (methodName.equals("<init>")) {
                code.visitVarInsn(Opcodes.ALOAD, 0);
                code.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
            }
            code.visitTypeInsn(Opcodes.NEW, "java/lang/NoClassDefFoundError");
            code.visitInsn(Opcodes.DUP);
            code.visitLdcInsn(
                    name.replace('/', '.') + " isn't in this game: a mod reached code for something not here");
            code.visitMethodInsn(
                    Opcodes.INVOKESPECIAL, "java/lang/NoClassDefFoundError", "<init>", "(Ljava/lang/String;)V", false);
            code.visitInsn(Opcodes.ATHROW);
            code.visitMaxs(0, 0);
            code.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }
}
