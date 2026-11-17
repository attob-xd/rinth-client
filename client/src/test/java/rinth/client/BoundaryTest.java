package rinth.client;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Rinth's game-side code runs in every build, so it's platform-neutral: it reaches the machine
 * only through platform-api, never through LWJGL, TeaVM or a platform implementation. That covers
 * the rinth.client packages and every vendor class Rinth replaces (patches/classes.txt), which
 * is the point of replacing them. Vendor code keeps its own LWJGL calls until a patch routes them.
 */
class BoundaryTest {

    private static JavaClasses classes;
    private static Set<String> replacedClasses;

    @BeforeAll
    static void importClasses() throws IOException {
        Path manifest = Path.of(System.getProperty("rinth.repoRoot", "../..")).resolve("patches/classes.txt");
        replacedClasses = Files.readAllLines(manifest).stream()
                .map(line -> line.replaceAll("#.*", "").strip())
                .filter(line -> line.startsWith("replace "))
                .map(line -> line.split("\\s+", -1)[1])
                .collect(Collectors.toSet());
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("rinth.client", "com.mojang", "net.minecraft");
    }

    @Test
    void rinthCodeIsPlatformNeutral() {
        DescribedPredicate<JavaClass> rinthCode = DescribedPredicate.describe(
                "Rinth code (rinth.client and the classes patches/classes.txt replaces)",
                javaClass -> javaClass.getPackageName().startsWith("rinth.client")
                        || replacedClasses.contains(outermost(javaClass).getName()));

        noClasses()
                .that(rinthCode)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.lwjgl..", "org.teavm..", "rinth.platform.desktop..", "rinth.platform.web..")
                .check(classes);
    }

    @Test
    void everyReplacementWasImported() {
        // Guards the rule above: a replacement it can't see isn't checked.
        Set<String> imported = classes.stream().map(JavaClass::getName).collect(Collectors.toSet());
        assertThat(imported).containsAll(replacedClasses);
    }

    private static JavaClass outermost(JavaClass javaClass) {
        JavaClass current = javaClass;
        while (current.getEnclosingClass().isPresent()) {
            current = current.getEnclosingClass().get();
        }
        return current;
    }
}
