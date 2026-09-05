plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.hot.reload)
}

dependencies {
    // Compose Desktop for the hot-reload fixture window (uz.disastrouspumpkin.wdb.dummy.hot.HotAppKt).
    implementation(compose.desktop.currentOs)
    // Note: `hot-reload-runtime-jvm` (DevelopmentEntryPoint) only publishes a
    // `compose-dev-java-runtime` variant wired by the CHR plugin's own hotRun path, so it is not
    // added here — a plain `implementation` breaks a normal runtimeClasspath resolve.
}

kotlin {
    jvmToolchain(21)
}

// The Compose fixture main (used only for live hot-reload verification). Agent tests still
// launch the headless heartbeat `uz.disastrouspumpkin.wdb.dummy.MainKt` via --main-class, so the jar's default
// Main-Class stays the heartbeat.
compose.desktop {
    application {
        mainClass = "uz.disastrouspumpkin.wdb.dummy.hot.HotAppKt"
    }
}

// A self-contained, runnable "uber" JAR used as a stand-in app in agent tests (supervision,
// deploy, log-streaming and JDWP all launch it with `java -jar`), so kotlin-stdlib + Compose
// must be bundled in. Main-Class stays the headless heartbeat so existing agent tests are unchanged.
tasks.jar {
    manifest {
        attributes["Main-Class"] = "uz.disastrouspumpkin.wdb.dummy.MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
}
