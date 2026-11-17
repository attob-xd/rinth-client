package rinth.tools.fabricmods;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.ModMetadataImpl;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Builds the Fabric mods in a directory into the client (ADR 0065):
 *
 * <pre>
 * fabricmods --mods &lt;dir&gt; --classpath &lt;file listing the game's class path&gt; --out &lt;dir&gt; --minecraft &lt;version&gt;
 * </pre>
 *
 * <p>Writes {@code classes/} (the changed classes, the mods' own and the generated ones, which go
 * before the game's on the browser build's class path), {@code resources/fabric-mods/} (the mods'
 * assets and their list, for Rinth's pack), {@code src/} (the mod table) and {@code report.txt}.
 * With no mods it writes an empty table and changes nothing.
 */
public final class Main {

    /** What Fabric Loader itself provides, so dependencies on it are met. */
    private static final Set<String> PROVIDED =
            Set.of("minecraft", "java", "fabricloader", "fabric-loader", "mixinextras");

    private Main() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            options.put(args[i], args[i + 1]);
        }
        Path modsDir = Path.of(option(options, "--mods"));
        Path out = Path.of(option(options, "--out"));
        Path classPathList = Path.of(option(options, "--classpath"));
        String minecraft = option(options, "--minecraft");
        // A build of one combination of switched-off mods (ModSwitchPlan), for the main run to merge.
        String offOption = options.get("--off");
        Set<String> off = offOption == null ? Set.of() : Set.of(offOption.split(","));
        boolean variantRun = offOption != null;
        if (variantRun) {
            // Ends with the run that started it, however that ends (a stopped build kills only
            // that one): otherwise it holds its files, and the next run can't clear them.
            ProcessHandle.current().parent().ifPresent(parent -> {
                Thread watch = new Thread(
                        () -> {
                            parent.onExit().join();
                            System.exit(1);
                        },
                        "parent-watch");
                watch.setDaemon(true);
                watch.start();
            });
        }
        for (String part : List.of("classes", "resources", "src", "variants")) {
            deleteTree(out.resolve(part));
        }
        // Always there, so the browser build's class path names a real directory.
        Files.createDirectories(out.resolve("classes"));
        Report report = new Report();
        report.heading("Fabric mods from " + modsDir + " (experimental, ADR 0065)");

        List<ModJar> mods = new ArrayList<>();
        for (ModJar mod : new ModScanner(report).scan(modsDir)) {
            if (!mod.clientSide()) {
                report.note(mod.id() + " is for servers only; skipped");
            } else if (off.contains(mod.id())) {
                report.note(mod.id() + " is off in this combination");
            } else {
                mods.add(mod);
            }
        }
        ModSwitchPlan none = new ModSwitchPlan(List.of(), List.of(Set.of()), Map.of(), Map.of());
        if (mods.isEmpty()) {
            report.note("No client mods: the client is built without any");
            writeTable(out, minecraft, List.of(), List.of(), none, Set.of());
            report.write(out.resolve("report.txt"));
            return;
        }
        checkDependencies(mods, minecraft, report);
        ModSwitchPlan plan = variantRun ? none : ModSwitchPlan.of(mods);
        List<Process> variantRuns = new ArrayList<>();
        for (int i = 1; i < plan.variants().size(); i++) {
            variantRuns.add(startVariant(
                    options,
                    out.resolve("variants").resolve(String.valueOf(i)),
                    plan.variants().get(i)));
        }

        List<Path> classPath = new ArrayList<>();
        for (String line : Files.readAllLines(classPathList, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                classPath.add(Path.of(line.trim()));
            }
        }
        try (ClassIndex index = new ClassIndex(classPath)) {
            for (ModJar mod : mods) {
                index.addModClasses(mod.entries());
            }
            // Mods' mixin plugins ask the loader what's loaded while the mixins are chosen.
            List<ModContainerImpl> containers = new ArrayList<>();
            for (ModJar mod : mods) {
                containers.add(container(mod));
            }
            Path game = out.resolve("game");
            prepareGameDirectory(game);
            FabricLoaderImpl.INSTANCE.setUp(EnvType.CLIENT, game, minecraft, null, containers, List.of());

            report.heading("Access wideners");
            applyAccessWideners(mods, index, report);
            report.heading("Mixins");
            report.note("Mixin plugins see the browser's graphics back end as Vulkan, not OpenGL (" + OPTIONS
                    + "): mods that leave out their OpenGL code there do here, as Iris does its shaders");
            MixinRun mixins = new MixinRun(index, report);
            mixins.run(mods);
            Set<String> merged = mixins.mergedMixins();

            report.heading("Entrypoints");
            List<ModTableWriter.Entry> entrypoints = entrypoints(mods, index, merged, report);
            byte[] minecraftClass = index.bytes(BootHook.MINECRAFT);
            byte[] hooked = minecraftClass == null ? null : BootHook.insert(minecraftClass);
            if (hooked == null) {
                report.problem(
                        "Couldn't find where Minecraft's constructor sets the game directory: the mods won't start");
            } else {
                index.change(BootHook.MINECRAFT, hooked);
            }

            Set<String> output = new TreeSet<>(index.changed().keySet());
            for (ModJar mod : mods) {
                for (String entry : mod.entries().keySet()) {
                    if (entry.endsWith(".class") && !entry.startsWith("META-INF/")) {
                        String name = entry.substring(0, entry.length() - ".class".length());
                        if (!merged.contains(name)) {
                            output.add(name);
                        }
                    }
                }
            }
            output.removeAll(merged);
            StandIns standIns = new StandIns(index);
            for (String name : output) {
                byte[] bytes = index.bytes(name);
                if (bytes != null) {
                    standIns.scan(bytes);
                }
            }
            Map<String, byte[]> stoodIn = standIns.write();
            for (Map.Entry<String, byte[]> standIn : stoodIn.entrySet()) {
                index.change(standIn.getKey(), standIn.getValue());
                output.add(standIn.getKey());
            }
            // Before the reflection scan: the mods find Unsafe's instance by reflection.
            String unsafeNote = redirectUnsafe(output, index);
            String handleNote = redirectHandles(output, index);
            replaceRuntimeClasses(output, index, report);
            List<String> reflectionNotes = new ArrayList<>();
            List<String> reflected = findReflected(output, stoodIn.keySet(), index, reflectionNotes);
            List<String> serviceNotes = new ArrayList<>();
            bindServices(mods, output, stoodIn.keySet(), index, serviceNotes);
            String patternNote = redirectPatterns(output, index);
            Map<String, byte[]> classes = new LinkedHashMap<>();
            for (String name : output) {
                byte[] bytes = index.bytes(name);
                if (bytes != null) {
                    classes.put(name, bytes);
                }
            }
            List<String> switchNotes = new ArrayList<>();
            if (!plan.switchable().isEmpty()) {
                // Each switchable mod's mixin code asks whether the mod is on (ModGuards): this one build
                // runs every combination, however many mods there are.
                Map<String, Integer> modIndex = new HashMap<>();
                Map<String, Integer> mixinMods = new HashMap<>();
                for (int i = 0; i < mods.size(); i++) {
                    ModJar mod = mods.get(i);
                    if (plan.switchable().contains(mod.id())) {
                        modIndex.put(mod.id(), i);
                        for (String entry : mod.entries().keySet()) {
                            if (entry.endsWith(".class") && !entry.startsWith("META-INF/")) {
                                mixinMods.put(entry.substring(0, entry.length() - ".class".length()), i);
                            }
                        }
                    }
                }
                ModGuards.Result guarded = ModGuards.guard(classes, mixinMods, modIndex, new ModGuards.Source() {
                    @Override
                    public byte @Nullable [] bytes(String internalName) {
                        return index.bytes(internalName);
                    }

                    @Override
                    public byte @Nullable [] originalBytes(String internalName) {
                        return index.originalBytes(internalName);
                    }
                });
                classes = guarded.classes();
                switchNotes.addAll(guarded.notes());
            }
            if (!variantRuns.isEmpty()) {
                List<Map<String, byte[]>> variants = new ArrayList<>();
                for (int i = 0; i < variantRuns.size(); i++) {
                    Path variantOut = out.resolve("variants").resolve(String.valueOf(i + 1));
                    int exit = variantRuns.get(i).waitFor();
                    if (exit != 0) {
                        throw new IllegalStateException(
                                "The build with " + plan.variants().get(i + 1) + " off failed (exit " + exit + "): see "
                                        + variantOut.resolve("log.txt"));
                    }
                    variants.add(readClasses(variantOut.resolve("classes")));
                }
                VariantMerge.Result combined = VariantMerge.merge(classes, variants, index::originalBytes);
                classes = combined.classes();
                output.addAll(classes.keySet());
                switchNotes.addAll(combined.notes());
            }
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                Path file = out.resolve("classes").resolve(entry.getKey() + ".class");
                Files.createDirectories(file.getParent());
                Files.write(file, entry.getValue());
            }
            report.heading("Missing classes");
            if (!stoodIn.isEmpty()) {
                List<String> names = stoodIn.keySet().stream()
                        .map(name -> name.replace('/', '.'))
                        .toList();
                report.note(names.size() + " classes the mods use aren't here, so they get stand-ins that fail only "
                        + "if the game reaches them, as on a PC: "
                        + String.join(", ", names.subList(0, Math.min(8, names.size())))
                        + (names.size() > 8 ? ", ..." : ""));
            }
            checkReferences(output, mods, index, report);
            report.heading("Reflection");
            writeReflected(out, reflected, reflectionNotes, report);
            report.note(handleNote);
            report.heading("Services");
            serviceNotes.forEach(report::note);
            report.heading("Regular expressions");
            report.note(patternNote);
            report.heading("Native memory");
            report.note(unsafeNote);
            report.heading("Switches");
            if (plan.switchable().isEmpty()) {
                report.note(
                        variantRun
                                ? "A combination's build, for the main build to merge"
                                : "No mod can be switched in Mod Menu without a rebuild");
            } else {
                report.note("Mod Menu switches these without a rebuild, in any combination, all from this one build: "
                        + String.join(", ", plan.switchable()));
                switchNotes.forEach(report::note);
            }
            writeResources(out, mods, report);
            writeTable(out, minecraft, mods, entrypoints, plan, hidden(modsDir, mods, report));
            report.heading(mods.size() + " mods, " + output.size() + " classes, " + report.problems() + " problems: "
                    + out.resolve("report.txt"));
        }
        report.write(out.resolve("report.txt"));
    }

    private static String option(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Usage: fabricmods --mods <dir> --classpath <file> --out <dir> --minecraft <version> (no " + name
                            + ")");
        }
        return value;
    }

    private static ModContainerImpl container(ModJar mod) {
        return new ModContainerImpl(new ModMetadataImpl(
                mod.id(),
                mod.version(),
                mod.name(),
                mod.description(),
                mod.environment(),
                mod.provides(),
                mod.authors(),
                mod.licenses(),
                mod.icon()));
    }

    private static void checkDependencies(List<ModJar> mods, String minecraft, Report report) {
        Set<String> present = new HashSet<>(PROVIDED);
        for (ModJar mod : mods) {
            present.add(mod.id());
            present.addAll(mod.provides());
        }
        report.heading("Mods");
        for (ModJar mod : mods) {
            report.note(mod.id() + " " + mod.version() + " (" + mod.source() + ")");
            for (Map.Entry<String, List<String>> dependency : mod.depends().entrySet()) {
                String id = dependency.getKey();
                if (id.equals("minecraft")) {
                    if (!matches(minecraft, dependency.getValue())) {
                        report.problem(mod.id() + " is made for Minecraft " + String.join(" or ", dependency.getValue())
                                + ", not " + minecraft + ": it may not work");
                    }
                } else if (!present.contains(id)) {
                    report.problem(mod.id() + " needs " + id + ", which isn't in mods/: it will likely fail");
                }
            }
            for (String unsupported : mod.unsupportedEntrypoints()) {
                report.problem(mod.id() + ": entrypoint " + unsupported
                        + " can't be built in (only plain class names with Java's adapter)");
            }
        }
    }

    private static boolean matches(String version, List<String> requirements) {
        try {
            Version current = Version.parse(version);
            for (String requirement : requirements) {
                if (VersionPredicate.parse(requirement).test(current)) {
                    return true;
                }
            }
            return requirements.isEmpty();
        } catch (VersionParsingException e) {
            return true;
        }
    }

    private static void applyAccessWideners(List<ModJar> mods, ClassIndex index, Report report) {
        AccessWideners wideners = new AccessWideners();
        int count = 0;
        for (ModJar mod : mods) {
            String path = mod.accessWidener();
            if (path == null) {
                continue;
            }
            byte[] file = mod.entries().get(path);
            if (file == null) {
                report.problem(mod.id() + ": access widener " + path + " is listed but missing");
            } else {
                wideners.add(mod.id(), file, report);
                count++;
            }
        }
        // A class's nested classes are listed in it, so their owners are widened too.
        Set<String> owners = new TreeSet<>();
        for (String owner : wideners.owners()) {
            owners.add(owner);
            int nested = owner.indexOf('$');
            if (nested > 0) {
                owners.add(owner.substring(0, nested));
            }
        }
        int changed = 0;
        for (String owner : owners) {
            byte[] bytes = index.bytes(owner);
            if (bytes == null) {
                report.note("access widener names " + owner.replace('/', '.') + ", which isn't in this game");
                continue;
            }
            byte[] widened = wideners.apply(owner, bytes);
            if (widened != null) {
                index.change(owner, widened);
                changed++;
            }
        }
        report.note(count + " access wideners, " + changed + " classes widened");
    }

    /**
     * The entrypoints the table can make: listed classes the build has, whose supertypes all
     * resolve (an entrypoint for a mod that isn't here, like a config screen's, is left out), made
     * public with a public no-argument constructor so the table can call {@code new}.
     */
    private static List<ModTableWriter.Entry> entrypoints(
            List<ModJar> mods, ClassIndex index, Set<String> merged, Report report) {
        List<ModTableWriter.Entry> entries = new ArrayList<>();
        Set<String> opened = new HashSet<>();
        for (ModJar mod : mods) {
            for (Map.Entry<String, List<String>> key : mod.entrypoints().entrySet()) {
                for (String value : key.getValue()) {
                    if (value.contains("::")) {
                        // A static method as the initializer (Fabric API's networking): made into one by a lambda.
                        String owner = value.substring(0, value.indexOf("::")).replace('.', '/');
                        String method = value.substring(value.indexOf("::") + 2);
                        byte[] ownerBytes = index.bytes(owner);
                        byte[] open = ownerBytes == null || merged.contains(owner)
                                ? null
                                : openMethodForTable(owner, ownerBytes, method);
                        if (open == null) {
                            report.problem(mod.id() + ": entrypoint " + value + " (" + key.getKey()
                                    + ") isn't a static method without arguments in the mod; left out");
                            continue;
                        }
                        index.change(owner, open);
                        entries.add(new ModTableWriter.Entry(
                                key.getKey(),
                                mod.id(),
                                value,
                                owner.replace('/', '.').replace('$', '.') + "::" + method));
                        report.note(mod.id() + ": " + key.getKey() + " -> " + value);
                        continue;
                    }
                    String internalName = value.replace('.', '/');
                    byte[] bytes = index.bytes(internalName);
                    if (bytes == null || merged.contains(internalName)) {
                        report.problem(mod.id() + ": entrypoint " + value + " (" + key.getKey() + ") isn't in the mod");
                        continue;
                    }
                    String missing = missingSupertype(internalName, index);
                    if (missing != null) {
                        report.note(mod.id() + ": entrypoint " + value + " (" + key.getKey() + ") left out: it needs "
                                + missing.replace('/', '.') + ", which isn't here");
                        continue;
                    }
                    if (opened.add(internalName)) {
                        byte[] open = openForTable(internalName, bytes);
                        if (open == null) {
                            report.problem(mod.id() + ": entrypoint " + value
                                    + " has no constructor without arguments; left out");
                            opened.remove(internalName);
                            continue;
                        }
                        index.change(internalName, open);
                    }
                    entries.add(new ModTableWriter.Entry(key.getKey(), mod.id(), value, value.replace('$', '.')));
                    report.note(mod.id() + ": " + key.getKey() + " -> " + value);
                }
            }
        }
        return entries;
    }

    private static @Nullable String missingSupertype(String internalName, ClassIndex index) {
        List<String> pending = new ArrayList<>(List.of(internalName));
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            String name = pending.remove(pending.size() - 1);
            if (!seen.add(name)) {
                continue;
            }
            byte[] bytes = index.bytes(name);
            if (bytes == null) {
                return name;
            }
            ClassReader reader = new ClassReader(bytes);
            if (reader.getSuperName() != null) {
                pending.add(reader.getSuperName());
            }
            pending.addAll(List.of(reader.getInterfaces()));
        }
        return null;
    }

    /** The class public, its nested-class entry too, and its no-argument constructor public; null without one. */
    private static byte @Nullable [] openForTable(String internalName, byte[] bytes) {
        boolean[] found = {false};
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public void visit(
                                    int version,
                                    int access,
                                    String name,
                                    @Nullable String signature,
                                    @Nullable String superName,
                                    String @Nullable [] interfaces) {
                                super.visit(version, publicAccess(access), name, signature, superName, interfaces);
                            }

                            @Override
                            public void visitInnerClass(
                                    String name, @Nullable String outerName, @Nullable String innerName, int access) {
                                super.visitInnerClass(
                                        name,
                                        outerName,
                                        innerName,
                                        name.equals(internalName) ? publicAccess(access) : access);
                            }

                            @Override
                            public @Nullable MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    @Nullable String signature,
                                    String @Nullable [] exceptions) {
                                if (name.equals("<init>") && descriptor.equals("()V")) {
                                    found[0] = true;
                                    return super.visitMethod(
                                            publicAccess(access), name, descriptor, signature, exceptions);
                                }
                                return super.visitMethod(access, name, descriptor, signature, exceptions);
                            }
                        },
                        0);
        return found[0] ? writer.toByteArray() : null;
    }

    /** The class public, its nested-class entry too, and its static no-argument {@code method} public; null without one. */
    private static byte @Nullable [] openMethodForTable(String internalName, byte[] bytes, String method) {
        boolean[] found = {false};
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public void visit(
                                    int version,
                                    int access,
                                    String name,
                                    @Nullable String signature,
                                    @Nullable String superName,
                                    String @Nullable [] interfaces) {
                                super.visit(version, publicAccess(access), name, signature, superName, interfaces);
                            }

                            @Override
                            public void visitInnerClass(
                                    String name, @Nullable String outerName, @Nullable String innerName, int access) {
                                super.visitInnerClass(
                                        name,
                                        outerName,
                                        innerName,
                                        name.equals(internalName) ? publicAccess(access) : access);
                            }

                            @Override
                            public @Nullable MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    @Nullable String signature,
                                    String @Nullable [] exceptions) {
                                if (name.equals(method)
                                        && descriptor.equals("()V")
                                        && (access & Opcodes.ACC_STATIC) != 0) {
                                    found[0] = true;
                                    return super.visitMethod(
                                            publicAccess(access), name, descriptor, signature, exceptions);
                                }
                                return super.visitMethod(access, name, descriptor, signature, exceptions);
                            }
                        },
                        0);
        return found[0] ? writer.toByteArray() : null;
    }

    private static int publicAccess(int access) {
        return (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
    }

    /**
     * Classes the built-in classes name that nothing has, once {@link StandIns} stood in for the
     * ones their code uses: what's left is in annotations and generic types, which the browser
     * build does without.
     */
    private static void checkReferences(Set<String> output, List<ModJar> mods, ClassIndex index, Report report) {
        Map<String, String> owners = new HashMap<>();
        for (ModJar mod : mods) {
            for (String entry : mod.entries().keySet()) {
                if (entry.endsWith(".class")) {
                    owners.put(entry.substring(0, entry.length() - ".class".length()), mod.id());
                }
            }
        }
        Map<String, Set<String>> missing = new TreeMap<>();
        for (String name : output) {
            byte[] bytes = index.bytes(name);
            if (bytes == null) {
                continue;
            }
            for (String reference : References.of(bytes)) {
                // fabric/runtime's own classes (the boot hook's FabricBoot) and web-classlib's (the
                // ServiceProviders ModServices binds) join the build after this.
                if (index.bytes(reference) == null
                        && !reference.startsWith("rinth/fabric/")
                        && !reference.startsWith("rinth/classlib/")) {
                    String who = owners.getOrDefault(name, "the game");
                    missing.computeIfAbsent(who, k -> new TreeSet<>()).add(reference.replace('/', '.'));
                }
            }
        }
        if (missing.isEmpty()) {
            report.note("Every class the mods name is here");
        }
        for (Map.Entry<String, Set<String>> entry : missing.entrySet()) {
            List<String> names = new ArrayList<>(entry.getValue());
            report.note(entry.getKey() + " names " + names.size() + " classes that aren't here, only where the "
                    + "browser build doesn't need them (annotations, generic types): "
                    + String.join(", ", names.subList(0, Math.min(8, names.size())))
                    + (names.size() > 8 ? ", ..." : ""));
        }
    }

    /**
     * The classes the mods' code looks at by reflection ({@link ReflectedClasses}), with the mods'
     * {@code Class.forName} calls sent to a lookup that knows their own ({@link ClassesByName},
     * added to {@code output}). Not one that needs a class with
     * a stand-in: that's support for a mod that isn't here (Iris's for Distant Horizons), which
     * keeping would pull into the build for nothing; {@code notes} says which.
     */
    private static List<String> findReflected(
            Set<String> output, Set<String> stoodIn, ClassIndex index, List<String> notes) {
        ReflectedClasses reflected = new ReflectedClasses();
        for (String name : output) {
            byte[] bytes = index.bytes(name);
            if (bytes != null && index.isModClass(name)) {
                reflected.scan(bytes);
            }
        }
        List<String> names = new ArrayList<>();
        Set<String> builtIn = new HashSet<>();
        for (String name : reflected.classes()) {
            byte[] bytes = index.isModClass(name) ? index.bytes(name) : null;
            String missing = bytes == null
                    ? null
                    : References.of(bytes).stream()
                            .filter(stoodIn::contains)
                            .findFirst()
                            .orElse(null);
            if (missing != null) {
                notes.add(name.replace('/', '.') + " isn't kept for reflection: it needs " + missing.replace('/', '.')
                        + ", which isn't here");
                continue;
            }
            names.add(name.replace('/', '.'));
            if (bytes != null) {
                builtIn.add(name);
            }
        }
        // Only the mods' own classes: a name for one that isn't here must still not be found.
        if (!builtIn.isEmpty()) {
            index.change(ClassesByName.NAME, ClassesByName.write(builtIn));
            List<String> callers = new ArrayList<>();
            for (String name : output) {
                byte[] bytes = index.isModClass(name) ? index.bytes(name) : null;
                byte[] redirected = bytes == null ? null : ClassesByName.redirect(bytes);
                if (redirected != null) {
                    index.change(name, redirected);
                    callers.add(name);
                }
            }
            output.add(ClassesByName.NAME);
            notes.add(callers.size() + " classes' Class.forName calls find the mods' " + builtIn.size()
                    + " classes by name through " + ClassesByName.NAME.replace('/', '.'));
        }
        return names;
    }

    /** {@code reflection.txt}: the classes {@link #findReflected} found, one a line, for the browser build. */
    private static void writeReflected(Path out, List<String> names, List<String> notes, Report report)
            throws IOException {
        for (String note : notes) {
            report.note(note);
        }
        Files.writeString(
                out.resolve("reflection.txt"),
                "# Generated by tools/fabricmods: classes the mods look at by reflection.\n"
                        + names.stream().map(name -> name + "\n").collect(Collectors.joining()),
                StandardCharsets.UTF_8);
        report.note(
                names.isEmpty()
                        ? "The mods look at no class by reflection"
                        : names.size() + " classes the mods look at by reflection keep their members for it: "
                                + String.join(", ", names.subList(0, Math.min(8, names.size())))
                                + (names.size() > 8 ? ", ..." : ""));
    }

    /**
     * The providers the mods' {@code META-INF/services} files list, bound for the browser build's
     * {@code ServiceLoader} ({@link ModServices}, added to {@code output}), with the built-in
     * classes' {@code ServiceLoader.load} calls sent to it: mods that find their platform's helpers
     * with {@code ServiceLoader.load(...).findFirst()} need them. Only the mods' own service types,
     * and providers that are built in, in the mods' order; not one that needs a class with a stand-in
     * (as in {@link #findReflected}). {@code notes} says what was bound and left out.
     */
    private static void bindServices(
            List<ModJar> mods, Set<String> output, Set<String> stoodIn, ClassIndex index, List<String> notes) {
        Map<String, Set<String>> services = new TreeMap<>();
        for (ModJar mod : mods) {
            for (Map.Entry<String, byte[]> entry : mod.entries().entrySet()) {
                String path = entry.getKey();
                String service = path.substring(Math.min(path.length(), SERVICES.length()))
                        .replace('.', '/');
                if (!path.startsWith(SERVICES) || path.endsWith("/") || !index.isModClass(service)) {
                    continue;
                }
                new String(entry.getValue(), StandardCharsets.UTF_8)
                        .lines()
                        .map(line -> (line.contains("#") ? line.substring(0, line.indexOf('#')) : line).trim())
                        .filter(provider -> !provider.isEmpty())
                        .map(provider -> provider.replace('.', '/'))
                        .forEach(provider -> services.computeIfAbsent(service, k -> new LinkedHashSet<>())
                                .add(provider));
            }
        }
        Map<String, List<String>> bound = new TreeMap<>();
        for (Map.Entry<String, Set<String>> service : services.entrySet()) {
            for (String provider : service.getValue()) {
                byte[] bytes = output.contains(provider) ? index.bytes(provider) : null;
                String missing = bytes == null
                        ? null
                        : References.of(bytes).stream()
                                .filter(stoodIn::contains)
                                .findFirst()
                                .orElse(null);
                if (bytes == null || !ModServices.instantiable(bytes)) {
                    notes.add(provider.replace('/', '.') + " isn't a provider of "
                            + service.getKey().replace('/', '.')
                            + " here: it isn't built in, or has no constructor without arguments");
                } else if (missing != null) {
                    notes.add(provider.replace('/', '.') + " isn't a provider of "
                            + service.getKey().replace('/', '.') + " here: it needs " + missing.replace('/', '.')
                            + ", which isn't here");
                } else {
                    bound.computeIfAbsent(service.getKey(), k -> new ArrayList<>())
                            .add(provider);
                }
            }
        }
        if (bound.isEmpty()) {
            notes.add("The mods look up no services of their own with ServiceLoader");
            return;
        }
        index.change(ModServices.NAME, ModServices.write(bound));
        int callers = 0;
        for (String name : output) {
            byte[] bytes = index.bytes(name);
            byte[] redirected = bytes == null ? null : ModServices.redirect(bytes);
            if (redirected != null) {
                index.change(name, redirected);
                callers++;
            }
        }
        output.add(ModServices.NAME);
        notes.add(bound.size() + " services for ServiceLoader, bound through " + ModServices.NAME.replace('/', '.')
                + " for " + callers + " classes' lookups: "
                + bound.keySet().stream().map(name -> name.replace('/', '.')).collect(Collectors.joining(", ")));
    }

    private static final String SERVICES = "META-INF/services/";

    /**
     * The built-in classes' {@code Pattern.compile} calls sent to web-classlib's
     * {@code UnicodePatterns}, which takes {@code UNICODE_CHARACTER_CLASS} and the Basic Latin block:
     * TeaVM's regex refuses both, and Sodium uses them (its options search, its chunk builder).
     * Returns the report's note.
     */
    private static String redirectPatterns(Set<String> output, ClassIndex index) {
        int callers = 0;
        for (String name : output) {
            byte[] bytes = index.bytes(name);
            byte[] redirected = bytes == null
                    ? null
                    : StaticCalls.redirect(
                            bytes,
                            "java/util/regex/Pattern",
                            "compile",
                            Set.of(COMPILE, COMPILE_PLAIN),
                            UNICODE_PATTERNS);
            if (redirected != null) {
                index.change(name, redirected);
                callers++;
            }
        }
        return callers == 0
                ? "The mods compile no patterns"
                : callers + " classes' Pattern.compile calls go through " + UNICODE_PATTERNS.replace('/', '.')
                        + ", which takes UNICODE_CHARACTER_CLASS and the Basic Latin block";
    }

    /** web-classlib's, which keeps this name. */
    private static final String UNICODE_PATTERNS = "rinth/classlib/UnicodePatterns";

    /**
     * The mods' own classes' method handles made from reflection ({@link MethodHandleCalls}), with
     * the bridges their calls need added to {@code output}; returns the note.
     */
    private static String redirectHandles(Set<String> output, ClassIndex index) {
        Set<String> bridges = new TreeSet<>();
        int classes = 0;
        for (String name : output) {
            byte[] bytes = index.isModClass(name) ? index.bytes(name) : null;
            byte[] redirected = bytes == null ? null : MethodHandleCalls.redirect(bytes, bridges);
            if (redirected != null) {
                index.change(name, redirected);
                classes++;
            }
        }
        if (!bridges.isEmpty()) {
            index.change(MethodHandleCalls.NAME, MethodHandleCalls.write(bridges));
            output.add(MethodHandleCalls.NAME);
        }
        return classes == 0
                ? "The mods make no method handles from reflection"
                : classes + " classes' method handles are made from reflection by "
                        + MethodHandleCalls.HANDLES.replace('/', '.') + ", called through " + bridges.size()
                        + " bridges in " + MethodHandleCalls.NAME.replace('/', '.');
    }

    /** The classes known mods make as they run, made by built-in ones instead ({@link RuntimeClasses}). */
    private static void replaceRuntimeClasses(Set<String> output, ClassIndex index, Report report) {
        byte[] registry = output.contains(RuntimeClasses.REGISTRY) ? index.bytes(RuntimeClasses.REGISTRY) : null;
        if (registry == null) {
            return;
        }
        byte[] replaced = RuntimeClasses.replaceSerializers(registry);
        if (replaced == null) {
            report.problem("sodium: its vertex serializers aren't the ones this knows, so making one throws "
                    + "(the browser can't load the classes Sodium makes as it runs)");
            return;
        }
        index.change(RuntimeClasses.REGISTRY, replaced);
        index.change(RuntimeClasses.SERIALIZER, RuntimeClasses.serializer());
        output.add(RuntimeClasses.SERIALIZER);
        report.note("sodium: its vertex serializers, classes it makes as it runs, copy by format through "
                + RuntimeClasses.SERIALIZER.replace('/', '.') + " instead");
    }

    /** The mods' own classes' {@code sun.misc.Unsafe} made web-classlib's ({@link ModUnsafe}); returns the note. */
    private static String redirectUnsafe(Set<String> output, ClassIndex index) {
        int classes = 0;
        for (String name : output) {
            byte[] bytes = index.isModClass(name) ? index.bytes(name) : null;
            byte[] redirected = bytes == null ? null : ModUnsafe.redirect(bytes);
            if (redirected != null) {
                index.change(name, redirected);
                classes++;
            }
        }
        return classes == 0
                ? "The mods don't use sun.misc.Unsafe"
                : classes + " classes' sun.misc.Unsafe is " + ModUnsafe.OFF_HEAP.replace('/', '.')
                        + ", which reads and writes the browser's memory by address";
    }

    private static final String COMPILE = "(Ljava/lang/String;I)Ljava/util/regex/Pattern;";

    /** {@code Pattern.compile(regex)}: its Unicode blocks are rewritten too (Sodium's chunk builder). */
    private static final String COMPILE_PLAIN = "(Ljava/lang/String;)Ljava/util/regex/Pattern;";

    /**
     * The mods' assets for Rinth's pack, and their list: data packs aren't loaded (no mods on the
     * server). Their other resources, which they read with {@code Class.getResource} (Sodium's
     * options logo, {@code /config-icon.png}), go where the pack's class path is
     * ({@code resources/}, the pack's {@code classpath/}); not their manifests and mixin configs,
     * which only the build reads.
     */
    private static void writeResources(Path out, List<ModJar> mods, Report report) throws IOException {
        Path root = out.resolve("resources").resolve("fabric-mods");
        Map<String, String> files = new LinkedHashMap<>();
        Set<String> classPath = new HashSet<>();
        for (ModJar mod : mods) {
            int data = 0;
            for (Map.Entry<String, byte[]> entry : mod.entries().entrySet()) {
                String path = entry.getKey();
                if (path.startsWith("data/") && !path.endsWith("/")) {
                    data++;
                }
                // The mod's icon, for Mod Menu, where the loader finds a mod's files: also outside
                // its assets (Sodium's sodium-icon.png).
                if (path.equals(mod.icon()) && !path.startsWith("assets/")) {
                    Path icon = root.resolve(mod.id()).resolve(path);
                    Files.createDirectories(icon.getParent());
                    Files.write(icon, entry.getValue());
                }
                if (isClassPathResource(path)) {
                    if (!classPath.add(path)) {
                        report.note(mod.id() + ": " + path + " is also in an earlier mod; keeping that one");
                        continue;
                    }
                    Path file = out.resolve("resources").resolve(path);
                    Files.createDirectories(file.getParent());
                    Files.write(file, entry.getValue());
                    continue;
                }
                if (!path.startsWith("assets/") || path.endsWith("/")) {
                    continue;
                }
                String served = "/fabric-mods/" + mod.id() + "/" + path;
                if (files.putIfAbsent(path, served) != null) {
                    report.note(mod.id() + ": " + path + " is also in an earlier mod; keeping that one");
                    continue;
                }
                Path file = root.resolve(mod.id()).resolve(path);
                Files.createDirectories(file.getParent());
                Files.write(file, entry.getValue());
            }
            if (data > 0) {
                report.note(mod.id() + ": " + data
                        + " data pack files left out (the singleplayer server runs without mods)");
            }
        }
        StringBuilder list = new StringBuilder("# Generated by tools/fabricmods: the built-in mods' assets.\n");
        for (Map.Entry<String, String> file : files.entrySet()) {
            list.append(file.getKey()).append(" = ").append(file.getValue()).append('\n');
        }
        Files.createDirectories(root);
        Files.writeString(root.resolve("files.txt"), list, StandardCharsets.UTF_8);
        if (!classPath.isEmpty()) {
            report.note(classPath.size() + " of the mods' class-path resources are in the pack, for Class.getResource");
        }
    }

    /**
     * A file a mod may read with {@code Class.getResource}: not a class, not under
     * {@code assets/}, {@code data/} or {@code META-INF/}, and not one only the build reads.
     */
    static boolean isClassPathResource(String path) {
        if (path.endsWith("/")
                || path.endsWith(".class")
                || path.startsWith("assets/")
                || path.startsWith("data/")
                || path.startsWith("META-INF/")) {
            return false;
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        return !name.equals("fabric.mod.json")
                && !name.equals("quilt.mod.json")
                && !name.endsWith(".mixins.json")
                && !(name.startsWith("mixins.") && name.endsWith(".json"))
                && !name.endsWith(".refmap.json")
                && !name.endsWith(".accesswidener")
                && !name.endsWith(".classtweaker")
                && !name.startsWith("LICENSE");
    }

    /**
     * The mods Mod Menu's list (and the launcher's) leave out: those inside another mod's jar (its
     * libraries) and the ids in {@code mods/hidden.txt}, one a line. They still load and run.
     */
    private static Set<String> hidden(Path modsDir, List<ModJar> mods, Report report) throws IOException {
        Set<String> hidden = new TreeSet<>();
        for (ModJar mod : mods) {
            if (mod.source().contains("!")) {
                hidden.add(mod.id());
            }
        }
        Path file = modsDir.resolve("hidden.txt");
        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String id = line.strip();
                if (!id.isEmpty() && !id.startsWith("#")) {
                    hidden.add(id);
                }
            }
        }
        report.note("Hidden from the mod list (still running): " + hidden.size() + " mods");
        return hidden;
    }

    private static void writeTable(
            Path out,
            String minecraft,
            List<ModJar> mods,
            List<ModTableWriter.Entry> entrypoints,
            ModSwitchPlan plan,
            Set<String> hidden)
            throws IOException {
        Path file = out.resolve("src/rinth/fabric/generated/FabricModTable.java");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file, ModTableWriter.write(minecraft, mods, entrypoints, plan, hidden), StandardCharsets.UTF_8);
    }

    /**
     * Starts this tool again, on this JVM, for the build with {@code off}'s mods left out, into
     * {@code variantOut} (its output in {@code log.txt}): run alongside this one, since Mixin's
     * state is global to a JVM.
     */
    private static Process startVariant(Map<String, String> options, Path variantOut, Set<String> off)
            throws IOException {
        Path game = variantOut.resolve("game");
        Files.createDirectories(game);
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx3g");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Main.class.getName());
        for (String name : List.of("--mods", "--classpath", "--minecraft")) {
            command.add(name);
            command.add(option(options, name));
        }
        command.add("--out");
        command.add(variantOut.toAbsolutePath().toString());
        command.add("--off");
        command.add(String.join(",", off));
        // In its own game directory, as this run is in its own (fabric/runtime's fabricMods).
        return new ProcessBuilder(command)
                .directory(game.toFile())
                .redirectErrorStream(true)
                .redirectOutput(variantOut.resolve("log.txt").toFile())
                .start();
    }

    /** The classes a build wrote under {@code root}, by internal name. */
    private static Map<String, byte[]> readClasses(Path root) throws IOException {
        Map<String, byte[]> classes = new TreeMap<>();
        if (!Files.isDirectory(root)) {
            return classes;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = root.relativize(file).toString().replace('\\', '/');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }

    private static final String OPTIONS = "options.txt";

    /**
     * The game directory the mods see while they're built in, and the working directory, as a PC's
     * game directory is (fabric/runtime's fabricMods task): emptied, so what mods wrote last time
     * (Sodium's mixin config) is written afresh, then given the one option mixin plugins read to
     * choose their mixins. The browser draws with WebGL2, never desktop OpenGL, and Vulkan is the
     * only other back end mods know: Iris then applies only the mixins it runs on Vulkan with.
     */
    static void prepareGameDirectory(Path game) throws IOException {
        Files.createDirectories(game);
        try (Stream<Path> children = Files.list(game)) {
            for (Path child : children.toList()) {
                deleteTree(child);
            }
        }
        Files.writeString(game.resolve(OPTIONS), "preferredGraphicsBackend:\"vulkan\"\n", StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.delete(path);
        }
    }
}
