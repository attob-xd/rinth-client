package rinth.tools.fabricmods;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class PluginDecisionsTest {

    /**
     * A plugin whose initializer decides {@code usingVulkan} false, as Iris's does without the
     * option, with other simple fields beside it (one a constant), or with no initializer at all.
     */
    private static byte[] plugin(boolean withInitializer) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/Plugin", null, "java/lang/Object", null);
        int open = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        writer.visitField(open, "usingVulkan", "Z", null, null).visitEnd();
        writer.visitField(open, "threads", "I", null, null).visitEnd();
        writer.visitField(open, "scale", "D", null, null).visitEnd();
        writer.visitField(open, "backend", "Ljava/lang/String;", null, null).visitEnd();
        writer.visitField(open | Opcodes.ACC_FINAL, "VERSION", "I", null, 3).visitEnd();
        if (withInitializer) {
            MethodVisitor init = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            init.visitCode();
            init.visitInsn(Opcodes.ICONST_0);
            init.visitFieldInsn(Opcodes.PUTSTATIC, "example/Plugin", "usingVulkan", "Z");
            init.visitLdcInsn("opengl");
            init.visitFieldInsn(Opcodes.PUTSTATIC, "example/Plugin", "backend", "Ljava/lang/String;");
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Class<?> load(byte[] bytes) throws ClassNotFoundException {
        return new ClassLoader(PluginDecisionsTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                return name.equals("example.Plugin")
                        ? defineClass(name, bytes, 0, bytes.length)
                        : super.findClass(name);
            }
        }.loadClass("example.Plugin");
    }

    @Test
    void theInitializerEndsWithTheBuildsValues() throws Exception {
        Map<String, @Nullable Object> values = new HashMap<>();
        values.put("usingVulkan", true);
        values.put("threads", 4);
        values.put("scale", 0.5);
        values.put("backend", null);
        values.put("VERSION", 9);
        for (boolean withInitializer : new boolean[] {true, false}) {
            Class<?> plugin = load(Objects.requireNonNull(PluginDecisions.keep(plugin(withInitializer), values)));
            assertThat(plugin.getField("usingVulkan").getBoolean(null)).isTrue();
            assertThat(plugin.getField("threads").getInt(null)).isEqualTo(4);
            assertThat(plugin.getField("scale").getDouble(null)).isEqualTo(0.5);
            assertThat(plugin.getField("backend").get(null)).isNull();
            assertThat(plugin.getField("VERSION").getInt(null)).isEqualTo(3);
        }
    }

    @Test
    void leavesAPluginWithNothingToKeepAlone() {
        assertThat(PluginDecisions.keep(plugin(true), Map.of())).isNull();
        assertThat(PluginDecisions.keep(plugin(true), Map.of("VERSION", 9))).isNull();
        assertThat(PluginDecisions.keeps(boolean.class)).isTrue();
        assertThat(PluginDecisions.keeps(String.class)).isTrue();
        assertThat(PluginDecisions.keeps(Object.class)).isFalse();
    }
}
