package rinth.tools.fabricmods;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which mods Mod Menu's switches turn on and off without a rebuild. Every mod is built into the one
 * client, all on; {@link ModGuards} makes each switchable mod's mixin code ask whether the mod is on,
 * so any combination runs from the same build (no build per combination: there may be hundreds of mods).
 *
 * <p>A mod can be switched if it's a jar of its own in {@code mods/} and nothing that stays on needs
 * it: not Mod Menu, not the switches (they'd take themselves away), not Fabric API, not anything
 * those need. Turning a mod off turns off what depends on it (Sodium off takes Iris with it);
 * turning one on turns on what it needs.
 *
 * @param switchable the mods with a switch, by id, in the mods' order
 * @param variants the builds: only all on (none off), which every combination runs from
 * @param dependents for each switchable mod, the mods that go off with it (itself included)
 * @param requirements for each switchable mod, the switchable mods that come on with it (itself included)
 */
record ModSwitchPlan(
        List<String> switchable,
        List<Set<String>> variants,
        Map<String, Set<String>> dependents,
        Map<String, Set<String>> requirements) {

    /**
     * The mods that host the switches, so always stay on, and Fabric API: a library other mods need, not
     * something to switch (off, it would only take the mods that need it along).
     */
    static final Set<String> ALWAYS_ON = Set.of("modmenu", "rinth_modtoggles", "fabric-api");

    static ModSwitchPlan of(List<ModJar> mods) {
        Map<String, String> owners = new HashMap<>();
        for (ModJar mod : mods) {
            owners.put(mod.id(), mod.id());
            for (String alias : mod.provides()) {
                owners.putIfAbsent(alias, mod.id());
            }
        }
        // Who needs whom, among the mods here.
        Map<String, Set<String>> needs = new HashMap<>();
        for (ModJar mod : mods) {
            Set<String> needed = new LinkedHashSet<>();
            for (String dependency : mod.depends().keySet()) {
                String owner = owners.get(dependency);
                if (owner != null && !owner.equals(mod.id())) {
                    needed.add(owner);
                }
            }
            needs.put(mod.id(), needed);
        }
        Set<String> keptOn = new TreeSet<>();
        for (String id : ALWAYS_ON) {
            if (needs.containsKey(id)) {
                keptOn.addAll(reach(id, needs));
            }
        }
        List<String> switchable = new ArrayList<>();
        for (ModJar mod : mods) {
            if (!mod.source().contains("!") && !keptOn.contains(mod.id())) {
                switchable.add(mod.id());
            }
        }
        Map<String, Set<String>> neededBy = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : needs.entrySet()) {
            for (String needed : entry.getValue()) {
                neededBy.computeIfAbsent(needed, key -> new LinkedHashSet<>()).add(entry.getKey());
            }
        }
        Map<String, Set<String>> dependents = new TreeMap<>();
        Map<String, Set<String>> requirements = new TreeMap<>();
        for (String id : switchable) {
            dependents.put(id, new TreeSet<>(reach(id, neededBy)));
            Set<String> required = new TreeSet<>(reach(id, needs));
            required.retainAll(switchable);
            requirements.put(id, required);
        }
        return new ModSwitchPlan(List.copyOf(switchable), List.of(Set.of()), dependents, requirements);
    }

    /** {@code start} and everything {@code edges} lead to from it. */
    private static Set<String> reach(String start, Map<String, Set<String>> edges) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> next = new ArrayList<>(List.of(start));
        while (!next.isEmpty()) {
            String id = next.remove(next.size() - 1);
            if (seen.add(id)) {
                next.addAll(edges.getOrDefault(id, Set.of()));
            }
        }
        return seen;
    }
}
