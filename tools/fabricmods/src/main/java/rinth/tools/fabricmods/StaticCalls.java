package rinth.tools.fabricmods;

import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Calls to a static method sent to another class's method of the same name and type. */
final class StaticCalls {

    private StaticCalls() {}

    /**
     * {@code bytes} with its calls to {@code owner.name} (with one of {@code descriptors}) sent to
     * {@code to}, or null if it makes none.
     */
    static byte @Nullable [] redirect(byte[] bytes, String owner, String name, Set<String> descriptors, String to) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals(owner)
                        && call.name.equals(name)
                        && descriptors.contains(call.desc)) {
                    call.owner = to;
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
}
