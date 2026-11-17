package rinth.tools.fabricmods;

import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Where the mods start: a call to fabric/runtime's {@code FabricBoot.start(this)} in Minecraft's
 * constructor, right after it sets the game directory, where Fabric Loader's own hook goes. The
 * mods' initializers run there, before the options, key mappings and resources are made. Put in
 * only when there are mods, so a client without them is unchanged.
 */
final class BootHook {

    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String BOOT = "rinth/fabric/FabricBoot";

    private BootHook() {}

    /** Minecraft's class with the call, or null if the constructor no longer sets the field. */
    static byte @Nullable [] insert(byte[] minecraft) {
        ClassNode node = new ClassNode();
        new ClassReader(minecraft).accept(node, 0);
        boolean inserted = false;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.PUTFIELD
                        && field.owner.equals(MINECRAFT)
                        && field.name.equals("gameDirectory")) {
                    InsnList call = new InsnList();
                    call.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BOOT, "start", "(L" + MINECRAFT + ";)V", false));
                    method.instructions.insert(instruction, call);
                    inserted = true;
                    break;
                }
            }
        }
        if (!inserted) {
            return null;
        }
        // Straight-line code where the stack is empty: the frames stand, only the stack depth may grow.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
