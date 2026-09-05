plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":wdb-client"))
    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    // CLI end-to-end tests drive a real agent.
    testImplementation(project(":wdb-agent"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("uz.disastrouspumpkin.wdb.cli.MainKt")
    applicationName = "wdb"
}

// The launcher is `wdb`, but name the release archive `wdb-cli-<ver>.zip` so it's unambiguous
// among the release assets (the extracted dir is wdb-cli-<ver>/, launcher still bin/wdb{,.bat}).
distributions {
    named("main") { distributionBaseName = "wdb-cli" }
}

// Class-Path manifest so `java -jar` (and the jpackage launcher) resolves sibling deps.
tasks.jar {
    manifest {
        attributes["Main-Class"] = "uz.disastrouspumpkin.wdb.cli.MainKt"
        attributes["Class-Path"] = configurations.runtimeClasspath.get().joinToString(" ") { it.name }
    }
}

// Package the CLI as a self-contained wdb.exe (task 1.2), mirroring :wdb-agent:packageAgent.
// Usage: ./gradlew :wdb-cli:packageCli -PjbrHome=<path to full JBR 21 image>
tasks.register<Exec>("packageCli") {
    group = "distribution"
    description = "jpackage app-image (wdb.exe) with a bundled JBR runtime"
    dependsOn("installDist")
    val jbrHome = (project.findProperty("jbrHome") as String?) ?: System.getenv("JBR_HOME")
    val libDir = layout.buildDirectory.dir("install/wdb/lib").get().asFile.absolutePath
    val outDir = layout.buildDirectory.dir("jpackage").get().asFile.absolutePath
    doFirst { require(!jbrHome.isNullOrBlank()) { "set -PjbrHome=<JBR 21 image> or JBR_HOME env var" } }
    commandLine(
        "jpackage", "--type", "app-image", "--name", "wdb",
        "--input", libDir,
        "--main-jar", "wdb-cli-${project.version}.jar",
        "--main-class", "uz.disastrouspumpkin.wdb.cli.MainKt",
        "--runtime-image", jbrHome ?: "JBR_HOME_UNSET",
        "--dest", outDir,
        "--app-version", project.version.toString(),
        "--win-console",
    )
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
