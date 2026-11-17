plugins {
    `java-library`
}

// JS Mods (ADR 0067): hands the game's ticks, joins and HUD to the platform's script host, where
// the browser runs the JavaScript mods added in the page's Mods panel.
// A feature module: it sees the module framework and nothing else.
dependencies {
    api(project(":framework"))

    testImplementation(testFixtures(project(":framework")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.launcher)
}
