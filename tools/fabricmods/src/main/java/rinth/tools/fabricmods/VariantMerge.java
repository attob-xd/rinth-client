package rinth.tools.fabricmods;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

/**
 * Merges the client built with each combination of switched-off mods ({@link ModSwitchPlan}) into
 * one, so Mod Menu's switches need no rebuild. The game starts with the combination the player
 * chose ({@code rinth.fabric.ModSwitchState}, which says its index) and runs that build's code:
 *
 * <ul>
 *   <li>A method whose code differs between the builds keeps each version as a private copy
 *       ({@code name$rinth$0}, ...) and becomes a switch on the combination that calls the right one.
 *   <li>A method only the mods add (a mixin's handler) returns at once, if it returns nothing, in
 *       the combinations whose builds don't have it: that is what turns off a mod's injections into
 *       constructors, which can't be switched as above (their own code must run first).
 *   <li>Members and interfaces only another combination's build has are added: its code calls them.
 * </ul>
 *
 * <p>What it can't switch it leaves as all mods on and says so: constructors and static
 * initializers that differ in more than calls to handlers, and handlers that return a value.
 */
final class VariantMerge {

    /** The runtime's answer: the index of the combination the game started with. */
    static final String STATE = "rinth/fabric/ModSwitchState";

    static final String STATE_METHOD = "variant";

    private static final String COPY = "$rinth$";

    /** The merged classes, and what it couldn't switch. */
    record Result(Map<String, byte[]> classes, List<String> notes) {}

    private VariantMerge() {}

    /**
     * Merges the builds: {@code base} is the classes the build with every mod on writes (its
     * combination is 0), {@code variants} the same for combinations 1 and on, and {@code original}
     * gives a game class as it is without mods (null for a class that isn't the game's). Returns
     * every class to write: {@code base}'s, merged, and those only other builds changed.
     */
    static Result merge(
            Map<String, byte[]> base,
            List<Map<String, byte[]>> variants,
            Function<String, byte @Nullable []> original) {
        Map<String, byte[]> out = new LinkedHashMap<>(base);
        List<String> notes = new ArrayList<>();
        Set<String> names = new TreeSet<>(base.keySet());
        for (Map<String, byte[]> variant : variants) {
            names.addAll(variant.keySet());
        }
        int switched = 0;
        int[] unguarded = {0};
        for (String name : names) {
            byte[] baseBytes = base.get(name);
            if (baseBytes == null) {
                baseBytes = original.apply(name);
            }
            if (baseBytes == null) {
                // Only another build has it (a stand-in for a class a mod that's off lacks): kept as is.
                for (Map<String, byte[]> variant : variants) {
                    byte[] bytes = variant.get(name);
                    if (bytes != null) {
                        out.put(name, bytes);
                        break;
                    }
                }
                continue;
            }
            byte[] @Nullable [] builds = new byte[variants.size() + 1][];
            builds[0] = baseBytes;
            boolean differs = false;
            for (int i = 0; i < variants.size(); i++) {
                byte[] bytes = variants.get(i).get(name);
                if (bytes == null) {
                    // Not changed in that build: the game's own class, or, for a mod's class that
                    // build doesn't have, nothing (it's never reached there).
                    bytes = original.apply(name);
                }
                builds[i + 1] = bytes;
                differs |= bytes != null && !Arrays.equals(bytes, baseBytes);
            }
            if (!differs) {
                out.put(name, baseBytes);
                continue;
            }
            out.put(name, mergeClass(name, baseBytes, builds, notes, unguarded));
            switched++;
        }
        notes.add(
                0,
                switched + " classes differ between the " + (variants.size() + 1)
                        + " combinations: their methods switch on the one the game starts with");
        if (unguarded[0] > 0) {
            notes.add(unguarded[0] + " of the mods' handlers return a value, so they don't return at once where"
                    + " their mod is off: only what can't be switched (listed here) still calls them");
        }
        return new Result(out, notes);
    }

    private static byte[] mergeClass(
            String name, byte[] baseBytes, byte[] @Nullable [] builds, List<String> notes, int[] unguarded) {
        ClassNode merged = read(baseBytes);
        boolean isInterface = (merged.access & Opcodes.ACC_INTERFACE) != 0;
        List<@Nullable ClassNode> others = new ArrayList<>();
        for (int i = 1; i < builds.length; i++) {
            others.add(builds[i] == null ? null : read(builds[i]));
        }
        // What only other builds have, so their code finds it.
        Set<String> members = new HashSet<>();
        for (MethodNode method : merged.methods) {
            members.add(method.name + method.desc);
        }
        Set<String> fields = new HashSet<>();
        for (FieldNode field : merged.fields) {
            fields.add(field.name);
        }
        Map<String, MethodNode> addedMethods = new LinkedHashMap<>();
        for (ClassNode other : others) {
            if (other == null) {
                continue;
            }
            for (String itf : other.interfaces) {
                if (!merged.interfaces.contains(itf)) {
                    merged.interfaces.add(itf);
                }
            }
            for (FieldNode field : other.fields) {
                if (fields.add(field.name)) {
                    merged.fields.add(field);
                }
            }
            for (MethodNode method : other.methods) {
                String key = method.name + method.desc;
                if (!members.contains(key) && !addedMethods.containsKey(key)) {
                    addedMethods.put(key, method);
                }
            }
        }
        List<MethodNode> copies = new ArrayList<>();
        for (MethodNode method : new ArrayList<>(merged.methods)) {
            String key = method.name + method.desc;
            MethodNode[] bodies = new MethodNode[builds.length];
            bodies[0] = method;
            boolean[] lacks = new boolean[builds.length];
            for (int i = 0; i < others.size(); i++) {
                ClassNode other = others.get(i);
                MethodNode match = other == null ? null : find(other, key);
                if (match == null) {
                    lacks[i + 1] = other != null;
                    match = method;
                }
                bodies[i + 1] = match;
            }
            boolean hasCode = (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
            if (!hasCode) {
                continue;
            }
            List<String> texts = new ArrayList<>();
            int[] version = new int[builds.length];
            List<MethodNode> distinct = new ArrayList<>();
            for (int i = 0; i < bodies.length; i++) {
                String text = text(bodies[i]);
                int index = texts.indexOf(text);
                if (index < 0) {
                    index = texts.size();
                    texts.add(text);
                    distinct.add(bodies[i]);
                }
                version[i] = index;
            }
            boolean special = method.name.equals("<init>") || method.name.equals("<clinit>");
            if (distinct.size() > 1 && !method.name.equals("<init>")) {
                // A static initializer moves whole, as any method: it has no superclass call.
                for (int j = 0; j < distinct.size(); j++) {
                    copies.add(copy(distinct.get(j), method, j));
                }
                dispatch(merged.name, method, version, isInterface);
                continue;
            }
            if (distinct.size() > 1 && splitConstructor(merged, method, distinct, version, copies)) {
                continue;
            }
            if (distinct.size() > 1) {
                notes.add(name.replace('/', '.') + "." + method.name
                        + " differs between the combinations and can't be switched: it runs as with every mod on");
            }
            // Only Mixin's own names (handler$..., lambda$...): a plain one may override the
            // superclass's method, which the builds without it run instead.
            if (anyLacks(lacks) && !special && method.name.contains("$")) {
                if (Type.getReturnType(method.desc).getSort() == Type.VOID) {
                    guard(merged.name, method, lacks);
                } else {
                    unguarded[0]++;
                }
            }
        }
        merged.methods.addAll(copies);
        merged.methods.addAll(addedMethods.values());
        // Fields a constructor or static initializer set are now set by its copies, which aren't
        // initializers: the JVM allows that only for fields that aren't final.
        Set<String> moved = new HashSet<>();
        for (MethodNode copy : copies) {
            for (AbstractInsnNode node = copy.instructions.getFirst(); node != null; node = node.getNext()) {
                if (node instanceof FieldInsnNode put
                        && (put.getOpcode() == Opcodes.PUTFIELD || put.getOpcode() == Opcodes.PUTSTATIC)
                        && put.owner.equals(merged.name)) {
                    moved.add(put.name);
                }
            }
        }
        for (FieldNode field : merged.fields) {
            if (moved.contains(field.name)) {
                field.access &= ~Opcodes.ACC_FINAL;
            }
        }
        ClassWriter writer = new ClassWriter(0);
        merged.accept(writer);
        return writer.toByteArray();
    }

    private static boolean anyLacks(boolean[] lacks) {
        for (boolean lack : lacks) {
            if (lack) {
                return true;
            }
        }
        return false;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        // Full frames, so code keeps its frames wherever it moves (a constructor's tail into a copy).
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    /**
     * Switches a constructor whose code differs between the builds: the part up to its superclass's
     * constructor (which must come first, and is the same in every build here) stays, and the rest
     * of each version moves to a private method ({@code rinth$init$0}, ...) it then calls by the
     * combination. False, changing nothing, where it can't be split so.
     */
    private static boolean splitConstructor(
            ClassNode owner, MethodNode method, List<MethodNode> distinct, int[] version, List<MethodNode> copies) {
        int[] splits = new int[distinct.size()];
        String prefix = null;
        for (int j = 0; j < distinct.size(); j++) {
            MethodNode body = distinct.get(j);
            int split = afterSuperCall(body, owner.name, owner.superName);
            if (split < 0 || !splittable(body, split)) {
                return false;
            }
            String text = text(body, split);
            if (prefix != null && !prefix.equals(text)) {
                return false;
            }
            prefix = text;
            splits[j] = split;
        }
        List<MethodNode> tails = new ArrayList<>();
        for (int j = 0; j < distinct.size(); j++) {
            MethodNode body = distinct.get(j);
            MethodNode tail = new MethodNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                    "rinth$init$" + j,
                    method.desc,
                    null,
                    method.exceptions.toArray(String[]::new));
            AbstractInsnNode node = body.instructions.get(splits[j]);
            while (node != null) {
                AbstractInsnNode next = node.getNext();
                body.instructions.remove(node);
                tail.instructions.add(node);
                node = next;
            }
            tail.tryCatchBlocks = body.tryCatchBlocks;
            body.tryCatchBlocks = new ArrayList<>();
            tail.maxStack = body.maxStack;
            tail.maxLocals = body.maxLocals;
            tails.add(tail);
        }
        // The constructor: its own first part (left in the method's node), then the switch.
        Type[] arguments = Type.getArgumentTypes(method.desc);
        List<Object> locals = new ArrayList<>();
        locals.add(owner.name);
        for (Type argument : arguments) {
            locals.add(frameType(argument));
        }
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, STATE, STATE_METHOD, "()I", false));
        LabelNode[] targets = new LabelNode[tails.size()];
        for (int j = 0; j < targets.length; j++) {
            targets[j] = new LabelNode();
        }
        LabelNode[] cases = new LabelNode[version.length];
        for (int i = 0; i < version.length; i++) {
            cases[i] = targets[version[i]];
        }
        code.add(new TableSwitchInsnNode(0, version.length - 1, targets[version[0]], cases));
        int size = 1;
        for (Type argument : arguments) {
            size += argument.getSize();
        }
        for (int j = 0; j < targets.length; j++) {
            code.add(targets[j]);
            code.add(new FrameNode(Opcodes.F_NEW, locals.size(), locals.toArray(), 0, new Object[0]));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            int slot = 1;
            for (Type argument : arguments) {
                code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
                slot += argument.getSize();
            }
            code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner.name, "rinth$init$" + j, method.desc, false));
            code.add(new InsnNode(Opcodes.RETURN));
        }
        MethodNode first = distinct.get(0);
        method.instructions = first.instructions;
        method.instructions.add(code);
        method.tryCatchBlocks = first.tryCatchBlocks;
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxStack = Math.max(first.maxStack, size + 1);
        method.maxLocals = Math.max(first.maxLocals, size);
        copies.addAll(tails);
        return true;
    }

    /**
     * The index of the instruction after a constructor's call to its superclass's constructor (or
     * another of its own), or -1: the first {@code invokespecial <init>} not for an object the
     * constructor made with {@code new} first.
     */
    private static int afterSuperCall(MethodNode init, String owner, @Nullable String superName) {
        int pending = 0;
        int index = 0;
        for (AbstractInsnNode node = init.instructions.getFirst(); node != null; node = node.getNext(), index++) {
            if (node.getOpcode() == Opcodes.NEW) {
                pending++;
            } else if (node instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.name.equals("<init>")) {
                if (pending > 0) {
                    pending--;
                } else {
                    return call.owner.equals(superName) || call.owner.equals(owner) ? index + 1 : -1;
                }
            }
        }
        return -1;
    }

    /** Whether nothing after {@code split} jumps back before it, and no try block spans it. */
    private static boolean splittable(MethodNode body, int split) {
        Set<LabelNode> before = new HashSet<>();
        int index = 0;
        for (AbstractInsnNode node = body.instructions.getFirst(); node != null; node = node.getNext(), index++) {
            if (index < split) {
                if (node instanceof LabelNode label) {
                    before.add(label);
                }
                continue;
            }
            if (node instanceof JumpInsnNode jump && before.contains(jump.label)) {
                return false;
            }
            if (node instanceof TableSwitchInsnNode table
                    && (before.contains(table.dflt) || table.labels.stream().anyMatch(before::contains))) {
                return false;
            }
            if (node instanceof LookupSwitchInsnNode lookup
                    && (before.contains(lookup.dflt) || lookup.labels.stream().anyMatch(before::contains))) {
                return false;
            }
        }
        for (TryCatchBlockNode block : body.tryCatchBlocks) {
            if (before.contains(block.start) || before.contains(block.end) || before.contains(block.handler)) {
                return false;
            }
        }
        return true;
    }

    /** A value of {@code type} as a full frame lists it. */
    private static Object frameType(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.INTEGER;
            case Type.FLOAT -> Opcodes.FLOAT;
            case Type.LONG -> Opcodes.LONG;
            case Type.DOUBLE -> Opcodes.DOUBLE;
            default -> type.getInternalName();
        };
    }

    private static @Nullable MethodNode find(ClassNode node, String key) {
        for (MethodNode method : node.methods) {
            if ((method.name + method.desc).equals(key)) {
                return method;
            }
        }
        return null;
    }

    /** A method's code as text: what it does, whatever constant pool each build wrote it with. */
    static String text(MethodNode method) {
        Textifier textifier = new Textifier();
        TraceMethodVisitor visitor = new TraceMethodVisitor(textifier);
        method.instructions.accept(visitor);
        for (var block : method.tryCatchBlocks) {
            block.accept(visitor);
        }
        return print(textifier);
    }

    /** The text of a method's first {@code count} instructions. */
    private static String text(MethodNode method, int count) {
        Textifier textifier = new Textifier();
        TraceMethodVisitor visitor = new TraceMethodVisitor(textifier);
        int index = 0;
        for (AbstractInsnNode node = method.instructions.getFirst();
                node != null && index < count;
                node = node.getNext()) {
            node.accept(visitor);
            index++;
        }
        return print(textifier);
    }

    private static String print(Textifier textifier) {
        StringWriter out = new StringWriter();
        try (PrintWriter writer = new PrintWriter(out)) {
            textifier.print(writer);
        }
        return out.toString();
    }

    /** {@code body} as the private copy {@code j} of {@code method}. */
    private static MethodNode copy(MethodNode body, MethodNode method, int j) {
        int access = (method.access & Opcodes.ACC_STATIC) | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        MethodNode copy = new MethodNode(
                access, copyName(method.name, j), method.desc, null, method.exceptions.toArray(String[]::new));
        // Another build's node, read for this merge alone: its code moves into the copy.
        copy.instructions = body.instructions;
        copy.tryCatchBlocks = body.tryCatchBlocks;
        copy.maxStack = body.maxStack;
        copy.maxLocals = body.maxLocals;
        body.instructions = new InsnList();
        body.tryCatchBlocks = new ArrayList<>();
        return copy;
    }

    static String copyName(String name, int j) {
        // A static initializer's copies can't be named <...>: rinth$clinit$0, ...
        if (name.startsWith("<")) {
            return "rinth$" + name.substring(1, name.length() - 1) + "$" + j;
        }
        return name + COPY + j;
    }

    /** The frame where {@code method} starts: its receiver and arguments, nothing on the stack. */
    private static FrameNode entryFrame(String owner, MethodNode method) {
        List<Object> locals = new ArrayList<>();
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            locals.add(owner);
        }
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            locals.add(frameType(argument));
        }
        return new FrameNode(Opcodes.F_NEW, locals.size(), locals.toArray(), 0, new Object[0]);
    }

    /** Replaces {@code method}'s code with a switch on the combination calling its copies. */
    private static void dispatch(String owner, MethodNode method, int[] version, boolean isInterface) {
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        Type[] arguments = Type.getArgumentTypes(method.desc);
        Type returns = Type.getReturnType(method.desc);
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, STATE, STATE_METHOD, "()I", false));
        int copies = 0;
        for (int v : version) {
            copies = Math.max(copies, v + 1);
        }
        LabelNode[] targets = new LabelNode[copies];
        for (int j = 0; j < copies; j++) {
            targets[j] = new LabelNode();
        }
        LabelNode[] cases = new LabelNode[version.length];
        for (int i = 0; i < version.length; i++) {
            cases[i] = targets[version[i]];
        }
        code.add(new TableSwitchInsnNode(0, version.length - 1, targets[version[0]], cases));
        int size = isStatic ? 0 : 1;
        for (Type argument : arguments) {
            size += argument.getSize();
        }
        for (int j = 0; j < copies; j++) {
            code.add(targets[j]);
            code.add(entryFrame(owner, method));
            int slot = 0;
            if (!isStatic) {
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                slot = 1;
            }
            for (Type argument : arguments) {
                code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
                slot += argument.getSize();
            }
            code.add(new MethodInsnNode(
                    isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL,
                    owner,
                    copyName(method.name, j),
                    method.desc,
                    isInterface));
            code.add(new InsnNode(returns.getOpcode(Opcodes.IRETURN)));
        }
        method.instructions = code;
        method.tryCatchBlocks = new ArrayList<>();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxLocals = size;
        method.maxStack = Math.max(1, size);
    }

    /** Makes {@code method} (returning nothing) return at once in the combinations {@code lacks} marks. */
    private static void guard(String owner, MethodNode method, boolean[] lacks) {
        int mask = 0;
        for (int i = 0; i < lacks.length && i < 31; i++) {
            if (lacks[i]) {
                mask |= 1 << i;
            }
        }
        LabelNode go = new LabelNode();
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(mask));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, STATE, STATE_METHOD, "()I", false));
        code.add(new InsnNode(Opcodes.ISHL));
        code.add(new InsnNode(Opcodes.IAND));
        code.add(new JumpInsnNode(Opcodes.IFEQ, go));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(go);
        // A frame is needed where the code goes on, unless the method's own first one is there.
        if (!framedAtStart(method)) {
            code.add(entryFrame(owner, method));
        }
        method.instructions.insert(code);
        method.maxStack = Math.max(method.maxStack, 3);
    }

    private static boolean framedAtStart(MethodNode method) {
        for (AbstractInsnNode node = method.instructions.getFirst(); node != null; node = node.getNext()) {
            if (node instanceof FrameNode) {
                return true;
            }
            if (node.getOpcode() >= 0) {
                return false;
            }
        }
        return false;
    }

    /** For tests: how often each copy name is used in {@code bytes}. */
    static Map<String, Integer> copies(byte[] bytes) {
        Map<String, Integer> found = new HashMap<>();
        for (MethodNode method : read(bytes).methods) {
            int at = method.name.indexOf(COPY);
            if (at > 0) {
                found.merge(method.name.substring(0, at), 1, Integer::sum);
            }
        }
        return found;
    }
}
