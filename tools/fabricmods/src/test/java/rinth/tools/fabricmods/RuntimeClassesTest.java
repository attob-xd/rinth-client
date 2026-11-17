package rinth.tools.fabricmods;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

class RuntimeClassesTest {

    private static final String CREATE_TYPE = "(Lcom/mojang/renderpearl/api/vertex/VertexFormat;"
            + "Lcom/mojang/renderpearl/api/vertex/VertexFormat;)"
            + "Lnet/caffeinemc/mods/sodium/api/vertex/serializer/VertexSerializer;";

    /** A registry like Sodium's: createSerializer defines a class at run time, in a try block. */
    private static byte[] registry(String method) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, RuntimeClasses.REGISTRY, null, "java/lang/Object", null);
        MethodVisitor create =
                writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, method, CREATE_TYPE, null, null);
        create.visitCode();
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        create.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException");
        create.visitLabel(start);
        create.visitInsn(Opcodes.ACONST_NULL);
        create.visitLabel(end);
        create.visitInsn(Opcodes.ARETURN);
        create.visitLabel(handler);
        create.visitInsn(Opcodes.ATHROW);
        create.visitMaxs(0, 0);
        create.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> code(MethodNode method) {
        List<String> code = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) {
                code.add(org.objectweb.asm.util.Printer.OPCODES[instruction.getOpcode()]);
            }
        }
        return code;
    }

    @Test
    void sodiumsSerializersAreTheBuiltInOne() {
        ClassNode node = new ClassNode();
        new ClassReader(Objects.requireNonNull(RuntimeClasses.replaceSerializers(registry("createSerializer"))))
                .accept(node, 0);
        MethodNode create = node.methods.getFirst();
        assertThat(code(create)).containsExactly("NEW", "DUP", "ALOAD", "ALOAD", "INVOKESPECIAL", "ARETURN");
        assertThat(create.tryCatchBlocks).isEmpty();
        assertThat(RuntimeClasses.replaceSerializers(registry("somethingElse"))).isNull();
    }

    @Test
    void theBuiltInSerializerCopiesThroughVertexCopies() {
        ClassNode node = new ClassNode();
        new ClassReader(RuntimeClasses.serializer()).accept(node, 0);
        assertThat(node.interfaces)
                .containsExactly("net/caffeinemc/mods/sodium/api/vertex/serializer/VertexSerializer");
        MethodNode serialize = node.methods.stream()
                .filter(method -> method.name.equals("serialize"))
                .findFirst()
                .orElseThrow();
        assertThat(serialize.desc).isEqualTo("(JJI)V");
        assertThat(code(serialize)).contains("INVOKESTATIC");
    }
}
