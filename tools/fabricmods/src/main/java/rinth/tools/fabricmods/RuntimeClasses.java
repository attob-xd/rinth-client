package rinth.tools.fabricmods;

import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Classes a mod makes as it runs, which the browser can't load: it compiles every class ahead of
 * time (ADR 0065). A known maker gets a stand-in that does the same work with a class built in.
 * Sodium makes a vertex serializer class for each pair of formats it converts between
 * ({@code VertexSerializerRegistryImpl.createSerializer}, which Sodium's text and models reach);
 * here that method returns one built-in serializer instead, which copies by format through
 * fabric/runtime's {@code VertexCopies}, element by element as Sodium's generated classes do.
 */
final class RuntimeClasses {

    /** The serializer this adds. */
    static final String SERIALIZER = "rinth/fabric/generated/CopyingVertexSerializer";

    static final String REGISTRY =
            "net/caffeinemc/mods/sodium/client/render/vertex/serializers/VertexSerializerRegistryImpl";

    private static final String FORMAT = "com/mojang/renderpearl/api/vertex/VertexFormat";
    private static final String SERIALIZER_TYPE = "net/caffeinemc/mods/sodium/api/vertex/serializer/VertexSerializer";
    private static final String CREATE = "createSerializer";
    private static final String CREATE_TYPE = "(L" + FORMAT + ";L" + FORMAT + ";)L" + SERIALIZER_TYPE + ";";
    private static final String CONSTRUCTOR_TYPE = "(L" + FORMAT + ";L" + FORMAT + ";)V";

    /** fabric/runtime's, which keeps this name and its members. */
    private static final String COPIES = "rinth/fabric/VertexCopies";

    private RuntimeClasses() {}

    /**
     * Sodium's registry with its static {@code createSerializer(source, target)} returning the
     * built-in serializer, or null if the class has no such method (another Sodium than this knows).
     */
    static byte @Nullable [] replaceSerializers(byte[] registry) {
        ClassNode node = new ClassNode();
        new ClassReader(registry).accept(node, 0);
        MethodNode create = node.methods.stream()
                .filter(method -> method.name.equals(CREATE)
                        && method.desc.equals(CREATE_TYPE)
                        && (method.access & Opcodes.ACC_STATIC) != 0)
                .findFirst()
                .orElse(null);
        if (create == null) {
            return null;
        }
        InsnList body = new InsnList();
        body.add(new TypeInsnNode(Opcodes.NEW, SERIALIZER));
        body.add(new InsnNode(Opcodes.DUP));
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, SERIALIZER, "<init>", CONSTRUCTOR_TYPE, false));
        body.add(new InsnNode(Opcodes.ARETURN));
        create.instructions = body;
        create.tryCatchBlocks.clear();
        create.localVariables = null;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** The serializer: the copies VertexCopies plans for its two formats, made with each vertex. */
    static byte[] serializer() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                SERIALIZER,
                null,
                "java/lang/Object",
                new String[] {SERIALIZER_TYPE});
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "plan", "[I", null, null)
                .visitEnd();

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", CONSTRUCTOR_TYPE, null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitVarInsn(Opcodes.ALOAD, 2);
        init.visitMethodInsn(Opcodes.INVOKESTATIC, COPIES, "plan", "(L" + FORMAT + ";L" + FORMAT + ";)[I", false);
        init.visitFieldInsn(Opcodes.PUTFIELD, SERIALIZER, "plan", "[I");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor serialize = writer.visitMethod(Opcodes.ACC_PUBLIC, "serialize", "(JJI)V", null, null);
        serialize.visitCode();
        serialize.visitVarInsn(Opcodes.ALOAD, 0);
        serialize.visitFieldInsn(Opcodes.GETFIELD, SERIALIZER, "plan", "[I");
        serialize.visitVarInsn(Opcodes.LLOAD, 1);
        serialize.visitVarInsn(Opcodes.LLOAD, 3);
        serialize.visitVarInsn(Opcodes.ILOAD, 5);
        serialize.visitMethodInsn(Opcodes.INVOKESTATIC, COPIES, "copy", "([IJJI)V", false);
        serialize.visitInsn(Opcodes.RETURN);
        serialize.visitMaxs(0, 0);
        serialize.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
