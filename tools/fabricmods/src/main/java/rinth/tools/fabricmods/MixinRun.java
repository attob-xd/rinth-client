package rinth.tools.fabricmods;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import com.llamalad7.mixinextras.utils.ClassGenUtils;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/**
 * Applies the mods' mixins to the classes they target, once, as Fabric Loader would as each class
 * loaded: Fabric's Mixin and MixinExtras over the {@link ClassIndex}, through
 * {@link OfflineMixinService}. Every config is made not required, so a mixin that doesn't fit is
 * skipped with a warning instead of failing its class, and every injector optional, so one that
 * finds no place loses only itself (the report names it); a class whose mixins still fail keeps
 * its own code, and the report says which mods' mixins it lost. Then the classes Mixin and MixinExtras
 * generate (for {@code @ModifyArgs}, mixins' inner classes, {@code LocalRef}s) are added: a running
 * game would make them as they were first loaded.
 */
final class MixinRun {

    private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";
    /** Mixin's own injectors; MixinExtras's are everything in its {@code injector} package. */
    private static final Set<String> INJECTORS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;");
    /** A handler Mixin merged into its target: {@code handler$zzb000$modid$method}. */
    private static final Pattern HANDLER = Pattern.compile("[A-Za-z]+\\$[a-z0-9]+\\$([^$]+)\\$(.+)");

    private final ClassIndex index;
    private final Report report;
    /** Every mixin class by internal name, and whether it's an interface (accessors, invokers). */
    private final Map<String, Boolean> mixins = new LinkedHashMap<>();
    /** Each target class, and the mods mixing into it. */
    private final Map<String, Set<String>> targets = new TreeMap<>();
    /** The injectors their mods required, as {@code modid$method}, before all were made optional. */
    private final Set<String> requiredHandlers = new HashSet<>();
    /** Each mixin config's plugin class, for those that name one. */
    private final Map<String, String> plugins = new HashMap<>();

    MixinRun(ClassIndex index, Report report) {
        this.index = index;
        this.report = report;
    }

    /**
     * The mixin classes merged into their targets, never built in themselves. Interfaces stay: an
     * accessor's interface is what the mod's code casts the target to.
     */
    Set<String> mergedMixins() {
        Set<String> merged = new HashSet<>();
        for (Map.Entry<String, Boolean> mixin : mixins.entrySet()) {
            if (!mixin.getValue()) {
                merged.add(mixin.getKey());
            }
        }
        return merged;
    }

    void run(List<ModJar> mods) throws ReflectiveOperationException {
        Map<String, byte[]> resources = new HashMap<>();
        for (ModJar mod : mods) {
            for (Map.Entry<String, byte[]> entry : mod.entries().entrySet()) {
                if (!entry.getKey().endsWith(".class")) {
                    resources.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }
        Map<String, String> configMods = new LinkedHashMap<>();
        for (ModJar mod : mods) {
            for (String config : mod.mixinConfigs()) {
                if (readConfig(mod, config, resources)) {
                    configMods.put(config, mod.id());
                }
            }
        }
        if (configMods.isEmpty()) {
            report.note("No mixins to apply");
            return;
        }

        IndexClassLoader loader = new IndexClassLoader(index, mods, MixinRun.class.getClassLoader());
        OfflineMixinService.index = index;
        OfflineMixinService.resources = resources;
        OfflineMixinService.loader = loader;
        // Plugins, and what they call, look things up through the thread's loader (ServiceLoader).
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            checkPlugins(configMods, loader);
            if (configMods.isEmpty()) {
                report.note("No mixins to apply");
                return;
            }
            apply(configMods);
            keepPluginDecisions(configMods, loader);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** The configs' plugins' decisions, kept for the browser ({@link PluginDecisions}). */
    private void keepPluginDecisions(Map<String, String> configMods, ClassLoader loader) {
        Set<String> classes = new TreeSet<>();
        for (String config : configMods.keySet()) {
            String plugin = plugins.get(config);
            if (plugin != null) {
                classes.add(plugin);
            }
        }
        List<String> kept = new ArrayList<>();
        for (String plugin : classes) {
            String internalName = plugin.replace('.', '/');
            byte[] bytes = index.isModClass(internalName) ? index.bytes(internalName) : null;
            if (bytes == null) {
                continue;
            }
            Map<String, @Nullable Object> values = new LinkedHashMap<>();
            try {
                for (Field field : Class.forName(plugin, false, loader).getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) && PluginDecisions.keeps(field.getType())) {
                        field.setAccessible(true);
                        values.put(field.getName(), field.get(null));
                    }
                }
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                report.problem("the mixin plugin " + plugin + "'s decisions couldn't be read, so the browser makes "
                        + "them again: " + cause(e));
                continue;
            }
            byte[] changed = PluginDecisions.keep(bytes, values);
            if (changed != null) {
                // Another mod's mixins may have changed the plugin's class; bytes() reads that first.
                if (index.changed().containsKey(internalName)) {
                    index.change(internalName, changed);
                } else {
                    index.replaceModClass(internalName, changed);
                }
                values.forEach((name, value) -> kept.add(plugin + "." + name + " = " + value));
            }
        }
        if (!kept.isEmpty()) {
            report.note("Mixin plugins' decisions kept for the browser, as they were when the mixins were chosen: "
                    + String.join(", ", kept));
        }
    }

    /**
     * Leaves out the configs whose plugin can't even be made here: Mixin would then fail every
     * mod's mixins, where Fabric would stop the game. Only that mod loses them, and the report says
     * why.
     */
    private void checkPlugins(Map<String, String> configMods, ClassLoader loader) {
        Iterator<Map.Entry<String, String>> configs = configMods.entrySet().iterator();
        while (configs.hasNext()) {
            Map.Entry<String, String> config = configs.next();
            String plugin = plugins.get(config.getKey());
            if (plugin == null) {
                continue;
            }
            try {
                Class.forName(plugin, true, loader).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                report.problem(config.getValue() + ": the mixin plugin of " + config.getKey() + " fails here, so "
                        + "that config's mixins are left out: " + cause(e));
                configs.remove();
            }
        }
    }

    private void apply(Map<String, String> configMods) throws ReflectiveOperationException {
        System.setProperty("mixin.bootstrapService", OfflineMixinServiceBootstrap.class.getName());
        System.setProperty("mixin.service", OfflineMixinService.class.getName());
        MixinBootstrap.init();
        MixinExtrasBootstrap.init();
        for (String config : configMods.keySet()) {
            Mixins.addConfiguration(config);
        }
        for (Config config : Mixins.getConfigs()) {
            String modId = configMods.get(config.getName());
            if (modId != null) {
                config.getConfig().decorate(FabricUtil.KEY_MOD_ID, modId);
                config.getConfig().decorate(FabricUtil.KEY_COMPATIBILITY, FabricUtil.COMPATIBILITY_LATEST);
            }
        }
        // As Fabric Loader does once the game's classes may load: Mixin's INIT and DEFAULT phases.
        Method gotoPhase = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
        gotoPhase.setAccessible(true);
        try {
            gotoPhase.invoke(null, MixinEnvironment.Phase.INIT);
            gotoPhase.invoke(null, MixinEnvironment.Phase.DEFAULT);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Mixin couldn't start: " + cause(e), e);
        }
        IMixinTransformer transformer = OfflineMixinService.transformer;
        if (transformer == null) {
            throw new IllegalStateException("Mixin offered no transformer");
        }
        logged("Mixin start-up");

        int applied = 0;
        for (Map.Entry<String, Set<String>> target : targets.entrySet()) {
            String internalName = target.getKey();
            String name = internalName.replace('/', '.');
            byte[] bytes = index.bytes(internalName);
            if (bytes == null) {
                report.note(name + " isn't in this game (" + String.join(", ", target.getValue())
                        + "); its mixins are skipped, as Fabric does for @Pseudo ones");
                continue;
            }
            try {
                byte[] transformed = transformer.transformClassBytes(name, name, bytes);
                if (transformed != null && !Arrays.equals(transformed, bytes)) {
                    index.change(internalName, transformed);
                    applied++;
                    reportUnplaced(name, transformed);
                }
            } catch (Throwable t) { // Mixin reports apply failures as Errors
                report.problem("mixins into " + name + " (" + String.join(", ", target.getValue())
                        + ") failed, so it keeps its own code: " + cause(t));
            }
            logged(name);
        }
        report.note("Mixins applied to " + applied + " of " + targets.size() + " target classes");
        fillStaticAccessors(transformer);
        addGenerated(transformer);
    }

    private static final Set<String> ACCESSORS =
            Set.of("Lorg/spongepowered/asm/mixin/gen/Accessor;", "Lorg/spongepowered/asm/mixin/gen/Invoker;");

    /**
     * Accessor mixins' static accessors and invokers: in the jar their bodies only throw, and Mixin
     * fills them in to reach the target as the accessor's own class loads. Each such accessor goes
     * through the transformer, as Fabric's class loader would load it. Only the applied ones, which
     * their targets now implement: Mixin refuses to load one its plugin left out, and nothing that
     * runs calls it.
     */
    private void fillStaticAccessors(IMixinTransformer transformer) {
        Set<String> applied = new HashSet<>();
        for (String target : targets.keySet()) {
            byte[] bytes = index.changed().get(target);
            if (bytes != null) {
                applied.addAll(Arrays.asList(new ClassReader(bytes).getInterfaces()));
            }
        }
        int filled = 0;
        for (Map.Entry<String, Boolean> mixin : mixins.entrySet()) {
            String internalName = mixin.getKey();
            byte[] bytes = mixin.getValue() && applied.contains(internalName) ? index.bytes(internalName) : null;
            if (bytes == null || !hasStaticAccessors(bytes)) {
                continue;
            }
            String name = internalName.replace('/', '.');
            try {
                byte[] transformed = transformer.transformClassBytes(name, name, bytes);
                if (transformed != null && !Arrays.equals(transformed, bytes)) {
                    index.replaceModClass(internalName, transformed);
                    filled++;
                }
            } catch (Throwable t) { // Mixin reports load failures as Errors
                report.problem(
                        name + "'s static accessors couldn't be filled in, so they throw if called: " + cause(t));
            }
        }
        if (filled > 0) {
            report.note(filled + " accessor mixins' static accessors filled in");
        }
    }

    private static boolean hasStaticAccessors(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
        return node.methods.stream()
                .filter(method -> (method.access & Opcodes.ACC_STATIC) != 0 && method.visibleAnnotations != null)
                .flatMap(method -> method.visibleAnnotations.stream())
                .anyMatch(annotation -> ACCESSORS.contains(annotation.desc));
    }

    /** Reads a mixin config: its mixins and their targets, and the config as Mixin gets it. */
    private boolean readConfig(ModJar mod, String config, Map<String, byte[]> resources) {
        byte[] json = mod.entries().get(config);
        if (json == null) {
            report.problem(mod.id() + ": mixin config " + config + " is listed but missing");
            return false;
        }
        JsonObject object;
        try {
            object = JsonParser.parseString(new String(json, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            report.problem(mod.id() + ": mixin config " + config + " isn't valid JSON; skipped");
            return false;
        }
        object.addProperty("required", false);
        JsonObject injectors = object.get("injectors") instanceof JsonObject options ? options : new JsonObject();
        boolean required = injectors.get("defaultRequire") instanceof JsonPrimitive value
                && value.isNumber()
                && value.getAsInt() > 0;
        injectors.addProperty("defaultRequire", 0);
        object.add("injectors", injectors);
        if (object.get("plugin") instanceof JsonPrimitive plugin && plugin.isString()) {
            plugins.put(config, plugin.getAsString());
        }
        resources.put(config, new Gson().toJson(object).getBytes(StandardCharsets.UTF_8));
        String pack = object.has("package") ? object.get("package").getAsString() : "";
        for (String side : List.of("mixins", "client")) {
            JsonElement listed = object.get(side);
            if (listed == null || !listed.isJsonArray()) {
                continue;
            }
            for (JsonElement entry : listed.getAsJsonArray()) {
                String mixin = (pack.isEmpty() ? "" : pack + ".") + entry.getAsString();
                readMixin(mod, mixin.replace('.', '/'), required);
            }
        }
        return true;
    }

    private void readMixin(ModJar mod, String internalName, boolean requiredByDefault) {
        byte[] bytes = mod.entries().get(internalName + ".class");
        if (bytes == null) {
            report.problem(mod.id() + ": mixin " + internalName.replace('/', '.') + " is listed but missing");
            return;
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        mixins.put(internalName, (node.access & Opcodes.ACC_INTERFACE) != 0);
        if (makeInjectorsOptional(node, mod.id(), requiredByDefault)) {
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            index.replaceModClass(internalName, writer.toByteArray());
        }
        for (List<AnnotationNode> annotations : Arrays.asList(node.invisibleAnnotations, node.visibleAnnotations)) {
            if (annotations == null) {
                continue;
            }
            for (AnnotationNode annotation : annotations) {
                if (!annotation.desc.equals(MIXIN_ANNOTATION) || annotation.values == null) {
                    continue;
                }
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    Object key = annotation.values.get(i);
                    Object value = annotation.values.get(i + 1);
                    if (!(value instanceof List<?> list)) {
                        continue;
                    }
                    for (Object target : list) {
                        String targetName = null;
                        if ("value".equals(key) && target instanceof Type type) {
                            targetName = type.getInternalName();
                        } else if ("targets".equals(key) && target instanceof String string) {
                            targetName = string.replace('.', '/');
                        }
                        if (targetName != null) {
                            targets.computeIfAbsent(targetName, k -> new TreeSet<>())
                                    .add(mod.id());
                        }
                    }
                }
            }
        }
    }

    /**
     * Makes a mixin's injectors optional, keeping which were required: Rinth's game differs from
     * Mojang's where Rinth changed it, and a mod should lose only the part that no longer fits
     * ({@link #reportUnplaced} says which). Returns whether an annotation changed.
     */
    private boolean makeInjectorsOptional(ClassNode node, String modId, boolean requiredByDefault) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (List<AnnotationNode> annotations :
                    Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
                if (annotations == null) {
                    continue;
                }
                for (AnnotationNode annotation : annotations) {
                    if (!isInjector(annotation.desc)) {
                        continue;
                    }
                    boolean required = requiredByDefault;
                    List<Object> values = annotation.values;
                    for (int i = 0; values != null && i + 1 < values.size(); i += 2) {
                        if ("require".equals(values.get(i)) && values.get(i + 1) instanceof Integer require) {
                            required = require > 0;
                            if (require != 0) {
                                values.set(i + 1, 0);
                                changed = true;
                            }
                        }
                    }
                    if (required) {
                        requiredHandlers.add(modId + "$" + method.name);
                    }
                }
            }
        }
        return changed;
    }

    private static boolean isInjector(String descriptor) {
        return INJECTORS.contains(descriptor) || descriptor.startsWith("Lcom/llamalad7/mixinextras/injector/");
    }

    /**
     * Says which injectors found no place in a class Mixin changed: their handlers were merged in,
     * but nothing in the class calls them. The game's code differs there, mostly because Rinth
     * changed it; Fabric would stop the game for a required one, here that part of the mod is left
     * out.
     */
    private void reportUnplaced(String name, byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        Set<String> called = new HashSet<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(node.name)) {
                    called.add(call.name + call.desc);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    for (Object argument : dynamic.bsmArgs) {
                        if (argument instanceof Handle handle
                                && handle.getOwner().equals(node.name)) {
                            called.add(handle.getName() + handle.getDesc());
                        }
                    }
                }
            }
        }
        for (MethodNode method : node.methods) {
            Matcher handler = HANDLER.matcher(method.name);
            if (!handler.matches() || called.contains(method.name + method.desc)) {
                continue;
            }
            String what = handler.group(1) + ": " + handler.group(2) + " found no place in " + name
                    + " (the game's code differs there), so that part of the mod is left out";
            if (requiredHandlers.contains(handler.group(1) + "$" + handler.group(2))) {
                report.problem(what);
            } else {
                report.note(what);
            }
        }
    }

    /**
     * MixinExtras's generated classes, then the synthetic classes the changed classes name but no
     * one has: Mixin makes those when asked by name, as Knot asks when one is first loaded.
     */
    private void addGenerated(IMixinTransformer transformer) {
        int made = 0;
        for (Map.Entry<String, byte[]> definition :
                ClassGenUtils.getDefinitions().entrySet()) {
            index.change(definition.getKey().replace('.', '/'), definition.getValue());
            made++;
        }
        Deque<String> queue = new ArrayDeque<>(index.changed().keySet());
        Set<String> seen = new HashSet<>(queue);
        while (!queue.isEmpty()) {
            byte[] bytes = index.bytes(queue.pop());
            if (bytes == null) {
                continue;
            }
            for (String reference : References.of(bytes)) {
                if (!seen.add(reference) || index.bytes(reference) != null) {
                    continue;
                }
                byte[] generated = transformer.generateClass(
                        MixinEnvironment.getCurrentEnvironment(), reference.replace('/', '.'));
                if (generated != null) {
                    index.change(reference, generated);
                    queue.add(reference);
                    made++;
                }
            }
        }
        if (made > 0) {
            report.note(made + " classes generated by Mixin and MixinExtras");
        }
    }

    private void logged(String during) {
        for (String line : RecordingLogger.drain()) {
            if (line.startsWith("WARN")) {
                report.note("Mixin, " + during + ": " + line.substring("WARN ".length()));
            } else {
                report.problem("Mixin, " + during + ": " + line);
            }
        }
    }

    static String cause(Throwable t) {
        StringBuilder chain = new StringBuilder(String.valueOf(t.getMessage()));
        Throwable cause = t.getCause();
        for (int depth = 0; cause != null && depth < 8; depth++) {
            chain.append(" <- ")
                    .append(cause.getClass().getSimpleName())
                    .append(": ")
                    .append(cause.getMessage());
            cause = cause.getCause();
        }
        return chain.toString();
    }
}
