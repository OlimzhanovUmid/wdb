plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// wdb-client is embedded in a future IntelliJ plugin, so it MUST stay free of any
// third-party networking or native dependency (see design D10). Only the protocol
// module and kotlinx-coroutines are allowed here.
dependencies {
    api(project(":wdb-protocol"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // Fail a wedged socket test instead of hanging the whole build.
    systemProperty("junit.jupiter.execution.timeout.default", "60s")
}
