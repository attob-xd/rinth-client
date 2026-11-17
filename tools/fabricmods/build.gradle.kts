import net.ltgt.gradle.errorprone.errorprone

plugins {
    application
}

// Builds Fabric mods into the client at build time (ADR 0065, fabric/README.md): reads the mod jars
// in mods/ (and the jars they nest), applies their access wideners and mixins to the game's classes
// with Fabric's own Mixin and MixinExtras on a normal JVM, and writes what the browser build compiles
// in: the changed classes, the mods' classes and assets, and the table of their entrypoints. Never
// shipped; fabric/runtime runs it.

dependencies {
    implementation(project(":fabric:api"))
    implementation(libs.sponge.mixin)
    implementation(libs.mixinextras)
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.asm.commons)
    implementation(libs.asm.util)
    // Mixin parses its configs with Gson and leans on Guava, as the game ships them.
    implementation(libs.gson)
    implementation(libs.guava)

    // web-classlib's ServiceProviders, which the generated ModServices binds (ModServicesTest).
    testImplementation(project(":web-classlib"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    applicationName = "rinth-fabricmods"
    mainClass = "rinth.tools.fabricmods.Main"
}

tasks.withType<JavaCompile>().configureEach {
    // Mixin's service interfaces return null where they mean "none"; NullAway can't see that.
    options.errorprone.option("NullAway:ExcludedClasses", "rinth.tools.fabricmods.OfflineMixinService")
}
