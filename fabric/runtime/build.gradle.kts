import java.util.Properties
import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
}

// Starts the Fabric mods built into the client (ADR 0065, fabric/README.md). Its build runs
// tools/fabricmods over mods/ (fabricMods): the changed and added classes go before the game's on
// the browser build's class path (app-web), the mods' assets into Rinth's pack, and the generated
// mod table is compiled here with FabricBoot, which the changed Minecraft class calls. With no mods
// in mods/, nothing is changed and the table is empty.

val repoRoot = rootProject.layout.projectDirectory
val minecraftVersion: String = Properties().run {
    repoRoot.file("rinth.properties").asFile.reader().use { load(it) }
    getProperty("minecraft.version")
}

val fabricmodsTool: Configuration = configurations.create("fabricmodsTool")

dependencies {
    api(project(":fabric:api"))
    implementation(project(":client"))
    // What mixed-in code calls as the game runs: Mixin's callback infos, MixinExtras's operations.
    runtimeOnly(libs.sponge.mixin) { isTransitive = false }
    runtimeOnly(libs.mixinextras) { isTransitive = false }
    fabricmodsTool(project(":tools:fabricmods"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}

val modsDir = repoRoot.dir("mods")
val modsOutput = layout.buildDirectory.dir("fabric-mods")

val fabricMods = tasks.register<JavaExec>("fabricMods") {
    group = "build"
    description = "Applies the Fabric mods in mods/ to the game's classes and writes their table (ADR 0065)."
    classpath = fabricmodsTool
    mainClass = "rinth.tools.fabricmods.Main"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    maxHeapSize = "3g"
    // The game's class path, the patched game and Mojang's libraries, for the mixins' targets.
    // Locals, not the script's values: the configuration cache keeps what the task captures.
    val gameClassPath: FileCollection = files(configurations.runtimeClasspath)
    val mods = modsDir.asFile
    val out = modsOutput
    val list = layout.buildDirectory.file("fabric-mods-classpath.txt")
    val version = minecraftVersion
    inputs.files(fileTree(mods) { include("*.jar") }).withPropertyName("mods")
    inputs.files(gameClassPath).withPropertyName("gameClassPath")
    inputs.property("minecraft", version)
    outputs.dir(out)
    doFirst {
        val file = list.get().asFile
        file.parentFile.mkdirs()
        file.writeText(gameClassPath.files.joinToString("\n") { it.absolutePath } + "\n")
        // In the mods' game directory, as on a PC: mods read and write files there by relative
        // paths (Sodium's ./config/sodium-mixins.properties), not in this project.
        workingDir = out.get().asFile.resolve("game").also { it.mkdirs() }
    }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--mods", mods.absolutePath,
            "--classpath", list.get().asFile.absolutePath,
            "--out", out.get().asFile.absolutePath,
            "--minecraft", version,
        )
    })
}

sourceSets.main {
    java.srcDir(files(modsOutput.map { it.dir("src") }).builtBy(fabricMods))
}

dependencies {
    // The mods' classes, for the generated table's `new`s; the browser build takes them from the output.
    compileOnly(files(modsOutput.map { it.dir("classes") }).builtBy(fabricMods))
}

tasks.withType<JavaCompile>().configureEach {
    // The generated table names the mods' classes as they are.
    options.errorprone.excludedPaths = ".*[/\\\\]fabric-mods[/\\\\]src[/\\\\].*"
}
