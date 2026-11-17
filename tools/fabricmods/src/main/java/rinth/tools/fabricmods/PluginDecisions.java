package rinth.tools.fabricmods;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * What a mixin plugin decided, carried to the browser. Fabric runs a plugin once per launch, and
 * the mod's own code reads the plugin's static fields later in the same run (Iris's settings page
 * asks whether Iris runs on Vulkan). Here the plugin runs when the client is built, but its class
 * initializes again in the browser, from the browser's files, and could decide otherwise than the
 * mixins built in. So its static fields of simple types are set again, to their values once the
 * mixins were chosen, at the end of its class's initializer.
 */
final class PluginDecisions {

    private static final Set<Class<?>> SIMPLE = Set.of(
            boolean.class,
            byte.class,
            char.class,
            short.class,
            int.class,
            long.class,
            float.class,
            double.class,
            String.class);

    private PluginDecisions() {}

    /** Whether a static field of {@code type} is kept. */
    static boolean keeps(Class<?> type) {
        return SIMPLE.contains(type);
    }

    /**
     * {@code bytes} with each field in {@code values} (by name) set to its value as the class's
     * initializer ends, or null if there's none to set. Constants (fields with a constant value)
     * are left alone: nothing can change them.
     */
    static byte @Nullable [] keep(byte[] bytes, Map<String, @Nullable Object> values) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        InsnList assignments = new InsnList();
        for (FieldNode field : node.fields) {
            if ((field.access & Opcodes.ACC_STATIC) == 0 || field.value != null || !values.containsKey(field.name)) {
                continue;
            }
            Object value = values.get(field.name);
            assignments.add(constant(Type.getType(field.desc), value));
            assignments.add(new FieldInsnNode(Opcodes.PUTSTATIC, node.name, field.name, field.desc));
        }
        if (assignments.size() == 0) {
            return null;
        }
        MethodNode initializer = node.methods.stream()
                .filter(method -> method.name.equals("<clinit>"))
                .findFirst()
                .orElse(null);
        if (initializer == null) {
            initializer = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            initializer.instructions.add(new InsnNode(Opcodes.RETURN));
            node.methods.add(initializer);
        }
        for (AbstractInsnNode instruction : initializer.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                InsnList copy = new InsnList();
                for (AbstractInsnNode assignment : assignments) {
                    copy.add(assignment.clone(Map.of()));
                }
                initializer.instructions.insertBefore(instruction, copy);
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Pushes {@code value} as a value of {@code type}. */
    private static AbstractInsnNode constant(Type type, @Nullable Object value) {
        if (value == null) {
            return new InsnNode(Opcodes.ACONST_NULL); // Only a string can be null.
        }
        return switch (type.getSort()) {
            case Type.BOOLEAN -> new InsnNode((Boolean) value ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            case Type.CHAR -> new LdcInsnNode((int) (Character) value);
            case Type.BYTE, Type.SHORT, Type.INT -> new LdcInsnNode(((Number) value).intValue());
            default -> new LdcInsnNode(value);
        };
    }
}
