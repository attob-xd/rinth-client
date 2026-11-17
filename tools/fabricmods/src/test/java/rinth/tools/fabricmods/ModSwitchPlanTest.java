package rinth.tools.fabricmods;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Which mods Mod Menu switches without a rebuild, and which combinations are built. */
class ModSwitchPlanTest {

    private static ModJar mod(String id, String source, String... needs) {
        Map<String, List<String>> depends = new LinkedHashMap<>();
        depends.put("minecraft", List.of("*"));
        for (String need : needs) {
            depends.put(need, List.of("*"));
        }
        return new ModJar(
                id, "1", id, "", "*", List.of(), List.of(), List.of(), depends, Map.of(), List.of(), List.of(), null,
                source, Map.of(), "");
    }

    /** As in mods/ now: Iris needs Sodium, the switches need Mod Menu, Iris nests a library. */
    private static List<ModJar> mods() {
        return List.of(
                mod("iris", "iris.jar", "sodium", "glsl-transformer"),
                mod("glsl-transformer", "iris.jar!META-INF/jars/glsl.jar"),
                mod("modmenu", "modmenu.jar"),
                mod("rinth_modtoggles", "rinth-modtoggles.jar", "modmenu"),
                mod("sodium", "sodium.jar"));
    }

    @Test
    void theSwitchesHostsAndNestedJarsHaveNoSwitch() {
        assertThat(ModSwitchPlan.of(mods()).switchable()).containsExactly("iris", "sodium");
    }

    @Test
    void turningAModOffTakesWhatNeedsItAndTurningOneOnBringsWhatItNeeds() {
        ModSwitchPlan plan = ModSwitchPlan.of(mods());
        assertThat(plan.dependents().get("sodium")).containsExactly("iris", "sodium");
        assertThat(plan.dependents().get("iris")).containsExactly("iris");
        assertThat(plan.requirements().get("iris")).containsExactly("iris", "sodium");
        assertThat(plan.requirements().get("sodium")).containsExactly("sodium");
    }

    @Test
    void oneBuildRunsEveryCombination() {
        List<ModJar> many = List.of(mod("a", "a.jar"), mod("b", "b.jar"), mod("c", "c.jar"), mod("d", "d.jar"));
        ModSwitchPlan plan = ModSwitchPlan.of(many);
        assertThat(plan.switchable()).containsExactly("a", "b", "c", "d");
        assertThat(plan.variants()).containsExactly(Set.of());
    }

    @Test
    void withNothingSwitchableOnlyTheOneBuildIsMade() {
        ModSwitchPlan plan = ModSwitchPlan.of(List.of(mod("modmenu", "modmenu.jar")));
        assertThat(plan.switchable()).isEmpty();
        assertThat(plan.variants()).containsExactly(Set.of());
    }
}
