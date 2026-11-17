package rinth.tools.fabricmods;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Mods switched on and off as the game runs, every one built into the same client. The mixins of
 * all mods are applied once; then each method a switchable mod's mixin merged into a class first
 * asks {@code ModSwitchState.on(mod)} and, with the mod off, does what the game did there without
 * it: an {@code @Inject} handler returns, a {@code @Modify...} hands its value back unchanged, a
 * {@code @WrapOperation} or {@code @WrapMethod} runs the operation it wraps, a
 * {@code @WrapWithCondition} lets the call happen, a {@code @Redirect} makes the call (or field
 * access) it replaced, and an {@code @Overwrite} calls a copy of the game's own method. A method
 * that can't be guarded that way stays on whatever the switch says, and the report names it.
 *
 * <p>The mixins chosen are the all-on build's: a mixin plugin's choice that depends on which other
 * mods are present doesn't change with a switch.
 */
final class ModGuards {

    static final String STATE = "rinth/fabric/ModSwitchState";
    static final String STATE_METHOD = "on";

    private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    private static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    /** Mixin's names for merged handlers: {@code <kind>$z<2 letters><3 digits>$<mod>$<name>}. */
    private static final Pattern HANDLER = Pattern.compile("([A-Za-z]+)\\$z[a-z]{2}\\d{3}\\$([^$]+)\\$(.+)");
    /** Prefix of the copies of the game's methods an {@code @Overwrite} replaced. */
    static final String GAME_COPY = "rinth$game$";

    /** Where the classes come from: the build's (mods' and changed game classes) and the game's untouched ones. */
    interface Source {
        byte @Nullable [] bytes(String internalName);

        byte @Nullable [] originalBytes(String internalName);
    }

    record Result(Map<String, byte[]> classes, List<String> notes) {}

    private ModGuards() {}

    /**
     * @param classes the build's output classes, by internal name
     * @param mixinMods each mixin class (internal name) of a switchable mod, to that mod's index in the table
     * @param modIndex each switchable mod's id to its index, for handlers named after a mod
     */
    static Result guard(
            Map<String, byte[]> classes, Map<String, Integer> mixinMods, Map<String, Integer> modIndex, Source source) {
        Map<String, byte[]> out = new LinkedHashMap<>(classes);
        Map<String, int[]> counts = new TreeMap<>();
        List<String> unguarded = new ArrayList<>();
        Map<Integer, String> modIds = new TreeMap<>();
        modIndex.forEach((id, index) -> modIds.put(index, id));
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            String owner = entry.getKey();
            if (!new String(entry.getValue(), StandardCharsets.ISO_8859_1).contains("MixinMerged")) {
                continue;
            }
            ClassNode node = new ClassNode();
            new ClassReader(entry.getValue()).accept(node, ClassReader.EXPAND_FRAMES);
            boolean changed = false;
            ClassNode original = null;
            for (MethodNode method : new ArrayList<>(node.methods)) {
                AnnotationNode merged = annotation(method.visibleAnnotations, MERGED);
                if (merged == null || method.instructions.size() == 0) {
                    continue;
                }
                String mixin = String.valueOf(value(merged, "mixin")).replace('.', '/');
                Matcher handler = HANDLER.matcher(method.name);
                Integer mod = mixinMods.get(mixin);
                if (mod == null && handler.matches()) {
                    mod = modIndex.get(handler.group(2));
                }
                if (mod == null) {
                    continue; // a mod that stays on: Mod Menu, the switches, a library
                }
                String why;
                if (handler.matches()) {
                    why = guardHandler(owner, method, mod, handler.group(1), handler.group(3), mixin, source);
                } else {
                    if (original == null) {
                        byte[] bytes = source.originalBytes(owner);
                        original = new ClassNode();
                        if (bytes != null) {
                            new ClassReader(bytes).accept(original, ClassReader.EXPAND_FRAMES);
                        }
                    }
                    MethodNode game = find(original, method.name, method.desc);
                    if (game == null) {
                        continue; // a method the mixin added: nothing of the game's to fall back on, nor needed
                    }
                    why = guardOverwrite(owner, node, method, game, mod);
                }
                int[] count = counts.computeIfAbsent(modIds.getOrDefault(mod, "?"), id -> new int[2]);
                if (why == null) {
                    count[0]++;
                    changed = true;
                } else {
                    count[1]++;
                    unguarded.add(modIds.getOrDefault(mod, "?") + ": " + owner.replace('/', '.') + "." + method.name
                            + " stays on (" + why + ")");
                }
            }
            if (changed) {
                ClassWriter writer = new ClassWriter(0);
                node.accept(writer);
                out.put(owner, writer.toByteArray());
            }
        }
        List<String> notes = new ArrayList<>();
        counts.forEach((id, count) -> notes.add(id + ": " + count[0] + " mixin methods switch with it"
                + (count[1] == 0 ? "" : ", " + count[1] + " can't")));
        notes.addAll(unguarded);
        return new Result(out, notes);
    }

    /** Guards a merged injector handler; null when done, else why not. */
    private static @Nullable String guardHandler(
            String owner, MethodNode method, int mod, String kind, String name, String mixin, Source source) {
        Type returns = Type.getReturnType(method.desc);
        Type[] arguments = Type.getArgumentTypes(method.desc);
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        InsnList off = new InsnList();
        int stack = 1;
        switch (kind) {
            case "handler", "args" -> {
                if (returns.getSort() != Type.VOID) {
                    return "a " + kind + " handler that returns a value";
                }
                off.add(new InsnNode(Opcodes.RETURN));
            }
            case "modify", "localvar", "constant", "modifyExpressionValue", "modifyReturnValue", "modifyReceiver" -> {
                int at = -1;
                for (int i = 0; i < arguments.length && at < 0; i++) {
                    if (arguments[i].equals(returns)) {
                        at = i;
                    }
                }
                if (at < 0) {
                    return "no argument to hand back";
                }
                off.add(new VarInsnNode(returns.getOpcode(Opcodes.ILOAD), local(isStatic, arguments, at)));
                off.add(new InsnNode(returns.getOpcode(Opcodes.IRETURN)));
                stack = returns.getSize();
            }
            case "wrapWithCondition" -> {
                if (returns.getSort() != Type.BOOLEAN) {
                    return "a condition that isn't a boolean";
                }
                off.add(new InsnNode(Opcodes.ICONST_1));
                off.add(new InsnNode(Opcodes.IRETURN));
            }
            case "wrapOperation", "wrapMethod" -> {
                int operation = -1;
                for (int i = 0; i < arguments.length && operation < 0; i++) {
                    if (arguments[i].getSort() == Type.OBJECT
                            && arguments[i].getInternalName().equals(OPERATION)) {
                        operation = i;
                    }
                }
                if (operation < 0) {
                    return "no Operation argument";
                }
                off.add(new VarInsnNode(Opcodes.ALOAD, local(isStatic, arguments, operation)));
                push(off, operation);
                off.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
                for (int i = 0; i < operation; i++) {
                    off.add(new InsnNode(Opcodes.DUP));
                    push(off, i);
                    off.add(new VarInsnNode(arguments[i].getOpcode(Opcodes.ILOAD), local(isStatic, arguments, i)));
                    box(off, arguments[i]);
                    off.add(new InsnNode(Opcodes.AASTORE));
                }
                off.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE, OPERATION, "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true));
                if (returns.getSort() == Type.VOID) {
                    off.add(new InsnNode(Opcodes.POP));
                    off.add(new InsnNode(Opcodes.RETURN));
                } else {
                    unbox(off, returns);
                    off.add(new InsnNode(returns.getOpcode(Opcodes.IRETURN)));
                }
                stack = 7;
            }
            case "redirect" -> {
                String why = redirect(off, method, name, mixin, isStatic, arguments, returns, source);
                if (why != null) {
                    return why;
                }
                stack = 2;
                for (Type argument : arguments) {
                    stack += argument.getSize();
                }
            }
            default -> {
                return "a " + kind + " injector";
            }
        }
        insertGuard(owner, method, mod, off, stack);
        return null;
    }

    /** The call or field access a {@code @Redirect} replaced, made from its {@code @At}; null when done. */
    private static @Nullable String redirect(
            InsnList off,
            MethodNode method,
            String name,
            String mixin,
            boolean isStatic,
            Type[] arguments,
            Type returns,
            Source source) {
        byte[] mixinBytes = source.bytes(mixin);
        if (mixinBytes == null) {
            return "its mixin class isn't here";
        }
        ClassNode mixinNode = new ClassNode();
        new ClassReader(mixinBytes).accept(mixinNode, ClassReader.SKIP_CODE);
        // Mixin's merged name keeps the handler's own name after the mod's ("sodium$cancel" ends "...$sodium$cancel").
        MethodNode declared = null;
        for (MethodNode candidate : mixinNode.methods) {
            if (candidate.desc.equals(method.desc)
                    && (candidate.name.equals(name) || candidate.name.endsWith("$" + name))
                    && (declared == null || candidate.name.equals(name))) {
                declared = candidate;
            }
        }
        AnnotationNode redirect = declared == null ? null : annotation(declared.visibleAnnotations, REDIRECT);
        if (redirect == null && declared != null) {
            redirect = annotation(declared.invisibleAnnotations, REDIRECT);
        }
        Object at = redirect == null ? null : value(redirect, "at");
        if (!(at instanceof AnnotationNode atNode)) {
            return "its @Redirect isn't readable";
        }
        String kind = String.valueOf(value(atNode, "value"));
        String target = String.valueOf(value(atNode, "target"));
        Matcher parts = Pattern.compile("L?([^;.]+)[;.]([^(:]+)([(:].+)").matcher(target);
        if (!parts.matches()) {
            return "a target it can't read: " + target;
        }
        String targetOwner = parts.group(1);
        String targetName = parts.group(2);
        String targetDesc = parts.group(3).startsWith(":") ? parts.group(3).substring(1) : parts.group(3);
        if (kind.equals("INVOKE")) {
            Type[] targetArguments = Type.getArgumentTypes(targetDesc);
            Boolean targetStatic = isStatic(targetOwner, targetName, targetDesc, source);
            boolean callStatic = targetStatic != null ? targetStatic : arguments.length == targetArguments.length;
            int count = targetArguments.length + (callStatic ? 0 : 1);
            if (arguments.length < count) {
                return "fewer arguments than the call it replaced";
            }
            for (int i = 0; i < count; i++) {
                off.add(new VarInsnNode(arguments[i].getOpcode(Opcodes.ILOAD), local(isStatic, arguments, i)));
            }
            boolean itf = isInterface(targetOwner, source);
            int opcode = callStatic ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
            off.add(new MethodInsnNode(opcode, targetOwner, targetName, targetDesc, itf));
            off.add(new InsnNode(returns.getOpcode(Opcodes.IRETURN)));
            return null;
        }
        if (kind.equals("FIELD")) {
            boolean put = returns.getSort() == Type.VOID;
            boolean fieldStatic = arguments.length == (put ? 1 : 0);
            int count = (put ? 1 : 0) + (fieldStatic ? 0 : 1);
            for (int i = 0; i < count; i++) {
                off.add(new VarInsnNode(arguments[i].getOpcode(Opcodes.ILOAD), local(isStatic, arguments, i)));
            }
            int opcode = put
                    ? (fieldStatic ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD)
                    : (fieldStatic ? Opcodes.GETSTATIC : Opcodes.GETFIELD);
            off.add(new FieldInsnNode(opcode, targetOwner, targetName, targetDesc));
            off.add(new InsnNode(put ? Opcodes.RETURN : returns.getOpcode(Opcodes.IRETURN)));
            return null;
        }
        return "a @Redirect at " + kind;
    }

    /** Guards an {@code @Overwrite}: the game's method, kept as a copy, runs while the mod is off. */
    private static @Nullable String guardOverwrite(
            String owner, ClassNode node, MethodNode method, MethodNode game, int mod) {
        if (method.name.startsWith("<")) {
            return "an overwritten constructor";
        }
        // In an interface the copy is a private interface method, called as one.
        boolean itf = (node.access & Opcodes.ACC_INTERFACE) != 0;
        MethodNode copy = new MethodNode(
                (game.access & Opcodes.ACC_STATIC) | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                GAME_COPY + method.name,
                game.desc,
                game.signature,
                game.exceptions == null ? null : game.exceptions.toArray(String[]::new));
        game.accept(copy);
        copy.name = GAME_COPY + method.name;
        copy.access = (game.access & Opcodes.ACC_STATIC) | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        copy.visibleAnnotations = null;
        copy.invisibleAnnotations = null;
        if (find(node, copy.name, copy.desc) != null) {
            return "copied already";
        }
        node.methods.add(copy);
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        Type[] arguments = Type.getArgumentTypes(method.desc);
        Type returns = Type.getReturnType(method.desc);
        InsnList off = new InsnList();
        int stack = 1;
        if (!isStatic) {
            off.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
        for (int i = 0; i < arguments.length; i++) {
            off.add(new VarInsnNode(arguments[i].getOpcode(Opcodes.ILOAD), local(isStatic, arguments, i)));
            stack += arguments[i].getSize();
        }
        off.add(new MethodInsnNode(
                isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL, owner, copy.name, copy.desc, itf));
        off.add(new InsnNode(returns.getOpcode(Opcodes.IRETURN)));
        insertGuard(owner, method, mod, off, Math.max(stack, returns.getSize()));
        return null;
    }

    /** {@code if (!ModSwitchState.on(mod)) { off }} at the start of {@code method}. */
    private static void insertGuard(String owner, MethodNode method, int mod, InsnList off, int stack) {
        InsnList code = new InsnList();
        LabelNode go = new LabelNode();
        push(code, mod);
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, STATE, STATE_METHOD, "(I)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFNE, go));
        code.add(off);
        code.add(go);
        // A frame is needed where the code goes on, unless the method's own first one is there.
        if (!framedAtStart(method)) {
            code.add(entryFrame(owner, method));
        }
        method.instructions.insert(code);
        method.maxStack = Math.max(method.maxStack, Math.max(stack, 1));
    }

    private static int local(boolean isStatic, Type[] arguments, int index) {
        int slot = isStatic ? 0 : 1;
        for (int i = 0; i < index; i++) {
            slot += arguments[i].getSize();
        }
        return slot;
    }

    private static void push(InsnList code, int value) {
        if (value >= -1 && value <= 5) {
            code.add(new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            code.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            code.add(new IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            code.add(new LdcInsnNode(value));
        }
    }

    private static void box(InsnList code, Type type) {
        String wrapper = wrapper(type);
        if (wrapper != null) {
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    wrapper,
                    "valueOf",
                    "(" + type.getDescriptor() + ")L" + wrapper + ";",
                    false));
        }
    }

    private static void unbox(InsnList code, Type type) {
        String wrapper = wrapper(type);
        if (wrapper == null) {
            code.add(new TypeInsnNode(
                    Opcodes.CHECKCAST, type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName()));
            return;
        }
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, wrapper));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, wrapper, type.getClassName() + "Value", "()" + type.getDescriptor(), false));
    }

    private static @Nullable String wrapper(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            case Type.DOUBLE -> "java/lang/Double";
            default -> null;
        };
    }

    /** Whether the target method is static, from the build's classes or else the JDK's; null if unknown. */
    private static @Nullable Boolean isStatic(String owner, String name, String desc, Source source) {
        byte[] bytes = source.bytes(owner);
        if (bytes != null) {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
            MethodNode method = find(node, name, desc);
            return method == null ? null : (method.access & Opcodes.ACC_STATIC) != 0;
        }
        try {
            for (Method method : Class.forName(owner.replace('/', '.'), false, ModGuards.class.getClassLoader())
                    .getDeclaredMethods()) {
                if (method.getName().equals(name)
                        && Type.getMethodDescriptor(method).equals(desc)) {
                    return Modifier.isStatic(method.getModifiers());
                }
            }
        } catch (ClassNotFoundException | LinkageError e) {
            // not the JDK's either
        }
        return null;
    }

    private static boolean isInterface(String owner, Source source) {
        byte[] bytes = source.bytes(owner);
        if (bytes != null) {
            return (new ClassReader(bytes).getAccess() & Opcodes.ACC_INTERFACE) != 0;
        }
        try {
            return Class.forName(owner.replace('/', '.'), false, ModGuards.class.getClassLoader())
                    .isInterface();
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static @Nullable AnnotationNode annotation(@Nullable List<AnnotationNode> annotations, String desc) {
        if (annotations != null) {
            for (AnnotationNode annotation : annotations) {
                if (annotation.desc.equals(desc)) {
                    return annotation;
                }
            }
        }
        return null;
    }

    private static @Nullable Object value(AnnotationNode annotation, String name) {
        if (annotation.values != null) {
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if (name.equals(annotation.values.get(i))) {
                    return annotation.values.get(i + 1);
                }
            }
        }
        return null;
    }

    private static @Nullable MethodNode find(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        return null;
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

    /** The frame where {@code method} starts: its receiver and arguments, nothing on the stack. */
    private static FrameNode entryFrame(String owner, MethodNode method) {
        List<Object> locals = new ArrayList<>();
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            locals.add(owner);
        }
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            locals.add(
                    switch (argument.getSort()) {
                        case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.INTEGER;
                        case Type.LONG -> Opcodes.LONG;
                        case Type.FLOAT -> Opcodes.FLOAT;
                        case Type.DOUBLE -> Opcodes.DOUBLE;
                        case Type.ARRAY -> argument.getDescriptor();
                        default -> argument.getInternalName();
                    });
        }
        return new FrameNode(Opcodes.F_NEW, locals.size(), locals.toArray(), 0, new Object[0]);
    }
}
