package rinth.tools.fabricmods;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** One client from the builds of each combination of switched-off mods, run on a real JVM. */
class VariantMergeTest {

    private static final String GAME = "example/Game";

    /**
     * The game class as a build makes it: {@code value()} returns {@code value}; with the mod on,
     * its constructor calls the mod's handler (counting into {@code hits}) and the mod adds a plain
     * method {@code extra()} returning 7.
     */
    private static byte[] game(int value, boolean modOn) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, GAME, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "hits", "I", null, null)
                .visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "made", "I", null, null)
                .visitEnd();
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        // A final field the constructor sets, differently in each build.
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitLdcInsn(value * 10);
        init.visitFieldInsn(Opcodes.PUTFIELD, GAME, "made", "I");
        if (modOn) {
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GAME, "handler$zza000$mod$hook", "()V", false);
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor valueCode = writer.visitMethod(Opcodes.ACC_PUBLIC, "value", "(I)I", null, null);
        valueCode.visitCode();
        valueCode.visitVarInsn(Opcodes.ILOAD, 1);
        valueCode.visitLdcInsn(value);
        valueCode.visitInsn(Opcodes.IADD);
        valueCode.visitInsn(Opcodes.IRETURN);
        valueCode.visitMaxs(0, 0);
        valueCode.visitEnd();
        if (modOn) {
            MethodVisitor handler =
                    writer.visitMethod(Opcodes.ACC_PRIVATE, "handler$zza000$mod$hook", "()V", null, null);
            handler.visitCode();
            handler.visitFieldInsn(Opcodes.GETSTATIC, GAME, "hits", "I");
            handler.visitInsn(Opcodes.ICONST_1);
            handler.visitInsn(Opcodes.IADD);
            handler.visitFieldInsn(Opcodes.PUTSTATIC, GAME, "hits", "I");
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(0, 0);
            handler.visitEnd();
            MethodVisitor extra = writer.visitMethod(Opcodes.ACC_PUBLIC, "extra", "()I", null, null);
            extra.visitCode();
            extra.visitLdcInsn(7);
            extra.visitInsn(Opcodes.IRETURN);
            extra.visitMaxs(0, 0);
            extra.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A stand-in for fabric/runtime's ModSwitchState: {@code variant()} answers its {@code current}. */
    private static byte[] state() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, VariantMerge.STATE, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "current", "I", null, null)
                .visitEnd();
        MethodVisitor variant = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, VariantMerge.STATE_METHOD, "()I", null, null);
        variant.visitCode();
        variant.visitFieldInsn(Opcodes.GETSTATIC, VariantMerge.STATE, "current", "I");
        variant.visitInsn(Opcodes.IRETURN);
        variant.visitMaxs(0, 0);
        variant.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class Loader extends ClassLoader {
        private final Map<String, byte[]> classes;

        Loader(Map<String, byte[]> classes) {
            super(VariantMergeTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    @Test
    void eachCombinationRunsItsOwnBuildsCode() throws Exception {
        // Combination 0 has the mod on; combination 1 has it off and a different value().
        VariantMerge.Result result = VariantMerge.merge(
                Map.of(GAME, game(1, true)), List.of(Map.of(GAME, game(2, false))), name -> game(0, false));
        Loader loader = new Loader(Map.of(GAME, result.classes().get(GAME), VariantMerge.STATE, state()));
        Class<?> game = loader.loadClass(GAME.replace('/', '.'));
        Field current = loader.loadClass(VariantMerge.STATE.replace('/', '.')).getField("current");
        Field hits = game.getField("hits");
        Method value = game.getMethod("value", int.class);

        current.setInt(null, 0);
        Object withMod = game.getConstructor().newInstance();
        assertThat(value.invoke(withMod, 10)).isEqualTo(11);
        assertThat(hits.getInt(null)).isEqualTo(1);
        assertThat(game.getField("made").getInt(withMod)).isEqualTo(10);

        current.setInt(null, 1);
        Object withoutMod = game.getConstructor().newInstance();
        assertThat(value.invoke(withoutMod, 10)).isEqualTo(12);
        // The constructor runs combination 1's own code after its superclass's: no handler call.
        assertThat(hits.getInt(null)).isEqualTo(1);
        assertThat(game.getField("made").getInt(withoutMod)).isEqualTo(20);
        // A plain method the mod added isn't silenced: it may override the superclass's.
        assertThat(game.getMethod("extra").invoke(withoutMod)).isEqualTo(7);
    }

    @Test
    void aClassNoOtherBuildChangesIsLeftAsIs() {
        byte[] bytes = game(1, true);
        VariantMerge.Result result = VariantMerge.merge(Map.of(GAME, bytes), List.of(Map.of()), name -> bytes);
        assertThat(result.classes().get(GAME)).isSameAs(bytes);
    }

    @Test
    void aClassOnlyAnotherBuildChangesIsMergedFromTheGamesOwn() throws Exception {
        // Not in combination 0's output (unchanged there), changed in combination 1's.
        VariantMerge.Result result =
                VariantMerge.merge(Map.of(), List.of(Map.of(GAME, game(5, false))), name -> game(1, false));
        Loader loader = new Loader(Map.of(GAME, result.classes().get(GAME), VariantMerge.STATE, state()));
        Class<?> game = loader.loadClass(GAME.replace('/', '.'));
        Field current = loader.loadClass(VariantMerge.STATE.replace('/', '.')).getField("current");
        Method value = game.getMethod("value", int.class);
        Object instance = game.getConstructor().newInstance();
        current.setInt(null, 0);
        assertThat(value.invoke(instance, 0)).isEqualTo(1);
        current.setInt(null, 1);
        assertThat(value.invoke(instance, 0)).isEqualTo(5);
    }
}
