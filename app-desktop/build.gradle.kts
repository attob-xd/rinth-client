import java.time.Duration
import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    application
}

// The desktop dev runtime: the game on a normal JVM, through platform-desktop.
// `./gradlew :app-desktop:run` starts it offline, with its game directory in run/ (git-ignored).

dependencies {
    implementation(project(":client"))
    implementation(project(":platform-desktop"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

val repoRoot = rootProject.layout.projectDirectory
val minecraftVersion: String = Properties().run {
    repoRoot.file("rinth.properties").asFile.reader().use { load(it) }
    getProperty("minecraft.version")
}
val versionDir = repoRoot.dir("work/cache/versions/$minecraftVersion")

/** This machine's LWJGL natives, as `rinth fetch` downloaded them. */
val nativeJars = run {
    val root = repoRoot.asFile
    providers.fileContents(versionDir.file("natives.txt")).asText.map { text ->
        text.lines().filter { it.isNotBlank() }.map { root.resolve(it.trim()) }
    }.orElse(emptyList())
}

/** The asset index id from the version JSON; `./rinth assets` downloads it. */
val assetIndex = providers.fileContents(versionDir.file("$minecraftVersion.json")).asText.map { text ->
    @Suppress("UNCHECKED_CAST")
    ((JsonSlurper().parseText(text) as Map<String, Any>)["assetIndex"] as Map<String, Any>)["id"] as String
}

application {
    applicationName = "rinth-desktop"
    mainClass = "rinth.app.desktop.DesktopMain"
}

/** The launcher's JVM arguments for this version, with this machine's natives. */
val gameJvmArgs = repoRoot.dir("work/natives").asFile.let { natives ->
    listOf(
        "-Xms2G", "-Xmx4G", "-XX:+UseCompactObjectHeaders", "-XX:+UseZGC",
        "-XX:StackShadowPages=32",
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "-Dorg.lwjgl.system.SharedLibraryExtractPath=${natives.resolve("lwjgl")}",
        "-Dio.netty.native.workdir=${natives.resolve("netty")}",
        "-Dminecraft.launcher.brand=rinth-dev",
    ) + if (System.getProperty("os.name").lowercase().startsWith("mac")) {
        listOf("-XstartOnFirstThread")
    } else emptyList()
}

/**
 * Runs the game offline in `gameDir`, the way the launcher would: this machine's natives, the
 * launcher's JVM arguments for this version (the version JSON's "jvm" and "default-user-jvm"
 * lists, minus other operating systems'), and the downloaded assets.
 */
fun JavaExec.runsGame(gameDir: File, username: Provider<String>, smoke: String?) {
    val assetsDir = repoRoot.dir("work/cache/assets").asFile
    val version = minecraftVersion
    val index = assetIndex
    classpath = sourceSets.main.get().runtimeClasspath + files(nativeJars)
    mainClass = "rinth.app.desktop.DesktopMain"
    workingDir = gameDir
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    jvmArgs(gameJvmArgs)
    // `-Printh.browserStartup`: the browser build's start-up on the desktop, to try it with a
    // debugger: the data fixer built only when needed, and models loaded after the title.
    if (providers.gradleProperty("rinth.browserStartup").isPresent) {
        systemProperty("rinth.browserStartup", "true")
    }
    if (smoke != null) {
        systemProperty("rinth.smoke", smoke)
        // A backstop behind the smoke test's own watchdog.
        timeout = Duration.ofMinutes(15)
    }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--version", version,
            "--versionType", "rinth-dev",
            "--gameDir", gameDir.path,
            "--assetsDir", assetsDir.path,
            "--assetIndex", index.get(),
            "--accessToken", "0",
            "--username", username.get(),
        )
    })
    doFirst {
        gameDir.mkdirs()
        if (!assetsDir.resolve("indexes/${index.get()}.json").isFile) {
            throw GradleException("The game's assets aren't downloaded yet. Run: ./rinth assets")
        }
    }
}

tasks.named<JavaExec>("run") {
    description = "Runs the game on the desktop platform, offline, in run/."
    runsGame(repoRoot.dir("run").asFile, providers.gradleProperty("rinth.username").orElse("Dev"), null)
    // A name given here stands; without one, Edit Profile opens first and its saved name is used.
    if (providers.gradleProperty("rinth.username").isPresent) {
        systemProperty("rinth.fixedName", "true")
    }
    // `-Printh.online=ws://127.0.0.1:8090/v1/ws`: Rinth accounts against another service (ADR 0058,
    // tools/onlinedev), as the page's ?online= does, without rebuilding the client for a new URL.
    providers.gradleProperty("rinth.online").orNull?.let { systemProperty("rinth.online.url", it) }
    // `-Printh.updates=on`: the title screen's update notice, which only release builds show
    // (with tools/onlinedev's `--release <version>`).
    providers.gradleProperty("rinth.updates").orNull?.let { systemProperty("rinth.updates", it) }
    // `-Printh.sceneAgent=<port>`: remote control over a loopback port (SceneAgent), for scripted looks.
    providers.gradleProperty("rinth.sceneAgent").orNull?.let { systemProperty("rinth.sceneAgent", it) }
}

// The smoke test (rinth.app.desktop.smoke.SmokeTest): a scripted session, results and
// screenshots in run/smoke-*/rinth-smoke/. smokeTest plays alone; smokeHost waits for
// smokeJoin, run at the same time from another terminal, to join its LAN world.
tasks.register<JavaExec>("smokeTest") {
    group = "verification"
    description = "Plays a scripted session on the desktop runtime and reports what worked."
    runsGame(repoRoot.dir("run/smoke-host").asFile, providers.provider { "RinthHost" }, "host")
}

tasks.register<JavaExec>("smokeHost") {
    group = "verification"
    description = "Like smokeTest, then waits up to three minutes for smokeJoin to join over LAN."
    runsGame(repoRoot.dir("run/smoke-host").asFile, providers.provider { "RinthHost" }, "host:180")
}

// Joins a dedicated server, over a WebSocket for a ws:// address (for a vanilla server,
// through tools/wsbridge), changes dimension there and back, disconnects and reconnects. The
// player, RinthSmoke, must be an operator on that server.
tasks.register<JavaExec>("smokeServer") {
    group = "verification"
    description = "Joins -Printh.server=<address> (ws://... for WebSocket), changes dimension, reconnects."
    val server = providers.gradleProperty("rinth.server").orElse("ws://127.0.0.1:8816")
    runsGame(repoRoot.dir("run/smoke-server").asFile, providers.provider { "RinthSmoke" }, null)
    systemProperty("rinth.smoke", "server:" + server.get())
    timeout = Duration.ofMinutes(15)
}

// Skins both ways on an Eagler server. smokeSkinA and smokeSkinB, run at the same time, each
// set a generated skin (A: slim, with a cape; B: wide), join -Printh.server=<ws://...>, and check
// the other's arrives from the server and draws as the texture it sent.
listOf("A" to "B", "B" to "A").forEach { (me, peer) ->
    tasks.register<JavaExec>("smokeSkin$me") {
        group = "verification"
        description = "Joins an Eagler server as RinthSkin$me and checks RinthSkin$peer's skin (run with smokeSkin$peer)."
        val server = providers.gradleProperty("rinth.server").orElse("ws://127.0.0.1:25577")
        runsGame(repoRoot.dir("run/smoke-skin-$me").asFile, providers.provider { "RinthSkin$me" }, null)
        systemProperty("rinth.smoke", "skins:RinthSkin$peer:" + server.get())
        timeout = Duration.ofMinutes(15)
    }
}

// Rinth accounts and chat (ADR 0058, ADR 0059) against a real online service: smokeOnlineA and
// smokeOnlineB, run at the same time, each make an account on -Printh.online (default a local
// online service, ws://127.0.0.1:8080/v1/ws), join -Printh.server (default the local 26.3 server,
// 127.0.0.1:25582), see each other as Rinth players, become friends, message, block, and delete
// their accounts.
listOf("A" to "B", "B" to "A").forEach { (me, peer) ->
    tasks.register<JavaExec>("smokeOnline$me") {
        group = "verification"
        description = "Accounts and chat as RinthOnline$me with RinthOnline$peer (run with smokeOnline$peer)."
        val server = providers.gradleProperty("rinth.server").orElse("127.0.0.1:25582")
        val online = providers.gradleProperty("rinth.online").orElse("ws://127.0.0.1:8080/v1/ws")
        runsGame(repoRoot.dir("run/smoke-online-$me").asFile, providers.provider { "RinthOnline$me" }, null)
        systemProperty("rinth.smoke", "online:RinthOnline$peer:" + server.get())
        systemProperty("rinth.online.url", online.get())
        timeout = Duration.ofMinutes(15)
    }
}

// A password login and a cookie on an Eagler server whose auth plugin asks this player
// (AuthERinth: the EAGLER_SHA256 method) for the password "rinth" and then sets a cookie.
tasks.register<JavaExec>("smokeAuth") {
    group = "verification"
    description = "Logs in to -Printh.server=<ws://...> with a password, then again by the server's cookie."
    val server = providers.gradleProperty("rinth.server").orElse("ws://127.0.0.1:25577")
    val name = providers.gradleProperty("rinth.username").orElse("AuthERinth")
    runsGame(repoRoot.dir("run/smoke-auth").asFile, name, null)
    systemProperty("rinth.smoke", "auth:" + server.get())
    timeout = Duration.ofMinutes(15)
}

// The legacy behaviour layer (ADR 0020) on a 1.8 server (-Printh.server, default the 1.8.9 server's
// development bridge), checked where the server can tell; -Printh.rcon is that server's console.
tasks.register<JavaExec>("smokeLegacy") {
    group = "verification"
    description = "Checks 1.8's sneaking, pick margin, cooldown, ladders and sword blocking on a 1.8 server."
    val server = providers.gradleProperty("rinth.server").orElse("ws://127.0.0.1:8801")
    val rcon = providers.gradleProperty("rinth.rcon").orElse("127.0.0.1:25611")
    runsGame(repoRoot.dir("run/smoke-legacy").asFile, providers.provider { "RinthLegacy" }, null)
    systemProperty("rinth.smoke", "legacy:" + server.get())
    systemProperty("rinth.rcon", rcon.get())
    timeout = Duration.ofMinutes(15)
}

// Voice chat's signalling on an Eagler server with voice on, with WebRTC scripted on the
// desktop (which has none): smokeVoiceA and smokeVoiceB, run together, turn global voice on and
// check the server pairs them.
listOf("A" to "B", "B" to "A").forEach { (me, peer) ->
    tasks.register<JavaExec>("smokeVoice$me") {
        group = "verification"
        description = "Joins an Eagler server as RinthVoice$me and checks voice pairs it with RinthVoice$peer."
        val server = providers.gradleProperty("rinth.server").orElse("ws://127.0.0.1:25577")
        runsGame(repoRoot.dir("run/smoke-voice-$me").asFile, providers.provider { "RinthVoice$me" }, null)
        systemProperty("rinth.smoke", "voice:RinthVoice$peer:" + server.get())
        timeout = Duration.ofMinutes(15)
    }
}

// The module menu (ADR 0046), Toggle Sprint in a world, the profile saved.
tasks.register<JavaExec>("smokeModules") {
    group = "verification"
    description = "Opens the module menu, latches sprint with Toggle Sprint, and screenshots the module screens."
    runsGame(repoRoot.dir("run/smoke-modules").asFile, providers.provider { "RinthSmoke" }, "modules")
}

// The client's look (ADR 0048), both GUI presets, on the screens players see most.
tasks.register<JavaExec>("smokeLook") {
    group = "verification"
    description = "Screenshots the title, options, lists, module menu, pause menu and inventory in both GUI presets."
    runsGame(repoRoot.dir("run/smoke-look").asFile, providers.provider { "RinthSmoke" }, "look")
}

// Text that must fit its box, at GUI scale 2, 3 and 4 in a Chromebook-sized window, and the
// narrowest layouts at 3 in smaller windows.
tasks.register<JavaExec>("smokeFit") {
    group = "verification"
    description = "Screenshots screens with long or player-chosen text at GUI scale 2, 3 and 4 in a 1366x768 window, and at 3 in 1280x720 and 1024x768."
    runsGame(repoRoot.dir("run/smoke-fit").asFile, providers.provider { "RinthSmoke" }, "fit")
    args("--width", "1366", "--height", "768")
}

// Every screen's layout judged at the GUI sizes players get, from 320x240 up, in both fonts: a
// widget off the screen or over another, or text past its box, fails. Problems in layout.txt.
tasks.register<JavaExec>("smokeLayout") {
    group = "verification"
    description = "Checks every Rinth screen's layout at six GUI sizes from 320x240, in both fonts."
    runsGame(repoRoot.dir("run/smoke-layout").asFile, providers.provider { "RinthSmoke" }, "layout")
}

// The HUD editor (ADR 0049) from the module menu, dragged and resized in both GUI presets.
tasks.register<JavaExec>("smokeHudEditor") {
    group = "verification"
    description = "Opens the HUD editor from the module menu, drags and resizes Toggle Sprint's line, checks Potato's backdrop rate."
    runsGame(repoRoot.dir("run/smoke-hudeditor").asFile, providers.provider { "RinthSmoke" }, "hudeditor")
}

// ADR 0050: the first modules in a world: armor and effects, a moved sidebar, chat, fire.
tasks.register<JavaExec>("smokeFeatures") {
    group = "verification"
    description = "Turns on Armor & Potion View, Custom Scoreboard, Clear Chat, Low Fire and No Hurt Cam in a world and screenshots them."
    runsGame(repoRoot.dir("run/smoke-features").asFile, providers.provider { "RinthSmoke" }, "features")
}

// ADR 0051: the minimap and waypoints in a world, their screens, a death's waypoint.
tasks.register<JavaExec>("smokeWaypoints") {
    group = "verification"
    description = "Turns on Minimap and Waypoints in a world with waypoints round the player and screenshots them and their screens."
    runsGame(repoRoot.dir("run/smoke-waypoints").asFile, providers.provider { "RinthSmoke" }, "waypoints")
}

// ADR 0052: the HUD modules (Keystrokes, CPS, FPS, Reach, Coordinates, Shield & Totem) in a world.
tasks.register<JavaExec>("smokeHudModules") {
    group = "verification"
    description = "Turns on the HUD modules of ADR 0052 in a world, clicks a mob and holds keys, and screenshots them."
    runsGame(repoRoot.dir("run/smoke-hudmodules").asFile, providers.provider { "RinthSmoke" }, "hudmodules")
}

// ADR 0053: the visual modules (Crosshair, Cooldown, Weather, Freelook, Low Shield, 1.7 Animations), and Hitboxes.
tasks.register<JavaExec>("smokeVisualModules") {
    group = "verification"
    description = "Runs the visual and combat modules of ADR 0053, and Hitboxes, in a world and screenshots them."
    runsGame(repoRoot.dir("run/smoke-visualmodules").asFile, providers.provider { "RinthSmoke" }, "visualmodules")
}

// ADR 0054: Shulker Tooltips, Screenshot Manager and Theme Editor.
tasks.register<JavaExec>("smokeScreenModules") {
    group = "verification"
    description = "Runs Shulker Tooltips, Screenshot Manager and Theme Editor and screenshots them."
    runsGame(repoRoot.dir("run/smoke-screenmodules").asFile, providers.provider { "RinthSmoke" }, "screenmodules")
}

// ADR 0061: cosmetics worn, the Wardrobe, emotes and the emote wheel.
tasks.register<JavaExec>("smokeCosmetics") {
    group = "verification"
    description = "Wears cosmetics on the title screen and in a world, plays emotes and screenshots the Wardrobe."
    runsGame(repoRoot.dir("run/smoke-cosmetics").asFile, providers.provider { "RinthSmoke" }, "cosmetics")
}

tasks.register<JavaExec>("smokeScreens") {
    group = "verification"
    description = "Screenshots the screens M2 adds (the edit-server screen's Version button, Edit Profile), edits, replaces and skins game screens (ADR 0047)."
    runsGame(repoRoot.dir("run/smoke-screens").asFile, providers.provider { "RinthSmoke" }, "screens")
}

// ADR 0023: every section meshed by the game's compiler and by Rinth's mesher, compared, over
// terrain of every kind, every dimension and the debug world's every block state.
tasks.register<JavaExec>("smokeMesher") {
    group = "verification"
    description = "Meshes varied worlds with both the game's compiler and Rinth's mesher and compares them."
    runsGame(repoRoot.dir("run/smoke-mesher").asFile, providers.provider { "RinthMesher" }, "mesher")
    systemProperty("rinth.mesher", "verify")
    timeout = Duration.ofMinutes(45)
}

tasks.register<JavaExec>("smokeJoin") {
    group = "verification"
    description = "Joins smokeHost's LAN world through the platform transport."
    runsGame(repoRoot.dir("run/smoke-join").asFile, providers.provider { "RinthGuest" }, "join:127.0.0.1:25577")
}

// How the screenshot-diff harness (tools/webbuild's ScreenshotDiff, `:app-web:screenshotDiff`)
// starts this game to render its reference images with Mojang's OpenGL backend. One line per
// token: `java <path>`, `jvm <argument>`, `classpath <entry>`, `main <class>`, `arg <argument>`;
// the harness adds the game directory, the player, the server and its remote-control port.
tasks.register("sceneLaunch") {
    group = "verification"
    description = "Writes build/scene-launch.txt: how the screenshot-diff harness starts the desktop game."
    val classpath = sourceSets.main.get().runtimeClasspath + files(nativeJars)
    val launcher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val output = layout.buildDirectory.file("scene-launch.txt")
    val jvm = gameJvmArgs
    val version = minecraftVersion
    val index = assetIndex
    val assetsDir = repoRoot.dir("work/cache/assets").asFile
    inputs.files(classpath)
    inputs.property("jvm", jvm)
    outputs.file(output)
    doLast {
        val lines = mutableListOf("java " + launcher.get().executablePath.asFile.path)
        jvm.forEach { lines.add("jvm $it") }
        classpath.files.forEach { lines.add("classpath " + it.path) }
        lines.add("main rinth.app.desktop.DesktopMain")
        listOf("--version", version, "--versionType", "rinth-dev", "--assetsDir", assetsDir.path,
            "--assetIndex", index.get(), "--accessToken", "0").forEach { lines.add("arg $it") }
        output.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

// ADR 0041: the game's dedicated server with the browser build's world generation changes on or
// off (-Pworldgen=on|off), in run/worldgen-<on|off>/ from server.properties' seed, typing
// tools/worldgen/script.txt into its console. tools/worldgen/compare.py compares the two worlds.
tasks.register<JavaExec>("worldgenServer") {
    group = "verification"
    description = "Runs the dedicated server with ADR 0041's world generation changes on or off (-Pworldgen=on|off)."
    val mode = providers.gradleProperty("worldgen").orElse("off")
    val serverDir = mode.map { repoRoot.dir("run/worldgen-$it").asFile }
    classpath = sourceSets.main.get().runtimeClasspath + files(nativeJars)
    mainClass = "rinth.app.desktop.smoke.WorldgenServer"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    jvmArgs(gameJvmArgs)
    systemProperty("rinth.worldgen", mode.get())
    systemProperty("rinth.worldgen.script", repoRoot.file("tools/worldgen/script.txt").asFile.path)
    // One background thread (-Pworldgen.threads=1): chunks generate in one order, so the features
    // one chunk places in its neighbours land the same way each run.
    providers.gradleProperty("worldgen.threads").orNull?.let { systemProperty("max.bg.threads", it) }
    if (providers.gradleProperty("worldgen.verify").isPresent) {
        systemProperty("rinth.worldgen.verify", "true")
    }
    val dir = serverDir.get()
    val properties = repoRoot.file("tools/worldgen/server.properties").asFile
    workingDir = dir
    args("--nogui", "--universe", dir.path)
    timeout = Duration.ofMinutes(20)
    doFirst {
        dir.deleteRecursively()
        dir.mkdirs()
        dir.resolve("eula.txt").writeText("eula=true\n")
        properties.copyTo(dir.resolve("server.properties"))
    }
}
