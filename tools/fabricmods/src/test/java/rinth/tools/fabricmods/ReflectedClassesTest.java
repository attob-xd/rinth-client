package rinth.tools.fabricmods;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

class ReflectedClassesTest {

    private static final String FIELDS = "()[Ljava/lang/reflect/Field;";

    /** {@code example/Mod}: reflects on a constant, on itself, and on what {@code init} is passed. */
    private static byte[] modClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/Mod", null, "java/lang/Object", null);

        MethodVisitor constant = writer.visitMethod(Opcodes.ACC_STATIC, "constant", "()V", null, null);
        constant.visitCode();
        constant.visitLdcInsn(Type.getObjectType("example/Config"));
        constant.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredFields", FIELDS, false);
        constant.visitInsn(Opcodes.POP);
        constant.visitInsn(Opcodes.RETURN);
        constant.visitMaxs(0, 0);
        constant.visitEnd();

        MethodVisitor self = writer.visitMethod(0, "self", "()V", null, null);
        self.visitCode();
        self.visitVarInsn(Opcodes.ALOAD, 0);
        self.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        self.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getFields", FIELDS, false);
        self.visitInsn(Opcodes.POP);
        self.visitInsn(Opcodes.RETURN);
        self.visitMaxs(0, 0);
        self.visitEnd();

        // init(String id, Class<?> config): reflects on its second parameter.
        MethodVisitor init =
                writer.visitMethod(Opcodes.ACC_STATIC, "init", "(Ljava/lang/String;Ljava/lang/Class;)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredFields", FIELDS, false);
        init.visitInsn(Opcodes.POP);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // load(Class<?> service, String impl): finds its second parameter by name, as Sodium does.
        MethodVisitor load = writer.visitMethod(
                Opcodes.ACC_STATIC, "load", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;", null, null);
        load.visitCode();
        load.visitVarInsn(Opcodes.ALOAD, 1);
        load.visitMethodInsn(
                Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
        load.visitInsn(Opcodes.ARETURN);
        load.visitMaxs(0, 0);
        load.visitEnd();

        MethodVisitor caller = writer.visitMethod(Opcodes.ACC_STATIC, "register", "()V", null, null);
        caller.visitCode();
        caller.visitLdcInsn("example.Direct");
        caller.visitMethodInsn(
                Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
        caller.visitInsn(Opcodes.POP);
        caller.visitLdcInsn(Type.getObjectType("example/Service"));
        caller.visitLdcInsn("example.ServiceImpl");
        caller.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "example/Mod",
                "load",
                "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;",
                false);
        caller.visitInsn(Opcodes.POP);
        caller.visitLdcInsn("mod");
        caller.visitLdcInsn(Type.getObjectType("example/OtherConfig"));
        caller.visitMethodInsn(
                Opcodes.INVOKESTATIC, "example/Mod", "init", "(Ljava/lang/String;Ljava/lang/Class;)V", false);
        // A class constant that's never looked at doesn't count.
        caller.visitLdcInsn(Type.getObjectType("example/Unrelated"));
        caller.visitInsn(Opcodes.POP);
        caller.visitInsn(Opcodes.RETURN);
        caller.visitMaxs(0, 0);
        caller.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void findsTheClassesLookedAt() {
        ReflectedClasses reflected = new ReflectedClasses();
        reflected.scan(modClass());

        assertThat(reflected.classes())
                .containsExactly(
                        "example/Config",
                        "example/Direct",
                        "example/Mod",
                        "example/OtherConfig",
                        "example/ServiceImpl");
    }

    /** A class with a field of {@code type} named {@code name}, as Sodium's options hold their sections. */
    private static byte[] holder(String internalName, String name, String type) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, name, type, null, null)
                .visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void gsonReflectsOnTheClassItReadsIntoAndTheClassesItsFieldsHold() {
        // Options.load(): GSON.fromJson(reader, example/Options.class), as Sodium reads its options.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/Loader", null, "java/lang/Object", null);
        MethodVisitor load = writer.visitMethod(
                Opcodes.ACC_STATIC, "load", "(Lcom/google/gson/Gson;Ljava/io/Reader;)Ljava/lang/Object;", null, null);
        load.visitCode();
        load.visitVarInsn(Opcodes.ALOAD, 0);
        load.visitVarInsn(Opcodes.ALOAD, 1);
        load.visitLdcInsn(Type.getObjectType("example/Options"));
        load.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "com/google/gson/Gson",
                "fromJson",
                "(Ljava/io/Reader;Ljava/lang/Class;)Ljava/lang/Object;",
                false);
        load.visitInsn(Opcodes.ARETURN);
        load.visitMaxs(0, 0);
        load.visitEnd();
        writer.visitEnd();

        ReflectedClasses reflected = new ReflectedClasses();
        reflected.scan(writer.toByteArray());
        reflected.scan(holder("example/Options", "quality", "Lexample/Options$Quality;"));
        reflected.scan(holder("example/Options$Quality", "levels", "[Lexample/Options$Level;"));
        reflected.scan(holder("example/Options$Level", "name", "Ljava/lang/String;"));

        assertThat(reflected.classes())
                .containsExactly("example/Options", "example/Options$Level", "example/Options$Quality");
    }
}
