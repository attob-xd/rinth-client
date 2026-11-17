package rinth.tools.fabricmods;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Fabric's access wideners: a mod's list of the game's classes, methods and fields to make public
 * ({@code accessible}), subclassable or overridable ({@code extendable}) or writable ({@code
 * mutable}), and in the newer class tweaker format ({@code classTweaker v1}) interfaces to add to
 * a class ({@code inject-interface}). Applied to the class bytes before the mixins, as Fabric
 * Loader does when it loads a class. Names are the game's own (26.x isn't obfuscated), whatever
 * namespace the header names.
 */
final class AccessWideners {

    private static final Pattern LINES = Pattern.compile("\\R");
    private static final Pattern WORDS = Pattern.compile("\\s+");

    private final Map<String, Set<String>> classes = new HashMap<>();
    private final Map<String, Map<String, Set<String>>> members = new HashMap<>();
    /** Interfaces a class tweaker adds to a class, each as written: a name, generics allowed. */
    private final Map<String, Set<String>> interfaces = new HashMap<>();

    /** Adds a mod's access widener; problems go to {@code report} under {@code modId}. */
    void add(String modId, byte[] file, Report report) {
        List<String> lines =
                LINES.splitAsStream(new String(file, StandardCharsets.UTF_8)).toList();
        boolean header = false;
        for (String raw : lines) {
            String line = raw.contains("#") ? raw.substring(0, raw.indexOf('#')) : raw;
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            List<String> parts = WORDS.splitAsStream(line).toList();
            if (!header) {
                header = true;
                if (!parts.get(0).equals("accessWidener") && !parts.get(0).equals("classTweaker")) {
                    report.problem(modId + ": its access widener has no header; skipped");
                    return;
                }
                continue;
            }
            String first = parts.get(0);
            String access = first.startsWith("transitive-") ? first.substring("transitive-".length()) : first;
            if (parts.size() == 3 && access.equals("inject-interface")) {
                interfaces
                        .computeIfAbsent(parts.get(1), k -> new LinkedHashSet<>())
                        .add(parts.get(2));
            } else if (parts.size() == 3 && parts.get(1).equals("class")) {
                classes.computeIfAbsent(parts.get(2), k -> new HashSet<>()).add(access);
            } else if (parts.size() == 5
                    && (parts.get(1).equals("method") || parts.get(1).equals("field"))) {
                members.computeIfAbsent(parts.get(2), k -> new HashMap<>())
                        .computeIfAbsent(parts.get(1) + " " + parts.get(3) + parts.get(4), k -> new HashSet<>())
                        .add(access);
            } else {
                report.problem(modId + ": access widener line not understood: " + line);
            }
        }
    }

    /** The classes the wideners change. */
    Set<String> owners() {
        Set<String> owners = new HashSet<>(classes.keySet());
        owners.addAll(members.keySet());
        owners.addAll(interfaces.keySet());
        return owners;
    }

    /** {@code bytes} with the wideners applied (a class's nested classes' entries too), or null if none apply. */
    byte @Nullable [] apply(String owner, byte[] bytes) {
        Set<String> classAccess = classes.getOrDefault(owner, Set.of());
        Map<String, Set<String>> memberAccess = members.getOrDefault(owner, Map.of());
        Set<String> added = interfaces.getOrDefault(owner, Set.of());
        if (classAccess.isEmpty() && memberAccess.isEmpty() && added.isEmpty() && !hasNestedRules(owner, bytes)) {
            return null;
        }
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public void visit(
                            int version,
                            int access,
                            String name,
                            @Nullable String signature,
                            @Nullable String superName,
                            String @Nullable [] interfaces) {
                        Set<String> names = new LinkedHashSet<>(interfaces == null ? List.of() : List.of(interfaces));
                        String generic = signature;
                        for (String injected : added) {
                            int bracket = injected.indexOf('<');
                            if (names.add(bracket < 0 ? injected : injected.substring(0, bracket)) && generic != null) {
                                generic += "L" + injected + ";";
                            }
                        }
                        super.visit(
                                version,
                                widenClass(access, classAccess),
                                name,
                                generic,
                                superName,
                                names.toArray(String[]::new));
                    }

                    @Override
                    public void visitInnerClass(
                            String name, @Nullable String outerName, @Nullable String innerName, int access) {
                        super.visitInnerClass(
                                name, outerName, innerName, widenClass(access, classes.getOrDefault(name, Set.of())));
                    }

                    @Override
                    public @Nullable FieldVisitor visitField(
                            int access,
                            String name,
                            String descriptor,
                            @Nullable String signature,
                            @Nullable Object value) {
                        Set<String> rules = memberAccess.getOrDefault("field " + name + descriptor, Set.of());
                        int widened = access;
                        if (rules.contains("accessible")) {
                            widened = makePublic(widened);
                        }
                        if (rules.contains("mutable")) {
                            widened &= ~Opcodes.ACC_FINAL;
                        }
                        return super.visitField(widened, name, descriptor, signature, value);
                    }

                    @Override
                    public @Nullable MethodVisitor visitMethod(
                            int access,
                            String name,
                            String descriptor,
                            @Nullable String signature,
                            String @Nullable [] exceptions) {
                        Set<String> rules = memberAccess.getOrDefault("method " + name + descriptor, Set.of());
                        int widened = access;
                        if (rules.contains("accessible")) {
                            // A private method made public stays final, so calls to it within the
                            // class (invokespecial) still mean that method, as with Fabric's.
                            boolean wasPrivate = (access & Opcodes.ACC_PRIVATE) != 0;
                            widened = makePublic(widened);
                            if (wasPrivate && !name.equals("<init>")) {
                                widened |= Opcodes.ACC_FINAL;
                            }
                        }
                        if (rules.contains("extendable")) {
                            if ((widened & Opcodes.ACC_PUBLIC) == 0) {
                                widened = (widened & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PROTECTED;
                            }
                            widened &= ~Opcodes.ACC_FINAL;
                        }
                        return super.visitMethod(widened, name, descriptor, signature, exceptions);
                    }
                },
                0);
        return writer.toByteArray();
    }

    private boolean hasNestedRules(String owner, byte[] bytes) {
        boolean[] found = {false};
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitInnerClass(
                                    String name, @Nullable String outerName, @Nullable String innerName, int access) {
                                if (!name.equals(owner) && classes.containsKey(name)) {
                                    found[0] = true;
                                }
                            }
                        },
                        ClassReader.SKIP_CODE);
        return found[0];
    }

    private static int widenClass(int access, Set<String> rules) {
        int widened = access;
        if (rules.contains("accessible") || rules.contains("extendable")) {
            widened = makePublic(widened);
        }
        if (rules.contains("extendable")) {
            widened &= ~Opcodes.ACC_FINAL;
        }
        return widened;
    }

    private static int makePublic(int access) {
        return (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
    }
}
