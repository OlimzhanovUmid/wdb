plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":wdb-client"))
    implementation(libs.mcp.sdk.server)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("uz.disastrouspumpkin.wdb.mcp.MainKt")
    applicationName = "wdb-mcp"
}
