import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
}

// A small Fabric mod for Minecraft 26.3 that tests the build-time loader (ADR 0065): a client
// entrypoint, an @Inject, a MixinExtras injector, an accessor, an access widener and a language
// file. Built like any Fabric mod, against the game's own names and Mixin's annotations. Never
// built into the client unless copied to mods/: `./gradlew :fabric:testmod:installTestMod`.

dependencies {
    compileOnly(project(":client"))
    compileOnly(project(":fabric:api"))
    compileOnly(libs.sponge.mixin) { isTransitive = false }
    compileOnly(libs.mixinextras) { isTransitive = false }
}

tasks.withType<JavaCompile>().configureEach {
    // A stand-in for a third-party mod: Fabric's conventions, not Rinth's gates, and no annotation
    // processing (Mixin's writes refmaps, which unobfuscated 26.x doesn't need).
    options.errorprone {
        excludedPaths = ".*"
    }
    options.compilerArgs.removeAll(listOf("-Xlint:all,-classfile", "-Werror"))
    options.compilerArgs.add("-proc:none")
}

tasks.jar {
    archiveFileName = "rinth-testmod.jar"
}

tasks.register<Copy>("installTestMod") {
    group = "build"
    description = "Copies the test mod into mods/, so the next build builds it in."
    from(tasks.jar)
    into(rootProject.layout.projectDirectory.dir("mods"))
}
