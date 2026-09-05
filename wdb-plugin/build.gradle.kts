import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
    alias(libs.plugins.compose.compiler)
}

// The IntelliJ Platform Gradle Plugin requires its repositories at the project level.
repositories {
    mavenCentral()
    google()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // wdb-client is Compose-free and net-dependency-free, so it drags in no Compose that could
    // clash with the platform's bundled Compose (design D1). Exclude its kotlinx-coroutines: the
    // plugin MUST use the platform's bundled coroutines, otherwise a second CoroutineScope class
    // lands on the classpath and the platform can't inject the service's (Project, CoroutineScope).
    implementation(project(":wdb-client")) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }

    intellijPlatform {
        // IntelliJ IDEA Community (IC) is no longer published since 2025.3; the IDE now ships as a
        // single distribution resolved via intellijIdea(...).
        intellijIdea(libs.versions.intellijIdea.get())
        // Java debugger (RemoteConfiguration / Remote JVM Debug) for one-click attach (D5).
        bundledPlugin("com.intellij.java")
        // Gradle (External System) to run the configured build task before deploy (D6).
        bundledPlugin("com.intellij.gradle")
        // Compose + Jewel come from the platform's bundled modules — NEVER bundle our own
        // compose-runtime/foundation/skiko (design D1). Duplicate Compose = LinkageError.
        bundledModule("intellij.platform.jewel.foundation")
        bundledModule("intellij.platform.jewel.ui")
        bundledModule("intellij.platform.jewel.ideLafBridge")
        bundledModule("intellij.libraries.compose.foundation.desktop")
        // 2026.1 split the Compose runtime (@Composable/remember/collectAsState) into its own module.
        bundledModule("intellij.libraries.compose.runtime.desktop")
        bundledModule("intellij.libraries.skiko")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = "261.*"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// Disable dynamic plugin auto-reload: the plugin holds non-unloadable state (coroutine scope,
// tool window, discovery sockets), so a hot-swap on rebuild fails with "failed to unload wdb".
// Iterate by restarting runIde instead.
tasks.withType<RunIdeTask>().configureEach {
    systemProperty("idea.auto.reload.plugins", "false")
}
