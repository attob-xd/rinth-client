package rinth.mesher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The mesher is compiled alone into the page's worker module: it may use only the JDK's basics and
 * JSpecify. No game, library or platform types.
 */
@AnalyzeClasses(packages = "rinth.mesher", importOptions = ImportOption.DoNotIncludeTests.class)
class BoundaryTest {

    @ArchTest
    static final ArchRule onlyJdkAndJspecify = classes()
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java.lang..", "java.util", "org.jspecify..", "rinth.mesher..")
            .allowEmptyShould(true);
}
