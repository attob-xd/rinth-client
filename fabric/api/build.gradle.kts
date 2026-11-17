import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
}

// Fabric Loader's public API, the one mods are compiled against (net.fabricmc.api,
// net.fabricmc.loader.api, and the version parsing it calls), compiled from the loader's own
// sources (Apache-2.0; fetched at build time, never committed) so every signature matches the jars
// the mods were built against. Behind it is Rinth's own loader (src/main/java): the built-in mods,
// their entrypoints and the game's directories. No game types (gradle/module-boundaries.txt): the
// build-time processor (tools/fabricmods) uses it too, for the mods' mixin plugins. ADR 0065.

val loaderSources: Configuration = configurations.create("loaderSources") { isTransitive = false }

dependencies {
    loaderSources(variantOf(libs.fabric.loader) { classifier("sources") })
}

val loaderSourceDir = layout.buildDirectory.dir("generated/sources/fabric-loader")
val extractLoaderSources = tasks.register<Sync>("extractLoaderSources") {
    description = "Extracts Fabric Loader's public API, and the version parsing it calls, from its sources jar."
    from({ loaderSources.map { zipTree(it) } }) {
        include(
            "net/fabricmc/api/**",
            "net/fabricmc/loader/api/**",
            "net/fabricmc/loader/impl/util/version/**",
            // The old version classes the current ones still extend.
            "net/fabricmc/loader/util/version/**",
        )
    }
    into(loaderSourceDir)
}

sourceSets.main {
    java.srcDir(extractLoaderSources)
}

tasks.withType<JavaCompile>().configureEach {
    // Fabric's sources keep Fabric's conventions, not Rinth's lint and null-safety gates; the few
    // classes of Rinth's here are written to the API they implement.
    options.errorprone {
        excludedPaths = ".*"
    }
    options.compilerArgs.removeAll(listOf("-Xlint:all,-classfile", "-Werror"))
}
