package rinth.tools.fabricmods;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * The classes mods look at by reflection ({@code getDeclaredFields()} and the like), for the
 * browser build to keep their members' metadata: TeaVM keeps none unless told, so a config
 * screen built from a class's fields comes up empty. A class counts when its {@code Class} is
 * a constant ({@code MyConfig.class.getDeclaredFields()}), {@code this.getClass()}, or a method's
 * parameter that a call passes a constant to ({@code Config.init(MyConfig.class)}, as config
 * libraries do); and so does a class found by name ({@code Class.forName("a.b.Impl")}, directly
 * or through such a parameter, as Sodium finds its implementations). Gson's {@code fromJson(json,
 * MyConfig.class)} reflects too, on the class and on the classes its fields hold (Sodium's options
 * and their sections), so those count with it.
 */
final class ReflectedClasses {

    /** {@code java.lang.Class}'s methods that list or find members. */
    private static final Set<String> LOOKUPS = Set.of(
            "getDeclaredFields",
            "getFields",
            "getDeclaredField",
            "getField",
            "getDeclaredMethods",
            "getMethods",
            "getDeclaredMethod",
            "getMethod",
            "getDeclaredConstructors",
            "getConstructors",
            "getDeclaredConstructor",
            "getConstructor",
            "getRecordComponents");

    /** A method that looks at its {@code Class} parameter: {@code owner.name desc}, and which one. */
    private record ReflectingParameter(String owner, String name, String descriptor, int parameter) {}

    private final Set<String> classes = new TreeSet<>();
    private final List<ReflectingParameter> parameters = new ArrayList<>();
    private final List<ClassNode> scanned = new ArrayList<>();
    /** The classes Gson reads into, whose fields' classes it reflects on as well. */
    private final Set<String> gsonRoots = new TreeSet<>();

    private boolean resolved;

    /** Looks at a mod class's code. */
    void scan(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
        scanned.add(node);
        for (MethodNode method : node.methods) {
            if (!calls(method, ReflectedClasses::isLookup)) {
                continue;
            }
            Frame<SourceValue>[] frames = frames(node, method);
            AbstractInsnNode[] instructions = method.instructions.toArray();
            for (int i = 0; i < instructions.length; i++) {
                if (instructions[i] instanceof MethodInsnNode call && isLookup(call) && frames[i] != null) {
                    // forName's class is its first argument (a name), Gson's its last; the others'
                    // is their receiver.
                    int arguments = Type.getArgumentCount(call.desc);
                    int looked = isGson(call)
                            ? frames[i].getStackSize() - 1
                            : frames[i].getStackSize() - arguments - (isForName(call) ? 0 : 1);
                    Set<String> had = new TreeSet<>(classes);
                    lookedAt(node, method, frames, frames[i].getStack(looked));
                    if (isGson(call)) {
                        for (String found : classes) {
                            if (!had.contains(found)) {
                                gsonRoots.add(found);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * What a lookup looked at: a class or class name constant, {@code getClass()}, or one of the
     * method's parameters.
     */
    private void lookedAt(ClassNode node, MethodNode method, Frame<SourceValue>[] frames, SourceValue value) {
        for (AbstractInsnNode source : value.insns) {
            if (source instanceof LdcInsnNode constant && found(constant)) {
                continue;
            }
            if (source instanceof MethodInsnNode call
                    && call.name.equals("getClass")
                    && call.desc.equals("()Ljava/lang/Class;")) {
                classes.add(node.name);
            } else if (source instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
                Frame<SourceValue> before = frames[method.instructions.indexOf(load)];
                int parameter = parameter(method, load.var);
                if (before != null
                        && parameter >= 0
                        && before.getLocal(load.var).insns.isEmpty()) {
                    parameters.add(new ReflectingParameter(node.name, method.name, method.desc, parameter));
                }
            }
        }
    }

    /**
     * The classes found, once every class is scanned: with the constants passed to the methods
     * that look at their parameter.
     */
    Set<String> classes() {
        if (resolved) {
            return classes;
        }
        if (parameters.isEmpty()) {
            resolved = true;
            addGsonFields();
            return classes;
        }
        resolved = true;
        for (ClassNode node : scanned) {
            for (MethodNode method : node.methods) {
                if (!calls(method, call -> reflecting(call) != null)) {
                    continue;
                }
                Frame<SourceValue>[] frames = frames(node, method);
                AbstractInsnNode[] instructions = method.instructions.toArray();
                for (int i = 0; i < instructions.length; i++) {
                    if (!(instructions[i] instanceof MethodInsnNode call) || frames[i] == null) {
                        continue;
                    }
                    ReflectingParameter target = reflecting(call);
                    if (target == null) {
                        continue;
                    }
                    int arguments = Type.getArgumentCount(call.desc);
                    SourceValue argument =
                            frames[i].getStack(frames[i].getStackSize() - arguments + target.parameter());
                    for (AbstractInsnNode source : argument.insns) {
                        if (source instanceof LdcInsnNode constant) {
                            found(constant);
                        }
                    }
                }
            }
        }
        addGsonFields();
        return classes;
    }

    /** The mods' classes that the fields of the classes Gson reads into hold, and theirs in turn. */
    private void addGsonFields() {
        Map<String, ClassNode> byName = new HashMap<>();
        for (ClassNode node : scanned) {
            byName.put(node.name, node);
        }
        List<String> next = new ArrayList<>(gsonRoots);
        while (!next.isEmpty()) {
            ClassNode node = byName.get(next.remove(next.size() - 1));
            if (node == null) {
                continue;
            }
            for (FieldNode field : node.fields) {
                Type type = Type.getType(field.desc);
                while (type.getSort() == Type.ARRAY) {
                    type = type.getElementType();
                }
                if (type.getSort() == Type.OBJECT
                        && byName.containsKey(type.getInternalName())
                        && classes.add(type.getInternalName())) {
                    next.add(type.getInternalName());
                }
            }
        }
    }

    /** Records the class a constant names, if it names one. */
    private boolean found(LdcInsnNode constant) {
        String internalName = constant(constant);
        if (internalName == null) {
            return false;
        }
        classes.add(internalName);
        return true;
    }

    private @Nullable ReflectingParameter reflecting(MethodInsnNode call) {
        for (ReflectingParameter parameter : parameters) {
            if (parameter.owner().equals(call.owner)
                    && parameter.name().equals(call.name)
                    && parameter.descriptor().equals(call.desc)) {
                return parameter;
            }
        }
        return null;
    }

    private static boolean isLookup(MethodInsnNode call) {
        return (call.owner.equals("java/lang/Class") && (LOOKUPS.contains(call.name) || isForName(call)))
                || isGson(call);
    }

    /** Gson reading JSON into a class it's given: {@code fromJson(json, MyConfig.class)}. */
    private static boolean isGson(MethodInsnNode call) {
        return call.owner.equals("com/google/gson/Gson")
                && call.name.equals("fromJson")
                && call.desc.contains(";Ljava/lang/Class;)");
    }

    private static boolean isForName(MethodInsnNode call) {
        return call.owner.equals("java/lang/Class")
                && call.name.equals("forName")
                && call.desc.startsWith("(Ljava/lang/String;");
    }

    /** The internal name a constant names: a class constant, or a class's name as a string. */
    private static @Nullable String constant(LdcInsnNode constant) {
        if (constant.cst instanceof Type type && type.getSort() == Type.OBJECT) {
            return type.getInternalName();
        }
        if (constant.cst instanceof String name && CLASS_NAME.matcher(name).matches()) {
            return name.replace('.', '/');
        }
        return null;
    }

    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+");

    private static boolean calls(MethodNode method, Predicate<MethodInsnNode> which) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && which.test(call)) {
                return true;
            }
        }
        return false;
    }

    /** Which parameter (from 0, not counting {@code this}) local {@code slot} holds, or -1. */
    private static int parameter(MethodNode method, int slot) {
        int next = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        Type[] arguments = Type.getArgumentTypes(method.desc);
        for (int i = 0; i < arguments.length; i++) {
            if (next == slot) {
                return i;
            }
            next += arguments[i].getSize();
        }
        return -1;
    }

    @SuppressWarnings("unchecked") // ASM's analyzer makes the array.
    private static Frame<SourceValue>[] frames(ClassNode node, MethodNode method) {
        try {
            return new Analyzer<>(new SourceInterpreter()).analyze(node.name, method);
        } catch (AnalyzerException e) {
            return (Frame<SourceValue>[]) new Frame<?>[method.instructions.size()];
        }
    }
}
