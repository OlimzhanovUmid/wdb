plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

subprojects {
    group = "uz.disastrouspumpkin.wdb"
    // wdb-agent overrides this with wdbAgentVersion in its own build (independent cadence).
    // CI drives a release via -PwdbVersion=<tag>; the property default lives in gradle.properties.
    version = providers.gradleProperty("wdbVersion").getOrElse("0.1.0")
}
