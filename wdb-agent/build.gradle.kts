plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.buildconfig)
    application
}

// Single source of truth: the agent version comes from the `wdbAgentVersion` gradle property
// (gradle.properties). It names the built jar, the jpackage app-image, and the installer zip, and
// is generated into uz.disastrouspumpkin.wdb.agent.BuildConfig.AGENT_VERSION for the Kotlin code to read.
version = providers.gradleProperty("wdbAgentVersion").get()

buildConfig {
    packageName("uz.disastrouspumpkin.wdb.agent")
    className("BuildConfig")
    buildConfigField("String", "AGENT_VERSION", "\"${project.version}\"")
}

dependencies {
    implementation(project(":wdb-client"))
    implementation(libs.kotlinx.coroutines.core)
    // JNA is used ONLY by the agent (Job Object, display-awake) — never by wdb-client.
    implementation(libs.jna)
    implementation(libs.jna.platform)
    // Compose Hot Reload orchestration: the agent hosts the server the app's CHR agent
    // connects to and emits reload requests into it (design D1/D2). `core` carries the
    // Future/Broadcast/Either types that appear in orchestration's public API.
    implementation(libs.hot.reload.orchestration)
    implementation(libs.hot.reload.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// Resolve the CHR `-javaagent` jar so packaging can bundle it and the agent can pass
// `-javaagent:<path>` at hot launch (design D3). Kept off the compile classpath.
// hot-reload-agent ships two variants; a -javaagent must be self-contained, so request the
// SHADOWED (fat) variant — it bundles core/orchestration/analysis/javassist, otherwise the
// agent's premain dies with NoClassDefFoundError on its own dependencies.
val hotReloadAgentJar: Configuration = configurations.create("hotReloadAgentJar") {
    isTransitive = false
    attributes {
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.SHADOWED))
    }
}
dependencies { add("hotReloadAgentJar", libs.hot.reload.agent) }

// The CHR runtime jars the hot app needs on ITS classpath so the agent's premain activates the
// development entrypoint (screenshot / semantic-tree / UI-action handlers) — design D1 of
// add-plugin-devtools. Bundled next to the agent and prepended to the hot `-cp`. Non-transitive:
// we want ONLY the CHR jars; compose/kotlin/skiko come from the deployed app's own uber jar.
val devtoolsRuntimeJars: Configuration = configurations.create("devtoolsRuntimeJars") { isTransitive = false }
dependencies {
    val chr = libs.versions.composeHotReload.get()
    // NOTE: deliberately NOT hot-reload-analysis — it depends on ASM (not bundled here), and its
    // presence on the app classpath makes reload resolve analysis from this asm-less copy →
    // NoClassDefFoundError org/objectweb/asm/ClassReader. Reload's redefine uses the shadowed
    // -javaagent's own analysis+asm; the devtools handlers (screenshot/tree/ui-action) don't need it.
    listOf(
        "hot-reload-runtime-jvm", "hot-reload-runtime-api-jvm", "hot-reload-core",
        "hot-reload-orchestration", "hot-reload-annotations-jvm",
        "hot-reload-devtools-api", "hot-reload-devtools",
    ).forEach { add("devtoolsRuntimeJars", "org.jetbrains.compose.hot-reload:$it:$chr") }
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("uz.disastrouspumpkin.wdb.agent.MainKt")
    applicationName = "wdb-agent"
}

// Give the agent jar a Class-Path manifest so `java -jar` (and the jpackage launcher)
// resolves the sibling dependency jars laid out next to it by installDist.
tasks.jar {
    manifest {
        attributes["Main-Class"] = "uz.disastrouspumpkin.wdb.agent.MainKt"
        attributes["Class-Path"] = configurations.runtimeClasspath.get().joinToString(" ") { it.name }
    }
}

val agentVersion: String = project.version.toString()

// Tests drive a real dummy app JAR; give them its path and depend on building it.
// Package a self-contained app-image bundling a JetBrains Runtime (task 4.5).
// Usage: ./gradlew :wdb-agent:packageAgent -PjbrHome=<path to full JBR 21 image>
tasks.register<Exec>("packageAgent") {
    group = "distribution"
    description = "jpackage app-image with a bundled JBR runtime"
    dependsOn("installDist")
    val jbrHome = (project.findProperty("jbrHome") as String?) ?: System.getenv("JBR_HOME")
    val libDir = layout.buildDirectory.dir("install/wdb-agent/lib").get().asFile.absolutePath
    val outDir = layout.buildDirectory.dir("jpackage").get().asFile.absolutePath
    val chrAgentJar = hotReloadAgentJar
    val devtoolsJars = devtoolsRuntimeJars
    doFirst {
        require(!jbrHome.isNullOrBlank()) { "set -PjbrHome=<JBR 21 image> or JBR_HOME env var" }
        // jpackage refuses to overwrite an existing app-image dir; clear any prior build.
        delete("$outDir/wdb-agent")
        // Bundle the CHR `-javaagent` jar next to the agent's jars so hot mode can find it
        // (detectHotReloadAgentJar scans this dir). It stays OFF the app's classpath — it's
        // loaded as a java agent, not a library.
        copy { from(chrAgentJar); into(libDir) }
    }
    // Bundle the CHR runtime jars in a SEPARATE app/devtools dir (not the agent's own classpath):
    // the agent prepends them to the HOT app's -cp so devtools handlers activate (design D1). After
    // jpackage so they land inside the built app-image.
    doLast {
        copy { from(devtoolsJars); into("$outDir/wdb-agent/app/devtools") }
    }
    commandLine(
        "jpackage", "--type", "app-image", "--name", "wdb-agent",
        "--input", libDir,
        "--main-jar", "wdb-agent-$agentVersion.jar",
        "--main-class", "uz.disastrouspumpkin.wdb.agent.MainKt",
        "--runtime-image", jbrHome ?: "JBR_HOME_UNSET",
        "--dest", outDir,
        "--app-version", agentVersion,
        "--win-console",
    )
}

// Wrap the packaged app-image + the operator installer script into one distributable zip.
// The operator copies this to a wall box, unzips, and runs install-agent.ps1 — the only input
// is the wall name. Zip name carries AGENT_VERSION so "latest" is unambiguous on disk.
tasks.register<Zip>("packageAgentInstaller") {
    group = "distribution"
    description = "Bundle the jpackage app-image + install-agent.ps1 into wdb-agent-installer-<ver>.zip"
    dependsOn("packageAgent")

    archiveFileName.set("wdb-agent-installer-$agentVersion.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))

    // Everything under the jpackage app-image, plus the installer script next to wdb-agent.exe,
    // all inside a single top-level folder so unzip yields one tidy directory.
    into("wdb-agent") {
        from(layout.buildDirectory.dir("jpackage/wdb-agent"))
        from(rootProject.file("scripts/install-agent.ps1"))
    }
}

val dummyJar = project(":wdb-dummy-app").let {
    it.layout.buildDirectory.file("libs/wdb-dummy-app-${it.version}.jar")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(":wdb-dummy-app:jar")
    systemProperty("wdb.dummyJar", dummyJar.get().asFile.absolutePath)
    systemProperty("junit.jupiter.execution.timeout.default", "90s")
}
