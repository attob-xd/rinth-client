import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
}

// Rinth's switches in Mod Menu's list: an ON/OFF switch at the right of each mod's row. Every mod is
// built into the one client and its mixin code asks whether it's on (tools/fabricmods' ModGuards), so
// a switch saves the choice and the page reloads to use it: no rebuild, any combination. Built like any Fabric mod, against the game's names, Mixin's annotations and the Mod
// Menu jar in mods/. Never built into the client unless copied to mods/:
// `./gradlew :fabric:modtoggles:installModToggles`.

val modMenu = fileTree(rootProject.layout.projectDirectory.dir("mods")) { include("modmenu-*.jar") }

dependencies {
    compileOnly(project(":client"))
    compileOnly(project(":fabric:api"))
    compileOnly(libs.sponge.mixin) { isTransitive = false }
    compileOnly(modMenu)
}

tasks.withType<JavaCompile>().configureEach {
    // Fabric's conventions, as the test mod: not Rinth's gates, and no annotation processing.
    options.errorprone {
        excludedPaths = ".*"
    }
    options.compilerArgs.removeAll(listOf("-Xlint:all,-classfile", "-Werror"))
    options.compilerArgs.add("-proc:none")
}

tasks.jar {
    archiveFileName = "rinth-modtoggles.jar"
}

tasks.register<Copy>("installModToggles") {
    group = "build"
    description = "Copies Mod Menu's switches into mods/, so the next build builds them in."
    from(tasks.jar)
    into(rootProject.layout.projectDirectory.dir("mods"))
}
